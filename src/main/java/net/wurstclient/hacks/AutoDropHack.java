/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ItemListSetting;

@SearchTags({"auto drop", "AutoEject", "auto-eject", "auto eject",
	"InventoryCleaner", "inventory cleaner", "InvCleaner", "inv cleaner"})
public final class AutoDropHack extends Hack implements UpdateListener
{
	private ItemListSetting items = new ItemListSetting("Items",
		"Unwanted items that will be dropped.", "minecraft:allium",
		"minecraft:azure_bluet", "minecraft:blue_orchid",
		"minecraft:cornflower", "minecraft:dandelion", "minecraft:lilac",
		"minecraft:lily_of_the_valley", "minecraft:orange_tulip",
		"minecraft:oxeye_daisy", "minecraft:peony", "minecraft:pink_tulip",
		"minecraft:poisonous_potato", "minecraft:poppy", "minecraft:red_tulip",
		"minecraft:rose_bush", "minecraft:rotten_flesh", "minecraft:sunflower",
		"minecraft:wheat_seeds", "minecraft:white_tulip");
	
	private final CheckboxSetting packetOnly = new CheckboxSetting(
		"Packet-only mode",
		"Sends raw drop packets instead of using Minecraft's normal inventory"
			+ " click handler. Experimental: may reduce local animations, but can"
			+ " cause temporary inventory desync if a server rejects the packet.",
		false);
	
	private final String renderName =
		Math.random() < 0.01 ? "AutoLinus" : getName();
	
	public AutoDropHack()
	{
		super("AutoDrop");
		setCategory(Category.ITEMS);
		addSetting(items);
		addSetting(packetOnly);
	}
	
	@Override
	public String getRenderName()
	{
		return renderName;
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		// check screen
		if(MC.gui.screen() instanceof AbstractContainerScreen
			&& !(MC.gui.screen() instanceof InventoryScreen))
			return;
		
		for(int slot = 9; slot < 45; slot++)
		{
			int adjustedSlot = slot;
			if(adjustedSlot >= 36)
				adjustedSlot -= 36;
			ItemStack stack = MC.player.getInventory().getItem(adjustedSlot);
			
			if(stack.isEmpty())
				continue;
			
			Item item = stack.getItem();
			String itemName = BuiltInRegistries.ITEM.getKey(item).toString();
			
			if(!items.getItemNames().contains(itemName))
				continue;
			
			if(packetOnly.isChecked())
				sendThrowPacket(slot, adjustedSlot);
			else
				IMC.getInteractionManager().windowClick_THROW(slot);
		}
	}
	
	private void sendThrowPacket(int slot, int inventorySlot)
	{
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
			return;
		
		ServerboundContainerClickPacket packet =
			new ServerboundContainerClickPacket(
				MC.player.containerMenu.containerId,
				MC.player.containerMenu.getStateId(), (short)slot, (byte)1,
				ContainerInput.THROW, new Int2ObjectOpenHashMap<>(),
				HashedStack.EMPTY);
		
		connection.send(packet);
		
		// Vanilla normally predicts click results locally. Packet-only mode
		// skips that path, so clear the slot to avoid resending it every tick.
		MC.player.getInventory().setItem(inventorySlot, ItemStack.EMPTY);
	}
}
