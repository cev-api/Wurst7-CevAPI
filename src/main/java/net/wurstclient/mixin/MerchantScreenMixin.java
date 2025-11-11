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
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.text.Text;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AutoTraderHack;

import net.minecraft.entity.player.PlayerInventory;

@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin
	extends HandledScreen<MerchantScreenHandler>
{
	private MerchantScreenMixin(WurstClient wurst,
		MerchantScreenHandler handler, PlayerInventory inventory, Text title)
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
		
		ButtonWidget button = ButtonWidget
			.builder(Text.literal("AutoTrader"),
				b -> autoTrader.triggerFromGui())
			.dimensions(x + backgroundWidth - 90, y - 20, 80, 16).build();
		addDrawableChild(button);
	}
}
