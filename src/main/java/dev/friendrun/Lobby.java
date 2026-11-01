package dev.friendrun;

import com.google.gson.*;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.*;

/**
 * Лобби по названию поверх Firebase Realtime Database (REST, без аккаунтов у игроков).
 * Документ: /friendrun/<лобби>.json
 *   host, hostSeen, mode(0/1), itemSeed(str), offItems/skipHard/famMode/offChaos (настройки пула, см. Settings), launched, worldSeed(str), address, roundId,
 *   roundSeed(str), startAt(epoch ms), winner{name,ms,round}, players{<name>{ready,lastSeen,win{round,ms}}}
 * Хост — «арбитр»: решает, когда старт, и кто победил. Остальные только читают и пишут своё.
 */
public final class Lobby {
    public interface Listener {
        void onDoc(JsonObject doc);   // вызывается в потоке опроса!
        void onClosed(String reason);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final Random RNG = new Random();

    public final String name;
    public final String me;
    public final boolean host;
    private final String base;
    private final Listener listener;
    // Polling must never wait behind UI-triggered writes (settings, ready, scores, etc.).
    // Keep writes serialized so PATCH operations issued by one client retain their order.
    private final ScheduledExecutorService pollEx = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FriendRun-Lobby-Poll");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService writeEx = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FriendRun-Lobby-Write");
        t.setDaemon(true);
        return t;
    });
    // Firebase Realtime Database SSE stream: server pushes document changes instead of
    // making every client download the whole lobby on a fixed polling interval.
    private final ExecutorService streamEx = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FriendRun-Lobby-Events");
        t.setDaemon(true);
        return t;
    });
    private final Object snapshotLock = new Object();
    private volatile JsonObject latestDoc;
    private final java.util.concurrent.atomic.AtomicBoolean snapshotQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private ScheduledFuture<?> poller;
    private long lastBeat = 0;
    private long nextDelay = 1000;
    private long scheduledStartRound; // prevents repeatedly extending startAt before SSE echoes the PATCH
    private int failures = 0;
    private volatile boolean closed = false;
    // Не допускаем повторного начисления рейтинга, если финальный PATCH лобби задержался/не прошёл.
    // Ключ включает режим и номер раунда; доступ безопасен из poll/write потоков.
    private final java.util.Set<String> ratingAttempts = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Lobby(String dbUrl, String name, String me, boolean host, Listener l) {
        String u = dbUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.base = u + "/friendrun/" + name;
        this.name = name; this.me = me; this.host = host; this.listener = l;
    }

    public static boolean validName(String n) { return n.matches("[A-Za-z0-9_-]{1,24}") && !n.startsWith("_"); }   // "_..." — служебные узлы (рейтинг)
    public static String newSeed() { return Long.toString(RNG.nextLong()); }


    private static String rootOf(String dbUrl) {
        String u = dbUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + "/friendrun";
    }

    // ---------- рейтинг ----------
    /** Вся таблица рейтинга: ник -> профиль (общий рейтинг + рейтинг по режимам). */
    public static java.util.Map<String, Ranking.Profile> fetchRatings(String dbUrl) throws IOException {
        return Ranking.parse(send("GET", rootOf(dbUrl) + "/_mmr", null));
    }

    /** Читает таблицу, считает раунд, записывает обратно. Возвращает строку изменений для документа лобби или "" (сбой сети). */
    String settle(java.util.function.Function<java.util.Map<String, Ranking.Profile>, Ranking.Outcome> round) {
        try {
            String url = base.substring(0, base.lastIndexOf('/')) + "/_mmr";
            Ranking.Outcome out = round.apply(Ranking.parse(send("GET", url, null)));
            send("PATCH", url, Ranking.toJson(out.updated()).toString());
            Ranking.invalidateCache();
            // После завершения раунда подтягиваем обновлённый рейтинг; в остальное время — вручную.
            Ranking.refreshAsync(Config.databaseUrl);
            return Ranking.encodeDeltas(out.deltas());
        } catch (IOException | RuntimeException e) {
            FriendRunClient.LOG.warn("FRun: рейтинг не записан: {}", e.toString());
            return "";
        }
    }

    /** Выполняет расчёт рейтинга не более одного раза для конкретного режима и раунда за жизнь хоста. */
    String settleOnce(int game, long roundId, java.util.function.Supplier<String> calculation) {
        String key = game + ":" + roundId;
        if (!ratingAttempts.add(key)) {
            FriendRunClient.LOG.debug("FRun: повторный расчёт рейтинга пропущен (режим {}, раунд {})", game, roundId);
            return "";
        }
        return calculation.get();
    }

    /** Победитель одного (первый закончивший: спидран, цепочка, бинго): он против всех остальных. */
    private String settleWinner(JsonObject players, int game, String winner, long ms) {
        if (players.size() < 2 || !players.has(winner)) return "";
        java.util.List<String> others = new java.util.ArrayList<>(players.keySet());
        others.remove(winner);
        java.util.Collections.sort(others);
        java.util.List<java.util.List<String>> tiers = java.util.List.of(java.util.List.of(winner), others);
        return settle(cur -> Ranking.settleTiers(cur, game, tiers, java.util.Map.of(winner, ms)));
    }

    /** Режимы с очками у каждого игрока (конвейер, волны): места по очкам. */
    private String settleScores(JsonObject players, int game, boolean laterBetter, boolean zeroTied) {
        if (players.size() < 2) return "";
        java.util.Map<String, long[]> data = new java.util.HashMap<>();
        java.util.Map<String, Long> bests = new java.util.HashMap<>();
        for (String p : players.keySet()) {
            long sc = num(obj(players, p), "score");
            data.put(p, new long[] {sc, num(obj(players, p), "lastMs")});
            bests.put(p, sc);
        }
        java.util.List<java.util.List<String>> tiers = Ranking.tiersByScore(data, laterBetter, zeroTied);
        if (tiers.size() < 2) return "";   // все поделили первое место — рейтинг не меняется
        return settle(cur -> Ranking.settleTiers(cur, game, tiers, bests));
    }

    /**
     * «Столбы»: хост сообщает итог раунда встроенной карты. Победители — те, кого карта объявила победителями
     * (один игрок или команда), проигравшие — все остальные игроки лобби. Пустой список — «Никто не победил»
     * (рейтинг не меняется). Результат (и изменения MMR) пишется в документ лобби, как у остальных режимов.
     */
    public void reportPillars(java.util.Collection<String> winners) {
        if (!host || closed) return;
        final java.util.List<String> won = new java.util.ArrayList<>(winners);
        try {
            writeEx.execute(() -> {
                try {
                    JsonElement e = request("GET", "", null);
                    if (e == null || !e.isJsonObject()) return;
                    JsonObject doc = e.getAsJsonObject();
                    JsonObject players = obj(doc, "players");
                    java.util.List<String> w = new java.util.ArrayList<>(), l = new java.util.ArrayList<>();
                    for (String p : players.keySet()) (won.contains(p) ? w : l).add(p);
                    java.util.Collections.sort(w);
                    java.util.Collections.sort(l);
                    long completedRound = num(doc, "roundId");
                    persistPillarsHistory(doc, completedRound, w, l);
                    String deltas = "";
                    if (!w.isEmpty() && !l.isEmpty()) {   // рейтинг только при 2+ игроках и наличии победителя
                        java.util.List<java.util.List<String>> tiers = java.util.List.of(w, l);
                        deltas = settleOnce(Game.PILLARS, completedRound, () -> settle(cur -> Ranking.settleTiers(cur, Game.PILLARS, tiers, java.util.Map.of())));
                    }
                    long next = completedRound + 1;
                    JsonObject u = new JsonObject();
                    u.addProperty("roundId", next);
                    u.addProperty("winner/name", w.isEmpty() ? "—" : String.join(", ", w));
                    u.addProperty("winner/ms", 0);
                    u.addProperty("winner/score", 0);
                    u.addProperty("winner/mode", Game.PILLARS);
                    u.addProperty("winner/deltas", deltas);
                    u.addProperty("winner/round", next);
                    request("PATCH", "", u.toString());
                } catch (IOException | RuntimeException ex2) {
                    FriendRunClient.LOG.warn("FRun: итог «Столбов» не записан: {}", ex2.toString());
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /**
     * Постоянная история режима «Столбы» и агрегированная статистика игроков.
     * Ключ раунда включает имя лобби, идентификатор сессии и номер раунда, поэтому повторная
     * доставка одного и того же результата не создаёт дубликаты.
     */
    private void persistPillarsHistory(JsonObject lobbyDoc, long round, java.util.List<String> winners,
                                       java.util.List<String> losers) {
        try {
            String root = base.substring(0, base.lastIndexOf('/'));
            String session = str(lobbyDoc, "sessionId");
            if (session.isBlank()) session = Long.toString(num(lobbyDoc, "hostSeen"));
            String historyKey = firebaseKey(name) + "_" + firebaseKey(session) + "_" + round;
            String historyUrl = root + "/_pillars_history/" + historyKey;
            JsonElement previous = send("GET", historyUrl, null);
            if (previous != null) return; // этот раунд уже сохранён

            long now = System.currentTimeMillis();
            JsonObject event = new JsonObject();
            event.addProperty("lobby", name);
            event.addProperty("session", session);
            event.addProperty("round", round + 1);
            event.addProperty("timestamp", now);
            event.add("winners", strings(winners));
            event.add("losers", strings(losers));
            event.addProperty("winner", winners.isEmpty() ? "—" : String.join(", ", winners));

            // Собираем историю и агрегаты в один multi-location PATCH: частично записанного раунда не будет.
            JsonObject updates = new JsonObject();
            updates.add("_pillars_history/" + historyKey, event);
            java.util.List<String> all = new java.util.ArrayList<>(winners);
            all.addAll(losers);
            for (String player : all) {
                String key = firebaseKey(player);
                String statsUrl = root + "/_pillars_stats/" + key;
                JsonElement old = send("GET", statsUrl, null);
                JsonObject stats = old != null && old.isJsonObject() ? old.getAsJsonObject().deepCopy() : new JsonObject();
                stats.addProperty("name", player);
                stats.addProperty("games", num(stats, "games") + 1);
                if (winners.contains(player)) stats.addProperty("wins", num(stats, "wins") + 1);
                else stats.addProperty("losses", num(stats, "losses") + 1);
                stats.addProperty("lastPlayedAt", now);
                stats.addProperty("lastLobby", name);
                stats.addProperty("lastRound", round + 1);
                updates.add("_pillars_stats/" + key, stats);
            }
            send("PATCH", root, updates.toString());
        } catch (IOException | RuntimeException e) {
            FriendRunClient.LOG.warn("FRun: не удалось сохранить историю/статистику «Столбов»: {}", e.toString());
        }
    }

    private static com.google.gson.JsonArray strings(java.util.List<String> values) {
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (String value : values) out.add(value);
        return out;
    }

    private static String firebaseKey(String value) {
        String key = value == null ? "" : value.replaceAll("[.#$\\[\\]/]", "_");
        return key.isBlank() ? "unknown" : key;
    }

    /** Манхант: команды (беглецы против охотников). */
    private String settleTeams(JsonObject players, String[] runnerNames, boolean huntersWon) {
        java.util.List<String> runners = new java.util.ArrayList<>(), hunters = new java.util.ArrayList<>();
        java.util.Set<String> rs = new java.util.HashSet<>(java.util.Arrays.asList(runnerNames));
        for (String p : players.keySet()) (rs.contains(p) ? runners : hunters).add(p);
        if (runners.isEmpty() || hunters.isEmpty()) return "";
        return settle(cur -> huntersWon
            ? Ranking.settleTeams(cur, Game.MANHUNT, hunters, runners, java.util.Map.of())
            : Ranking.settleTeams(cur, Game.MANHUNT, runners, hunters, java.util.Map.of()));
    }

    // ---------- список открытых лобби ----------
    public record Entry(String name, String host, int players, int ready, int mode, int gameMode, boolean chaos, boolean launched) {}

    public static java.util.List<Entry> list(String dbUrl) throws IOException {
        String u = dbUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        try {
            HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create(u + "/friendrun.json")).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401 || r.statusCode() == 403)
                throw new IOException("База отклонила запрос (проверьте правила Firebase)");
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
            java.util.List<Entry> out = new java.util.ArrayList<>();
            String t = r.body();
            if (t == null || t.isBlank() || t.equals("null")) return out;
            JsonElement root = JsonParser.parseString(t);
            if (!root.isJsonObject()) return out;
            long now = System.currentTimeMillis();
            for (String name : root.getAsJsonObject().keySet()) {
                JsonObject d = obj(root.getAsJsonObject(), name);
                if (now - num(d, "hostSeen") > 20_000) continue;   // хост офлайн
                JsonObject pl = obj(d, "players");
                int ready = 0;
                for (String pn : pl.keySet()) if (bool(obj(pl, pn), "ready")) ready++;
                out.add(new Entry(name, str(d, "host"), pl.size(), ready,
                    (int) num(d, "mode"), (int) num(d, "gameMode"), bool(d, "chaos"), bool(d, "launched")));
            }
            out.sort(java.util.Comparator.comparing(Entry::launched)
                .thenComparing(java.util.Comparator.comparingInt(Entry::players).reversed())
                .thenComparing(Entry::name));   // сначала открытые, потом где больше игроков
            return out;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        } catch (IllegalArgumentException iae) {
            throw new IOException("Неверный URL базы данных");
        }
    }

    // ---------- создание / вход ----------
    public static Lobby create(String dbUrl, String name, String me, JsonObject settings, boolean chaosOn, Listener l) throws IOException {
        Lobby lb = new Lobby(dbUrl, name, me, true, l);
        JsonElement existing = lb.request("GET", "", null);
        if (existing != null && existing.isJsonObject()) {
            long seen = num(existing.getAsJsonObject(), "hostSeen");
            if (System.currentTimeMillis() - seen < 20_000) throw new IOException("Лобби с таким названием уже существует");
        }
        JsonObject doc = new JsonObject();
        doc.addProperty("host", me);
        doc.addProperty("hostSeen", System.currentTimeMillis());
        doc.addProperty("sessionId", Long.toString(System.currentTimeMillis()));
        doc.addProperty("mode", 0);
        doc.addProperty("itemSeed", newSeed());
        doc.addProperty("chaos", chaosOn);
        for (String k : settings.keySet()) doc.add(k, settings.get(k));   // настройки пула предметов и усложнений хоста
        doc.addProperty("gameMode", 0);
        doc.addProperty("soloLock", false);
        doc.addProperty("convTotal", 20);
        doc.addProperty("convSlot", 3);
        doc.addProperty("chainLen", 5);
        doc.addProperty("bingoSize", 3);
        doc.addProperty("bingoFull", false);
        doc.addProperty("draftSize", 5);
        doc.addProperty("draftType", 3);
        doc.addProperty("draftActive", false);
        doc.addProperty("draftComplete", false);
        doc.addProperty("draftPickCount", 0);
        doc.addProperty("draftTurn", "");
        doc.addProperty("draftSeed", "0");
        doc.addProperty("draftBoardIds", "");
        doc.addProperty("draftOwnersIds", "");
        doc.addProperty("draftOfferA", "");
        doc.addProperty("draftOfferB", "");
        doc.addProperty("dpBans", DraftPlus.BANS_DEFAULT);   // Драфт+: банов на игрока
        doc.addProperty("dpPhase", "");
        doc.addProperty("dpDone", false);
        doc.addProperty("dpFormat", 0);        // 0 — каждый сам за себя, 1 — командный
        doc.addProperty("dpTeamSize", 2);
        doc.addProperty("dpBanOn", true);
        doc.addProperty("dpRoleOn", true);
        doc.addProperty("dpModOn", true);
        doc.addProperty("dpCoinOn", true);
        doc.addProperty("dpOffMods", "");
        doc.addProperty("dpOffRoles", "");
        doc.addProperty("dpTeamNameA", "Красные");
        doc.addProperty("dpTeamNameB", "Синие");
        doc.addProperty("waveSec", 45);
        doc.addProperty("waveDiff", 1);
        doc.addProperty("manRunners", 1);
        doc.addProperty("manMin", 15);
        doc.addProperty("roundGame", 0);
        doc.addProperty("chaosMod", Chaos.randomIndex(RNG, Settings.localOffChaos()));
        doc.addProperty("rollAt", 0);
        doc.addProperty("chaosRollAt", 0);
        doc.addProperty("launched", false);
        doc.addProperty("hostInGame", false);
        doc.addProperty("worldSeed", "0");
        doc.addProperty("address", "");
        doc.addProperty("lanAddress", "");
        doc.addProperty("roundId", 0);
        doc.addProperty("roundSeed", "0");
        doc.addProperty("startAt", 0);
        doc.addProperty("prepareRound", 0);
        JsonObject players = new JsonObject();
        players.add(me, player());
        doc.add("players", players);
        lb.cleanupStale(dbUrl);
        lb.request("PUT", "", doc.toString());
        lb.startPolling();
        return lb;
    }

    public static Lobby join(String dbUrl, String name, String me, Listener l) throws IOException {
        Lobby lb0 = new Lobby(dbUrl, name, me, false, l);
        JsonElement e = lb0.request("GET", "", null);
        if (e == null || !e.isJsonObject()) throw new IOException("Лобби не найдено");
        JsonObject doc = e.getAsJsonObject();
        if (System.currentTimeMillis() - num(doc, "hostSeen") > 20_000) throw new IOException("Лобби не найдено (хост офлайн)");
        if (bool(doc, "launched")) throw new IOException("Игра в этом лобби уже запущена");
        if (bool(doc, "soloLock") || num(doc, "gameMode") == Game.SINGLE_SPEEDRUN) throw new IOException("В этом лобби выбран одиночный спидран — вход закрыт");
        String nick = me;
        JsonObject players = obj(doc, "players");
        int i = 2;
        while (players.has(nick)) nick = me + i++;
        Lobby lb = new Lobby(dbUrl, name, nick, false, l);
        lb.request("PUT", "/players/" + nick, player().toString());
        // Повторная проверка закрывает гонку, если хост включил soloLock между GET и PUT.
        JsonElement verify = lb.request("GET", "", null);
        if (verify != null && verify.isJsonObject() && (bool(verify.getAsJsonObject(), "soloLock") || num(verify.getAsJsonObject(), "gameMode") == Game.SINGLE_SPEEDRUN)) {
            lb.request("DELETE", "/players/" + nick, null);
            throw new IOException("Хост включил одиночный спидран — вход в лобби закрыт");
        }
        lb.startPolling();
        return lb;
    }

    private static JsonObject player() {
        JsonObject p = new JsonObject();
        p.addProperty("ready", false);
        p.addProperty("lastSeen", System.currentTimeMillis());
        p.addProperty("draftChoice", -1);
        p.addProperty("draftChoicePick", -1);
        p.addProperty("draftClaim", -1);
        p.addProperty("draftClaimRound", 0);
        p.addProperty("draftClaimAt", 0);
        return p;
    }

    // ---------- действия ----------
    public void setSettings(int mode, String itemSeed, boolean roll) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode); o.addProperty("itemSeed", itemSeed);
        if (roll) o.addProperty("rollAt", System.currentTimeMillis());
        patch("", o);
    }
    public void patchPlayer(String playerName, JsonObject o) { patch("/players/" + playerName, o); }
    public static long orDef(long v, long def) { return v > 0 ? v : def; }
    public void setNum(String key, long v) { JsonObject o = new JsonObject(); o.addProperty(key, v); patch("", o); }
    public void setStr(String key, String v) { JsonObject o = new JsonObject(); o.addProperty(key, v); patch("", o); }
    public void setFlag(String key, boolean v) { JsonObject o = new JsonObject(); o.addProperty(key, v); patch("", o); }
    public void setGameMode(int g) {
        if (!host || closed) return;
        JsonObject o = new JsonObject();
        o.addProperty("gameMode", g);
        boolean solo = g == Game.SINGLE_SPEEDRUN;
        o.addProperty("soloLock", solo);
        if (solo) { o.addProperty("chaos", false); o.addProperty("roundMod", -1); }
        if (solo) {
            JsonObject current = latestSnapshot();
            JsonObject players = current == null ? new JsonObject() : obj(current, "players");
            for (String playerName : players.keySet()) if (!playerName.equals(me))
                o.add("players/" + playerName, JsonNull.INSTANCE);
            o.addProperty("mode", 0); // одиночный режим всегда в отдельном мире хоста
            o.addProperty("launched", false);
            o.addProperty("startAt", 0);
            o.addProperty("prepareRound", 0);
        }
        patch("", o);
    }
    public void reportScore(long score, long ms) {
        JsonObject o = new JsonObject();
        o.addProperty("score", score); o.addProperty("lastMs", ms);
        patch("/players/" + me, o);
    }
    /** Хост изменил пул предметов: новые настройки + новый seed (без анимации), чтобы выпавший предмет был из нового пула. */
    public void setPool(String offItems, boolean skipHard, boolean famMode) {
        JsonObject o = new JsonObject();
        o.addProperty("offItems", offItems);
        o.addProperty("skipHard", skipHard);
        o.addProperty("famMode", famMode);
        o.addProperty("itemSeed", newSeed());
        patch("", o);
    }

    /** Хост изменил список включённых усложнений. */
    public void setOffChaos(String offChaos) {
        JsonObject o = new JsonObject();
        o.addProperty("offChaos", offChaos);
        patch("", o);
    }

    public void setChaos(boolean on) {
        JsonObject o = new JsonObject();
        o.addProperty("chaos", on);
        if (on) {
            o.addProperty("chaosMod", Chaos.randomIndex(RNG, Settings.localOffChaos()));
            o.addProperty("chaosRollAt", System.currentTimeMillis());
        }
        patch("", o);
    }
    public void rollChaos() {
        JsonObject o = new JsonObject();
        o.addProperty("chaosMod", Chaos.randomIndex(RNG, Settings.localOffChaos()));
        o.addProperty("chaosRollAt", System.currentTimeMillis());
        patch("", o);
    }
    public void launch() {
        JsonObject o = new JsonObject();
        o.addProperty("launched", true); o.addProperty("worldSeed", newSeed());
        patch("", o);
    }
    public void setLanAddress(String addr) { JsonObject o = new JsonObject(); o.addProperty("lanAddress", addr); patch("", o); }
    /** Хост сообщает, находится ли он в игровом мире; это позволяет снизить частоту опроса в меню. */
    public void setHostInGame(boolean inGame) { if (!host) return; JsonObject o = new JsonObject(); o.addProperty("hostInGame", inGame); patch("", o); }

    /**
     * Хост штатно вышел из игрового мира: останавливаем текущую игру во всём лобби,
     * сбрасываем подготовку/отсчёт и готовность игроков, но сохраняем само лобби и состав.
     */
    public void endBecauseHostLeftWorld() {
        if (!host || closed) return;
        JsonObject o = new JsonObject();
        o.addProperty("launched", false);
        o.addProperty("hostInGame", false);
        o.addProperty("startAt", 0);
        o.addProperty("prepareRound", 0);
        o.addProperty("draftActive", false);
        o.addProperty("draftComplete", false);
        o.addProperty("draftPickCount", 0);
        o.addProperty("draftTurn", "");
        o.addProperty("draftSeed", "0");
        o.addProperty("draftBoardIds", "");
        o.addProperty("draftOwnersIds", "");
        o.addProperty("draftOfferA", "");
        o.addProperty("draftOfferB", "");
        o.addProperty("address", "");
        o.addProperty("lanAddress", "");
        JsonObject players = latestSnapshot() == null ? new JsonObject() : obj(latestSnapshot(), "players");
        DraftPlus.resetFields(o, players.keySet());
        for (String p : players.keySet()) {
            o.addProperty("players/" + p + "/ready", false);
            o.addProperty("players/" + p + "/preparedRound", 0);
            o.add("players/" + p + "/win", JsonNull.INSTANCE);
            o.add("players/" + p + "/score", JsonNull.INSTANCE);
            o.add("players/" + p + "/lastMs", JsonNull.INSTANCE);
            o.add("players/" + p + "/dead", JsonNull.INSTANCE);
            o.addProperty("players/" + p + "/draftChoice", -1);
            o.addProperty("players/" + p + "/draftChoicePick", -1);
            o.addProperty("players/" + p + "/draftClaim", -1);
            o.addProperty("players/" + p + "/draftClaimRound", 0);
            o.addProperty("players/" + p + "/draftClaimAt", 0);
        }
        patch("", o);
    }
    public void setAddress(String addr) { JsonObject o = new JsonObject(); o.addProperty("address", addr); patch("", o); }
    public void setReady() { JsonObject o = new JsonObject(); o.addProperty("ready", true); patch("/players/" + me, o); }
    public void chooseDraft(int choice, int pickCount) {
        JsonObject o = new JsonObject();
        o.addProperty("draftChoice", choice == 1 ? 1 : 0);
        o.addProperty("draftChoicePick", pickCount);
        patch("/players/" + me, o);
    }
    /** Драфт+: мой бан (закодированная цель) на ход номер banIndex. */
    public void chooseBan(String encodedGoal, int banIndex) {
        JsonObject o = new JsonObject();
        o.addProperty("dpBanChoice", encodedGoal);
        o.addProperty("dpBanChoicePick", banIndex);
        patch("/players/" + me, o);
    }
    /** Драфт+: занять команду (1/2) или выйти из команды (0). Время вступления берём у сервера Firebase — по нему определяется капитан. */
    public void joinTeam(int team) {
        JsonObject o = new JsonObject();
        o.addProperty("team", team);
        if (team == 0) o.addProperty("teamAt", 0);
        else { JsonObject sv = new JsonObject(); sv.addProperty(".sv", "timestamp"); o.add("teamAt", sv); }
        patch("/players/" + me, o);
    }
    /** Драфт+ (хост): случайное распределение. assignment: ник -> команда. */
    public void setTeams(java.util.Map<String, Integer> assignment) {
        JsonObject o = new JsonObject();
        long t = System.currentTimeMillis();
        int i = 0;
        for (var e : assignment.entrySet()) {
            o.addProperty("players/" + e.getKey() + "/team", e.getValue());
            o.addProperty("players/" + e.getKey() + "/teamAt", e.getValue() == 0 ? 0 : t + (i++));
        }
        patch("", o);
    }
    /** Драфт+: что я сейчас выбираю (видно остальным). */
    public void setHover(String value) {
        JsonObject o = new JsonObject();
        o.addProperty("dpHover", value);
        patch("/players/" + me, o);
    }
    /** Драфт+: моя роль. */
    public void chooseRole(String roleId) {
        JsonObject o = new JsonObject();
        o.addProperty("dpRole", roleId);
        patch("/players/" + me, o);
    }
    public void claimDraftGoal(int index, long roundId, long hostTimeMs) {
        JsonObject o = new JsonObject();
        o.addProperty("draftClaim", index);
        o.addProperty("draftClaimRound", roundId);
        o.addProperty("draftClaimAt", hostTimeMs);
        patch("/players/" + me, o);
    }
    public void reportWin(long roundId, long ms) {
        JsonObject w = new JsonObject();
        w.addProperty("round", roundId); w.addProperty("ms", ms);
        JsonObject o = new JsonObject();
        o.add("win", w);
        patch("/players/" + me, o);
    }

    private void patch(String path, JsonObject body) {
        if (closed) return;
        try {
            writeEx.execute(() -> {
                if (closed) return;
                try { request("PATCH", path, body.toString()); }
                catch (IOException e) { fail(e); }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (poller != null) poller.cancel(false);
        try {
            writeEx.execute(() -> {
                try { request("DELETE", host ? "" : "/players/" + me, null); } catch (IOException ignored) {}
            });
        } catch (RejectedExecutionException ignored) {}
        pollEx.shutdownNow();
        streamEx.shutdownNow();
        writeEx.shutdown();
    }

    // ---------- опрос ----------
    private void startPolling() {
        try { streamEx.execute(this::streamLoop); } catch (RejectedExecutionException ignored) {}
        schedule(0);
    }

    private void schedule(long delayMs) {
        if (closed) return;
        try { poller = pollEx.schedule(this::pollTask, delayMs, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException ignored) {}
    }

    private void pollTask() {
        try { poll(); } finally { schedule(nextDelay); }
    }

    /**
     * Maintenance tick: no regular full-document GET. Firebase SSE supplies changes;
     * this tick only sends heartbeats and lets the host evaluate timers (e.g. Waves/Manhunt).
     */
    private void poll() {
        if (closed) return;
        JsonObject doc = latestSnapshot();
        if (doc == null) {
            nextDelay = 1000;
            return; // wait for the initial SSE snapshot
        }
        long nowMs = System.currentTimeMillis();
        if (!host && nowMs - num(doc, "hostSeen") > 20_000) {
            shutdown("Хост отключился от лобби");
            return;
        }
        if (nowMs - lastBeat > 5000) { lastBeat = nowMs; heartbeatAsync(); }
        try {
            if (host) hostLogic(doc.deepCopy());
        } catch (IOException ex) {
            fail(ex);
        }
        boolean hostInGame = bool(doc, "hostInGame");
        long startAt = num(doc, "startAt");
        // Host timer checks stay responsive while active; no Firebase GET is performed here.
        nextDelay = !hostInGame ? (bool(doc, "launched") ? 2000 : 5000)
            : (startAt == 0 ? 1000 : 250);
    }

    private JsonObject latestSnapshot() {
        synchronized (snapshotLock) { return latestDoc == null ? null : latestDoc.deepCopy(); }
    }

    /** Long-lived Firebase Realtime Database event stream with reconnect/backoff. */
    private void streamLoop() {
        int reconnects = 0;
        while (!closed) {
            HttpRequest req;
            try {
                req = HttpRequest.newBuilder(URI.create(base + ".json"))
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .timeout(Duration.ofSeconds(65)).GET().build();
                HttpResponse<InputStream> response = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw new IOException("Firebase отклонил SSE-подключение (проверьте правила базы)");
                if (response.statusCode() / 100 != 2) throw new IOException("SSE HTTP " + response.statusCode());
                reconnects = 0;
                try (InputStream in = response.body(); BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String event = "message";
                    StringBuilder data = new StringBuilder();
                    String line;
                    while (!closed && (line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!data.isEmpty()) handleSse(event, data.toString());
                            event = "message";
                            data.setLength(0);
                        } else if (line.startsWith("event:")) {
                            event = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (!data.isEmpty()) data.append('\n');
                            data.append(line.substring(5).trim());
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                if (!closed) FriendRunClient.LOG.warn("FRun: SSE connection interrupted: {}", e.toString());
            }
            if (!closed) {
                reconnects++;
                try { Thread.sleep(Math.min(10_000L, 500L * (1L << Math.min(reconnects - 1, 4)))); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void handleSse(String event, String raw) {
        if (closed || event.equals("keep-alive")) return;
        if (event.equals("cancel") || event.equals("auth_revoked")) {
            shutdown(event.equals("cancel") ? "Доступ к лобби отменён Firebase" : "Firebase отозвал доступ к лобби");
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) return;
            JsonObject envelope = parsed.getAsJsonObject();
            String path = str(envelope, "path");
            JsonElement data = envelope.get("data");
            if (event.equals("put") && (path.isEmpty() || path.equals("/"))
                && (data == null || data.isJsonNull())) {
                shutdown("Лобби закрыто");
                return;
            }
            synchronized (snapshotLock) {
                if (event.equals("put") && (path.isEmpty() || path.equals("/"))) {
                    latestDoc = data != null && data.isJsonObject() ? data.getAsJsonObject().deepCopy() : new JsonObject();
                } else {
                    if (latestDoc == null) latestDoc = new JsonObject();
                    if (event.equals("patch") && data != null && data.isJsonObject()) {
                        for (var entry : data.getAsJsonObject().entrySet()) {
                            String childPath = (path.endsWith("/") ? path : path + "/") + entry.getKey();
                            setPath(latestDoc, childPath, entry.getValue());
                        }
                    } else {
                        setPath(latestDoc, path, data);
                    }
                }
            }
            queueSnapshotDelivery();
        } catch (RuntimeException e) {
            FriendRunClient.LOG.debug("FRun: invalid SSE event: {}", e.toString());
        }
    }

    private static void setPath(JsonObject root, String path, JsonElement value) {
        String clean = path == null ? "" : path;
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty()) return;
        String[] parts = clean.split("/");
        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement child = current.get(parts[i]);
            if (child == null || !child.isJsonObject()) {
                JsonObject created = new JsonObject();
                current.add(parts[i], created);
                current = created;
            } else current = child.getAsJsonObject();
        }
        if (value == null || value.isJsonNull()) current.remove(parts[parts.length - 1]);
        else current.add(parts[parts.length - 1], value.deepCopy());
    }

    private void queueSnapshotDelivery() {
        if (closed || !snapshotQueued.compareAndSet(false, true)) return;
        try {
            pollEx.execute(() -> {
                try {
                    JsonObject doc = latestSnapshot();
                    if (doc == null || closed) return;
                    if (!host && !obj(doc, "players").has(me)) { shutdown("Вас удалили из лобби"); return; }
                    // Apply latest state promptly on every Firebase event.
                    listener.onDoc(doc);
                } finally { snapshotQueued.set(false); }
            });
        } catch (RejectedExecutionException ignored) { snapshotQueued.set(false); }
    }

    /** Удаляет лобби, чей хост не отвечает больше минуты (вылетел, не закрыв лобби). */
    private void cleanupStale(String dbUrl) {
        try {
            String u = dbUrl.trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            String root = u + "/friendrun";
            JsonElement all = send("GET", root, null);
            if (all == null || !all.isJsonObject()) return;
            long now = System.currentTimeMillis();
            for (String n : all.getAsJsonObject().keySet()) {
                if (n.equals(name) || n.startsWith("_")) continue;   // "_mmr" и другие служебные узлы не удаляем
                if (now - num(obj(all.getAsJsonObject(), n), "hostSeen") > 60_000) send("DELETE", root + "/" + n, null);
            }
        } catch (IOException | RuntimeException e) {
            FriendRunClient.LOG.warn("FRun: очистка старых лобби не удалась: {}", e.toString());
        }
    }

    private void heartbeatAsync() {
        if (closed) return;
        try {
            writeEx.execute(() -> {
                if (closed) return;
                try {
                    long now = System.currentTimeMillis();
                    JsonObject playerBeat = new JsonObject();
                    playerBeat.addProperty("lastSeen", now);
                    request("PATCH", "/players/" + me, playerBeat.toString());
                    if (host) {
                        JsonObject hostBeat = new JsonObject();
                        hostBeat.addProperty("hostSeen", now);
                        request("PATCH", "", hostBeat.toString());
                    }
                } catch (IOException e) {
                    fail(e);
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private void hostLogic(JsonObject doc) throws IOException {
        long now = System.currentTimeMillis();
        JsonObject players = obj(doc, "players");
        JsonObject updates = new JsonObject();

        // Одиночный спидран: хост — единственный участник. Удаляем только игроков ЭТОГО лобби.
        boolean solo = bool(doc, "soloLock") || num(doc, "gameMode") == Game.SINGLE_SPEEDRUN;
        if (solo) {
            for (String p : players.keySet()) if (!p.equals(me)) updates.add("players/" + p, JsonNull.INSTANCE);
        }
        // выкинуть пропавших игроков
        for (String p : players.keySet()) {
            if (!p.equals(me) && (solo || now - num(obj(players, p), "lastSeen") > 20_000)) {
                updates.add("players/" + p, JsonNull.INSTANCE);
            }
        }
        boolean launched = bool(doc, "launched");
        long startAt = num(doc, "startAt");
        long roundId = num(doc, "roundId");
        int roundGame = (int) num(doc, "roundGame");

        long prepareRound = num(doc, "prepareRound");
        if (launched && startAt == 0 && !players.isEmpty()) {
            int gmSel = (int) num(doc, "gameMode");
            if (prepareRound == 0) {
                boolean all = true;
                for (String p : players.keySet()) if (!bool(obj(players, p), "ready")) { all = false; break; }
                if (all && !(gmSel == Game.MANHUNT && players.size() < 2)) {
                    if (gmSel == Game.DRAFT_PLUS && !bool(doc, "dpDone")) {
                        DraftPlus.hostStep(updates, doc, players, now);   // баны -> роли -> драфт целей
                    } else if (gmSel == Game.DRAFT && !bool(doc, "draftComplete")) {
                        if (!bool(doc, "draftActive")) {
                            startDraft(updates, doc, players);
                        } else {
                            advanceDraft(updates, doc, players);
                        }
                    } else {
                        long nextRound = roundId + 1;
                        // First prepare every client with identical round data. Start is scheduled
                        // only after every currently connected player acknowledges preparation.
                        updates.addProperty("prepareRound", nextRound);
                        updates.addProperty("roundId", nextRound);
                        updates.addProperty("roundSeed", str(doc, "itemSeed"));
                        // В матче Драфт+ работает та же механика захвата клеток, что и в классике (roundGame = DRAFT);
                        // отличие матча — флаг rDraftPlus.
                        updates.addProperty("roundGame", gmSel == Game.DRAFT_PLUS ? Game.DRAFT : gmSel);
                        updates.addProperty("rDraftPlus", gmSel == Game.DRAFT_PLUS);
                        updates.addProperty("rConvTotal", orDef(num(doc, "convTotal"), 20));
                        updates.addProperty("rConvSlot", orDef(num(doc, "convSlot"), 3));
                        updates.addProperty("rChainLen", orDef(num(doc, "chainLen"), 5));
                        updates.addProperty("rBingoSize", orDef(num(doc, "bingoSize"), 3));
                        updates.addProperty("rBingoFull", bool(doc, "bingoFull"));
                        updates.addProperty("rDraftSize", gmSel == Game.DRAFT_PLUS ? DraftPlus.BOARD
                            : Math.max(5, Math.min(7, (int) orDef(num(doc, "draftSize"), 5))));
                        updates.addProperty("rDraftType", Math.max(0, Math.min(3, (int) num(doc, "draftType"))));
                        updates.addProperty("rWaveSec", orDef(num(doc, "waveSec"), 45));
                        updates.addProperty("rWaveDiff", num(doc, "waveDiff"));
                        if (gmSel == Game.MANHUNT) {
                            updates.addProperty("rManMin", orDef(num(doc, "manMin"), 15));
                            updates.addProperty("runners", pickRunners(players, doc));
                        }
                        updates.addProperty("roundMod", bool(doc, "chaos") ? (int) num(doc, "chaosMod") : -1);
                    }
                }
            } else {
                boolean prepared = true;
                for (String p : players.keySet()) {
                    if (!bool(obj(players, p), "ready") || num(obj(players, p), "preparedRound") != prepareRound) {
                        prepared = false;
                        break;
                    }
                }
                if (prepared && scheduledStartRound != prepareRound) {
                    // Extra lead time gives all clients time to receive the shared deadline.
                    updates.addProperty("startAt", now + 8000);
                }
            }
        } else if (startAt > 0) {
            if (roundGame == Game.CONVEYOR) {
                // конвейер: раунд кончается по времени, побеждает больший счёт (при равенстве — кто раньше набрал)
                if (now >= startAt + orDef(num(doc, "rConvTotal"), 20) * 60_000L + 3000) {
                    String best = null; long bestScore = 0, bestMs = Long.MAX_VALUE;
                    for (String p : players.keySet()) {
                        long sc = num(obj(players, p), "score"), ms = num(obj(players, p), "lastMs");
                        if (sc > 0 && (sc > bestScore || (sc == bestScore && ms < bestMs))) { best = p; bestScore = sc; bestMs = ms; }
                    }
                    finishRound(updates, doc, players, best == null ? "—" : best, best == null ? 0 : bestMs, bestScore, roundGame, roundId);
                    if (best != null) updates.addProperty("winner/deltas", settleOnce(roundGame, roundId, () -> settleScores(players, roundGame, false, true)));
                }
            } else if (roundGame == Game.MANHUNT) {
                // манхант: охотники побеждают, если пойманы все беглецы; беглецы — если продержались до конца времени
                String[] rs = str(doc, "runners").split(",");
                boolean allCaught = rs.length > 0 && !rs[0].isEmpty();
                for (String r : rs) if (!r.isEmpty() && players.has(r) && !bool(obj(players, r), "dead")) { allCaught = false; break; }
                long dur = orDef(num(doc, "rManMin"), 15) * 60_000L;
                if (allCaught && now > startAt + 10_000) {
                    finishRound(updates, doc, players, "Охотники", now - startAt, 0, roundGame, roundId);
                    updates.addProperty("winner/deltas", settleOnce(roundGame, roundId, () -> settleTeams(players, rs, true)));
                } else if (now >= startAt + dur) {
                    finishRound(updates, doc, players, "Беглецы", now - startAt, 0, roundGame, roundId);
                    updates.addProperty("winner/deltas", settleOnce(roundGame, roundId, () -> settleTeams(players, rs, false)));
                }
            } else if (roundGame == Game.WAVES) {
                // волны: раунд кончается, когда погибли все (или по лимиту времени); побеждает больше очков, при равенстве — дольше продержался
                boolean allDead = !players.isEmpty();
                for (String p : players.keySet()) if (!bool(obj(players, p), "dead")) { allDead = false; break; }
                boolean cap = now >= startAt + Game.WAVES_CAP_MS;
                if ((allDead && now > startAt + 15_000) || cap) {
                    String best = null; long bestScore = -1, bestMs = -1;
                    for (String p : players.keySet()) {
                        long sc = num(obj(players, p), "score"), ms = num(obj(players, p), "lastMs");
                        if (sc > bestScore || (sc == bestScore && ms > bestMs)) { best = p; bestScore = sc; bestMs = ms; }
                    }
                    finishRound(updates, doc, players, best == null ? "—" : best, Math.max(0, bestMs), Math.max(0, bestScore), roundGame, roundId);
                    if (best != null) updates.addProperty("winner/deltas", settleOnce(roundGame, roundId, () -> settleScores(players, roundGame, true, false)));
                }
            } else if (roundGame == Game.DRAFT) {
                if (bool(doc, "rDraftPlus")) DraftPlus.processClaims(this, updates, doc, players, startAt, roundId);
                else processDraftClaims(updates, doc, players, startAt, roundId);
            } else {
                // спидран / цепочка: побеждает первый (по времени у себя) закончивший
                String best = null; long bestMs = Long.MAX_VALUE;
                for (String p : players.keySet()) {
                    JsonObject w = obj(obj(players, p), "win");
                    if (w.size() > 0 && num(w, "round") == roundId && num(w, "ms") < bestMs) { best = p; bestMs = num(w, "ms"); }
                }
                if (best != null) {
                    // Capture the selected winner in effectively-final locals for the lambda.
                    final String winnerName = best;
                    final long winnerTimeMs = bestMs;
                    finishRound(updates, doc, players, winnerName, winnerTimeMs,
                            num(obj(players, winnerName), "score"), roundGame, roundId);
                    if (roundGame != Game.SINGLE_SPEEDRUN) updates.addProperty("winner/deltas", settleOnce(roundGame, roundId,
                            () -> settleWinner(players, roundGame, winnerName, winnerTimeMs)));
                }
            }
        }
        if (updates.size() > 0) {
            request("PATCH", "", updates.toString());
            if (updates.has("startAt") && num(updates, "startAt") > 0) {
                scheduledStartRound = num(doc, "prepareRound");
            }
        }
    }

    private static List<String> splitCsv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.isBlank()) for (String part : raw.split(",", -1)) out.add(part);
        return out;
    }

    private static String[] draftOptions(long seed, int pick, String boardCsv, int draftType) {
        Set<String> used = new HashSet<>(splitCsv(boardCsv));
        Random random = new Random(seed ^ (0x9E3779B97F4A7C15L * (pick + 1L)));
        List<String> itemPool = new ArrayList<>();
        List<String> quests = new ArrayList<>();
        List<String> advancements = new ArrayList<>();
        if (draftType == 0 || draftType == 3) {
            for (net.minecraft.world.item.Item item : ItemPicker.poolItems()) {
                String id = "item:" + ItemPicker.idOf(item);
                if (!used.contains(id)) itemPool.add(id);
            }
            // A 7x7 board still needs two offers on its final pick.
            if (itemPool.size() < 50) {
                for (net.minecraft.world.item.Item item : ItemPicker.baseItems()) {
                    String id = "item:" + ItemPicker.idOf(item);
                    if (!used.contains(id) && !itemPool.contains(id)) itemPool.add(id);
                }
            }
        }
        if (draftType == 1 || draftType == 3) {
            for (String id : DraftGoal.Quest.ids()) {
                String value = "quest:" + id;
                if (!used.contains(value) && !quests.contains(value)) quests.add(value);
            }
        }
        if (draftType == 2 || draftType == 3) {
            for (String id : DraftGoal.Advancement.ids()) {
                String value = "advancement:" + id;
                if (!used.contains(value) && !advancements.contains(value)) advancements.add(value);
            }
        }
        List<String> pool = new ArrayList<>();
        if (draftType == 0) pool.addAll(itemPool);
        else if (draftType == 1) pool.addAll(quests);
        else if (draftType == 2) pool.addAll(advancements);
        else {
            java.util.Collections.shuffle(itemPool, random);
            pool.addAll(itemPool.subList(0, Math.min(itemPool.size(), quests.size() + advancements.size())));
            pool.addAll(quests);
            pool.addAll(advancements);
        }
        java.util.Collections.shuffle(pool, random);
        if (pool.size() < 2) throw new IllegalStateException("В выбранном типе драфта недостаточно уникальных целей");
        return new String[] {pool.get(0), pool.get(1)};
    }

    private void startDraft(JsonObject updates, JsonObject doc, JsonObject players) {
        List<String> names = new ArrayList<>(players.keySet());
        java.util.Collections.sort(names);
        if (names.isEmpty()) return;
        long seed;
        try { seed = Long.parseLong(str(doc, "itemSeed")); } catch (NumberFormatException e) { seed = RNG.nextLong(); }
        int draftType = Math.max(0, Math.min(3, (int) num(doc, "draftType")));
        updates.addProperty("draftRunType", draftType);
        String[] offers = draftOptions(seed, 0, "", draftType);
        updates.addProperty("draftActive", true);
        updates.addProperty("draftComplete", false);
        updates.addProperty("draftPickCount", 0);
        updates.addProperty("draftTurn", names.get(0));
        updates.addProperty("draftSeed", Long.toString(seed));
        updates.addProperty("draftBoardIds", "");
        int size = Math.max(5, Math.min(7, (int) orDef(num(doc, "draftSize"), 5)));
        updates.addProperty("draftOwnersIds", String.join(",", java.util.Collections.nCopies(size * size, "-")));
        updates.addProperty("draftOfferA", offers[0]);
        updates.addProperty("draftOfferB", offers[1]);
        for (String name : players.keySet()) {
            updates.addProperty("players/" + name + "/draftChoice", -1);
            updates.addProperty("players/" + name + "/draftChoicePick", -1);
            updates.addProperty("players/" + name + "/draftClaim", -1);
            updates.addProperty("players/" + name + "/draftClaimRound", 0);
            updates.addProperty("players/" + name + "/draftClaimAt", 0);
        }
    }

    private void advanceDraft(JsonObject updates, JsonObject doc, JsonObject players) {
        if (!bool(doc, "draftActive")) return;
        int pick = (int) num(doc, "draftPickCount");
        String turn = str(doc, "draftTurn");
        JsonObject player = obj(players, turn);
        if (turn.isEmpty() || !players.has(turn)) {
            List<String> remaining = new ArrayList<>(players.keySet());
            java.util.Collections.sort(remaining);
            if (!remaining.isEmpty()) updates.addProperty("draftTurn", remaining.get(pick % remaining.size()));
            return;
        }
        if (num(player, "draftChoicePick") != pick) return;
        int choice = (int) num(player, "draftChoice");
        String selected = str(doc, choice == 1 ? "draftOfferB" : "draftOfferA");
        List<String> board = splitCsv(str(doc, "draftBoardIds"));
        if (selected.isBlank() || board.contains(selected)) return;
        board.add(selected);
        updates.addProperty("draftBoardIds", String.join(",", board));
        updates.addProperty("draftPickCount", board.size());
        updates.addProperty("players/" + turn + "/draftChoice", -1);
        updates.addProperty("players/" + turn + "/draftChoicePick", -1);
        int boardSize = Math.max(5, Math.min(7, (int) orDef(num(doc, "draftSize"), 5)));
        if (board.size() >= boardSize * boardSize) {
            updates.addProperty("draftActive", false);
            updates.addProperty("draftComplete", true);
            updates.addProperty("draftTurn", "");
            updates.addProperty("draftOfferA", "");
            updates.addProperty("draftOfferB", "");
            return;
        }
        List<String> names = new ArrayList<>(players.keySet());
        java.util.Collections.sort(names);
        String next = names.get(board.size() % names.size());
        long seed;
        try { seed = Long.parseLong(str(doc, "draftSeed")); } catch (NumberFormatException e) { seed = 0; }
        int draftType = Math.max(0, Math.min(3, (int) num(doc, "draftRunType")));
        String[] offers = draftOptions(seed, board.size(), String.join(",", board), draftType);
        updates.addProperty("draftTurn", next);
        updates.addProperty("draftOfferA", offers[0]);
        updates.addProperty("draftOfferB", offers[1]);
    }

    // Короткое окно для почти одновременных заявок игроков.
    // Основная задержка раньше была 4000 мс; теперь захват обычно подтверждается менее чем за секунду.
    private static final long DRAFT_CLAIM_SETTLE_MS = 500L;

    private long nowForDraftClaims() { return System.currentTimeMillis(); }

    private void processDraftClaims(JsonObject updates, JsonObject doc, JsonObject players, long startAt, long roundId) {
        List<String> owners = splitCsv(str(doc, "draftOwnersIds"));
        int boardSize = Math.max(5, Math.min(7, (int) orDef(num(doc, "rDraftSize"), orDef(num(doc, "draftSize"), 5))));
        int cellCount = boardSize * boardSize;
        while (owners.size() < cellCount) owners.add("-");
        if (owners.size() > cellCount) owners = new ArrayList<>(owners.subList(0, cellCount));
        List<String> names = new ArrayList<>(players.keySet());
        names.sort((a, b) -> {
            long ta = num(obj(players, a), "draftClaimAt"), tb = num(obj(players, b), "draftClaimAt");
            if (ta != tb) return Long.compare(ta, tb);
            return a.compareTo(b);
        });
        String winner = null;
        long winnerMs = 0;
        for (String name : names) {
            JsonObject p = obj(players, name);
            int idx = (int) num(p, "draftClaim");
            long claimAt = num(p, "draftClaimAt");
            if (num(p, "draftClaimRound") != roundId || idx < 0 || idx >= cellCount) continue;
            // Keep a short grace window for nearly simultaneous Firebase writes,
            // without making the player wait several seconds for a confirmed claim.
            if (claimAt <= 0 || nowForDraftClaims() - claimAt < DRAFT_CLAIM_SETTLE_MS) continue;
            if (owners.get(idx).equals("-")) {
                owners.set(idx, name);
                long score = num(p, "score") + 1;
                long ms = Math.max(0, num(p, "draftClaimAt") - startAt);
                updates.addProperty("players/" + name + "/score", score);
                updates.addProperty("players/" + name + "/lastMs", ms);
                p.addProperty("score", score);
                p.addProperty("lastMs", ms);
                if (score >= (cellCount / 2 + 1) && winner == null) { winner = name; winnerMs = ms; }
            }
            updates.addProperty("players/" + name + "/draftClaim", -1);
            updates.addProperty("players/" + name + "/draftClaimRound", 0);
            updates.addProperty("players/" + name + "/draftClaimAt", 0);
            if (winner != null) break;
        }
        updates.addProperty("draftOwnersIds", String.join(",", owners));
        if (winner != null) {
            finishRound(updates, doc, players, winner, winnerMs, num(obj(players, winner), "score"), Game.DRAFT, roundId);
            updates.addProperty("winner/deltas", settleOnce(Game.DRAFT, roundId,
                () -> settleScores(players, Game.DRAFT, false, true)));
        }
    }

    /** Случайно выбирает беглецов (остальные — охотники): минимум один беглец и один охотник. */
    private static String pickRunners(JsonObject players, JsonObject doc) {
        java.util.List<String> names = new java.util.ArrayList<>(players.keySet());
        java.util.Collections.sort(names);
        long seed;
        try { seed = Long.parseLong(str(doc, "itemSeed")); } catch (NumberFormatException e) { seed = RNG.nextLong(); }
        java.util.Collections.shuffle(names, new Random(seed));
        int n = (int) Math.max(1, Math.min(num(doc, "manRunners"), names.size() - 1));
        return String.join(",", names.subList(0, n));
    }

    /** Записывает итог раунда и готовит следующий: сброс готовности, счёта, новый предмет и усложнение. */
    void finishRound(JsonObject updates, JsonObject doc, JsonObject players, String name, long ms, long score, int mode, long roundId) {
        scheduledStartRound = 0;
        updates.addProperty("winner/name", name);
        updates.addProperty("winner/ms", ms);
        updates.addProperty("winner/score", score);
        updates.addProperty("winner/mode", mode);
        updates.addProperty("winner/deltas", "");   // изменения MMR за раунд (заполняет хост, если раунд рейтинговый)
        updates.addProperty("winner/round", roundId);
        updates.addProperty("startAt", 0);
        updates.addProperty("prepareRound", 0);
        updates.addProperty("itemSeed", newSeed());
        updates.addProperty("draftActive", false);
        updates.addProperty("draftComplete", false);
        updates.addProperty("draftPickCount", 0);
        updates.addProperty("draftTurn", "");
        updates.addProperty("draftSeed", "0");
        updates.addProperty("draftBoardIds", "");
        updates.addProperty("draftOwnersIds", "");
        updates.addProperty("draftOfferA", "");
        updates.addProperty("draftOfferB", "");
        if (bool(doc, "chaos")) updates.addProperty("chaosMod", Chaos.randomIndex(RNG, Settings.localOffChaos()));
        for (String p : players.keySet()) {
            updates.addProperty("players/" + p + "/ready", false);
            updates.addProperty("players/" + p + "/preparedRound", 0);
            updates.add("players/" + p + "/win", JsonNull.INSTANCE);
            updates.add("players/" + p + "/score", JsonNull.INSTANCE);
            updates.add("players/" + p + "/lastMs", JsonNull.INSTANCE);
            updates.add("players/" + p + "/dead", JsonNull.INSTANCE);
            updates.addProperty("players/" + p + "/draftChoice", -1);
            updates.addProperty("players/" + p + "/draftChoicePick", -1);
            updates.addProperty("players/" + p + "/draftClaim", -1);
            updates.addProperty("players/" + p + "/draftClaimRound", 0);
            updates.addProperty("players/" + p + "/draftClaimAt", 0);
        }
    }

    private void shutdown(String reason) {
        if (closed) return;
        closed = true;
        if (poller != null) poller.cancel(false);
        pollEx.shutdownNow();
        streamEx.shutdownNow();
        writeEx.shutdownNow();
        listener.onClosed(reason);
    }

    private void fail(IOException e) { FriendRunClient.LOG.warn("lobby write failed", e); }

    // ---------- HTTP ----------
    private JsonElement request(String method, String path, String body) throws IOException {
        return send(method, base + path, body);
    }

    private static JsonElement send(String method, String urlBase, String body) throws IOException {
        try {
            // записи с print=silent: Firebase не присылает обратно то, что мы сами отправили
            String suffix = method.equals("GET") ? "" : "?print=silent";
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(urlBase + ".json" + suffix))
                .timeout(Duration.ofSeconds(5));
            HttpRequest.BodyPublisher bp = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
            HttpResponse<String> r = HTTP.send(b.method(method, bp).header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401 || r.statusCode() == 403)
                throw new IOException("База отклонила запрос (проверьте правила Firebase, см. README)");
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
            String t = r.body();
            if (t == null || t.isBlank() || t.equals("null")) return null;
            return JsonParser.parseString(t);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        } catch (IllegalArgumentException iae) {
            throw new IOException("Неверный URL базы данных");
        }
    }

    // ---------- JSON-хелперы ----------
    public static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }
    public static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    public static long num(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0; } catch (Exception e) { return 0; }
    }
    public static boolean bool(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean(); }
}
