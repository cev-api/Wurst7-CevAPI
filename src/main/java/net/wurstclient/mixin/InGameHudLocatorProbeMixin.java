/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.hud.bar.LocatorBar;
import net.minecraft.client.render.RenderTickCounter;

@Mixin(InGameHud.class)
public class InGameHudLocatorProbeMixin
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("WurstLocator");
	private static long lastProbeLogMs = 0L;
	
	@Inject(
		method = "renderPlayerList(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
		at = @At("HEAD"))
	private void onAnyHudRender(DrawContext ctx, RenderTickCounter rtc,
		CallbackInfo ci)
	{
		long now = System.currentTimeMillis();
		if(now - lastProbeLogMs < 5000)
			return;
		lastProbeLogMs = now;
		
		Object self = this; // transformed InGameHud instance
		Class<?> cls = self.getClass();
		while(cls != null && cls != Object.class)
		{
			for(Field f : cls.getDeclaredFields())
			{
				try
				{
					f.setAccessible(true);
					Object v = f.get(self);
					if(v == null)
						continue;
					if(v instanceof Map<?, ?> map)
					{
						// Look for LocatorBar among values
						int count = 0;
						for(Object val : map.values())
						{
							if(val instanceof LocatorBar)
							{
								count++;
								reflectLocatorBar(val);
							}
						}
						if(count > 0)
						{
							LOGGER.info(
								"[WurstLocator] InGameHud bars map '{}' contains {} LocatorBar instance(s)",
								f.getName(), count);
						}
					}
				}catch(Throwable ignore)
				{}
			}
			cls = cls.getSuperclass();
		}
	}
	
	private static void reflectLocatorBar(Object locatorBar)
	{
		try
		{
			Class<?> c = locatorBar.getClass();
			int logged = 0;
			while(c != null && c != Object.class)
			{
				for(Field f : c.getDeclaredFields())
				{
					try
					{
						f.setAccessible(true);
						Object v = f.get(locatorBar);
						String type = f.getType().getName();
						String summary = summarizeValue(v, 4);
						LOGGER.info(
							"[WurstLocator] LB field={} type={} value= {}",
							f.getName(), type, summary);
						// Extra: if collection/map/array print element class
						// samples
						verboseElements(f.getName(), v);
						logged++;
					}catch(Throwable ignore)
					{}
				}
				c = c.getSuperclass();
			}
			if(logged == 0)
				LOGGER.info("[WurstLocator] LB no fields via reflection");
		}catch(Throwable t)
		{
			LOGGER.warn("[WurstLocator] LB reflect error: {}", t.toString());
		}
	}
	
	private static void verboseElements(String fieldName, Object v)
	{
		try
		{
			int limit = 6;
			if(v instanceof Collection<?> col)
			{
				int i = 0;
				for(Object e : col)
				{
					if(i++ >= limit)
						break;
					LOGGER.info(
						"[WurstLocator] LB verbose field={} elemClass={} sample={}",
						fieldName, e == null ? "null" : e.getClass().getName(),
						pretty(e));
				}
				if(col.size() > limit)
					LOGGER.info(
						"[WurstLocator] LB verbose field={} has {}+ elements",
						fieldName, col.size());
			}else if(v instanceof Map<?, ?> map)
			{
				int i = 0;
				for(Map.Entry<?, ?> en : map.entrySet())
				{
					if(i++ >= limit)
						break;
					Object val = en.getValue();
					LOGGER.info(
						"[WurstLocator] LB verbose field={} map valClass={} sample={}",
						fieldName,
						val == null ? "null" : val.getClass().getName(),
						pretty(val));
				}
			}else if(v != null && v.getClass().isArray())
			{
				int len = Array.getLength(v);
				for(int i = 0; i < Math.min(len, limit); i++)
				{
					Object e = Array.get(v, i);
					LOGGER.info(
						"[WurstLocator] LB verbose field={} elemClass={} sample={}",
						fieldName, e == null ? "null" : e.getClass().getName(),
						pretty(e));
				}
				if(len > limit)
					LOGGER.info(
						"[WurstLocator] LB verbose field={} has {}+ elements",
						fieldName, len);
			}
		}catch(Throwable ignore)
		{}
	}
	
	private static String summarizeValue(Object v, int limit)
	{
		if(v == null)
			return "null";
		try
		{
			if(v instanceof Collection<?> col)
				return "Collection(size=" + col.size() + ", sample="
					+ sampleCollection(col, limit) + ")";
			if(v instanceof Map<?, ?> map)
				return "Map(size=" + map.size() + ", sampleKeys="
					+ sampleCollection(map.keySet(), limit) + ")";
			if(v.getClass().isArray())
			{
				int len = Array.getLength(v);
				StringBuilder sb = new StringBuilder();
				sb.append("Array(len=").append(len).append(", sample=[");
				for(int i = 0; i < Math.min(len, limit); i++)
				{
					if(i > 0)
						sb.append(',');
					Object e = Array.get(v, i);
					sb.append(pretty(e));
				}
				if(len > limit)
					sb.append(",...");
				sb.append("])");
				return sb.toString();
			}
			String s = String.valueOf(v);
			if(s.length() > 200)
				s = s.substring(0, 200) + "...";
			return s;
		}catch(Throwable t)
		{
			return v.getClass().getName();
		}
	}
	
	private static String sampleCollection(Collection<?> col, int limit)
	{
		StringBuilder sb = new StringBuilder("[");
		int i = 0;
		Iterator<?> it = col.iterator();
		while(it.hasNext() && i < limit)
		{
			Object e = it.next();
			if(i++ > 0)
				sb.append(',');
			sb.append(pretty(e));
		}
		if(col.size() > limit)
			sb.append(",...");
		sb.append(']');
		return sb.toString();
	}
	
	private static String pretty(Object o)
	{
		if(o == null)
			return "null";
		try
		{
			String s = String.valueOf(o);
			if(s.length() > 200)
				s = s.substring(0, 200) + "...";
			return s;
		}catch(Throwable t)
		{
			return o.getClass().getName();
		}
	}
}
