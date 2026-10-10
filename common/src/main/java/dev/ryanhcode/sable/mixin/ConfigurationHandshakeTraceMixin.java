package dev.ryanhcode.sable.mixin;

import dev.ryanhcode.sable.Sable;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporary diagnostic instrumentation for the Minecraft login and
 * configuration packet exchange. This mixin observes traffic to identify
 * where the "Client disconnected" timeout occurs during the configuration
 * phase.
 *
 * <p>This mixin must only observe traffic; it must not modify packet
 * handling, cancel packets, or alter timeout values.</p>
 */
@Mixin(Connection.class)
public abstract class ConfigurationHandshakeTraceMixin {

    @Unique
    private volatile long sable$configurationTraceStartNanos;

    /** Enable/disable config handshake tracing (default: true for diagnosis). */
    @Unique
    private static final boolean sable$ENABLE_CONFIG_TRACE = true;

    /**
     * Observe outgoing packets after Connection.send(...) has routed them
     * into its internal send path.
     */
    @Inject(method = "send", at = @At("HEAD"))
    private void sable$traceOutgoingPacket(final Packet<?> packet, final CallbackInfo ci) {
        if (!sable$ENABLE_CONFIG_TRACE) {
            return;
        }

        final Connection connection = (Connection) (Object) this;
        final PacketListener listener = connection.getPacketListener();

        if (!sable$shouldTrace(packet, listener)) {
            return;
        }

        sable$trace("SEND", packet, listener);
    }

    /**
     * Observe incoming packets immediately before Minecraft dispatches
     * them through Connection.channelRead0(...).
     */
    @Inject(method = "channelRead0", at = @At("HEAD"))
    private void sable$traceIncomingPacket(final ChannelHandlerContext context, final Packet<?> packet, final CallbackInfo ci) {
        if (!sable$ENABLE_CONFIG_TRACE) {
            return;
        }

        final Connection connection = (Connection) (Object) this;
        final PacketListener listener = connection.getPacketListener();

        if (!sable$shouldTrace(packet, listener)) {
            return;
        }

        sable$trace("RECEIVE_BEGIN", packet, listener);
    }

    /**
     * Marks return from Connection.channelRead0(...). This is not proof
     * that a handler scheduled on another thread has completed.
     */
    @Inject(method = "channelRead0", at = @At("TAIL"))
    private void sable$traceIncomingDispatchReturn(final ChannelHandlerContext context, final Packet<?> packet, final CallbackInfo ci) {
        if (!sable$ENABLE_CONFIG_TRACE) {
            return;
        }

        final Connection connection = (Connection) (Object) this;
        final PacketListener listener = connection.getPacketListener();

        if (!sable$shouldTrace(packet, listener)) {
            return;
        }

        sable$trace("RECEIVE_DISPATCH_RETURN", packet, listener);
    }

    @Unique
    private static boolean sable$shouldTrace(final Packet<?> packet, @Nullable final PacketListener listener) {
        final String packetClass = packet.getClass().getName();

        if (packetClass.contains(".protocol.configuration.")
                || packetClass.contains(".protocol.login.")) {
            return true;
        }

        return listener != null
                && listener.getClass().getName().contains("Configuration");
    }

    @Unique
    private void sable$trace(final String event, final Packet<?> packet, @Nullable final PacketListener listener) {
        final long now = System.nanoTime();

        if (this.sable$configurationTraceStartNanos == 0L) {
            this.sable$configurationTraceStartNanos = now;
        }

        final long elapsedMillis =
                (now - this.sable$configurationTraceStartNanos) / 1_000_000L;

        final String listenerName = listener == null
                ? "<none>"
                : listener.getClass().getName();

        Sable.LOGGER.info(
                "[CONFIG-TRACE] elapsedMs={} event={} packet={} listener={} thread={}",
                elapsedMillis,
                event,
                packet.getClass().getName(),
                listenerName,
                Thread.currentThread().getName()
        );
    }
}