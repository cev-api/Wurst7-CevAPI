/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.locator;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.contextualbar.LocatorBarRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LocatorBarRenderer.class)
public class LocatorBarMixin
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("WurstLocator");
	private static long lastLogMs = 0L;
	private static boolean mixinAppliedLogged = false;
	
	// Inject after vanilla finishes rendering addons to snapshot entries for
	// this frame
	@Inject(
		method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
		at = @At("TAIL"))
	private void onRenderAddons(GuiGraphics ctx, DeltaTracker rtc,
		CallbackInfo ci)
	{
		// Log once to confirm mixin was executed in-game
		if(!mixinAppliedLogged)
		{
			mixinAppliedLogged = true;
			LOGGER.info("[WurstLocator] mixin applied");
		}
		// Throttle logging to once every 5 seconds
		long now = System.currentTimeMillis();
		if(now - lastLogMs < 5000)
			return;
		lastLogMs = now;
		
		try
		{
			// Introspect fields on the target class (LocatorBar)
			Class<?> cls = this.getClass();
			int logged = 0;
			while(cls != null && cls != Object.class)
			{
				for(Field f : cls.getDeclaredFields())
				{
					try
					{
						f.setAccessible(true);
						Object v = f.get(this);
						String type = f.getType().getName();
						String summary = summarizeValue(v, 4);
						LOGGER.info("[WurstLocator] field={} type={} value={}",
							f.getName(), type, summary);
						logged++;
					}catch(Throwable ignore)
					{
						// ignore
					}
				}
				cls = cls.getSuperclass();
			}
			if(logged == 0)
				LOGGER
					.info("[WurstLocator] no fields discovered via reflection");
				
			// Additional verbose inspection: for any non-null field try to
			// print
			// element class names and a short toString() sample so we can find
			// the real locator entries when they appear.
			for(Field fOuter : this.getClass().getDeclaredFields())
			{
				try
				{
					fOuter.setAccessible(true);
					Object v = fOuter.get(this);
					if(v == null)
						continue;
					// limit verbose output per field
					int verboseLimit = 6;
					if(v instanceof Collection<?> col)
					{
						int i = 0;
						for(Object e : col)
						{
							if(i++ >= verboseLimit)
								break;
							LOGGER.info(
								"[WurstLocator] verbose field={} elemClass={} sample={}",
								fOuter.getName(),
								e == null ? "null" : e.getClass().getName(),
								pretty(e));
						}
						if(col.size() > verboseLimit)
							LOGGER.info(
								"[WurstLocator] verbose field={} has {}+ elements",
								fOuter.getName(), col.size());
						continue;
					}
					if(v.getClass().isArray())
					{
						int len = Array.getLength(v);
						for(int i = 0; i < Math.min(len, verboseLimit); i++)
						{
							Object e = Array.get(v, i);
							LOGGER.info(
								"[WurstLocator] verbose field={} elemClass={} sample={}",
								fOuter.getName(),
								e == null ? "null" : e.getClass().getName(),
								pretty(e));
						}
						if(len > verboseLimit)
							LOGGER.info(
								"[WurstLocator] verbose field={} has {}+ elements",
								fOuter.getName(), len);
						continue;
					}
					if(v instanceof Map<?, ?> map)
					{
						int i = 0;
						for(Map.Entry<?, ?> en : map.entrySet())
						{
							if(i++ >= verboseLimit)
								break;
							Object key = en.getKey();
							Object val = en.getValue();
							LOGGER.info(
								"[WurstLocator] verbose field={} mapEntry keyClass={} valClass={} valSample={}",
								fOuter.getName(),
								key == null ? "null" : key.getClass().getName(),
								val == null ? "null" : val.getClass().getName(),
								pretty(val));
						}
						continue;
					}
					// Non-collection object: log its class and toString() if
					// informative
					String s = String.valueOf(v);
					if(s.length() > 0
						&& (s.length() < 200 || s.toLowerCase().contains("name")
							|| s.toLowerCase().contains("uuid")
							|| s.toLowerCase().contains("distance")
							|| s.toLowerCase().contains("pos")))
					{
						LOGGER.info(
							"[WurstLocator] verbose field={} class={} toString={}",
							fOuter.getName(), v.getClass().getName(),
							s.length() > 400 ? s.substring(0, 400) + "..." : s);
					}
				}catch(Throwable ignore)
				{
					// ignore per-field errors
				}
			}
		}catch(Throwable t)
		{
			LOGGER.warn("[WurstLocator] introspect error: {}", t.toString());
		}
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
			Class<?> c = v.getClass();
			if(c.isArray())
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
			if(s.length() > 120)
				s = s.substring(0, 120) + "...";
			return s;
		}catch(Throwable t)
		{
			return o.getClass().getName();
		}
	}
	
	// Also hook the main bar render as a fallback point each frame
	@Inject(
		method = "renderBackground(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
		at = @At("TAIL"))
	private void onRenderBar(GuiGraphics ctx, DeltaTracker rtc, CallbackInfo ci)
	{
		// Keep data store from going stale until we map exact fields
		// LocatorDataStore may not be present in this build; guard usage
		try
		{
			Class<?> cls =
				Class.forName("net.wurstclient.locator.LocatorDataStore");
			java.lang.reflect.Method getEntries = cls.getMethod("getEntries");
			Object entries = getEntries.invoke(null);
			boolean empty = true;
			if(entries instanceof java.util.Collection)
				empty = ((java.util.Collection<?>)entries).isEmpty();
			if(!empty)
				return;
			java.lang.reflect.Method clear = cls.getMethod("clear");
			clear.invoke(null);
		}catch(Throwable ignored)
		{}
	}
}
