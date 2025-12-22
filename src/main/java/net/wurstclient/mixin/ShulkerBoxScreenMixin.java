/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AutoStealHack;
import net.wurstclient.hacks.QuickShulkerHack;

@Mixin(ShulkerBoxScreen.class)
public abstract class ShulkerBoxScreenMixin
	extends AbstractContainerScreen<ShulkerBoxMenu>
{
	@Unique
	private final AutoStealHack autoSteal =
		WurstClient.INSTANCE.getHax().autoStealHack;
	@Unique
	private final QuickShulkerHack quickShulker =
		WurstClient.INSTANCE.getHax().quickShulkerHack;
	
	private ShulkerBoxScreenMixin(WurstClient wurst, ShulkerBoxMenu handler,
		Inventory inventory, Component title)
	{
		super(handler, inventory, title);
	}
	
	@Override
	public void init()
	{
		super.init();
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		boolean autoButtonsPlaced = false;
		final int autoButtonHeight = 12;
		final int autoButtonY = topPos - autoButtonHeight - 4;
		if(autoSteal.areButtonsVisible())
		{
			autoButtonsPlaced = true;
			final int buttonWidth = 44;
			final int buttonSpacing = 3;
			final int rightMargin = 6;
			int dumpX = leftPos + imageWidth - rightMargin - buttonWidth;
			int storeX = dumpX - buttonSpacing - buttonWidth;
			int stealX = storeX - buttonSpacing - buttonWidth;
			
			addRenderableWidget(Button
				.builder(Component.literal("Steal"),
					b -> autoSteal.steal(this, 3))
				.bounds(stealX, autoButtonY, buttonWidth, autoButtonHeight)
				.build());
			
			addRenderableWidget(Button
				.builder(Component.literal("Store"),
					b -> autoSteal.store(this, 3))
				.bounds(storeX, autoButtonY, buttonWidth, autoButtonHeight)
				.build());
			
			addRenderableWidget(Button
				.builder(Component.literal("Dump"),
					b -> autoSteal.dump(this, 3))
				.bounds(dumpX, autoButtonY, buttonWidth, autoButtonHeight)
				.build());
		}
		
		if(autoSteal.isEnabled())
			autoSteal.steal(this, 3);
		
		if(quickShulker != null && quickShulker.isEnabled()
			&& quickShulker.hasUsableShulker())
		{
			// place the QuickShulker button outside the shulker UI so it
			// doesn't overlap the container background, matching the
			// inventory screen placement
			int quickButtonY =
				autoButtonsPlaced ? autoButtonY - 20 : topPos - 20;
			Button quickButton = Button
				.builder(Component.literal("QuickShulker"),
					b -> quickShulker.triggerFromGui())
				.bounds(leftPos + imageWidth - 90, quickButtonY, 80, 16)
				.build();
			quickButton.active = !quickShulker.isBusy();
			addRenderableWidget(quickButton);
		}
	}
}
