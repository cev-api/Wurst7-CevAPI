/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** Composable property constraints shared by built-in and custom filters. */
public record ItemFilterModifiers(Boolean enchanted, Boolean damaged,
	Integer minimumDurabilityPercent, Integer minimumEnchantmentLevel,
	Boolean customNamed, String requiredEnchantmentId, String material,
	Boolean treasureEnchantment, Boolean curse)
{
	public ItemFilterModifiers(Boolean enchanted, Boolean damaged,
		Integer minimumDurabilityPercent, Integer minimumEnchantmentLevel,
		Boolean customNamed)
	{
		this(enchanted, damaged, minimumDurabilityPercent,
			minimumEnchantmentLevel, customNamed, null, null, null, null);
	}
	
	public boolean matches(ItemStack stack)
	{
		if(stack.isEmpty())
			return false;
		var enchantments =
			EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet();
		boolean hasEnchantments = !enchantments.isEmpty()
			|| stack.has(DataComponents.STORED_ENCHANTMENTS);
		if(enchanted != null && enchanted.booleanValue() != hasEnchantments)
			return false;
		boolean isDamaged =
			stack.isDamageableItem() && stack.getDamageValue() > 0;
		if(damaged != null && damaged.booleanValue() != isDamaged)
			return false;
		if(minimumDurabilityPercent != null)
		{
			if(!stack.isDamageableItem() || stack.getMaxDamage() <= 0)
				return false;
			int remaining = (stack.getMaxDamage() - stack.getDamageValue())
				* 100 / stack.getMaxDamage();
			if(remaining < minimumDurabilityPercent)
				return false;
		}
		if(minimumEnchantmentLevel != null && enchantments.stream()
			.noneMatch(entry -> entry.getIntValue() >= minimumEnchantmentLevel))
			return false;
		if(requiredEnchantmentId != null && !requiredEnchantmentId.isBlank()
			&& enchantments.stream().noneMatch(
				entry -> hasId(entry.getKey(), requiredEnchantmentId)))
			return false;
		if(material != null && !material.isBlank()
			&& !hasMaterial(stack, material))
			return false;
		if(treasureEnchantment != null
			&& enchantments.stream().anyMatch(entry -> entry.getKey()
				.is(EnchantmentTags.TREASURE)) != treasureEnchantment)
			return false;
		if(curse != null && enchantments.stream().anyMatch(
			entry -> entry.getKey().is(EnchantmentTags.CURSE)) != curse)
			return false;
		return customNamed == null || customNamed.booleanValue() == stack
			.has(DataComponents.CUSTOM_NAME);
	}
	
	private static boolean hasMaterial(ItemStack stack, String rawMaterial)
	{
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		String material = rawMaterial.trim().toLowerCase(java.util.Locale.ROOT);
		if(material.equals("gold"))
			return path.contains("gold") || path.contains("golden");
		if(material.equals("wood"))
			return path.contains("wood") || path.contains("wooden");
		return path.contains(material);
	}
	
	private static boolean hasId(Holder<Enchantment> enchantment, String rawId)
	{
		try
		{
			Identifier wanted = Identifier.parse(rawId.trim());
			return enchantment.unwrapKey()
				.map(key -> key.identifier().equals(wanted)).orElse(false);
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
}
