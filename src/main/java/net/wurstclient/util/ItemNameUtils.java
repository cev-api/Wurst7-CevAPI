/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Arrays;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class ItemNameUtils
{
	private ItemNameUtils()
	{}
	
	public static String buildEnchantmentName(Identifier id, String path)
	{
		if(path == null || path.isEmpty())
			return "Unknown Enchant";
		String namespace = id != null ? id.getNamespace() : "minecraft";
		String key = "enchantment." + namespace + "." + path;
		String translated = Component.translatable(key).getString();
		if(translated.equals(key))
			return humanize(path);
		return translated;
	}
	
	public static String buildEffectName(Identifier id, String path)
	{
		if(path == null || path.isEmpty())
			return "Unknown Effect";
		String namespace = id != null ? id.getNamespace() : "minecraft";
		String key = "effect." + namespace + "." + path;
		String translated = Component.translatable(key).getString();
		if(translated.equals(key))
			return humanize(path);
		return translated;
	}
	
	public static String buildPotionName(Identifier id, String path)
	{
		if(path == null || path.isEmpty())
			return "Unknown Potion";
		String namespace = id != null ? id.getNamespace() : "minecraft";
		String key = "potion." + namespace + "." + path;
		String translated = Component.translatable(key).getString();
		if(translated.equals(key))
			return humanize(path);
		return translated;
	}
	
	public static String humanize(String path)
	{
		if(path == null || path.isEmpty())
			return "Unknown";
		String humanized =
			Arrays.stream(path.split("_")).filter(part -> !part.isEmpty())
				.map(part -> Character.toUpperCase(part.charAt(0))
					+ (part.length() > 1 ? part.substring(1) : ""))
				.collect(Collectors.joining(" "));
		return humanized.isEmpty() ? "Unknown" : humanized;
	}
	
	public static String sanitizePath(String raw)
	{
		if(raw == null || raw.isEmpty())
			return "";
		int colon = raw.indexOf(':');
		return colon >= 0 && colon + 1 < raw.length() ? raw.substring(colon + 1)
			: raw;
	}
}
