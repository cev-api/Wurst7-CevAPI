/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
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
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AutoDisenchantHack;

@Mixin(GrindstoneScreen.class)
public abstract class GrindstoneScreenMixin
	extends AbstractContainerScreen<GrindstoneMenu>
{
	@Unique
	private final AutoDisenchantHack autoDisenchant =
		WurstClient.INSTANCE.getHax().autoDisenchantHack;
	
	private GrindstoneScreenMixin(WurstClient wurst, GrindstoneMenu handler,
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
		
		addRenderableWidget(Button
			.builder(Component.literal("AutoDisenchant"),
				b -> autoDisenchant.start((GrindstoneScreen)(Object)this))
			.bounds(leftPos + imageWidth - 110, topPos + 4, 106, 12).build());
		
		if(autoDisenchant.isEnabled())
			autoDisenchant.start((GrindstoneScreen)(Object)this);
	}
}
