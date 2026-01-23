/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.ui_utils;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.wurstclient.uiutils.UiUtilsState;

@Mixin(InBedChatScreen.class)
public abstract class UiUtilsInBedChatScreenMixin extends Screen
{
	private UiUtilsInBedChatScreenMixin(Component title)
	{
		super(title);
	}
	
	@Inject(at = @At("TAIL"), method = "init()V")
	private void onInit(CallbackInfo ci)
	{
		if(!UiUtilsState.isUiEnabled())
			return;
		
		int baseX = 8;
		int startY = Math.max(5, (this.height - 20) / 2);
		addRenderableWidget(
			Button.builder(Component.literal("Client wake up"), b -> {
				Minecraft mc = Minecraft.getInstance();
				if(mc.player != null)
				{
					mc.player.stopSleeping();
					mc.setScreen(null);
				}
			}).bounds(baseX, startY, 115, 20).build());
	}
}
