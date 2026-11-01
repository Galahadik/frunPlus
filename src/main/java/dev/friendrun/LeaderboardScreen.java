package dev.friendrun;

import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Таблица лидеров. Вкладки: «Общий» (все режимы) и по одной на каждый режим.
 * Колонки: место, игрок, ранг, MMR, победы/игры и последняя — по режиму (лучшее время, рекорд очков или винрейт).
 */
public final class LeaderboardScreen extends Screen {
    private static final int ROW_H = 18;
    private static final int SOLO_SCOPE = Ranking.modeCount() + 1;
    private static final int TABS = Ranking.modeCount() + 2;   // «Общий» + рейтинговые режимы + отдельный топ соло-забегов

    private final Screen parent;
    private volatile Map<String, Ranking.Profile> profiles = Map.of();
    private volatile List<Ranking.Row> rows = List.of();
    private volatile List<SpeedrunRecords.Row> speedrunRows = List.of();
    private volatile String status = "Загрузка…";
    private volatile String speedrunStatus = "Загрузка…";
    private volatile boolean loading;
    private volatile boolean dirty;
    private int scope;                  // 0 — общий, 1.. — режим (id + 1)
    private int scroll;

    private int px, py, pw, ph, ix, iw, listX, listW, listY, visRows, pinY;
    private Ui.R rBack, rRefresh, rUp, rDown;
    private final Ui.R[] rTab = new Ui.R[TABS];
    private final Button[] bTab = new Button[TABS];
    private Button bBack, bRefresh, bUp, bDown;

    public LeaderboardScreen(Screen parent) {
        super(Component.literal("FRun: таблица лидеров"));
        this.parent = parent;
        this.scope = FriendRunClient.inLobby() ? (FriendRunClient.STATE.gameMode == Game.SINGLE_SPEEDRUN ? SOLO_SCOPE : Math.max(0, Math.min(Ranking.modeCount() - 1, FriendRunClient.STATE.gameMode)) + 1) : 0;
    }

    private Button make(Ui.R r, Runnable action) {
        Button b = addRenderableWidget(Button.builder(Component.empty(), x -> action.run())
            .bounds(r.x(), r.y(), r.w(), r.h()).build());
        Ui.invisible(b);
        return b;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, 460);
        ph = Math.min(height - 8, 340);
        px = (width - pw) / 2;
        py = Math.max(4, (height - ph) / 2);
        ix = px + 10;
        iw = pw - 20;
        listX = ix;
        listW = iw - 16;
        listY = py + 80;
        pinY = py + ph - 8 - 20;                              // закреплённая строка «вы»
        visRows = Math.max(3, (pinY - 4 - listY) / ROW_H);

        rBack = new Ui.R(px + pw - 10 - 56, py + 6, 56, 18);
        rRefresh = new Ui.R(rBack.x() - 4 - 76, py + 6, 76, 18);
        rUp = new Ui.R(listX + listW + 3, listY, 13, 13);
        rDown = new Ui.R(listX + listW + 3, listY + visRows * ROW_H - 13, 13, 13);

        int tw = (iw - (TABS - 1) * 2) / TABS;
        for (int i = 0; i < TABS; i++) {
            final int n = i;
            rTab[i] = new Ui.R(ix + i * (tw + 2), py + 42, tw, 16);
            bTab[i] = make(rTab[i], () -> setScope(n));
        }
        bBack = make(rBack, this::onClose);
        bRefresh = make(rRefresh, this::refreshNow);
        bUp = make(rUp, () -> scrollBy(-Math.max(1, visRows / 2)));
        bDown = make(rDown, () -> scrollBy(Math.max(1, visRows / 2)));
        dirty = true;
        load();
    }

    private void setScope(int s) {
        scope = s;
        scroll = 0;
        dirty = true;
    }

    // При входе на экран загружаем актуальные данные один раз. Дальше — только кнопкой «Обновить».
    private void load() { load(true); }

    private void refreshNow() { load(true); }

    private void load(boolean forceRefresh) {
        if (loading) return;
        loading = true;
        status = "Загрузка…";
        final String url = Config.databaseUrl;
        Thread t = new Thread(() -> {
            try { profiles = Ranking.fetchCached(url, forceRefresh); status = ""; }
            catch (Exception e) { status = "Не удалось загрузить рейтинг: " + e.getMessage(); }
            try { speedrunRows = SpeedrunRecords.fetch(url); speedrunStatus = ""; }
            catch (Exception e) { speedrunStatus = "Не удалось загрузить топ спидранов: " + e.getMessage(); }
            dirty = true;
            loading = false;
        }, "FRun-leaderboard");
        t.setDaemon(true);
        t.start();
    }

    private void scrollBy(int d) {
        int count = scope == SOLO_SCOPE ? speedrunRows.size() : rows.size();
        int max = Math.max(0, count - visRows);
        scroll = Math.max(0, Math.min(max, scroll + d));
    }

    /** Колесо мыши (без @Override: если сигнатура в версии игры другая — мод всё равно соберётся). */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollBy(scrollY > 0 ? -3 : 3);
        return true;
    }

    @Override
    public void onClose() { Mc.setScreen(parent); }

    private static String trunc(String s, int maxW) {
        if (Ui.w(s) <= maxW) return s;
        while (s.length() > 1 && Ui.w(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static int placeColor(int place) {
        return place == 1 ? 0xFFFFD700 : place == 2 ? 0xFFD0D6E0 : place == 3 ? 0xFFCD8B4A : Ui.MUTED;
    }

    private String subtitle() {
        if (scope == SOLO_SCOPE) return "Топ полных одиночных забегов до финальных титров; меньшее время лучше";
        if (scope == 0) return "Общий рейтинг: меняется от каждого рейтингового раунда. Старт: " + Ranking.START;
        return switch (scope - 1) {
            case Game.CONVEYOR -> "Конвейер: места по числу сданных предметов";
            case Game.CHAIN -> "Цепочка: побеждает первый, кто закончил";
            case Game.BINGO -> "Бинго: побеждает первый, кто закрыл линию";
            case Game.WAVES -> "Волны мобов: места по очкам, потом по времени";
            case Game.MANHUNT -> "Манхант: команды, считается средний рейтинг";
            case Game.PILLARS -> "Столбы: победитель раунда карты против остальных";
            case Game.DRAFT -> "Драфт: рейтинг по количеству захваченных целей";
            case Game.DRAFT_PLUS -> "Драфт+: места по целям + личный вклад (редкие цели, удачные выборы)";
            default -> "Спидран: побеждает первый, кто добыл предмет";
        };
    }

    /** Заголовок и значение последней колонки. */
    private String lastHeader() {
        int g = scope - 1;
        if (scope == SOLO_SCOPE) return "Время";
        if (scope == 0 || g == Game.MANHUNT || g == Game.PILLARS) return "Винрейт";
        return Ranking.timeMode(g) ? "Лучшее" : "Рекорд";
    }

    private String lastValue(Ranking.Rec r) {
        int g = scope - 1;
        if (scope == SOLO_SCOPE) return "—";
        if (scope == 0 || g == Game.MANHUNT || g == Game.PILLARS) return r.games() == 0 ? "—" : (100 * r.wins() / r.games()) + "%";
        if (r.best() <= 0) return "—";
        return Ranking.timeMode(g) ? RunState.formatTime(r.best()) : String.valueOf(r.best());
    }

    private void drawRow(GuiGraphicsExtractor g, int y, int place, Ranking.Row r, boolean me, boolean wide, boolean hover) {
        int rowR = listX + listW;
        Ui.R rr = new Ui.R(listX, y, listW, ROW_H - 2);
        Ui.frame(g, rr.x(), rr.y(), rr.w(), rr.h(), me ? Ui.GREEN_BG : (hover ? Ui.ROW_HOVER : Ui.ROW), me ? Ui.GOLD_D : Ui.LINE);
        Ranking.Tier tier = Ranking.tier(r.rec().mmr());

        Ui.right(g, "#" + place, listX + 30, y + 5, placeColor(place));
        Ui.head(g, listX + 36, y + 2, 12, r.name());
        int nameMax = (wide ? rowR - 215 : rowR - 120) - (listX + 52);
        Gfx.text(g, trunc(r.name() + (me ? " (вы)" : ""), nameMax), listX + 52, y + 5, me ? Ui.GOLD : Ui.TEXT);

        if (wide) {
            Gfx.text(g, tier.name(), rowR - 207, y + 5, tier.color());
            Ui.right(g, String.valueOf(r.rec().mmr()), rowR - 130, y + 5, Ui.TEXT);
            Ui.right(g, r.rec().wins() + "/" + r.rec().games(), rowR - 76, y + 5, Ui.MUTED);
            Ui.right(g, lastValue(r.rec()), rowR - 6, y + 5, Ui.MUTED);
        } else {
            Ui.right(g, String.valueOf(r.rec().mmr()), rowR - 60, y + 5, tier.color());
            Ui.right(g, r.rec().wins() + "/" + r.rec().games(), rowR - 6, y + 5, Ui.MUTED);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (dirty) { dirty = false; rows = scope == SOLO_SCOPE ? List.of() : Ranking.sorted(profiles, scope); scrollBy(0); }
        boolean wide = listW >= 380;
        List<Ranking.Row> list = rows;
        String me = FriendRunClient.accountName();
        boolean soloTab = scope == SOLO_SCOPE;
        boolean loaded = soloTab ? speedrunStatus.isEmpty() : status.isEmpty();

        Ui.panel(g, px, py, pw, ph);
        Icons.draw(g, Icons.CROWN, px + 12, py + 10, 2, Ui.GOLD);
        Ui.bold(g, "Таблица лидеров", px + 34, py + 10, Ui.GOLD);
        Gfx.text(g, trunc(subtitle(), iw), ix, py + 30, Ui.MUTED);

        // шапка колонок
        Ui.frame(g, ix, py + 62, listW, 14, Ui.SECTION, Ui.LINE);
        int rowR = listX + listW;
        Gfx.text(g, "Игрок", listX + 52, py + 66, Ui.MUTED);
        if (soloTab) {
            Ui.right(g, "Время", rowR - 6, py + 66, Ui.MUTED);
        } else if (wide) {
            Gfx.text(g, "Ранг", rowR - 207, py + 66, Ui.MUTED);
            Ui.right(g, "MMR", rowR - 130, py + 66, Ui.MUTED);
            Ui.right(g, "Победы", rowR - 76, py + 66, Ui.MUTED);
            Ui.right(g, lastHeader(), rowR - 6, py + 66, Ui.MUTED);
        } else {
            Ui.right(g, "MMR", rowR - 60, py + 66, Ui.MUTED);
            Ui.right(g, "Побед", rowR - 6, py + 66, Ui.MUTED);
        }

        // стандартные виджеты (невидимые кнопки)
        super.extractRenderState(g, mx, my, delta);

        Ui.button(g, rBack, rBack.in(mx, my), true, Ui.Kind.GOLD);
        Gfx.centered(g, "Назад", rBack.x() + rBack.w() / 2, rBack.y() + 5, 0xFF1A1200);
        Ui.button(g, rRefresh, rRefresh.in(mx, my), !loading, Ui.Kind.NORMAL);
        Gfx.centered(g, loading ? "Загрузка…" : "Обновить", rRefresh.x() + rRefresh.w() / 2, rRefresh.y() + 5, Ui.TEXT);

        // вкладки: «Общий» и режимы
        for (int i = 0; i < TABS; i++) {
            boolean sel = scope == i;
            Ui.button(g, rTab[i], rTab[i].in(mx, my), true, sel ? Ui.Kind.GOLD : Ui.Kind.NORMAL);
            String label = trunc(i == 0 ? "Общий" : (i == SOLO_SCOPE ? "Соло" : Ranking.shortName(i - 1)), rTab[i].w() - 4);
            Gfx.centered(g, label, rTab[i].x() + rTab[i].w() / 2, rTab[i].y() + 4, sel ? 0xFF1A1200 : Ui.TEXT);
        }

        // строки
        if (soloTab) {
            List<SpeedrunRecords.Row> records = speedrunRows;
            for (int i = 0; i < visRows; i++) {
                int idx = scroll + i;
                if (idx >= records.size()) break;
                SpeedrunRecords.Row r = records.get(idx);
                int y = listY + i * ROW_H;
                Ui.R rr = new Ui.R(listX, y, listW, ROW_H - 2);
                boolean mine = r.name().equals(me);
                Ui.frame(g, rr.x(), rr.y(), rr.w(), rr.h(), mine ? Ui.GREEN_BG : (rr.in(mx, my) ? Ui.ROW_HOVER : Ui.ROW), mine ? Ui.GOLD_D : Ui.LINE);
                Ui.right(g, "#" + (idx + 1), listX + 30, y + 5, placeColor(idx + 1));
                Ui.head(g, listX + 36, y + 2, 12, r.name());
                Gfx.text(g, trunc(r.name() + (mine ? " (вы)" : ""), Math.max(30, listW - 150)), listX + 52, y + 5, mine ? Ui.GOLD : Ui.TEXT);
                Ui.right(g, RunState.formatTime(r.ms()), rowR - 6, y + 5, Ui.TEXT);
            }
        } else {
            for (int i = 0; i < visRows; i++) {
                int idx = scroll + i;
                if (idx >= list.size()) break;
                Ranking.Row r = list.get(idx);
                int y = listY + i * ROW_H;
                drawRow(g, y, idx + 1, r, r.name().equals(me), wide, new Ui.R(listX, y, listW, ROW_H - 2).in(mx, my));
            }
        }
        if (!loaded) { String shownStatus = soloTab ? speedrunStatus : status; Gfx.text(g, trunc(shownStatus, iw), ix + 4, listY + 6, shownStatus.startsWith("Не удалось") ? Ui.RED : Ui.MUTED); }
        else if (soloTab ? speedrunRows.isEmpty() : list.isEmpty()) Gfx.text(g, trunc(soloTab ? "Пока нет завершённых одиночных забегов." : "Здесь пока пусто: рейтинг идёт в лобби от 2 игроков.", iw), ix + 4, listY + 6, Ui.MUTED);

        // полоса прокрутки
        Ui.button(g, rUp, rUp.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.TRI_UP, rUp.x() + 3, rUp.y() + 5, 1, Ui.MUTED);
        Ui.button(g, rDown, rDown.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.TRI_DOWN, rDown.x() + 3, rDown.y() + 4, 1, Ui.MUTED);
        int ty0 = rUp.y() + 16, ty1 = rDown.y() - 3;
        Gfx.rect(g, rUp.x() + 4, ty0, rUp.x() + 9, ty1, Ui.SECTION);
        int scrollCount = soloTab ? speedrunRows.size() : list.size();
        if (scrollCount > visRows && ty1 > ty0) {
            int track = ty1 - ty0, thumb = Math.max(10, track * visRows / scrollCount), maxS = scrollCount - visRows;
            int top = ty0 + (track - thumb) * Math.min(scroll, maxS) / Math.max(1, maxS);
            Gfx.rect(g, rUp.x() + 4, top, rUp.x() + 9, top + thumb, Ui.GOLD_D);
        }

        // закреплённая строка с вашим местом в этой вкладке
        if (loaded && soloTab) {
            int found = -1;
            for (int i = 0; i < speedrunRows.size(); i++) if (speedrunRows.get(i).name().equals(me)) { found = i; break; }
            if (found >= 0) {
                SpeedrunRecords.Row r = speedrunRows.get(found);
                Ui.frame(g, listX, pinY, listW, ROW_H - 2, Ui.GREEN_BG, Ui.GOLD_D);
                Ui.right(g, "#" + (found + 1), listX + 30, pinY + 5, placeColor(found + 1));
                Gfx.text(g, r.name() + " (вы)", listX + 52, pinY + 5, Ui.GOLD);
                Ui.right(g, RunState.formatTime(r.ms()), listX + listW - 6, pinY + 5, Ui.TEXT);
            } else Gfx.text(g, trunc("Вашего рекорда пока нет", iw - 8), ix + 4, pinY + 5, Ui.MUTED);
        } else if (loaded) {
            int found = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i).name().equals(me)) { found = i; break; }
            if (found >= 0) drawRow(g, pinY, found + 1, list.get(found), true, wide, false);
            else Gfx.text(g, trunc("Вас нет в этой таблице: сыграйте с друзьями (" + me + ")", iw - 8), ix + 4, pinY + 5, Ui.MUTED);
        }
    }
}
