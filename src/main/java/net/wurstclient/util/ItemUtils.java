/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.OptionalDouble;

import net.minecraft.IdentifierException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.wurstclient.WurstClient;

public enum ItemUtils
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	public static String getStackId(ItemStack stack)
	{
		if(stack == null)
			return null;
		CompoundTag tag =
			stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
				.copyTag();
		if(tag != null && tag.contains("synthetic_id"))
			return tag.getString("synthetic_id").orElse(null);
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id != null ? id.toString() : null;
	}
	
	public static boolean isSyntheticXp(ItemStack stack)
	{
		if(stack == null)
			return false;
		CompoundTag tag =
			stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
				.copyTag();
		return tag != null && tag.contains("isXpOrb");
	}
	
	public static int getXpAmount(ItemStack stack)
	{
		if(stack == null)
			return 0;
		CompoundTag tag =
			stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
				.copyTag();
		if(tag == null)
			return 0;
		return tag.contains("xp_amount") ? tag.getInt("xp_amount").orElse(0)
			: 0;
	}
	
	public static int getXpAge(ItemStack stack)
	{
		if(stack == null)
			return 0;
		CompoundTag tag =
			stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
				.copyTag();
		if(tag == null)
			return 0;
		return tag.contains("xp_age") ? tag.getInt("xp_age").orElse(0) : 0;
	}
	
	public static ItemStack createSyntheticXpStack(ExperienceOrb orb)
	{
		ItemStack s = new ItemStack(Items.EXPERIENCE_BOTTLE);
		s.setCount(1);
		CompoundTag tag = new CompoundTag();
		tag.putString("synthetic_id", "minecraft:experience_orb");
		tag.putInt("xp_amount", orb.getValue());
		tag.putInt("xp_age", orb.tickCount);
		tag.putByte("isXpOrb", (byte)1);
		try
		{
			s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
			// set display name via custom name component (safe)
			s.set(DataComponents.CUSTOM_NAME,
				Component.literal("XP Orb (+" + orb.getValue() + " XP)"));
		}catch(Throwable ignored)
		{}
		return s;
	}
	
	/**
	 * @param nameOrId
	 *            a String containing the item's name ({@link Identifier}) or
	 *            numeric ID.
	 * @return the requested item, or null if the item doesn't exist.
	 */
	public static Item getItemFromNameOrID(String nameOrId)
	{
		if(MathUtils.isInteger(nameOrId))
		{
			// There is no getOptionalValue() for raw IDs, so this detects when
			// the registry defaults and returns null instead
			int id = Integer.parseInt(nameOrId);
			Item item = BuiltInRegistries.ITEM.byId(id);
			if(id != 0 && BuiltInRegistries.ITEM.getId(item) == 0)
				return null;
			
			return item;
		}
		
		try
		{
			// getOptionalValue() returns null instead of Items.AIR if the
			// requested item doesn't exist
			return BuiltInRegistries.ITEM
				.getOptional(Identifier.parse(nameOrId)).orElse(null);
			
		}catch(IdentifierException e)
		{
			return null;
		}
	}
	
	// TODO: Update AutoSword to use calculateModifiedAttribute() instead,
	// then remove this method.
	public static OptionalDouble getAttribute(Item item,
		Holder<Attribute> attribute)
	{
		return item.components()
			.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
				ItemAttributeModifiers.EMPTY)
			.modifiers().stream()
			.filter(modifier -> modifier.attribute() == attribute)
			.mapToDouble(modifier -> modifier.modifier().amount()).findFirst();
	}
	
	public static double calculateModifiedAttribute(Item item,
		Holder<Attribute> attribute, double base, EquipmentSlot slot)
	{
		ItemAttributeModifiers modifiers = item.components().getOrDefault(
			DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		
		double result = base;
		for(ItemAttributeModifiers.Entry entry : modifiers.modifiers())
		{
			if(entry.attribute() != attribute || !entry.slot().test(slot))
				continue;
			
			double value = entry.modifier().amount();
			result += switch(entry.modifier().operation())
			{
				case ADD_VALUE -> value;
				case ADD_MULTIPLIED_BASE -> value * base;
				case ADD_MULTIPLIED_TOTAL -> value * result;
			};
		}
		
		return result;
	}
	
	public static double getArmorAttribute(Item item,
		Holder<Attribute> attribute)
	{
		Equippable equippable =
			item.components().get(DataComponents.EQUIPPABLE);
		
		double base = MC.player.getAttributeBaseValue(attribute);
		if(equippable == null)
			return base;
		
		return calculateModifiedAttribute(item, attribute, base,
			equippable.slot());
	}
	
	public static double getArmorPoints(Item item)
	{
		return getArmorAttribute(item, Attributes.ARMOR);
	}
	
	public static double getToughness(Item item)
	{
		return getArmorAttribute(item, Attributes.ARMOR_TOUGHNESS);
	}
	
	public static EquipmentSlot getArmorSlot(Item item)
	{
		Equippable equippable =
			item.components().get(DataComponents.EQUIPPABLE);
		
		return equippable != null ? equippable.slot() : null;
	}
	
	public static boolean hasEffect(ItemStack stack, Holder<MobEffect> effect)
	{
		PotionContents potionContents = stack.getComponents()
			.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
		
		for(MobEffectInstance effectInstance : potionContents.getAllEffects())
			if(effectInstance.getEffect() == effect)
				return true;
			
		return false;
	}
}
