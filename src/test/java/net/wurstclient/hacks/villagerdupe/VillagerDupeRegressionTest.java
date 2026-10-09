/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.villagerdupe;

import java.util.ArrayDeque;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.wurstclient.hacks.villagerdupe.VillagerDupeEngine.DupeFilter;

/** Inventory and filtering checks without a Minecraft client or server. */
public final class VillagerDupeRegressionTest
{
	public static void main(String[] args) throws ReflectiveOperationException
	{
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		var lookup = VanillaRegistries.createWorldLookup();
		BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)
			.forEach(pending -> pending.apply());
		
		ItemStack saddle = new ItemStack(Items.SADDLE);
		ItemStack armor = new ItemStack(Items.DIAMOND_HORSE_ARMOR);
		ItemStack carpet = new ItemStack(Items.CARPET.asList().getFirst());
		check(DupeFilter.ALL.matches(saddle),
			"All must include shearable saddles");
		check(DupeFilter.SADDLE.matches(saddle),
			"Saddles filter must match saddles");
		check(!DupeFilter.SADDLE.matches(armor),
			"Saddles filter must exclude horse armor");
		check(DupeFilter.HORSE_ARMOR.matches(armor),
			"Horse armor filter must match horse armor");
		check(
			DupeFilter.HORSE_ARMOR
				.matches(new ItemStack(Items.COPPER_HORSE_ARMOR)),
			"Copper armor must be included");
		check(
			DupeFilter.HORSE_ARMOR
				.matches(new ItemStack(Items.NETHERITE_HORSE_ARMOR)),
			"Netherite armor must be included");
		check(DupeFilter.CARPET.matches(carpet),
			"Carpet filter must match colored carpets");
		check(!DupeFilter.CARPET.matches(saddle),
			"Carpet filter must exclude saddles");
		check(!DupeFilter.ALL.matches(new ItemStack(Items.EMERALD)),
			"Non-shearable trade items must be excluded");
		check(!DupeFilter.ALL.matches(ItemStack.EMPTY),
			"Empty hands must be excluded");
		armor.enchant(lookup.lookupOrThrow(Registries.ENCHANTMENT)
			.getOrThrow(Enchantments.BINDING_CURSE), 1);
		check(!DupeFilter.ALL.matches(armor),
			"Binding curse must prevent shearing");
		check(DupeFilter.fromToken("Horse Armor") == DupeFilter.HORSE_ARMOR,
			"UI label must round-trip");
		check(DupeFilter.fromToken("minecraft:red_carpet") == DupeFilter.CARPET,
			"Carpet item token must resolve");
		
		ItemStack shears = new ItemStack(Items.SHEARS);
		check(
			VillagerDupeEngine.usesLeft(shears, 4) == shears.getMaxDamage() - 5,
			"Burst budget must reserve pending damage and the final durability point");
		shears.setDamageValue(shears.getMaxDamage() - 1);
		check(VillagerDupeEngine.usesLeft(shears, 0) == 0,
			"Last durability point must not be spent");
		
		VillagerDupeEngine engine = new VillagerDupeEngine();
		shears.setDamageValue(10);
		engine.restartShearLog(shears);
		var field = VillagerDupeEngine.class.getDeclaredField("shearLog");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		ArrayDeque<int[]> pending = (ArrayDeque<int[]>)field.get(engine);
		pending.add(new int[]{1, 3});
		pending.add(new int[]{2, 2});
		// Server acknowledges two shears, replacing the stack object.
		ItemStack ack = shears.copy();
		ack.setDamageValue(12);
		engine.settleShearLog(ack);
		check(pending.size() == 2 && pending.peekFirst()[1] == 1,
			"Damage acknowledgement must release only the acknowledged reservations");
		// A later update mutates the same object instead of replacing it.
		ack.setDamageValue(14);
		engine.settleShearLog(ack);
		check(pending.size() == 1 && pending.peekFirst()[1] == 1,
			"In-place damage update must release reservations across burst boundaries");
		engine.settleShearLog(ack);
		check(pending.peekFirst()[1] == 1,
			"Repeated observation must not release damage twice");
		ItemStack fresh = new ItemStack(Items.SHEARS);
		engine.settleShearLog(fresh);
		check(pending.isEmpty(),
			"Replacement shears must clear old reservations");
		pending.add(new int[]{3, 1});
		engine.settleShearLog(ItemStack.EMPTY);
		check(pending.isEmpty(), "Lost shears must clear reservations");
		System.out.println(
			"VillagerDupe filtering and durability regression checks passed.");
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
