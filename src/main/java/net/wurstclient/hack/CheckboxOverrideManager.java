/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hack;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import net.wurstclient.settings.CheckboxSetting;

/**
 * Utility to capture, apply, and restore {@link CheckboxSetting} values across
 * all hacks in {@link HackList} that expose a given field name.
 */
public enum CheckboxOverrideManager
{
	;
	
	public static LinkedHashMap<CheckboxSetting, Boolean> capture(HackList hax,
		String fieldName)
	{
		LinkedHashMap<CheckboxSetting, Boolean> snapshot =
			new LinkedHashMap<>();
		
		for(Hack hack : hax.getAllHax())
		{
			CheckboxSetting setting = getCheckboxSetting(hack, fieldName);
			if(setting == null)
				continue;
			
			snapshot.put(setting, setting.isChecked());
		}
		
		return snapshot;
	}
	
	public static void apply(HackList hax, String fieldName, boolean value)
	{
		for(Hack hack : hax.getAllHax())
		{
			CheckboxSetting setting = getCheckboxSetting(hack, fieldName);
			if(setting != null)
				setting.setChecked(value);
		}
	}
	
	public static void restore(Map<CheckboxSetting, Boolean> snapshot)
	{
		if(snapshot == null || snapshot.isEmpty())
			return;
		
		for(Map.Entry<CheckboxSetting, Boolean> entry : snapshot.entrySet())
		{
			CheckboxSetting setting = entry.getKey();
			if(setting == null)
				continue;
			
			setting.setChecked(entry.getValue());
		}
	}
	
	private static CheckboxSetting getCheckboxSetting(Hack hack,
		String fieldName)
	{
		try
		{
			Field field = findField(hack.getClass(), fieldName);
			if(field == null)
				return null;
			
			field.setAccessible(true);
			Object value = field.get(hack);
			if(value instanceof CheckboxSetting cs)
				return cs;
			
		}catch(IllegalAccessException ignored)
		{}
		
		return null;
	}
	
	private static Field findField(Class<?> cls, String name)
	{
		Class<?> current = cls;
		
		while(current != null && current != Object.class)
		{
			try
			{
				return current.getDeclaredField(name);
			}catch(NoSuchFieldException e)
			{
				current = current.getSuperclass();
			}
		}
		
		return null;
	}
}
