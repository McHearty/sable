package dev.ryanhcode.sable.mixin;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.SableConfig;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Traces client-side configuration completion handling.
 * Observes handler entry/return and tick activity.
 */
@Mixin(ClientConfigurationPacketListenerImpl.class)
public abstract class ClientConfigurationTraceMixin {

    @Inject(method = "handleConfigurationFinished", at = @At("HEAD"))
    private void sable$traceFinishEntry(
            final ClientboundFinishConfigurationPacket packet,
            final CallbackInfo ci) {
        if (SableConfig.CONFIG_TRACE_CLIENT.get()) {
            sable$log("FINISH_ENTER", "");
        }
    }

    @Inject(method = "handleConfigurationFinished", at = @At("RETURN"))
    private void sable$traceFinishReturn(
            final ClientboundFinishConfigurationPacket packet,
            final CallbackInfo ci) {
        if (SableConfig.CONFIG_TRACE_CLIENT.get()) {
            sable$log("FINISH_RETURN", "");
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void sable$traceTick(final CallbackInfo ci) {
        if (SableConfig.CONFIG_TRACE_CLIENT.get()) {
            sable$log("TICK", "");
        }
    }

    private static void sable$log(
            final String event,
            final String detail) {
        Sable.LOGGER.info(
                "[CONFIG-TRACE] side=CLIENT event={} detail={} thread={}",
                event,
                detail,
                Thread.currentThread().getName());
    }
}