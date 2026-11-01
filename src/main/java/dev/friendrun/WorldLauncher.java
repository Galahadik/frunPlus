package dev.friendrun;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.Minecraft;

/**
 * Создание мира / публикация / подключение. Сигнатуры этих методов меняются почти каждый релиз,
 * поэтому вызовы собираются по именам и типам параметров (рефлексия): недостающие параметры
 * заполняются значениями по умолчанию (SURVIVAL, NORMAL, false, DEFAULT-константы и т.п.).
 */
public final class WorldLauncher {
    private WorldLauncher() {}

    private static volatile boolean pillarsResetPending;
    private static final String[] PREFER = {"SURVIVAL", "NORMAL", "OTHER", "LAN"};

    // ---------- публичный API ----------
    public static void createWorld(long seed) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Path saves = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("saves");
            String base = "FRun-" + System.currentTimeMillis();
            String name = base;
            int suffix = 1;
            while (Files.exists(saves.resolve(name))) name = base + "-" + suffix++;
            Worlds.registerFriendRunWorld(name);
            Object settings = build(Class.forName("net.minecraft.world.level.LevelSettings"), name);
            Object options = build(Class.forName("net.minecraft.world.level.levelgen.WorldOptions"), seed, true, false);

            Class<?> presets = Class.forName("net.minecraft.world.level.levelgen.presets.WorldPresets");
            Method dims = findMethod(presets, "createNormalWorldDimensions");
            Function<Object, Object> factory = provider -> {
                try { return dims.invoke(null, provider); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            };

            Object flows = Minecraft.class.getMethod("createWorldOpenFlows").invoke(mc);
            Method m = findMethod(flows.getClass(), "createFreshLevel");
            List<Object> pool = new ArrayList<>(Arrays.asList(name, settings, options, factory));
            if (Mc.currentScreen() != null) pool.add(Mc.currentScreen());
            m.invoke(flows, args(m, pool));
        } catch (Throwable t) {
            fail("создание мира", t);
        }
    }

    /**
     * Открывает постоянный мир карты «Столбики».
     * Сохранение намеренно не удаляется при выходе из лобби: scoreboard и command storage карты
     * содержат общий прогресс кликера, разблокировки и статистику игроков.
     */
    public static void createPillarsWorld() {
        Minecraft mc = Minecraft.getInstance();
        Thread worker = new Thread(() -> {
            try {
                Path saves = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("saves");
                Files.createDirectories(saves);
                String name = "FRun-Pillars";
                Path target = saves.resolve(name).normalize();
                if (!target.getParent().equals(saves.normalize())) throw new IOException("Недопустимое имя папки мира");

                if (!Files.isRegularFile(target.resolve("level.dat"))) {
                    if (Files.exists(target)) throw new IOException("Папка FRun-Pillars существует, но не содержит корректный level.dat");
                    Files.createDirectories(target);
                    try (InputStream raw = WorldLauncher.class.getResourceAsStream("/pillars-of-fortune.zip")) {
                        if (raw == null) throw new IOException("Встроенная карта pillars-of-fortune.zip не найдена");
                        try (ZipInputStream zip = new ZipInputStream(raw)) {
                            ZipEntry entry;
                            while ((entry = zip.getNextEntry()) != null) {
                                String entryName = entry.getName();
                                if (entryName.equals("session.lock") || entryName.startsWith("players/")) {
                                    zip.closeEntry();
                                    continue;
                                }
                                Path out = target.resolve(entryName).normalize();
                                if (!out.startsWith(target)) throw new IOException("Небезопасный путь в архиве карты");
                                if (entry.isDirectory()) Files.createDirectories(out);
                                else {
                                    Files.createDirectories(out.getParent());
                                    Files.copy(zip, out);
                                }
                                zip.closeEntry();
                            }
                        }
                    }
                    if (!Files.isRegularFile(target.resolve("level.dat"))) throw new IOException("В архиве карты отсутствует level.dat");
                    FriendRunClient.LOG.info("FRun: постоянная карта «Столбики» установлена в saves/{}", name);
                } else {
                    FriendRunClient.LOG.info("FRun: используем сохранённый мир «Столбики» — прогресс кликера и статистика сохраняются");
                }

                final String worldName = name;
                mc.execute(() -> {
                    try {
                        Object flows = Minecraft.class.getMethod("createWorldOpenFlows").invoke(mc);
                        Method open = findMethod(flows.getClass(), "openWorld");
                        List<Object> pool = new ArrayList<>(Arrays.asList(worldName));
                        if (Mc.currentScreen() != null) pool.add(Mc.currentScreen());
                        open.invoke(flows, args(open, pool));
                        pillarsResetPending = true;
                        FriendRunClient.status("Карта «Столбики» загружается…");
                    } catch (Throwable t) {
                        fail("открытие карты «Столбики»", t);
                    }
                });
            } catch (Throwable t) {
                fail("подготовка карты «Столбики»", t);
            }
        }, "FriendRun-Pillars-Map");
        worker.setDaemon(true);
        worker.start();
    }

    /** После загрузки постоянной карты возвращает незавершённую партию в лобби, не сбрасывая кликер. */
    public static void tick(Minecraft mc) {
        if (!pillarsResetPending || mc.player == null) return;
        Object server = mc.getSingleplayerServer();
        if (server == null) return;
        pillarsResetPending = false;
        try {
            Method enqueue = findMethod(server.getClass(), "execute");
            enqueue.invoke(server, (Runnable) () -> {
                try {
                    Class<?> levelResource = Class.forName("net.minecraft.world.level.storage.LevelResource");
                    Object rootResource = levelResource.getField("ROOT").get(null);
                    Object worldPath = findMethod(server.getClass(), "getWorldPath").invoke(server, rootResource);
                    if (!(worldPath instanceof Path path) || path.getFileName() == null
                        || !"FRun-Pillars".equals(path.getFileName().toString())) {
                        FriendRunClient.LOG.warn("FRun: пропускаем сброс карты — активное сохранение не FRun-Pillars");
                        return;
                    }
                    Object commands = findMethod(server.getClass(), "getCommands").invoke(server);
                    Object source = findMethod(server.getClass(), "createCommandSourceStack").invoke(server);
                    Method command = findMethod(commands.getClass(), "performPrefixedCommand");
                    List<Object> pool = new ArrayList<>(Arrays.asList(source,
                        "execute if data storage minecraft:map {game_running:true} run function cmd:map/func/stop"));
                    command.invoke(commands, args(command, pool));
                } catch (Throwable t) {
                    fail("сброс незавершённого раунда «Столбиков»", t);
                }
            });
        } catch (Throwable t) {
            fail("планирование сброса незавершённого раунда «Столбиков»", t);
        }
    }

    /** Открывает текущий одиночный мир для LAN на свободном порту (e4mc перехватывает это). */
    public static int publish() {
        try {
            Object srv = Minecraft.getInstance().getSingleplayerServer();
            if (srv == null) return -1;
            Method m = findMethod(srv.getClass(), "publishServer");
            List<Object> pool = new ArrayList<>();
            int port = freePort();
            pool.add(port);
            Object r = m.invoke(srv, args(m, pool));
            boolean ok = !(r instanceof Boolean b) || b;
            return ok ? port : -1;
        } catch (Throwable t) {
            fail("открытие мира для сети", t);
            return -1;
        }
    }

    /** Подключается к адресу (домен e4mc, порт по умолчанию). */
    public static void joinServer(String addr) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Class<?> addrCls = Class.forName("net.minecraft.client.multiplayer.resolver.ServerAddress");
            Object address = findMethod(addrCls, "parseString").invoke(null, addr);
            Object data = build(Class.forName("net.minecraft.client.multiplayer.ServerData"), "FRun", addr);
            Class<?> cs = Class.forName("net.minecraft.client.gui.screens.ConnectScreen");
            Method m = findMethod(cs, "startConnecting");
            List<Object> pool = new ArrayList<>(Arrays.asList(mc, address, data));
            if (Mc.currentScreen() != null) pool.add(0, Mc.currentScreen());
            Object[] a = args(m, pool);
            // TransferState должен быть null: иначе игра считает это переходом с другого сервера и падает
            Class<?>[] pt = m.getParameterTypes();
            for (int i = 0; i < pt.length; i++) if (pt[i].getSimpleName().equals("TransferState")) a[i] = null;
            m.invoke(null, a);
        } catch (Throwable t) {
            fail("подключение к миру хоста", t);
        }
    }

    // ---------- рефлексия-хелперы ----------
    private static int freePort() throws Exception {
        try (var s = new java.net.ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static void fail(String what, Throwable t) {
        Throwable c = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
        FriendRunClient.LOG.error("FriendRun: ошибка: " + what, c);
        FriendRunClient.status("Ошибка (" + what + "): " + c);
    }

    private static Method findMethod(Class<?> c, String name) throws NoSuchMethodException {
        Method best = null;
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && (best == null || m.getParameterCount() > best.getParameterCount())) best = m;
        }
        if (best == null) throw new NoSuchMethodException(c.getName() + "#" + name);
        return best;
    }

    private static Object[] args(Method m, List<Object> pool) throws Exception {
        Class<?>[] pt = m.getParameterTypes();
        Object[] out = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) out[i] = arg(pt[i], pool);
        return out;
    }

    private static Object build(Class<?> c, Object... hints) throws Exception {
        List<Object> pool = new ArrayList<>(Arrays.asList(hints));
        Constructor<?>[] cs = c.getConstructors();
        if (cs.length == 0) cs = c.getDeclaredConstructors();
        Arrays.sort(cs, Comparator.comparingInt((Constructor<?> k) -> k.getParameterCount()).reversed());
        Constructor<?> k = cs[0];
        k.setAccessible(true);
        Class<?>[] pt = k.getParameterTypes();
        Object[] a = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) a[i] = arg(pt[i], pool);
        return k.newInstance(a);
    }

    private static Class<?> wrap(Class<?> t) {
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == boolean.class) return Boolean.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        return t;
    }

    private static Object arg(Class<?> t, List<Object> pool) throws Exception {
        Class<?> w = wrap(t);
        for (Iterator<Object> it = pool.iterator(); it.hasNext();) {
            Object o = it.next();
            if (o != null && w.isInstance(o)) { it.remove(); return o; }
        }
        return defaultFor(t);
    }

    private static Object defaultFor(Class<?> t) throws Exception {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == String.class) return "";
        if (t == Optional.class) return Optional.empty();
        if (t.isEnum()) {
            Object[] cs = t.getEnumConstants();
            for (String p : PREFER) for (Object c : cs) if (((Enum<?>) c).name().equals(p)) return c;
            return cs.length > 0 ? cs[0] : null;
        }
        for (Field f : t.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == t && f.getName().equals("DEFAULT")) return f.get(null);
        }
        if (t.isRecord()) return build(t);
        return null;
    }
}
