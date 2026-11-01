package dev.friendrun;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * Комната лобби в две колонки: слева игра, рулетка/настройки режима, усложнение и управление;
 * справа список игроков (с прокруткой) и кнопки «Старт» / «Выйти». Вид рисуется поверх стандартных кнопок.
 */
public final class LobbyRoomScreen extends Screen {
    private static final int PLAYER_ROW = 17, PLAYER_ROWS = 9, BODY_Y = 28, PLAYERS_H = 15 + PLAYER_ROWS * PLAYER_ROW + 2, FOOT_Y = 238, PANEL_H = 254;

    private final Screen parent;
    private Button bGame, bMode, bRollItem, bRollChaos, bToggle, bStart, bLeave, bSetA, bSetB, bSettings, bBoard;
    private Ui.R rGame, rMode, rRollItem, rRollChaos, rToggle, rStart, rLeave, rSetA, rSetB, rSettings, rBoard, rChaos, rPlayers;
    private int px, py, pw, ph, lx, lw, rx, rw;

    private final Ui.Anim anim = new Ui.Anim();
    private final Map<String, Long> seenAt = new HashMap<>();   // когда игрок впервые появился (для плавного входа)
    private long openedAt = System.nanoTime();
    private int playerScroll;
    private boolean seenInit;

    public LobbyRoomScreen(Screen parent) {
        super(Component.literal("FRun"));
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
        RunState s = FriendRunClient.STATE;
        pw = Math.min(width - 16, 520);
        px = (width - pw) / 2;
        ph = PANEL_H;
        py = Math.max(4, (height - ph) / 2);

        int gap = 8, inner = pw - 20;
        lx = px + 10;
        lw = (inner - gap) * 53 / 100;
        rx = lx + lw + gap;
        rw = inner - gap - lw;

        rSettings = new Ui.R(px + pw - 10 - 88, py + 5, 88, 17);
        rBoard = new Ui.R(rSettings.x() - 4 - 44, py + 5, 44, 17);
        rSetA = new Ui.R(lx, py + 46, lw, 20);
        rSetB = new Ui.R(lx, py + 70, lw, 20);
        rChaos = new Ui.R(lx, py + 106, lw, 28);
        rGame = new Ui.R(lx, py + 138, lw, 20);
        rMode = new Ui.R(lx, py + 162, lw, 20);
        int half = (lw - 4) / 2;
        rRollItem = new Ui.R(lx, py + 186, half, 20);
        rRollChaos = new Ui.R(lx + half + 4, py + 186, lw - half - 4, 20);
        rToggle = new Ui.R(lx, py + 210, lw, 20);
        rPlayers = new Ui.R(rx, py + BODY_Y, rw, PLAYERS_H);
        int startW = rw * 62 / 100;
        rStart = new Ui.R(rx, py + 210, startW, 20);
        rLeave = new Ui.R(rx + startW + 4, py + 210, rw - startW - 4, 20);

        bSettings = make(rSettings, () -> Mc.setScreen(new SettingsScreen(this)));
        bSetA = make(rSetA, FriendRunClient::settingA);
        bSetB = make(rSetB, () -> {
            if (s.gameMode == Game.DRAFT_PLUS) Mc.setScreen(new DraftPlusSetupScreen(this));   // настройка матча и команды (доступна всем)
            else FriendRunClient.settingB();
        });
        bBoard = make(rBoard, () -> Mc.setScreen(new LeaderboardScreen(this)));
        bGame = make(rGame, () -> Mc.setScreen(new GameModeScreen(this)));
        bMode = make(rMode, () -> FriendRunClient.setMode(s.mode == RunState.Mode.SEPARATE_WORLDS
            ? RunState.Mode.HOST_WORLD : RunState.Mode.SEPARATE_WORLDS));
        bRollItem = make(rRollItem, FriendRunClient::rollItem);
        bRollChaos = make(rRollChaos, FriendRunClient::rollChaos);
        bToggle = make(rToggle, FriendRunClient::toggleChaos);
        bStart = make(rStart, FriendRunClient::launch);
        bLeave = make(rLeave, () -> { FriendRunClient.leave(); Mc.setScreen(parent); });
        openedAt = System.nanoTime();
    }

    /** Колесо мыши над списком игроков. Без @Override намеренно (как в остальных экранах мода). */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int n = FriendRunClient.STATE.players.size();
        if (n <= PLAYER_ROWS || !rPlayers.in((int) mouseX, (int) mouseY)) return false;
        playerScroll = Math.max(0, Math.min(n - PLAYER_ROWS, playerScroll + (scrollY > 0 ? -1 : 1)));
        return true;
    }

    @Override
    public void onClose() { Mc.setScreen(parent); }

    /** Плавная подсветка кнопки по её прямоугольнику. */
    private float hv(String key, Ui.R r, int mx, int my, boolean active) { return anim.to(key, active && r.in(mx, my)); }

    private void label(GuiGraphicsExtractor g, Ui.R r, String[] icon, int iconColor, String text, int textColor) {
        Icons.draw(g, icon, r.x() + 8, r.cy() - Icons.h(icon) / 2, 1, iconColor);
        int tx = r.x() + 8 + Icons.w(icon) + 6;
        Gfx.text(g, Ui.trunc(text, r.w() - (tx - r.x()) - 18), tx, r.cy() - 4, textColor);
    }

    /** Настройки выбранного режима вместо рулетки: у каждого режима свои. */
    private void drawSettings(GuiGraphicsExtractor g, RunState s, int mx, int my, boolean canSet, int cx) {
        int tmax = lw - 20;
        if (s.gameMode == Game.SINGLE_SPEEDRUN) {
            Ui.frame(g, rSetA.x(), rSetA.y(), rSetA.w(), rSetA.h(), Ui.SECTION, Ui.LINE);
            Gfx.text(g, Ui.trunc(Tr.t("friendrun.ui.solo_info", "Один игрок · выживание · до титров"), tmax), rSetA.x() + 8, rSetA.cy() - 4, Ui.TEXT);
            return;
        }
        if (s.gameMode == Game.PILLARS) {
            Ui.frame(g, rSetA.x(), rSetA.y(), rSetA.w(), rSetA.h(), Ui.SECTION, Ui.LINE);
            Icons.draw(g, Icons.WORLD_ONE, rSetA.x() + 8, rSetA.cy() - 3, 1, Ui.GOLD);
            Gfx.text(g, Ui.trunc(Tr.t("friendrun.ui.pillars_info", "Готовая карта «Столбики»"), tmax - 16), rSetA.x() + 24, rSetA.cy() - 4, Ui.TEXT);
            return;
        }
        String a, b = null;
        switch (s.gameMode) {
            case Game.CONVEYOR -> { a = Tr.t("friendrun.ui.conv_total", "Длительность раунда: %s мин", s.convTotalMin + ""); b = Tr.t("friendrun.ui.conv_slot", "Смена предмета: каждые %s мин", s.convSlotMin + ""); }
            case Game.CHAIN -> a = Tr.t("friendrun.ui.chain_len", "Длина цепочки: %s предметов", s.chainLen + "");
            case Game.MANHUNT -> { a = Tr.t("friendrun.ui.man_runners", "Беглецов: %s (остальные — охотники)", s.manRunners + ""); b = Tr.t("friendrun.ui.man_min", "Время выживания: %s мин", s.manMin + ""); }
            case Game.WAVES -> { a = Tr.t("friendrun.ui.wave_sec", "Интервал волн: %s сек", s.waveSec + ""); b = Tr.t("friendrun.ui.wave_diff", "Сложность: %s", RunState.diffName(s.waveDiff)); }
            case Game.BINGO -> { a = Tr.t("friendrun.ui.bingo_size", "Размер карточки: %s", s.bingoSize + "×" + s.bingoSize); b = Tr.t("friendrun.ui.bingo_win", "Победа: %s", s.bingoFull ? Tr.t("friendrun.ui.bingo_full", "вся карточка") : Tr.t("friendrun.ui.bingo_line", "линия")); }
            case Game.DRAFT_PLUS -> { a = Tr.t("friendrun.ui.draft_type", "Тип драфта: %s", DraftGoal.draftTypeName(s.draftType)); b = DraftPlus.summary(s); }
            case Game.DRAFT -> { a = Tr.t("friendrun.ui.draft_type", "Тип драфта: %s", DraftGoal.draftTypeName(s.draftType)); b = Tr.t("friendrun.ui.draft_size", "Размер поля: %s", s.draftSize + "×" + s.draftSize); }
            default -> a = "";
        }
        Ui.button(g, rSetA, hv("setA", rSetA, mx, my, canSet), canSet, Ui.Kind.NORMAL);
        label(g, rSetA, Icons.GEAR, canSet ? Ui.GOLD : Ui.MUTED, a, canSet ? Ui.TEXT : Ui.MUTED);
        Icons.draw(g, Icons.CHEV_R, rSetA.x() + rSetA.w() - 12, rSetA.cy() - 3, 1, Ui.MUTED);
        if (b != null) {
            boolean setB = canSet || s.gameMode == Game.DRAFT_PLUS;   // настройку матча Драфта+ можно открыть всем
            Ui.button(g, rSetB, hv("setB", rSetB, mx, my, setB), setB, Ui.Kind.NORMAL);
            label(g, rSetB, Icons.GEAR, setB ? Ui.GOLD : Ui.MUTED, b, setB ? Ui.TEXT : Ui.MUTED);
            Icons.draw(g, Icons.CHEV_R, rSetB.x() + rSetB.w() - 12, rSetB.cy() - 3, 1, Ui.MUTED);
        } else {
            Gfx.centered(g, Ui.trunc(s.gameMode == Game.DRAFT
                ? Tr.t("friendrun.ui.draft_win", "Победа: захватить %s клеток", (s.draftSize * s.draftSize / 2 + 1) + "")
                : Tr.t("friendrun.ui.in_order", "Предметы идут строго по порядку"), lw - 8), cx, py + 74, Ui.MUTED);
        }
        Gfx.centered(g, Ui.trunc(s.gameMode == Game.DRAFT_PLUS
            ? Tr.t("friendrun.ui.dp_hint", "Баны → роли → драфт 5×5 → случайное событие; победа — 13 клеток")
            : s.gameMode == Game.DRAFT
            ? Tr.t("friendrun.ui.draft_hint", "Один тип или смешайте все категории")
            : Tr.t("friendrun.ui.items_at_start", "Предметы определятся при старте раунда"), lw - 8), cx, py + 95, Ui.MUTED);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        RunState s = FriendRunClient.STATE;
        anim.tick();

        // лобби закрылось (хост вышел / обрыв) — вернуться в меню
        if (!FriendRunClient.inLobby()) { Mc.setScreen(parent); return; }

        boolean host = s.amHost, open = host && !s.launched;
        boolean speed = s.gameMode == Game.SPEEDRUN;
        boolean soloSpeedrun = s.gameMode == Game.SINGLE_SPEEDRUN;
        boolean canRollItem = open && speed && !Roulette.running();
        boolean canSet = host && !s.launched && s.phase == RunState.Phase.IDLE;
        boolean canStart = open && (!soloSpeedrun || s.players.size() == 1) && (s.gameMode != Game.MANHUNT || (s.mode == RunState.Mode.HOST_WORLD && s.players.size() >= 2))
            && (s.gameMode != Game.PILLARS || (s.mode == RunState.Mode.HOST_WORLD && s.players.size() >= 2))
            && (s.gameMode != Game.DRAFT_PLUS || DraftPlus.startError(s) == null);
        bSetA.visible = !speed && !soloSpeedrun && s.gameMode != Game.PILLARS;
        bSetB.visible = !speed && !soloSpeedrun && s.gameMode != Game.CHAIN && s.gameMode != Game.PILLARS;
        bSetA.active = canSet;
        bSetB.active = canSet || s.gameMode == Game.DRAFT_PLUS;
        boolean pillars = s.gameMode == Game.PILLARS;
        boolean canRollChaos = host && !pillars && !soloSpeedrun && s.chaos && !ChaosRoll.running();
        bGame.active = host;
        bMode.active = open && !soloSpeedrun && s.gameMode != Game.MANHUNT && s.gameMode != Game.PILLARS;
        bRollItem.active = canRollItem && !pillars && !soloSpeedrun;
        bRollChaos.active = canRollChaos && !soloSpeedrun;
        bToggle.active = host && !pillars && !soloSpeedrun;
        bStart.active = canStart;

        int lcx = lx + lw / 2;
        int nPlayers = s.players.size();
        playerScroll = Math.max(0, Math.min(playerScroll, Math.max(0, nPlayers - PLAYER_ROWS)));

        // ---------- единый фон FriendRun и панель ----------
        Ui.backdrop(g, width, height);
        Ui.panel(g, px, py, pw, ph);
        Icons.draw(g, Icons.CROWN, px + 12, py + 8, 2, Ui.GOLD);
        String title = Tr.t("friendrun.ui.lobby", "Лобби");
        Ui.bold(g, title, px + 34, py + 8, Ui.GOLD);
        int afterTitle = px + 34 + Ui.w(title) + 8;
        if (host) { String tag = Tr.t("friendrun.ui.you_host", "(вы хост)"); Gfx.text(g, tag, afterTitle, py + 8, Ui.MUTED); afterTitle += Ui.w(tag) + 8; }
        if (!s.status.isEmpty()) {
            int right = rBoard.x() - 8;
            Ui.right(g, Ui.trunc(s.status, Math.max(20, right - afterTitle)), right, py + 9, Ui.ORANGE);
        }

        // левая колонка: заголовок секции
        Ui.frame(g, lx, py + BODY_Y, lw, 13, Ui.SECTION, Ui.LINE);
        String secTitle = speed ? Tr.t("friendrun.ui.roulette", "Рулетка предмета")
            : soloSpeedrun ? Tr.t("friendrun.ui.solo_title", "Полный Minecraft-спидран")
            : Tr.t("friendrun.ui.mode_settings", "Режим: %s", Game.name(s.gameMode));
        Gfx.text(g, Ui.trunc(secTitle, lw - 90), lx + 6, py + 31, Ui.MUTED);
        if (!pillars && !soloSpeedrun && !Roulette.running()) Ui.right(g, Tr.t("friendrun.ui.pool", "в пуле: %s", ItemPicker.poolSize() + ""), lx + lw - 6, py + 31, Ui.MUTED);
        if (speed && Roulette.running()) Ui.right(g, String.format("%.1fс", Roulette.remainingSec()), lx + lw - 6, py + 31, Ui.GOLD);

        // блок усложнения
        if (s.chaos && !soloSpeedrun) Ui.frame(g, rChaos.x(), rChaos.y(), rChaos.w(), rChaos.h(), Ui.RED_BG, Ui.RED_LINE);
        else Ui.frame(g, rChaos.x(), rChaos.y(), rChaos.w(), rChaos.h(), Ui.SECTION, Ui.LINE);

        // правая колонка: рамка игроков
        Ui.frame(g, rPlayers.x(), rPlayers.y(), rPlayers.w(), rPlayers.h(), Ui.SECTION, Ui.LINE);

        // ---------- стандартные виджеты ----------
        super.extractRenderState(g, mx, my, delta);

        // ---------- рулетка / настройки режима ----------
        if (speed) {
            Roulette.draw(g, lcx, py + 48, lw - 4);
            Item item = ItemPicker.pick(s.itemSeed);
            if (Roulette.running()) {
                Gfx.centered(g, Tr.t("friendrun.ui.spinning", "Крутим…"), lcx, py + 95, Ui.GOLD);
            } else {
                String itemLabel = Ui.trunc(Tr.t("friendrun.ui.item", "Предмет: %s", ItemInfo.name(item)), lw - 40);
                int labelX = lcx - Ui.w(itemLabel) / 2;
                MenuItemIcon.draw(g, item, labelX - 19, py + 89, 16);
                Gfx.centered(g, itemLabel, lcx, py + 95, Ui.GREEN);
            }
        } else {
            drawSettings(g, s, mx, my, canSet, lcx);
        }

        // ---------- усложнение ----------
        if (pillars || soloSpeedrun) {
            Icons.draw(g, Icons.GEAR, rChaos.x() + 8, rChaos.y() + 10, 1, Ui.GOLD);
            Gfx.text(g, Ui.trunc(Tr.t("friendrun.ui.rules_by_map", "Правила и запуск определяются картой"), lw - 30), rChaos.x() + 22, rChaos.y() + 10, Ui.TEXT);
        } else if (s.chaos) {
            int shown = ChaosRoll.update();
            boolean spin = ChaosRoll.running();
            String cl = Tr.t("friendrun.ui.chaos", "Усложнение:");
            String pool = Chaos.enabledIndices(Settings.effOffChaos()).length + "/" + Chaos.COUNT;
            Icons.draw(g, Icons.GEAR, rChaos.x() + 8, rChaos.y() + 6, 1, Ui.RED);
            Gfx.text(g, cl, rChaos.x() + 22, rChaos.y() + 4, 0xFFFF9A9A);
            int nx = rChaos.x() + 22 + Ui.w(cl) + 5;
            Gfx.text(g, Ui.trunc(Chaos.nameOf(shown), rChaos.x() + rChaos.w() - 8 - Ui.w(pool) - 8 - nx), nx, rChaos.y() + 4, spin ? Ui.GOLD : Ui.TEXT);
            Gfx.text(g, Ui.trunc(spin ? "…" : Chaos.descOf(s.chaosMod), lw - 30), rChaos.x() + 22, rChaos.y() + 16, Ui.MUTED);
            Ui.right(g, pool, rChaos.x() + rChaos.w() - 8, rChaos.y() + 4, Ui.MUTED);
        } else {
            Icons.draw(g, Icons.GEAR, rChaos.x() + 8, rChaos.y() + 10, 1, Ui.MUTED);
            Gfx.text(g, Tr.t("friendrun.ui.chaos_off", "Усложнение: выключено"), rChaos.x() + 22, rChaos.y() + 10, Ui.MUTED);
        }

        // ---------- игроки ----------
        Ui.bold(g, Tr.t("friendrun.ui.players", "Игроки (%s)", nPlayers + ""), rx + 6, rPlayers.y() + 4, Ui.TEXT);
        int y = rPlayers.y() + 15;
        int shownPlayers = Math.min(PLAYER_ROWS, nPlayers - playerScroll);
        long now = System.nanoTime();
        if (!seenInit) { seenInit = true; for (RunState.PlayerInfo p : s.players) seenAt.put(p.name(), openedAt); }   // уже сидящие не «въезжают»
        String youName = FriendRunClient.accountName();
        for (int i = 0; i < shownPlayers; i++) {
            RunState.PlayerInfo p = s.players.get(playerScroll + i);
            int rowX = rx + 3, rowW = rw - 3 - 8;
            boolean mine = p.name().equals(youName);
            if (mine) Gfx.rect(g, rowX, y, rowX + rowW, y + PLAYER_ROW - 1, 0xFF121D2F);
            Ui.head(g, rx + 6, y + 2, 12, p.name());
            int nx = rx + 24;
            if (s.gameMode == Game.DRAFT_PLUS && s.dpTeam) {   // цвет команды слева; ★ — капитан
                int tm = DraftPlus.teamOf(s.dpRoster, p.name());
                if (tm > 0) {
                    Gfx.rect(g, rowX, y, rowX + 2, y + PLAYER_ROW - 1, tm == 1 ? 0xFFFF6B6B : 0xFF6BA8FF);
                    if (DraftPlus.captain(s.dpRoster, tm).equals(p.name())) { Gfx.text(g, "★", nx, y + 4, 0xFFFFD700); nx += 9; }
                }
            }
            if (p.host()) { Icons.draw(g, Icons.CROWN, nx, y + 5, 1, Ui.GOLD); nx += 12; }

            // готовность: цвет и значок меняются плавно
            float rd = anim.to("rdy:" + p.name(), p.ready());
            String readyText = rd > 0.5f ? Tr.t("friendrun.ui.ready", "Готов") : Tr.t("friendrun.ui.not_ready", "Не готов");
            int readyColor = Ui.mix(Ui.MUTED, Ui.GREEN, rd);
            int xr = rx + rw - 12;
            Gfx.text(g, readyText, xr - Ui.w(readyText), y + 4, readyColor);
            Icons.draw(g, rd > 0.5f ? Icons.CHECK : Icons.DOT, xr - Ui.w(readyText) - (rd > 0.5f ? 11 : 9), y + (rd > 0.5f ? 5 : 6), 1, readyColor);
            int leftLimit = xr - Math.max(Ui.w(Tr.t("friendrun.ui.ready", "Готов")), Ui.w(Tr.t("friendrun.ui.not_ready", "Не готов"))) - 16;

            // рейтинг игрока в выбранном режиме
            String mmrText;
            int mmrColor;
            if (s.gameMode == Game.SINGLE_SPEEDRUN) { mmrText = Tr.t("friendrun.ui.no_mmr", "без MMR"); mmrColor = Ui.MUTED; }
            else {
                Ranking.Rec rec = Ranking.recFor(Ranking.cached(p.name()), s.gameMode + 1);
                int mmr = rec == null ? Ranking.START : rec.mmr();
                mmrText = mmr + " " + Ranking.tier(mmr).name();
                mmrColor = rec == null ? Ui.MUTED : Ranking.tier(mmr).color();
            }
            int mmrW = Ui.w(mmrText);
            boolean mmrFits = leftLimit - mmrW - 8 > nx + 36;
            if (mmrFits) Gfx.text(g, mmrText, leftLimit - mmrW, y + 4, mmrColor);
            int nameRight = mmrFits ? leftLimit - mmrW - 8 : leftLimit;
            String nm = p.name() + (p.host() ? " " + Tr.t("friendrun.ui.host_tag", "(хост)") : "");
            Gfx.text(g, Ui.trunc(nm, nameRight - nx), nx, y + 4, Ui.TEXT);

            // плавное появление нового игрока
            long first = seenAt.computeIfAbsent(p.name(), k -> now);
            if (now - first < 400_000_000L && first != openedAt) {
                float a = Ui.easeOut((now - first) / 1_000_000f / 300f);
                Ui.veil(g, rowX, y, rowW, PLAYER_ROW - 1, 1f - a);
            }
            y += PLAYER_ROW;
        }
        Ui.scrollbar(g, rx + rw - 6, rPlayers.y() + 15, PLAYER_ROWS * PLAYER_ROW, playerScroll, nPlayers, PLAYER_ROWS);

        // ---------- кнопки (наш вид поверх стандартных) ----------
        Ui.button(g, rSettings, hv("settings", rSettings, mx, my, true), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.GEAR, rSettings.x() + 6, rSettings.cy() - 3, 1, Ui.GOLD);
        Gfx.text(g, Tr.t("friendrun.ui.settings", "Настройки"), rSettings.x() + 18, rSettings.cy() - 4, Ui.TEXT);
        Ui.button(g, rBoard, hv("board", rBoard, mx, my, true), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.CROWN, rBoard.x() + 6, rBoard.cy() - 2, 1, Ui.GOLD);
        Gfx.text(g, Tr.t("friendrun.ui.top", "Топ"), rBoard.x() + 20, rBoard.cy() - 4, Ui.TEXT);

        Ui.button(g, rGame, hv("game", rGame, mx, my, host), host, Ui.Kind.NORMAL);
        label(g, rGame, Icons.CROWN, Ui.GOLD, Tr.t("friendrun.ui.game", "Игра: %s", Game.name(s.gameMode) + " (" + s.gameSummary() + ")"), host ? Ui.TEXT : Ui.MUTED);
        Icons.draw(g, Icons.CHEV_R, rGame.x() + rGame.w() - 12, rGame.cy() - 3, 1, Ui.MUTED);

        boolean modeOn = open && !soloSpeedrun && s.gameMode != Game.MANHUNT && s.gameMode != Game.PILLARS;
        Ui.button(g, rMode, hv("mode", rMode, mx, my, modeOn), modeOn, Ui.Kind.NORMAL);
        label(g, rMode, s.mode == RunState.Mode.SEPARATE_WORLDS ? Icons.WORLD_MANY : Icons.WORLD_ONE, Ui.MUTED,
            Tr.t("friendrun.ui.mode", "Режим: %s", s.mode == RunState.Mode.SEPARATE_WORLDS ? Tr.t("friendrun.ui.world_separate_l", "отдельные миры") : Tr.t("friendrun.ui.world_shared_l", "мир хоста")),
            modeOn ? Ui.TEXT : Ui.MUTED);
        Icons.draw(g, Icons.CHEV_R, rMode.x() + rMode.w() - 12, rMode.cy() - 3, 1, Ui.MUTED);

        boolean rollItemOn = canRollItem && !pillars && !soloSpeedrun;
        Ui.button(g, rRollItem, hv("rollI", rRollItem, mx, my, rollItemOn), rollItemOn, Ui.Kind.NORMAL);
        label(g, rRollItem, Icons.DICE, rollItemOn ? Ui.TEXT : Ui.MUTED, Tr.t("friendrun.ui.roll_item", "Ролл предмета"), rollItemOn ? Ui.TEXT : Ui.MUTED);

        boolean rollChaosOn = canRollChaos && !soloSpeedrun;
        Ui.button(g, rRollChaos, hv("rollC", rRollChaos, mx, my, rollChaosOn), rollChaosOn, Ui.Kind.NORMAL);
        label(g, rRollChaos, Icons.GEAR, rollChaosOn ? Ui.RED : Ui.MUTED, Tr.t("friendrun.ui.roll_chaos", "Ролл усложнения"), rollChaosOn ? Ui.TEXT : Ui.MUTED);

        boolean chaosOn = s.chaos && !soloSpeedrun;
        Ui.button(g, rToggle, hv("toggle", rToggle, mx, my, host && !soloSpeedrun), host && !soloSpeedrun, s.chaos ? Ui.Kind.GREEN : Ui.Kind.NORMAL);
        label(g, rToggle, Icons.DICE, Ui.TEXT, soloSpeedrun ? Tr.t("friendrun.ui.chaos_disabled", "Усложнения отключены")
            : Tr.t("friendrun.ui.chaos_toggle", "Случайное усложнение: %s", s.chaos ? Tr.t("friendrun.ui.on", "ВКЛ") : Tr.t("friendrun.ui.off", "выкл")), soloSpeedrun ? Ui.MUTED : Ui.TEXT);
        // переключатель с плавным движком
        float tOn = anim.to("chaosToggle", chaosOn);
        int tx = rToggle.x() + rToggle.w() - 30, ty = rToggle.cy() - 5;
        Gfx.rect(g, tx, ty, tx + 22, ty + 10, Ui.mix(0xFF3A4658, Ui.GREEN, tOn));
        int kx = tx + 1 + Math.round(12 * tOn);
        Gfx.rect(g, kx, ty + 1, kx + 8, ty + 9, 0xFFFFFFFF);

        Ui.button(g, rStart, hv("start", rStart, mx, my, canStart), canStart, Ui.Kind.GOLD);
        int sc = canStart ? 0xFF1A1200 : 0xFF8A7A50;
        Icons.draw(g, Icons.PLAY, rStart.x() + 8, rStart.cy() - 3, 1, sc);
        String startText = s.launched ? Tr.t("friendrun.ui.started", "Игра запущена")
            : host ? (canStart ? Tr.t("friendrun.ui.start", "Старт (создать мир)") : s.gameMode == Game.DRAFT_PLUS && DraftPlus.startError(s) != null ? DraftPlus.startError(s) : soloSpeedrun ? Tr.t("friendrun.ui.need_solo", "Одиночный спидран: 1 игрок") : Tr.t("friendrun.ui.need_two", "Нужно 2+ игрока и мир хоста"))
            : Tr.t("friendrun.ui.waiting_host", "Ждём старт от хоста…");
        Gfx.text(g, Ui.trunc(startText, rStart.w() - 26), rStart.x() + 20, rStart.cy() - 4, sc);

        Ui.button(g, rLeave, hv("leave", rLeave, mx, my, true), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.CROSS, rLeave.x() + 8, rLeave.cy() - 2, 1, Ui.MUTED);
        Gfx.text(g, Ui.trunc(Tr.t("friendrun.ui.leave", "Выйти"), rLeave.w() - 24), rLeave.x() + 18, rLeave.cy() - 4, Ui.TEXT);

        // ---------- подвал ----------
        int fy = py + FOOT_Y;
        String hint = s.mode == RunState.Mode.HOST_WORLD
            ? (host && Net.isAndroid() ? Tr.t("friendrun.ui.android_lan", "Android: только друзья из вашей Wi-Fi") : Tr.t("friendrun.ui.need_e4mc", "Нужен мод e4mc (для общего мира)"))
            : "";
        int hintW = hint.isEmpty() ? 0 : Ui.w(hint) + 12;
        Gfx.text(g, Ui.trunc(Tr.t("friendrun.ui.lobby_name", "Название лобби: %s", s.lobbyName), pw - 20 - hintW), lx + 2, fy, Ui.MUTED);
        if (!hint.isEmpty()) Ui.right(g, hint, px + pw - 12, fy, Ui.ORANGE);

        // плавное появление всей панели
        Ui.veil(g, px, py, pw, ph, 1f - Ui.easeOut((System.nanoTime() - openedAt) / 1_000_000f / 180f));
    }
}
