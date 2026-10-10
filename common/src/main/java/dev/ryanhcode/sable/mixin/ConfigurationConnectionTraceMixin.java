package dev.ryanhcode.sable.mixin;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.SableConfig;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Traces configuration/login packet exchange at the connection level.
 * Observes outgoing writes, incoming dispatch, and listener transitions.
 * Observational only; does not alter packet handling.
 */
@Mixin(Connection.class)
public abstract class ConfigurationConnectionTraceMixin {

    @Inject(method = "doSendPacket", at = @At("HEAD"))
    private void sable$traceOutgoing(
            final Packet<?> packet,
            @Nullable final PacketSendListener callbacks,
            final boolean flush,
            final CallbackInfo ci) {
        if (SableConfig.CONFIG_TRACE_CONNECTION.get() && sable$shouldTrace(packet)) {
            sable$log("SEND_ATTEMPT", packet.getClass().getName(),
                    ((Connection) (Object) this).getPacketListener());
        }
    }

    @Inject(method = "channelRead0", at = @At("HEAD"))
    private void sable$traceIncoming(
            final ChannelHandlerContext context,
            final Packet<?> packet,
            final CallbackInfo ci) {
        if (SableConfig.CONFIG_TRACE_CONNECTION.get() && sable$shouldTrace(packet)) {
            sable$log("RECEIVE", packet.getClass().getName(),
                    ((Connection) (Object) this).getPacketListener());
        }
    }

    @Inject(method = "setupInboundProtocol", at = @At("HEAD"))
    private void sable$traceListenerTransition(
            final ProtocolInfo<?> protocolInfo,
            final PacketListener listener,
            final CallbackInfo ci) {
        if (SableConfig.CONFIG_TRACE_CONNECTION.get()) {
            sable$log("INBOUND_PROTOCOL_TRANSITION",
                    "protocol=" + (protocolInfo == null ? "<null>" : protocolInfo.id()) + ", listener=" + listener.getClass().getName(),
                    listener);
        }
    }

    private static boolean sable$shouldTrace(final Packet<?> packet) {
        final String name = packet.getClass().getName();
        return name.contains(".protocol.login.")
                || name.contains(".protocol.configuration.");
    }

    private static void sable$log(
            final String event,
            final String detail,
            @Nullable final PacketListener listener) {
        Sable.LOGGER.info(
                "[CONFIG-TRACE] event={} detail={} listener={} thread={}",
                event,
                detail,
                listener == null ? "<none>" : listener.getClass().getName(),
                Thread.currentThread().getName());
    }
}