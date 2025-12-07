/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.seedmapper;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Holds the static option lists needed to build SeedMapper commands.
 *
 * <p>
 * At runtime we try to mirror SeedMapper's own argument tables through
 * reflection so that dropdowns stay in sync even if the mod updates. When the
 * vendor data is unavailable, we fall back to curated lists that cover vanilla
 * content so the helper remains usable.
 */
public final class SeedMapperData
{
	private static final List<String> DEFAULT_HIGHLIGHT_BLOCKS =
		List.of("ancient_debris", "andesite", "basalt", "blackstone", "clay",
			"coal_ore", "copper_ore", "deepslate", "diamond_ore", "diorite",
			"dirt", "emerald_ore", "gold_ore", "granite", "gravel", "iron_ore",
			"lapis_ore", "magma_block", "netherrack", "nether_gold_ore",
			"nether_quartz_ore", "raw_copper_block", "raw_iron_block",
			"redstone_ore", "soul_sand", "stone", "tuff");
	
	private static final List<String> DEFAULT_CANYON_CARVERS =
		List.of("canyon", "underwater_canyon");
	
	private static final List<String> DEFAULT_CAVE_CARVERS =
		List.of("cave", "cave_extra_underground", "ocean_cave",
			"underwater_cave", "nether_cave");
	
	private static final List<String> DEFAULT_BIOMES = List.of("plains",
		"sunflower_plains", "desert", "savanna", "savanna_plateau", "forest",
		"flower_forest", "birch_forest", "dark_forest",
		"old_growth_spruce_taiga", "taiga", "snowy_taiga", "snowy_plains",
		"mushroom_fields", "swamp", "mangrove_swamp", "jungle", "bamboo_jungle",
		"badlands", "eroded_badlands", "cherry_grove", "windswept_forest",
		"windswept_hills", "frozen_peaks", "stony_peaks", "dripstone_caves",
		"lush_caves", "deep_dark", "nether_wastes", "crimson_forest",
		"warped_forest", "basalt_deltas", "soul_sand_valley", "the_end",
		"small_end_islands", "end_midlands", "end_highlands");
	
	private static final List<String> DEFAULT_ENCHANTMENTS = List.of(
		"protection", "fire_protection", "feather_falling", "blast_protection",
		"projectile_protection", "respiration", "aqua_affinity", "thorns",
		"depth_strider", "frost_walker", "soul_speed", "swift_sneak",
		"sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect",
		"looting", "sweeping_edge", "efficiency", "silk_touch", "unbreaking",
		"fortune", "power", "punch", "flame", "infinity", "luck_of_the_sea",
		"lure", "loyalty", "impaling", "riptide", "channeling", "multishot",
		"quick_charge", "piercing", "mending", "binding_curse",
		"vanishing_curse");
	
	private static final List<String> DEFAULT_STRUCTURES =
		List.of("feature", "ancient_city", "bastion_remnant", "buried_treasure",
			"canyon", "desert_pyramid", "desert_well", "end_city",
			"end_city_ship", "end_gateway", "end_island", "fortress", "geode",
			"igloo", "jungle_pyramid", "mansion", "mineshaft", "monument",
			"ocean_ruin", "pillager_outpost", "ruined_portal",
			"ruined_portal_nether", "shipwreck", "stronghold", "swamp_hut",
			"trail_ruins", "trial_chambers", "village");
	
	private static final Map<String, List<String>> DEFAULT_STRUCTURE_PIECES =
		createDefaultStructurePieces();
	
	private static final Map<String, List<String>> DEFAULT_STRUCTURE_VARIANTS =
		createDefaultStructureVariants();
	
	private static final List<String> DEFAULT_GENERIC_VARIANTS =
		List.of("biome", "rotation", "mirrored");
	
	private static final List<String> DEFAULT_DENSITY_FUNCTIONS = List.of(
		"overworld.base_3d_noise",
		"overworld.caves.spaghetti_roughness_function",
		"overworld.caves.spaghetti_2d_thickness_modulator",
		"overworld.caves.spaghetti_2d", "spaghetti_3d", "cave_entrance",
		"overworld.caves.entrances", "cave_layer", "overworld.sloped_cheese",
		"cave_cheese", "overworld.caves.pillars", "overworld.caves.noodle",
		"underground", "final_density", "preliminary_surface_level");
	
	private static final List<String> DEFAULT_DIMENSIONS =
		List.of("overworld", "the_nether", "the_end");
	
	private static final List<String> DEFAULT_WORLD_PRESETS = List.of(
		"amplified", "superflat", "large_biomes", "single_biome", "default");
	
	private static final List<String> DEFAULT_VERSION_SHORTCUTS = List.of(
		"auto", "latest", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.2",
		"1.20.1", "1.19.4", "1.19.2", "1.18.2", "1.17.1", "1.16.5", "1.12.2");
	
	private static final List<String> DEFAULT_ORE_VEINS =
		List.of("copper", "iron");
	
	private final List<String> highlightBlocks;
	private final List<String> canyonCarvers;
	private final List<String> caveCarvers;
	private final List<String> biomeKeys;
	private final List<String> structureKeys;
	private final Map<String, List<String>> structurePieces;
	private final Map<String, List<String>> structureVariants;
	private final List<String> variantKeyUnion;
	private final List<String> lootItems;
	private final List<String> lootEnchantments;
	private final List<String> densityFunctions;
	private final List<String> dimensionShortcuts;
	private final List<String> versionShortcuts;
	private final List<String> worldPresets;
	
	private SeedMapperData(List<String> highlightBlocks,
		List<String> canyonCarvers, List<String> caveCarvers,
		List<String> biomeKeys, List<String> structureKeys,
		Map<String, List<String>> structurePieces,
		Map<String, List<String>> structureVariants,
		List<String> variantKeyUnion, List<String> lootItems,
		List<String> lootEnchantments, List<String> densityFunctions,
		List<String> dimensionShortcuts, List<String> versionShortcuts,
		List<String> worldPresets)
	{
		this.highlightBlocks = highlightBlocks;
		this.canyonCarvers = canyonCarvers;
		this.caveCarvers = caveCarvers;
		this.biomeKeys = biomeKeys;
		this.structureKeys = structureKeys;
		this.structurePieces = structurePieces;
		this.structureVariants = structureVariants;
		this.variantKeyUnion = variantKeyUnion;
		this.lootItems = lootItems;
		this.lootEnchantments = lootEnchantments;
		this.densityFunctions = densityFunctions;
		this.dimensionShortcuts = dimensionShortcuts;
		this.versionShortcuts = versionShortcuts;
		this.worldPresets = worldPresets;
	}
	
	public List<String> getHighlightBlocks()
	{
		return highlightBlocks;
	}
	
	public List<String> getCanyonCarvers()
	{
		return canyonCarvers;
	}
	
	public List<String> getCaveCarvers()
	{
		return caveCarvers;
	}
	
	public List<String> getBiomeKeys()
	{
		return biomeKeys;
	}
	
	public List<String> getStructureKeys()
	{
		return structureKeys;
	}
	
	public List<String> getStructurePieces(String structure)
	{
		return structurePieces.getOrDefault(structure, Collections.emptyList());
	}
	
	public boolean supportsPieceFilters(String structure)
	{
		return structurePieces.containsKey(structure);
	}
	
	public List<String> getStructureVariants(String structure)
	{
		return structureVariants.getOrDefault(structure,
			Collections.emptyList());
	}
	
	public boolean supportsVariantFilters(String structure)
	{
		return structureVariants.containsKey(structure);
	}
	
	public List<String> getVariantKeyUnion()
	{
		return variantKeyUnion;
	}
	
	public List<String> getGenericVariantKeys()
	{
		return DEFAULT_GENERIC_VARIANTS;
	}
	
	public List<String> getLootItems()
	{
		return lootItems;
	}
	
	public List<String> getLootEnchantments()
	{
		return lootEnchantments;
	}
	
	public List<String> getDensityFunctions()
	{
		return densityFunctions;
	}
	
	public List<String> getDimensionShortcuts()
	{
		return dimensionShortcuts;
	}
	
	public List<String> getVersionShortcuts()
	{
		return versionShortcuts;
	}
	
	public List<String> getWorldPresets()
	{
		return worldPresets;
	}
	
	public List<String> getOreVeinTargets()
	{
		return DEFAULT_ORE_VEINS;
	}
	
	public static SeedMapperData createFallback()
	{
		List<String> biomes = fallbackBiomes();
		List<String> items = registryKeys(BuiltInRegistries.ITEM);
		List<String> enchants = fallbackEnchantments();
		
		return new SeedMapperData(copySorted(DEFAULT_HIGHLIGHT_BLOCKS),
			copySorted(DEFAULT_CANYON_CARVERS),
			copySorted(DEFAULT_CAVE_CARVERS), biomes,
			copySorted(DEFAULT_STRUCTURES), copyMap(DEFAULT_STRUCTURE_PIECES),
			copyMap(DEFAULT_STRUCTURE_VARIANTS),
			buildVariantUnion(DEFAULT_STRUCTURE_VARIANTS),
			limitList(items, 1024), limitList(enchants, 256),
			copySorted(DEFAULT_DENSITY_FUNCTIONS),
			copySorted(DEFAULT_DIMENSIONS),
			copySorted(DEFAULT_VERSION_SHORTCUTS),
			copySorted(DEFAULT_WORLD_PRESETS));
	}
	
	public static SeedMapperData tryLoadFromVendor()
	{
		List<String> highlightBlocks = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.BlockArgument", "BLOCKS"),
			DEFAULT_HIGHLIGHT_BLOCKS);
		List<String> canyonCarvers = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.CanyonCarverArgument",
			"CARVERS"), DEFAULT_CANYON_CARVERS);
		List<String> caveCarvers = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.CaveCarverArgument",
			"CARVERS"), DEFAULT_CAVE_CARVERS);
		List<String> biomeKeys = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.BiomeArgument", "BIOMES"),
			fallbackBiomes());
		List<String> structureKeys = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.StructurePredicateArgument",
			"STRUCTURES"), DEFAULT_STRUCTURES);
		Map<String, List<String>> structurePieces = tryRead(supplyMap(
			"dev.xpple.seedmapper.command.arguments.StructurePredicateArgument",
			"STRUCTURE_PIECES"), DEFAULT_STRUCTURE_PIECES);
		Map<String, List<String>> structureVariants = tryRead(supplyMap(
			"dev.xpple.seedmapper.command.arguments.StructurePredicateArgument",
			"STRUCTURE_VARIANTS"), DEFAULT_STRUCTURE_VARIANTS);
		List<String> lootItems = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.ItemAndEnchantmentsPredicateArgument",
			"ITEMS"), registryKeys(BuiltInRegistries.ITEM));
		List<String> lootEnchantments = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.ItemAndEnchantmentsPredicateArgument",
			"ENCHANTMENTS"), fallbackEnchantments());
		List<String> densityFunctions = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.DensityFunctionArgument",
			"DENSITY_FUNCTIONS"), DEFAULT_DENSITY_FUNCTIONS);
		List<String> dimensions = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.DimensionArgument",
			"DIMENSIONS"), DEFAULT_DIMENSIONS);
		List<String> versionShortcuts = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.VersionArgument",
			"VERSIONS"), DEFAULT_VERSION_SHORTCUTS);
		List<String> worldPresets = tryRead(supplyValueList(
			"dev.xpple.seedmapper.command.arguments.PresetArgument", "PRESETS"),
			DEFAULT_WORLD_PRESETS);
		
		return new SeedMapperData(copySorted(highlightBlocks),
			copySorted(canyonCarvers), copySorted(caveCarvers),
			copySorted(biomeKeys), copySorted(structureKeys),
			copyMap(structurePieces), copyMap(structureVariants),
			buildVariantUnion(structureVariants), limitList(lootItems, 2048),
			limitList(lootEnchantments, 512), copySorted(densityFunctions),
			copySorted(dimensions), copySorted(versionShortcuts),
			copySorted(worldPresets));
	}
	
	private static List<String> fallbackBiomes()
	{
		List<String> reflected =
			fallbackResourceKeys("net.minecraft.world.level.biome.Biomes");
		if(reflected.isEmpty())
			return copySorted(DEFAULT_BIOMES);
		return reflected;
	}
	
	private static List<String> fallbackEnchantments()
	{
		List<String> reflected = fallbackResourceKeys(
			"net.minecraft.world.item.enchantment.Enchantments");
		if(reflected.isEmpty())
			return copySorted(DEFAULT_ENCHANTMENTS);
		return reflected;
	}
	
	private static List<String> fallbackResourceKeys(String className)
	{
		try
		{
			Class<?> clazz = Class.forName(className);
			LinkedHashSet<String> keys = new LinkedHashSet<>();
			for(Field field : clazz.getFields())
			{
				if(!Modifier.isStatic(field.getModifiers()))
					continue;
				if(!ResourceKey.class.isAssignableFrom(field.getType()))
					continue;
				Object value = field.get(null);
				if(!(value instanceof ResourceKey<?> key))
					continue;
				keys.add(key.location().toString());
			}
			ArrayList<String> list = new ArrayList<>(keys);
			Collections.sort(list);
			return List.copyOf(list);
			
		}catch(Throwable e)
		{
			return List.of();
		}
	}
	
	private static Supplier<List<String>> supplyValueList(String className,
		String fieldName)
	{
		return () -> {
			try
			{
				return readValueList(className, fieldName);
			}catch(ReflectiveOperationException e)
			{
				throw new RuntimeException(e);
			}
		};
	}
	
	private static Supplier<Map<String, List<String>>> supplyMap(
		String className, String fieldName)
	{
		return () -> {
			try
			{
				return readMapOfLists(className, fieldName);
			}catch(ReflectiveOperationException e)
			{
				throw new RuntimeException(e);
			}
		};
	}
	
	private static List<String> buildVariantUnion(
		Map<String, List<String>> variants)
	{
		LinkedHashSet<String> all = new LinkedHashSet<>();
		for(List<String> value : variants.values())
			all.addAll(value);
		all.addAll(DEFAULT_GENERIC_VARIANTS);
		return List.copyOf(all);
	}
	
	private static List<String> readValueList(String className, String field)
		throws ReflectiveOperationException
	{
		Class<?> clazz = Class.forName(className);
		Field f = clazz.getDeclaredField(field);
		f.setAccessible(true);
		Object value = f.get(null);
		return extractStrings(value);
	}
	
	private static Map<String, List<String>> readMapOfLists(String className,
		String field) throws ReflectiveOperationException
	{
		Class<?> clazz = Class.forName(className);
		Field f = clazz.getDeclaredField(field);
		f.setAccessible(true);
		Object value = f.get(null);
		if(!(value instanceof Map<?, ?> map))
			return Collections.emptyMap();
		
		LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
		for(Map.Entry<?, ?> entry : map.entrySet())
		{
			String key = stringify(entry.getKey());
			if(key.isEmpty())
				continue;
			List<String> list = extractStrings(entry.getValue());
			if(list.isEmpty())
				continue;
			copy.put(key, copySorted(list));
		}
		
		return copy;
	}
	
	private static List<String> extractStrings(Object value)
	{
		if(value == null)
			return Collections.emptyList();
		
		Collection<?> raw;
		if(value instanceof Map<?, ?> map)
			raw = map.keySet();
		else if(value instanceof Collection<?> collection)
			raw = collection;
		else if(value.getClass().isArray())
		{
			int len = Array.getLength(value);
			List<Object> tmp = new ArrayList<>(len);
			for(int i = 0; i < len; i++)
				tmp.add(Array.get(value, i));
			raw = tmp;
		}else
			raw = List.of(value);
		
		return raw.stream().map(SeedMapperData::stringify).map(String::trim)
			.filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT))
			.distinct().sorted().collect(Collectors.toList());
	}
	
	private static String stringify(Object value)
	{
		if(value == null)
			return "";
		if(value instanceof ResourceLocation rl)
			return rl.toString();
		return value.toString();
	}
	
	private static <T> T tryRead(Supplier<T> supplier, T fallback)
	{
		try
		{
			T value = supplier.get();
			if(value instanceof Collection<?> collection
				&& collection.isEmpty())
				return fallback;
			
			if(value instanceof Map<?, ?> map && map.isEmpty())
				return fallback;
			
			return Objects.requireNonNullElse(value, fallback);
			
		}catch(Throwable e)
		{
			return fallback;
		}
	}
	
	private static List<String> registryKeys(
		Iterable<ResourceLocation> registry)
	{
		LinkedHashSet<String> keys = new LinkedHashSet<>();
		for(ResourceLocation id : registry)
			keys.add(id.toString());
		ArrayList<String> list = new ArrayList<>(keys);
		Collections.sort(list);
		return List.copyOf(list);
	}
	
	private static List<String> registryKeys(
		net.minecraft.core.Registry<?> registry)
	{
		return registryKeys(registry.keySet());
	}
	
	private static List<String> copySorted(List<String> values)
	{
		if(values == null || values.isEmpty())
			return List.of();
		ArrayList<String> copy = new ArrayList<>(values);
		Collections.sort(copy);
		return List.copyOf(copy);
	}
	
	private static Map<String, List<String>> copyMap(
		Map<String, List<String>> source)
	{
		LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
		source.forEach((key, value) -> copy.put(key, copySorted(value)));
		return Collections.unmodifiableMap(copy);
	}
	
	private static List<String> limitList(List<String> list, int maxSize)
	{
		if(list.size() <= maxSize)
			return copySorted(list);
		return copySorted(list.subList(0, maxSize));
	}
	
	private static Map<String, List<String>> createDefaultStructurePieces()
	{
		LinkedHashMap<String, List<String>> pieces = new LinkedHashMap<>();
		pieces.put("fortress",
			List.of("bridge_crossing", "bridge_end", "bridge_small",
				"bridge_spawner", "corridor_exit", "corridor_nether_wart",
				"corridor_straight", "stairs_bottom", "stairs_top"));
		pieces.put("end_city",
			List.of("base_floor", "bridge", "bridge_end", "end_ship",
				"fat_tower", "second_floor", "small_room", "tower_top"));
		return pieces;
	}
	
	private static Map<String, List<String>> createDefaultStructureVariants()
	{
		LinkedHashMap<String, List<String>> variants = new LinkedHashMap<>();
		variants.put("village",
			List.of("plains", "desert", "savanna", "snowy", "taiga"));
		variants.put("bastion_remnant",
			List.of("bridge", "housing", "treasure", "hoglin_stable"));
		variants.put("ruined_portal",
			List.of("standard", "desert", "jungle", "swamp", "mountain",
				"ocean", "nether", "giant", "underground", "air_pocket"));
		variants.put("igloo", List.of("basement"));
		variants.put("geode", List.of("small", "large", "mega"));
		variants.put("trial_chambers", List.of("atrium", "barracks", "corridor",
			"crossing", "reward_vault", "trial_spawner"));
		return variants;
	}
}
