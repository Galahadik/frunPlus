package dev.friendrun;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * Настройки мода: вкладки «Предметы» (включить/выключить любой предмет, поиск, массовые действия),
 * «Усложнения» (все усложнения с описанием) и «Прочее» (правила пула и личные параметры).
 * Устроен как остальные экраны мода: невидимые стандартные кнопки + собственная отрисовка поверх.
 */
public final class SettingsScreen extends Screen {
    private static final int ROW_I = 20, ROW_C = 26;
    private static final String[] TABS = {"Предметы", "Усложнения", "Прочее"};

    private record Entry(Item item, String id, String name, String key, boolean hard) {}

    private static List<Entry> allCache;

    private final Screen parent;
    private int tab;
    private int filterMode;            // 0 — все, 1 — только включённые, 2 — только выключенные
    private int scrollI, scrollC;
    private String queryText = "";
    private String flash = "";
    private long flashUntil;

    private List<Entry> shown = List.of();
    private int totalItems, onItems;
    private String lastQuery;
    private int lastVersion = -1, lastFilter = -1;

    // раскладка
    private int px, py, pw, ph, ix, iw, listX, listW, listY, listH, cols, colW, rowsI, rowsC;
    private Ui.R rDone, rSearch, rFilter, rOn, rOff, rAllOn, rAllOff, rUp, rDown, rReset;
    private final Ui.R[] rTab = new Ui.R[3], rMisc = new Ui.R[4];
    private int miscLabel1Y, miscLabel2Y;

    private EditBox search;
    private Button bDone, bUp, bDown, bFilter, bOn, bOff, bAllOn, bAllOff, bReset;
    private final Button[] bTab = new Button[3], bMisc = new Button[4];
    private Button[] itemSlots, chaosSlots;

    public SettingsScreen(Screen parent) {
        super(Component.literal("FRun: настройки"));
        this.parent = parent;
    }

    private Button make(Ui.R r, Runnable action) {
        Button b = addRenderableWidget(Button.builder(Component.empty(), x -> action.run())
            .bounds(r.x(), r.y(), r.w(), r.h()).build());
        Ui.invisible(b);
        return b;
    }

    private static List<Entry> all() {
        if (allCache == null) {
            List<Entry> l = new ArrayList<>();
            for (Item it : ItemPicker.baseItems()) {
                String id = ItemPicker.idOf(it), name = ItemInfo.name(it);
                l.add(new Entry(it, id, name, (name + " " + id.replace('_', ' ')).toLowerCase(Locale.ROOT), ItemPicker.isHard(id)));
            }
            l.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
            allCache = l;
        }
        return allCache;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, 470);
        ph = Math.min(height - 8, 340);
        px = (width - pw) / 2;
        py = Math.max(4, (height - ph) / 2);
        ix = px + 10;
        iw = pw - 20;
        int y0 = py + 62;

        rDone = new Ui.R(px + pw - 10 - 64, py + 6, 64, 18);
        int tw = (iw - 8) / 3;
        for (int i = 0; i < 3; i++) rTab[i] = new Ui.R(ix + i * (tw + 4), py + 40, tw, 18);

        // вкладка «Предметы»
        rFilter = new Ui.R(ix + iw - 110, y0, 110, 18);
        rSearch = new Ui.R(ix, y0, iw - 114, 18);
        int half = (iw - 4) / 2;
        rOn = new Ui.R(ix, y0 + 22, half, 16);
        rOff = new Ui.R(ix + half + 4, y0 + 22, iw - half - 4, 16);
        // вкладка «Усложнения»
        rAllOn = new Ui.R(ix, y0, half, 18);
        rAllOff = new Ui.R(ix + half + 4, y0, iw - half - 4, 18);

        // общий список
        listX = ix;
        listW = iw - 16;
        listY = y0 + 54;
        listH = Math.max(ROW_I * 3, py + ph - 8 - listY);
        cols = listW >= 380 ? 2 : 1;
        colW = (listW - (cols - 1) * 4) / cols;
        rowsI = Math.max(3, listH / ROW_I);
        rowsC = Math.max(2, listH / ROW_C);
        rUp = new Ui.R(listX + listW + 3, listY, 13, 13);
        rDown = new Ui.R(listX + listW + 3, listY + listH - 13, 13, 13);

        // вкладка «Прочее»
        miscLabel1Y = y0 + 2;
        rMisc[0] = new Ui.R(ix, y0 + 14, iw, 28);
        rMisc[1] = new Ui.R(ix, y0 + 46, iw, 28);
        miscLabel2Y = y0 + 82;
        rMisc[2] = new Ui.R(ix, y0 + 96, iw, 28);
        rMisc[3] = new Ui.R(ix, y0 + 128, iw, 28);
        rReset = new Ui.R(ix, y0 + 164, 170, 18);

        // виджеты
        search = new EditBox(font, rSearch.x() + 8, rSearch.y() + 5, rSearch.w() - 16, 12, Component.literal("Поиск"));
        search.setHint(Component.literal("Поиск предмета…"));
        search.setMaxLength(40);
        search.setValue(queryText);
        Ui.noBorder(search);
        addRenderableWidget(search);

        bDone = make(rDone, this::onClose);
        for (int i = 0; i < 3; i++) { final int n = i; bTab[i] = make(rTab[i], () -> setTab(n)); }
        bFilter = make(rFilter, () -> { filterMode = (filterMode + 1) % 3; scrollI = 0; });
        bOn = make(rOn, () -> bulkItems(false));
        bOff = make(rOff, () -> bulkItems(true));
        bAllOn = make(rAllOn, () -> bulkChaos(false));
        bAllOff = make(rAllOff, () -> bulkChaos(true));
        bUp = make(rUp, () -> scroll(-Math.max(1, rows() / 2)));
        bDown = make(rDown, () -> scroll(Math.max(1, rows() / 2)));
        bReset = make(rReset, () -> { Settings.resetAll(); queryText = ""; search.setValue(""); filterMode = 0; });

        bMisc[0] = make(rMisc[0], () -> { if (canItems()) Settings.setSkipHard(!Settings.viewSkipHard()); });
        bMisc[1] = make(rMisc[1], () -> { if (canItems()) Settings.setFamilyWeighted(!Settings.viewFamilyWeighted()); });
        bMisc[2] = make(rMisc[2], () -> Settings.setChaosDefault(!Settings.chaosDefault));
        bMisc[3] = make(rMisc[3], () -> Settings.setSounds(!Settings.sounds));

        itemSlots = new Button[rowsI * cols];
        for (int i = 0; i < itemSlots.length; i++) {
            final int slot = i;
            int r = i / cols, c = i % cols;
            itemSlots[i] = make(new Ui.R(listX + c * (colW + 4), listY + r * ROW_I, colW, ROW_I - 2), () -> clickItem(slot));
        }
        chaosSlots = new Button[rowsC];
        for (int i = 0; i < chaosSlots.length; i++) {
            final int slot = i;
            chaosSlots[i] = make(new Ui.R(listX, listY + i * ROW_C, listW, ROW_C - 2), () -> clickChaos(slot));
        }
        lastQuery = null;   // пересчитать список
    }

    // ---------- действия ----------
    private void setTab(int n) { tab = n; }

    private int rows() { return tab == 0 ? rowsI : rowsC; }

    private boolean canItems() {
        if (Settings.canEditItems()) return true;
        say(FriendRunClient.STATE.amHost ? "Игра уже запущена: набор предметов менять нельзя" : "В лобби предметы выбирает хост");
        return false;
    }

    private boolean canChaos() {
        if (Settings.canEditChaos()) return true;
        say("В лобби усложнения выбирает хост");
        return false;
    }

    private void say(String s) { flash = s; flashUntil = System.currentTimeMillis() + 3500; }

    private void scroll(int delta) {
        if (tab == 0) {
            int max = Math.max(0, (shown.size() + cols - 1) / cols - rowsI);
            scrollI = Math.max(0, Math.min(max, scrollI + delta));
        } else if (tab == 1) {
            scrollC = Math.max(0, Math.min(Math.max(0, Chaos.COUNT - rowsC), scrollC + delta));
        }
    }

    /** Колесо мыши. Без @Override намеренно: если сигнатура в версии игры другая, мод всё равно соберётся. */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab > 1) return false;
        scroll(scrollY > 0 ? -3 : 3);
        return true;
    }

    private boolean effectiveOn(Entry e, Set<String> off, boolean skipHard) {
        return !off.contains(e.id()) && !(skipHard && e.hard());
    }

    private void clickItem(int slot) {
        int idx = (scrollI + slot / cols) * cols + slot % cols;
        if (idx < 0 || idx >= shown.size() || !canItems()) return;
        Entry e = shown.get(idx);
        if (Settings.viewSkipHard() && e.hard()) { say("Тяжёлые предметы исключены правилом (вкладка «Прочее»)"); return; }
        Settings.setItemsOff(List.of(e.id()), !Settings.viewOffItems().contains(e.id()));
    }

    private void bulkItems(boolean off) {
        if (shown.isEmpty() || !canItems()) return;
        List<String> ids = new ArrayList<>();
        for (Entry e : shown) ids.add(e.id());
        Settings.setItemsOff(ids, off);
        say((off ? "Выключено: " : "Включено: ") + ids.size());
    }

    private void clickChaos(int slot) {
        int idx = scrollC + slot;
        if (idx < 0 || idx >= Chaos.COUNT || !canChaos()) return;
        String id = Chaos.MODS[idx].id();
        Settings.setChaosOff(List.of(id), !Settings.viewOffChaos().contains(id));
    }

    private void bulkChaos(boolean off) {
        if (!canChaos()) return;
        List<String> ids = new ArrayList<>();
        for (Chaos.Mod m : Chaos.MODS) ids.add(m.id());
        Settings.setChaosOff(ids, off);
    }

    @Override
    public void onClose() {
        Config.save();
        Mc.setScreen(parent);
    }

    // ---------- список предметов ----------
    private void refilter() {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        queryText = search.getValue();
        String[] toks = q.isEmpty() ? new String[0] : q.split("\\s+");
        Set<String> off = Settings.viewOffItems();
        boolean skip = Settings.viewSkipHard();
        List<Entry> out = new ArrayList<>();
        int total = 0, on = 0;
        for (Entry e : all()) {
            boolean isOn = effectiveOn(e, off, skip);
            total++;
            if (isOn) on++;
            if (filterMode == 1 && !isOn) continue;
            if (filterMode == 2 && isOn) continue;
            boolean ok = true;
            for (String t : toks) if (!e.key().contains(t)) { ok = false; break; }
            if (ok) out.add(e);
        }
        shown = out;
        totalItems = total;
        onItems = on;
        lastQuery = search.getValue();
        lastVersion = Settings.version();
        lastFilter = filterMode;
        if (tab == 0) scroll(0);
    }

    // ---------- отрисовка ----------
    private static String trunc(String s, int maxW) {
        if (Ui.w(s) <= maxW) return s;
        while (s.length() > 1 && Ui.w(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static void sw(GuiGraphicsExtractor g, int x, int cy, boolean on, boolean enabled) {
        int track = on ? (enabled ? Ui.GREEN : Ui.GREEN_LINE) : 0xFF3A4658;
        Gfx.rect(g, x, cy - 5, x + 22, cy + 5, track);
        Gfx.rect(g, on ? x + 13 : x + 1, cy - 4, on ? x + 21 : x + 9, cy + 4, enabled ? 0xFFFFFFFF : 0xFFAAAAAA);
    }

    /** Строка-переключатель с названием, описанием и тумблером. */
    private void toggleRow(GuiGraphicsExtractor g, Ui.R r, int mx, int my, boolean on, boolean enabled, String name, String desc) {
        Ui.button(g, r, r.in(mx, my) && enabled, true, on ? Ui.Kind.GREEN : Ui.Kind.NORMAL);
        int tx = r.x() + 8, maxW = r.w() - 60;
        Gfx.text(g, trunc(name, maxW), tx, r.y() + 5, enabled ? Ui.TEXT : Ui.MUTED);
        if (desc != null) Gfx.text(g, trunc(desc, maxW), tx, r.y() + 16, Ui.MUTED);
        sw(g, r.x() + r.w() - 30, r.cy(), on, enabled);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        // пересчёт списка, если поменялся запрос / фильтр / настройки
        if (lastQuery == null || !search.getValue().equals(lastQuery) || lastVersion != Settings.version() || lastFilter != filterMode) refilter();

        boolean t0 = tab == 0, t1 = tab == 1, t2 = tab == 2;
        boolean ei = Settings.canEditItems(), ec = Settings.canEditChaos();

        // видимость виджетов
        search.visible = t0;
        bFilter.visible = t0; bOn.visible = t0; bOff.visible = t0;
        bAllOn.visible = t1; bAllOff.visible = t1;
        bUp.visible = !t2; bDown.visible = !t2;
        for (int i = 0; i < itemSlots.length; i++) itemSlots[i].visible = t0 && (scrollI + i / cols) * cols + i % cols < shown.size();
        for (int i = 0; i < chaosSlots.length; i++) chaosSlots[i].visible = t1 && scrollC + i < Chaos.COUNT;
        for (Button b : bMisc) b.visible = t2;
        bReset.visible = t2;

        // ---------- единый фон FriendRun ----------
        Ui.backdrop(g, width, height);
        // ---------- фон экрана ----------
        Ui.panel(g, px, py, pw, ph);
        Icons.draw(g, Icons.GEAR, px + 12, py + 9, 2, Ui.GOLD);
        Ui.bold(g, "Настройки мода", px + 34, py + 10, Ui.GOLD);

        boolean flashing = System.currentTimeMillis() < flashUntil;
        Object[] note = Settings.modeNote();
        Gfx.text(g, trunc(flashing ? flash : (String) note[0], iw), ix, py + 27, flashing ? Ui.ORANGE : (Integer) note[1]);

        if (t0) Ui.frame(g, rSearch.x(), rSearch.y(), rSearch.w(), rSearch.h(), Ui.SECTION, Ui.LINE);

        // ---------- стандартные виджеты ----------
        super.extractRenderState(g, mx, my, delta);

        // ---------- кнопки ----------
        Ui.button(g, rDone, rDone.in(mx, my), true, Ui.Kind.GOLD);
        Gfx.centered(g, "Готово", rDone.x() + rDone.w() / 2, rDone.y() + 5, 0xFF1A1200);
        for (int i = 0; i < 3; i++) {
            boolean sel = tab == i;
            Ui.button(g, rTab[i], rTab[i].in(mx, my), true, sel ? Ui.Kind.GOLD : Ui.Kind.NORMAL);
            Gfx.centered(g, TABS[i], rTab[i].x() + rTab[i].w() / 2, rTab[i].y() + 5, sel ? 0xFF1A1200 : Ui.TEXT);
        }

        if (t0) drawItems(g, mx, my, ei);
        else if (t1) drawChaos(g, mx, my, ec);
        else drawMisc(g, mx, my, ei);

        if (!t2) drawScrollbar(g, mx, my, t0 ? (shown.size() + cols - 1) / cols : Chaos.COUNT, t0 ? rowsI : rowsC, t0 ? scrollI : scrollC);
    }

    private void drawItems(GuiGraphicsExtractor g, int mx, int my, boolean editable) {
        String[] fm = {"Показать: все", "Показать: вкл.", "Показать: выкл."};
        Ui.button(g, rFilter, rFilter.in(mx, my), true, Ui.Kind.NORMAL);
        Gfx.centered(g, fm[filterMode], rFilter.x() + rFilter.w() / 2, rFilter.y() + 5, Ui.TEXT);

        boolean any = !shown.isEmpty() && editable;
        Ui.button(g, rOn, rOn.in(mx, my) && any, any, Ui.Kind.GREEN);
        Gfx.centered(g, "Включить найденные (" + shown.size() + ")", rOn.x() + rOn.w() / 2, rOn.y() + 4, any ? Ui.TEXT : Ui.MUTED);
        Ui.button(g, rOff, rOff.in(mx, my) && any, any, Ui.Kind.NORMAL);
        Gfx.centered(g, "Выключить найденные (" + shown.size() + ")", rOff.x() + rOff.w() / 2, rOff.y() + 4, any ? Ui.TEXT : Ui.MUTED);

        int cy = rOn.y() + 20;
        String cnt = "В пуле: " + onItems + " из " + totalItems;
        Gfx.text(g, cnt, ix + 2, cy, onItems == 0 ? Ui.RED : Ui.GREEN);
        if (onItems == 0) Gfx.text(g, "— пусто, будет использован полный список", ix + 2 + Ui.w(cnt) + 6, cy, Ui.RED);
        Ui.right(g, "найдено: " + shown.size(), ix + iw - 2, cy, Ui.MUTED);

        Set<String> off = Settings.viewOffItems();
        boolean skip = Settings.viewSkipHard();
        if (shown.isEmpty()) Gfx.centered(g, "Ничего не найдено", listX + listW / 2, listY + 20, Ui.MUTED);
        for (int i = 0; i < itemSlots.length; i++) {
            int idx = (scrollI + i / cols) * cols + i % cols;
            if (idx >= shown.size()) continue;
            Entry e = shown.get(idx);
            int x = listX + (i % cols) * (colW + 4), y = listY + (i / cols) * ROW_I;
            Ui.R rr = new Ui.R(x, y, colW, ROW_I - 2);
            boolean on = effectiveOn(e, off, skip);
            Ui.button(g, rr, rr.in(mx, my) && editable, true, on ? Ui.Kind.GREEN : Ui.Kind.NORMAL);

            // Menu-only icon path; do not alter the in-game HUD renderer.
            MenuItemIcon.draw(g, e.item(), x + 3, y + 1, 16);

            int maxW = colW - 24 - 32 - (e.hard() ? 26 : 0);
            Gfx.text(g, trunc(e.name(), maxW), x + 24, y + 5, on ? Ui.TEXT : Ui.MUTED);
            if (e.hard()) Ui.right(g, "тяж.", x + colW - 34, y + 5, skip ? Ui.RED : Ui.ORANGE);
            sw(g, x + colW - 28, rr.cy(), on, editable);
        }
    }

    private void drawChaos(GuiGraphicsExtractor g, int mx, int my, boolean editable) {
        Ui.button(g, rAllOn, rAllOn.in(mx, my) && editable, editable, Ui.Kind.GREEN);
        Gfx.centered(g, "Включить все", rAllOn.x() + rAllOn.w() / 2, rAllOn.y() + 5, editable ? Ui.TEXT : Ui.MUTED);
        Ui.button(g, rAllOff, rAllOff.in(mx, my) && editable, editable, Ui.Kind.NORMAL);
        Gfx.centered(g, "Выключить все", rAllOff.x() + rAllOff.w() / 2, rAllOff.y() + 5, editable ? Ui.TEXT : Ui.MUTED);

        Set<String> off = Settings.viewOffChaos();
        int on = Chaos.enabledIndices(off).length;
        int cy = rAllOn.y() + 24;
        Gfx.text(g, "Включено: " + on + " из " + Chaos.COUNT, ix + 2, cy, on == 0 ? Ui.RED : Ui.GREEN);
        if (on == 0) Gfx.text(g, "— случайное усложнение в лобби работать не будет", ix + 2 + Ui.w("Включено: " + on + " из " + Chaos.COUNT) + 6, cy, Ui.RED);
        else Ui.right(g, "Выпадают только включённые", ix + iw - 2, cy, Ui.MUTED);

        for (int i = 0; i < chaosSlots.length; i++) {
            int idx = scrollC + i;
            if (idx >= Chaos.COUNT) continue;
            Chaos.Mod m = Chaos.MODS[idx];
            boolean isOn = !off.contains(m.id());
            Ui.R rr = new Ui.R(listX, listY + i * ROW_C, listW, ROW_C - 2);
            Ui.button(g, rr, rr.in(mx, my) && editable, true, isOn ? Ui.Kind.GREEN : Ui.Kind.NORMAL);
            int maxW = listW - 60;
            Gfx.text(g, trunc(m.name(), maxW), rr.x() + 8, rr.y() + 4, isOn ? Ui.TEXT : Ui.MUTED);
            Gfx.text(g, trunc(m.desc(), maxW), rr.x() + 8, rr.y() + 14, Ui.MUTED);
            sw(g, rr.x() + rr.w() - 30, rr.cy(), isOn, editable);
        }
    }

    private void drawMisc(GuiGraphicsExtractor g, int mx, int my, boolean ei) {
        Gfx.text(g, "Для всего лобби (задаёт хост)", ix + 2, miscLabel1Y, Ui.GOLD);
        toggleRow(g, rMisc[0], mx, my, Settings.viewSkipHard(), ei, "Исключить очень тяжёлые предметы",
            "Незеритовые вещи, элитры, маяк, диски, шаблоны ковки и т. п.");
        toggleRow(g, rMisc[1], mx, my, !Settings.viewFamilyWeighted(), ei, "Равный шанс у каждого предмета",
            "Выкл: шанс по семействам (все цвета шерсти — один вариант)");

        Gfx.text(g, "Только у вас", ix + 2, miscLabel2Y, Ui.GOLD);
        toggleRow(g, rMisc[2], mx, my, Settings.chaosDefault, true, "Усложнение включено в новых лобби",
            "Лобби, которые вы создаёте, сразу с случайным усложнением");
        toggleRow(g, rMisc[3], mx, my, Settings.sounds, true, "Звуки рулетки",
            "Щелчки при прокрутке и сигнал выпадения");

        Ui.button(g, rReset, rReset.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.CROSS, rReset.x() + 8, rReset.cy() - 2, 1, Ui.MUTED);
        Gfx.text(g, "Сбросить все настройки", rReset.x() + 20, rReset.cy() - 4, Ui.TEXT);
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int mx, int my, int totalRows, int visRows, int scroll) {
        Ui.button(g, rUp, rUp.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.TRI_UP, rUp.x() + 3, rUp.y() + 5, 1, Ui.MUTED);
        Ui.button(g, rDown, rDown.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.TRI_DOWN, rDown.x() + 3, rDown.y() + 4, 1, Ui.MUTED);

        int ty0 = rUp.y() + 16, ty1 = rDown.y() - 3;
        Gfx.rect(g, rUp.x() + 4, ty0, rUp.x() + 9, ty1, Ui.SECTION);
        if (totalRows > visRows && ty1 > ty0) {
            int track = ty1 - ty0;
            int thumb = Math.max(10, track * visRows / totalRows);
            int maxS = totalRows - visRows;
            int top = ty0 + (track - thumb) * Math.min(scroll, maxS) / Math.max(1, maxS);
            Gfx.rect(g, rUp.x() + 4, top, rUp.x() + 9, top + thumb, Ui.GOLD_D);
        }
    }
}
