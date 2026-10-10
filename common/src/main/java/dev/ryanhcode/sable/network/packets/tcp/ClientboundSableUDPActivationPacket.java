package dev.ryanhcode.sable.network.packets.tcp;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.SableClientConfig;
import dev.ryanhcode.sable.mixinterface.udp.ConnectionExtension;
import dev.ryanhcode.sable.network.packets.udp.SableUDPAuthenticationPacket;
import dev.ryanhcode.sable.network.tcp.SableTCPPacket;
import dev.ryanhcode.sable.network.udp.AddressedSableUDPPacket;
import foundry.veil.api.network.handler.PacketContext;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * TCP packet sent by the server to initiate UDP authentication for a client.
 * Contains a random UUID token that the client must broadcast back over UDP
 * to complete the authentication handshake.
 */
public record ClientboundSableUDPActivationPacket(UUID uuid) implements SableTCPPacket {

    public static final Type<ClientboundSableUDPActivationPacket> TYPE = new Type<>(Sable.sablePath("udp_activation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundSableUDPActivationPacket> CODEC = StreamCodec.of((buf, value) -> value.write(buf), ClientboundSableUDPActivationPacket::read);

    private void write(final FriendlyByteBuf buf) {
        buf.writeUUID(this.uuid);
    }

    private static ClientboundSableUDPActivationPacket read(final FriendlyByteBuf buf) {
        return new ClientboundSableUDPActivationPacket(buf.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Handles the UDP activation packet by sending the authentication token
     * back to the server over UDP. Skips if UDP networking is disabled, the
     * UDP channel isn't available, or the remote address isn't a valid socket.
     */
    @Override
    public void handle(final PacketContext context) {
        if (!SableClientConfig.ATTEMPT_UDP_NETWORKING.get()) {
            Sable.LOGGER.info("[sable-udp] Received UDP authentication request, ignoring due to disabled attempt_udp_networking config");
            return;
        }

        final Connection connection = Minecraft.getInstance().getConnection().getConnection();
        final ConnectionExtension connectionExtension = (ConnectionExtension) connection;
        final Channel channel = connectionExtension.sable$getUDPChannel();

        if (channel == null) {
            Sable.LOGGER.warn("[sable-udp] Received UDP activation packet but no UDP channel is available on this connection; remaining on TCP-only");
            return;
        }

        if (!(connection.getRemoteAddress() instanceof final InetSocketAddress baseAddress)) {
            Sable.LOGGER.warn("[sable-udp] Received UDP activation packet but remote address is not an InetSocketAddress ({}); skipping UDP auth", connection.getRemoteAddress());
            return;
        }

        final InetSocketAddress remoteAddress = new InetSocketAddress(baseAddress.getAddress(), baseAddress.getPort());

        Sable.LOGGER.info("[sable-udp] Received authentication request, sending response over UDP to {}", remoteAddress);

        channel.eventLoop().execute(() -> {
            final SableUDPAuthenticationPacket packet = new SableUDPAuthenticationPacket(this.uuid.toString());

            final AddressedSableUDPPacket envelope = new AddressedSableUDPPacket(packet, remoteAddress);
            final ChannelFuture writeFuture = channel.writeAndFlush(envelope);

            writeFuture.addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    Sable.LOGGER.warn("[sable-udp] Failed to send UDP auth response to {}: {}", remoteAddress, future.cause() != null ? future.cause().toString() : "<no cause>");
                }
            });
        });
    }
}