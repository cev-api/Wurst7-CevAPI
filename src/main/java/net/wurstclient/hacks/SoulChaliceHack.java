/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"soul chalice", "chalice", "clock"})
public final class SoulChaliceHack extends Hack implements UpdateListener
{
	private static final Set<String> COUNT_KEYS = Set.of("soul", "souls",
		"kill", "kills", "killcount", "kill_count", "soulcount", "soul_count");
	private static final Pattern SOULS_COUNT_PATTERN =
		Pattern.compile("(?i)souls\\s*:?\\s*(\\d+)\\s*/\\s*(\\d+)");
	private static final int FULL_COUNT = 20;
	
	private final TextFieldSetting chaliceName =
		new TextFieldSetting("Chalice name", "Soul Chalice");
	private final TextFieldSetting fullMarker =
		new TextFieldSetting("Full text", "Souls 20/20");
	private final CheckboxSetting checkNbtCounts = new CheckboxSetting(
		"Check numeric NBT", "Looks for NBT tags like souls/kills.", true);
	private final CheckboxSetting enableAutoTotem =
		new CheckboxSetting("Enable AutoTotem when full",
			"Turns on AutoTotem when all chalices are full.", true);
	private final CheckboxSetting disableAutoTotemOnEmpty =
		new CheckboxSetting("Disable AutoTotem on empty",
			"Turns AutoTotem back off when an empty chalice is found.", true);
	
	private int nextTickSlot = -1;
	private boolean autoTotemToggled;
	private boolean pendingEquipHotbar;
	private int currentSouls;
	private int fullChalices;
	private int totalChalices;
	
	public SoulChaliceHack()
	{
		super("SoulChalice");
		addSetting(chaliceName);
		addSetting(fullMarker);
		addSetting(checkNbtCounts);
		addSetting(enableAutoTotem);
		addSetting(disableAutoTotemOnEmpty);
	}
	
	@Override
	public String getRenderName()
	{
		if(totalChalices <= 0)
			return getName();
		
		return getName() + " [" + currentSouls + "/" + FULL_COUNT + " | "
			+ fullChalices + "/" + totalChalices + "]";
	}
	
	@Override
	protected void onEnable()
	{
		nextTickSlot = -1;
		autoTotemToggled = false;
		pendingEquipHotbar = true;
		if(WURST.getHax().autoTotemHack.isEnabled())
			WURST.getHax().autoTotemHack.setEnabled(false);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		if(autoTotemToggled)
		{
			WURST.getHax().autoTotemHack.setEnabled(false);
			autoTotemToggled = false;
		}
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null)
			return;
		
		finishMovingChalice();
		handlePendingHotbarEquip();
		updateCounts();
		
		if(MC.screen instanceof AbstractContainerScreen
			&& !(MC.screen instanceof InventoryScreen
				|| MC.screen instanceof CreativeModeInventoryScreen))
			return;
		
		ItemStack offhand = MC.player.getOffhandItem();
		if(!isChalice(offhand))
			return;
		
		if(!isFullChalice(offhand))
		{
			disableAutoTotemIfNeeded();
			return;
		}
		
		int emptySlot = findEmptyChaliceSlot();
		if(emptySlot != -1)
		{
			disableAutoTotemIfNeeded();
			moveToOffhand(emptySlot);
			return;
		}
		
		if(enableAutoTotem.isChecked())
			enableAutoTotem();
	}
	
	private void moveToOffhand(int itemSlot)
	{
		boolean offhandEmpty = MC.player.getOffhandItem().isEmpty();
		IMC.getInteractionManager().windowClick_PICKUP(itemSlot);
		IMC.getInteractionManager().windowClick_PICKUP(45);
		if(!offhandEmpty)
			nextTickSlot = itemSlot;
	}
	
	private void finishMovingChalice()
	{
		if(nextTickSlot == -1)
			return;
		
		IMC.getInteractionManager().windowClick_PICKUP(nextTickSlot);
		nextTickSlot = -1;
	}
	
	private int findEmptyChaliceSlot()
	{
		int slot = InventoryUtils.indexOf(this::isEmptyChalice, 36, false);
		return InventoryUtils.toNetworkSlot(slot);
	}
	
	private boolean isEmptyChalice(ItemStack stack)
	{
		return isChalice(stack) && !isFullChalice(stack);
	}
	
	private boolean isChalice(ItemStack stack)
	{
		if(stack == null || stack.isEmpty() || !stack.is(Items.CLOCK))
			return false;
		
		if(hasChaliceTag(stack))
			return true;
		
		String name = stack.getHoverName().getString();
		String target = chaliceName.getValue().trim();
		if(target.isEmpty())
			return false;
		
		return containsIgnoreCase(name, target);
	}
	
	private boolean isFullChalice(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		
		String marker = fullMarker.getValue().trim();
		if(!marker.isEmpty())
		{
			if(containsIgnoreCase(stack.getHoverName().getString(), marker))
				return true;
			
			ItemLore lore = stack.get(DataComponents.LORE);
			if(lore != null)
				for(var line : lore.lines())
				{
					if(containsIgnoreCase(line.getString(), marker))
						return true;
				}
			
			if(containsInCustomData(stack, marker))
				return true;
		}
		
		if(isFullFromLoreCount(stack))
			return true;
		
		if(checkNbtCounts.isChecked())
		{
			CompoundTag tag =
				stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
					.copyTag();
			if(tag != null && hasFullCountTag(tag, FULL_COUNT))
				return true;
		}
		
		return false;
	}
	
	private boolean isFullFromLoreCount(ItemStack stack)
	{
		ItemLore lore = stack.get(DataComponents.LORE);
		if(lore == null)
			return false;
		
		for(var line : lore.lines())
		{
			Matcher matcher = SOULS_COUNT_PATTERN.matcher(line.getString());
			if(!matcher.find())
				continue;
			
			int current = parseInt(matcher.group(1));
			int max = parseInt(matcher.group(2));
			if(max > 0 && max == FULL_COUNT && current >= max)
				return true;
		}
		
		return false;
	}
	
	private int getSoulsFromLore(ItemStack stack)
	{
		ItemLore lore = stack.get(DataComponents.LORE);
		if(lore == null)
			return 0;
		
		for(var line : lore.lines())
		{
			Matcher matcher = SOULS_COUNT_PATTERN.matcher(line.getString());
			if(!matcher.find())
				continue;
			
			int current = parseInt(matcher.group(1));
			int max = parseInt(matcher.group(2));
			if(max == FULL_COUNT)
				return current;
		}
		
		return 0;
	}
	
	private boolean containsInCustomData(ItemStack stack, String marker)
	{
		CompoundTag tag =
			stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
				.copyTag();
		if(tag == null)
			return false;
		
		return containsIgnoreCase(tag.toString(), marker);
	}
	
	private boolean hasChaliceTag(ItemStack stack)
	{
		CompoundTag tag =
			stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
				.copyTag();
		if(tag == null)
			return false;
		
		if(tag.contains("vb.item.soul_chalice"))
			return true;
		
		if(tag.contains("vb.item") && !tag.contains("vb.item.soul_chalice"))
			return false;
		
		return false;
	}
	
	private boolean hasFullCountTag(CompoundTag tag, int target)
	{
		for(String key : getTagKeys(tag))
		{
			Tag value = tag.get(key);
			if(value instanceof CompoundTag sub && hasFullCountTag(sub, target))
				return true;
			
			if(!(value instanceof NumericTag num))
				continue;
			
			String keyLower = key.toLowerCase(Locale.ROOT);
			boolean keyMatch = COUNT_KEYS.stream().anyMatch(keyLower::contains);
			if(keyMatch && readNumericTag(num) >= target)
				return true;
		}
		
		return false;
	}
	
	private void enableAutoTotem()
	{
		if(InventoryUtils.count(stack -> stack.is(Items.TOTEM_OF_UNDYING), 40,
			true) <= 0)
			return;
		
		if(WURST.getHax().autoTotemHack.isEnabled())
			return;
		
		WURST.getHax().autoTotemHack.setEnabled(true);
		autoTotemToggled = true;
	}
	
	private void disableAutoTotemIfNeeded()
	{
		if(!autoTotemToggled || !disableAutoTotemOnEmpty.isChecked())
			return;
		
		WURST.getHax().autoTotemHack.setEnabled(false);
		autoTotemToggled = false;
	}
	
	private boolean containsIgnoreCase(String haystack, String needle)
	{
		if(haystack == null || needle == null)
			return false;
		
		return haystack.toLowerCase(Locale.ROOT)
			.contains(needle.toLowerCase(Locale.ROOT));
	}
	
	private int parseInt(String value)
	{
		try
		{
			return Integer.parseInt(value);
		}catch(NumberFormatException e)
		{
			return 0;
		}
	}
	
	private void updateCounts()
	{
		currentSouls = 0;
		ItemStack offhand = MC.player.getOffhandItem();
		if(isChalice(offhand))
			currentSouls = getSoulsFromLore(offhand);
		
		totalChalices = InventoryUtils.count(this::isChalice, 40, true);
		fullChalices = InventoryUtils
			.count(stack -> isChalice(stack) && isFullChalice(stack), 40, true);
	}
	
	private void handlePendingHotbarEquip()
	{
		if(!pendingEquipHotbar)
			return;
		
		if(MC.screen instanceof AbstractContainerScreen
			&& !(MC.screen instanceof InventoryScreen
				|| MC.screen instanceof CreativeModeInventoryScreen))
			return;
		
		int targetSlot = findChaliceSlotForHotbar();
		if(targetSlot == -1)
		{
			pendingEquipHotbar = false;
			return;
		}
		
		if(targetSlot != 1)
		{
			IMC.getInteractionManager()
				.windowClick_SWAP(InventoryUtils.toNetworkSlot(targetSlot), 1);
		}
		
		MC.player.getInventory().setSelectedSlot(1);
		pendingEquipHotbar = false;
	}
	
	private int findChaliceSlotForHotbar()
	{
		int emptySlot = InventoryUtils.indexOf(this::isEmptyChalice, 36, false);
		if(emptySlot != -1)
			return emptySlot;
		
		return InventoryUtils.indexOf(this::isChalice, 36, false);
	}
	
	private Collection<String> getTagKeys(CompoundTag tag)
	{
		if(tag == null)
			return Collections.emptySet();
		
		for(String methodName : new String[]{"getAllKeys", "getKeys",
			"getKeySet"})
		{
			try
			{
				Object result =
					tag.getClass().getMethod(methodName).invoke(tag);
				if(result instanceof Collection<?> collection)
				{
					HashSet<String> keys = new HashSet<>();
					for(Object entry : collection)
						if(entry instanceof String s)
							keys.add(s);
					return keys;
				}
			}catch(ReflectiveOperationException ignored)
			{
				// fall through to try next method
			}
		}
		
		return Collections.emptySet();
	}
	
	private int readNumericTag(NumericTag tag)
	{
		if(tag == null)
			return 0;
		
		for(String methodName : new String[]{"getAsInt", "getAsNumber"})
		{
			try
			{
				Object result =
					tag.getClass().getMethod(methodName).invoke(tag);
				if(result instanceof Number num)
					return num.intValue();
			}catch(ReflectiveOperationException ignored)
			{
				// fall through to try next method
			}
		}
		
		return 0;
	}
}
