package dev.friendrun;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Настройка матча «Драфта+»: вкладки «Формат» (каждый сам за себя / команды 1×1…5×5, тип целей),
 * «Правила» (баны, роли, монетка, хаос-события — всё включается и выключается) и «Команды»
 * (игроки сами занимают места, капитан — первый вступивший — переименовывает команду, у хоста «Случайные команды»).
 * Настройки меняет хост; занять команду может каждый.
 */
public final class DraftPlusSetupScreen extends Screen {
    private static final String[] TABS = {"Формат", "Правила", "Команды"};
    private static final int[] TEAM_COL = {0xFFFF6B6B, 0xFF6BA8FF};
    private static final int RULE_H = 22, SLOT_H = 22;

    /** Строка вкладки «Правила». */
    private record Rule(String label, String desc, boolean on, boolean enabled, boolean sub, boolean step, Runnable act) {}

    private final Screen parent;
    private int tab, scroll;
    private int px, py, pw, ph, ix, iw, y0, listY, listW, rows;
    private Ui.R rDone, rFfa, rTeam, rType, rUp, rDown, rRandom, rSave, rName;
    private final Ui.R[] rTab = new Ui.R[3], rSize = new Ui.R[5];
    private final Ui.R[][] rSlot = new Ui.R[2][DraftPlus.TEAM_MAX];
    private Button bDone, bFfa, bTeam, bType, bUp, bDown, bRandom, bSave;
    private final Button[] bTab = new Button[3], bSize = new Button[5];
    private final Button[][] bSlot = new Button[2][DraftPlus.TEAM_MAX];
    private Button[] ruleSlots;
    private EditBox nameBox;
    private int nameFor = -1;
    private String flash = "";
    private long flashUntil;

    public DraftPlusSetupScreen(Screen parent) {
        super(Component.literal("FriendRun — Драфт+: настройка матча"));
        this.parent = parent;
    }

    private Button make(Ui.R r, Runnable action) {
        Button b = addRenderableWidget(Button.builder(Component.empty(), ignored -> action.run())
            .bounds(r.x(), r.y(), r.w(), r.h()).build());
        Ui.invisible(b);
        return b;
    }

    private void say(String s) { flash = s; flashUntil = System.currentTimeMillis() + 3500; }

    private boolean cfg() {
        if (FriendRunClient.dpCanConfigure()) return true;
        say(FriendRunClient.STATE.amHost ? "Сейчас идёт подготовка или матч — настройки закрыты" : "Настройки матча меняет хост лобби");
        return false;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, 460);
        ph = Math.min(height - 8, 340);
        px = (width - pw) / 2;
        py = Math.max(4, (height - ph) / 2);
        ix = px + 10;
        iw = pw - 20;
        y0 = py + 64;
        int half = (iw - 4) / 2;

        rDone = new Ui.R(px + pw - 10 - 64, py + 6, 64, 18);
        int tw = (iw - 8) / 3;
        for (int i = 0; i < 3; i++) rTab[i] = new Ui.R(ix + i * (tw + 4), py + 40, tw, 18);

        // формат
        rFfa = new Ui.R(ix, y0 + 14, half, 34);
        rTeam = new Ui.R(ix + half + 4, y0 + 14, iw - half - 4, 34);
        int sw = (iw - 4 * 4) / 5;
        for (int i = 0; i < 5; i++) rSize[i] = new Ui.R(ix + i * (sw + 4), y0 + 72, sw, 24);
        rType = new Ui.R(ix, y0 + 106, iw, 20);

        // правила
        listY = y0;
        listW = iw - 16;
        rows = Math.max(3, (py + ph - 8 - listY) / RULE_H);
        rUp = new Ui.R(ix + listW + 3, listY, 13, 13);
        rDown = new Ui.R(ix + listW + 3, listY + rows * RULE_H - 13, 13, 13);

        // команды
        int colW = (iw - 8) / 2;
        for (int t = 0; t < 2; t++)
            for (int k = 0; k < DraftPlus.TEAM_MAX; k++) rSlot[t][k] = new Ui.R(ix + t * (colW + 8), y0 + 22 + k * (SLOT_H + 2), colW, SLOT_H);
        rRandom = new Ui.R(px + pw - 10 - 150, py + ph - 8 - 18, 150, 18);
        rSave = new Ui.R(px + pw - 10 - 150 - 4 - 70, py + ph - 8 - 18, 70, 18);

        int nx = ix + 112;
        rName = new Ui.R(nx, rSave.y(), rSave.x() - 4 - nx, 18);
        nameBox = new EditBox(font, rName.x() + 5, rName.y() + 3, rName.w() - 10, 12, Component.literal("Название"));
        nameBox.setMaxLength(16);
        Ui.noBorder(nameBox);
        addRenderableWidget(nameBox);
        nameFor = -1;

        bDone = make(rDone, this::onClose);
        for (int i = 0; i < 3; i++) { final int n = i; bTab[i] = make(rTab[i], () -> { tab = n; scroll = 0; }); }
        bFfa = make(rFfa, () -> { if (cfg()) FriendRunClient.dpSetNum("dpFormat", 0); });
        bTeam = make(rTeam, () -> { if (cfg()) FriendRunClient.dpSetNum("dpFormat", 1); });
        for (int i = 0; i < 5; i++) {
            final int n = i + 1;
            bSize[i] = make(rSize[i], () -> {
                if (cfg() && FriendRunClient.STATE.dpTeam) FriendRunClient.dpSetNum("dpTeamSize", n);
            });
        }
        bType = make(rType, () -> { if (cfg()) FriendRunClient.dpSetNum("draftType", (FriendRunClient.STATE.draftType + 1) % 4); });
        bUp = make(rUp, () -> scrollBy(-Math.max(1, rows / 2)));
        bDown = make(rDown, () -> scrollBy(Math.max(1, rows / 2)));
        ruleSlots = new Button[rows];
        for (int i = 0; i < rows; i++) {
            final int slot = i;
            ruleSlots[i] = make(new Ui.R(ix, listY + i * RULE_H, listW, RULE_H - 2), () -> clickRule(slot));
        }
        for (int t = 0; t < 2; t++)
            for (int k = 0; k < DraftPlus.TEAM_MAX; k++) {
                final int team = t + 1, slot = k;
                bSlot[t][k] = make(rSlot[t][k], () -> clickSlot(team, slot));
            }
        bRandom = make(rRandom, () -> { if (cfg()) FriendRunClient.dpRandomTeams(); });
        bSave = make(rSave, () -> {
            int cap = capTeam();
            if (cap > 0) { FriendRunClient.dpRenameTeam(cap, nameBox.getValue()); say("Название команды сохранено"); }
        });
    }

    // ---------- данные ----------
    private int capTeam() {
        RunState s = FriendRunClient.STATE;
        String me = FriendRunClient.myName();
        return DraftPlus.captain(s.dpRoster, 1).equals(me) ? 1 : DraftPlus.captain(s.dpRoster, 2).equals(me) ? 2 : 0;
    }

    private List<Rule> rules() {
        RunState s = FriendRunClient.STATE;
        List<Rule> l = new ArrayList<>();
        l.add(new Rule("Баны", "Игроки по очереди убирают цели из пула драфта", s.dpBanOn, true, false, false,
            () -> { if (cfg()) FriendRunClient.dpSetFlag("dpBanOn", !s.dpBanOn); }));
        l.add(new Rule("Банов на игрока: " + s.dpBans, "Нажмите, чтобы переключить 1 → 2 → 3", s.dpBanOn, s.dpBanOn, true, true,
            () -> { if (cfg() && s.dpBanOn) FriendRunClient.dpSetNum("dpBans", s.dpBans % DraftPlus.BANS_MAX + 1); }));
        l.add(new Rule("Роли", "Специализации с пассивным бонусом к добыче и крафту", s.dpRoleOn, true, false, false,
            () -> { if (cfg()) FriendRunClient.dpSetFlag("dpRoleOn", !s.dpRoleOn); }));
        for (DraftPlus.Role r : DraftPlus.ROLES) {
            boolean on = !s.dpOffRoles.contains(r.id());
            l.add(new Rule(r.name(), r.desc(), on, s.dpRoleOn, true, false,
                () -> { if (cfg() && s.dpRoleOn) FriendRunClient.dpSetStr("dpOffRoles", toggled(s.dpOffRoles, r.id())); }));
        }
        l.add(new Rule("Монетка", "Решает, кто (или какая команда) первым делает бан и пик", s.dpCoinOn, true, false, false,
            () -> { if (cfg()) FriendRunClient.dpSetFlag("dpCoinOn", !s.dpCoinOn); }));
        l.add(new Rule("Хаос-событие", "Случайное событие меняет правила матча", s.dpModOn, true, false, false,
            () -> { if (cfg()) FriendRunClient.dpSetFlag("dpModOn", !s.dpModOn); }));
        for (DraftPlus.Modifier m : DraftPlus.MODIFIERS) {
            boolean on = !s.dpOffMods.contains(m.id());
            l.add(new Rule(m.name(), m.desc(), on, s.dpModOn, true, false,
                () -> { if (cfg() && s.dpModOn) FriendRunClient.dpSetStr("dpOffMods", toggled(s.dpOffMods, m.id())); }));
        }
        return l;
    }

    private static String toggled(java.util.Set<String> off, String id) {
        java.util.Set<String> n = new java.util.TreeSet<>(off);
        if (!n.remove(id)) n.add(id);
        return String.join(",", n);
    }

    private void clickRule(int slot) {
        List<Rule> l = rules();
        int idx = scroll + slot;
        if (idx >= 0 && idx < l.size() && l.get(idx).enabled()) l.get(idx).act().run();
    }

    private void scrollBy(int d) {
        scroll = Math.max(0, Math.min(Math.max(0, rules().size() - rows), scroll + d));
    }

    /** Колесо мыши (без @Override: если сигнатура в версии игры другая — мод всё равно соберётся). */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab != 1) return false;
        scrollBy(scrollY > 0 ? -2 : 2);
        return true;
    }

    private void clickSlot(int team, int slot) {
        RunState s = FriendRunClient.STATE;
        if (!s.dpTeam) { say("Выберите формат «Командный» на вкладке «Формат»"); return; }
        if (!FriendRunClient.dpCanJoinTeam()) { say("Сейчас идёт подготовка или матч — команды закрыты"); return; }
        List<String> m = DraftPlus.members(s.dpRoster, team);
        String me = FriendRunClient.myName();
        if (slot < m.size()) {
            if (m.get(slot).equals(me)) FriendRunClient.dpJoinTeam(0);   // клик по себе — выйти из команды
            return;
        }
        if (slot >= s.dpTeamSize) return;
        if (m.size() >= s.dpTeamSize) { say("Команда заполнена"); return; }
        FriendRunClient.dpJoinTeam(team);
    }

    @Override
    public void onClose() { Mc.setScreen(parent); }

    // ---------- отрисовка ----------
    private static String trunc(String s, int maxW) { return Ui.trunc(s, maxW); }

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

    private static void sw(GuiGraphicsExtractor g, int x, int cy, boolean on, boolean enabled) {
        int track = on ? (enabled ? Ui.GREEN : Ui.GREEN_LINE) : 0xFF3A4658;
        Gfx.rect(g, x, cy - 5, x + 22, cy + 5, track);
        Gfx.rect(g, on ? x + 13 : x + 1, cy - 4, on ? x + 21 : x + 9, cy + 4, enabled ? 0xFFFFFFFF : 0xFFAAAAAA);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        RunState s = FriendRunClient.STATE;
        boolean t0 = tab == 0, t1 = tab == 1, t2 = tab == 2;
        boolean canCfg = FriendRunClient.dpCanConfigure(), canJoin = FriendRunClient.dpCanJoinTeam();
        int cap = capTeam();
        List<Rule> rl = rules();
        scrollBy(0);

        // видимость
        bFfa.visible = t0; bTeam.visible = t0; bType.visible = t0;
        for (Button b : bSize) b.visible = t0 && s.dpTeam;
        bUp.visible = t1; bDown.visible = t1;
        for (int i = 0; i < ruleSlots.length; i++) ruleSlots[i].visible = t1 && scroll + i < rl.size();
        for (int t = 0; t < 2; t++) for (int k = 0; k < DraftPlus.TEAM_MAX; k++) bSlot[t][k].visible = t2 && s.dpTeam && k < s.dpTeamSize;
        bRandom.visible = t2 && s.dpTeam && s.amHost;
        boolean showName = t2 && s.dpTeam && cap > 0 && canJoin;
        nameBox.visible = showName;
        bSave.visible = showName;
        if (showName && nameFor != cap) { nameFor = cap; nameBox.setValue(cap == 1 ? s.dpTeamNameA : s.dpTeamNameB); }
        if (!showName) nameFor = -1;

        Gfx.rect(g, 0, 0, width, height, 0xB5080D14);
        Ui.panel(g, px, py, pw, ph);
        Ui.bold(g, "ДРАФТ+ · НАСТРОЙКА МАТЧА", px + 12, py + 11, Ui.GOLD);
        boolean flashing = System.currentTimeMillis() < flashUntil;
        String note = flashing ? flash
            : !s.amHost ? "Настройки задаёт хост (только просмотр). Занять команду может каждый."
            : canCfg ? "Вы хост: изменения сразу видят все игроки лобби" : "Идёт подготовка или матч: настройки закрыты";
        Gfx.text(g, trunc(note, iw), ix, py + 28, flashing ? Ui.ORANGE : (s.amHost && canCfg ? Ui.GREEN : Ui.MUTED));
        if (showName) Ui.frame(g, rName.x(), rName.y(), rName.w(), rName.h(), Ui.SECTION, Ui.LINE);

        super.extractRenderState(g, mx, my, delta);

        Ui.button(g, rDone, rDone.in(mx, my), true, Ui.Kind.GOLD);
        Gfx.centered(g, "Готово", rDone.x() + rDone.w() / 2, rDone.y() + 5, 0xFF1A1200);
        for (int i = 0; i < 3; i++) {
            boolean sel = tab == i;
            Ui.button(g, rTab[i], rTab[i].in(mx, my), true, sel ? Ui.Kind.GOLD : Ui.Kind.NORMAL);
            Gfx.centered(g, TABS[i], rTab[i].x() + rTab[i].w() / 2, rTab[i].y() + 5, sel ? 0xFF1A1200 : Ui.TEXT);
        }

        if (t0) drawFormat(g, mx, my, s, canCfg);
        else if (t1) drawRules(g, mx, my, rl);
        else drawTeams(g, mx, my, s, canJoin, cap);
    }

    private void drawFormat(GuiGraphicsExtractor g, int mx, int my, RunState s, boolean canCfg) {
        Gfx.text(g, "Формат матча", ix + 2, y0, Ui.GOLD);
        for (int i = 0; i < 2; i++) {
            Ui.R r = i == 0 ? rFfa : rTeam;
            boolean sel = (i == 1) == s.dpTeam;
            Ui.button(g, r, r.in(mx, my) && canCfg, true, sel ? Ui.Kind.GOLD : Ui.Kind.NORMAL);
            int col = sel ? 0xFF1A1200 : Ui.TEXT;
            Gfx.centered(g, i == 0 ? "Каждый сам за себя" : "Командный", r.x() + r.w() / 2, r.y() + 8, col);
            Gfx.centered(g, i == 0 ? "от 1 до " + DraftPlus.FFA_MAX + " игроков" : "две команды", r.x() + r.w() / 2, r.y() + 20, sel ? 0xFF3A2A00 : Ui.MUTED);
        }
        Gfx.text(g, "Размер команды", ix + 2, y0 + 60, s.dpTeam ? Ui.GOLD : Ui.MUTED);
        if (s.dpTeam) {
            for (int i = 0; i < 5; i++) {
                boolean sel = s.dpTeamSize == i + 1;
                Ui.button(g, rSize[i], rSize[i].in(mx, my) && canCfg, true, sel ? Ui.Kind.GOLD : Ui.Kind.NORMAL);
                Gfx.centered(g, (i + 1) + "×" + (i + 1), rSize[i].x() + rSize[i].w() / 2, rSize[i].y() + 8, sel ? 0xFF1A1200 : Ui.TEXT);
            }
        } else {
            Gfx.text(g, "доступно в командном формате", ix + 100, y0 + 60, Ui.MUTED);
        }
        Ui.button(g, rType, rType.in(mx, my) && canCfg, true, Ui.Kind.NORMAL);
        Gfx.text(g, "Что можно выбирать на драфте: " + DraftGoal.draftTypeName(s.draftType), rType.x() + 8, rType.cy() - 4, Ui.TEXT);
        String info = s.dpTeam
            ? "Две команды по " + s.dpTeamSize + " игрока. На вкладке «Команды» каждый занимает место сам, а хост может нажать «Случайные команды». "
              + "Капитан (первый вступивший) переименовывает команду. Команды ходят по очереди, внутри команды — по кругу; первой ходит команда, выбранная монеткой. "
              + "Побеждает команда, захватившая 13 из 25 клеток."
            : "Каждый играет за себя, ходы идут по кругу, первым начинает игрок, выбранный жребием. "
              + "Побеждает первый, кто захватит 13 из 25 клеток.";
        int y = y0 + 134;
        for (String line : wrap(info, iw - 4)) { Gfx.text(g, line, ix + 2, y, Ui.MUTED); y += 11; }
        String err = DraftPlus.startError(s);
        if (err != null) Gfx.text(g, trunc("Сейчас старт невозможен: " + err, iw - 4), ix + 2, y + 6, Ui.ORANGE);
    }

    private void drawRules(GuiGraphicsExtractor g, int mx, int my, List<Rule> rl) {
        for (int i = 0; i < ruleSlots.length; i++) {
            int idx = scroll + i;
            if (idx >= rl.size()) break;
            Rule r = rl.get(idx);
            Ui.R rr = new Ui.R(ix, listY + i * RULE_H, listW, RULE_H - 2);
            Ui.button(g, rr, rr.in(mx, my) && r.enabled(), true, r.on() && r.enabled() ? Ui.Kind.GREEN : Ui.Kind.NORMAL);
            int tx = rr.x() + (r.sub() ? 22 : 8), maxW = rr.w() - (tx - rr.x()) - 40;
            int col = r.enabled() ? Ui.TEXT : Ui.MUTED;
            Gfx.text(g, trunc(r.label(), maxW / 3 + 40), tx, rr.y() + 3, col);
            Gfx.text(g, trunc(r.desc(), maxW), tx, rr.y() + 12, Ui.MUTED);
            if (!r.step()) sw(g, rr.x() + rr.w() - 30, rr.cy(), r.on(), r.enabled());
            else Icons.draw(g, Icons.CHEV_R, rr.x() + rr.w() - 14, rr.cy() - 3, 1, Ui.MUTED);
        }
        Ui.button(g, rUp, rUp.in(mx, my), true, Ui.Kind.NORMAL);
        Ui.button(g, rDown, rDown.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.TRI_UP, rUp.x() + 3, rUp.y() + 5, 1, Ui.MUTED);
        Icons.draw(g, Icons.TRI_DOWN, rDown.x() + 3, rDown.y() + 4, 1, Ui.MUTED);
        Ui.scrollbar(g, rUp.x() + 5, rUp.y() + 15, rows * RULE_H - 30, scroll, rl.size(), rows);
    }

    private void drawTeams(GuiGraphicsExtractor g, int mx, int my, RunState s, boolean canJoin, int cap) {
        if (!s.dpTeam) {
            Gfx.centered(g, "Командная игра выключена", width / 2, y0 + 40, Ui.MUTED);
            Gfx.centered(g, "Выберите формат «Командный» на вкладке «Формат»", width / 2, y0 + 54, Ui.MUTED);
            return;
        }
        String me = FriendRunClient.myName();
        int colW = (iw - 8) / 2;
        for (int t = 0; t < 2; t++) {
            int team = t + 1;
            List<String> m = DraftPlus.members(s.dpRoster, team);
            int x = ix + t * (colW + 8);
            String title = DraftPlus.teamLabel(team == 1 ? s.dpTeamNameA : s.dpTeamNameB);
            Gfx.rect(g, x, y0, x + 3, y0 + 14, TEAM_COL[t]);
            Gfx.text(g, trunc(title, colW - 50), x + 8, y0 + 3, TEAM_COL[t]);
            Ui.right(g, m.size() + "/" + s.dpTeamSize, x + colW - 2, y0 + 3, Ui.MUTED);
            for (int k = 0; k < s.dpTeamSize; k++) {
                Ui.R r = rSlot[t][k];
                boolean mine = k < m.size() && m.get(k).equals(me);
                boolean free = k >= m.size();
                Ui.button(g, r, r.in(mx, my) && canJoin && (free || mine), true, mine ? Ui.Kind.GOLD : free ? Ui.Kind.NORMAL : Ui.Kind.GREEN);
                if (free) {
                    Gfx.text(g, "— свободно: занять —", r.x() + 8, r.cy() - 4, Ui.MUTED);
                } else {
                    Ui.head(g, r.x() + 5, r.cy() - 6, 12, m.get(k));
                    Gfx.text(g, trunc((k == 0 ? "★ " : "") + m.get(k) + (k == 0 ? " (капитан)" : ""), r.w() - 70), r.x() + 22, r.cy() - 4, mine ? 0xFF1A1200 : Ui.TEXT);
                    if (mine) Ui.right(g, "выйти", r.x() + r.w() - 6, r.cy() - 4, 0xFF3A2A00);
                }
            }
        }
        // игроки без команды
        List<String> loose = new ArrayList<>();
        for (RunState.PlayerInfo p : s.players) if (DraftPlus.teamOf(s.dpRoster, p.name()) == 0) loose.add(p.name());
        int uy = y0 + 22 + s.dpTeamSize * (SLOT_H + 2) + 6;
        Gfx.text(g, trunc(loose.isEmpty() ? "Все игроки заняли команды" : "Без команды: " + String.join(", ", loose), iw - 4), ix + 2, uy,
            loose.isEmpty() ? Ui.GREEN : Ui.ORANGE);
        String err = DraftPlus.startError(s);
        if (err != null) Gfx.text(g, trunc(err, iw - 4), ix + 2, uy + 12, Ui.MUTED);

        if (s.amHost) {
            Ui.button(g, rRandom, rRandom.in(mx, my) && canJoin, canJoin, Ui.Kind.GREEN);
            Gfx.centered(g, "Случайные команды", rRandom.x() + rRandom.w() / 2, rRandom.y() + 5, canJoin ? Ui.TEXT : Ui.MUTED);
        }
        if (cap > 0 && canJoin) {
            Gfx.text(g, "Название команды:", ix + 2, rSave.y() + 5, Ui.MUTED);
            Ui.button(g, rSave, rSave.in(mx, my), true, Ui.Kind.NORMAL);
            Gfx.centered(g, "Сохранить", rSave.x() + rSave.w() / 2, rSave.y() + 5, Ui.TEXT);
        } else if (cap == 0 && canJoin) {
            Gfx.text(g, "Название команды меняет её капитан — первый, кто в неё вступил", ix + 2, rSave.y() + 5, Ui.MUTED);
        }
    }
}
