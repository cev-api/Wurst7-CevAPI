/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hack;

import java.lang.reflect.Field;

import net.wurstclient.settings.CheckboxSetting;
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
				Field f = findField(h.getClass(), "onlyAboveGround");
				if(f != null)
				{
					f.setAccessible(true);
					Object val = f.get(h);
					if(val instanceof CheckboxSetting cs)
						cs.setChecked(enabled);
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
				Field f = findField(h.getClass(), "aboveGroundY");
				if(f != null)
				{
					f.setAccessible(true);
					Object val = f.get(h);
					if(val instanceof SliderSetting ss)
						ss.setValue(y);
				}
			}catch(Throwable ignored)
			{}
		}
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
