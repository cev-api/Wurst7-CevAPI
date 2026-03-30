/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hack;

import java.lang.reflect.Field;

import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SliderSetting;

/**
 * Utility to toggle or set the above-ground filter settings on hacks that
 * expose them. Uses reflection to avoid tightly coupling hack classes.
 */
public final class AboveGroundFilterManager
{
	public static void toggle(HackList hacks, boolean enabled)
	{
		for(Hack h : hacks.getAllHax())
		{
			try
			{
				CheckboxSetting setting = getCheckbox(h, "onlyAboveGround");
				if(setting != null)
				{
					setting.setChecked(enabled);
					invalidateCachedEspResults(h);
				}
			}catch(Throwable ignored)
			{}
		}
	}
	
	public static void setY(HackList hacks, int y)
	{
		for(Hack h : hacks.getAllHax())
		{
			try
			{
				SliderSetting setting = getSlider(h, "aboveGroundY");
				if(setting != null)
				{
					setting.setValue(y);
					invalidateCachedEspResults(h);
				}
			}catch(Throwable ignored)
			{}
		}
	}
	
	private static void invalidateCachedEspResults(Hack hack)
	{
		setBooleanField(hack, "groupsUpToDate", false);
		setBooleanField(hack, "bufferUpToDate", false);
		setBooleanField(hack, "highlightPositionsUpToDate", false);
		setBooleanField(hack, "visibleBoxesUpToDate", false);
	}
	
	private static void setBooleanField(Hack hack, String fieldName,
		boolean value)
	{
		try
		{
			Field field = findField(hack.getClass(), fieldName);
			if(field == null || field.getType() != boolean.class)
				return;
			
			field.setAccessible(true);
			field.setBoolean(hack, value);
		}catch(Throwable ignored)
		{}
	}
	
	private static CheckboxSetting getCheckbox(Hack hack, String fieldName)
	{
		try
		{
			Field f = findField(hack.getClass(), fieldName);
			if(f != null)
			{
				f.setAccessible(true);
				Object val = f.get(hack);
				if(val instanceof CheckboxSetting cs)
					return cs;
			}
		}catch(Throwable ignored)
		{}
		
		String wanted = normalize(fieldName);
		for(Setting setting : hack.getSettings().values())
		{
			if(!(setting instanceof CheckboxSetting cs))
				continue;
			if(normalize(setting.getName()).equals(wanted))
				return cs;
		}
		
		if("onlyaboveground".equals(wanted))
			for(Setting setting : hack.getSettings().values())
			{
				if(!(setting instanceof CheckboxSetting cs))
					continue;
				if("abovegroundonly".equals(normalize(setting.getName())))
					return cs;
			}
		
		return null;
	}
	
	private static SliderSetting getSlider(Hack hack, String fieldName)
	{
		try
		{
			Field f = findField(hack.getClass(), fieldName);
			if(f != null)
			{
				f.setAccessible(true);
				Object val = f.get(hack);
				if(val instanceof SliderSetting ss)
					return ss;
			}
		}catch(Throwable ignored)
		{}
		
		String wanted = normalize(fieldName);
		for(Setting setting : hack.getSettings().values())
		{
			if(!(setting instanceof SliderSetting ss))
				continue;
			if(normalize(setting.getName()).equals(wanted))
				return ss;
		}
		
		if("abovegroundy".equals(wanted))
			for(Setting setting : hack.getSettings().values())
			{
				if(!(setting instanceof SliderSetting ss))
					continue;
				if("setespylimit".equals(normalize(setting.getName())))
					return ss;
			}
		
		return null;
	}
	
	private static String normalize(String name)
	{
		if(name == null)
			return "";
		
		StringBuilder normalized = new StringBuilder(name.length());
		for(int i = 0; i < name.length(); i++)
		{
			char c = name.charAt(i);
			if(Character.isLetterOrDigit(c))
				normalized.append(Character.toLowerCase(c));
		}
		
		return normalized.toString();
	}
	
	private static Field findField(Class<?> cls, String name)
	{
		Class<?> c = cls;
		while(c != null && c != Object.class)
		{
			try
			{
				Field f = c.getDeclaredField(name);
				return f;
			}catch(NoSuchFieldException e)
			{
				c = c.getSuperclass();
			}
		}
		return null;
	}
}
