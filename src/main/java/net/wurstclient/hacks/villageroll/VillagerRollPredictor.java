/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.villageroll;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class VillagerRollPredictor
{
	public static final String LIBRARIAN_SEQUENCE_ID =
		"minecraft:trade_set/librarian/level_1";
	
	private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
	private static final long SILVER_RATIO_64 = 0x6A09E667F3BCC909L;
	private static final long MIX_CONST_1 = 0xBF58476D1CE4E5B9L;
	private static final long MIX_CONST_2 = 0x94D049BB133111EBL;
	private static final long[] SEQUENCE_HASH =
		hashSequenceId(LIBRARIAN_SEQUENCE_ID);
	
	private static final int PAPER = 0;
	private static final int ENCHANTED_BOOK = 1;
	private static final int BOOKSHELF = 2;
	
	private static final List<EnchantmentInfo> ENCHANTMENTS =
		List.of(new EnchantmentInfo("minecraft:protection", 4, false),
			new EnchantmentInfo("minecraft:fire_protection", 4, false),
			new EnchantmentInfo("minecraft:feather_falling", 4, false),
			new EnchantmentInfo("minecraft:blast_protection", 4, false),
			new EnchantmentInfo("minecraft:projectile_protection", 4, false),
			new EnchantmentInfo("minecraft:respiration", 3, false),
			new EnchantmentInfo("minecraft:aqua_affinity", 1, false),
			new EnchantmentInfo("minecraft:thorns", 3, false),
			new EnchantmentInfo("minecraft:depth_strider", 3, false),
			new EnchantmentInfo("minecraft:sharpness", 5, false),
			new EnchantmentInfo("minecraft:smite", 5, false),
			new EnchantmentInfo("minecraft:bane_of_arthropods", 5, false),
			new EnchantmentInfo("minecraft:knockback", 2, false),
			new EnchantmentInfo("minecraft:fire_aspect", 2, false),
			new EnchantmentInfo("minecraft:looting", 3, false),
			new EnchantmentInfo("minecraft:sweeping_edge", 3, false),
			new EnchantmentInfo("minecraft:efficiency", 5, false),
			new EnchantmentInfo("minecraft:silk_touch", 1, false),
			new EnchantmentInfo("minecraft:unbreaking", 3, false),
			new EnchantmentInfo("minecraft:fortune", 3, false),
			new EnchantmentInfo("minecraft:power", 5, false),
			new EnchantmentInfo("minecraft:punch", 2, false),
			new EnchantmentInfo("minecraft:flame", 1, false),
			new EnchantmentInfo("minecraft:infinity", 1, false),
			new EnchantmentInfo("minecraft:luck_of_the_sea", 3, false),
			new EnchantmentInfo("minecraft:lure", 3, false),
			new EnchantmentInfo("minecraft:loyalty", 3, false),
			new EnchantmentInfo("minecraft:impaling", 5, false),
			new EnchantmentInfo("minecraft:riptide", 3, false),
			new EnchantmentInfo("minecraft:channeling", 1, false),
			new EnchantmentInfo("minecraft:multishot", 1, false),
			new EnchantmentInfo("minecraft:quick_charge", 3, false),
			new EnchantmentInfo("minecraft:piercing", 4, false),
			new EnchantmentInfo("minecraft:density", 5, false),
			new EnchantmentInfo("minecraft:breach", 4, false),
			new EnchantmentInfo("minecraft:lunge", 3, false),
			new EnchantmentInfo("minecraft:binding_curse", 1, true),
			new EnchantmentInfo("minecraft:vanishing_curse", 1, true),
			new EnchantmentInfo("minecraft:frost_walker", 2, true),
			new EnchantmentInfo("minecraft:mending", 1, true));
	
	static
	{
		if(ENCHANTMENTS.size() != 40)
			throw new IllegalStateException(
				"The librarian enchantment table must contain 40 entries.");
	}
	
	private VillagerRollPredictor()
	{}
	
	public static List<EnchantmentInfo> enchantments()
	{
		return ENCHANTMENTS;
	}
	
	public static EnchantmentInfo findEnchantment(String input)
	{
		if(input == null)
			return null;
		
		String id = normalizeId(input);
		return ENCHANTMENTS.stream().filter(e -> e.id().equals(id)).findFirst()
			.orElse(null);
	}
	
	public static String normalizeId(String input)
	{
		String id = input.trim().toLowerCase(java.util.Locale.ROOT);
		if(!id.contains(":"))
			id = "minecraft:" + id;
		return id;
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
		
		return (long)value.hashCode();
	}
	
	public static long[] createSequenceSeed(long worldSeed)
	{
		long s0 = worldSeed ^ SILVER_RATIO_64;
		long s1 = s0 + GOLDEN_GAMMA;
		s0 ^= SEQUENCE_HASH[0];
		s1 ^= SEQUENCE_HASH[1];
		return new long[]{mixStafford13(s0), mixStafford13(s1)};
	}
	
	public static PredictorRandom createRandom(long worldSeed)
	{
		long[] seed = createSequenceSeed(worldSeed);
		return new PredictorRandom(seed[0], seed[1]);
	}
	
	public static VillagerRollRoll predictRoll(long worldSeed, long rollIndex)
	{
		if(rollIndex < 0)
			throw new IllegalArgumentException(
				"rollIndex must not be negative");
		
		PredictorRandom random = createRandom(worldSeed);
		advance(random, rollIndex);
		return nextLibrarianRoll(random);
	}
	
	public static List<VillagerRollRoll> predictRolls(long worldSeed,
		long firstRoll, int count)
	{
		if(firstRoll < 0 || count < 0)
			throw new IllegalArgumentException("Invalid roll range");
		
		PredictorRandom random = createRandom(worldSeed);
		advance(random, firstRoll);
		ArrayList<VillagerRollRoll> result = new ArrayList<>(count);
		for(int i = 0; i < count; i++)
			result.add(nextLibrarianRoll(random));
		return Collections.unmodifiableList(result);
	}
	
	public static List<BookHit> findBooks(long worldSeed, long firstRoll,
		int horizon, String enchantmentId, Integer level, Integer maxPrice,
		int maxHits, BooleanSupplier cancelled)
	{
		if(firstRoll < 0 || horizon < 0 || maxHits < 1)
			throw new IllegalArgumentException("Invalid search range");
		
		String normalizedId = normalizeId(enchantmentId);
		PredictorRandom random = createRandom(worldSeed);
		advance(random, firstRoll);
		ArrayList<BookHit> result = new ArrayList<>();
		for(int offset = 0; offset <= horizon; offset++)
		{
			if((offset & 0x3FF) == 0 && cancelled.getAsBoolean())
				break;
			
			long absoluteRoll = firstRoll + offset;
			VillagerRollRoll roll = nextLibrarianRoll(random);
			addBookHit(result, roll.first(), 1, offset, absoluteRoll,
				normalizedId, level, maxPrice, maxHits);
			addBookHit(result, roll.second(), 2, offset, absoluteRoll,
				normalizedId, level, maxPrice, maxHits);
			if(result.size() >= maxHits)
				break;
		}
		return Collections.unmodifiableList(result);
	}
	
	private static void addBookHit(List<BookHit> result,
		VillagerRollTrade trade, int slot, int offset, long absoluteRoll,
		String enchantmentId, Integer level, Integer maxPrice, int maxHits)
	{
		if(result.size() >= maxHits
			|| trade.kind() != VillagerRollTradeKind.ENCHANTED_BOOK)
			return;
		if(!trade.enchantmentId().equals(enchantmentId)
			|| level != null && trade.level() != level
			|| maxPrice != null && trade.emeraldPrice() > maxPrice)
			return;
		result.add(new BookHit(offset, absoluteRoll, slot, trade));
	}
	
	public static void advance(PredictorRandom random, long rollCount)
	{
		for(long i = 0; i < rollCount; i++)
			nextLibrarianRoll(random);
	}
	
	public static VillagerRollRoll nextLibrarianRoll(PredictorRandom random)
	{
		int firstType = random.nextInt(3);
		VillagerRollTrade first = generateTrade(random, firstType);
		int secondChoice = random.nextInt(2);
		int secondType = getRemainingTradeType(firstType, secondChoice);
		VillagerRollTrade second = generateTrade(random, secondType);
		return new VillagerRollRoll(first, second);
	}
	
	private static VillagerRollTrade generateTrade(PredictorRandom random,
		int type)
	{
		return switch(type)
		{
			case PAPER -> VillagerRollTrade.paper();
			case BOOKSHELF -> VillagerRollTrade.bookshelf();
			case ENCHANTED_BOOK -> generateBook(random);
			default -> throw new IllegalStateException(
				"Unknown trade type: " + type);
		};
	}
	
	private static int getRemainingTradeType(int firstType, int secondChoice)
	{
		return switch(firstType)
		{
			case PAPER -> secondChoice == 0 ? ENCHANTED_BOOK : BOOKSHELF;
			case ENCHANTED_BOOK -> secondChoice == 0 ? PAPER : BOOKSHELF;
			case BOOKSHELF -> secondChoice == 0 ? PAPER : ENCHANTED_BOOK;
			default -> throw new IllegalStateException(
				"Unknown trade type: " + firstType);
		};
	}
	
	private static VillagerRollTrade generateBook(PredictorRandom random)
	{
		EnchantmentInfo enchantment =
			ENCHANTMENTS.get(random.nextInt(ENCHANTMENTS.size()));
		int level = enchantment.maxLevel() > 1
			? 1 + random.nextInt(enchantment.maxLevel()) : 1;
		int priceRange = 5 + level * 10;
		int price = 2 + random.nextInt(priceRange) + 3 * level;
		if(enchantment.doublePrice())
			price *= 2;
		price = Math.max(0, Math.min(64, price));
		return VillagerRollTrade.enchantedBook(enchantment.id(), level, price);
	}
	
	private static long mixStafford13(long value)
	{
		value = (value ^ (value >>> 30)) * MIX_CONST_1;
		value = (value ^ (value >>> 27)) * MIX_CONST_2;
		return value ^ (value >>> 31);
	}
	
	private static long readBigEndianLong(byte[] bytes, int offset)
	{
		long result = 0L;
		for(int i = 0; i < 8; i++)
		{
			result <<= 8;
			result |= bytes[offset + i] & 0xFFL;
		}
		return result;
	}
	
	private static long[] hashSequenceId(String sequenceId)
	{
		try
		{
			MessageDigest md5 = MessageDigest.getInstance("MD5");
			byte[] digest =
				md5.digest(sequenceId.getBytes(StandardCharsets.UTF_8));
			return new long[]{readBigEndianLong(digest, 0),
				readBigEndianLong(digest, 8)};
		}catch(NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("MD5 unavailable", e);
		}
	}
	
	public record EnchantmentInfo(String id, int maxLevel, boolean doublePrice)
	{
		public EnchantmentInfo
		{
			Objects.requireNonNull(id);
			if(maxLevel < 1)
				throw new IllegalArgumentException("maxLevel must be positive");
		}
	}
	
	public record BookHit(int rollsAhead, long absoluteRoll, int slot,
		VillagerRollTrade trade)
	{}
	
	public static final class PredictorRandom
	{
		private long s0;
		private long s1;
		
		private PredictorRandom(long s0, long s1)
		{
			if((s0 | s1) == 0L)
			{
				s0 = GOLDEN_GAMMA;
				s1 = SILVER_RATIO_64;
			}
			this.s0 = s0;
			this.s1 = s1;
		}
		
		public long nextLong()
		{
			long a = s0;
			long b = s1;
			long result = Long.rotateLeft(a + b, 17) + a;
			b ^= a;
			s0 = Long.rotateLeft(a, 49) ^ b ^ (b << 21);
			s1 = Long.rotateLeft(b, 28);
			return result;
		}
		
		public int nextInt(int bound)
		{
			if(bound <= 0)
				throw new IllegalArgumentException("Bound must be positive");
			
			long randomBits = nextLong() & 0xFFFFFFFFL;
			long multipliedRandomBits = randomBits * bound;
			long fractionalPart = multipliedRandomBits & 0xFFFFFFFFL;
			long threshold = 0x1_0000_0000L % bound;
			while(fractionalPart < threshold)
			{
				randomBits = nextLong() & 0xFFFFFFFFL;
				multipliedRandomBits = randomBits * bound;
				fractionalPart = multipliedRandomBits & 0xFFFFFFFFL;
			}
			return (int)(multipliedRandomBits >>> 32);
		}
	}
}
