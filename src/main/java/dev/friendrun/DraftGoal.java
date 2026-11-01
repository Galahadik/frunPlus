package dev.friendrun;

import java.util.Locale;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Typed goal stored in the shared Draft board. IDs are stable Firebase-safe strings. */
public record DraftGoal(Type type, String id) {
    public enum Type { ITEM, QUEST, ADVANCEMENT }

    public static DraftGoal parse(String raw) {
        if (raw == null) return null;
        int split = raw.indexOf(':');
        if (split <= 0 || split == raw.length() - 1) return null;
        try {
            Type type = Type.valueOf(raw.substring(0, split).toUpperCase(Locale.ROOT));
            String id = raw.substring(split + 1);
            if (type == Type.ITEM && ItemPicker.byId(id) == null) return null;
            if (type == Type.QUEST && !Quest.isKnown(id)) return null;
            if (type == Type.ADVANCEMENT && !Advancement.isKnown(id)) return null;
            return new DraftGoal(type, id);
        } catch (IllegalArgumentException ignored) { return null; }
    }

    public String encode() { return type.name().toLowerCase(Locale.ROOT) + ":" + id; }

    public String displayName() {
        return switch (type) {
            case ITEM -> {
                Item item = ItemPicker.byId(id);
                yield item == null ? id : ItemInfo.name(item);
            }
            case QUEST -> Quest.name(id);
            case ADVANCEMENT -> Advancement.name(id);
        };
    }

    public Item icon() {
        return switch (type) {
            case ITEM -> ItemPicker.byId(id);
            case QUEST -> Items.COMPASS;
            case ADVANCEMENT -> Items.KNOWLEDGE_BOOK;
        };
    }

    /** Numeric progress snapshot used to detect progress made during this round. */
    public int progressValue(net.minecraft.client.Minecraft mc) {
        if (mc == null || mc.player == null) return 0;
        return switch (type) {
            case ITEM -> {
                Item item = ItemPicker.byId(id);
                yield item == null ? 0 : Inv.count(mc.player, item);
            }
            case QUEST -> {
                if (id.equals("enter_nether")) yield mc.player.level().dimension() == net.minecraft.world.level.Level.NETHER ? 1 : 0;
                if (id.equals("enter_end")) yield mc.player.level().dimension() == net.minecraft.world.level.Level.END ? 1 : 0;
                String itemId = Quest.itemId(id);
                Item item = itemId == null ? null : ItemPicker.byId(itemId);
                yield item == null ? 0 : Inv.count(mc.player, item);
            }
            case ADVANCEMENT -> {
                var connection = mc.getConnection();
                if (connection == null) yield 0;
                var holder = connection.getAdvancements().get(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", id));
                var progress = holder == null ? null : connection.getAdvancements().progress().get(holder);
                yield progress != null && progress.isDone() ? 1 : 0;
            }
        };
    }

    private static String localized(String key, String fallback) {
        try {
            if (Language.getInstance().has(key)) return Component.translatable(key).getString();
        } catch (RuntimeException ignored) {}
        return fallback;
    }

    /** Quest goals: dimension travel plus 48 distinct collectible objectives (50 total). */
    public static final class Quest {
        private static final String[] ITEM_IDS = {
            "oak_log", "cobblestone", "coal", "iron_ingot", "gold_ingot", "diamond", "emerald",
            "redstone", "lapis_lazuli", "obsidian", "blaze_rod", "ender_pearl", "string", "bone",
            "gunpowder", "leather", "wheat", "bread", "cooked_beef", "bow", "shield", "bucket",
            "water_bucket", "flint_and_steel", "torch", "furnace", "crafting_table", "compass", "clock",
            "book", "paper", "slime_ball", "ghast_tear", "magma_cream", "prismarine_shard",
            "nautilus_shell", "turtle_scute", "phantom_membrane", "honey_bottle", "amethyst_shard", "golden_apple",
            "copper_ingot", "quartz", "nether_brick", "soul_sand", "glowstone_dust", "spider_eye", "carrot"
        };
        private static final String[] IDS;
        static {
            IDS = new String[ITEM_IDS.length + 2];
            IDS[0] = "enter_nether";
            IDS[1] = "enter_end";
            for (int i = 0; i < ITEM_IDS.length; i++) IDS[i + 2] = "collect_" + ITEM_IDS[i];
        }
        public static String itemId(String id) {
            if (id == null || !id.startsWith("collect_")) return null;
            String itemId = id.substring("collect_".length());
            for (String known : ITEM_IDS) if (known.equals(itemId)) return itemId;
            return null;
        }
        public static boolean isKnown(String id) { for (String value : IDS) if (value.equals(id)) return true; return false; }
        public static String name(String id) {
            if (id.equals("enter_nether")) return localized("friendrun.quest.enter_nether", "Попасть в Незер");
            if (id.equals("enter_end")) return localized("friendrun.quest.enter_end", "Попасть в Энд");
            String itemId = itemId(id);
            Item item = itemId == null ? null : ItemPicker.byId(itemId);
            return item == null ? id : localized("friendrun.quest.collect", "Получить: ") + ItemInfo.name(item);
        }
        public static String description(String id) {
            if (id.equals("enter_nether") || id.equals("enter_end"))
                return localized("friendrun.quest." + id + ".description", "");
            String itemId = itemId(id);
            Item item = itemId == null ? null : ItemPicker.byId(itemId);
            return item == null ? "" : localized("friendrun.quest.collect.description", "Получите этот предмет во время матча.");
        }
        public static String[] ids() { return IDS.clone(); }
    }

    /** Vanilla advancement objectives with stable resource IDs. */
    public static final class Advancement {
        private static final String[] IDS = {
            "story/root", "story/mine_stone", "story/upgrade_tools", "story/smelt_iron",
            "story/obtain_armor", "story/lava_bucket", "story/iron_tools", "story/deflect_arrow",
            "story/form_obsidian", "story/mine_diamond", "story/enter_the_nether", "story/shiny_gear",
            "story/enter_the_end", "story/cure_zombie_villager", "story/follow_ender_eye", "story/enchant_item",
            "nether/root", "nether/return_to_sender", "nether/find_bastion", "nether/obtain_ancient_debris",
            "nether/uneasy_alliance", "nether/fast_travel", "nether/netherite_armor", "nether/ride_strider",
            "nether/loot_bastion", "nether/charge_respawn_anchor", "nether/explore_nether", "nether/all_potions",
            "nether/all_effects", "nether/brew_potion", "nether/create_beacon", "nether/create_full_beacon",
            "nether/find_fortress", "nether/get_wither_skull", "nether/obtain_blaze_rod", "nether/summon_wither",
            "end/root", "end/kill_dragon", "end/dragon_egg", "end/respawn_dragon",
            "end/dragon_breath", "end/enter_end_gateway", "end/levitate", "end/elytra",
            "end/find_end_city", "adventure/root", "adventure/kill_a_mob", "adventure/trade",
            "adventure/ol_betsy", "adventure/voluntary_exile", "adventure/hero_of_the_village", "adventure/totem_of_undying",
            "adventure/arbalistic", "adventure/sniper_duel", "adventure/very_very_frightening", "adventure/two_birds_one_arrow",
            "adventure/whos_the_pillager_now", "adventure/kill_all_mobs", "adventure/postmortal", "adventure/adventuring_time",
            "adventure/sleep_in_bed", "adventure/lightning_rod", "adventure/spyglass_at_parrot", "adventure/avoid_vibration",
            "adventure/throw_trident", "adventure/trim_with_any_armor_pattern", "adventure/read_power_of_chiseled_bookshelf", "adventure/brush_armadillo",
            "adventure/walk_on_powder_snow_with_leather_boots", "adventure/summon_iron_golem", "adventure/shoot_arrow", "adventure/kill_mob_near_sculk_catalyst",
            "husbandry/root", "husbandry/breed_an_animal", "husbandry/tame_an_animal", "husbandry/balanced_diet",
            "husbandry/plant_seed", "husbandry/fishy_business", "husbandry/tactical_fishing", "husbandry/complete_catalogue",
            "husbandry/bred_all_animals", "husbandry/froglights", "husbandry/two_by_two", "husbandry/a_seedy_place",
            "husbandry/obtain_sniffer_egg", "husbandry/plant_any_sniffer_seed", "husbandry/wax_on", "husbandry/wax_off",
            "husbandry/silk_touch_nest", "husbandry/axolotl_in_a_bucket", "husbandry/ride_a_boat_with_a_goat", "husbandry/smells_interesting",
            "husbandry/little_sniffs", "husbandry/planting_the_past"
        };
        public static boolean isKnown(String id) { for (String value : IDS) if (value.equals(id)) return true; return false; }
        public static String name(String id) {
            String key = "advancement.minecraft." + id.replace('/', '.') + ".title";
            String fallbackPath = id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
            String fallback = "Достижение: " + Character.toUpperCase(fallbackPath.charAt(0)) + fallbackPath.substring(1);
            return localized(key, fallback);
        }
        public static String[] ids() { return IDS.clone(); }
    }

    public static String draftTypeName(int type) {
        return switch (type) {
            case 0 -> localized("friendrun.draft.type.items", "Предметы (классика)");
            case 1 -> localized("friendrun.draft.type.quests", "Задания");
            case 2 -> localized("friendrun.draft.type.advancements", "Достижения");
            default -> localized("friendrun.draft.type.mixed", "Смешанный");
        };
    }
}
