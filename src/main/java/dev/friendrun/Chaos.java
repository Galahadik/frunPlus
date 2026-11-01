package dev.friendrun;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;

/**
 * Случайные усложнения раунда. Применяются ванильными командами на сервере мира, поэтому работают там,
 * где у игрока есть встроенный сервер (свой мир или мир хоста — хост применяет ко всем игрокам мира).
 *
 * period: 1 — состояние (эффект/атрибут), переприменяется каждые 5 секунд (возвращается после смерти);
 *         N>1 — событие, выполняется раз в N*5 секунд (но не в первую секунду раунда).
 */
public final class Chaos {
    public record Mod(String id, String name, String desc, int period, String[] commands, double hp) {}

    private static Mod m(String id, String name, String desc, int period, String... cmds) { return new Mod(id, name, desc, period, cmds, 0); }

    /** Усложнение «максимум здоровья»: и командой, и напрямую через атрибут игрока (надёжнее). */
    private static Mod hp(String id, String name, String desc, int maxHealth) {
        return new Mod(id, name, desc, 1, new String[] {attr("max_health", String.valueOf(maxHealth))}, maxHealth);
    }

    public static final Mod[] MODS = {
        // --- эффекты ---
        m("slowness", "Замедление", "Вы двигаетесь медленнее обычного.", 1, "effect give @a minecraft:slowness infinite 1 true"),
        m("mining_fatigue", "Усталость", "Блоки ломаются гораздо дольше.", 1, "effect give @a minecraft:mining_fatigue infinite 2 true"),
        m("hunger", "Постоянный голод", "Сытость быстро падает — ищите еду.", 1, "effect give @a minecraft:hunger infinite 3 true"),
        m("darkness", "Тьма", "Периодически темнеет в глазах.", 1, "effect give @a minecraft:darkness infinite 0 true"),
        m("nausea", "Тошнота", "Искажение экрана на 5 секунд, затем перерыв на 5 секунд.", 2, "effect give @a minecraft:nausea 5 0 true"),
        m("glowing", "Свечение", "Все игроки подсвечены и видны сквозь стены.", 1, "effect give @a minecraft:glowing infinite 0 true"),
        m("blindness", "Слепота", "Вы почти ничего не видите и не можете бегать.", 1, "effect give @a minecraft:blindness infinite 0 true"),
        m("poison", "Вечный яд", "Здоровье падает до 1 сердца, но не ниже.", 1, "effect give @a minecraft:poison infinite 0 true"),
        m("weakness", "Слабость", "Ваши удары почти не наносят урона.", 1, "effect give @a minecraft:weakness infinite 1 true"),
        m("jump_boost", "Прыгучесть", "Вы прыгаете гораздо выше обычного.", 1, "effect give @a minecraft:jump_boost infinite 6 true"),
        m("speed", "Ускорение", "Вы бегаете значительно быстрее обычного.", 1, "effect give @a minecraft:speed infinite 8 true"),
        // --- здоровье и размер ---
        hp("hearts5", "Всего 5 сердец", "Максимум здоровья — 5 сердец.", 10),
        hp("hearts3", "Хрупкость", "Максимум здоровья — всего 3 сердца.", 6),
        hp("hearts1", "Одно сердце", "Одна ошибка — и конец. Максимум 1 сердце.", 2),
        m("giant", "Великан", "Вы огромный: цепляетесь за всё вокруг.", 1),
        m("tiny", "Малыш", "Вы крошечный, и всё кажется огромным.", 1),
        // --- физика ---
        m("gravity_heavy", "Тяжёлая гравитация", "Вы падаете и прыгаете как в свинце.", 1, attr("gravity", "0.2")),
        m("gravity_moon", "Лунная гравитация", "Вы парите и падаете очень медленно.", 1, attr("gravity", "0.02")),
        m("short_reach", "Короткие руки", "До блоков и существ можно дотянуться только с близкого расстояния.", 1),
        // --- мир ---
        m("night", "Вечная ночь", "Солнце не встаёт, мобы не горят.", 1, "time set midnight"),
        m("thunder", "Гроза", "Непрерывная гроза и молнии.", 1, "weather thunder 6000"),
        // --- события ---
        m("teleport", "Телепорт-хаос", "Каждые 30 секунд вас бросает в другое место.", 6,
            "execute as @a at @s run spreadplayers ~ ~ 1 30 false @s",
            "title @a actionbar \"Телепорт!\""),
        m("zombies", "Нашествие зомби", "Каждые 20 секунд рядом появляется зомби.", 4,
            "execute as @a at @s run summon minecraft:zombie ~2 ~ ~2",
            "title @a actionbar \"Зомби рядом!\""),
        m("creeper", "Сюрприз: крипер", "Каждые 30 секунд рядом появляется крипер.", 6,
            "execute as @a at @s run summon minecraft:creeper ~3 ~ ~3",
            "title @a actionbar \"Крипер рядом!\""),
    };
    public static final int COUNT = MODS.length;

    private static boolean active;
    private static int calls;
    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Chaos() {}

    public static int indexOfId(String id) {
        for (int i = 0; i < COUNT; i++) if (MODS[i].id().equals(id)) return i;
        return -1;
    }

    /** Индексы усложнений, не входящих в набор выключенных id. */
    public static int[] enabledIndices(java.util.Set<String> off) {
        int n = 0;
        int[] tmp = new int[COUNT];
        for (int i = 0; i < COUNT; i++) if (!off.contains(MODS[i].id())) tmp[n++] = i;
        return java.util.Arrays.copyOf(tmp, n);
    }

    /** Случайное включённое усложнение (если выключены все — любое). */
    public static int randomIndex(java.util.Random r, java.util.Set<String> off) {
        int[] e = enabledIndices(off);
        return e.length == 0 ? r.nextInt(COUNT) : e[r.nextInt(e.length)];
    }

    public static String descOf(int idx) { return idx >= 0 && idx < COUNT ? MODS[idx].desc() : ""; }

    public static String nameOf(int idx) { return idx >= 0 && idx < COUNT ? MODS[idx].name() : ""; }

    /** Вызывается в начале раунда и затем каждые 5 секунд. */
    public static void apply(int idx) {
        if (idx < 0 || idx >= COUNT) return;
        if (!active) { active = true; calls = 0; }
        Mod mod = MODS[idx];
        boolean now = mod.period() == 1 || (calls > 0 && calls % mod.period() == 0);
        calls++;
        if (!now) return;
        for (String c : mod.commands()) run(c);
        applyDirectAttributes(mod.id());
        if (mod.hp() > 0) setMaxHealth(mod.hp());
    }

    /** Проверка: выполнить команды усложнения прямо сейчас (номер с 1). Ошибки команд покажутся в чате. */
    public static void test(int number) {
        int idx = number - 1;
        if (idx < 0 || idx >= COUNT) { FriendRunClient.chat("Номер от 1 до " + COUNT + ". Список: /rr chaos list"); return; }
        if (!(Minecraft.getInstance().getSingleplayerServer() instanceof Executor)) {
            FriendRunClient.chat("Нужен свой мир (встроенный сервер): зайдите в одиночный мир или мир FRun.");
            return;
        }
        REPORTED.clear();
        clear();
        active = true;
        FriendRunClient.chat("Тест усложнения: " + MODS[idx].name());
        for (String c : MODS[idx].commands()) run(c);
        applyDirectAttributes(MODS[idx].id());
        if (MODS[idx].hp() > 0) setMaxHealth(MODS[idx].hp());
    }

    public static void clear() {
        if (!active) return;
        active = false;
        calls = 0;
        run("effect clear @a");
        run(attr("max_health", "20"));
        run(attr("scale", "1"));
        run(attr("gravity", "0.08"));
        run(attr("block_interaction_range", "4.5"));
        run(attr("entity_interaction_range", "3.0"));
        setDirectAttribute("SCALE", 1.0);
        setDirectAttribute("BLOCK_INTERACTION_RANGE", 4.5);
        setDirectAttribute("ENTITY_INTERACTION_RANGE", 3.0);
        setMaxHealth(20);
    }

    /** Команда атрибута с запасным названием (в разных версиях: minecraft:scale или minecraft:generic.scale). */
    public static String attr(String name, String value) {
        return "attribute @a minecraft:" + name + " base set " + value
            + " || attribute @a minecraft:generic." + name + " base set " + value;
    }

    // ---------- команда на сервере (рефлексия: сигнатуры меняются между версиями) ----------
    /** cmd может содержать запасные варианты через " || " — пробуются по очереди до первого успешного. */
    static void run(String cmd) {
        Object srv = Minecraft.getInstance().getSingleplayerServer();
        if (!(srv instanceof Executor ex)) return;
        ex.execute(() -> {
            try {
                Object src = call(srv, "createCommandSourceStack");
                Object quiet = call(src, "withSuppressedOutput");
                if (quiet != null) src = quiet;
                Object commands = call(srv, "getCommands");
                Object disp = call(commands, "getDispatcher");

                String[] alts = cmd.split(" \\|\\| ");
                Throwable last = null;
                String lastCmd = cmd;
                for (String alt : alts) {
                    lastCmd = alt;
                    if (disp == null) {
                        call(commands, "performPrefixedCommand", src, alt);
                        last = null;
                        break;
                    }
                    Method exec = disp.getClass().getMethod("execute", String.class, Object.class);
                    try {
                        exec.invoke(disp, alt, src);
                        last = null;
                        break;
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        last = ite.getCause() != null ? ite.getCause() : ite;
                    }
                }
                if (last != null) {
                    FriendRunClient.LOG.warn("FRun: команда не сработала: {} -> {}", lastCmd, last.toString());
                    if (REPORTED.add(lastCmd)) FriendRunClient.chat("Команда не сработала: " + lastCmd + " → " + last.getMessage());
                }
            } catch (Throwable t) {
                FriendRunClient.LOG.warn("FRun: ошибка выполнения команды: " + cmd, t);
            }
        });
    }


    /** Эти три усложнения меняют атрибуты напрямую на встроенном сервере.
     * Это надёжнее команд attribute в разных сборках Minecraft 26.3. */
    private static void applyDirectAttributes(String id) {
        switch (id) {
            case "giant" -> setDirectAttribute("SCALE", 2.5);
            case "tiny" -> setDirectAttribute("SCALE", 0.4);
            case "short_reach" -> {
                setDirectAttribute("BLOCK_INTERACTION_RANGE", 1.5);
                setDirectAttribute("ENTITY_INTERACTION_RANGE", 1.5);
            }
            default -> { }
        }
    }

    /** Устанавливает атрибут всем игрокам встроенного сервера через API атрибутов. */
    private static void setDirectAttribute(String fieldName, double value) {
        Object srv = Minecraft.getInstance().getSingleplayerServer();
        if (!(srv instanceof Executor ex)) return;
        ex.execute(() -> {
            try {
                Object plist = callPrim(srv, "getPlayerList");
                Object playersObj = callPrim(plist, "getPlayers");
                if (!(playersObj instanceof java.util.List<?> players)) return;
                Class<?> attributes = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes");
                Object holder = attributes.getField(fieldName).get(null);
                for (Object player : players) {
                    Object instance = callPrim(player, "getAttribute", holder);
                    if (instance == null) {
                        Object raw = callPrim(holder, "value");
                        if (raw != null) instance = callPrim(player, "getAttribute", raw);
                    }
                    if (instance == null) {
                        FriendRunClient.LOG.warn("FRun: не найден атрибут {} у игрока", fieldName);
                        continue;
                    }
                    callPrim(instance, "setBaseValue", value);
                }
            } catch (Throwable t) {
                FriendRunClient.LOG.warn("FRun: не удалось установить атрибут {} = {}", fieldName, value, t);
            }
        });
    }

    private static boolean warnedHp;

    /** Ставит максимум здоровья всем игрокам мира напрямую через атрибут (на потоке сервера). */
    static void setMaxHealth(double hp) {
        Object srv = Minecraft.getInstance().getSingleplayerServer();
        if (!(srv instanceof Executor ex)) return;
        ex.execute(() -> {
            try {
                Object plist = callPrim(srv, "getPlayerList");
                Object playersObj = callPrim(plist, "getPlayers");
                if (!(playersObj instanceof java.util.List<?> players)) return;
                Object maxHealth = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes").getField("MAX_HEALTH").get(null);
                for (Object p : players) {
                    Object inst = callPrim(p, "getAttribute", maxHealth);
                    if (inst == null) {
                        Object raw = callPrim(maxHealth, "value");
                        if (raw != null) inst = callPrim(p, "getAttribute", raw);
                    }
                    if (inst == null) {
                        if (!warnedHp) { warnedHp = true; FriendRunClient.LOG.warn("FRun: не найден getAttribute(MAX_HEALTH)"); }
                        continue;
                    }
                    callPrim(inst, "setBaseValue", hp);
                    Object h = callPrim(p, "getHealth");
                    if (h instanceof Float f && f > hp) callPrim(p, "setHealth", (float) hp);
                }
            } catch (Throwable t) {
                if (!warnedHp) { warnedHp = true; FriendRunClient.LOG.warn("FRun: не удалось изменить максимум здоровья", t); }
            }
        });
    }

    private static Class<?> wrap(Class<?> t) {
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == boolean.class) return Boolean.class;
        return t;
    }

    /** Как call(), но понимает примитивные параметры (double, float...). */
    private static Object callPrim(Object o, String name, Object... args) throws Exception {
        if (o == null) return null;
        for (Method m : o.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            Class<?>[] pt = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < pt.length; i++) if (args[i] == null || !wrap(pt[i]).isInstance(args[i])) { ok = false; break; }
            if (ok) { m.setAccessible(true); return m.invoke(o, args); }
        }
        return null;
    }

    private static boolean hasMethod(Object o, String name) {
        for (Method m : o.getClass().getMethods()) if (m.getName().equals(name)) return true;
        return false;
    }

    private static Object call(Object o, String name, Object... args) throws Exception {
        if (o == null) return null;
        for (Method m : o.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            Class<?>[] pt = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < pt.length; i++) if (args[i] != null && !pt[i].isInstance(args[i])) { ok = false; break; }
            if (ok) { m.setAccessible(true); return m.invoke(o, args); }
        }
        return null;
    }
}
