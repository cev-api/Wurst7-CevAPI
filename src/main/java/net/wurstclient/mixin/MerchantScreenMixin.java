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
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AutoTraderHack;

@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin
	extends AbstractContainerScreen<MerchantMenu>
{
	private MerchantScreenMixin(WurstClient wurst, MerchantMenu handler,
		Inventory inventory, Component title)
	{
		super(handler, inventory, title);
	}
	
	@Inject(method = "init", at = @At("TAIL"))
	private void wurst$addAutoTraderButton(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		AutoTraderHack autoTrader =
			WurstClient.INSTANCE.getHax().autoTraderHack;
		if(autoTrader == null)
			return;
		
		Button button = Button
			.builder(Component.literal("AutoTrader"),
				b -> autoTrader.triggerFromGui())
			.bounds(leftPos + imageWidth - 90, topPos - 20, 80, 16).build();
		addRenderableWidget(button);
	}
}
