package dev.friendrun;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Настройки мода.
 *
 * Два набора значений:
 *  - локальные (config/friendrun.json) — то, что игрок выставил в меню; хост передаёт их в лобби;
 *  - лобби-значения — то, что хост записал в документ лобби. Пока игрок в лобби, ИГРОВАЯ логика
 *    (выбор предмета, анимация усложнения) берёт именно их, чтобы у всех игроков был одинаковый пул
 *    предметов: предмет определяется из seed на каждом клиенте отдельно.
 *
 * Меняют настройки пула предметов: вне лобби — кто угодно (сохраняется на будущее), в лобби — только хост
 * и только до нажатия «Старт». Усложнения хост может менять и после старта (их выбирает только хост).
 */
public final class Settings {
    // ---- локальные (сохраняются) ----
    private static volatile Set<String> localOffItems = Set.of();
    private static volatile Set<String> localOffChaos = Set.of();
    private static volatile boolean localSkipHard = false;       // исключить очень тяжёлые предметы
    private static volatile boolean localFamilyWeighted = true;  // шанс по семействам (иначе у каждого предмета равный)
    /** Новые лобби создаются с включённым случайным усложнением (только у вас). */
    public static volatile boolean chaosDefault = false;
    /** Звуки рулетки (только у вас). */
    public static volatile boolean sounds = true;

    // ---- значения из лобби ----
    private static volatile Set<String> lobbyOffItems = Set.of();
    private static volatile Set<String> lobbyOffChaos = Set.of();
    private static volatile boolean lobbySkipHard = false;
    private static volatile boolean lobbyFamilyWeighted = true;

    private static volatile int version;

    private Settings() {}

    // ---------- версия пула (для сброса кэша ItemPicker) ----------
    public static int version() { return version; }

    /** Ключ кэша: меняется при любом изменении настроек пула и при входе/выходе из лобби. */
    public static int poolKey() { return version * 2 + (FriendRunClient.inLobby() ? 1 : 0); }

    private static void bump() { version++; }

    // ---------- значения для игровой логики ----------
    public static Set<String> effOffItems() { return FriendRunClient.inLobby() ? lobbyOffItems : localOffItems; }
    public static Set<String> effOffChaos() { return FriendRunClient.inLobby() ? lobbyOffChaos : localOffChaos; }
    public static boolean effSkipHard() { return FriendRunClient.inLobby() ? lobbySkipHard : localSkipHard; }
    public static boolean effFamilyWeighted() { return FriendRunClient.inLobby() ? lobbyFamilyWeighted : localFamilyWeighted; }

    /** Локальные значения выключенных усложнений (их использует хост при ролле; потокобезопасно). */
    public static Set<String> localOffChaos() { return localOffChaos; }

    // ---------- значения для отображения в меню настроек ----------
    private static boolean viewLobby() { return FriendRunClient.inLobby() && !FriendRunClient.STATE.amHost; }

    public static Set<String> viewOffItems() { return viewLobby() ? lobbyOffItems : localOffItems; }
    public static Set<String> viewOffChaos() { return viewLobby() ? lobbyOffChaos : localOffChaos; }
    public static boolean viewSkipHard() { return viewLobby() ? lobbySkipHard : localSkipHard; }
    public static boolean viewFamilyWeighted() { return viewLobby() ? lobbyFamilyWeighted : localFamilyWeighted; }

    // ---------- что можно менять ----------
    public static boolean canEditItems() {
        if (!FriendRunClient.inLobby()) return true;
        return FriendRunClient.STATE.amHost && !FriendRunClient.STATE.launched;
    }

    public static boolean canEditChaos() {
        return !FriendRunClient.inLobby() || FriendRunClient.STATE.amHost;
    }

    /** Подпись режима для шапки меню настроек: {текст, цвет}. */
    public static Object[] modeNote() {
        if (!FriendRunClient.inLobby()) return new Object[] {"Вне лобби: настройки сохраняются и применятся к новым лобби", Ui.MUTED};
        RunState s = FriendRunClient.STATE;
        if (!s.amHost) return new Object[] {"Вы в лобби: настройки задаёт хост (только просмотр)", Ui.ORANGE};
        if (s.launched) return new Object[] {"Игра запущена: предметы зафиксированы, усложнения менять можно", Ui.ORANGE};
        return new Object[] {"Вы хост: изменения сразу уходят всем игрокам лобби", Ui.GREEN};
    }

    // ---------- изменение (локальное + отправка в лобби) ----------
    private static Set<String> with(Set<String> src, Collection<String> ids, boolean add) {
        Set<String> n = new HashSet<>(src);
        if (add) n.addAll(ids); else n.removeAll(ids);
        return Set.copyOf(n);
    }

    /** Выключить (off=true) или включить предметы. */
    public static void setItemsOff(Collection<String> ids, boolean off) {
        if (!canEditItems() || ids.isEmpty()) return;
        localOffItems = with(localOffItems, ids, off);
        commitItems();
    }

    public static void setSkipHard(boolean v) {
        if (!canEditItems()) return;
        localSkipHard = v;
        commitItems();
    }

    public static void setFamilyWeighted(boolean v) {
        if (!canEditItems()) return;
        localFamilyWeighted = v;
        commitItems();
    }

    public static void setChaosOff(Collection<String> ids, boolean off) {
        if (!canEditChaos() || ids.isEmpty()) return;
        localOffChaos = with(localOffChaos, ids, off);
        commitChaos();
    }

    public static void setChaosDefault(boolean v) { chaosDefault = v; Config.save(); }

    public static void setSounds(boolean v) { sounds = v; Config.save(); }

    /** Сбросить всё к значениям по умолчанию (то, что сейчас можно менять). */
    public static void resetAll() {
        boolean items = canEditItems(), chaos = canEditChaos();
        if (items) { localOffItems = Set.of(); localSkipHard = false; localFamilyWeighted = true; }
        if (chaos) localOffChaos = Set.of();
        chaosDefault = false;
        sounds = true;
        if (items) commitItems(); else bump();
        if (chaos) commitChaos();
        Config.save();
    }

    private static void commitItems() {
        bump();
        Config.save();
        FriendRunClient.pushPool();
    }

    private static void commitChaos() {
        Config.save();
        FriendRunClient.pushChaos();
    }

    // ---------- сохранение в config/friendrun.json ----------
    static void read(JsonObject o) {
        localOffItems = readSet(o, "offItems");
        localOffChaos = readSet(o, "offChaos");
        if (o.has("skipHard")) localSkipHard = o.get("skipHard").getAsBoolean();
        if (o.has("familyWeighted")) localFamilyWeighted = o.get("familyWeighted").getAsBoolean();
        if (o.has("chaosDefault")) chaosDefault = o.get("chaosDefault").getAsBoolean();
        if (o.has("sounds")) sounds = o.get("sounds").getAsBoolean();
        bump();
    }

    static void write(JsonObject o) {
        o.add("offItems", toArray(localOffItems));
        o.add("offChaos", toArray(localOffChaos));
        o.addProperty("skipHard", localSkipHard);
        o.addProperty("familyWeighted", localFamilyWeighted);
        o.addProperty("chaosDefault", chaosDefault);
        o.addProperty("sounds", sounds);
    }

    private static Set<String> readSet(JsonObject o, String key) {
        Set<String> s = new HashSet<>();
        if (o.has(key) && o.get(key).isJsonArray()) for (var e : o.getAsJsonArray(key)) s.add(e.getAsString());
        return Set.copyOf(s);
    }

    private static JsonArray toArray(Set<String> set) {
        JsonArray a = new JsonArray();
        set.stream().sorted().forEach(a::add);
        return a;
    }

    // ---------- синхронизация с лобби ----------
    /**
     * Предметы в документе: пустая строка — всё включено; "-a,b" — выключены a и b;
     * "+a,b" — включены ТОЛЬКО a и b. Выбирается более короткая запись, чтобы документ лобби
     * (его опрашивают раз в секунду) оставался маленьким.
     */
    public static String encodeItems(Set<String> off) {
        Set<String> base = ItemPicker.baseIds();
        Set<String> offIn = new HashSet<>(off);
        offIn.retainAll(base);
        if (offIn.isEmpty()) return "";
        Set<String> on = new HashSet<>(base);
        on.removeAll(offIn);
        if (offIn.size() <= on.size()) return "-" + String.join(",", offIn.stream().sorted().toList());
        return "+" + String.join(",", on.stream().sorted().toList());
    }

    public static Set<String> decodeItems(String enc) {
        if (enc == null || enc.isEmpty()) return Set.of();
        Set<String> listed = new HashSet<>();
        for (String p : enc.substring(1).split(",")) if (!p.isEmpty()) listed.add(p);
        if (enc.charAt(0) == '-') return Set.copyOf(listed);
        Set<String> off = new HashSet<>(ItemPicker.baseIds());
        off.removeAll(listed);
        return Set.copyOf(off);
    }

    public static String encodeChaos(Set<String> off) {
        return String.join(",", off.stream().sorted().toList());
    }

    private static Set<String> decodeChaos(String enc) {
        Set<String> s = new HashSet<>();
        for (String p : enc.split(",")) if (!p.isEmpty()) s.add(p);
        return Set.copyOf(s);
    }

    /** Поля настроек для нового документа лобби (локальные значения хоста). Вызывать из главного потока. */
    public static JsonObject toDoc() {
        JsonObject o = new JsonObject();
        o.addProperty("offItems", encodeItems(localOffItems));
        o.addProperty("skipHard", localSkipHard);
        o.addProperty("famMode", localFamilyWeighted);
        o.addProperty("offChaos", encodeChaos(localOffChaos));
        return o;
    }

    /** Применить настройки из документа лобби (главный поток, в начале apply()). */
    public static void applyLobby(JsonObject doc) {
        Set<String> items = decodeItems(Lobby.str(doc, "offItems"));
        Set<String> chaos = decodeChaos(Lobby.str(doc, "offChaos"));
        boolean skip = Lobby.bool(doc, "skipHard");
        boolean fam = !doc.has("famMode") || Lobby.bool(doc, "famMode");
        if (items.equals(lobbyOffItems) && chaos.equals(lobbyOffChaos) && skip == lobbySkipHard && fam == lobbyFamilyWeighted) return;
        lobbyOffItems = items;
        lobbyOffChaos = chaos;
        lobbySkipHard = skip;
        lobbyFamilyWeighted = fam;
        bump();
    }

    /** Сбрасывает лобби-значения (вход в новое лобби / выход). */
    public static void clearLobby() {
        lobbyOffItems = Set.of();
        lobbyOffChaos = Set.of();
        lobbySkipHard = false;
        lobbyFamilyWeighted = true;
        bump();
    }
}
