package dev.ryanhcode.sable.network.udp;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.SableClient;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.mixinterface.udp.ServerConnectionListenerExtension;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundSableUDPActivationPacket;
import dev.ryanhcode.sable.network.packets.udp.SableUDPClientboundKeepAlivePacket;
import dev.ryanhcode.sable.util.SableDistUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.local.LocalAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.*;

/**
 * Central UDP server instance that manages authentication, keep-alive heartbeats,
 * and packet routing for all connected clients. One instance per Minecraft server,
 * created lazily when the first UDP channel connection is established.
 *
 * <h3>Authentication Flow</h3>
 * <ol>
 *   <li>Client connects via TCP and receives a UDP activation token via {@link #beginAuthentication}</li>
 *   <li>Client broadcasts the token over UDP (or sends it via TCP if UDP unavailable)</li>
 *   <li>Server receives the token via {@link #receiveAuthenticationPacket} and validates it</li>
 *   <li>On match, server stores the client's UDP address and marks them authenticated</li>
 * </ol>
 *
 * <h3>Keep-Alive & Eviction</h3>
 * Server sends keep-alive packets every {@link #PING_INTERVAL}ms (2.5s) to all
 * authenticated clients. Clients that fail to respond for
 * {@link #MISSED_PINGS_ALLOWED} consecutive intervals (~25s total) are evicted
 * from the authentication map and fall back to TCP.
 *
 * <h3>Thread Safety</h3>
 * Uses {@link WeakHashMap} for auth states to allow garbage collection when
 * connections are closed. All channel writes are submitted to the channel's
 * event loop to ensure thread-safe packet sending.
 */
public class SableUDPServer {
    /** Interval between keep-alive ping broadcasts (milliseconds). */
    public static final long PING_INTERVAL = 2500;
    /** Maximum consecutive missed pings before evicting a client (~25s total). */
    private static final int MISSED_PINGS_ALLOWED = 10;

    private final Channel channel;
    private final Map<Connection, SableUDPAuthenticationState> udpAuthStates;
    private final MinecraftServer server;
    private int pingIndex = 0;

    /**
     * Creates a new UDP server instance bound to the given channel.
     * The channel is typically the server's UDP listener channel.
     */
    public SableUDPServer(final MinecraftServer server, final Channel channel) {
        this.server = server;
        this.channel = channel;
        this.udpAuthStates = new WeakHashMap<>();
    }

    /**
     * Retrieves the current instance of the UDP server from a {@link MinecraftServer}.
     * Returns null if the UDP server hasn't been initialized yet.
     */
    @ApiStatus.Internal
    @Nullable
    public static SableUDPServer getServer(final MinecraftServer server) {
        return (((ServerConnectionListenerExtension) server.getConnection())).sable$getServer();
    }

    /*@Override
    public void flushUDP() {
        if (this.udpChannel.eventLoop().inEventLoop())
            throw new IllegalStateException("Cannot flush from event loop");

        this.udpChannel.eventLoop().execute(() -> {
            this.udpChannel.flush();
        });
    }*/

    /**
     * Checks if UDP packets can be sent to a player.
     * <p>
     * Returns true if: UDP networking is enabled, the player is either the
     * local singleplayer owner or has completed UDP authentication.
     *
     * @param player the player to check
     * @return true if UDP packets can be sent to the player
     */
    public boolean isConnectedTo(final ServerPlayer player) {
        if (!SableConfig.ATTEMPT_UDP_NETWORKING.get()) {
            return false;
        }

        if (player.connection.getRemoteAddress() instanceof LocalAddress) {
            if (player.server.isSingleplayer() && player.server.isSingleplayerOwner(player.getGameProfile()))
                return true;
        }

        final Connection connection = player.connection.connection;
        final SableUDPAuthenticationState authState = this.udpAuthStates.get(connection);
        return authState != null && authState.getState() == SableUDPAuthenticationState.State.AUTHENTICATED;
    }

    /**
     * Sends a UDP packet to a server player, routing through the appropriate
     * channel based on connection type.
     * <p>
     * For local connections (integrated worlds), uses in-memory packet handling.
     * For remote connections, routes through the authenticated UDP channel.
     * <p>
     * Must not be called from the channel's event loop thread.
     *
     * @param player the player to send the packet to
     * @param packet the packet to send
     * @param flush  whether to flush the packet immediately
     * @return true if the packet was sent (or queued for sending)
     */
    public boolean sendUDPPacket(final ServerPlayer player, final SableUDPPacket packet, final boolean flush) {
        if (this.channel.eventLoop().inEventLoop())
            throw new IllegalStateException("Cannot send packet from event loop");

        final Connection connection = player.connection.connection;

        if (connection.getRemoteAddress() instanceof LocalAddress) {
            // We can't turn a local address into an InetSocketAddress, because there's no net communication
            // Let's instead locally send the packet.

            this.sendUDPPacketLocal(packet);
            return true;
        }

        final SableUDPAuthenticationState authenticationState = this.udpAuthStates.get(connection);

        if (authenticationState == null) {
            Sable.LOGGER.error("Attempted to send packet to player \"{}\" without authentication state", player.getName().getString());
            return false;
        }

        final InetSocketAddress inetSocketAddress = authenticationState.getActiveAddress();
        if (inetSocketAddress == null) {
            Sable.LOGGER.error("No UDP address in authentication state for player \"{}\"", player.getName().getString());
            return false;
        }

        this.channel.eventLoop().execute(() -> {
            final AddressedSableUDPPacket envelope = new AddressedSableUDPPacket(packet, inetSocketAddress);
            final ChannelFuture writeFuture = flush ? this.channel.writeAndFlush(envelope) : this.channel.write(envelope);

            writeFuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        });

        return true;
    }

    /**
     * Sends a UDP packet to the locally connected player via in-memory
     * channel handling. Used when the client and server are in the same
     * JVM (integrated/singleplayer world).
     *
     * @param packet the packet to send
     */
    private void sendUDPPacketLocal(final SableUDPPacket packet) {
        final RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), this.server.registryAccess());
        packet.getType().write(buffer, packet);
        final SableUDPPacket decodedPacket = packet.getType().create(buffer);
        SableClient.NETWORK_EVENT_LOOP.tell(() -> decodedPacket.handleClient(SableDistUtil.getClientLevel()));
    }

    /**
     * Begins the UDP authentication process for a player by generating a
     * random token and sending it via TCP. The client must then broadcast
     * this token over UDP to complete authentication.
     * <p>
     * For local connections, authentication is skipped entirely.
     *
     * @param player the player to authenticate
     */
    @ApiStatus.Internal
    public void beginAuthentication(final ServerPlayer player) {
        if (player.connection.getRemoteAddress() instanceof LocalAddress) {
            // No point in sending a UDP activation packet to a local player - local communication will be used anyway
            // instead of the UDP channel
            return;
        }

        // Generate and store a token
        final UUID token = UUID.randomUUID();
        final SableUDPAuthenticationState authState = new SableUDPAuthenticationState(token);

        this.udpAuthStates.put(player.connection.connection, authState);

        // Send the token to the client
        if (SableConfig.ATTEMPT_UDP_NETWORKING.get()) {
            player.connection.send(new ClientboundCustomPayloadPacket(new ClientboundSableUDPActivationPacket(token)));
        }
    }

    /**
     * Validates an incoming authentication token from a client. Searches the
     * authentication map for a matching token, and on match, records the
     * client's UDP address and marks them as authenticated.
     *
     * @param uuid              the UUID token received from the client
     * @param inetSocketAddress the client address that broadcasted the token
     */
    @ApiStatus.Internal
    public void receiveAuthenticationPacket(final UUID uuid, final InetSocketAddress inetSocketAddress) {
        for (final Map.Entry<Connection, SableUDPAuthenticationState> entry : this.udpAuthStates.entrySet()) {
            final SableUDPAuthenticationState state = entry.getValue();

            if (state.isExpectedToken(uuid)) {
                state.assignAddress(inetSocketAddress);
                state.setLastAlivePingIndex(this.pingIndex);
                Sable.LOGGER.info("UDP authentication complete with {}, UDP routing to {}", entry.getKey().getRemoteAddress(), inetSocketAddress);
                return;
            }
        }
    }

    /**
     * Sends keep-alive packets to all authenticated clients and evicts
     * those that have stopped responding. Called periodically (every
     * {@link #PING_INTERVAL}ms) by the server tick system.
     * <p>
     * Eviction criteria:
     * <ul>
     *   <li>Connection is no longer connected</li>
     *   <li>Client hasn't responded to keep-alives for >{@link #MISSED_PINGS_ALLOWED} intervals (~25s)</li>
     * </ul>
     */
    public void sendPings() {
        final Iterator<Map.Entry<Connection, SableUDPAuthenticationState>> iter = this.udpAuthStates.entrySet().iterator();

        while (iter.hasNext()) {
            final Map.Entry<Connection, SableUDPAuthenticationState> entry = iter.next();
            final Connection connection = entry.getKey();
            final SableUDPAuthenticationState state = entry.getValue();

            if (!connection.isConnected()) {
                iter.remove();
                continue;
            }

            if (state.getState() != SableUDPAuthenticationState.State.AUTHENTICATED) {
                continue;
            }

            if (this.pingIndex - state.getLastAlivePingIndex() > MISSED_PINGS_ALLOWED) {
                Sable.LOGGER.warn("UDP connection with {} failed to respond to any keep-alive packets after ~{}ms, kicking them to TCP", connection.getRemoteAddress(), MISSED_PINGS_ALLOWED * PING_INTERVAL);
                iter.remove();
                continue;
            }

            final InetSocketAddress inetSocketAddress = state.getActiveAddress();

            if (inetSocketAddress == null) {
                continue;
            }

            final SableUDPClientboundKeepAlivePacket packet = new SableUDPClientboundKeepAlivePacket();

            this.channel.eventLoop().execute(() -> {
                final AddressedSableUDPPacket envelope = new AddressedSableUDPPacket(packet, inetSocketAddress);
                final ChannelFuture writeFuture = this.channel.writeAndFlush(envelope);

                writeFuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            });
        }

        this.pingIndex ++;
    }

    /**
     * Marks a client as alive by updating their last-responded timestamp.
     * Called when a keep-alive response is received from a client.
     *
     * @param sender the address of the client that responded
     */
    public void receiveAlivePacket(final InetSocketAddress sender) {
        for (final SableUDPAuthenticationState state : this.udpAuthStates.values()) {
            if (state.getState() != SableUDPAuthenticationState.State.AUTHENTICATED) {
                continue;
            }

            if (Objects.equals(state.getActiveAddress(), sender)) {
                state.setLastAlivePingIndex(this.pingIndex);
                return;
            }
        }
    }
}
