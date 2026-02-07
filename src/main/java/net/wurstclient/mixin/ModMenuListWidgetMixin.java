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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

@Mixin(targets = "com.terraformersmc.modmenu.gui.widget.ModListWidget",
	remap = false)
public abstract class ModMenuListWidgetMixin
{
	private static final String[] WURST_MOD_IDS = {"wurst", "nicewurst"};
	private static final String[] ENTRY_CLASS_NAMES =
		{"com.terraformersmc.modmenu.gui.widget.entries.ModListEntry"};
	private static volatile Class<?> detectedEntryClass;
	
	@Inject(method = "<init>", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuList(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(method = "setSearch", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuSearch(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(method = "setSearch(Ljava/lang/String;)V",
		at = @At("TAIL"),
		require = 0)
	private void wurst$hideFromModMenuSearchString(String query,
		CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(method = "setSearch(Ljava/lang/String;Z)V",
		at = @At("TAIL"),
		require = 0)
	private void wurst$hideFromModMenuSearchStringFlag(String query,
		boolean userInput, CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(method = "updateEntries()V", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuUpdateEntries(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(method = "refresh()V", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuRefresh(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(method = "rebuild()V", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuRebuild(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(method = "refreshList()V", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuRefreshList(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(method = "finalizeInit()V", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuFinalizeInit(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(method = "reloadFilters()V", at = @At("TAIL"), require = 0)
	private void wurst$hideFromModMenuReloadFilters(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(method = "filter(Ljava/lang/String;Z)V",
		at = @At("TAIL"),
		require = 0)
	private void wurst$hideFromModMenuFilter(String query, boolean userInput,
		CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		removeFromModMenuList(this, WURST_MOD_IDS);
	}
	
	@Inject(
		method = "addEntry(Lcom/terraformersmc/modmenu/gui/widget/entries/ModListEntry;)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0)
	private void wurst$hideFromModMenuAddEntry(@Coerce Object entry,
		CallbackInfoReturnable<Integer> cir)
	{
		if(!WurstClient.INSTANCE.shouldHideModMenuEntries())
			return;
		
		if(isMatchingMod(entry, WURST_MOD_IDS))
			cir.setReturnValue(-1);
	}
	
	private static void removeFromModMenuList(Object widget, String[] modIds)
	{
		ensureEntryClassDetected(widget);
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
					if(!(value instanceof Collection<?> list))
						continue;
					
					if(removeMatchingEntries(list, modIds))
						continue;
					
					List<Object> filtered = new ArrayList<>(list.size());
					for(Object entry : list)
						if(!isMatchingMod(entry, modIds))
							filtered.add(entry);
						
					if(filtered.size() == list.size())
						continue;
					
					try
					{
						field.set(widget, filtered);
					}catch(IllegalAccessException ignored)
					{}
					
				}catch(Throwable ignored)
				{}
			}
		}
		
		Object children = tryCall(widget, "children");
		if(children instanceof Collection<?> collection)
			removeMatchingEntries(collection, modIds);
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
