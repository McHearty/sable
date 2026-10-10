package dev.ryanhcode.sable.mixin.udp;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.mixinterface.udp.ServerConnectionListenerExtension;
import dev.ryanhcode.sable.network.udp.SableUDPPacket;
import dev.ryanhcode.sable.network.udp.SableUDPServer;
import dev.ryanhcode.sable.network.udp.handler.SableUDPChannelHandlerServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 * Mixin that establishes the server-side UDP listener for receiving
 * authentication packets, keep-alive responses, and client UDP traffic.
 * Injects into {@link ServerConnectionListener} to create the UDP channel
 * alongside the primary TCP listener.
 *
 * <h3>Lifecycle Hooks</h3>
 * <ul>
 *   <li>{@code startTcpServerListener} (HEAD): Creates UDP server channel</li>
 *   <li>{@code startMemoryChannel} (TAIL): Creates local UDP channel for integrated worlds</li>
 *   <li>{@code stop} (TAIL): Clears the SableUDPServer reference</li>
 * </ul>
 *
 * <h3>Transport Selection</h3>
 * On Linux with Epoll available and when {@link MinecraftServer#isEpollEnabled()}
 * returns true, uses {@link EpollDatagramChannel}. Otherwise falls back to
 * {@link NioDatagramChannel}.
 *
 * <h3>SableUDPServer Management</h3>
 * The {@link SableUDPServer} instance is created lazily when the first UDP
 * channel connection is established (via {@code sable$setupUDPServer}). This
 * allows the UDP server to manage authentication state and packet routing
 * for all connected clients through a single central instance.
 */
@Mixin(ServerConnectionListener.class)
public class ServerConnectionListenerMixin implements ServerConnectionListenerExtension {

    @Shadow
    @Final
    private List<ChannelFuture> channels;

    @Shadow
    @Final
    private MinecraftServer server;

    @Unique
    private SableUDPServer sable$server = null;

    /**
     * Creates the UDP server channel when the TCP server listener starts.
     * Injected at HEAD of startTcpServerListener so the UDP channel is
     * ready before the TCP listener accepts connections.
     */
    @Inject(method = "startTcpServerListener", at = @At("HEAD"))
    private void sable$startTcpServerListener(final InetAddress inetAddress, final int port, final CallbackInfo ci) {
        if (SableConfig.DISABLE_UDP_PIPELINE.get()) {
            return;
        }

        synchronized (this.channels) {
            final Class<? extends Channel> channelClass;
            final EventLoopGroup eventLoopGroup;

            if (Epoll.isAvailable() && this.server.isEpollEnabled()) {
                channelClass = EpollDatagramChannel.class;
                eventLoopGroup = ServerConnectionListener.SERVER_EPOLL_EVENT_GROUP.get();
            } else {
                channelClass = NioDatagramChannel.class;
                eventLoopGroup = ServerConnectionListener.SERVER_EVENT_GROUP.get();
            }

            Sable.LOGGER.info("Adding UDP server channel future");

            this.channels.add(new Bootstrap()
                    .channel(channelClass)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(final Channel channel) {
                            SableUDPPacket.configureSerialization(channel.pipeline(), PacketFlow.SERVERBOUND, false, null);
                            ServerConnectionListenerMixin.this.sable$setupChannel(channel);
                        }
                    })
                    .group(eventLoopGroup)
                    .localAddress(inetAddress, port)
                    .bind()
                    .syncUninterruptibly());
        }
    }

    /**
     * Creates the local UDP server channel for integrated/singleplayer worlds.
     * Uses LocalServerChannel with in-memory packet handling instead of
     * actual UDP sockets.
     */
    @Inject(method = "startMemoryChannel", at = @At("TAIL"))
    private void sable$startMemoryChannel(final CallbackInfoReturnable<SocketAddress> cir) {
        if (SableConfig.DISABLE_UDP_PIPELINE.get()) {
            return;
        }

        synchronized (this.channels) {
            Sable.LOGGER.info("Adding local UDP server channel future");

            this.channels.add(new Bootstrap()
                    .channel(LocalServerChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(final Channel channel) {
                            SableUDPPacket.configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND);
                            ServerConnectionListenerMixin.this.sable$setupChannel(channel);
                        }
                    })
                    .group(ServerConnectionListener.SERVER_EVENT_GROUP.get())
                    .localAddress(LocalAddress.ANY)
                    .bind()
                    .syncUninterruptibly());
        }
    }

    /**
     * Adds the server-side UDP channel handler to the pipeline. This handler
     * receives incoming UDP packets and routes them to the SableUDPServer
     * for authentication, keep-alive, and data processing.
     */
    @Unique
    private void sable$setupChannel(final Channel channel) {
        final ChannelPipeline pipeline = channel.pipeline();

        pipeline.addLast(new SableUDPChannelHandlerServer(this.server, (ServerConnectionListener) (Object) this));
    }

    /**
     * Clears the SableUDPServer reference when the server stops. The actual
     * channel teardown is handled by the channels list during stop().
     */
    @Inject(method = "stop", at = @At("TAIL"))
    private void sable$stop(final CallbackInfo ci) {
        this.sable$server = null;
    }

    /**
     * Creates and stores the SableUDPServer instance. Called when the first
     * UDP channel connection is established.
     */
    @Override
    public void sable$setupUDPServer(final Channel channel) {
        this.sable$server = new SableUDPServer(this.server, channel);
    }

    /**
     * Returns the currently active SableUDPServer instance, or null if not yet created.
     */
    @Override
    @Nullable
    public SableUDPServer sable$getServer() {
        return this.sable$server;
    }
}
