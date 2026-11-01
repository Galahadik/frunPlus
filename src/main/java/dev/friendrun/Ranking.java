package dev.friendrun;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Рейтинг (MMR). У каждого игрока ОБЩИЙ рейтинг (меняется от любого раунда) и отдельный рейтинг в каждом режиме.
 * Хранится в Firebase: friendrun/_mmr/<ник> = {mmr, wins, games, bestMs, modes: {speedrun: {...}, conveyor: {...}, ...}}.
 * Считает и записывает хост в конце раунда (нужно 2+ игрока), остальные только читают.
 *
 * Elo: старт 1000. Результат раунда — список «мест» (в каждом месте один или несколько игроков, делящих его).
 * Каждая пара «выше/ниже» даёт очки; общий вес раунда фиксирован (K=32 на всех соперников), поэтому победа в лобби
 * из 2 и из 6 человек стоит примерно одинаково. Первые 5 игр в режиме — калибровка: изменения ×1.5.
 * Манхант считается по командам (средний рейтинг команды против средней команды соперника).
 */
public final class Ranking {
    public static final int START = 1000;
    public static final int CALIBRATION_GAMES = 5;
    public static final double CALIBRATION_MULT = 1.5;
    private static final double K_TOTAL = 32.0;

    /** Ключи режимов в базе, индекс = id режима из Game. */
    private static final String[] MODE_KEYS = {"speedrun", "conveyor", "chain", "bingo", "waves", "manhunt", "pillars", "draft", "draftplus"};
    private static final String[] MODE_SHORT = {"Спидран", "Конвейер", "Цепочка", "Бинго", "Волны", "Манхант", "Столбы", "Драфт", "Драфт+"};

    /** best: лучшее время (мс, меньше — лучше) или лучший счёт (больше — лучше), зависит от режима. */
    public record Rec(int mmr, int wins, int games, long best) {}
    public record Profile(Rec overall, Map<String, Rec> modes) {}
    public record Delta(String name, int delta, int mmr, int oDelta, int oMmr) {}
    public record Outcome(Map<String, Profile> updated, List<Delta> deltas) {}
    public record Tier(String name, int color) {}
    public record Row(String name, Rec rec) {}

    private static final Rec FRESH = new Rec(START, 0, 0, 0);

    private Ranking() {}

    private static volatile Map<String, Profile> cache = Map.of();
    private static volatile String cacheUrl = "";
    private static volatile long cacheLoadedAt;
    private static final long CACHE_TTL_MS = 30_000L;

    /** Последняя загруженная таблица (для показа MMR рядом с игроками). null — игрок ещё не играл. */
    public static Profile cached(String name) { return cache.get(name); }

    /** Кэш для экранов: повторные открытия не обращаются к Firebase чаще раза в 30 секунд.
     * Синхронизация метода объединяет одновременные запросы; ручное обновление может принудительно обойти TTL.
     */
    public static synchronized Map<String, Profile> fetchCached(String dbUrl, boolean force) throws java.io.IOException {
        long now = System.currentTimeMillis();
        if (!force && cacheLoadedAt > 0L && dbUrl.equals(cacheUrl) && now - cacheLoadedAt < CACHE_TTL_MS)
            return cache;
        Map<String, Profile> fresh = Lobby.fetchRatings(dbUrl);
        cache = Map.copyOf(fresh);
        cacheUrl = dbUrl;
        cacheLoadedAt = System.currentTimeMillis();
        return cache;
    }

    /** Сбросить кэш после записи рейтинга, чтобы следующий экран получил свежие данные. */
    public static synchronized void invalidateCache() {
        cacheLoadedAt = 0L;
    }

    // ---------- режимы и области таблицы ----------
    public static String key(int game) { return MODE_KEYS[Math.max(0, Math.min(MODE_KEYS.length - 1, game))]; }
    public static String shortName(int game) { return MODE_SHORT[Math.max(0, Math.min(MODE_SHORT.length - 1, game))]; }
    public static int modeCount() { return MODE_KEYS.length; }

    /** Область таблицы: 0 — общий рейтинг, 1.. — режим (id режима + 1). */
    public static Rec recFor(Profile p, int scope) {
        if (p == null) return null;
        return scope == 0 ? p.overall() : p.modes().get(key(scope - 1));
    }

    /** Время (меньше — лучше), а не счёт? */
    public static boolean timeMode(int game) { return game == Game.SPEEDRUN || game == Game.CHAIN || game == Game.BINGO; }
    public static boolean scoreMode(int game) { return game == Game.CONVEYOR || game == Game.WAVES || game == Game.DRAFT || game == Game.DRAFT_PLUS; }

    // ---------- ранги ----------
    public static Tier tier(int mmr) {
        if (mmr < 800) return new Tier("Дерево", 0xFFB08050);
        if (mmr < 1000) return new Tier("Камень", 0xFF9AA5B5);
        if (mmr < 1200) return new Tier("Железо", 0xFFE0E0E0);
        if (mmr < 1400) return new Tier("Золото", 0xFFFFC82E);
        if (mmr < 1600) return new Tier("Алмаз", 0xFF4CE0E0);
        return new Tier("Незерит", 0xFFB86CFF);
    }

    // ---------- расчёт очков ----------
    private static Rec rec(Map<String, Rec> recs, String p) { return recs.getOrDefault(p, FRESH); }

    private static double calib(Rec r) { return r.games() < CALIBRATION_GAMES ? CALIBRATION_MULT : 1.0; }

    private static double expected(int mine, int theirs) { return 1.0 / (1.0 + Math.pow(10, (theirs - mine) / 400.0)); }

    /** Не ниже 0: возвращает реальное изменение. */
    private static int floored(int mmr, int delta) { return Math.max(0, mmr + delta) - mmr; }

    /** Изменения MMR по местам (tiers — от лучшего к худшему; в одном месте — разделившие его игроки). */
    static Map<String, Integer> eloTiers(Map<String, Rec> recs, List<List<String>> tiers) {
        int n = 0;
        for (List<String> t : tiers) n += t.size();
        double k = K_TOTAL / Math.max(1, n - 1);
        Map<String, Double> acc = new HashMap<>();
        for (int a = 0; a < tiers.size(); a++)
            for (int b = a + 1; b < tiers.size(); b++)
                for (String p : tiers.get(a))
                    for (String q : tiers.get(b)) {
                        double d = k * (1 - expected(rec(recs, p).mmr(), rec(recs, q).mmr()));
                        acc.merge(p, d, Double::sum);
                        acc.merge(q, -d, Double::sum);
                    }
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < tiers.size(); i++)
            for (String p : tiers.get(i)) {
                int d = (int) Math.round(acc.getOrDefault(p, 0.0) * calib(rec(recs, p)));
                if (i == 0 && tiers.size() > 1 && d < 1) d = 1;          // победитель всегда получает хотя бы +1
                out.put(p, floored(rec(recs, p).mmr(), d));
            }
        return out;
    }

    /** Изменения MMR при командной победе (манхант). Размер команд уравновешен: сумма изменений ≈ 0. */
    static Map<String, Integer> eloTeams(Map<String, Rec> recs, List<String> winners, List<String> losers) {
        double avgW = winners.stream().mapToInt(p -> rec(recs, p).mmr()).average().orElse(START);
        double avgL = losers.stream().mapToInt(p -> rec(recs, p).mmr()).average().orElse(START);
        double base = K_TOTAL * (1 - expected((int) Math.round(avgW), (int) Math.round(avgL)));
        int nW = winners.size(), nL = losers.size(), n = Math.max(1, nW + nL);
        Map<String, Integer> out = new HashMap<>();
        for (String p : winners) {
            int d = Math.max(1, (int) Math.round(base * 2.0 * nL / n * calib(rec(recs, p))));
            out.put(p, floored(rec(recs, p).mmr(), d));
        }
        for (String p : losers) {
            int d = -(int) Math.round(base * 2.0 * nW / n * calib(rec(recs, p)));
            out.put(p, floored(rec(recs, p).mmr(), d));
        }
        return out;
    }

    private static Map<String, Rec> overallRecs(Map<String, Profile> cur, Collection<String> players) {
        Map<String, Rec> m = new HashMap<>();
        for (String p : players) m.put(p, cur.containsKey(p) ? cur.get(p).overall() : FRESH);
        return m;
    }

    private static Map<String, Rec> modeRecs(Map<String, Profile> cur, int game, Collection<String> players) {
        Map<String, Rec> m = new HashMap<>();
        for (String p : players) {
            Rec r = cur.containsKey(p) ? cur.get(p).modes().get(key(game)) : null;
            m.put(p, r == null ? FRESH : r);
        }
        return m;
    }

    /** Раунд с местами. bests: личный результат игроков (время победителя или счёт каждого). */
    public static Outcome settleTiers(Map<String, Profile> cur, int game, List<List<String>> tiers, Map<String, Long> bests) {
        return settleTiersBonus(cur, game, tiers, bests, Map.of());
    }

    /**
     * То же, но с личным вкладом игрока (Драфт+): bonus — очки MMR (не меньше 0), которые прибавляются
     * к изменению и в рейтинге режима, и в общем — уже после калибровки. Так проигравший, который много
     * сделал для команды, теряет меньше, а победитель получает больше.
     */
    public static Outcome settleTiersBonus(Map<String, Profile> cur, int game, List<List<String>> tiers,
                                           Map<String, Long> bests, Map<String, Integer> bonus) {
        List<String> all = new ArrayList<>();
        for (List<String> t : tiers) all.addAll(t);
        Map<String, Rec> mr = modeRecs(cur, game, all), or = overallRecs(cur, all);
        Map<String, Integer> dm = new HashMap<>(eloTiers(mr, tiers));
        Map<String, Integer> da = new HashMap<>(eloTiers(or, tiers));
        for (String p : all) {
            int b = Math.max(0, bonus.getOrDefault(p, 0));
            if (b == 0) continue;
            dm.put(p, floored(rec(mr, p).mmr(), dm.getOrDefault(p, 0) + b));
            da.put(p, floored(rec(or, p).mmr(), da.getOrDefault(p, 0) + b));
        }
        return finish(cur, game, all, dm, da, new HashSet<>(tiers.get(0)), bests);
    }

    /** Командный раунд (манхант). */
    public static Outcome settleTeams(Map<String, Profile> cur, int game, List<String> winners, List<String> losers, Map<String, Long> bests) {
        List<String> all = new ArrayList<>(winners);
        all.addAll(losers);
        Map<String, Integer> dm = eloTeams(modeRecs(cur, game, all), winners, losers);
        Map<String, Integer> da = eloTeams(overallRecs(cur, all), winners, losers);
        return finish(cur, game, all, dm, da, new HashSet<>(winners), bests);
    }

    /** Командный раунд с личным вкладом (Драфт+ в командном режиме): как settleTeams + bonus каждому игроку. */
    public static Outcome settleTeamsBonus(Map<String, Profile> cur, int game, List<String> winners, List<String> losers,
                                           Map<String, Long> bests, Map<String, Integer> bonus) {
        List<String> all = new ArrayList<>(winners);
        all.addAll(losers);
        Map<String, Rec> mr = modeRecs(cur, game, all), or = overallRecs(cur, all);
        Map<String, Integer> dm = new HashMap<>(eloTeams(mr, winners, losers));
        Map<String, Integer> da = new HashMap<>(eloTeams(or, winners, losers));
        for (String p : all) {
            int b = Math.max(0, bonus.getOrDefault(p, 0));
            if (b == 0) continue;
            dm.put(p, floored(rec(mr, p).mmr(), dm.getOrDefault(p, 0) + b));
            da.put(p, floored(rec(or, p).mmr(), da.getOrDefault(p, 0) + b));
        }
        return finish(cur, game, all, dm, da, new HashSet<>(winners), bests);
    }

    private static long bestUpdate(int game, long old, Long v) {
        if (v == null || v <= 0) return old;
        if (timeMode(game)) return old == 0 || v < old ? v : old;
        if (scoreMode(game)) return v > old ? v : old;
        return old;
    }

    private static Outcome finish(Map<String, Profile> cur, int game, List<String> players, Map<String, Integer> dm,
                                  Map<String, Integer> da, Set<String> winners, Map<String, Long> bests) {
        Map<String, Profile> out = new HashMap<>();
        List<Delta> deltas = new ArrayList<>();
        String k = key(game);
        for (String p : players) {
            Profile old = cur.get(p);
            Rec ov = old == null ? FRESH : old.overall();
            Rec mo = old == null || old.modes().get(k) == null ? FRESH : old.modes().get(k);
            boolean win = winners.contains(p);
            Rec nm = new Rec(mo.mmr() + dm.getOrDefault(p, 0), mo.wins() + (win ? 1 : 0), mo.games() + 1, bestUpdate(game, mo.best(), bests.get(p)));
            Rec no = new Rec(ov.mmr() + da.getOrDefault(p, 0), ov.wins() + (win ? 1 : 0), ov.games() + 1, 0);
            Map<String, Rec> modes = new LinkedHashMap<>(old == null ? Map.of() : old.modes());
            modes.put(k, nm);
            out.put(p, new Profile(no, modes));
            deltas.add(new Delta(p, nm.mmr() - mo.mmr(), nm.mmr(), no.mmr() - ov.mmr(), no.mmr()));
        }
        deltas.sort(Comparator.<Delta>comparingInt(d -> -d.delta()).thenComparing(Delta::name));
        return new Outcome(out, deltas);
    }

    /** Места по очкам. data: ник -> {счёт, время}. laterBetter: при равном счёте больше время лучше (волны), иначе меньше (конвейер). */
    public static List<List<String>> tiersByScore(Map<String, long[]> data, boolean laterBetter, boolean zeroTied) {
        List<String> names = new ArrayList<>(data.keySet());
        Comparator<String> cmp = (a, b) -> {
            long[] x = data.get(a), y = data.get(b);
            if (x[0] != y[0]) return Long.compare(y[0], x[0]);                 // больше очков — выше
            if (zeroTied && x[0] == 0) return 0;
            return laterBetter ? Long.compare(y[1], x[1]) : Long.compare(x[1], y[1]);
        };
        names.sort(cmp.thenComparing(Comparator.naturalOrder()));
        List<List<String>> tiers = new ArrayList<>();
        for (String n : names) {
            if (!tiers.isEmpty() && cmp.compare(tiers.get(tiers.size() - 1).get(0), n) == 0) tiers.get(tiers.size() - 1).add(n);
            else tiers.add(new ArrayList<>(List.of(n)));
        }
        return tiers;
    }

    // ---------- (де)сериализация ----------
    private static Rec recOf(JsonObject r) {
        int mmr = r.has("mmr") ? (int) Lobby.num(r, "mmr") : START;
        return new Rec(mmr, (int) Lobby.num(r, "wins"), (int) Lobby.num(r, "games"), Lobby.num(r, "bestMs"));
    }

    private static JsonObject recJson(Rec r) {
        JsonObject o = new JsonObject();
        o.addProperty("mmr", r.mmr());
        o.addProperty("wins", r.wins());
        o.addProperty("games", r.games());
        o.addProperty("bestMs", r.best());
        return o;
    }

    public static Map<String, Profile> parse(JsonElement e) {
        Map<String, Profile> m = new HashMap<>();
        if (e == null || !e.isJsonObject()) return m;
        JsonObject o = e.getAsJsonObject();
        for (String name : o.keySet()) {
            JsonObject r = Lobby.obj(o, name);
            if (r.size() == 0) continue;
            Rec overall = recOf(r);
            Map<String, Rec> modes = new LinkedHashMap<>();
            if (r.has("modes")) {
                JsonObject mo = Lobby.obj(r, "modes");
                for (String k : MODE_KEYS) if (mo.has(k)) modes.put(k, recOf(Lobby.obj(mo, k)));
            } else if (overall.games() > 0) {
                modes.put("speedrun", overall);   // записи первой версии: рейтинг считался только за спидран
            }
            m.put(name, new Profile(overall, modes));
        }
        return m;
    }

    public static JsonObject toJson(Map<String, Profile> profiles) {
        JsonObject o = new JsonObject();
        for (var e : profiles.entrySet()) {
            JsonObject p = recJson(e.getValue().overall());
            JsonObject modes = new JsonObject();
            for (var m : e.getValue().modes().entrySet()) modes.add(m.getKey(), recJson(m.getValue()));
            p.add("modes", modes);
            o.add(e.getKey(), p);
        }
        return o;
    }

    /** "ник:изм:mmr:общ.изм:общ.mmr;..." — пишется в документ лобби, чтобы все увидели изменения. */
    public static String encodeDeltas(List<Delta> ds) {
        StringBuilder sb = new StringBuilder();
        for (Delta d : ds) {
            if (sb.length() > 0) sb.append(';');
            sb.append(d.name()).append(':').append(d.delta()).append(':').append(d.mmr())
              .append(':').append(d.oDelta()).append(':').append(d.oMmr());
        }
        return sb.toString();
    }

    public static List<Delta> parseDeltas(String s) {
        List<Delta> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] f = part.split(":");
            try {
                if (f.length == 5) out.add(new Delta(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4])));
                else if (f.length == 3) out.add(new Delta(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[1]), Integer.parseInt(f[2])));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    /** Таблица области по убыванию MMR (при равенстве — больше побед, потом ник). Только те, кто играл. */
    public static List<Row> sorted(Map<String, Profile> m, int scope) {
        List<Row> rows = new ArrayList<>();
        for (var e : m.entrySet()) {
            Rec r = recFor(e.getValue(), scope);
            if (r != null && r.games() > 0) rows.add(new Row(e.getKey(), r));
        }
        rows.sort(Comparator.<Row>comparingInt(r -> -r.rec().mmr())
            .thenComparingInt(r -> -r.rec().wins()).thenComparing(Row::name));
        return rows;
    }

    // ---------- загрузка ----------
    /** Загрузить таблицу в кеш в фоне (ошибки сети молча игнорируются). */
    public static void refreshAsync(String dbUrl) {
        Thread t = new Thread(() -> {
            try { fetchCached(dbUrl, false); } catch (Exception ignored) {}
        }, "FRun-ranking");
        t.setDaemon(true);
        t.start();
    }
}
