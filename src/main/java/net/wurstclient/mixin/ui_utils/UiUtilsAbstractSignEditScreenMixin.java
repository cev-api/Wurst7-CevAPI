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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.wurstclient.uiutils.UiUtils;
import net.wurstclient.uiutils.UiUtilsState;

@Mixin(AbstractSignEditScreen.class)
public abstract class UiUtilsAbstractSignEditScreenMixin extends Screen
{
	private UiUtilsAbstractSignEditScreenMixin(Component title)
	{
		super(title);
	}
	
	@Inject(at = @At("TAIL"), method = "init()V")
	private void onInit(CallbackInfo ci)
	{
		if(net.wurstclient.WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(!UiUtilsState.isUiEnabled())
			return;
		
		Minecraft mc = Minecraft.getInstance();
		int spacing = 4;
		int buttonHeight = 20;
		int totalHeight = buttonHeight * 2 + spacing;
		int startY = Math.max(5, (this.height - totalHeight) / 2);
		int baseX = 8;
		addRenderableWidget(
			Button.builder(Component.literal("Close without packet"), b -> {
				UiUtilsState.shouldEditSign = false;
				mc.setScreen(null);
			}).bounds(baseX, startY, 115, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Disconnect"), b -> {
				if(mc.getConnection() != null)
					mc.getConnection().getConnection().disconnect(
						Component.literal("Disconnecting (UI-UTILS)"));
				else
					UiUtils.LOGGER.warn(
						"Minecraft connection was null while disconnecting.");
			}).bounds(baseX, startY + buttonHeight + spacing, 115, 20).build());
	}
}
