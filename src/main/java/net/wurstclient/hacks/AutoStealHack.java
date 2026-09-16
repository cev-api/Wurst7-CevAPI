/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.wurstclient.Category;
import net.wurstclient.WurstClient;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"auto steal", "ChestStealer", "chest stealer",
	"steal store buttons", "Steal/Store buttons"})
public final class AutoStealHack extends Hack implements UpdateListener
{
	private final SliderSetting delay = new SliderSetting("Delay",
		"Delay between moving stacks of items.\n"
			+ "Should be at least 70ms for NoCheat+ servers.",
		100, 0, 500, 10, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final CheckboxSetting buttons =
		new CheckboxSetting("Steal/Store buttons", true);
	
	private final CheckboxSetting reverseSteal =
		new CheckboxSetting("Reverse steal order", false);
	
	private final CheckboxSetting stealStoreSame = new CheckboxSetting(
		"Steal/Store same",
		"Only move exact matching item types present in the source.", false);
	
	private final CheckboxSetting listOnly =
		new CheckboxSetting("List only",
			WText.literal(
				"Only move the items that are in the adjacent item list."),
			false);
	
	private final ItemListSetting itemList =
		new ItemListSetting("Item list", WText.literal(
			"Items that AutoSteal is allowed to move when \"List only\" is on."));
	
	private final ArrayDeque<Integer> pendingSlots = new ArrayDeque<>();
	private AbstractContainerScreen<?> transferScreen;
	private AbstractContainerScreen<?> manualScreen;
	private boolean listening;
	private boolean manualTransfer;
	private boolean movedItems;
	private ContainerInput transferInput;
	private long nextClickMs;
	private long lastAutoStealAttemptMs;
	private static final long AUTO_STEAL_COOLDOWN_MS = 500L;
	
	public AutoStealHack()
	{
		super("AutoSteal");
		setCategory(Category.ITEMS);
		addSetting(buttons);
		addSetting(delay);
		addSetting(reverseSteal);
		addSetting(stealStoreSame);
		addSetting(listOnly);
		addSetting(itemList);
	}
	
	@Override
	protected void onEnable()
	{
		super.onEnable();
		manualScreen = null;
		lastAutoStealAttemptMs = 0L;
		setListening(true);
	}
	
	@Override
	protected void onDisable()
	{
		super.onDisable();
		cancelTransfer();
		manualScreen = null;
		setListening(false);
	}
	
	public void steal(AbstractContainerScreen<?> screen)
	{
		startTransfer(screen, true, false, true);
	}
	
	public void store(AbstractContainerScreen<?> screen)
	{
		startTransfer(screen, false, false, true);
	}
	
	public void dump(AbstractContainerScreen<?> screen)
	{
		startTransfer(screen, true, true, true);
	}
	
	private void startTransfer(AbstractContainerScreen<?> screen, boolean steal,
		boolean dump, boolean manual)
	{
		cancelTransfer();
		if(!isSupportedScreen(screen) || MC.player == null
			|| MC.gameMode == null
			|| MC.player.containerMenu != screen.getMenu())
		{
			if(manual)
				ChatUtils
					.warning("AutoSteal: this container is no longer open.");
			return;
		}
		if(WurstClient.INSTANCE.getHax().quickShulkerHack.isBusy())
		{
			if(manual)
				ChatUtils
					.warning("AutoSteal: wait for QuickShulker to finish.");
			return;
		}
		if(!screen.getMenu().getCarried().isEmpty())
		{
			if(manual)
				ChatUtils.warning(
					"AutoSteal: put down the item on your cursor first.");
			return;
		}
		
		if(manual)
			manualScreen = screen;
		int containerSlots = getContainerSlots(screen);
		int end = screen.getMenu().slots.size();
		int from = steal ? 0 : containerSlots;
		int to = steal ? containerSlots : end;
		Set<Item> matchingTypes = new HashSet<>();
		if(!dump && stealStoreSame.isChecked())
			for(int i = steal ? containerSlots : 0; i < (steal ? end
				: containerSlots); i++)
			{
				Slot slot = screen.getMenu().slots.get(i);
				if(slot.hasItem())
					matchingTypes.add(slot.getItem().getItem());
			}
		
		boolean hasSourceItems = false;
		for(int i = from; i < to; i++)
		{
			Slot slot = screen.getMenu().slots.get(i);
			if(!slot.hasItem())
				continue;
			hasSourceItems = true;
			Item item = slot.getItem().getItem();
			if(!dump && (listOnly.isChecked() && !itemList.contains(item)
				|| stealStoreSame.isChecked() && !matchingTypes.contains(item)))
				continue;
			if(steal && !dump && reverseSteal.isChecked())
				pendingSlots.addFirst(i);
			else
				pendingSlots.addLast(i);
		}
		if(pendingSlots.isEmpty())
		{
			if(manual)
				ChatUtils.warning(hasSourceItems
					? "AutoSteal: no items match your List only / Steal/Store same filters."
					: "AutoSteal: there are no items to move.");
			return;
		}
		
		transferScreen = screen;
		manualTransfer = manual;
		movedItems = false;
		transferInput = dump ? ContainerInput.THROW : ContainerInput.QUICK_MOVE;
		nextClickMs = 0L;
		// Buttons work independently of the automatic-stealing toggle.
		setListening(true);
		processTransfer();
	}
	
	private void processTransfer()
	{
		if(transferScreen == null)
			return;
		if(MC.player == null || MC.gameMode == null
			|| MC.gui.screen() != transferScreen
			|| MC.player.containerMenu != transferScreen.getMenu()
			|| !transferScreen.getMenu().getCarried().isEmpty()
			|| WurstClient.INSTANCE.getHax().quickShulkerHack.isBusy())
		{
			cancelTransfer();
			return;
		}
		while(!pendingSlots.isEmpty()
			&& System.currentTimeMillis() >= nextClickMs)
		{
			int index = pendingSlots.removeFirst();
			Slot slot = transferScreen.getMenu().slots.get(index);
			if(!slot.hasItem())
				continue;
			int before = slot.getItem().getCount();
			if(transferInput == ContainerInput.THROW)
				// Keep the screen's AntiDrop protection for Dump.
				transferScreen.slotClicked(slot, index, 1, transferInput);
			else
				MC.gameMode.handleContainerInput(
					transferScreen.getMenu().containerId, index, 0,
					transferInput, MC.player);
			movedItems |= slot.getItem().getCount() < before;
			nextClickMs = System.currentTimeMillis() + delay.getValueI();
		}
		if(pendingSlots.isEmpty())
		{
			if(manualTransfer && !movedItems)
				ChatUtils.warning(
					"AutoSteal: no items moved. The destination may be full or reject these items.");
			cancelTransfer();
		}
	}
	
	private void cancelTransfer()
	{
		pendingSlots.clear();
		transferScreen = null;
		if(!isEnabled())
			setListening(false);
	}
	
	private void setListening(boolean value)
	{
		if(listening == value)
			return;
		listening = value;
		if(value)
			EVENTS.add(UpdateListener.class, this);
		else
			EVENTS.remove(UpdateListener.class, this);
	}
	
	private static int getContainerSlots(AbstractContainerScreen<?> screen)
	{
		if(screen.getMenu() instanceof ChestMenu chest)
			return chest.getRowCount() * 9;
		return screen.getMenu() instanceof ShulkerBoxMenu ? 27 : 0;
	}
	
	public boolean areButtonsVisible()
	{
		return buttons.isChecked();
	}
	
	public static boolean isSupportedScreen(AbstractContainerScreen<?> screen)
	{
		return !(screen instanceof CreativeModeInventoryScreen)
			&& (screen.getMenu() instanceof ChestMenu
				|| screen.getMenu() instanceof ShulkerBoxMenu);
	}
	
	@Override
	public void onUpdate()
	{
		if(transferScreen != null)
		{
			processTransfer();
			return;
		}
		if(!isEnabled())
		{
			setListening(false);
			return;
		}
		if(!(MC.gui.screen() instanceof AbstractContainerScreen<?> screen)
			|| !isSupportedScreen(screen))
		{
			manualScreen = null;
			lastAutoStealAttemptMs = 0L;
			return;
		}
		if(manualScreen == screen
			|| WurstClient.INSTANCE.getHax().quickShulkerHack.isBusy())
			return;
		manualScreen = null;
		if(System.currentTimeMillis()
			- lastAutoStealAttemptMs < AUTO_STEAL_COOLDOWN_MS)
			return;
		lastAutoStealAttemptMs = System.currentTimeMillis();
		startTransfer(screen, true, false, false);
	}
}
