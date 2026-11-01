package dev.friendrun.mixin;

import dev.friendrun.FriendRunClient;
import dev.friendrun.LobbyMenuScreen;
import dev.friendrun.Mc;
import dev.friendrun.LobbyRoomScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Кнопка FriendRun в левом верхнем углу главного меню. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) { super(title); }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void friendrun$addButton(CallbackInfo ci) {
        this.addRenderableWidget(Button.builder(Component.literal("FRun"), b -> {
            if (FriendRunClient.inLobby()) Mc.setScreen(new LobbyRoomScreen(this));
            else Mc.setScreen(new LobbyMenuScreen(this));
        }).bounds(6, 6, 90, 20).build());
    }
}
