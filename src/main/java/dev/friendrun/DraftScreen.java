package dev.friendrun;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Экран фазы драфта: на каждом ходе игрок выбирает одну из двух целей. */
public final class DraftScreen extends Screen {
    private Ui.R leftRect, rightRect, panel;
    private Button leftButton, rightButton;

    public DraftScreen() {
        super(Component.literal("FriendRun — драфт целей"));
    }

    private Button make(Ui.R r, Runnable action) {
        Button b = addRenderableWidget(Button.builder(Component.empty(), ignored -> action.run())
            .bounds(r.x(), r.y(), r.w(), r.h()).build());
        Ui.invisible(b);
        return b;
    }

    @Override
    protected void init() {
        int pw = Math.min(width - 20, 480);
        int ph = Math.min(174, height - 20);
        int px = (width - pw) / 2, py = (height - ph) / 2;
        panel = new Ui.R(px, py, pw, ph);
        int bw = (pw - 24) / 2;
        leftRect = new Ui.R(px + 8, py + 62, bw, 88);
        rightRect = new Ui.R(px + 16 + bw, py + 62, bw, 88);
        leftButton = make(leftRect, () -> FriendRunClient.chooseDraft(0));
        rightButton = make(rightRect, () -> FriendRunClient.chooseDraft(1));
    }

    @Override
    public void onClose() {
        // While drafting, the tick handler will reopen this screen if the user closes it.
        Mc.setScreen(null);
    }

    private void drawOption(GuiGraphicsExtractor g, Ui.R r, String encoded, boolean hover, boolean enabled, int index) {
        Ui.button(g, r, hover, enabled, enabled ? Ui.Kind.GREEN : Ui.Kind.NORMAL);
        DraftGoal goal = DraftGoal.parse(encoded);
        if (goal == null) {
            Gfx.centered(g, "Цель недоступна", r.x() + r.w() / 2, r.y() + 40, Ui.MUTED);
            return;
        }
        var icon = goal.icon();
        if (icon != null) {
            int itemX = r.x() + (r.w() - 16) / 2;
            int itemY = r.y() + 7;
            var stack = ItemInfo.stack(icon);
            if (stack != null) Gfx.item(g, stack, itemX, itemY);
            else Gfx.itemTexture(g, icon, itemX, itemY, 16);
        }
        String type = switch (goal.type()) {
            case ITEM -> "ПРЕДМЕТ";
            case QUEST -> "ЗАДАНИЕ";
            case ADVANCEMENT -> "ДОСТИЖЕНИЕ";
        };
        Gfx.centered(g, type, r.x() + r.w() / 2, r.y() + 27, Ui.MUTED);
        Gfx.fitted(g, goal.displayName(), r.x() + r.w() / 2, r.y() + 39, r.w() - 12, Ui.TEXT);
        Gfx.centered(g, "ВЫБРАТЬ " + (index + 1), r.x() + r.w() / 2, r.y() + 65, enabled ? Ui.GOLD : Ui.MUTED);
    }

    private static void outline(GuiGraphicsExtractor g, Ui.R r, int color) {
        Gfx.rect(g, r.x() - 2, r.y() - 2, r.x() + r.w() + 2, r.y(), color);
        Gfx.rect(g, r.x() - 2, r.y() + r.h(), r.x() + r.w() + 2, r.y() + r.h() + 2, color);
        Gfx.rect(g, r.x() - 2, r.y(), r.x(), r.y() + r.h(), color);
        Gfx.rect(g, r.x() + r.w(), r.y(), r.x() + r.w() + 2, r.y() + r.h(), color);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        RunState s = FriendRunClient.STATE;
        // Draft uses a clean, solid dimmed background instead of the shared checker/grid backdrop.
        Gfx.rect(g, 0, 0, width, height, 0xB5080D14);
        Ui.panel(g, panel.x(), panel.y(), panel.w(), panel.h());
        boolean plus = s.gameMode == Game.DRAFT_PLUS;   // Драфт+: те же выборы целей, но после банов и ролей
        Gfx.centered(g, (plus ? "ДРАФТ+ · " : "ДРАФТ · ") + DraftGoal.draftTypeName(FriendRunClient.STATE.draftType), width / 2, panel.y() + 8, Ui.GOLD);
        Gfx.centered(g, "Выбрано " + s.draftPickCount + " / " + (plus ? DraftPlus.CELLS : s.cfgDraftSize * s.cfgDraftSize), width / 2, panel.y() + 23, Ui.TEXT);
        boolean myTurn = FriendRunClient.myName().equals(s.draftTurn);
        boolean canPick = myTurn && !s.draftChoiceSent && s.draftActive;
        String status = canPick ? "Твой ход — выбери одну цель" : "Выбирает: " + (s.draftTurn.isBlank() ? "ожидание" : s.draftTurn);
        Gfx.centered(g, status, width / 2, panel.y() + 39, canPick ? Ui.GREEN : Ui.MUTED);
        drawOption(g, leftRect, s.draftOfferA, leftRect.in(mx, my), canPick, 0);
        drawOption(g, rightRect, s.draftOfferB, rightRect.in(mx, my), canPick, 1);
        if (plus) {
            if (canPick) {
                DraftPlus.reportHover(leftRect.in(mx, my) ? "0" : rightRect.in(mx, my) ? "1" : "");   // остальные видят, на что я навожу
            } else {
                DraftPlus.hoverIdle();
                // Чужой ход: подсвечиваем ту цель, на которую сейчас наводит ходящий игрок.
                if (s.dpHover.equals("0")) outline(g, leftRect, 0xFFFFD700);
                else if (s.dpHover.equals("1")) outline(g, rightRect, 0xFFFFD700);
            }
            Gfx.fitted(g, DraftPlus.actorLine(s), width / 2, panel.y() + panel.h() - 14, panel.w() - 16, canPick ? Ui.GREEN : Ui.TEXT);
        }
        if (leftButton != null) leftButton.active = canPick;
        if (rightButton != null) rightButton.active = canPick;
        super.extractRenderState(g, mx, my, delta);
    }
}
