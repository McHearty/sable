package dev.ryanhcode.sable.mixin;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.SableConfig;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Traces server-side configuration completion handling.
 * Observes handler entry/return and tick activity.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationTraceMixin {

    @Inject(method = "handleConfigurationFinished", at = @At("HEAD"))
    private void sable$traceFinishEntry(
            final ServerboundFinishConfigurationPacket packet,
            final CallbackInfo ci) {
        if (SableConfig.CONFIG_TRACE_SERVER.get()) {
            sable$log("FINISH_ENTER");
        }
    }

    @Inject(method = "handleConfigurationFinished", at = @At("RETURN"))
    private void sable$traceFinishReturn(
            final ServerboundFinishConfigurationPacket packet,
            final CallbackInfo ci) {
        if (SableConfig.CONFIG_TRACE_SERVER.get()) {
            sable$log("FINISH_RETURN");
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void sable$traceTick(final CallbackInfo ci) {
        if (SableConfig.CONFIG_TRACE_SERVER.get()) {
            sable$log("TICK");
        }
    }

    private static void sable$log(final String event) {
        Sable.LOGGER.info(
                "[CONFIG-TRACE] side=SERVER event={} thread={}",
                event,
                Thread.currentThread().getName());
    }
}