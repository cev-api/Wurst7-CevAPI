/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

@Mixin(targets = "com.terraformersmc.modmenu.gui.ModsScreen", remap = false)
public abstract class ModMenuScreenMixin
{
	private static final String[] WURST_MOD_IDS = {"wurst", "nicewurst"};
	private static final String[] ENTRY_CLASS_NAMES =
		{"com.terraformersmc.modmenu.gui.widget.entries.ModListEntry",
			"com.terraformersmc.modmenu.gui.widget.ModListWidget$ModEntry",
			"com.terraformersmc.modmenu.gui.widget.ModListWidget$Entry",
			"com.terraformersmc.modmenu.gui.widget.ModListWidget$ModListEntry"};
	private static volatile Class<?> detectedEntryClass;
	private static final int MAX_REFILTER_ATTEMPTS = 40;
	private boolean wurst$needsRefilter = true;
	private int wurst$refilterAttempts = 0;
	
	@Inject(method = "init()V", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenu(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		wurst$needsRefilter = true;
		wurst$refilterAttempts = 0;
		tryFilter();
	}
	
	@Inject(method = "method_25426()V", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuInit(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		wurst$needsRefilter = true;
		wurst$refilterAttempts = 0;
		tryFilter();
	}
	
	@Inject(method = "tick()V", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuTick(CallbackInfo ci)
	{
		if(!wurst$needsRefilter
			|| wurst$refilterAttempts >= MAX_REFILTER_ATTEMPTS)
			return;
		
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		wurst$refilterAttempts++;
		tryFilter();
	}
	
	@Inject(method = "method_25419()V", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuTickObf(CallbackInfo ci)
	{
		if(!wurst$needsRefilter
			|| wurst$refilterAttempts >= MAX_REFILTER_ATTEMPTS)
			return;
		
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		wurst$refilterAttempts++;
		tryFilter();
	}
	
	private void tryFilter()
	{
		boolean removed = false;
		Object listWidget = findModListWidget(this);
		if(listWidget != null)
		{
			ensureEntryClassDetected(listWidget);
			removed |= removeFromCollections(listWidget, WURST_MOD_IDS);
			removed |= removeFromChildren(listWidget, WURST_MOD_IDS);
		}
		
		removed |= removeFromScreenFields(this, WURST_MOD_IDS);
		
		if(removed)
			wurst$needsRefilter = false;
	}
	
	private static Object findModListWidget(Object screen)
	{
		for(Class<?> type = screen.getClass(); type != null; type =
			type.getSuperclass())
		{
			for(Field field : type.getDeclaredFields())
			{
				try
				{
					Class<?> fieldType = field.getType();
					String typeName = fieldType.getName();
					if(!typeName.contains("ModListWidget")
						&& !AbstractSelectionList.class
							.isAssignableFrom(fieldType))
						continue;
					
					field.setAccessible(true);
					Object value = field.get(screen);
					if(value != null)
						return value;
					
				}catch(Throwable ignored)
				{}
			}
		}
		
		return null;
	}
	
	private static boolean removeFromChildren(Object widget, String[] modIds)
	{
		Object children = tryCall(widget, "children");
		if(children instanceof Collection<?> collection)
			return removeMatchingEntries(collection, modIds);
		return false;
	}
	
	private static boolean removeFromCollections(Object widget, String[] modIds)
	{
		boolean removed = false;
		for(Class<?> type = widget.getClass(); type != null; type =
			type.getSuperclass())
		{
			for(Field field : type.getDeclaredFields())
			{
				try
				{
					if(!Collection.class.isAssignableFrom(field.getType()))
						continue;
					
					field.setAccessible(true);
					Object value = field.get(widget);
					if(!(value instanceof Collection<?> collection))
						continue;
					
					if(removeMatchingEntries(collection, modIds))
						removed = true;
					
				}catch(Throwable ignored)
				{}
			}
		}
		
		return removed;
	}
	
	private static boolean removeFromScreenFields(Object screen,
		String[] modIds)
	{
		boolean removed = false;
		for(Class<?> type = screen.getClass(); type != null; type =
			type.getSuperclass())
		{
			for(Field field : type.getDeclaredFields())
			{
				try
				{
					field.setAccessible(true);
					Object value = field.get(screen);
					if(value == null)
						continue;
					
					if(value instanceof Collection<?> collection)
						removed |= removeMatchingEntries(collection, modIds);
					else if(value instanceof Map<?, ?> map)
						removed |= removeMatchingFromMap(map, modIds);
				}catch(Throwable ignored)
				{}
			}
		}
		
		return removed;
	}
	
	private static boolean removeMatchingFromMap(Map<?, ?> map, String[] modIds)
	{
		int size = map.size();
		try
		{
			map.entrySet()
				.removeIf(entry -> isMatchingMod(entry.getKey(), modIds)
					|| isMatchingMod(entry.getValue(), modIds));
			return map.size() != size;
		}catch(Throwable ignored)
		{
			return false;
		}
	}
	
	private static boolean removeMatchingEntries(Collection<?> list,
		String[] modIds)
	{
		int size = list.size();
		try
		{
			list.removeIf(entry -> isMatchingMod(entry, modIds));
			return list.size() != size;
		}catch(Throwable ignored)
		{
			return false;
		}
	}
	
	private static boolean isMatchingMod(Object entry, String[] modIds)
	{
		if(entry == null)
			return false;
		
		if(detectedEntryClass != null && !detectedEntryClass.isInstance(entry))
			return false;
		
		if(shouldHideFromKeywordList(entry))
			return true;
		
		String id = extractModId(entry);
		if(id == null)
		{
			String name = extractDisplayName(entry);
			return name != null && name.toLowerCase().contains("wurst");
		}
		
		for(String modId : modIds)
			if(id.equalsIgnoreCase(modId))
				return true;
			
		return false;
	}
	
	private static void ensureEntryClassDetected(Object widget)
	{
		if(detectedEntryClass != null)
			return;
		
		for(String className : ENTRY_CLASS_NAMES)
		{
			try
			{
				detectedEntryClass = Class.forName(className);
				return;
			}catch(Throwable ignored)
			{}
		}
		
		Object children = tryCall(widget, "children");
		if(children instanceof Collection<?> collection)
		{
			for(Object entry : collection)
				if(entry != null)
				{
					detectedEntryClass = entry.getClass();
					return;
				}
		}
	}
	
	private static boolean shouldHideFromKeywordList(Object entry)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null || hax.hideModMenuHack == null
			|| !hax.hideModMenuHack.isEnabled())
			return false;
		
		Set<String> keywords = hax.hideModMenuHack.getKeywords();
		if(keywords.isEmpty())
			return false;
		
		String id = extractModId(entry);
		String name = extractDisplayName(entry);
		String haystack = (id == null ? "" : id.toLowerCase()) + " "
			+ (name == null ? "" : name.toLowerCase());
		
		for(String keyword : keywords)
			if(haystack.contains(keyword))
				return true;
			
		return false;
	}
	
	private static String extractModId(Object entry)
	{
		Set<Object> visited = new HashSet<>();
		ArrayDeque<Object> queue = new ArrayDeque<>();
		queue.add(entry);
		while(!queue.isEmpty())
		{
			Object current = queue.removeFirst();
			if(current == null || !visited.add(current))
				continue;
			
			String id = tryCallString(current, "getId", "getModId", "getModID");
			if(id != null)
				return id;
			
			Object meta = tryCall(current, "getMetadata");
			id = meta != null ? tryCallString(meta, "getId") : null;
			if(id != null)
				return id;
			
			Object container =
				tryCall(current, "getModContainer", "getContainer", "getMod");
			if(container != null)
				queue.add(container);
			
			if(meta != null)
				queue.add(meta);
			
			for(Field field : current.getClass().getDeclaredFields())
			{
				try
				{
					field.setAccessible(true);
					Object value = field.get(current);
					if(value == null)
						continue;
					String name = field.getName().toLowerCase();
					String typeName = field.getType().getName().toLowerCase();
					if(name.contains("mod") || name.contains("container")
						|| typeName.contains("mod"))
						queue.add(value);
				}catch(Throwable ignored)
				{}
			}
		}
		
		return null;
	}
	
	private static String extractDisplayName(Object entry)
	{
		String name = tryCallString(entry, "getName", "getDisplayName",
			"getTranslatedName");
		if(name != null)
			return name;
		
		Object meta = tryCall(entry, "getMetadata");
		name = meta != null ? tryCallString(meta, "getName") : null;
		if(name != null)
			return name;
		
		Object container =
			tryCall(entry, "getModContainer", "getContainer", "getMod");
		if(container != null)
		{
			meta = tryCall(container, "getMetadata");
			name = meta != null ? tryCallString(meta, "getName") : null;
		}
		
		return name;
	}
	
	private static Object tryCall(Object target, String... methods)
	{
		for(String name : methods)
		{
			try
			{
				Method m = target.getClass().getMethod(name);
				return m.invoke(target);
			}catch(Throwable ignored)
			{}
		}
		
		return null;
	}
	
	private static String tryCallString(Object target, String... methods)
	{
		Object value = tryCall(target, methods);
		return value != null ? Objects.toString(value, null) : null;
	}
}
