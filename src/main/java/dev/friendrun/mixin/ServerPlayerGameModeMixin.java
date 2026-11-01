package dev.friendrun.mixin;

import dev.friendrun.FriendRunClient;
import dev.friendrun.RunState;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces the single-speedrun Survival-only rule on the integrated server.
 * Other FriendRun modes retain their existing Creative-mode restriction.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Inject(method = "changeGameModeForPlayer(Lnet/minecraft/world/level/GameType;)Z",
            at = @At("HEAD"), cancellable = true)
    private void friendrun$denyCreativeDuringRun(GameType requested, CallbackInfoReturnable<Boolean> cir) {
        if (!FriendRunClient.inLobby()) return;
        if (FriendRunClient.STATE.gameMode == dev.friendrun.Game.SINGLE_SPEEDRUN
                && requested != GameType.SURVIVAL) {
            cir.setReturnValue(false);
            return;
        }
        if (requested == GameType.CREATIVE) cir.setReturnValue(false);
    }

    /** Corrects a player who was already Creative when the lobby was opened. */
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void friendrun$correctExistingCreative(CallbackInfo ci) {
        if (!FriendRunClient.inLobby()) return;

        ServerPlayerGameMode self = (ServerPlayerGameMode) (Object) this;
        GameType current = self.getGameModeForPlayer();
        if (FriendRunClient.STATE.gameMode == dev.friendrun.Game.SINGLE_SPEEDRUN) {
            if (current != GameType.SURVIVAL) self.changeGameModeForPlayer(GameType.SURVIVAL);
            return;
        }
        if (current != GameType.CREATIVE) return;
        RunState.Phase phase = FriendRunClient.STATE.phase;
        GameType enforced = (phase == RunState.Phase.COUNTDOWN) ? GameType.ADVENTURE : GameType.SURVIVAL;
        self.changeGameModeForPlayer(enforced);
    }
}
