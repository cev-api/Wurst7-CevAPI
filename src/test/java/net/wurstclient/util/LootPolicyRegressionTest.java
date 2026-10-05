/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** CPU checks for loot quality and selection inventory provenance. */
public final class LootPolicyRegressionTest
{
	public static void main(String[] args)
	{
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		var registryLookup = VanillaRegistries.createWorldLookup();
		// Hack settings are constructed before world component binding.
		check(
			Arrays.asList(LootItemPolicy.defaultWhitelist())
				.contains("minecraft:diamond"),
			"whitelist initialization must work before item components are bound");
		net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
			.build(registryLookup).forEach(pending -> pending.apply());
		var enchantments = registryLookup.lookupOrThrow(Registries.ENCHANTMENT);
		ItemStack plain = new ItemStack(Items.DIAMOND_CHESTPLATE);
		ItemStack protectedArmor = plain.copy();
		protectedArmor.enchant(enchantments
			.get(net.minecraft.world.item.enchantment.Enchantments.PROTECTION)
			.orElseThrow(), 4);
		protectedArmor.setDamageValue(protectedArmor.getMaxDamage() - 1);
		check(
			LootItemPolicy.score(protectedArmor) > LootItemPolicy.score(plain),
			"Protection IV must beat durability for equal material");
		ItemStack healthier = protectedArmor.copy();
		healthier.setDamageValue(0);
		check(
			LootItemPolicy.score(healthier) > LootItemPolicy
				.score(protectedArmor),
			"equal enchantments must prefer durability");
		check(
			LootItemPolicy.type(new ItemStack(Items.IRON_SWORD)).equals(
				LootItemPolicy.type(new ItemStack(Items.DIAMOND_SWORD))),
			"swords must compare across materials");
		check(
			!LootItemPolicy.type(new ItemStack(Items.DIAMOND_AXE)).equals(
				LootItemPolicy.type(new ItemStack(Items.DIAMOND_PICKAXE))),
			"axes and pickaxes must stay distinct");
		var whitelist = Arrays.asList(LootItemPolicy.defaultWhitelist());
		check(
			whitelist.contains("minecraft:diamond")
				&& whitelist.contains("minecraft:diamond_chestplate")
				&& whitelist.contains("minecraft:totem_of_undying"),
			"default valuables missing");
		check(
			!whitelist.contains("minecraft:dirt")
				&& !whitelist.contains("minecraft:oak_planks"),
			"default whitelist must exclude junk");
		check(
			LootItemPolicy.value(new ItemStack(Items.DIAMOND)) > LootItemPolicy
				.value(new ItemStack(Items.DIRT)),
			"value sort must distinguish diamonds from dirt");
		
		SelectedPickupBudget budget = new SelectedPickupBudget();
		ItemStack diamonds = new ItemStack(Items.DIAMOND, 10);
		budget.start(List.of(diamonds));
		var inventory =
			List.of(diamonds.copyWithCount(15), new ItemStack(Items.DIRT, 64));
		budget.observeInventory(inventory);
		check(budget.excess(inventory, diamonds) == 5,
			"unselected identical stacks must be rejected without losing original diamonds");
		check(budget.excess(inventory, inventory.get(1)) == 64,
			"all newly acquired junk must be rejected");
		budget.allow(diamonds, 3);
		check(budget.excess(inventory, diamonds) == 2,
			"only confirmed selected quantity may be added");
		budget.rememberInventory(List.of(diamonds.copyWithCount(13)));
		budget.observeInventory(List.of(diamonds.copyWithCount(13)));
		check(budget.excess(List.of(diamonds.copyWithCount(13)), diamonds) == 0,
			"our own rejection must preserve existing inventory");
		budget.observeInventory(List.of(diamonds.copyWithCount(8)));
		budget.observeInventory(List.of(diamonds.copyWithCount(13)));
		check(budget.excess(List.of(diamonds.copyWithCount(13)), diamonds) == 5,
			"dropped items must not be allowed back in");
		budget.observeInventory(List.of());
		budget.observeInventory(List.of(diamonds));
		check(budget.excess(List.of(diamonds), diamonds) == 10,
			"dropping the entire stack must revoke its whole allowance");
		budget.start(List.of(plain));
		check(
			budget.excess(List.of(plain, protectedArmor), protectedArmor) == 1,
			"components must identify different gear");
		System.out.println(
			"Loot policy and selected pickup budget regression checks passed.");
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
