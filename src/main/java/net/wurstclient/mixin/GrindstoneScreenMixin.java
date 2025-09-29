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

import net.minecraft.client.gui.screen.ingame.GrindstoneScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.text.Text;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AutoDisenchantHack;

@Mixin(GrindstoneScreen.class)
public abstract class GrindstoneScreenMixin
	extends HandledScreen<GrindstoneScreenHandler>
{
	@Unique
	private final AutoDisenchantHack autoDisenchant =
		WurstClient.INSTANCE.getHax().autoDisenchantHack;
	
	private GrindstoneScreenMixin(WurstClient wurst,
		GrindstoneScreenHandler handler, PlayerInventory inventory, Text title)
	{
		super(handler, inventory, title);
	}
	
	@Override
	public void init()
	{
		super.init();
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		addDrawableChild(ButtonWidget
			.builder(Text.literal("AutoDisenchant"),
				b -> autoDisenchant.start((GrindstoneScreen)(Object)this))
			.dimensions(x + backgroundWidth - 110, y + 4, 106, 12).build());
		
		if(autoDisenchant.isEnabled())
			autoDisenchant.start((GrindstoneScreen)(Object)this);
	}
}
