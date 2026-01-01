/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerColorRegistry
{
	private PlayerColorRegistry()
	{}
	
	private static final Map<UUID, Color> colorMap = new HashMap<>();
	private static final Map<UUID, String> ownerMap = new HashMap<>();
	
	// Assign color if none exists. Returns the color assigned.
	public static synchronized Color assignIfAbsent(UUID player, Color color,
		String owner)
	{
		if(!colorMap.containsKey(player))
		{
			colorMap.put(player, color);
			ownerMap.put(player, owner);
			return color;
		}
		return colorMap.get(player);
	}
	
	// Force assign (take ownership)
	public static synchronized void forceAssign(UUID player, Color color,
		String owner)
	{
		colorMap.put(player, color);
		ownerMap.put(player, owner);
	}
	
	public static synchronized Color get(UUID player)
	{
		return colorMap.get(player);
	}
	
	public static synchronized void remove(UUID player)
	{
		colorMap.remove(player);
		ownerMap.remove(player);
	}
	
	public static synchronized void clear()
	{
		colorMap.clear();
		ownerMap.clear();
	}
	
	public static synchronized String getOwner(UUID player)
	{
		return ownerMap.get(player);
	}
	
	/**
	 * Remove all registry entries owned by the given owner string.
	 * Useful when a feature that owned colors is disabled and we want to
	 * relinquish ownership so other features can assign colors.
	 */
	public static synchronized void removeByOwner(String owner)
	{
		var it = ownerMap.entrySet().iterator();
		while(it.hasNext())
		{
			var e = it.next();
			if(owner.equals(e.getValue()))
			{
				UUID key = e.getKey();
				it.remove();
				colorMap.remove(key);
			}
		}
	}
	
	// Generate a bright color from a palette index
	public static Color generateBrightColor(int index)
	{
		// Brighter, more saturated palette
		Color[] base = new Color[]{new Color(255, 0, 0), // red
			new Color(0, 255, 0), // green
			new Color(0, 0, 255), // blue
			new Color(255, 255, 0), // yellow
			new Color(255, 0, 255), // magenta
			new Color(0, 255, 255), // cyan
			new Color(255, 128, 0), // orange
			new Color(128, 255, 0), // lime
			new Color(255, 0, 128), // pink
			new Color(0, 128, 255), // sky
			new Color(128, 0, 255), // purple
			new Color(0, 255, 128) // aqua-lime
		};
		int baseCount = base.length;
		int b = Math.abs(index) % baseCount;
		int round = Math.abs(index) / baseCount;
		// Slightly vary brightness by round while keeping colors vibrant
		float factor = 1.0f - Math.min(0.2f, round * 0.04f);
		Color bc = base[b];
		int r = Math.min(255, Math.max(0, (int)(bc.getRed() * factor)));
		int g = Math.min(255, Math.max(0, (int)(bc.getGreen() * factor)));
		int bl = Math.min(255, Math.max(0, (int)(bc.getBlue() * factor)));
		return new Color(r, g, bl);
	}
	
	// Assign a generated bright color if absent
	public static synchronized Color assignGeneratedIfAbsent(UUID player,
		int index, String owner)
	{
		Color existing = colorMap.get(player);
		if(existing != null)
			return existing;
		Color c = generateBrightColor(index);
		colorMap.put(player, c);
		ownerMap.put(player, owner);
		return c;
	}
	
	/**
	 * Deterministically generate and assign a color for the player based on
	 * their UUID. This yields the same color for a given UUID across callers
	 * and helps keep colors unified between hacks.
	 */
	public static synchronized Color assignDeterministic(UUID player,
		String owner)
	{
		Color existing = colorMap.get(player);
		if(existing != null)
			return existing;
		int idx = player.hashCode();
		Color c = generateBrightColor(idx);
		colorMap.put(player, c);
		ownerMap.put(player, owner);
		return c;
	}
}
