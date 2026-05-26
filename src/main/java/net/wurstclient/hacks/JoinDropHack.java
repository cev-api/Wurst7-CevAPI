/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

public final class JoinDropHack extends Hack implements UpdateListener
{
	private static final int RUN_TICKS = 200;
	private static final int SPAM_PASSES = 3;
	
	private final CheckboxSetting shulkers =
		new CheckboxSetting("Shulkers", true);
	private final CheckboxSetting bundles =
		new CheckboxSetting("Bundles", true);
	private final CheckboxSetting bookAndQuills =
		new CheckboxSetting("Book & quills", true);
	private final CheckboxSetting all = new CheckboxSetting("All", false);
	private final CheckboxSetting disableAfterComplete =
		new CheckboxSetting("Disable after complete",
			"Turns this hack off after all selected items are dropped.", false);
	
	private int ticksLeft;
	private boolean listening;
	private final Map<Integer, DroppedStack> pendingDrops = new HashMap<>();
	private final HashSet<Integer> loggedSlots = new HashSet<>();
	
	public JoinDropHack()
	{
		super("JoinDrop",
			"Drops selected item types immediately after server join.", false);
		addSetting(shulkers);
		addSetting(bundles);
		addSetting(bookAndQuills);
		addSetting(all);
		addSetting(disableAfterComplete);
	}
	
	public void onServerJoin()
	{
		if(!isEnabled())
			return;
		
		ticksLeft = RUN_TICKS;
		pendingDrops.clear();
		loggedSlots.clear();
		
		if(!listening)
		{
			EVENTS.add(UpdateListener.class, this);
			listening = true;
		}
		
		dropMatches();
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player != null)
			onServerJoin();
	}
	
	@Override
	protected void onDisable()
	{
		stop();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.gameMode == null)
		{
			stop();
			return;
		}
		
		confirmDrops();
		dropMatches();
		
		ticksLeft--;
		if(ticksLeft <= 0 && pendingDrops.isEmpty())
		{
			if(disableAfterComplete.isChecked() && isEnabled())
				setEnabled(false);
			else
				stop();
		}
	}
	
	private void dropMatches()
	{
		if(MC.player == null || MC.gameMode == null)
			return;
		
		Inventory inv = MC.player.getInventory();
		int oldSlot = inv.getSelectedSlot();
		
		for(int pass = 0; pass < SPAM_PASSES; pass++)
		{
			for(int invSlot = 0; invSlot < 9; invSlot++)
				dropHotbarMatch(inv, invSlot);
			
			dropOffhandMatch(inv);
		}
		
		for(int invSlot = 9; invSlot < 36; invSlot++)
			dropInventoryMatch(inv, invSlot);
		
		if(inv.getSelectedSlot() != oldSlot)
		{
			inv.setSelectedSlot(oldSlot);
			MC.player.connection
				.send(new ServerboundSetCarriedItemPacket(oldSlot));
		}
	}
	
	private void dropHotbarMatch(Inventory inv, int invSlot)
	{
		ItemStack stack = inv.getItem(invSlot);
		if(!shouldDrop(stack))
			return;
		
		pendingDrops.putIfAbsent(invSlot, DroppedStack.from(stack));
		
		if(inv.getSelectedSlot() != invSlot)
		{
			inv.setSelectedSlot(invSlot);
			MC.player.connection
				.send(new ServerboundSetCarriedItemPacket(invSlot));
		}
		
		MC.player.connection.send(new ServerboundPlayerActionPacket(
			ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS, BlockPos.ZERO,
			Direction.DOWN, 0));
	}
	
	private void dropInventoryMatch(Inventory inv, int invSlot)
	{
		ItemStack stack = inv.getItem(invSlot);
		if(!shouldDrop(stack))
			return;
		
		pendingDrops.putIfAbsent(invSlot, DroppedStack.from(stack));
		IMC.getInteractionManager().windowClick_THROW(invSlot);
	}
	
	private void dropOffhandMatch(Inventory inv)
	{
		ItemStack stack = inv.getItem(40);
		if(!shouldDrop(stack))
			return;
		
		pendingDrops.putIfAbsent(40, DroppedStack.from(stack));
		
		sendAction(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND);
		sendAction(ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS);
		sendAction(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND);
	}
	
	private boolean shouldDrop(ItemStack stack)
	{
		if(stack.isEmpty())
			return false;
		
		if(all.isChecked())
			return true;
		
		if(shulkers.isChecked() && isShulker(stack))
			return true;
		
		if(bundles.isChecked() && stack.getItem() instanceof BundleItem)
			return true;
		
		if(bookAndQuills.isChecked()
			&& (stack.is(Items.WRITABLE_BOOK) || stack.is(Items.WRITTEN_BOOK)))
			return true;
		
		return false;
	}
	
	private void sendAction(ServerboundPlayerActionPacket.Action action)
	{
		MC.player.connection.send(new ServerboundPlayerActionPacket(action,
			BlockPos.ZERO, Direction.DOWN, 0));
	}
	
	private void confirmDrops()
	{
		if(pendingDrops.isEmpty() || MC.player == null)
			return;
		
		Inventory inv = MC.player.getInventory();
		pendingDrops.entrySet().removeIf(entry -> {
			int invSlot = entry.getKey();
			ItemStack current = inv.getItem(invSlot);
			DroppedStack dropped = entry.getValue();
			
			if(isSameStack(current, dropped))
				return false;
			
			if(loggedSlots.add(invSlot))
				System.out.println("[JoinDrop] Dropped " + dropped.description()
					+ " from inventory slot " + invSlot + ".");
			
			return true;
		});
	}
	
	private void stop()
	{
		if(!listening)
			return;
		
		EVENTS.remove(UpdateListener.class, this);
		listening = false;
		ticksLeft = 0;
		pendingDrops.clear();
		loggedSlots.clear();
	}
	
	private boolean isShulker(ItemStack stack)
	{
		if(!(stack.getItem() instanceof BlockItem blockItem))
			return false;
		
		return blockItem.getBlock() instanceof ShulkerBoxBlock;
	}
	
	private boolean isSameStack(ItemStack current, DroppedStack dropped)
	{
		if(current.isEmpty())
			return false;
		
		String currentId =
			BuiltInRegistries.ITEM.getKey(current.getItem()).toString();
		return currentId.equals(dropped.itemId())
			&& ItemStack.isSameItemSameComponents(current, dropped.stack());
	}
	
	private record DroppedStack(String itemId, String description,
		ItemStack stack)
	{
		private static DroppedStack from(ItemStack stack)
		{
			ItemStack copy = stack.copy();
			String itemId =
				BuiltInRegistries.ITEM.getKey(copy.getItem()).toString();
			String description = copy.getHoverName().getString() + " x"
				+ copy.getCount() + " (" + itemId + ")";
			
			return new DroppedStack(itemId, description, copy);
		}
	}
}
