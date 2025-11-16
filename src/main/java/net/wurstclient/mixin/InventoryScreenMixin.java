/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.QuickShulkerHack;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin
	extends AbstractContainerScreen<InventoryMenu>
{
	private InventoryScreenMixin(WurstClient wurst, InventoryMenu handler,
		Inventory inventory, Component title)
	{
		super(handler, inventory, title);
	}
	
	@Inject(method = "init", at = @At("TAIL"))
	private void wurst$addQuickShulkerButton(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		QuickShulkerHack quickShulker =
			WurstClient.INSTANCE.getHax().quickShulkerHack;
		if(quickShulker == null || !quickShulker.isEnabled()
			|| !quickShulker.hasUsableShulker())
			return;
			
		// place the button above the inventory border so it doesn't overlap
		// the container background
		Button button = Button
			.builder(Component.literal("QuickShulker"),
				b -> quickShulker.triggerFromGui())
			.bounds(leftPos + imageWidth - 90, topPos - 20, 80, 16).build();
		button.active = !quickShulker.isBusy();
		addRenderableWidget(button);
	}
}
