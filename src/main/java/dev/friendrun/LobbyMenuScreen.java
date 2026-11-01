package dev.friendrun;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Окно «Открытые лобби»: список с прокруткой, поиск, фильтр «только открытые», создание своего лобби.
 * Вид рисуется поверх стандартных виджетов (кнопки прозрачные).
 */
public final class LobbyMenuScreen extends Screen {
    private static final int ROW_H = 32, ROW_PITCH = 35, MAX_ROWS = 8;

    private final Screen parent;
    private EditBox lobbyName, dbUrl, search;
    private Button createBtn, refreshBtn, backBtn, settingsBtn, boardBtn, openOnlyBtn;
    private Button[] rows;
    private int rowCount;

    private List<Lobby.Entry> all = List.of();        // всё, что пришло с сервера
    private List<Lobby.Entry> shown = List.of();      // после поиска и фильтра
    private final String[] rowNames = new String[MAX_ROWS];
    private int scroll;                               // индекс первого видимого лобби
    private boolean openOnly = true;
    private String lastQuery = "";
    private boolean loaded;
    private String fetchError;

    private String status = "";
    private String listStatus = Tr.t("friendrun.ui.loading", "Загрузка…");
    private long lastFetch = 0;
    private boolean fetching = false;

    private final Ui.Anim anim = new Ui.Anim();
    private long openedAt = System.nanoTime();

    // раскладка
    private int px, py, pw, ph, rowsY, urlY, setY, listW;
    private Ui.R rCreate, rRefresh, rBack, rNameBox, rUrlBox, rSettings, rBoard, rSearch, rOpenOnly;

    public LobbyMenuScreen(Screen parent) {
        super(Component.literal("FRun"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // шапка 30 + создание 28 + поиск 22 + [список] + статус 18 + кнопки 28 + URL 28 + отступы
        int chrome = 84 + 4 + 14 + 6 + 22 + 6 + 22 + 10;
        rowCount = Math.max(2, Math.min(MAX_ROWS, (height - 8 - chrome) / ROW_PITCH));
        pw = Math.min(width - 16, 480);
        px = (width - pw) / 2;
        ph = chrome + rowCount * ROW_PITCH;
        py = Math.max(4, (height - ph) / 2);
        int ix = px + 10, iw = pw - 20;
        rowsY = py + 84;
        listW = pw - 16 - 6;                                  // справа место под полосу прокрутки
        setY = rowsY + rowCount * ROW_PITCH + 24;
        urlY = setY + 28;

        rNameBox = new Ui.R(ix, py + 34, iw - 90, 22);
        rCreate = new Ui.R(px + pw - 10 - 82, py + 34, 82, 22);
        rBack = new Ui.R(px + pw - 10 - 56, py + 6, 56, 18);
        int refW = 62, openW = Math.min(124, iw / 3);
        rSearch = new Ui.R(ix, py + 60, iw - openW - refW - 8, 20);
        rOpenOnly = new Ui.R(rSearch.x() + rSearch.w() + 4, py + 60, openW, 20);
        rRefresh = new Ui.R(rOpenOnly.x() + rOpenOnly.w() + 4, py + 60, refW, 20);
        rUrlBox = new Ui.R(ix, urlY, iw, 22);
        int halfW = (iw - 4) / 2;
        rSettings = new Ui.R(ix, setY, halfW, 22);
        rBoard = new Ui.R(ix + halfW + 4, setY, iw - halfW - 4, 22);

        lobbyName = new EditBox(font, rNameBox.x() + 8, rNameBox.y() + 7, rNameBox.w() - 16, 12, Component.literal("Название"));
        lobbyName.setHint(Component.literal(Tr.t("friendrun.ui.name_hint", "Название нового лобби…")));
        lobbyName.setMaxLength(24);
        lobbyName.setValue(Config.lastLobby);
        Ui.noBorder(lobbyName);
        addRenderableWidget(lobbyName);

        search = new EditBox(font, rSearch.x() + 22, rSearch.y() + 4, rSearch.w() - 30, 12, Component.literal("Поиск"));
        search.setHint(Component.literal(Tr.t("friendrun.ui.search_hint", "Поиск по названию или хосту…")));
        search.setMaxLength(24);
        search.setValue(lastQuery);
        Ui.noBorder(search);
        addRenderableWidget(search);

        createBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            status = Tr.t("friendrun.ui.creating", "Создаём…");
            FriendRunClient.createLobby(dbUrl.getValue(), lobbyName.getValue().trim(), this::afterConnect);
        }).bounds(rCreate.x(), rCreate.y(), rCreate.w(), rCreate.h()).build());

        rows = new Button[rowCount];
        for (int i = 0; i < rowCount; i++) {
            final int idx = i;
            rows[i] = Button.builder(Component.empty(), b -> {
                if (rowNames[idx] == null) return;
                status = Tr.t("friendrun.ui.joining", "Подключаемся…");
                FriendRunClient.joinLobby(dbUrl.getValue(), rowNames[idx], this::afterConnect);
            }).bounds(px + 8, rowsY + i * ROW_PITCH, listW, ROW_H).build();
            rows[i].visible = false;
            addRenderableWidget(rows[i]);
        }

        openOnlyBtn = addRenderableWidget(Button.builder(Component.empty(), b -> { openOnly = !openOnly; scroll = 0; applyFilter(); })
            .bounds(rOpenOnly.x(), rOpenOnly.y(), rOpenOnly.w(), rOpenOnly.h()).build());
        refreshBtn = addRenderableWidget(Button.builder(Component.empty(), b -> fetch())
            .bounds(rRefresh.x(), rRefresh.y(), rRefresh.w(), rRefresh.h()).build());
        backBtn = addRenderableWidget(Button.builder(Component.empty(), b -> onClose())
            .bounds(rBack.x(), rBack.y(), rBack.w(), rBack.h()).build());

        dbUrl = new EditBox(font, rUrlBox.x() + 62, rUrlBox.y() + 7, rUrlBox.w() - 70, 12, Component.literal("URL"));
        dbUrl.setHint(Component.literal("https://…firebasedatabase.app"));
        dbUrl.setMaxLength(200);
        dbUrl.setValue(Config.databaseUrl);
        Ui.noBorder(dbUrl);
        addRenderableWidget(dbUrl);

        settingsBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            Config.lastLobby = lobbyName.getValue().trim();   // чтобы введённое не пропало при возврате
            if (!dbUrl.getValue().isBlank()) Config.databaseUrl = dbUrl.getValue().trim();
            Mc.setScreen(new SettingsScreen(this));
        }).bounds(rSettings.x(), rSettings.y(), rSettings.w(), rSettings.h()).build());

        boardBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            Config.lastLobby = lobbyName.getValue().trim();
            if (!dbUrl.getValue().isBlank()) Config.databaseUrl = dbUrl.getValue().trim();
            Mc.setScreen(new LeaderboardScreen(this));
        }).bounds(rBoard.x(), rBoard.y(), rBoard.w(), rBoard.h()).build());

        for (Button b : new Button[] {createBtn, refreshBtn, backBtn, settingsBtn, boardBtn, openOnlyBtn}) Ui.invisible(b);
        for (Button b : rows) Ui.invisible(b);

        openedAt = System.nanoTime();
        applyFilter();
        fetch();
    }

    private void fetch() {
        String url = dbUrl == null ? Config.databaseUrl : dbUrl.getValue();
        lastFetch = System.currentTimeMillis();
        if (url.isBlank()) {
            fetchError = null;
            all = List.of(); loaded = true; applyFilter(); return;
        }
        if (fetching) return;
        fetching = true;
        Thread t = new Thread(() -> {
            List<Lobby.Entry> res = null;
            String err = null;
            try { res = Lobby.list(url); } catch (IOException e) { err = e.getMessage(); }
            List<Lobby.Entry> fr = res; String fe = err;
            Minecraft.getInstance().execute(() -> {
                fetching = false;
                loaded = true;
                fetchError = fe;
                all = fe != null ? List.of() : fr;
                applyFilter();
            });
        }, "FRun-List");
        t.setDaemon(true);
        t.start();
    }

    /** Пересобирает видимый список: поиск + «только открытые» + границы прокрутки + подписи. */
    private void applyFilter() {
        String q = search == null ? lastQuery : search.getValue().trim().toLowerCase(Locale.ROOT);
        lastQuery = q;
        List<Lobby.Entry> out = new ArrayList<>();
        for (Lobby.Entry e : all) {
            if (openOnly && e.launched()) continue;
            if (!q.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(q) && !e.host().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(e);
        }
        shown = out;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - rowCount)));
        String url = dbUrl == null ? Config.databaseUrl : dbUrl.getValue();
        if (fetchError != null) listStatus = Tr.t("friendrun.ui.error", "Ошибка: %s", fetchError);
        else if (url.isBlank()) listStatus = Tr.t("friendrun.ui.need_url", "Укажите URL базы внизу");
        else if (!loaded) listStatus = Tr.t("friendrun.ui.loading", "Загрузка…");
        else if (all.isEmpty()) listStatus = Tr.t("friendrun.ui.empty", "Открытых лобби нет — создайте своё");
        else if (shown.isEmpty()) listStatus = Tr.t("friendrun.ui.nothing_found", "Ничего не найдено");
        else listStatus = Tr.t("friendrun.ui.click_to_join", "Нажмите на лобби, чтобы войти (%s)", shown.size() + "");
        syncRows();
    }

    /** Привязывает невидимые кнопки к лобби, видимым при текущей прокрутке. */
    private void syncRows() {
        if (rows == null) return;
        for (int i = 0; i < rowCount; i++) {
            int idx = scroll + i;
            if (idx < shown.size()) {
                Lobby.Entry e = shown.get(idx);
                rowNames[i] = e.name();
                rows[i].visible = true;
                rows[i].active = !e.launched();
            } else {
                rowNames[i] = null;
                rows[i].visible = false;
            }
        }
    }

    private void afterConnect(String err) {
        if (err != null) status = Tr.t("friendrun.ui.error", "Ошибка: %s", err);
        else Mc.setScreen(new LobbyRoomScreen(parent));
    }

    /** Колесо мыши. Без @Override намеренно: если сигнатура в версии игры другая, мод всё равно соберётся. */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (shown.size() <= rowCount) return false;
        scroll = Math.max(0, Math.min(shown.size() - rowCount, scroll + (scrollY > 0 ? -1 : 1)));
        syncRows();
        return true;
    }

    @Override
    public void onClose() { Mc.setScreen(parent); }

    private static String modeName(int world) { return world == 1 ? Tr.t("friendrun.ui.world_shared", "Общий мир") : Tr.t("friendrun.ui.world_separate", "Отдельные миры"); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        anim.tick();
        if (!fetching && System.currentTimeMillis() - lastFetch > 3000) fetch();
        if (search != null && !search.getValue().trim().toLowerCase(Locale.ROOT).equals(lastQuery)) { scroll = 0; applyFilter(); }

        float open = Ui.easeOut((System.nanoTime() - openedAt) / 1_000_000f / 180f);

        // --- единый фон FriendRun и панель ---
        Ui.backdrop(g, width, height);
        Ui.panel(g, px, py, pw, ph);
        Icons.draw(g, Icons.CROWN, px + 12, py + 11, 2, Ui.GOLD);
        Ui.bold(g, "FRun", px + 34, py + 10, Ui.GOLD);
        if (FriendRunClient.inLobby()) {
            String in = Tr.t("friendrun.ui.in_lobby", "В лобби");
            int pillW = Ui.w(in) + 24, pillX = rBack.x() - pillW - 6;
            Ui.frame(g, pillX, py + 7, pillW, 16, Ui.SECTION, Ui.LINE);
            Icons.draw(g, Icons.DOT, pillX + 6, py + 12, 1, Ui.GREEN);
            Gfx.text(g, in, pillX + 15, py + 11, Ui.TEXT);
        }
        Ui.frame(g, rNameBox.x(), rNameBox.y(), rNameBox.w(), rNameBox.h(), Ui.SECTION, Ui.LINE);
        Ui.frame(g, rSearch.x(), rSearch.y(), rSearch.w(), rSearch.h(), Ui.SECTION, Ui.LINE);
        Ui.frame(g, rUrlBox.x(), rUrlBox.y(), rUrlBox.w(), rUrlBox.h(), Ui.SECTION, Ui.LINE);
        Ui.frame(g, px + 6, rowsY - 3, pw - 12, rowCount * ROW_PITCH + 1, 0xFF0B1320, Ui.LINE);   // «желоб» списка

        // --- обычные виджеты (поля ввода и кнопки) ---
        super.extractRenderState(g, mx, my, delta);

        // --- наш вид кнопок поверх ---
        float hCreate = anim.to("create", rCreate.in(mx, my));
        Ui.button(g, rCreate, hCreate, true, Ui.Kind.GOLD);
        Icons.draw(g, Icons.PLUS, rCreate.x() + 8, rCreate.cy() - 3, 1, 0xFF1A1200);
        Gfx.text(g, Tr.t("friendrun.ui.create", "Создать"), rCreate.x() + 22, rCreate.cy() - 4, 0xFF1A1200);

        Icons.draw(g, Icons.SEARCH, rSearch.x() + 7, rSearch.cy() - 3, 1, Ui.MUTED);

        // фильтр «только открытые» — переключатель
        Ui.button(g, rOpenOnly, anim.to("open", rOpenOnly.in(mx, my)), true, openOnly ? Ui.Kind.GREEN : Ui.Kind.NORMAL);
        Gfx.text(g, Ui.trunc(Tr.t("friendrun.ui.only_open", "Только открытые"), rOpenOnly.w() - 34), rOpenOnly.x() + 7, rOpenOnly.cy() - 4, Ui.TEXT);
        int tx = rOpenOnly.x() + rOpenOnly.w() - 24, ty = rOpenOnly.cy() - 5;
        float tOn = anim.to("openToggle", openOnly);
        Gfx.rect(g, tx, ty, tx + 18, ty + 10, Ui.mix(0xFF3A4658, Ui.GREEN, tOn));
        int kx = tx + 1 + Math.round(8 * tOn);
        Gfx.rect(g, kx, ty + 1, kx + 8, ty + 9, 0xFFFFFFFF);

        Ui.button(g, rRefresh, anim.to("refresh", rRefresh.in(mx, my)), true, Ui.Kind.NORMAL);
        Gfx.centered(g, fetching ? "…" : Tr.t("friendrun.ui.refresh", "Обновить"), rRefresh.x() + rRefresh.w() / 2, rRefresh.cy() - 4, fetching ? Ui.GOLD : Ui.MUTED);
        Ui.button(g, rBack, anim.to("back", rBack.in(mx, my)), true, Ui.Kind.NORMAL);
        Gfx.centered(g, Tr.t("friendrun.ui.back", "Назад"), rBack.x() + rBack.w() / 2, rBack.y() + 5, Ui.TEXT);

        // --- строки лобби ---
        boolean wide = listW >= 380;
        for (int i = 0; i < rowCount && scroll + i < shown.size(); i++) {
            Lobby.Entry e = shown.get(scroll + i);
            int x0 = px + 8, y0 = rowsY + i * ROW_PITCH, rw = listW;
            Ui.R rr = new Ui.R(x0, y0, rw, ROW_H);
            boolean openRow = !e.launched();
            float hv = anim.to("row:" + e.name(), rr.in(mx, my) && openRow);
            Ui.button(g, rr, hv, openRow, Ui.Kind.NORMAL);

            int xr = x0 + rw - 22;                                   // правый край содержимого
            Ui.avatar(g, x0 + 5, y0 + 4, 24, e.name());
            int nameMax = rw - 34 - 22 - (openRow ? 74 : 86);
            Gfx.text(g, Ui.trunc(e.name(), nameMax), x0 + 36, y0 + 5, openRow ? Ui.TEXT : Ui.MUTED);

            // статус справа вверху
            if (openRow) Ui.pill(g, xr, y0 + 5, Tr.t("friendrun.ui.open", "Открыто"), Ui.GREEN);
            else { Icons.draw(g, Icons.LOCK, xr - Ui.w(Tr.t("friendrun.ui.in_game", "Игра идёт")) - 10, y0 + 4, 1, Ui.MUTED);
                   Ui.right(g, Tr.t("friendrun.ui.in_game", "Игра идёт"), xr, y0 + 5, Ui.MUTED); }

            // вторая строка: хост · режим игры · усложнение
            int ly = y0 + 18;
            Icons.draw(g, Icons.CROWN, x0 + 36, ly + 1, 1, Ui.GOLD);
            String host = Ui.trunc(e.host(), 90);
            Gfx.text(g, host, x0 + 48, ly, Ui.MUTED);
            int gx = x0 + 48 + Ui.w(host) + 8;
            String[] gi = Icons.mode(e.gameMode());
            Icons.draw(g, gi, gx, ly + 1, 1, openRow ? Ui.GOLD : Ui.MUTED);
            String gn = Game.name(e.gameMode());
            Gfx.text(g, gn, gx + Icons.w(gi) + 4, ly, openRow ? Ui.TEXT : Ui.MUTED);
            int cx2 = gx + Icons.w(gi) + 4 + Ui.w(gn) + 8;
            if (e.chaos() && cx2 + 50 < xr - 70) {
                Icons.draw(g, Icons.GEAR, cx2, ly + 1, 1, Ui.RED);
                Gfx.text(g, Tr.t("friendrun.ui.chaos_short", "усложнение"), cx2 + 10, ly, 0xFFFF9A9A);
            }

            // справа внизу: игроки (готовы) и тип мира
            String pl = e.players() + (wide ? " (" + Tr.t("friendrun.ui.ready_count", "готовы: %s", e.ready() + "") + ")" : "");
            Ui.right(g, pl, xr, ly, Ui.TEXT);
            Icons.draw(g, Icons.PERSON, xr - Ui.w(pl) - 10, ly + 1, 1, Ui.MUTED);
            Icons.draw(g, Icons.CHEV_R, x0 + rw - 12, y0 + 12, 1, Ui.MUTED);
            if (wide) {
                boolean shared = e.mode() == 1;
                String wn = modeName(e.mode());
                int wx = xr - Ui.w(pl) - 10 - 14 - Ui.w(wn);
                if (wx > gx + 60) {
                    Icons.draw(g, shared ? Icons.WORLD_ONE : Icons.WORLD_MANY, wx - 11, ly + 1, 1, Ui.MUTED);
                    Gfx.text(g, wn, wx, ly, Ui.MUTED);
                }
            }

            // плавное появление строк по очереди
            float a = Ui.easeOut((System.nanoTime() - openedAt) / 1_000_000f / 200f - i * 0.12f);
            Ui.veil(g, x0, y0, rw, ROW_H, 1f - a);
        }
        Ui.scrollbar(g, px + pw - 12, rowsY - 2, rowCount * ROW_PITCH - 4, scroll, shown.size(), rowCount);

        // пустое состояние
        if (shown.isEmpty()) {
            int cy = rowsY + rowCount * ROW_PITCH / 2 - 10;
            Icons.draw(g, Icons.WORLD_MANY, px + pw / 2 - 11, cy - 12, 3, 0xFF2A3B54);
        }

        int hintY = rowsY + rowCount * ROW_PITCH + 6;
        Gfx.centered(g, status.isEmpty() ? listStatus : status, px + pw / 2, hintY, status.isEmpty() ? Ui.MUTED : Ui.ORANGE);
        Gfx.text(g, Tr.t("friendrun.ui.db_url", "URL базы:"), rUrlBox.x() + 8, rUrlBox.y() + 7, Ui.MUTED);

        // строка настроек мода и таблица лидеров
        Ui.button(g, rSettings, anim.to("settings", rSettings.in(mx, my)), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.GEAR, rSettings.x() + 8, rSettings.cy() - 3, 1, Ui.GOLD);
        Gfx.text(g, Tr.t("friendrun.ui.mod_settings", "Настройки мода"), rSettings.x() + 22, rSettings.cy() - 4, Ui.TEXT);
        Icons.draw(g, Icons.CHEV_R, rSettings.x() + rSettings.w() - 12, rSettings.cy() - 3, 1, Ui.MUTED);

        Ui.button(g, rBoard, anim.to("board", rBoard.in(mx, my)), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.CROWN, rBoard.x() + 8, rBoard.cy() - 2, 1, Ui.GOLD);
        Gfx.text(g, Tr.t("friendrun.ui.leaderboard", "Таблица лидеров"), rBoard.x() + 22, rBoard.cy() - 4, Ui.TEXT);
        Icons.draw(g, Icons.CHEV_R, rBoard.x() + rBoard.w() - 12, rBoard.cy() - 3, 1, Ui.MUTED);

        // плавное появление всей панели
        Ui.veil(g, px, py, pw, ph, 1f - open);
    }
}
