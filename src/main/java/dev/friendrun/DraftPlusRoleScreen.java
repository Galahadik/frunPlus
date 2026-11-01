package dev.friendrun;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Драфт+, выбор роли (специализации): каждая роль даёт пассивный бонус к добыче или крафту определённых предметов. */
public final class DraftPlusRoleScreen extends Screen {
    private Ui.R panel;
    private java.util.List<DraftPlus.Role> roles = java.util.List.of();
    private Ui.R[] cards;
    private Button[] buttons;

    public DraftPlusRoleScreen() { super(Component.literal("FriendRun — Драфт+: роли")); }

    private Button make(Ui.R r, Runnable action) {
        Button b = addRenderableWidget(Button.builder(Component.empty(), ignored -> action.run())
            .bounds(r.x(), r.y(), r.w(), r.h()).build());
        Ui.invisible(b);
        return b;
    }

    private static Item iconOf(String roleId) {
        return switch (roleId) {
            case "miner" -> Items.IRON_PICKAXE;
            case "farmer" -> Items.WHEAT;
            case "hunter" -> Items.BOW;
            case "lumberjack" -> Items.IRON_AXE;
            default -> Items.CRAFTING_TABLE;
        };
    }

    @Override
    protected void init() {
        roles = DraftPlus.enabledRoles(FriendRunClient.STATE.dpOffRoles);   // выключенные хостом роли не показываем
        int n = roles.size();
        int pw = Math.min(width - 16, 500), ph = Math.min(height - 8, 210);
        int px = (width - pw) / 2, py = (height - ph) / 2;
        panel = new Ui.R(px, py, pw, ph);
        int cols = pw >= 420 ? n : 3;
        int gap = 4, cw = (pw - 16 - gap * (cols - 1)) / cols;
        int rowsN = (n + cols - 1) / cols, ch = Math.min(110, (ph - 62 - gap * (rowsN - 1)) / rowsN);
        cards = new Ui.R[n];
        buttons = new Button[n];
        for (int i = 0; i < n; i++) {
            final String id = roles.get(i).id();
            cards[i] = new Ui.R(px + 8 + (i % cols) * (cw + gap), py + 48 + (i / cols) * (ch + gap), cw, ch);
            buttons[i] = make(cards[i], () -> FriendRunClient.chooseRole(id));
        }
    }

    @Override
    public void onClose() { Mc.setScreen(null); }   // пока идёт выбор ролей, tick откроет экран снова

    private static List<String> wrap(String text, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (Ui.w(t) > maxW && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(word); }
            else line = new StringBuilder(t);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        RunState s = FriendRunClient.STATE;
        String mine = s.dpRoles.get(FriendRunClient.myName());
        boolean chosen = mine != null || s.dpRoleSent;
        Gfx.rect(g, 0, 0, width, height, 0xB5080D14);
        Ui.panel(g, panel.x(), panel.y(), panel.w(), panel.h());
        Gfx.centered(g, "ДРАФТ+ · ВЫБОР РОЛИ", width / 2, panel.y() + 8, Ui.GOLD);
        Gfx.centered(g, chosen ? "Роль выбрана — ждём остальных (" + s.dpRoles.size() + " / " + s.players.size() + ")"
            : "Выбери специализацию на матч (не выберешь — достанется случайная)", width / 2, panel.y() + 24, chosen ? Ui.GREEN : Ui.TEXT);
        Gfx.centered(g, "Бонус работает в матче: к добыче и крафту предметов своего типа", width / 2, panel.y() + 36, Ui.MUTED);
        for (Button b : buttons) b.active = !chosen;

        super.extractRenderState(g, mx, my, delta);

        for (int i = 0; i < cards.length; i++) {
            DraftPlus.Role r = roles.get(i);
            Ui.R c = cards[i];
            boolean sel = r.id().equals(mine);
            Ui.button(g, c, c.in(mx, my) && !chosen, !chosen || sel, sel ? Ui.Kind.GOLD : Ui.Kind.NORMAL);
            Item icon = iconOf(r.id());
            int ix = c.x() + (c.w() - 16) / 2;
            var st = ItemInfo.stack(icon);
            if (st != null) Gfx.item(g, st, ix, c.y() + 6); else Gfx.itemTexture(g, icon, ix, c.y() + 6, 16);
            int col = sel ? 0xFF1A1200 : Ui.TEXT;
            Gfx.fitted(g, r.name(), c.x() + c.w() / 2, c.y() + 26, c.w() - 8, col);
            int y = c.y() + 40;
            for (String ln : wrap(r.desc(), c.w() - 10)) {
                if (y + 9 > c.y() + c.h() - 2) break;
                Gfx.centered(g, ln, c.x() + c.w() / 2, y, sel ? 0xFF3A2A00 : Ui.MUTED);
                y += 10;
            }
        }
    }
}
