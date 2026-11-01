package dev.friendrun;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;

/**
 * «Драфт+» — экспериментальный режим на базе классического драфта (Draft). Классический режим не затронут:
 * вся новая логика живёт здесь, а в Lobby/FriendRunClient стоят только точки вызова.
 *
 * Ход матча (всё считает хост, остальные читают документ лобби):
 *   1. BAN  — игроки по очереди банят предметы/цели, они исключаются из пула драфта.
 *   2. ROLE — каждый выбирает роль (шахтёр, фермер, охотник…) с пассивным бонусом к добыче/крафту.
 *   3. PICK — драфт целей на общую сетку 5×5 (копия логики классики, но без забаненных целей).
 *   4. MATCH — случайный модификатор («хаос-событие») + захват клеток; побеждает первый, у кого 13 из 25.
 *   5. Рейтинг: места по захваченным клеткам + личный вклад (редкие цели, удачные выборы на драфте).
 *
 * В матче используется та же механика захвата клеток, что и в классике (roundGame = DRAFT), поэтому HUD и
 * обнаружение целей переиспользуются; отличие матча Драфт+ — флаг rDraftPlus в документе лобби.
 */
public final class DraftPlus {
    private DraftPlus() {}

    public static final int BOARD = 5, CELLS = BOARD * BOARD, WIN_CELLS = CELLS / 2 + 1;   // 13 из 25
    public static final int BANS_DEFAULT = 2, BANS_MAX = 3;
    public static final int MAX_BONUS = 12;
    static final long BAN_TURN_MS = 45_000L, ROLE_PHASE_MS = 45_000L;
    private static final long CLAIM_SETTLE_MS = 500L;

    // ======================================================================================
    //  Роли
    // ======================================================================================
    /** effect/amp — постоянный эффект роли (пусто — нет); дополнительно роли дают бонусную добычу (см. DraftPlusBonus). */
    public record Role(String id, String name, String desc, String effect, int amp) {}

    public static final Role[] ROLES = {
        new Role("miner", "Шахтёр", "Руды: 35% шанс бонусной добычи. Постоянная Спешка I.", "haste", 0),
        new Role("farmer", "Фермер", "Созревший урожай: 50% шанс бонусной порции.", "", 0),
        new Role("hunter", "Охотник", "С убитых мобов: 35% шанс бонусного дропа. Скорость I.", "speed", 0),
        new Role("lumberjack", "Дровосек", "Брёвна: 40% шанс бонусной добычи. Постоянная Спешка I.", "haste", 0),
        new Role("crafter", "Ремесленник", "Крафт: 25% шанс получить копию результата.", "", 0),
    };

    public static Role roleById(String id) {
        if (id == null) return null;
        for (Role r : ROLES) if (r.id().equals(id)) return r;
        return null;
    }

    // ======================================================================================
    //  Модификаторы матча («хаос-события»)
    // ======================================================================================
    public record Modifier(String id, String name, String desc, String[] commands) {}

    public static final Modifier[] MODIFIERS = {
        new Modifier("haste", "Ускоренная работа", "Все получают Спешку II и Скорость I: добыча и ходьба быстрее.",
            new String[] {"effect give @a minecraft:haste infinite 1 true", "effect give @a minecraft:speed infinite 0 true"}),
        new Modifier("double_craft", "Двойной крафт", "Каждый крафт даёт дополнительную копию результата.", new String[0]),
        new Modifier("no_ores", "Запрет руд", "Руды ломать нельзя. Ищите в сундуках, у мобов и в торговле.", new String[0]),
        new Modifier("no_crops", "Запрет урожая", "Посевы, тыквы и арбузы ломать нельзя.", new String[0]),
        new Modifier("night", "Вечная ночь", "Время зафиксировано на полночь.", new String[] {"time set midnight"}),
        new Modifier("moon", "Лунная гравитация", "Вы прыгаете выше и падаете медленнее.", new String[] {Chaos.attr("gravity", "0.02")}),
        new Modifier("generous", "Щедрая земля", "Бонусную добычу руд, дерева и урожая получают все, а не только роли.", new String[0]),
    };

    public static Modifier modifierById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Modifier m : MODIFIERS) if (m.id().equals(id)) return m;
        return null;
    }

    public static String rollModifier(long seed) {
        return MODIFIERS[new Random(seed ^ 0x5DEECE66DL).nextInt(MODIFIERS.length)].id();
    }

    // ======================================================================================
    //  Пул целей, баны, редкость
    // ======================================================================================
    private static final Set<String> KEY_ITEMS = Set.of("diamond", "emerald", "blaze_rod", "ender_pearl", "ghast_tear",
        "magma_cream", "nautilus_shell", "turtle_scute", "phantom_membrane", "obsidian", "prismarine_shard",
        "amethyst_shard", "golden_apple", "quartz", "glowstone_dust", "honey_bottle");

    private static int itemRarity(String id) {
        if (ItemPicker.isHard(id)) return 2;
        return KEY_ITEMS.contains(id) ? 1 : 0;
    }

    /** Вес цели для вклада: 0 — обычная, 1 — ключевая, 2 — редкая. */
    public static int rarity(String encoded) {
        DraftGoal g = DraftGoal.parse(encoded);
        if (g == null) return 0;
        switch (g.type()) {
            case ITEM: return itemRarity(g.id());
            case QUEST: {
                if (g.id().equals("enter_end")) return 2;
                if (g.id().equals("enter_nether")) return 1;
                String item = DraftGoal.Quest.itemId(g.id());
                return item == null ? 0 : itemRarity(item);
            }
            default: {
                String id = g.id();
                if (id.startsWith("end/") && !id.endsWith("/root")) return 2;
                if (id.startsWith("nether/") && !id.endsWith("/root")) return 1;
                return id.equals("adventure/totem_of_undying") || id.equals("story/follow_ender_eye") ? 1 : 0;
            }
        }
    }

    /** Все цели выбранного типа драфта (в закодированном виде) — из них выбирают баны. */
    public static List<String> candidates(int draftType) {
        List<String> out = new ArrayList<>();
        if (draftType == 0 || draftType == 3) {
            Set<String> seen = new HashSet<>();
            for (Item item : ItemPicker.poolItems()) if (seen.add(ItemPicker.idOf(item))) out.add("item:" + ItemPicker.idOf(item));
            if (seen.size() < 50) {   // как в классике: 5×5 нужно минимум 26 целей
                for (Item item : ItemPicker.baseItems()) if (seen.add(ItemPicker.idOf(item))) out.add("item:" + ItemPicker.idOf(item));
            }
        }
        if (draftType == 1 || draftType == 3) for (String id : DraftGoal.Quest.ids()) out.add("quest:" + id);
        if (draftType == 2 || draftType == 3) for (String id : DraftGoal.Advancement.ids()) out.add("advancement:" + id);
        return out;
    }

    /** Две цели на очередной выбор — копия draftOptions классики, но забаненные цели исключены. */
    static String[] draftOptions(long seed, int pick, List<String> board, List<String> banned, int draftType) {
        Set<String> used = new HashSet<>(board);
        used.addAll(banned);
        Random random = new Random(seed ^ (0x9E3779B97F4A7C15L * (pick + 1L)));
        List<String> items = new ArrayList<>(), quests = new ArrayList<>(), advs = new ArrayList<>();
        for (String c : candidates(draftType)) {
            if (used.contains(c)) continue;
            if (c.startsWith("item:")) items.add(c); else if (c.startsWith("quest:")) quests.add(c); else advs.add(c);
        }
        List<String> pool = new ArrayList<>();
        if (draftType == 3) {   // смешанный: предметов столько же, сколько заданий и достижений вместе
            Collections.shuffle(items, random);
            pool.addAll(items.subList(0, Math.min(items.size(), quests.size() + advs.size())));
            pool.addAll(quests);
            pool.addAll(advs);
        } else {
            pool.addAll(items); pool.addAll(quests); pool.addAll(advs);
        }
        Collections.shuffle(pool, random);
        if (pool.size() < 2) throw new IllegalStateException("В Драфте+ после банов не осталось целей");
        return new String[] {pool.get(0), pool.get(1)};
    }

    // ======================================================================================
    //  Настройка матча и команды
    // ======================================================================================
    public static final int FFA_MAX = 10, TEAM_MAX = 5;
    static final long COIN_MS = 5_000L;

    /** Настройки матча из документа лобби. */
    public record Cfg(boolean team, int teamSize, boolean banOn, int bans, boolean roleOn, boolean modOn, boolean coinOn,
                      Set<String> offMods, Set<String> offRoles) {}

    private static boolean flag(JsonObject doc, String key) { return !doc.has(key) || Lobby.bool(doc, key); }

    public static Cfg cfg(JsonObject doc) {
        long ts = Lobby.num(doc, "dpTeamSize");
        return new Cfg(Lobby.num(doc, "dpFormat") == 1, (int) Math.max(1, Math.min(TEAM_MAX, ts == 0 ? 2 : ts)),
            flag(doc, "dpBanOn"), bansCfg(doc, "dpBans"), flag(doc, "dpRoleOn"), flag(doc, "dpModOn"), flag(doc, "dpCoinOn"),
            new HashSet<>(csv(Lobby.str(doc, "dpOffMods"))), new HashSet<>(csv(Lobby.str(doc, "dpOffRoles"))));
    }

    /** Состав: ник -> {команда 0/1/2, время вступления}. */
    public static Map<String, long[]> roster(JsonObject players) {
        Map<String, long[]> m = new HashMap<>();
        for (String n : players.keySet()) m.put(n, new long[] {Lobby.num(Lobby.obj(players, n), "team"), Lobby.num(Lobby.obj(players, n), "teamAt")});
        return m;
    }

    public static int teamOf(Map<String, long[]> roster, String name) {
        long[] r = roster.get(name);
        return r == null ? 0 : (int) r[0];
    }

    /** Участники команды: сначала вступившие раньше (первый — капитан), затем по нику. */
    public static List<String> members(Map<String, long[]> roster, int team) {
        List<String> out = new ArrayList<>();
        for (var e : roster.entrySet()) if (e.getValue()[0] == team) out.add(e.getKey());
        out.sort((a, b) -> {
            long ta = roster.get(a)[1], tb = roster.get(b)[1];
            return ta != tb ? Long.compare(ta, tb) : a.compareTo(b);
        });
        return out;
    }

    public static String captain(Map<String, long[]> roster, int team) {
        List<String> m = members(roster, team);
        return m.isEmpty() ? "" : m.get(0);
    }

    public static String teamName(JsonObject doc, int team) {
        String n = Lobby.str(doc, team == 1 ? "dpTeamNameA" : "dpTeamNameB");
        return n.isBlank() ? (team == 1 ? "Красные" : "Синие") : n;
    }

    /** «Команда Название» — так команда называется в чате и на панели итога. */
    public static String teamLabel(String name) { return "Команда " + name; }

    /** Ник игрока, чей сейчас ход (баны и драфт). i — номер хода с нуля. first — ник (FFA) или "1"/"2" (команды). */
    public static String turnPlayer(Cfg c, Map<String, long[]> roster, List<String> names, String first, int i) {
        if (c.team()) {
            List<String> a = members(roster, 1), b = members(roster, 2);
            if (!a.isEmpty() && !b.isEmpty()) {
                int firstTeam = "2".equals(first) ? 2 : 1;
                List<String> side = ((i % 2 == 0) == (firstTeam == 1)) ? a : b;   // команды ходят по очереди
                return side.get((i / 2) % side.size());                            // внутри команды — по кругу
            }
        }
        int start = Math.max(0, names.indexOf(first));
        return names.get((start + i) % names.size());
    }

    public static List<Role> enabledRoles(Set<String> off) {
        List<Role> out = new ArrayList<>();
        for (Role r : ROLES) if (!off.contains(r.id())) out.add(r);
        return out;
    }

    public static boolean rolesActive(Cfg c) { return c.roleOn() && !enabledRoles(c.offRoles()).isEmpty(); }

    /** Почему матч нельзя начать (null — всё в порядке). Используется кнопкой «Старт» и командой /go. */
    public static String startError(RunState s) {
        int n = s.players.size();
        if (!s.dpTeam) return n > FFA_MAX ? "Каждый сам за себя: не больше " + FFA_MAX + " игроков (сейчас " + n + ")" : null;
        int a = 0, b = 0;
        for (RunState.PlayerInfo p : s.players) {
            long[] r = s.dpRoster.get(p.name());
            int t = r == null ? 0 : (int) r[0];
            if (t == 1) a++; else if (t == 2) b++;
        }
        if (a + b < n) return "Все игроки должны занять команду (кнопка «Настройка матча»)";
        if (a == 0 || b == 0) return "В каждой команде нужен хотя бы один игрок";
        if (a > s.dpTeamSize || b > s.dpTeamSize) return "Команд больше выбранного размера " + s.dpTeamSize + "×" + s.dpTeamSize;
        return null;
    }

    /** Краткое описание настроек матча для кнопки в лобби. */
    public static String summary(RunState s) {
        StringBuilder sb = new StringBuilder("Матч: ");
        sb.append(s.dpTeam ? "команды " + s.dpTeamSize + "×" + s.dpTeamSize : "каждый сам за себя");
        sb.append(" · баны ").append(s.dpBanOn ? s.dpBans : "выкл");
        sb.append(" · роли ").append(s.dpRoleOn ? "вкл" : "выкл");
        sb.append(" · событие ").append(s.dpModOn ? "вкл" : "выкл");
        return sb.toString();
    }

    // ======================================================================================
    //  Хост: фазы подготовки
    // ======================================================================================
    private static List<String> csv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.isBlank()) Collections.addAll(out, raw.split(",", -1));
        return out;
    }

    private static List<String> sorted(Set<String> names) {
        List<String> l = new ArrayList<>(names);
        Collections.sort(l);
        return l;
    }

    private static int bansCfg(JsonObject doc, String key) {
        long v = Lobby.num(doc, key);
        return (int) Math.max(1, Math.min(BANS_MAX, v == 0 ? BANS_DEFAULT : v));
    }

    private static long parse(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    private static void clearPlayerChoices(JsonObject u, Set<String> names) {
        for (String n : names) {
            u.addProperty("players/" + n + "/dpBanChoice", "");
            u.addProperty("players/" + n + "/dpBanChoicePick", -1);
            u.addProperty("players/" + n + "/dpHover", "");
        }
    }

    /** Один шаг подготовки. Вызывается из Lobby.hostLogic, когда все игроки готовы и драфт+ ещё не завершён. */
    public static void hostStep(JsonObject updates, JsonObject doc, JsonObject players, long now) {
        List<String> names = sorted(players.keySet());
        if (names.isEmpty()) return;
        switch (Lobby.str(doc, "dpPhase")) {
            case "COIN" -> coinStep(updates, doc, players, names, now);
            case "BAN" -> banStep(updates, doc, players, names, now);
            case "ROLE" -> roleStep(updates, doc, players, names, now);
            case "PICK" -> pickStep(updates, doc, players, names, now);
            default -> start(updates, doc, players, names, now);
        }
    }

    private static void start(JsonObject u, JsonObject doc, JsonObject players, List<String> names, long now) {
        Cfg c = cfg(doc);
        int type = (int) Math.max(0, Math.min(3, Lobby.num(doc, "draftType")));
        Random rnd = new Random(parse(Lobby.str(doc, "itemSeed")) ^ now);
        // «Монетка»: решает, кто (или какая команда) начинает баны и пики.
        String first;
        int face = -1;   // 0 — орёл, 1 — решка (показываем только когда исхода ровно два)
        if (c.team()) {
            int t = c.coinOn() ? (rnd.nextBoolean() ? 1 : 2) : 1;
            first = String.valueOf(t);
            face = t == 1 ? 0 : 1;
        } else {
            int k = c.coinOn() ? rnd.nextInt(names.size()) : 0;
            first = names.get(k);
            if (names.size() == 2) face = k;
        }
        u.addProperty("dpDone", false);
        u.addProperty("dpRunType", type);
        u.addProperty("dpBanned", "");
        u.addProperty("dpBanTotal", 0);
        u.addProperty("dpBanTurn", "");
        u.addProperty("dpPickers", "");
        u.addProperty("dpModId", "");
        u.addProperty("dpFirst", first);
        u.addProperty("dpCoinFace", face);
        u.addProperty("dpCoinAt", now);
        u.addProperty("dpPhaseAt", now);
        clearPlayerChoices(u, players.keySet());
        for (String n : names) u.addProperty("players/" + n + "/dpRole", "");
        if (c.coinOn()) { u.addProperty("dpPhase", "COIN"); return; }
        nextAfterCoin(u, doc, players, names, c, first, now);
    }

    private static void coinStep(JsonObject u, JsonObject doc, JsonObject players, List<String> names, long now) {
        if (now - Lobby.num(doc, "dpPhaseAt") < COIN_MS) return;   // игроки смотрят на бросок
        nextAfterCoin(u, doc, players, names, cfg(doc), Lobby.str(doc, "dpFirst"), now);
    }

    private static void nextAfterCoin(JsonObject u, JsonObject doc, JsonObject players, List<String> names, Cfg c, String first, long now) {
        int type = (int) Lobby.num(doc, "dpRunType");
        // Нельзя банить так много, чтобы на сетку не хватило целей (нужно 25 + 1 про запас).
        int total = c.banOn() ? Math.max(0, Math.min(c.bans() * names.size(), candidates(type).size() - CELLS - 2)) : 0;
        if (total > 0) {
            u.addProperty("dpPhase", "BAN");
            u.addProperty("dpBanTotal", total);
            u.addProperty("dpBanTurn", turnPlayer(c, roster(players), names, first, 0));
            u.addProperty("dpTurnAt", now);
            u.addProperty("dpPhaseAt", now);
        } else {
            nextAfterBans(u, doc, players, names, c, now);
        }
    }

    private static void nextAfterBans(JsonObject u, JsonObject doc, JsonObject players, List<String> names, Cfg c, long now) {
        if (rolesActive(c)) {
            u.addProperty("dpPhase", "ROLE");
            u.addProperty("dpBanTurn", "");
            u.addProperty("dpPhaseAt", now);
        } else {
            toPick(u, doc, players, names, c);
        }
    }

    private static void banStep(JsonObject u, JsonObject doc, JsonObject players, List<String> names, long now) {
        Cfg c = cfg(doc);
        List<String> banned = csv(Lobby.str(doc, "dpBanned"));
        int total = (int) Lobby.num(doc, "dpBanTotal");
        if (banned.size() >= total) { nextAfterBans(u, doc, players, names, c, now); return; }
        String first = Lobby.str(doc, "dpFirst");
        String turn = turnPlayer(c, roster(players), names, first, banned.size());
        if (!turn.equals(Lobby.str(doc, "dpBanTurn"))) {   // ход сменился (или игрок вышел)
            u.addProperty("dpBanTurn", turn);
            u.addProperty("dpTurnAt", now);
            return;
        }
        JsonObject p = Lobby.obj(players, turn);
        String choice = Lobby.str(p, "dpBanChoice");
        int type = (int) Lobby.num(doc, "dpRunType");
        boolean valid = Lobby.num(p, "dpBanChoicePick") == banned.size() && !choice.isEmpty()
            && !banned.contains(choice) && candidates(type).contains(choice);
        boolean timeout = now - Lobby.num(doc, "dpTurnAt") > BAN_TURN_MS;
        if (!valid && !timeout) return;
        String ban = choice;
        if (!valid) {   // время вышло — случайный бан, чтобы игра не зависла
            List<String> pool = candidates(type);
            pool.removeAll(banned);
            long seed = parse(Lobby.str(doc, "itemSeed")) ^ (banned.size() * 0x9E3779B9L);
            ban = pool.get(new Random(seed).nextInt(pool.size()));
        }
        banned.add(ban);
        u.addProperty("dpBanned", String.join(",", banned));
        u.addProperty("players/" + turn + "/dpBanChoice", "");
        u.addProperty("players/" + turn + "/dpBanChoicePick", -1);
        u.addProperty("players/" + turn + "/dpHover", "");
        if (banned.size() >= total) { nextAfterBans(u, doc, players, names, c, now); return; }
        u.addProperty("dpBanTurn", turnPlayer(c, roster(players), names, first, banned.size()));
        u.addProperty("dpTurnAt", now);
    }

    private static void roleStep(JsonObject u, JsonObject doc, JsonObject players, List<String> names, long now) {
        Cfg c = cfg(doc);
        Set<String> allowed = new HashSet<>();
        for (Role r : enabledRoles(c.offRoles())) allowed.add(r.id());
        boolean all = true;
        for (String n : names) if (!allowed.contains(Lobby.str(Lobby.obj(players, n), "dpRole"))) { all = false; break; }
        if (!all && now - Lobby.num(doc, "dpPhaseAt") <= ROLE_PHASE_MS) return;
        if (!all) {   // не успели выбрать — роль достаётся случайно
            Random r = new Random(parse(Lobby.str(doc, "itemSeed")));
            List<Role> pool = enabledRoles(c.offRoles());
            for (String n : names)
                if (!allowed.contains(Lobby.str(Lobby.obj(players, n), "dpRole")))
                    u.addProperty("players/" + n + "/dpRole", pool.get(r.nextInt(pool.size())).id());
        }
        toPick(u, doc, players, names, c);
    }

    /** Старт драфта целей (копия startDraft классики) — уже без забаненных целей. Первым ходит тот, кого выбрала монетка. */
    private static void toPick(JsonObject u, JsonObject doc, JsonObject players, List<String> names, Cfg c) {
        long seed = parse(Lobby.str(doc, "itemSeed"));
        int type = (int) Lobby.num(doc, "dpRunType");
        String[] offers = draftOptions(seed, 0, List.of(), csv(Lobby.str(doc, "dpBanned")), type);
        u.addProperty("dpPhase", "PICK");
        u.addProperty("draftActive", true);
        u.addProperty("draftComplete", false);
        u.addProperty("draftPickCount", 0);
        u.addProperty("draftTurn", turnPlayer(c, roster(players), names, Lobby.str(doc, "dpFirst"), 0));
        u.addProperty("draftSeed", Long.toString(seed));
        u.addProperty("draftBoardIds", "");
        u.addProperty("draftOwnersIds", String.join(",", Collections.nCopies(CELLS, "-")));
        u.addProperty("draftOfferA", offers[0]);
        u.addProperty("draftOfferB", offers[1]);
        u.addProperty("dpPickers", "");
        for (String n : players.keySet()) {
            u.addProperty("players/" + n + "/draftChoice", -1);
            u.addProperty("players/" + n + "/draftChoicePick", -1);
            u.addProperty("players/" + n + "/draftClaim", -1);
            u.addProperty("players/" + n + "/draftClaimRound", 0);
            u.addProperty("players/" + n + "/draftClaimAt", 0);
            u.addProperty("players/" + n + "/dpHover", "");
        }
    }

    /** Ход драфта (копия advanceDraft классики) + запись, кто выбрал каждую цель — нужно для вклада в MMR. */
    private static void pickStep(JsonObject u, JsonObject doc, JsonObject players, List<String> names, long now) {
        if (!Lobby.bool(doc, "draftActive")) return;
        Cfg c = cfg(doc);
        int pick = (int) Lobby.num(doc, "draftPickCount");
        String first = Lobby.str(doc, "dpFirst");
        String turn = turnPlayer(c, roster(players), names, first, pick);
        if (!turn.equals(Lobby.str(doc, "draftTurn"))) {
            u.addProperty("draftTurn", turn);
            return;
        }
        JsonObject player = Lobby.obj(players, turn);
        if (Lobby.num(player, "draftChoicePick") != pick) return;
        int choice = (int) Lobby.num(player, "draftChoice");
        String selected = Lobby.str(doc, choice == 1 ? "draftOfferB" : "draftOfferA");
        List<String> board = csv(Lobby.str(doc, "draftBoardIds"));
        if (selected.isBlank() || board.contains(selected)) return;
        board.add(selected);
        List<String> pickers = csv(Lobby.str(doc, "dpPickers"));
        pickers.add(turn);
        u.addProperty("draftBoardIds", String.join(",", board));
        u.addProperty("dpPickers", String.join(",", pickers));
        u.addProperty("draftPickCount", board.size());
        u.addProperty("players/" + turn + "/draftChoice", -1);
        u.addProperty("players/" + turn + "/draftChoicePick", -1);
        u.addProperty("players/" + turn + "/dpHover", "");
        if (board.size() >= CELLS) {
            u.addProperty("draftActive", false);
            u.addProperty("draftComplete", true);
            u.addProperty("draftTurn", "");
            u.addProperty("draftOfferA", "");
            u.addProperty("draftOfferB", "");
            u.addProperty("dpPhase", "DONE");
            u.addProperty("dpDone", true);
            // Хаос-событие матча выпадает сразу после драфта (из включённых) — игроки узнают его до старта отсчёта.
            List<String> mods = new ArrayList<>();
            if (c.modOn()) for (Modifier m : MODIFIERS) if (!c.offMods().contains(m.id())) mods.add(m.id());
            u.addProperty("dpModId", mods.isEmpty() ? "" : mods.get(new Random(parse(Lobby.str(doc, "draftSeed")) + now).nextInt(mods.size())));
            return;
        }
        long seed = parse(Lobby.str(doc, "draftSeed"));
        String[] offers = draftOptions(seed, board.size(), board, csv(Lobby.str(doc, "dpBanned")), (int) Lobby.num(doc, "dpRunType"));
        u.addProperty("draftTurn", turnPlayer(c, roster(players), names, first, board.size()));
        u.addProperty("draftOfferA", offers[0]);
        u.addProperty("draftOfferB", offers[1]);
    }

    /** Сбросить поля Драфта+ (конец матча, выход хоста из мира). Настройки матча и команды остаются. */
    public static void resetFields(JsonObject u, Set<String> playerNames) {
        u.addProperty("dpPhase", "");
        u.addProperty("dpDone", false);
        u.addProperty("dpBanned", "");
        u.addProperty("dpBanTurn", "");
        u.addProperty("dpBanTotal", 0);
        u.addProperty("dpPickers", "");
        u.addProperty("dpModId", "");
        u.addProperty("dpFirst", "");
        u.addProperty("dpCoinAt", 0);
        for (String n : playerNames) {
            u.addProperty("players/" + n + "/dpRole", "");
            u.addProperty("players/" + n + "/dpBanChoice", "");
            u.addProperty("players/" + n + "/dpBanChoicePick", -1);
            u.addProperty("players/" + n + "/dpHover", "");
        }
    }

    // ======================================================================================
    //  Хост: матч — захват клеток и итог
    // ======================================================================================
    /** Вклад игрока: {вес редких целей, удачные выборы, захвачено клеток}. */
    public static Map<String, int[]> contributions(List<String> board, List<String> owners, List<String> pickers) {
        Map<String, int[]> m = new HashMap<>();
        for (int i = 0; i < Math.min(board.size(), owners.size()); i++) {
            String owner = owners.get(i);
            if (owner.equals("-") || owner.isEmpty()) continue;
            int[] c = m.computeIfAbsent(owner, k -> new int[3]);
            c[0] += rarity(board.get(i));
            c[2]++;
            if (i < pickers.size() && pickers.get(i).equals(owner)) c[1]++;   // сам выбрал на драфте и сам же выполнил
        }
        return m;
    }

    /** Очки MMR за вклад: редкая цель = 2 за единицу веса, удачный выбор = 1; не больше MAX_BONUS. */
    public static int bonusOf(int[] c) { return Math.min(MAX_BONUS, c[0] * 2 + c[1]); }

    /** Сколько клеток уже занято участниками команды. */
    static int teamCells(List<String> owners, Map<String, long[]> roster, int team) {
        int n = 0;
        for (String o : owners) if (!o.equals("-") && teamOf(roster, o) == team) n++;
        return n;
    }

    /** Захват клеток (копия processDraftClaims классики) + итог матча с рейтингом за вклад. В командном режиме клетки считаются командой. */
    static void processClaims(Lobby lobby, JsonObject updates, JsonObject doc, JsonObject players, long startAt, long roundId) {
        Cfg cfg = cfg(doc);
        Map<String, long[]> roster = roster(players);
        List<String> owners = csv(Lobby.str(doc, "draftOwnersIds"));
        while (owners.size() < CELLS) owners.add("-");
        if (owners.size() > CELLS) owners = new ArrayList<>(owners.subList(0, CELLS));
        List<String> names = new ArrayList<>(players.keySet());
        names.sort((a, b) -> {
            long ta = Lobby.num(Lobby.obj(players, a), "draftClaimAt"), tb = Lobby.num(Lobby.obj(players, b), "draftClaimAt");
            return ta != tb ? Long.compare(ta, tb) : a.compareTo(b);
        });
        String winner = null;
        int winnerTeam = 0;
        long winnerMs = 0;
        for (String name : names) {
            JsonObject p = Lobby.obj(players, name);
            int idx = (int) Lobby.num(p, "draftClaim");
            long claimAt = Lobby.num(p, "draftClaimAt");
            if (Lobby.num(p, "draftClaimRound") != roundId || idx < 0 || idx >= CELLS) continue;
            if (claimAt <= 0 || System.currentTimeMillis() - claimAt < CLAIM_SETTLE_MS) continue;
            if (owners.get(idx).equals("-")) {
                owners.set(idx, name);
                long score = Lobby.num(p, "score") + 1;
                long ms = Math.max(0, claimAt - startAt);
                updates.addProperty("players/" + name + "/score", score);
                updates.addProperty("players/" + name + "/lastMs", ms);
                p.addProperty("score", score);
                p.addProperty("lastMs", ms);
                if (winner == null) {
                    int t = teamOf(roster, name);
                    if (cfg.team() ? (t > 0 && teamCells(owners, roster, t) >= WIN_CELLS) : score >= WIN_CELLS) {
                        winner = name;
                        winnerTeam = cfg.team() ? t : 0;
                        winnerMs = ms;
                    }
                }
            }
            updates.addProperty("players/" + name + "/draftClaim", -1);
            updates.addProperty("players/" + name + "/draftClaimRound", 0);
            updates.addProperty("players/" + name + "/draftClaimAt", 0);
            if (winner != null) break;
        }
        updates.addProperty("draftOwnersIds", String.join(",", owners));
        if (winner == null) return;

        final List<String> finalOwners = owners;
        final int wt = winnerTeam;
        Map<String, int[]> contrib = contributions(csv(Lobby.str(doc, "draftBoardIds")), finalOwners, csv(Lobby.str(doc, "dpPickers")));
        Map<String, Integer> bonus = new HashMap<>();
        StringBuilder cs = new StringBuilder();
        for (var e : contrib.entrySet()) {
            int b = bonusOf(e.getValue());
            bonus.put(e.getKey(), b);
            if (cs.length() > 0) cs.append(';');
            cs.append(e.getKey()).append(':').append(e.getValue()[0]).append(':').append(e.getValue()[1]).append(':')
              .append(e.getValue()[2]).append(':').append(b);
        }
        String label = wt > 0 ? teamLabel(teamName(doc, wt)) : winner;
        long shownScore = wt > 0 ? teamCells(finalOwners, roster, wt) : Lobby.num(Lobby.obj(players, winner), "score");
        lobby.finishRound(updates, doc, players, label, winnerMs, shownScore, Game.DRAFT_PLUS, roundId);
        updates.addProperty("winner/contrib", cs.toString());
        updates.addProperty("winner/team", wt > 0 ? label : "");
        updates.addProperty("winner/deltas", lobby.settleOnce(Game.DRAFT_PLUS, roundId, () -> settleMatch(lobby, players, roster, wt, bonus)));
        resetFields(updates, players.keySet());
    }

    private static String settleMatch(Lobby lobby, JsonObject players, Map<String, long[]> roster, int winnerTeam, Map<String, Integer> bonus) {
        if (players.size() < 2) return "";
        Map<String, Long> bests = new HashMap<>();
        for (String p : players.keySet()) bests.put(p, Lobby.num(Lobby.obj(players, p), "score"));
        if (winnerTeam > 0) {   // командный матч: средние рейтинги команд
            List<String> win = members(roster, winnerTeam), lose = members(roster, winnerTeam == 1 ? 2 : 1);
            if (win.isEmpty() || lose.isEmpty()) return "";
            return lobby.settle(cur -> Ranking.settleTeamsBonus(cur, Game.DRAFT_PLUS, win, lose, bests, bonus));
        }
        Map<String, long[]> data = new HashMap<>();
        for (String p : players.keySet()) data.put(p, new long[] {bests.get(p), Lobby.num(Lobby.obj(players, p), "lastMs")});
        List<List<String>> tiers = Ranking.tiersByScore(data, false, true);
        if (tiers.size() < 2) return "";
        return lobby.settle(cur -> Ranking.settleTiersBonus(cur, Game.DRAFT_PLUS, tiers, bests, bonus));
    }

    // ======================================================================================
    //  Клиент: состояние, оповещения, эффекты, экраны
    // ======================================================================================
    private static String lastPhase = "", lastMod = "", lastSentHover = "";
    private static long coinStart, lastHoverAt;
    private static boolean effectsOn;

    /** Читает поля Драфта+ из документа лобби (главный поток, из FriendRunClient.apply). */
    public static void applyDoc(RunState s, JsonObject doc, JsonObject players) {
        s.dpPhase = Lobby.str(doc, "dpPhase");
        s.dpBanTurn = Lobby.str(doc, "dpBanTurn");
        s.dpBannedCsv = Lobby.str(doc, "dpBanned");
        s.dpBans = (int) Math.max(1, Math.min(BANS_MAX, Lobby.orDef(Lobby.num(doc, "dpBans"), BANS_DEFAULT)));
        s.dpBanTotal = (int) Lobby.num(doc, "dpBanTotal");
        s.dpModId = Lobby.str(doc, "dpModId");
        Map<String, String> roles = new HashMap<>();
        for (String p : players.keySet()) {
            String r = Lobby.str(Lobby.obj(players, p), "dpRole");
            if (!r.isEmpty()) roles.put(p, r);
        }
        s.dpRoles = Map.copyOf(roles);
        if (!s.dpPhase.equals("ROLE")) s.dpRoleSent = false;
        Cfg c = cfg(doc);
        s.dpTeam = c.team();
        s.dpTeamSize = c.teamSize();
        s.dpBanOn = c.banOn();
        s.dpRoleOn = c.roleOn();
        s.dpModOn = c.modOn();
        s.dpCoinOn = c.coinOn();
        s.dpOffMods = Set.copyOf(c.offMods());
        s.dpOffRoles = Set.copyOf(c.offRoles());
        s.dpTeamNameA = teamName(doc, 1);
        s.dpTeamNameB = teamName(doc, 2);
        s.dpRoster = Map.copyOf(roster(players));
        s.dpFirst = Lobby.str(doc, "dpFirst");
        s.dpCoinFace = (int) Lobby.num(doc, "dpCoinFace");
        String actor = !s.dpBanTurn.isEmpty() ? s.dpBanTurn : Lobby.str(doc, "draftTurn");
        s.dpHover = actor.isEmpty() ? "" : Lobby.str(Lobby.obj(players, actor), "dpHover");
        if (s.gameMode != Game.DRAFT_PLUS) return;

        if (!s.dpPhase.equals(lastPhase)) {   // оповещения о смене фазы
            lastPhase = s.dpPhase;
            if (s.dpPhase.equals("COIN")) coinStart = System.nanoTime();
            switch (s.dpPhase) {
                case "COIN" -> FriendRunClient.chat("Драфт+: бросаем монетку — кто начинает баны и выбор целей…");
                case "BAN" -> FriendRunClient.chat("Драфт+: фаза банов. Банов на игрока: " + s.dpBans
                    + " (всего " + s.dpBanTotal + "). Забаненные цели не попадут в драфт.");
                case "ROLE" -> FriendRunClient.chat("Драфт+: баны закончены. Забанено: " + bannedNames(s) + ". Выберите роль.");
                case "PICK" -> FriendRunClient.chat((s.dpRoles.isEmpty() ? "" : "Драфт+: роли — " + rolesLine(s) + ". ") + "Начинается драфт целей.");
                default -> { }
            }
        }
        if (!s.dpModId.equals(lastMod)) {
            lastMod = s.dpModId;
            Modifier m = modifierById(s.dpModId);
            if (m != null) FriendRunClient.chat("Хаос-событие матча: " + m.name() + " — " + m.desc());
        }
    }

    public static int bannedCount(RunState s) { return csv(s.dpBannedCsv).size(); }

    public static String bannedNames(RunState s) {
        List<String> out = new ArrayList<>();
        for (String b : csv(s.dpBannedCsv)) {
            DraftGoal g = DraftGoal.parse(b);
            if (g != null) out.add(g.displayName());
        }
        return out.isEmpty() ? "ничего" : String.join(", ", out);
    }

    public static String rolesLine(RunState s) {
        List<String> out = new ArrayList<>();
        for (String n : sorted(s.dpRoles.keySet())) {
            Role r = roleById(s.dpRoles.get(n));
            out.add(n + " — " + (r == null ? "?" : r.name()));
        }
        return String.join("; ", out);
    }

    public static void resetClientMemory() { lastPhase = ""; lastMod = ""; lastSentHover = ""; coinStart = 0; }

    /** Идёт ли сейчас матч Драфт+ (читается и потоком сервера — для бонусов ролей и запретов). */
    public static boolean matchRunning() {
        RunState s = FriendRunClient.STATE;
        return FriendRunClient.inLobby() && s.dpMatch && s.roundGame == Game.DRAFT && s.phase == RunState.Phase.RUNNING;
    }

    public static boolean modActive(String id) { return matchRunning() && id.equals(FriendRunClient.STATE.dpModId); }

    public static Role roleOf(String playerName) { return roleById(FriendRunClient.STATE.dpRoles.get(playerName)); }

    /** Постоянные эффекты роли и модификатора. Вызывается на старте матча и затем раз в 5 секунд (команды идут на сервер мира). */
    public static void applyEffects(RunState s) {
        effectsOn = true;
        Modifier m = modifierById(s.dpModId);
        if (m != null) for (String c : m.commands()) Chaos.run(c);
        for (var e : s.dpRoles.entrySet()) {
            Role r = roleById(e.getValue());
            if (r == null || r.effect().isEmpty()) continue;
            // execute as @a[name=…] ничего не делает, если игрока нет в этом мире (раздельные миры) — без ошибок в чате
            Chaos.run("execute as @a[name=" + e.getKey() + "] run effect give @s minecraft:" + r.effect() + " infinite " + r.amp() + " true");
        }
    }

    public static void clearEffects() {
        if (!effectsOn) return;
        effectsOn = false;
        Chaos.run("effect clear @a");
        Chaos.run(Chaos.attr("gravity", "0.08"));
    }

    /** Сообщение на старте матча (после «GO!»). */
    public static void announceMatch(RunState s) {
        Role r = roleOf(FriendRunClient.myName());
        Modifier m = modifierById(s.dpModId);
        int myTeam = teamOf(s.dpRoster, FriendRunClient.myName());
        FriendRunClient.chat("Драфт+ начался! "
            + (s.dpTeam && myTeam > 0 ? "Вы в команде «" + (myTeam == 1 ? s.dpTeamNameA : s.dpTeamNameB) + "». " : "")
            + "Для победы нужно " + WIN_CELLS + " из " + CELLS + " целей."
            + (r == null ? "" : " Ваша роль: " + r.name() + ".")
            + (m == null ? "" : " Событие: " + m.name() + "."));
    }

    /** Роль и событие матча в левом нижнем углу экрана. */
    public static void drawHud(GuiGraphicsExtractor g, RunState s) {
        if (s.dpPhase.equals("COIN")) drawCoin(g, s);
        Role r = roleOf(FriendRunClient.myName());
        Modifier m = modifierById(s.dpModId);
        if (r == null && m == null) return;
        int y = Gfx.guiHeight(g) - 44;
        if (r != null) Gfx.text(g, "Роль: " + r.name(), 6, y, 0xFFFFDD55);
        if (m != null) Gfx.text(g, "Событие: " + m.name(), 6, y + 11, 0xFFFF9955);
    }

    private static String firstLabel(RunState s) {
        if (s.dpTeam) return teamLabel("2".equals(s.dpFirst) ? s.dpTeamNameB : s.dpTeamNameA);
        return s.dpFirst.isEmpty() ? "?" : s.dpFirst;
    }

    /** Бросок монетки в начале матча: ~2 с «вращается», затем результат (кто начинает баны и пики). */
    private static void drawCoin(GuiGraphicsExtractor g, RunState s) {
        long ms = (System.nanoTime() - coinStart) / 1_000_000L;
        int w = Math.min(Gfx.guiWidth(g) - 20, 300), x = (Gfx.guiWidth(g) - w) / 2, y = 38;
        Gfx.rect(g, x, y, x + w, y + 44, 0xCC0B1018);
        Gfx.rect(g, x, y, x + w, y + 1, 0xFFFFD700);
        Gfx.rect(g, x, y + 43, x + w, y + 44, 0xFFFFD700);
        int cx = x + w / 2;
        if (ms < 2200) {
            Gfx.centered(g, "Бросаем монетку…", cx, y + 8, 0xFFFFFFFF);
            Gfx.centered(g, (ms / 140) % 2 == 0 ? "ОРЁЛ" : "РЕШКА", cx, y + 24, 0xFFFFD700);
        } else {
            String face = s.dpCoinFace == 0 ? "Выпал ОРЁЛ" : s.dpCoinFace == 1 ? "Выпала РЕШКА" : "Жребий брошен";
            Gfx.centered(g, face, cx, y + 8, 0xFFFFD700);
            Gfx.fitted(g, (s.dpTeam ? "Первой выбирает " : "Первым выбирает ") + firstLabel(s), cx, y + 24, w - 12, 0xFFFFFFFF);
        }
    }

    /** Кто сейчас делает выбор (бан или пик): для строки внизу экрана. */
    public static String actor(RunState s) { return !s.dpBanTurn.isEmpty() ? s.dpBanTurn : s.draftTurn; }

    public static String actorLine(RunState s) {
        String a = actor(s);
        if (a.isBlank()) return "Ожидание хода…";
        int t = teamOf(s.dpRoster, a);
        return "Игрок " + a + (s.dpTeam && t > 0 ? " (" + teamLabel(t == 1 ? s.dpTeamNameA : s.dpTeamNameB) + ")" : "") + " делает выбор";
    }

    /** Сообщить остальным, на что я навожу/что выбираю (раз в ~0,35 с, только при изменении). */
    public static void reportHover(String value) {
        String v = value == null ? "" : value;
        long now = System.currentTimeMillis();
        if (v.equals(lastSentHover) || now - lastHoverAt < 350) return;
        lastSentHover = v;
        lastHoverAt = now;
        FriendRunClient.dpHover(v);
    }

    /** Сейчас не мой ход: при следующем ходе подсказку надо отправить заново. */
    public static void hoverIdle() { lastSentHover = ""; }

    /** Открывает экраны банов и ролей в нужной фазе (из FriendRunClient.tick). */
    public static void tickScreens(RunState s) {
        var cur = Mc.currentScreen();
        boolean on = s.launched && s.gameMode == Game.DRAFT_PLUS;
        boolean ban = on && s.dpPhase.equals("BAN"), role = on && s.dpPhase.equals("ROLE");
        if (ban && !(cur instanceof DraftPlusBanScreen)) Mc.setScreen(new DraftPlusBanScreen());
        else if (!ban && cur instanceof DraftPlusBanScreen) Mc.setScreen(null);
        if (role && !(cur instanceof DraftPlusRoleScreen)) Mc.setScreen(new DraftPlusRoleScreen());
        else if (!role && cur instanceof DraftPlusRoleScreen) Mc.setScreen(null);
    }

    /** Строка о вкладе для чата: "Ann: редких 3 (вес), выборов 2, клеток 6 → +8". */
    public static List<String> contribLines(String encoded) {
        List<String> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return out;
        for (String part : encoded.split(";")) {
            String[] f = part.split(":");
            if (f.length != 5) continue;
            out.add(f[0] + ": клеток " + f[3] + ", вес редких целей " + f[1] + ", удачных выборов " + f[2] + " → вклад +" + f[4] + " MMR");
        }
        return out;
    }
}
