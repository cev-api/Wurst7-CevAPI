/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** Stable profile references for built-in filters and global custom presets. */
public enum ItemFilterCodec
{
	;
	
	public static String encode(ItemFilter filter)
	{
		return encode(filter, null);
	}
	
	public static String encode(ItemFilter filter,
		HolderLookup.Provider registries)
	{
		if(filter instanceof ModifiedItemFilter modified)
		{
			ItemFilterModifiers m = modified.modifiers();
			return "modified|" + encode(modified.base(), registries) + "|"
				+ field(m.enchanted()) + "|" + field(m.damaged()) + "|"
				+ field(m.minimumDurabilityPercent()) + "|"
				+ field(m.minimumEnchantmentLevel()) + "|"
				+ field(m.customNamed()) + "|"
				+ field(m.requiredEnchantmentId()) + "|" + field(m.material())
				+ "|" + field(m.treasureEnchantment()) + "|" + field(m.curse());
		}
		if(filter instanceof BuiltInItemFilter builtIn)
			return "builtin:" + builtIn.name();
		if(filter instanceof CustomItemFilterPreset preset)
			return "preset:" + preset.getName();
		if(filter instanceof ExactItemFilter exact)
		{
			if(registries == null)
				return "exact:"
					+ BuiltInRegistries.ITEM.getKey(exact.key().item());
			return ItemStack.CODEC
				.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries),
					exact.exemplar())
				.result()
				.map(value -> "exactjson:"
					+ Base64.getUrlEncoder().withoutPadding().encodeToString(
						value.toString().getBytes(StandardCharsets.UTF_8)))
				.orElse("exact:"
					+ BuiltInRegistries.ITEM.getKey(exact.key().item()));
		}
		return "builtin:" + BuiltInItemFilter.ALL.name();
	}
	
	public static ItemFilter decode(String token)
	{
		return decode(token, null);
	}
	
	public static ItemFilter decode(String token,
		HolderLookup.Provider registries)
	{
		if(token != null && token.startsWith("modified|"))
		{
			String[] fields = token.split("\\|", -1);
			if(fields.length == 11)
				return new ModifiedItemFilter(decode(fields[1], registries),
					new ItemFilterModifiers(booleanField(fields[2]),
						booleanField(fields[3]), integerField(fields[4]),
						integerField(fields[5]), booleanField(fields[6]),
						emptyToNull(fields[7]), emptyToNull(fields[8]),
						booleanField(fields[9]), booleanField(fields[10])));
			if(fields.length == 10)
				return new ModifiedItemFilter(decode(fields[1], registries),
					new ItemFilterModifiers(booleanField(fields[2]),
						booleanField(fields[3]), integerField(fields[4]),
						integerField(fields[5]), booleanField(fields[6]),
						emptyToNull(fields[7]), null, booleanField(fields[8]),
						booleanField(fields[9])));
			if(fields.length == 7)
				return new ModifiedItemFilter(decode(fields[1], registries),
					new ItemFilterModifiers(booleanField(fields[2]),
						booleanField(fields[3]), integerField(fields[4]),
						integerField(fields[5]), booleanField(fields[6])));
		}
		if(token != null && token.startsWith("builtin:"))
			try
			{
				return BuiltInItemFilter
					.valueOf(token.substring("builtin:".length()));
			}catch(IllegalArgumentException ignored)
			{}
		if(token != null && token.startsWith("preset:"))
		{
			String name = token.substring("preset:".length());
			return new CustomPresetStore().load().stream()
				.filter(preset -> preset.getName().equals(name))
				.<ItemFilter> map(preset -> preset).findFirst()
				.orElse(BuiltInItemFilter.ALL);
		}
		if(token != null && token.startsWith("exact:"))
			try
			{
				ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(
					Identifier.parse(token.substring("exact:".length()))));
				return stack.isEmpty() ? BuiltInItemFilter.ALL
					: new ExactItemFilter(ItemStackEquivalenceKey.of(stack),
						stack.getHoverName().getString());
			}catch(RuntimeException e)
			{}
		if(token != null && token.startsWith("exactjson:")
			&& registries != null)
			try
			{
				String json = new String(
					Base64.getUrlDecoder()
						.decode(token.substring("exactjson:".length())),
					StandardCharsets.UTF_8);
				return ItemStack.CODEC
					.parse(RegistryOps.create(JsonOps.INSTANCE, registries),
						JsonParser.parseString(json))
					.result().map(ExactItemFilter::new)
					.<ItemFilter> map(filter -> filter)
					.orElse(BuiltInItemFilter.ALL);
			}catch(RuntimeException e)
			{}
		return BuiltInItemFilter.ALL;
	}
	
	private static String field(Object value)
	{
		return value == null ? "" : value.toString();
	}
	
	private static Boolean booleanField(String value)
	{
		return value == null || value.isEmpty() ? null : Boolean.valueOf(value);
	}
	
	private static Integer integerField(String value)
	{
		try
		{
			return value == null || value.isEmpty() ? null
				: Integer.valueOf(value);
		}catch(NumberFormatException e)
		{
			return null;
		}
	}
	
	private static String emptyToNull(String value)
	{
		return value == null || value.isEmpty() ? null : value;
	}
}
