package dev.friendrun;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Драфт+, фаза банов: игроки по очереди банят цели, которые исключаются из пула драфта.
 * В свой ход — выбрать цель в списке (есть поиск) и нажать «Забанить»; в чужой ход — смотреть.
 */
public final class DraftPlusBanScreen extends Screen {
    private static final int ROW = 20;

    private record Entry(String enc, DraftGoal goal, String name, String key) {}

    private List<Entry> all = List.of(), shown = List.of();
    private String lastQuery, lastBanned = "", selected = "";
    private int scroll, px, py, pw, ph, ix, iw, listX, listY, listW, rows, cols, colW;
    private Ui.R rSearch, rBan, rUp, rDown;
    private EditBox search;
    private Button bBan, bUp, bDown;
    private Button[] slots;

    public DraftPlusBanScreen() { super(Component.literal("FriendRun — Драфт+: баны")); }

    private Button make(Ui.R r, Runnable action) {
        Button b = addRenderableWidget(Button.builder(Component.empty(), ignored -> action.run())
            .bounds(r.x(), r.y(), r.w(), r.h()).build());
        Ui.invisible(b);
        return b;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, 480);
        ph = Math.min(height - 8, 340);
        px = (width - pw) / 2;
        py = Math.max(4, (height - ph) / 2);
        ix = px + 10;
        iw = pw - 20;
        listX = ix;
        listW = iw - 16;
        listY = py + 78;
        int bottom = py + ph - 8 - 24;
        rows = Math.max(3, (bottom - listY) / ROW);
        cols = listW >= 380 ? 2 : 1;
        colW = (listW - (cols - 1) * 4) / cols;
        rSearch = new Ui.R(ix, py + 54, iw, 18);
        rBan = new Ui.R(ix + iw - 130, py + ph - 8 - 20, 130, 18);
        rUp = new Ui.R(listX + listW + 3, listY, 13, 13);
        rDown = new Ui.R(listX + listW + 3, listY + rows * ROW - 13, 13, 13);

        search = new EditBox(font, rSearch.x() + 8, rSearch.y() + 5, rSearch.w() - 16, 12, Component.literal("Поиск"));
        search.setHint(Component.literal("Поиск цели…"));
        search.setMaxLength(40);
        Ui.noBorder(search);
        addRenderableWidget(search);

        bBan = make(rBan, () -> {
            if (!selected.isEmpty() && myTurn()) { FriendRunClient.chooseBan(selected); selected = ""; }
        });
        bUp = make(rUp, () -> scrollBy(-Math.max(1, rows / 2)));
        bDown = make(rDown, () -> scrollBy(Math.max(1, rows / 2)));
        slots = new Button[rows * cols];
        for (int i = 0; i < slots.length; i++) {
            final int slot = i;
            slots[i] = make(new Ui.R(listX + (i % cols) * (colW + 4), listY + (i / cols) * ROW, colW, ROW - 2), () -> click(slot));
        }

        List<Entry> l = new ArrayList<>();
        for (String enc : DraftPlus.candidates(FriendRunClient.STATE.draftType)) {
            DraftGoal g = DraftGoal.parse(enc);
            if (g == null) continue;
            String n = g.displayName();
            l.add(new Entry(enc, g, n, (n + " " + g.id().replace('_', ' ')).toLowerCase(Locale.ROOT)));
        }
        l.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
        all = l;
        lastQuery = null;
    }

    private boolean myTurn() {
        RunState s = FriendRunClient.STATE;
        return FriendRunClient.myName().equals(s.dpBanTurn) && s.dpBanSentIndex != DraftPlus.bannedCount(s);
    }

    private void click(int slot) {
        int idx = (scroll + slot / cols) * cols + slot % cols;
        if (idx < 0 || idx >= shown.size() || !myTurn()) return;
        String enc = shown.get(idx).enc();
        selected = selected.equals(enc) ? "" : enc;
        DraftPlus.reportHover(selected);   // остальные игроки видят мой выбор сразу
    }

    private void scrollBy(int d) {
        int max = Math.max(0, (shown.size() + cols - 1) / cols - rows);
        scroll = Math.max(0, Math.min(max, scroll + d));
    }

    /** Колесо мыши (без @Override: если сигнатура в версии игры другая — мод всё равно соберётся). */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollBy(scrollY > 0 ? -3 : 3);
        return true;
    }

    @Override
    public void onClose() { Mc.setScreen(null); }   // пока идут баны, tick откроет экран снова

    private void refilter(RunState s) {
        Set<String> banned = new HashSet<>(List.of(s.dpBannedCsv.isEmpty() ? new String[0] : s.dpBannedCsv.split(",", -1)));
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        String[] toks = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (banned.contains(e.enc())) continue;
            boolean ok = true;
            for (String t : toks) if (!e.key().contains(t)) { ok = false; break; }
            if (ok) out.add(e);
        }
        shown = out;
        lastQuery = search.getValue();
        lastBanned = s.dpBannedCsv;
        if (!selected.isEmpty() && banned.contains(selected)) selected = "";
        scrollBy(0);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        RunState s = FriendRunClient.STATE;
        if (lastQuery == null || !search.getValue().equals(lastQuery) || !s.dpBannedCsv.equals(lastBanned)) refilter(s);
        boolean mine = myTurn();
        int done = DraftPlus.bannedCount(s);
        if (!mine) DraftPlus.hoverIdle();
        String watched = mine ? selected : s.dpHover;   // что выбрал ходящий игрок

        for (int i = 0; i < slots.length; i++) slots[i].visible = (scroll + i / cols) * cols + i % cols < shown.size();
        bBan.active = mine && !selected.isEmpty();

        Gfx.rect(g, 0, 0, width, height, 0xB5080D14);
        Ui.panel(g, px, py, pw, ph);
        Gfx.centered(g, "ДРАФТ+ · ФАЗА БАНОВ", width / 2, py + 8, Ui.GOLD);
        Gfx.centered(g, "Забанено " + done + " / " + s.dpBanTotal + " · по " + s.dpBans + " на игрока", width / 2, py + 22, Ui.TEXT);
        Gfx.centered(g, mine ? "Твой ход — выбери цель и нажми «Забанить»" : "Банит: " + (s.dpBanTurn.isBlank() ? "ожидание" : s.dpBanTurn),
            width / 2, py + 36, mine ? Ui.GREEN : Ui.MUTED);
        Ui.frame(g, rSearch.x(), rSearch.y(), rSearch.w(), rSearch.h(), Ui.SECTION, Ui.LINE);

        super.extractRenderState(g, mx, my, delta);

        if (shown.isEmpty()) Gfx.centered(g, "Ничего не найдено", listX + listW / 2, listY + 20, Ui.MUTED);
        for (int i = 0; i < slots.length; i++) {
            int idx = (scroll + i / cols) * cols + i % cols;
            if (idx >= shown.size()) continue;
            Entry e = shown.get(idx);
            Ui.R rr = new Ui.R(listX + (i % cols) * (colW + 4), listY + (i / cols) * ROW, colW, ROW - 2);
            boolean sel = e.enc().equals(selected);
            boolean watchedRow = !mine && !watched.isEmpty() && e.enc().equals(watched);
            Ui.button(g, rr, rr.in(mx, my) && mine, true, sel || watchedRow ? Ui.Kind.GOLD : Ui.Kind.NORMAL);
            var icon = e.goal().icon();
            if (icon != null) {
                var st = ItemInfo.stack(icon);
                if (st != null) Gfx.item(g, st, rr.x() + 3, rr.y() + 1); else Gfx.itemTexture(g, icon, rr.x() + 3, rr.y() + 1, 16);
            }
            int rar = DraftPlus.rarity(e.enc());
            String tag = rar >= 2 ? "редкая" : rar == 1 ? "ключевая" : "";
            int maxW = colW - 28 - (tag.isEmpty() ? 6 : Ui.w(tag) + 10);
            Gfx.text(g, Ui.trunc(e.name(), maxW), rr.x() + 24, rr.y() + 5, sel || watchedRow ? 0xFF1A1200 : Ui.TEXT);
            if (!tag.isEmpty()) Ui.right(g, tag, rr.x() + rr.w() - 5, rr.y() + 5, rar >= 2 ? Ui.ORANGE : Ui.MUTED);
        }
        Ui.scrollbar(g, rUp.x(), rUp.y(), rows * ROW, scroll, (shown.size() + cols - 1) / cols, rows);
        Ui.button(g, rUp, rUp.in(mx, my), true, Ui.Kind.NORMAL);
        Ui.button(g, rDown, rDown.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.TRI_UP, rUp.x() + 3, rUp.y() + 5, 1, Ui.MUTED);
        Icons.draw(g, Icons.TRI_DOWN, rDown.x() + 3, rDown.y() + 4, 1, Ui.MUTED);

        // Внизу: чей ход и что он сейчас выбирает (видно всем, даже если цели нет в вашем списке/поиске).
        DraftGoal spot = watched.isEmpty() ? null : DraftGoal.parse(watched);
        String line = DraftPlus.actorLine(s) + (spot == null ? "" : ": " + spot.displayName());
        Gfx.text(g, Ui.trunc(line, iw - 140), ix, py + ph - 8 - 15, mine ? Ui.GREEN : Ui.TEXT);
        Ui.button(g, rBan, rBan.in(mx, my) && bBan.active, bBan.active, Ui.Kind.GREEN);
        Gfx.centered(g, "Забанить", rBan.x() + rBan.w() / 2, rBan.y() + 5, bBan.active ? Ui.TEXT : Ui.MUTED);
    }
}
