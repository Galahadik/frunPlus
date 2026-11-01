package dev.friendrun;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Серверная часть «Драфта+»: пассивные бонусы ролей к добыче/крафту и запреты модификаторов матча.
 * Работает только пока идёт матч Драфта+ (DraftPlus.matchRunning). Хук крафта — ResultSlotMixin.
 *
 * Предметы выдаются командами мира (как усложнения Chaos): так код не зависит от внутренних API дропа,
 * которые меняются между версиями. События приходят с потока сервера мира: в «мире хоста» это встроенный
 * сервер хоста (бонус получают все игроки, роли берутся из документа лобби), в раздельных мирах — сервер
 * каждого игрока.
 */
public final class DraftPlusBonus {
    private DraftPlusBonus() {}

    private static final Map<String, String> ORE_DROPS = Map.ofEntries(
        Map.entry("coal_ore", "coal"), Map.entry("iron_ore", "raw_iron"), Map.entry("copper_ore", "raw_copper"),
        Map.entry("gold_ore", "raw_gold"), Map.entry("redstone_ore", "redstone"), Map.entry("lapis_ore", "lapis_lazuli"),
        Map.entry("diamond_ore", "diamond"), Map.entry("emerald_ore", "emerald"), Map.entry("nether_gold_ore", "gold_nugget"),
        Map.entry("nether_quartz_ore", "quartz"), Map.entry("ancient_debris", "ancient_debris"));

    private static final Map<String, String> CROP_DROPS = Map.ofEntries(
        Map.entry("wheat", "wheat"), Map.entry("carrots", "carrot"), Map.entry("potatoes", "potato"),
        Map.entry("beetroots", "beetroot"), Map.entry("melon", "melon_slice"), Map.entry("pumpkin", "pumpkin"),
        Map.entry("sugar_cane", "sugar_cane"));

    private static final Map<String, String> MOB_DROPS = Map.ofEntries(
        Map.entry("zombie", "rotten_flesh"), Map.entry("skeleton", "bone"), Map.entry("spider", "string"),
        Map.entry("cave_spider", "string"), Map.entry("creeper", "gunpowder"), Map.entry("enderman", "ender_pearl"),
        Map.entry("blaze", "blaze_rod"), Map.entry("ghast", "ghast_tear"), Map.entry("slime", "slime_ball"),
        Map.entry("magma_cube", "magma_cream"), Map.entry("phantom", "phantom_membrane"), Map.entry("cow", "leather"),
        Map.entry("pig", "porkchop"), Map.entry("chicken", "feather"), Map.entry("sheep", "mutton"));

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> allowBreak(player, state));
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> afterBreak(player, state));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> afterDeath(entity, source));
    }

    // ---------- вспомогательное ----------
    private static String nameOf(Player p) { return p.getName().getString(); }

    private static String blockId(BlockState state) {
        try { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(); } catch (RuntimeException e) { return ""; }
    }

    private static boolean isOre(String id) { return id.endsWith("_ore") || id.equals("ancient_debris"); }

    private static boolean isCrop(String id) { return CROP_DROPS.containsKey(id) || id.equals("nether_wart") || id.equals("sweet_berry_bush"); }

    private static boolean isLog(String id) { return !id.startsWith("stripped_") && (id.endsWith("_log") || id.endsWith("_stem") || id.endsWith("_wood")); }

    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_NOTE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Короткая подсказка над хотбаром (не чаще раза в 2 секунды на игрока). */
    private static void note(String name, String text) {
        long now = System.currentTimeMillis();
        Long last = LAST_NOTE.get(name);
        if (last != null && now - last < 2000) return;
        LAST_NOTE.put(name, now);
        Chaos.run("title @a[name=" + name + "] actionbar \"" + text + "\"");
    }

    private static void give(String name, String itemId, int count, String label) {
        Chaos.run("execute as @a[name=" + name + "] at @s run summon item ~ ~ ~ {Item:{id:\"minecraft:" + itemId + "\",count:" + count + "}}");
        note(name, label);
    }

    private static boolean roll(double chance) { return ThreadLocalRandom.current().nextDouble() < chance; }

    private static boolean has(String name, String roleId) {
        DraftPlus.Role r = DraftPlus.roleOf(name);
        return r != null && r.id().equals(roleId);
    }

    // ---------- запреты модификаторов ----------
    private static boolean allowBreak(Player player, BlockState state) {
        if (!DraftPlus.matchRunning()) return true;
        String id = blockId(state);
        if (DraftPlus.modActive("no_ores") && isOre(id)) { note(nameOf(player), "Событие матча: руды ломать нельзя"); return false; }
        if (DraftPlus.modActive("no_crops") && isCrop(id)) { note(nameOf(player), "Событие матча: урожай ломать нельзя"); return false; }
        return true;
    }

    // ---------- добыча блоков ----------
    private static void afterBreak(Player player, BlockState state) {
        if (!DraftPlus.matchRunning()) return;
        String id = blockId(state), name = nameOf(player), drop, need;
        double chance;
        if (ORE_DROPS.containsKey(id)) { drop = ORE_DROPS.get(id); need = "miner"; chance = 0.35; }
        else if (CROP_DROPS.containsKey(id)) {
            // Только созревший урожай: иначе можно бесконечно сажать и ломать росток.
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) return;
            drop = CROP_DROPS.get(id); need = "farmer"; chance = 0.5;
        }
        else if (isLog(id)) { drop = id; need = "lumberjack"; chance = 0.4; }
        else return;
        boolean generous = DraftPlus.modActive("generous");
        double p = has(name, need) ? chance : generous ? 0.25 : 0;
        if (p > 0 && roll(p)) give(name, drop, 1, has(name, need) ? "Бонус роли: +1" : "Щедрая земля: +1");
    }

    // ---------- добыча с мобов (охотник) ----------
    private static void afterDeath(LivingEntity entity, DamageSource source) {
        if (!DraftPlus.matchRunning() || entity instanceof Player) return;
        if (!(source.getEntity() instanceof Player killer)) return;
        String name = nameOf(killer);
        if (!has(name, "hunter")) return;
        String type;
        try { type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(); } catch (RuntimeException e) { return; }
        String drop = MOB_DROPS.get(type);
        if (drop != null && roll(type.equals("enderman") ? 0.15 : 0.35)) give(name, drop, 1, "Бонус охотника: +1");
    }

    // ---------- крафт (вызывается из ResultSlotMixin) ----------
    public static void onCraft(Player player, ItemStack stack) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer) || stack == null || stack.isEmpty()) return;
        if (!DraftPlus.matchRunning()) return;
        String name = nameOf(player);
        boolean doubled = DraftPlus.modActive("double_craft");
        boolean crafter = has(name, "crafter") && roll(0.25);
        if (!doubled && !crafter) return;
        String itemId = ItemPicker.idOf(stack.getItem());
        Chaos.run("give @a[name=" + name + "] minecraft:" + itemId + " " + Math.max(1, stack.getCount()));
        note(name, doubled ? "Двойной крафт: +копия" : "Бонус ремесленника: +копия");
    }
}
