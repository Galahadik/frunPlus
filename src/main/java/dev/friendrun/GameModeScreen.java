package dev.friendrun;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Отдельный экран выбора режима с краткими объяснениями. */
public final class GameModeScreen extends Screen {
    private final Screen parent;
    private final List<Ui.R> cards = new ArrayList<>();
    private final List<Button> cardButtons = new ArrayList<>();
    private Button back;
    private Ui.R backRect;
    private int cols, cardW, cardH, gap, startX, startY;

    public GameModeScreen(Screen parent) {
        super(Component.literal("FriendRun — выбор режима"));
        this.parent = parent;
    }

    private Button make(Ui.R r, Runnable action) {
        Button b = addRenderableWidget(Button.builder(Component.empty(), x -> action.run())
            .bounds(r.x(), r.y(), r.w(), r.h()).build());
        Ui.invisible(b);
        return b;
    }

    @Override
    protected void init() {
        cards.clear();
        cardButtons.clear();
        int margin = 12;
        cols = width >= 650 ? 3 : 2;
        gap = 8;
        int areaW = Math.min(width - margin * 2, 760);
        cardW = (areaW - gap * (cols - 1)) / cols;
        int rows = (Game.COUNT + cols - 1) / cols;
        int availableCardH = Math.max(height < 340 ? 44 : 52, (height - 76 - (rows - 1) * gap) / rows);
        cardH = Math.min(height < 400 ? 76 : 88, availableCardH);
        int gridH = rows * cardH + (rows - 1) * gap;
        startX = (width - (cardW * cols + gap * (cols - 1))) / 2;
        startY = Math.max(34, Math.min(56, (height - gridH - 30) / 2));

        for (int i = 0; i < Game.COUNT; i++) {
            final int mode = i;
            int col = i % cols, row = i / cols;
            Ui.R r = new Ui.R(startX + col * (cardW + gap), startY + row * (cardH + gap), cardW, cardH);
            cards.add(r);
            cardButtons.add(make(r, () -> {
                RunState s = FriendRunClient.STATE;
                if (!s.amHost || s.phase != RunState.Phase.IDLE) return;
                FriendRunClient.setGameMode(mode);
                // Драфт+: после выбора режима сразу спрашиваем формат (каждый сам за себя / команды) и правила матча.
                Mc.setScreen(mode == Game.DRAFT_PLUS ? new DraftPlusSetupScreen(parent) : parent);
            }));
        }

        backRect = new Ui.R(width / 2 - 54, height - 30, 108, 20);
        back = make(backRect, () -> Mc.setScreen(parent));
    }

    @Override
    public void onClose() { Mc.setScreen(parent); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        RunState s = FriendRunClient.STATE;
        boolean canChoose = s.amHost && s.phase == RunState.Phase.IDLE;

        // Единый фон и заголовок
        Ui.backdrop(g, width, height);
        int panelW = Math.min(width - 12, 790);
        int panelX = (width - panelW) / 2;
        Ui.panel(g, panelX, 8, panelW, height - 16);
        Icons.draw(g, Icons.DICE, panelX + 14, 20, 2, Ui.GOLD);
        Ui.bold(g, "Выбор режима", panelX + 36, 18, Ui.GOLD);
        Gfx.text(g, canChoose ? "Выбери, во что будете играть" : "Режим может менять только хост до начала раунда",
            panelX + 14, 34, Ui.MUTED);

        for (int i = 0; i < cards.size(); i++) {
            Ui.R r = cards.get(i);
            boolean selected = s.gameMode == i;
            boolean hover = r.in(mx, my);
            boolean active = canChoose;
            Ui.Kind kind = selected ? Ui.Kind.GREEN : Ui.Kind.NORMAL;
            Ui.button(g, r, hover, active, kind);
            if (selected) {
                Gfx.rect(g, r.x() + 1, r.y() + 1, r.x() + 4, r.y() + r.h() - 1, Ui.GREEN);
                Icons.draw(g, Icons.CHECK, r.x() + r.w() - 15, r.y() + 8, 1, Ui.GREEN);
            }
            int titleColor = active ? Ui.TEXT : Ui.MUTED;
            int iconColor = selected ? Ui.GREEN : (active ? Ui.GOLD : 0xFF626D7D);
            Icons.draw(g, Icons.mode(i), r.x() + 10, r.y() + 10, 2, iconColor);
            Gfx.text(g, Game.name(i), r.x() + 30, r.y() + 10, selected ? Ui.GREEN : titleColor);
            String desc = Game.desc(i);
            // Оставляем слева место под иконку, чтобы текст не пересекался с ней на телефонах.
            int maxW = r.w() - 40;
            while (!desc.isEmpty() && Ui.w(desc) > maxW) {
                int cut = desc.lastIndexOf(' ');
                if (cut < 1) { desc = desc.substring(0, Math.max(1, desc.length() - 2)) + "…"; break; }
                String first = desc.substring(0, cut);
                if (Ui.w(first) <= maxW) { desc = first + "…"; break; }
                desc = first;
            }
            Gfx.text(g, desc, r.x() + 30, r.y() + 27, active ? Ui.MUTED : 0xFF626D7D);
            if (selected) Gfx.text(g, "ТЕКУЩИЙ", r.x() + 10, r.y() + r.h() - 16, Ui.GREEN);
            else if (active) Gfx.text(g, "Нажми для выбора", r.x() + 10, r.y() + r.h() - 16, Ui.MUTED);
        }

        super.extractRenderState(g, mx, my, delta);
        Ui.button(g, backRect, backRect.in(mx, my), true, Ui.Kind.NORMAL);
        Gfx.centered(g, "Назад", backRect.x() + backRect.w() / 2, backRect.cy() - 4, Ui.TEXT);
        for (int i = 0; i < cardButtons.size(); i++) cardButtons.get(i).active = canChoose;
    }
}
