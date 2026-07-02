/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.keymap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.wurstclient.mixinterface.IKeyMapping;

public final class KeyOccupancyScanner
{
	public Map<InputConstants.Key, List<String>> scan(Minecraft minecraft)
	{
		LinkedHashMap<InputConstants.Key, LinkedHashSet<String>> occupancy =
			new LinkedHashMap<>();
		
		if(minecraft == null || minecraft.options == null
			|| minecraft.options.keyMappings == null)
			return Map.of();
		
		for(KeyMapping mapping : minecraft.options.keyMappings)
		{
			if(mapping == null)
				continue;
			
			InputConstants.Key boundKey =
				IKeyMapping.get(mapping).getBoundKey();
			if(boundKey == null)
				continue;
			
			String keyName = boundKey.getName();
			if(keyName == null || keyName.isBlank()
				|| "key.keyboard.unknown".equalsIgnoreCase(keyName))
				continue;
			
			String controlName = getTranslatedControlName(mapping);
			if(controlName.isBlank())
				continue;
			
			occupancy
				.computeIfAbsent(boundKey, ignored -> new LinkedHashSet<>())
				.add(controlName);
		}
		
		LinkedHashMap<InputConstants.Key, List<String>> out =
			new LinkedHashMap<>();
		for(Map.Entry<InputConstants.Key, LinkedHashSet<String>> entry : occupancy
			.entrySet())
			out.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		
		return out;
	}
	
	private String getTranslatedControlName(KeyMapping mapping)
	{
		try
		{
			return Component.translatable(mapping.getName()).getString();
			
		}catch(Throwable ignored)
		{
			try
			{
				return mapping.getTranslatedKeyMessage().getString();
			}catch(Throwable ignored2)
			{}
			
			String fallback = String.valueOf(mapping);
			return fallback == null ? "" : fallback;
		}
	}
}
