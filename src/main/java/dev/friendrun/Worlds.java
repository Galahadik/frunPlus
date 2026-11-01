package dev.friendrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Временные миры FriendRun удаляются после выхода из лобби.
 * Для удаления используются только точные имена, зарегистрированные WorldLauncher
 * при создании мира; общий поиск и удаление папок по префиксу запрещены.
 */
public final class Worlds {
    private static final Pattern NAME = Pattern.compile("^FRun-\\d+(-\\d+)?$");

    private static boolean pending;
    private static int idleTicks;
    /** Точные имена временных миров, созданных самим FriendRun в этой/прошлой сессии. */
    private static final Set<String> xaeroContainersToDelete = new LinkedHashSet<>();
    private static Path xaeroPendingFile() {
        return FabricLoader.getInstance().getGameDir().resolve("config").resolve("friendrun-xaero-pending.txt");
    }

    private Worlds() {}

    /**
     * Регистрирует только конкретное имя папки, которое FriendRun только что создал.
     * Никакого поиска по префиксам, адресам серверов или существующим картам Xaero.
     */
    public static synchronized void registerFriendRunWorld(String saveName) {
        // The Pillars world is persistent by design; it must never enter the temporary-world cleanup list.
        if ("FRun-Pillars".equals(saveName)) return;
        if (saveName == null || !NAME.matcher(saveName).matches()) return;
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves").normalize();
        Path world = saves.resolve(saveName).normalize();
        if (!saves.equals(world.getParent())) return;
        xaeroContainersToDelete.add(saveName);
        persistXaeroContainers(xaeroContainersToDelete);
    }

    /** После выхода из лобби запускаем безопасную очистку ранее зарегистрированного мира. */
    public static synchronized void markForDelete() {
        loadPersistedXaeroContainers();
        pending = true;
        idleTicks = 0;
    }

    /** Сетевые карты Xaero не удаляем: один адрес может использоваться вне FriendRun. */
    public static synchronized void markForDelete(String ignoredServerAddress) {
        markForDelete();
    }

    /** Сетевые карты не регистрируются — их идентификатор не доказывает принадлежность FriendRun. */
    public static synchronized void trackServerAddress(String ignoredServerAddress) {
        // Intentionally empty: never delete Multiplayer_* Xaero containers.
    }

    private static void loadPersistedXaeroContainers() {
        Path file = xaeroPendingFile();
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (NAME.matcher(line).matches()) xaeroContainersToDelete.add(line);
            }
        } catch (IOException e) {
            FriendRunClient.LOG.warn("FRun: не удалось прочитать список карт Xaero: {}", e.toString());
        }
    }

    private static void persistXaeroContainers(Set<String> containers) {
        Path file = xaeroPendingFile();
        try {
            Files.createDirectories(file.getParent());
            String content = containers.stream().filter(name -> name != null && NAME.matcher(name).matches())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            FriendRunClient.LOG.warn("FRun: не удалось сохранить список карт Xaero: {}", e.toString());
        }
    }

    public static void tick(Minecraft mc) {
        if (!pending) return;
        // ждём, пока игрок не в мире, мир не грузится и сервер остановлен (иначе удалим живой мир)
        var scr = Mc.currentScreen();
        String sn = scr == null ? "" : scr.getClass().getSimpleName();
        boolean safeScreen = sn.equals("TitleScreen") || sn.startsWith("Lobby");
        if (mc.player != null || mc.getSingleplayerServer() != null || !safeScreen) { idleTicks = 0; return; }
        if (++idleTicks < 60) return;   // ~3 секунды после выхода из мира: сохранение уже завершено
        pending = false;
        idleTicks = 0;
        Set<String> xaeroTargets;
        synchronized (Worlds.class) {
            xaeroTargets = new LinkedHashSet<>(xaeroContainersToDelete);
        }
        Thread t = new Thread(() -> {
            Set<String> failed = deleteXaeroMaps(xaeroTargets);
            deleteAll(xaeroTargets, failed);
            synchronized (Worlds.class) {
                xaeroContainersToDelete.clear();
                xaeroContainersToDelete.addAll(failed);
                persistXaeroContainers(xaeroContainersToDelete);
            }
        }, "FRun-WorldCleanup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Удаляет из Xaero World Map все непосредственные папки с префиксом "Frun".
     * Это намеренно шире списка временных миров: пользователь выбрал очищать
     * все карты FriendRun, включая сетевые. Папки с другими именами не трогаем.
     * Символические ссылки не разыменовываются; корень и каждый кандидат
     * проверяются на принадлежность каталогу xaero/world-map.
     */
    private static Set<String> deleteXaeroMaps(Set<String> targets) {
        Set<String> failed = new LinkedHashSet<>();
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path xaeroDir = gameDir.resolve("xaero").normalize();
        Path root = xaeroDir.resolve("world-map").normalize();
        if (!xaeroDir.getParent().equals(gameDir) || !root.getParent().equals(xaeroDir)
            || Files.isSymbolicLink(xaeroDir) || Files.isSymbolicLink(root)) {
            FriendRunClient.LOG.warn("FRun: очистка Xaero отменена — небезопасный путь к каталогу world-map");
            return failed;
        }
        if (!Files.isDirectory(root)) return failed;
        try (Stream<Path> entries = Files.list(root)) {
            entries.forEach(candidate -> {
                Path normalized = candidate.toAbsolutePath().normalize();
                Path fileName = normalized.getFileName();
                if (fileName == null) return;
                String name = fileName.toString();
                if (name.length() < 4 || !name.regionMatches(true, 0, "Frun", 0, 4)) return;
                // Только папки первого уровня. Ссылки и файлы не открываем/не удаляем.
                if (!normalized.getParent().equals(root) || Files.isSymbolicLink(candidate)
                    || !Files.isDirectory(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
                if (!deleteRecursively(candidate)) failed.add(name);
            });
        } catch (IOException e) {
            FriendRunClient.LOG.warn("FRun: не удалось просканировать папки карт Xaero: {}", e.toString());
            return new LinkedHashSet<>(targets);
        }
        if (!failed.isEmpty()) FriendRunClient.LOG.warn("FRun: не удалось удалить некоторые папки карт Xaero Frun*: {}", failed);
        else FriendRunClient.LOG.info("FRun: очистка карт Xaero завершена (все папки Frun* обработаны)");
        return failed;
    }

    /** Удаляем только точные зарегистрированные временные миры, а не все FRun-* папки. */
    private static void deleteAll(Set<String> targets, Set<String> xaeroFailed) {
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves").toAbsolutePath().normalize();
        if (!Files.isDirectory(saves) || Files.isSymbolicLink(saves)) return;
        int removed = 0;
        for (String name : targets) {
            if (name == null || !NAME.matcher(name).matches() || xaeroFailed.contains(name)) continue;
            Path world = saves.resolve(name).normalize();
            if (!world.getParent().equals(saves) || Files.isSymbolicLink(world)) continue;
            // Не трогаем каталог, если это не полноценное сохранение Minecraft.
            if (!Files.isDirectory(world) || !Files.isRegularFile(world.resolve("level.dat"))) continue;
            if (deleteRecursively(world)) removed++;
        }
        if (removed > 0) FriendRunClient.LOG.info("FRun: удалено временных миров FriendRun: {}", removed);
    }

    private static boolean deleteRecursively(Path dir) {
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            return false;
        }
        return !Files.exists(dir);
    }
}
