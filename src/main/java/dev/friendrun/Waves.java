package dev.friendrun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/**
 * Режим «Волны мобов» (хардкор): каждые N секунд рядом с игроками появляется волна мобов.
 * Очки: за каждого убитого моба (чем сильнее моб, тем больше) + бонус за каждую пройденную волну.
 * Смерть — конец для игрока: он становится зрителем и наблюдает за живым игроком (в общем мире).
 *
 * Считает очки тот, у кого есть встроенный сервер: в общем мире — хост за всех игроков, в отдельных мирах — каждый за себя.
 * Убийства и смерти берутся из scoreboard (критерии minecraft.killed:*, deathCount).
 */
public final class Waves {
    public record MobType(String id, String label, int points, int unlockWave, int weight) {}

    public static final MobType[] MOBS = {
        new MobType("zombie", "Зомби", 10, 1, 10),
        new MobType("skeleton", "Скелет", 12, 2, 8),
        new MobType("spider", "Паук", 12, 2, 8),
        new MobType("creeper", "Крипер", 20, 3, 6),
        new MobType("pillager", "Разбойник", 25, 5, 5),
        new MobType("witch", "Ведьма", 30, 6, 4),
        new MobType("enderman", "Эндермен", 35, 7, 3),
        new MobType("vindicator", "Поборник", 40, 8, 3),
    };

    public static final long PREP_MS = 10_000;     // подготовка перед первой волной
    private static final int WAVE_BONUS = 25;      // бонус за выживание: 25 * номер волны
    private static final Random RNG = new Random();

    private static boolean running;
    private static int lastWave, tickCounter, pollCounter;
    private static final List<String> names = new ArrayList<>();
    private static final Map<String, Integer> wavePts = new HashMap<>(), lastScore = new HashMap<>();
    private static final Map<String, Long> deadAt = new HashMap<>();
    private static final Map<String, String> watching = new HashMap<>();
    private static final Set<String> dead = new HashSet<>();

    private Waves() {}

    // ---------- общие вычисления (используются и в HUD) ----------
    public static int waveNo(long elapsedMs, int waveSec) {
        if (elapsedMs < PREP_MS) return 0;
        return 1 + (int) ((elapsedMs - PREP_MS) / (waveSec * 1000L));
    }

    public static long untilNext(long elapsedMs, int waveSec) {
        if (elapsedMs < PREP_MS) return PREP_MS - elapsedMs;
        long len = waveSec * 1000L;
        return len - (elapsedMs - PREP_MS) % len;
    }

    private static int countMul(RunState s) { return s.cfgWaveDiff == 0 ? 70 : s.cfgWaveDiff == 2 ? 140 : 100; }
    private static int pointsMul(RunState s) { return s.cfgWaveDiff == 0 ? 80 : s.cfgWaveDiff == 2 ? 130 : 100; }

    // ---------- старт ----------
    public static void start(Minecraft mc, RunState s) {
        cleanupState();
        if (mc.getSingleplayerServer() == null) { running = false; return; }   // у друга в общем мире считает хост
        running = true;

        if (s.mode == RunState.Mode.HOST_WORLD) for (RunState.PlayerInfo p : s.players) names.add(p.name());
        else names.add(FriendRunClient.myName());

        // таблицы убийств и смертей
        Chaos.run("scoreboard objectives remove frk_d");
        for (int i = 0; i < MOBS.length; i++) Chaos.run("scoreboard objectives remove frk_k" + i);
        Chaos.run("scoreboard objectives add frk_d deathCount");
        for (int i = 0; i < MOBS.length; i++)
            Chaos.run("scoreboard objectives add frk_k" + i + " minecraft.killed:minecraft." + MOBS[i].id());
        ensureScores();

        // стартовый набор
        Chaos.run("give @a minecraft:iron_sword");
        Chaos.run("give @a minecraft:bow");
        Chaos.run("give @a minecraft:arrow 64");
        Chaos.run("give @a minecraft:shield");
        Chaos.run("give @a minecraft:cooked_beef 32");
        Chaos.run("give @a minecraft:golden_apple 3");
        Chaos.run("item replace entity @a armor.head with minecraft:iron_helmet");
        Chaos.run("item replace entity @a armor.chest with minecraft:iron_chestplate");
        Chaos.run("item replace entity @a armor.legs with minecraft:iron_leggings");
        Chaos.run("item replace entity @a armor.feet with minecraft:iron_boots");
        Chaos.run("time set midnight");
        FriendRunClient.chat("Хардкор: одна жизнь. Первая волна через " + PREP_MS / 1000 + " секунд. Очки: за мобов и за каждую пройденную волну.");
    }

    private static void ensureScores() {
        Chaos.run("scoreboard players add @a frk_d 0");
        for (int i = 0; i < MOBS.length; i++) Chaos.run("scoreboard players add @a frk_k" + i + " 0");
    }

    // ---------- каждый тик ----------
    public static void tick(Minecraft mc, RunState s) {
        if (!running || mc.getSingleplayerServer() == null) return;
        long el = s.elapsedMs();
        int wave = waveNo(el, s.cfgWaveSec);
        if (wave > lastWave) {
            lastWave = wave;
            startWave(s, wave);
        }
        if (++tickCounter % 40 == 0) poll(s);
    }

    private static List<String> alive() {
        List<String> a = new ArrayList<>();
        for (String n : names) if (!dead.contains(n)) a.add(n);
        return a;
    }

    private static void startWave(RunState s, int wave) {
        List<String> alive = alive();
        if (alive.isEmpty()) return;
        ensureScores();
        for (String n : alive) wavePts.merge(n, WAVE_BONUS * wave, Integer::sum);   // бонус за выживание

        int count = (int) Math.round((3 + 2 * (wave - 1)) * (countMul(s) / 100.0) * (1 + 0.5 * (alive.size() - 1)));
        count = Math.max(2, Math.min(count, 45));

        List<MobType> pool = new ArrayList<>();
        int total = 0;
        for (MobType m : MOBS) if (m.unlockWave() <= wave) { pool.add(m); total += m.weight(); }

        for (int i = 0; i < count; i++) {
            int r = RNG.nextInt(total), acc = 0;
            MobType pick = pool.get(0);
            for (MobType m : pool) { acc += m.weight(); if (r < acc) { pick = m; break; } }
            String target = alive.get(RNG.nextInt(alive.size()));
            int dx = (6 + RNG.nextInt(7)) * (RNG.nextBoolean() ? 1 : -1);
            int dz = (6 + RNG.nextInt(7)) * (RNG.nextBoolean() ? 1 : -1);
            Chaos.run("execute as " + target + " at @s run summon minecraft:" + pick.id() + " ~" + dx + " ~ ~" + dz + " {PersistenceRequired:1b}");
        }
        Chaos.run("time set midnight");
        FriendRunClient.chat("Волна " + wave + ": " + count + " мобов. Бонус за выживание: +" + WAVE_BONUS * wave);
        Snd.play(Snd.CHAOS_TICK, 0.7f, 1.0f);
    }

    // ---------- опрос очков и смертей ----------
    private static void poll(RunState s) {
        pollCounter++;
        for (String name : new ArrayList<>(names)) {
            if (dead.contains(name)) {
                Chaos.run("gamemode spectator " + name);
                if (pollCounter % 3 == 0) retarget(name, true);
                continue;
            }
            List<CompletableFuture<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < MOBS.length; i++) fs.add(Cmd.query("scoreboard players get " + name + " frk_k" + i));
            fs.add(Cmd.query("scoreboard players get " + name + " frk_d"));
            CompletableFuture.allOf(fs.toArray(new CompletableFuture[0]))
                .thenRun(() -> Minecraft.getInstance().execute(() -> process(name, fs, s)));
        }
    }

    private static void process(String name, List<CompletableFuture<Integer>> fs, RunState s) {
        if (!running || dead.contains(name)) return;
        int pts = 0;
        for (int i = 0; i < MOBS.length; i++) {
            Integer k = fs.get(i).getNow(null);
            if (k != null) pts += k * MOBS[i].points();
        }
        pts = pts * pointsMul(s) / 100 + wavePts.getOrDefault(name, 0);
        Integer deaths = fs.get(MOBS.length).getNow(null);
        boolean died = deaths != null && deaths > 0;
        long el = s.elapsedMs();

        if (died) {
            dead.add(name);
            deadAt.put(name, el);
            FriendRunClient.chat(name + " погиб (хардкор). Очки: " + pts + ", продержался " + RunState.formatTime(el));
            Chaos.run("gamemode spectator " + name);
            retarget(name, false);
        }
        int last = lastScore.getOrDefault(name, -1);
        if (died || pts != last) {
            lastScore.put(name, pts);
            FriendRunClient.reportPlayer(name, pts, died ? deadAt.get(name) : el, died);
        }
    }

    // ---------- наблюдение после смерти ----------
    private static void retarget(String spectator, boolean reissue) {
        String cur = watching.get(spectator);
        if (cur != null && !dead.contains(cur) && !reissue) return;
        String target = cur != null && !dead.contains(cur) ? cur : null;
        if (target == null) for (String n : names) if (!dead.contains(n) && !n.equals(spectator)) { target = n; break; }
        if (target == null) return;   // живых больше нет (или игра одиночная)
        watching.put(spectator, target);
        Chaos.run("spectate " + target + " " + spectator);
    }

    // ---------- завершение ----------
    private static void cleanupState() {
        names.clear(); wavePts.clear(); lastScore.clear(); deadAt.clear(); watching.clear(); dead.clear();
        lastWave = 0; tickCounter = 0; pollCounter = 0;
    }

    /** Конец раунда: убрать мобов и таблицы. */
    public static void cleanup() {
        if (!running) { cleanupState(); return; }
        running = false;
        for (MobType m : MOBS) Chaos.run("kill @e[type=minecraft:" + m.id() + "]");
        Chaos.run("scoreboard objectives remove frk_d");
        for (int i = 0; i < MOBS.length; i++) Chaos.run("scoreboard objectives remove frk_k" + i);
        cleanupState();
    }
}
