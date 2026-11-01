package dev.friendrun.mixin;

import dev.friendrun.E4mc;
import dev.friendrun.Pillars;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ловит клиентские сообщения чата: домен *.e4mc.link и итоги раундов карты «Столбики». */
@Mixin(ChatComponent.class)
public abstract class ChatCaptureMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), require = 0)
    private void friendrun$capture(Component message, CallbackInfo ci) {
        E4mc.scan(message.getString());
        Pillars.onChat(message.getString());
    }
}
