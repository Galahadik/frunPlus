package dev.friendrun;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Выбор предмета раунда. Одинаковый seed -> одинаковый предмет у всех игроков (даже в разных мирах).
 * Шанс у всех СЕМЕЙСТВ предметов одинаковый (все цвета шерсти — одно семейство, алмаз — другое);
 * внутри семейства предметы равновероятны.
 */
public final class ItemPicker {
    /** Предметы, которые НЕЛЬЗЯ получить в выживании без команд. */
    private static final Set<String> UNOBTAINABLE = Set.of(
        "air", "bedrock", "barrier", "light", "structure_void", "structure_block", "jigsaw",
        "command_block", "chain_command_block", "repeating_command_block", "command_block_minecart",
        "debug_stick", "knowledge_book", "end_portal_frame", "farmland", "frogspawn", "budding_amethyst",
        "spawner", "trial_spawner", "vault", "reinforced_deepslate", "petrified_oak_slab", "player_head",
        "suspicious_sand", "suspicious_gravel", "tall_grass", "large_fern", "test_block", "test_instance_block");
    private static final String[] UNOBTAINABLE_PREFIX = {"infested_", "test_"};
    private static final String[] UNOBTAINABLE_SUFFIX = {"_spawn_egg"};

    /** Получаемые, но очень тяжёлые предметы. */
    private static final Set<String> VERY_HARD = Set.of(
        "dragon_egg", "dragon_head", "elytra", "beacon", "nether_star", "wither_rose",
        "enchanted_golden_apple", "heavy_core", "trial_key", "ominous_trial_key", "mace",
        "conduit", "heart_of_the_sea", "dragon_breath", "lingering_potion", "netherite_scrap",
        "netherite_ingot", "netherite_block", "disc_fragment_5", "echo_shard", "recovery_compass");
    private static final String[] VERY_HARD_PREFIX = {"netherite_", "music_disc_"};
    private static final String[] VERY_HARD_SUFFIX = {"_smithing_template", "shulker_box"};

    private ItemPicker() {}

    /** Предмет вообще может выпасть (получаем в выживании, ванильный). Без учёта настроек игрока. */
    private static boolean baseAllowed(Item item) {
        var key = BuiltInRegistries.ITEM.getKey(item);
        if (!key.getNamespace().equals("minecraft")) return false;   // предметы других модов не берём
        String id = key.getPath();

        if (UNOBTAINABLE.contains(id)) return false;
        for (String p : UNOBTAINABLE_PREFIX) if (id.startsWith(p)) return false;
        for (String sfx : UNOBTAINABLE_SUFFIX) if (id.endsWith(sfx)) return false;
        return true;
    }

    /** Получаемый, но очень тяжёлый предмет (эндгейм, редкий лут). */
    public static boolean isHard(String id) {
        if (VERY_HARD.contains(id)) return true;
        for (String p : VERY_HARD_PREFIX) if (id.startsWith(p)) return true;
        for (String sfx : VERY_HARD_SUFFIX) if (id.endsWith(sfx)) return true;
        return false;
    }

    public static String idOf(Item item) { return BuiltInRegistries.ITEM.getKey(item).getPath(); }

    /** Resolves a vanilla item path stored in Firebase. */
    public static Item byId(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            Item item = BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", id))
                .map(reference -> reference.value()).orElse(null);
            return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
        } catch (RuntimeException ignored) { return null; }
    }

    private static List<Item> base;
    private static Set<String> baseIds;

    /** Все предметы, которые могут выпасть (до настроек игрока), по алфавиту id. */
    public static synchronized List<Item> baseItems() {
        if (base == null) {
            base = BuiltInRegistries.ITEM.stream().filter(ItemPicker::baseAllowed)
                .sorted(Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).toString()))
                .toList();
            baseIds = new java.util.HashSet<>();
            for (Item i : base) baseIds.add(idOf(i));
        }
        return base;
    }

    public static Set<String> baseIds() {
        baseItems();
        return baseIds;
    }

    /** Проходит ли предмет с учётом настроек (off — набор выключенных id). */
    private static boolean inPool(Item item, Set<String> off, boolean skipHard) {
        String id = idOf(item);
        return !off.contains(id) && !(skipHard && isHard(id));
    }

    // ---------- семейства: у каждого семейства одинаковый шанс, внутри семейства предметы равновероятны ----------
    private static final String[] COLORS = {"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime",
        "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] WOODS = {"dark_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove",
        "cherry", "pale_oak", "bamboo", "crimson", "warped"};
    private static final String[] COPPER = {"waxed_", "exposed_", "weathered_", "oxidized_"};
    private static final String[] NOT_COLOR = {"blue_ice", "red_sand", "red_nether_brick"};
    /** Форма предмета: все ступени — одно семейство, все плиты — другое и т. д. */
    private static final String[][] SHAPES = {
        {"_chest_raft", "chest_boat"}, {"_chest_boat", "chest_boat"}, {"_raft", "boat"}, {"_boat", "boat"},
        {"_stairs", "stairs"}, {"_slab", "slab"}, {"_wall", "wall"}, {"_fence_gate", "fence_gate"}, {"_fence", "fence"},
        {"_door", "door"}, {"_trapdoor", "trapdoor"}, {"_button", "button"}, {"_pressure_plate", "pressure_plate"},
        {"_hanging_sign", "hanging_sign"}, {"_sign", "sign"},
        {"_sword", "sword"}, {"_pickaxe", "pickaxe"}, {"_axe", "axe"}, {"_shovel", "shovel"}, {"_hoe", "hoe"}, {"_spear", "spear"},
        {"_helmet", "helmet"}, {"_chestplate", "chestplate"}, {"_leggings", "leggings"}, {"_boots", "boots"},
        {"_horse_armor", "horse_armor"}, {"_nautilus_armor", "nautilus_armor"},
        {"_leaves", "leaves"}, {"_sapling", "sapling"}, {"_planks", "planks"}, {"_log", "log"}, {"_wood", "wood"},
        {"_stem", "stem"}, {"_hyphae", "hyphae"}};

    /** Ключ семейства для id предмета. */
    static String family(String id) {
        if (id.startsWith("music_disc_")) return "music_disc";
        if (id.endsWith("_pottery_sherd")) return "pottery_sherd";
        if (id.endsWith("_smithing_template")) return "smithing_template";
        if (id.endsWith("_banner_pattern")) return "banner_pattern";

        String coral = id.replaceFirst("^(dead_)?(tube|brain|bubble|fire|horn)_coral", "$1coral");
        if (!coral.equals(id)) return coral;

        String s = id;
        boolean again = true;
        while (again) {
            again = false;
            for (String c : COPPER) if (s.startsWith(c)) { s = s.substring(c.length()); again = true; }
        }
        if (s.equals("copper")) s = "copper_block";

        boolean stripped = s.startsWith("stripped_");
        if (stripped) s = s.substring("stripped_".length());
        for (String w : WOODS) if (s.startsWith(w + "_")) { s = s.substring(w.length() + 1); break; }
        if (stripped) s = "stripped_" + s;

        boolean skipColor = false;
        for (String n : NOT_COLOR) if (s.startsWith(n)) { skipColor = true; break; }
        if (!skipColor) for (String c : COLORS) if (s.startsWith(c + "_")) { s = s.substring(c.length() + 1); break; }

        for (String[] sh : SHAPES) if (s.endsWith(sh[0])) return sh[1];
        return s;
    }

    private static List<List<Item>> families;
    private static int builtKey = Integer.MIN_VALUE;
    private static int poolSize;

    private static synchronized List<List<Item>> families() {
        int key = Settings.poolKey();
        if (families == null || key != builtKey) {
            boolean first = families == null;
            java.util.List<Item> pool = new java.util.ArrayList<>();
            Set<String> off = Settings.effOffItems();
            boolean skipHard = Settings.effSkipHard();
            for (Item i : baseItems()) if (inPool(i, off, skipHard)) pool.add(i);
            if (pool.isEmpty()) pool.addAll(baseItems());   // всё выключено — берём полный список, чтобы игра не сломалась

            java.util.TreeMap<String, List<Item>> map = new java.util.TreeMap<>();
            if (Settings.effFamilyWeighted()) {
                for (Item i : pool) map.computeIfAbsent(family(idOf(i)), k -> new java.util.ArrayList<>()).add(i);
            } else {
                for (Item i : pool) map.put(idOf(i), new java.util.ArrayList<>(List.of(i)));   // каждый предмет — своё «семейство»
            }
            families = new java.util.ArrayList<>(map.values());
            poolSize = pool.size();
            builtKey = key;
            if (first) FriendRunClient.LOG.info("FRun: в пуле предметов {} шт., семейств {}", poolSize, families.size());
        }
        return families;
    }

    /** Current selectable item pool, respecting the host's Draft item settings. */
    public static List<Item> poolItems() {
        List<Item> out = new java.util.ArrayList<>();
        for (List<Item> family : families()) out.addAll(family);
        return out;
    }

    /** Сколько предметов сейчас в пуле (с учётом настроек). */
    public static int poolSize() {
        families();
        return poolSize;
    }

    /** Случайный предмет: сначала равновероятно семейство, потом предмет внутри него. */
    public static Item random(Random r) {
        List<List<Item>> f = families();
        List<Item> fam = f.get(r.nextInt(f.size()));
        return fam.get(r.nextInt(fam.size()));
    }

    public static Item pick(long seed) { return random(new Random(seed)); }

    /** k-й предмет раунда с данным seed (k=0 совпадает с pick(seed) — как в рулетке лобби). */
    public static Item itemFor(long seed, int k) {
        return k == 0 ? pick(seed) : pick(seed * 0x9E3779B97F4A7C15L + k);
    }

    /** Цепочка из n разных предметов. */
    public static List<Item> chain(long seed, int n) {
        List<Item> out = new java.util.ArrayList<>();
        for (int k = 0; k < n * 30 && out.size() < n; k++) {
            Item it = itemFor(seed, k);
            if (!out.contains(it)) out.add(it);
        }
        return out;
    }
}
