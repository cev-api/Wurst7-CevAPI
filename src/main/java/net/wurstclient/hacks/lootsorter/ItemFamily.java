/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Stable, deliberately conservative families used by frame-based sorting. */
public enum ItemFamily
{
	;
	
	private static final List<String> MATERIALS = List.of("oak", "spruce",
		"birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo",
		"crimson", "warped", "stone", "cobblestone", "deepslate", "blackstone",
		"sandstone", "red_sandstone", "prismarine", "purpur", "quartz",
		"amethyst", "copper", "iron", "gold", "diamond", "emerald", "netherite",
		"redstone", "lapis", "coal", "terracotta", "concrete", "wool", "glass",
		"ice", "snow", "dirt", "sand", "gravel", "clay", "brick", "mud",
		"netherrack", "end_stone");
	private static final List<String> COLORS = List.of("white", "orange",
		"magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
		"cyan", "purple", "blue", "brown", "green", "red", "black");
	private static final List<String> VARIANT_PREFIXES =
		List.of("stripped_", "waxed_", "exposed_", "weathered_", "oxidized_",
			"cut_", "smooth_", "polished_", "chiseled_", "cracked_", "mossy_");
	
	public static String of(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return "empty";
		var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
		if(key == null)
			return "unknown";
		String path = key.getPath().toLowerCase(Locale.ROOT);
		String normalized = path;
		boolean changed;
		do
		{
			changed = false;
			for(String prefix : VARIANT_PREFIXES)
				if(normalized.startsWith(prefix))
				{
					normalized = normalized.substring(prefix.length());
					changed = true;
					break;
				}
		}while(changed && !normalized.isEmpty());
		
		for(String material : MATERIALS)
			if(normalized.equals(material)
				|| normalized.startsWith(material + "_"))
				return "material:" + material;
		for(String color : COLORS)
			if(normalized.equals(color) || normalized.startsWith(color + "_"))
				return "color:" + color;
		return "item:" + path;
	}
	
	public static boolean matches(ItemStack exemplar, ItemStack candidate)
	{
		return !candidate.isEmpty() && of(exemplar).equals(of(candidate));
	}
}
