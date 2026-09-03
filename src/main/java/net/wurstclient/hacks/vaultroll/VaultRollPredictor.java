/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.vaultroll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Pure Java port of the 26.2 trial-chamber Vault loot sequences.
 *
 * <p>
 * The loot functions intentionally live here instead of calling the
 * client loot-table registry. A client registry does not own the server's
 * random-sequence state, while this class needs to consume exactly the same
 * Xoroshiro stream as the server.
 * </p>
 */
public final class VaultRollPredictor
{
	private static final List<EnchantmentInfo> ON_RANDOM_LOOT = List.of(
		enchantment("protection", 10, 4, 1, 11, 12, 11, Support.ARMOR, null,
			"armor"),
		enchantment("fire_protection", 5, 4, 10, 8, 18, 8, Support.ARMOR, null,
			"armor"),
		enchantment("feather_falling", 5, 4, 5, 6, 11, 6, Support.FOOT, null,
			null),
		enchantment("blast_protection", 2, 4, 5, 8, 13, 8, Support.ARMOR, null,
			"armor"),
		enchantment("projectile_protection", 5, 4, 3, 6, 9, 6, Support.ARMOR,
			null, "armor"),
		enchantment("respiration", 2, 3, 10, 10, 40, 10, Support.HEAD, null,
			null),
		enchantment("aqua_affinity", 2, 1, 1, 0, 41, 0, Support.HEAD, null,
			null),
		enchantment("thorns", 1, 3, 10, 20, 60, 20, Support.ARMOR,
			Support.CHEST, null),
		enchantment("depth_strider", 2, 3, 10, 10, 25, 10, Support.FOOT, null,
			"boots"),
		enchantment("sharpness", 10, 5, 1, 11, 21, 11, Support.SHARP_WEAPON,
			Support.MELEE, "damage"),
		enchantment("smite", 5, 5, 5, 8, 25, 8, Support.WEAPON, Support.MELEE,
			"damage"),
		enchantment("bane_of_arthropods", 5, 5, 5, 8, 25, 8, Support.WEAPON,
			Support.MELEE, "damage"),
		enchantment("knockback", 5, 2, 5, 20, 55, 20, Support.MELEE, null,
			null),
		enchantment("fire_aspect", 2, 2, 10, 20, 60, 20, Support.FIRE_ASPECT,
			Support.MELEE, null),
		enchantment("looting", 2, 3, 15, 9, 65, 9, Support.MELEE, null, null),
		enchantment("sweeping_edge", 2, 3, 5, 9, 20, 9, Support.SWEEPING, null,
			null),
		enchantment("efficiency", 10, 5, 1, 10, 51, 10, Support.MINING, null,
			null),
		enchantment("silk_touch", 1, 1, 15, 0, 65, 0, Support.MINING_LOOT, null,
			"mining"),
		enchantment("unbreaking", 5, 3, 5, 8, 55, 8, Support.DURABILITY, null,
			null),
		enchantment("fortune", 2, 3, 15, 9, 65, 9, Support.MINING_LOOT, null,
			"mining"),
		enchantment("power", 10, 5, 1, 10, 16, 10, Support.BOW, null, null),
		enchantment("punch", 2, 2, 12, 20, 37, 20, Support.BOW, null, null),
		enchantment("flame", 2, 1, 20, 0, 50, 0, Support.BOW, null, null),
		enchantment("infinity", 1, 1, 20, 0, 50, 0, Support.BOW, null, "bow"),
		enchantment("luck_of_the_sea", 2, 3, 15, 9, 65, 9, Support.FISHING,
			null, null),
		enchantment("lure", 2, 3, 15, 9, 65, 9, Support.FISHING, null, null),
		enchantment("loyalty", 5, 3, 12, 7, 50, 0, Support.TRIDENT, null,
			"riptide"),
		enchantment("impaling", 2, 5, 1, 8, 21, 8, Support.TRIDENT, null,
			"damage"),
		enchantment("riptide", 2, 3, 17, 7, 50, 0, Support.TRIDENT, null,
			"riptide"),
		enchantment("channeling", 1, 1, 25, 0, 50, 0, Support.TRIDENT, null,
			null),
		enchantment("multishot", 2, 1, 20, 0, 50, 0, Support.CROSSBOW, null,
			"crossbow"),
		enchantment("quick_charge", 5, 3, 12, 20, 50, 0, Support.CROSSBOW, null,
			null),
		enchantment("piercing", 10, 4, 1, 10, 50, 0, Support.CROSSBOW, null,
			"crossbow"),
		enchantment("density", 5, 5, 5, 8, 25, 8, Support.MACE, null, "damage"),
		enchantment("breach", 2, 4, 15, 9, 65, 9, Support.MACE, null, "damage"),
		enchantment("lunge", 5, 3, 5, 8, 25, 8, Support.LUNGE, null, null),
		enchantment("binding_curse", 1, 1, 25, 0, 50, 0, Support.EQUIPPABLE,
			null, null),
		enchantment("vanishing_curse", 1, 1, 25, 0, 50, 0, Support.VANISHING,
			null, null),
		enchantment("frost_walker", 2, 2, 10, 10, 25, 10, Support.FOOT, null,
			"boots"),
		enchantment("mending", 2, 1, 25, 25, 75, 25, Support.DURABILITY, null,
			null));
	
	private static final List<LootEntry> NORMAL_RARE =
		List.of(item("emerald", 3, setCount(uniform(2, 4))),
			item("shield", 3, setDamage(uniform(0.5F, 1.0F))),
			item("bow", 3, enchantWithLevels(uniform(5, 15))),
			item("crossbow", 2, enchantWithLevels(uniform(5, 20))),
			item("iron_axe", 2, enchantWithLevels(uniform(0, 10))),
			item("iron_chestplate", 2, enchantWithLevels(uniform(0, 10))),
			item("golden_carrot", 2, setCount(uniform(1, 2))),
			item("book", 2,
				enchantRandomly("sharpness", "bane_of_arthropods", "efficiency",
					"fortune", "silk_touch", "feather_falling")),
			item("book", 2,
				enchantRandomly("riptide", "loyalty", "channeling", "impaling",
					"mending")),
			item("diamond_chestplate", 1, enchantWithLevels(uniform(5, 15))),
			item("diamond_axe", 1, enchantWithLevels(uniform(5, 15))));
	
	private static final List<LootEntry> NORMAL_COMMON =
		List.of(item("arrow", 4, setCount(uniform(2, 8))),
			item("tipped_arrow", 4, setCount(uniform(2, 8)),
				setPotion("poison")),
			item("emerald", 4, setCount(uniform(2, 4))),
			item("wind_charge", 3, setCount(uniform(1, 3))),
			item("iron_ingot", 3, setCount(uniform(1, 4))),
			item("honey_bottle", 3, setCount(uniform(1, 2))),
			item("ominous_bottle", 2, setCount(constant(1)),
				setOminousBottleAmplifier(uniform(0, 1))),
			item("wind_charge", 1, setCount(uniform(4, 12))),
			item("diamond", 1, setCount(uniform(1, 2))));
	
	private static final List<LootEntry> NORMAL_UNIQUE = List.of(
		item("golden_apple", 4), item("bolt_armor_trim_smithing_template", 3),
		item("guster_banner_pattern", 2), item("music_disc_precipice", 2),
		item("trident", 1));
	
	private static final List<LootEntry> OMINOUS_RARE =
		List.of(item("emerald_block", 5), item("iron_block", 4),
			item("crossbow", 4, enchantWithLevels(uniform(5, 20))),
			item("golden_apple", 3),
			item("diamond_axe", 3, enchantWithLevels(uniform(10, 20))),
			item("diamond_chestplate", 3, enchantWithLevels(uniform(10, 20))),
			item("book", 2,
				enchantRandomly("knockback", "punch", "smite", "looting",
					"multishot")),
			item("book", 2, enchantRandomly("breach", "density")),
			item("book", 2, setEnchantment("wind_burst", 1)),
			item("diamond_block", 1));
	
	private static final List<LootEntry> OMINOUS_COMMON = List.of(
		item("emerald", 5, setCount(uniform(4, 10))),
		item("wind_charge", 4, setCount(uniform(8, 12))),
		item("tipped_arrow", 3, setCount(uniform(4, 12)),
			setPotion("strong_slowness")),
		item("diamond", 2, setCount(uniform(2, 3))), item("ominous_bottle", 1,
			setCount(constant(1)), setOminousBottleAmplifier(uniform(2, 4))));
	
	private static final List<LootEntry> OMINOUS_UNIQUE =
		List.of(item("enchanted_golden_apple", 3),
			item("flow_armor_trim_smithing_template", 3),
			item("flow_banner_pattern", 2), item("music_disc_creator", 1),
			item("heavy_core", 1));
	
	private VaultRollPredictor()
	{}
	
	public static List<String> itemIds()
	{
		Set<String> result = new LinkedHashSet<>();
		addItemIds(result, NORMAL_RARE);
		addItemIds(result, NORMAL_COMMON);
		addItemIds(result, NORMAL_UNIQUE);
		addItemIds(result, OMINOUS_RARE);
		addItemIds(result, OMINOUS_COMMON);
		addItemIds(result, OMINOUS_UNIQUE);
		result.add("enchanted_book");
		return List.copyOf(result);
	}
	
	private static void addItemIds(Set<String> result, List<LootEntry> entries)
	{
		for(LootEntry entry : entries)
			result.add(shortId(entry.itemId()));
	}
	
	public static VaultRollOpening predictOpening(long worldSeed,
		VaultRollMode mode, long openingIndex)
	{
		if(openingIndex < 0)
			throw new IllegalArgumentException(
				"openingIndex must not be negative");
		VaultRollRandom random = VaultRollRandom.forSequence(worldSeed, mode);
		advance(random, mode, openingIndex);
		return nextOpening(random, mode);
	}
	
	public static List<VaultRollOpening> predictOpenings(long worldSeed,
		VaultRollMode mode, long firstOpening, int count)
	{
		if(firstOpening < 0 || count < 0)
			throw new IllegalArgumentException("Invalid opening range");
		VaultRollRandom random = VaultRollRandom.forSequence(worldSeed, mode);
		advance(random, mode, firstOpening);
		ArrayList<VaultRollOpening> result = new ArrayList<>(count);
		for(int i = 0; i < count; i++)
			result.add(nextOpening(random, mode));
		return Collections.unmodifiableList(result);
	}
	
	public static void advance(VaultRollRandom random, VaultRollMode mode,
		long openingCount)
	{
		for(long i = 0; i < openingCount; i++)
			nextOpening(random, mode);
	}
	
	public static VaultRollOpening nextOpening(VaultRollRandom random,
		VaultRollMode mode)
	{
		List<VaultRollStack> result = new ArrayList<>();
		List<LootEntry> rarePool =
			mode == VaultRollMode.OMINOUS ? OMINOUS_RARE : NORMAL_RARE;
		List<LootEntry> commonPool =
			mode == VaultRollMode.OMINOUS ? OMINOUS_COMMON : NORMAL_COMMON;
		List<LootEntry> uniquePool =
			mode == VaultRollMode.OMINOUS ? OMINOUS_UNIQUE : NORMAL_UNIQUE;
		// reward(_ominous).json selects the nested rare/common table with
		// weights 8 and 2. This selection consumes one RNG call.
		List<LootEntry> firstPool =
			random.nextInt(10) < 8 ? rarePool : commonPool;
		result.add(choose(firstPool, random).create(random));
		int commonRolls = random.nextIntInclusive(1, 3);
		for(int i = 0; i < commonRolls; i++)
			result.add(choose(commonPool, random).create(random));
		float uniqueChance = mode == VaultRollMode.OMINOUS ? 0.75F : 0.25F;
		if(random.nextFloat() < uniqueChance)
			result.add(choose(uniquePool, random).create(random));
		return new VaultRollOpening(result);
	}
	
	static List<EnchantmentInfo> enchantments()
	{
		return ON_RANDOM_LOOT;
	}
	
	public static TargetHit findTarget(long worldSeed, VaultRollMode mode,
		long firstOpening, int horizon, String itemId, Integer minimumCount,
		BooleanSupplier cancelled)
	{
		if(firstOpening < 0 || horizon < 0)
			throw new IllegalArgumentException("Invalid target search range");
		String target = normalizeItemId(itemId);
		VaultRollRandom random = VaultRollRandom.forSequence(worldSeed, mode);
		advance(random, mode, firstOpening);
		for(int offset = 0; offset <= horizon; offset++)
		{
			if((offset & 0x3FF) == 0 && cancelled.getAsBoolean())
				return null;
			VaultRollOpening opening = nextOpening(random, mode);
			Integer count = opening.aggregate().get(target);
			if(count != null && (minimumCount == null || count >= minimumCount))
				return new TargetHit(offset, firstOpening + offset, opening);
		}
		return null;
	}
	
	public static String normalizeItemId(String input)
	{
		return VaultRollObservation.normalizeItemId(input);
	}
	
	public static Long tryParseSeed(String input)
	{
		if(input == null)
			return null;
		String value = input.trim();
		if(value.matches("-?\\d+"))
		{
			try
			{
				return Long.parseLong(value);
			}catch(NumberFormatException e)
			{
				return null;
			}
		}
		return value.isEmpty() ? null : (long)value.hashCode();
	}
	
	public static String shortId(String id)
	{
		int colon = id.lastIndexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}
	
	public static String formatItem(String id)
	{
		StringBuilder result = new StringBuilder();
		for(String word : shortId(id).replace('_', ' ').split(" "))
		{
			if(word.isEmpty())
				continue;
			if(result.length() > 0)
				result.append(' ');
			result.append(Character.toUpperCase(word.charAt(0)))
				.append(word.substring(1));
		}
		return result.toString();
	}
	
	public record TargetHit(long offset, long absoluteOpening,
		VaultRollOpening opening)
	{}
	
	private static LootEntry choose(List<LootEntry> entries,
		VaultRollRandom random)
	{
		if(entries.size() == 1)
			return entries.get(0);
		int totalWeight = 0;
		for(LootEntry entry : entries)
			totalWeight += entry.weight();
		int choice = random.nextInt(totalWeight);
		for(LootEntry entry : entries)
		{
			choice -= entry.weight();
			if(choice < 0)
				return entry;
		}
		throw new IllegalStateException("Weighted loot selection overflow");
	}
	
	private static LootEntry item(String id, int weight,
		LootFunction... functions)
	{
		return new LootEntry("minecraft:" + id, weight, List.of(functions));
	}
	
	private static Num uniform(int min, int max)
	{
		return Num.uniform(min, max);
	}
	
	private static Num uniform(float min, float max)
	{
		return Num.uniform(min, max);
	}
	
	private static Num constant(int value)
	{
		return Num.constant(value);
	}
	
	private static LootFunction setCount(Num count)
	{
		return (random, stack) -> stack.count = count.getInt(random);
	}
	
	private static LootFunction setDamage(Num damage)
	{
		return (random, stack) -> stack.note =
			"damage=" + damage.getFloat(random);
	}
	
	private static LootFunction setPotion(String potion)
	{
		return (random, stack) -> stack.note = "potion=minecraft:" + potion;
	}
	
	private static LootFunction setOminousBottleAmplifier(Num amplifier)
	{
		return (random, stack) -> stack.note =
			"amplifier=" + amplifier.getInt(random);
	}
	
	private static LootFunction setEnchantment(String id, int level)
	{
		return (random, stack) -> {
			stack.itemId = "minecraft:enchanted_book";
			stack.enchantments
				.add(new VaultRollStack.Enchantment("minecraft:" + id, level));
		};
	}
	
	private static LootFunction enchantRandomly(String... ids)
	{
		return (random, stack) -> {
			int index = random.nextInt(ids.length);
			EnchantmentInfo info = findEnchantment(ids[index]);
			if(info == null)
				throw new IllegalStateException(
					"Missing enchantment: " + ids[index]);
			int level = random.nextIntInclusive(1, info.maxLevel());
			if(stack.itemId.equals("minecraft:book"))
				stack.itemId = "minecraft:enchanted_book";
			stack.enchantments
				.add(new VaultRollStack.Enchantment(info.id(), level));
		};
	}
	
	private static LootFunction enchantWithLevels(Num levels)
	{
		return (random, stack) -> {
			List<VaultRollStack.Enchantment> enchantments =
				selectEnchantments(stack.itemId, levels.getInt(random), random);
			if(stack.itemId.equals("minecraft:book") && !enchantments.isEmpty())
				stack.itemId = "minecraft:enchanted_book";
			stack.enchantments.addAll(enchantments);
		};
	}
	
	private static List<VaultRollStack.Enchantment> selectEnchantments(
		String item, int level, VaultRollRandom random)
	{
		List<VaultRollStack.Enchantment> picked = new ArrayList<>();
		int enchantability = enchantability(item);
		if(enchantability <= 0)
			return picked;
		boolean book = item.equals("minecraft:book");
		int cost = level + 1 + random.nextInt(enchantability / 4 + 1)
			+ random.nextInt(enchantability / 4 + 1);
		float factor = (random.nextFloat() + random.nextFloat() - 1.0F) * 0.15F;
		cost = Math.max(1, Math.round(cost + cost * factor));
		List<AvailableEnchantment> available =
			availableEnchantments(cost, item, book);
		if(available.isEmpty())
			return picked;
		AvailableEnchantment first = weightedEnchantment(available, random);
		if(first == null)
			return picked;
		picked.add(
			new VaultRollStack.Enchantment(first.info().id(), first.level()));
		while(random.nextInt(50) <= cost)
		{
			if(!picked.isEmpty())
			{
				VaultRollStack.Enchantment last = picked.get(picked.size() - 1);
				available =
					available.stream()
						.filter(entry -> entry.info()
							.compatibleWith(findEnchantment(last.id())))
						.toList();
			}
			if(available.isEmpty())
				break;
			AvailableEnchantment next = weightedEnchantment(available, random);
			if(next == null)
				break;
			picked.add(
				new VaultRollStack.Enchantment(next.info().id(), next.level()));
			cost /= 2;
		}
		return picked;
	}
	
	private static List<AvailableEnchantment> availableEnchantments(int cost,
		String item, boolean book)
	{
		List<AvailableEnchantment> result = new ArrayList<>();
		for(EnchantmentInfo info : ON_RANDOM_LOOT)
		{
			if(!book && !info.isPrimary(item))
				continue;
			int level = info.levelAt(cost);
			if(level > 0)
				result.add(new AvailableEnchantment(info, level));
		}
		return result;
	}
	
	private static AvailableEnchantment weightedEnchantment(
		List<AvailableEnchantment> entries, VaultRollRandom random)
	{
		int total = 0;
		for(AvailableEnchantment entry : entries)
			total += entry.info().weight();
		int choice = random.nextInt(total);
		for(AvailableEnchantment entry : entries)
		{
			choice -= entry.info().weight();
			if(choice < 0)
				return entry;
		}
		return null;
	}
	
	private static int enchantability(String item)
	{
		return switch(item)
		{
			case "minecraft:book", "minecraft:bow", "minecraft:crossbow" -> 1;
			case "minecraft:iron_axe" -> 14;
			case "minecraft:iron_chestplate" -> 9;
			case "minecraft:diamond_axe", "minecraft:diamond_chestplate" -> 10;
			default -> 0;
		};
	}
	
	private static EnchantmentInfo findEnchantment(String input)
	{
		String id = input.contains(":") ? input : "minecraft:" + input;
		for(EnchantmentInfo info : ON_RANDOM_LOOT)
			if(info.id().equals(id))
				return info;
		return null;
	}
	
	private static EnchantmentInfo enchantment(String id, int weight,
		int maxLevel, int minBase, int minPer, int maxBase, int maxPer,
		Support supported, Support primary, String exclusiveSet)
	{
		return new EnchantmentInfo("minecraft:" + id, weight, maxLevel, minBase,
			minPer, maxBase, maxPer, supported, primary, exclusiveSet);
	}
	
	private record AvailableEnchantment(EnchantmentInfo info, int level)
	{}
	
	public record EnchantmentInfo(String id, int weight, int maxLevel,
		int minCostBase, int minCostPer, int maxCostBase, int maxCostPer,
		Support supported, Support primary, String exclusiveSet)
	{
		public EnchantmentInfo
		{
			Objects.requireNonNull(id);
			Objects.requireNonNull(supported);
			if(weight < 1 || maxLevel < 1)
				throw new IllegalArgumentException("Invalid enchantment data");
		}
		
		private int minCost(int level)
		{
			return minCostBase + minCostPer * (level - 1);
		}
		
		private int maxCost(int level)
		{
			return maxCostBase + maxCostPer * (level - 1);
		}
		
		private int levelAt(int cost)
		{
			for(int level = maxLevel; level >= 1; level--)
				if(cost >= minCost(level) && cost <= maxCost(level))
					return level;
			return 0;
		}
		
		private boolean isPrimary(String item)
		{
			return supports(item)
				&& (primary == null || primary.supports(item));
		}
		
		private boolean supports(String item)
		{
			return supported.supports(item);
		}
		
		private boolean compatibleWith(EnchantmentInfo other)
		{
			return other != null && !id.equals(other.id)
				&& !excludes(exclusiveSet, other.id)
				&& !excludes(other.exclusiveSet, id);
		}
		
		private static boolean excludes(String set, String id)
		{
			if(set == null)
				return false;
			return switch(set)
			{
				case "armor" -> id.equals("minecraft:protection")
					|| id.equals("minecraft:fire_protection")
					|| id.equals("minecraft:blast_protection")
					|| id.equals("minecraft:projectile_protection");
				case "damage" -> id.equals("minecraft:sharpness")
					|| id.equals("minecraft:smite")
					|| id.equals("minecraft:bane_of_arthropods")
					|| id.equals("minecraft:impaling")
					|| id.equals("minecraft:density")
					|| id.equals("minecraft:breach");
				case "riptide" -> id.equals("minecraft:loyalty")
					|| id.equals("minecraft:channeling")
					|| id.equals("minecraft:riptide");
				case "bow" -> id.equals("minecraft:infinity")
					|| id.equals("minecraft:mending");
				case "crossbow" -> id.equals("minecraft:multishot")
					|| id.equals("minecraft:piercing");
				case "boots" -> id.equals("minecraft:frost_walker")
					|| id.equals("minecraft:depth_strider");
				case "mining" -> id.equals("minecraft:fortune")
					|| id.equals("minecraft:silk_touch");
				default -> false;
			};
		}
	}
	
	public enum Support
	{
		ARMOR,
		FOOT,
		HEAD,
		CHEST,
		SHARP_WEAPON,
		WEAPON,
		MELEE,
		FIRE_ASPECT,
		SWEEPING,
		MINING,
		MINING_LOOT,
		DURABILITY,
		BOW,
		CROSSBOW,
		FISHING,
		TRIDENT,
		MACE,
		LUNGE,
		EQUIPPABLE,
		VANISHING;
		
		private boolean supports(String item)
		{
			boolean chest = item.equals("minecraft:iron_chestplate")
				|| item.equals("minecraft:diamond_chestplate");
			boolean axe = item.equals("minecraft:iron_axe")
				|| item.equals("minecraft:diamond_axe");
			return switch(this)
			{
				case ARMOR -> chest;
				case FOOT, HEAD, MELEE, FIRE_ASPECT, SWEEPING, FISHING, TRIDENT, MACE, LUNGE -> false;
				case CHEST -> chest;
				case SHARP_WEAPON, WEAPON, MINING, MINING_LOOT -> axe;
				case DURABILITY, VANISHING -> chest || axe
					|| item.equals("minecraft:bow")
					|| item.equals("minecraft:crossbow");
				case BOW -> item.equals("minecraft:bow");
				case CROSSBOW -> item.equals("minecraft:crossbow");
				case EQUIPPABLE -> chest;
			};
		}
	}
	
	private record LootEntry(String itemId, int weight,
		List<LootFunction> functions)
	{
		private LootEntry
		{
			if(weight < 1)
				throw new IllegalArgumentException("weight must be positive");
			functions = List.copyOf(functions);
		}
		
		private VaultRollStack create(VaultRollRandom random)
		{
			MutableStack stack = new MutableStack(itemId);
			for(LootFunction function : functions)
				function.apply(random, stack);
			return new VaultRollStack(stack.itemId, stack.count,
				stack.enchantments, stack.note);
		}
	}
	
	private interface LootFunction
	{
		void apply(VaultRollRandom random, MutableStack stack);
	}
	
	private static final class MutableStack
	{
		private String itemId;
		private int count = 1;
		private final List<VaultRollStack.Enchantment> enchantments =
			new ArrayList<>();
		private String note;
		
		private MutableStack(String itemId)
		{
			this.itemId = itemId;
		}
	}
	
	private record Num(float min, float max, boolean integer)
	{
		private static Num constant(float value)
		{
			return new Num(value, value, true);
		}
		
		private static Num uniform(int min, int max)
		{
			return new Num(min, max, true);
		}
		
		private static Num uniform(float min, float max)
		{
			return new Num(min, max, false);
		}
		
		private int getInt(VaultRollRandom random)
		{
			if(integer)
				return random.nextIntInclusive(Math.round(min),
					Math.round(max));
			return Math.round(getFloat(random));
		}
		
		private float getFloat(VaultRollRandom random)
		{
			return random.nextFloat(min, max);
		}
	}
}
