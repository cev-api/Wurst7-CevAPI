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
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** Serializes full item stacks for source-cache persistence. */
public enum ItemStackSnapshotCodec
{
	;
	
	public static String encode(ItemStack stack,
		HolderLookup.Provider registries)
	{
		if(stack == null || stack.isEmpty() || registries == null)
			return null;
		return ItemStack.CODEC
			.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries),
				stack)
			.result()
			.map(
				value -> Base64.getUrlEncoder().withoutPadding().encodeToString(
					value.toString().getBytes(StandardCharsets.UTF_8)))
			.orElse(null);
	}
	
	public static ItemStack decode(String token,
		HolderLookup.Provider registries)
	{
		if(token == null || token.isBlank() || registries == null)
			return ItemStack.EMPTY;
		try
		{
			String json = new String(Base64.getUrlDecoder().decode(token),
				StandardCharsets.UTF_8);
			return ItemStack.CODEC
				.parse(RegistryOps.create(JsonOps.INSTANCE, registries),
					JsonParser.parseString(json))
				.result().orElse(ItemStack.EMPTY);
		}catch(RuntimeException e)
		{
			return ItemStack.EMPTY;
		}
	}
}
