package dev.friendrun.mixin;

import dev.friendrun.DraftPlusBonus;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Хук крафта для «Драфта+» (бонус роли «Ремесленник» и событие «Двойной крафт»).
 * require = 0: если в этой версии игры сигнатура другая, мод не упадёт — бонус крафта просто не сработает.
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {
    @Inject(method = "onTake(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("TAIL"), require = 0)
    private void friendrun$bonusCraft(Player player, ItemStack stack, CallbackInfo ci) {
        try { DraftPlusBonus.onCraft(player, stack); } catch (Throwable ignored) { }
    }
}
