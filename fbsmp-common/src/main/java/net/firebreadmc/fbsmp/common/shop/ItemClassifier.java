package net.firebreadmc.fbsmp.common.shop;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure, name-based classification of a namespaced item id. It never depends on the
 * server's {@code Material} enum, so it is fully unit-testable without a server.
 *
 * <p>Rules are intentionally explicit and conservative. Restricted items (creative,
 * technical, one-of-a-kind or griefing-prone) win over every other rule so an item
 * can never be accidentally buyable.
 */
public final class ItemClassifier {

    private static final Set<String> RESTRICTED_EXACT = Set.of(
            "air", "cave_air", "void_air", "bedrock", "barrier", "light", "structure_void",
            "structure_block", "jigsaw", "debug_stick", "knowledge_book",
            "command_block", "chain_command_block", "repeating_command_block",
            "command_block_minecart", "spawner", "trial_spawner", "vault", "ominous_vault",
            "end_portal_frame", "reinforced_deepslate", "petrified_oak_slab", "budding_amethyst",
            "chorus_plant", "farmland", "frogspawn", "dragon_egg",
            "tnt", "tnt_minecart", "end_crystal", "lava_bucket", "respawn_anchor",
            "written_book", "filled_map", "player_head", "experience_bottle", "enchanted_book");

    private static final Set<String> RESTRICTED_SUFFIX = Set.of(
            "_spawn_egg", "_head", "_skull", "_banner_pattern");

    private static final Set<String> RESTRICTED_PREFIX = Set.of("infested_");

    /** High-value items that are allowed but must be priced explicitly, never generated. */
    private static final Set<String> REVIEW_ONLY = Set.of(
            "elytra", "enchanted_golden_apple", "nether_star", "beacon", "heavy_core", "mace",
            "trident", "netherite_ingot", "netherite_scrap", "ancient_debris",
            "netherite_block", "totem_of_undying", "shulker_shell");

    private static final Set<String> FOOD_EXACT = Set.of(
            "bread", "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton",
            "cooked_rabbit", "cooked_cod", "cooked_salmon", "baked_potato", "apple",
            "golden_apple", "carrot", "potato", "beetroot", "beetroot_soup", "mushroom_stew",
            "rabbit_stew", "suspicious_stew", "melon_slice", "sweet_berries", "glow_berries",
            "cookie", "cake", "pumpkin_pie", "dried_kelp", "honey_bottle", "milk_bucket");

    private static final Set<String> FOOD_SUFFIX = Set.of(
            "_stew", "_soup", "_pie", "_cookie", "_cake", "_berries");

    private static final Set<String> FARMING_EXACT = Set.of(
            "wheat", "wheat_seeds", "carrot", "potato", "beetroot", "beetroot_seeds",
            "melon", "melon_seeds", "pumpkin", "pumpkin_seeds", "sugar_cane", "bamboo",
            "cocoa_beans", "nether_wart", "sweet_berries", "glow_berries", "bone_meal",
            "kelp", "dried_kelp", "hay_block", "egg", "feather", "leather", "honeycomb",
            "honey_bottle", "milk_bucket", "brown_mushroom", "red_mushroom",
            "brown_mushroom_block", "red_mushroom_block", "mushroom_stem");

    private static final Set<String> FARMING_SUFFIX = Set.of(
            "_seeds", "_sapling", "_spawn_egg", "leaves");

    private static final Set<String> MINING_EXACT = Set.of(
            "coal", "charcoal", "iron_ingot", "gold_ingot", "copper_ingot", "netherite_scrap",
            "diamond", "emerald", "lapis_lazuli", "redstone", "quartz", "amethyst_shard",
            "raw_iron", "raw_gold", "raw_copper", "flint", "gunpowder", "glowstone_dust",
            "blaze_powder", "magma_cream", "obsidian", "crying_obsidian");

    private static final Set<String> MINING_SUFFIX = Set.of(
            "_ore", "_log", "_stem", "_hyphae");

    private static final Set<String> BUILDING_SUFFIX = Set.of(
            "_stone", "_bricks", "_brick", "_concrete", "_concrete_powder", "_terracotta",
            "_glazed_terracotta", "_wool", "_carpet", "_glass", "_glass_pane", "_slab",
            "_stairs", "_wall", "_fence", "_fence_gate", "_door", "_trapdoor", "_sandstone",
            "_quartz_block", "_prismarine", "_purpur_block", "_planks", "_wood", "_stripped");

    private static final Set<String> DECORATION_EXACT = Set.of(
            "flower_pot", "lantern", "soul_lantern", "torch", "soul_torch", "painting",
            "item_frame", "glow_item_frame", "armor_stand", "candle", "book",
            "bookshelf", "barrel", "chest", "trapped_chest", "ender_chest", "shulker_box",
            "white_banner", "black_banner", "bell", "ladder", "scaffolding", "chain",
            "campfire", "soul_campfire", "bee_nest", "beehive", "composter", "cauldron");

    private static final Set<String> DECORATION_SUFFIX = Set.of(
            "_banner", "_candle", "_bed", "_sign", "_hanging_sign", "_flower", "_sapling",
            "coral", "coral_block", "coral_fan");

    private static final Set<String> UTILITY_EXACT = Set.of(
            "chest", "crafting_table", "furnace", "blast_furnace", "smoker", "anvil",
            "chipped_anvil", "damaged_anvil", "grindstone", "stonecutter", "loom", "cartography_table",
            "fletching_table", "smithing_table", "brewing_stand", "enchanting_table", "bookshelf",
            "hopper", "dropper", "dispenser", "observer", "piston", "sticky_piston", "lever",
            "redstone_torch", "repeater", "comparator", "tripwire_hook", "target", "daylight_detector",
            "rail", "powered_rail", "detector_rail", "activator_rail", "minecart", "chest_minecart",
            "hopper_minecart", "bucket", "water_bucket", "compass", "clock", "spyglass", "lead",
            "name_tag", "saddle", "shears", "flint_and_steel", "fishing_rod", "bowl", "paper", "book",
            "ladder", "scaffolding", "end_rod", "lightning_rod", "chain", "bell", "note_block",
            "jukebox", "beehive", "bee_nest", "composter", "cauldron", "respawn_anchor");

    private static final Set<String> MISC_EXACT = Set.of(
            "stick", "string", "bowl", "paper", "book", "writable_book", "map", "empty_map",
            "slime_ball", "ender_pearl", "ender_eye", "blaze_rod", "ghast_tear", "nautilus_shell",
            "heart_of_the_sea", "prismarine_crystals", "prismarine_shard", "clay_ball", "brick",
            "nether_brick", "snowball", "bone", "ink_sac", "glow_ink_sac", "dye", "feather");

    private static final Pattern FLOWER_LIKE = Pattern.compile(
            "^(dandelion|poppy|blue_orchid|allium|azure_bluet|red_tulip|orange_tulip|white_tulip|"
                    + "pink_tulip|oxeye_daisy|cornflower|lily_of_the_valley|sunflower|lilac|rose_bush|"
                    + "peony|wither_rose|torchflower|pitcher_plant|pink_petals|spore_blossom)$");

    private ItemClassifier() {}

    public static Classification classify(String itemId) {
        String id = normalize(itemId);
        if (id.isEmpty()) {
            return new Classification(ItemCategory.MISC, PriceTier.COMMON, false, false,
                    "unreadable item id");
        }
        if (isRestricted(id)) {
            return new Classification(ItemCategory.RESTRICTED, PriceTier.LEGENDARY, true, true,
                    restrictedReason(id));
        }
        if (REVIEW_ONLY.contains(id)) {
            return new Classification(categoryFor(id), PriceTier.LEGENDARY, false, true,
                    "high-value item; requires an explicit price");
        }
        if (isVariantSensitive(id)) {
            return new Classification(categoryFor(id), PriceTier.UNCOMMON, false, true,
                    "variant-sensitive item; requires an explicit price");
        }
        return new Classification(categoryFor(id), tierFor(id), false, false, "");
    }

    public static boolean isRestricted(String itemId) {
        String id = normalize(itemId);
        if (id.isEmpty() || RESTRICTED_EXACT.contains(id)) {
            return true;
        }
        for (String suffix : RESTRICTED_SUFFIX) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        for (String prefix : RESTRICTED_PREFIX) {
            if (id.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String restrictedReason(String id) {
        if (id.isEmpty() || id.endsWith("_air")) {
            return "not a real item";
        }
        if (id.endsWith("_spawn_egg") || id.equals("spawner") || id.equals("trial_spawner")
                || id.equals("vault") || id.equals("ominous_vault")) {
            return "breaks mob economy/progression";
        }
        if (id.contains("command_block") || id.equals("debug_stick") || id.equals("jigsaw")
                || id.equals("structure_block") || id.equals("structure_void") || id.equals("barrier")
                || id.equals("light") || id.equals("bedrock") || id.equals("knowledge_book")) {
            return "technical/creative-only or admin-only";
        }
        if (id.equals("dragon_egg")) {
            return "one-of-a-kind; economy break";
        }
        if (id.equals("tnt") || id.equals("tnt_minecart") || id.equals("end_crystal")
                || id.equals("lava_bucket") || id.equals("respawn_anchor")) {
            return "griefing risk";
        }
        if (id.endsWith("_head") || id.endsWith("_skull") || id.equals("player_head")) {
            return "custom/unverifiable identity";
        }
        return "not survival-obtainable or unstable";
    }

    private static boolean isVariantSensitive(String id) {
        return id.contains("potion") || id.contains("tipped_arrow") || id.contains("splash_potion")
                || id.contains("lingering_potion") || id.endsWith("_banner_pattern");
    }

    private static ItemCategory categoryFor(String id) {
        if (FOOD_EXACT.contains(id) || FOOD_SUFFIX.stream().anyMatch(id::endsWith)) {
            return ItemCategory.FOOD;
        }
        if (FARMING_EXACT.contains(id) || FARMING_SUFFIX.stream().anyMatch(id::endsWith)) {
            return ItemCategory.FARMING;
        }
        if (MINING_EXACT.contains(id) || MINING_SUFFIX.stream().anyMatch(id::endsWith)) {
            return ItemCategory.MINING;
        }
        if (DECORATION_EXACT.contains(id) || DECORATION_SUFFIX.stream().anyMatch(id::endsWith)
                || FLOWER_LIKE.matcher(id).matches()) {
            return ItemCategory.DECORATION;
        }
        if (UTILITY_EXACT.contains(id)) {
            return ItemCategory.UTILITY;
        }
        if (BUILDING_SUFFIX.stream().anyMatch(id::endsWith)) {
            return ItemCategory.BUILDING;
        }
        if (MISC_EXACT.contains(id)) {
            return ItemCategory.MISC;
        }
        return ItemCategory.MISC;
    }

    private static PriceTier tierFor(String id) {
        if (id.contains("netherite") || id.equals("elytra") || id.equals("beacon")) {
            return PriceTier.LEGENDARY;
        }
        if (id.equals("diamond") || id.equals("emerald") || id.equals("ancient_debris")
                || id.equals("enchanted_golden_apple") || id.equals("nether_star")) {
            return PriceTier.EPIC;
        }
        if (id.equals("gold_ingot") || id.equals("iron_ingot") || id.equals("lapis_lazuli")
                || id.equals("obsidian") || id.equals("anvil") || id.equals("ender_pearl")
                || id.equals("blaze_rod") || id.equals("glowstone_dust") || id.equals("quartz")) {
            return PriceTier.RARE;
        }
        if (id.equals("copper_ingot") || id.equals("redstone") || id.equals("coal")
                || id.equals("charcoal") || id.equals("glass") || id.equals("brick")) {
            return PriceTier.UNCOMMON;
        }
        return PriceTier.COMMON;
    }

    public static String normalize(String itemId) {
        if (itemId == null) {
            return "";
        }
        String id = itemId.trim().toLowerCase(java.util.Locale.ROOT);
        int colon = id.indexOf(':');
        if (colon >= 0) {
            id = id.substring(colon + 1);
        }
        return id;
    }

    public record Classification(ItemCategory category, PriceTier tier,
                                 boolean restricted, boolean requiresReview, String note) {}
}
