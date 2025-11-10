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

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.text.Text;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.QuickShulkerHack;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin
	extends HandledScreen<PlayerScreenHandler>
{
	private InventoryScreenMixin(WurstClient wurst, PlayerScreenHandler handler,
		PlayerInventory inventory, Text title)
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
		ButtonWidget button = ButtonWidget
			.builder(Text.literal("QuickShulker"),
				b -> quickShulker.triggerFromGui())
			.dimensions(x + backgroundWidth - 90, y - 20, 80, 16).build();
		button.active = !quickShulker.isBusy();
		addDrawableChild(button);
	}
}
