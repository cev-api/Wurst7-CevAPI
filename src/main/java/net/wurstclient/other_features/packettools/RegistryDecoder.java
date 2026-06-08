/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.core.particles.ParticleType;

/**
 * Resolves registry IDs to human-readable names for entity types, items,
 * blocks, particles, sounds, attributes, game events, enchantments, fluids,
 * mob effects, and equipment slots. Also provides detailed Map-based decoders
 * for ItemStack, BlockState, and DataValue.
 */
public enum RegistryDecoder
{
	;
	
	public static String decode(Object value)
	{
		if(value == null)
			return null;
		
		if(value instanceof Holder<?> holder)
			return decodeHolder(holder);
		if(value instanceof ResourceKey<?> key)
			return decodeResourceKey(key);
		if(value instanceof Identifier loc)
			return loc.toString();
		if(value instanceof Number num)
			return decodeNumericId(num.intValue());
		if(value instanceof ItemStack stack)
			return decodeItemStack(stack);
		if(value instanceof BlockState state)
			return decodeBlockState(state);
		if(value instanceof Enum<?> enumVal)
			return enumVal.name();
		if(value instanceof EquipmentSlot slot)
			return slot.getName();
		
		return null;
	}
	
	private static String decodeHolder(Holder<?> holder)
	{
		Optional<?> keyOpt = holder.unwrapKey();
		if(keyOpt.isPresent() && keyOpt.get() instanceof ResourceKey<?> rk)
			return decodeResourceKey(rk);
		
		Object val = holder.value();
		if(val != null)
		{
			Identifier loc = getRegistryName(val);
			if(loc != null)
				return loc.toString();
			return val.getClass().getSimpleName() + "(unregistered)";
		}
		
		return "Holder(unknown)";
	}
	
	private static String decodeResourceKey(ResourceKey<?> key)
	{
		if(key == null)
			return null;
		Identifier loc = key.identifier();
		Identifier registry = key.registry();
		if(registry != null)
			return registry.getPath() + ":" + loc.getNamespace() + ":"
				+ loc.getPath();
		return loc.getNamespace() + ":" + loc.getPath();
	}
	
	private static String decodeNumericId(int id)
	{
		EntityType<?> et = BuiltInRegistries.ENTITY_TYPE.byId(id);
		if(et != null && BuiltInRegistries.ENTITY_TYPE.getId(et) == id)
			return "entity:" + BuiltInRegistries.ENTITY_TYPE.getKey(et);
		
		Item item = BuiltInRegistries.ITEM.byId(id);
		if(item != null && BuiltInRegistries.ITEM.getId(item) == id)
			return "item:" + BuiltInRegistries.ITEM.getKey(item);
		
		Block block = BuiltInRegistries.BLOCK.byId(id);
		if(block != null && BuiltInRegistries.BLOCK.getId(block) == id)
			return "block:" + BuiltInRegistries.BLOCK.getKey(block);
		
		ParticleType<?> particle = BuiltInRegistries.PARTICLE_TYPE.byId(id);
		if(particle != null
			&& BuiltInRegistries.PARTICLE_TYPE.getId(particle) == id)
			return "particle:"
				+ BuiltInRegistries.PARTICLE_TYPE.getKey(particle);
		
		SoundEvent sound = BuiltInRegistries.SOUND_EVENT.byId(id);
		if(sound != null && BuiltInRegistries.SOUND_EVENT.getId(sound) == id)
			return "sound:" + BuiltInRegistries.SOUND_EVENT.getKey(sound);
		
		GameEvent ge = BuiltInRegistries.GAME_EVENT.byId(id);
		if(ge != null && BuiltInRegistries.GAME_EVENT.getId(ge) == id)
			return "game_event:" + BuiltInRegistries.GAME_EVENT.getKey(ge);
		
		MobEffect effect = BuiltInRegistries.MOB_EFFECT.byId(id);
		if(effect != null && BuiltInRegistries.MOB_EFFECT.getId(effect) == id)
			return "effect:" + BuiltInRegistries.MOB_EFFECT.getKey(effect);
		
		return "unknown_id:" + id;
	}
	
	/**
	 * String form kept for backward compat. Prefer decodeItemStackDetailed().
	 */
	public static String decodeItemStack(ItemStack stack)
	{
		if(stack.isEmpty())
			return "ItemStack{empty}";
		Identifier itemLoc = BuiltInRegistries.ITEM.getKey(stack.getItem());
		StringBuilder sb = new StringBuilder();
		sb.append(itemLoc).append(" x").append(stack.getCount());
		if(stack.has(DataComponents.CUSTOM_NAME))
			sb.append(" named:'").append(stack.getHoverName().getString())
				.append("'");
		if(stack.isDamaged())
			sb.append(" damage:").append(stack.getDamageValue()).append("/")
				.append(stack.getMaxDamage());
		if(stack.has(DataComponents.ENCHANTMENTS))
		{
			var enchants = stack.get(DataComponents.ENCHANTMENTS);
			if(enchants != null && !enchants.isEmpty())
			{
				sb.append(" enchants:[");
				boolean first = true;
				for(var entry : enchants.entrySet())
				{
					if(!first)
						sb.append(",");
					first = false;
					String name = entry.getKey().unwrapKey()
						.map(k -> k.identifier().getPath()).orElse("?");
					sb.append(name).append("=").append(entry.getIntValue());
				}
				sb.append("]");
			}
		}
		if(stack.has(DataComponents.STORED_ENCHANTMENTS))
		{
			var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
			if(stored != null && !stored.isEmpty())
			{
				sb.append(" stored:[");
				boolean first = true;
				for(var entry : stored.entrySet())
				{
					if(!first)
						sb.append(",");
					first = false;
					String name = entry.getKey().unwrapKey()
						.map(k -> k.identifier().getPath()).orElse("?");
					sb.append(name).append("=").append(entry.getIntValue());
				}
				sb.append("]");
			}
		}
		if(stack.has(DataComponents.LORE))
		{
			var lore = stack.get(DataComponents.LORE);
			if(lore != null)
				sb.append(" loreLines:").append(lore.lines().size());
		}
		if(stack.has(DataComponents.RARITY))
			sb.append(" rarity:")
				.append(stack.get(DataComponents.RARITY).name());
		if(stack.has(DataComponents.REPAIR_COST))
			sb.append(" repairCost:")
				.append(stack.get(DataComponents.REPAIR_COST));
		if(stack.has(DataComponents.MAX_STACK_SIZE))
			sb.append(" maxStack:")
				.append(stack.get(DataComponents.MAX_STACK_SIZE));
		if(stack.has(DataComponents.UNBREAKABLE))
			sb.append(" unbreakable");
		if(stack.has(DataComponents.CUSTOM_MODEL_DATA))
			sb.append(" modelData:")
				.append(stack.get(DataComponents.CUSTOM_MODEL_DATA));
		return "ItemStack{" + sb + "}";
	}
	
	/** Detailed ItemStack → Map for JSONL output. Never uses toString(). */
	public static Map<String, Object> decodeItemStackDetailed(ItemStack stack)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		if(stack.isEmpty())
		{
			m.put("empty", true);
			return m;
		}
		m.put("item",
			BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		m.put("count", stack.getCount());
		m.put("displayName", stack.getHoverName().getString());
		
		if(stack.has(DataComponents.CUSTOM_NAME))
			m.put("customName", stack.getHoverName().getString());
		
		boolean damageable = stack.isDamageableItem();
		m.put("damageable", damageable);
		if(damageable)
		{
			m.put("damage", stack.getDamageValue());
			m.put("maxDamage", stack.getMaxDamage());
			m.put("durabilityRemaining",
				stack.getMaxDamage() - stack.getDamageValue());
		}
		
		if(stack.has(DataComponents.ENCHANTMENTS))
		{
			var enchants = stack.get(DataComponents.ENCHANTMENTS);
			if(enchants != null && !enchants.isEmpty())
			{
				Map<String, Object> em = new LinkedHashMap<>();
				for(var entry : enchants.entrySet())
				{
					String id = entry.getKey().unwrapKey()
						.map(k -> k.identifier().toString()).orElse("?");
					em.put(id, entry.getIntValue());
				}
				m.put("enchantments", em);
			}
		}
		
		if(stack.has(DataComponents.STORED_ENCHANTMENTS))
		{
			var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
			if(stored != null && !stored.isEmpty())
			{
				Map<String, Object> em = new LinkedHashMap<>();
				for(var entry : stored.entrySet())
				{
					String id = entry.getKey().unwrapKey()
						.map(k -> k.identifier().toString()).orElse("?");
					em.put(id, entry.getIntValue());
				}
				m.put("storedEnchantments", em);
			}
		}
		
		if(stack.has(DataComponents.LORE))
		{
			var lore = stack.get(DataComponents.LORE);
			if(lore != null)
			{
				List<String> lines = new java.util.ArrayList<>();
				for(var line : lore.lines())
					lines.add(line.getString());
				m.put("lore", lines);
			}
		}
		
		if(stack.has(DataComponents.RARITY))
			m.put("rarity", stack.get(DataComponents.RARITY).name());
		if(stack.has(DataComponents.REPAIR_COST))
			m.put("repairCost", stack.get(DataComponents.REPAIR_COST));
		if(stack.has(DataComponents.MAX_STACK_SIZE))
			m.put("maxStackSize", stack.get(DataComponents.MAX_STACK_SIZE));
		if(stack.has(DataComponents.UNBREAKABLE))
			m.put("unbreakable", true);
		if(stack.has(DataComponents.CUSTOM_MODEL_DATA))
			m.put("customModelData",
				stack.get(DataComponents.CUSTOM_MODEL_DATA));
		
		// Attribute modifiers
		if(stack.has(DataComponents.ATTRIBUTE_MODIFIERS))
		{
			var attrs = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
			if(attrs != null)
			{
				List<Map<String, Object>> al = new java.util.ArrayList<>();
				for(var mod : attrs.modifiers())
				{
					Map<String, Object> am = new LinkedHashMap<>();
					am.put("attribute", decode(mod.attribute()));
					am.put("amount", mod.modifier().amount());
					am.put("operation", mod.modifier().operation().name());
					am.put("slot", mod.slot().name().toLowerCase());
					al.add(am);
				}
				if(!al.isEmpty())
					m.put("attributeModifiers", al);
			}
		}
		
		// Trim
		if(stack.has(DataComponents.TRIM))
		{
			var trim = stack.get(DataComponents.TRIM);
			if(trim != null)
			{
				Map<String, Object> tm = new LinkedHashMap<>();
				tm.put("material", decode(trim.material()));
				tm.put("pattern", decode(trim.pattern()));
				m.put("trim", tm);
			}
		}
		
		// Dyed color
		if(stack.has(DataComponents.DYED_COLOR))
			m.put("dyedColor", stack.get(DataComponents.DYED_COLOR).rgb());
		
		// Food
		if(stack.has(DataComponents.FOOD))
		{
			var food = stack.get(DataComponents.FOOD);
			if(food != null)
			{
				Map<String, Object> fm = new LinkedHashMap<>();
				fm.put("nutrition", food.nutrition());
				fm.put("saturation", food.saturation());
				fm.put("canAlwaysEat", food.canAlwaysEat());
				m.put("food", fm);
			}
		}
		
		return m;
	}
	
	/** Decode a DataValue, unwrapping value() to avoid toString(). */
	public static Map<String, Object> decodeDataValue(
		SynchedEntityData.DataValue<?> dv)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", dv.id());
		try
		{
			m.put("serializer", dv.serializer().getClass().getSimpleName());
		}catch(Exception ignored)
		{}
		Object val = dv.value();
		if(val instanceof ItemStack stack)
			m.put("value", decodeItemStackDetailed(stack));
		else
			m.put("value", String.valueOf(val));
		return m;
	}
	
	/** Detailed BlockState → Map with block id and properties. */
	public static Map<String, Object> decodeBlockStateDetailed(BlockState state)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		if(state == null || state.isAir())
		{
			m.put("block", "minecraft:air");
			return m;
		}
		m.put("block",
			BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		Map<String, Object> props = new LinkedHashMap<>();
		for(var prop : state.getProperties())
			props.put(prop.getName(),
				state.getValue(prop).toString().toLowerCase());
		if(!props.isEmpty())
			m.put("properties", props);
		return m;
	}
	
	public static String decodeBlockState(BlockState state)
	{
		if(state == null || state.isAir())
			return "BlockState{air}";
		Identifier loc = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return "BlockState{" + loc + "}";
	}
	
	/** Public accessor for getRegistryName (used by PacketDumper). */
	public static Identifier getRegistryNameStatic(Object value)
	{
		return getRegistryName(value);
	}
	
	private static Identifier getRegistryName(Object value)
	{
		if(value instanceof EntityType<?> et)
			return BuiltInRegistries.ENTITY_TYPE.getKey(et);
		if(value instanceof Item item)
			return BuiltInRegistries.ITEM.getKey(item);
		if(value instanceof Block block)
			return BuiltInRegistries.BLOCK.getKey(block);
		if(value instanceof Fluid fluid)
			return BuiltInRegistries.FLUID.getKey(fluid);
		if(value instanceof SoundEvent sound)
			return BuiltInRegistries.SOUND_EVENT.getKey(sound);
		if(value instanceof ParticleType<?> particle)
			return BuiltInRegistries.PARTICLE_TYPE.getKey(particle);
		if(value instanceof GameEvent ge)
			return BuiltInRegistries.GAME_EVENT.getKey(ge);
		if(value instanceof MobEffect effect)
			return BuiltInRegistries.MOB_EFFECT.getKey(effect);
		if(value instanceof Attribute attr)
			return BuiltInRegistries.ATTRIBUTE.getKey(attr);
		return null;
	}
	
	public static String getEntityTypeName(int id)
	{
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(id);
		if(type != null)
			return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
		return "unknown_entity:" + id;
	}
	
	public static String getItemName(int id)
	{
		Item item = BuiltInRegistries.ITEM.byId(id);
		if(item != null)
			return BuiltInRegistries.ITEM.getKey(item).toString();
		return "unknown_item:" + id;
	}
	
	public static String getBlockName(int id)
	{
		Block block = BuiltInRegistries.BLOCK.byId(id);
		if(block != null)
			return BuiltInRegistries.BLOCK.getKey(block).toString();
		return "unknown_block:" + id;
	}
}
