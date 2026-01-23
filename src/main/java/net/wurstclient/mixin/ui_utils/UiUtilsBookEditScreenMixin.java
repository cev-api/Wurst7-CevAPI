/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.ui_utils;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;

import net.wurstclient.uiutils.UiUtils;
import net.wurstclient.uiutils.UiUtilsState;

@Mixin(BookEditScreen.class)
public abstract class UiUtilsBookEditScreenMixin extends Screen
{
	@Unique
	private EditBox uiUtilsChatField;
	
	private UiUtilsBookEditScreenMixin(Component title)
	{
		super(title);
	}
	
	@Inject(at = @At("TAIL"), method = "init()V")
	private void onInit(CallbackInfo ci)
	{
		if(!UiUtilsState.isUiEnabled())
			return;
		
		Minecraft mc = Minecraft.getInstance();
		int spacing = 4;
		int buttonHeight = 20;
		int buttonCount = 8;
		int chatHeight = 20;
		int blockHeight = buttonCount * buttonHeight
			+ (buttonCount - 1) * spacing + spacing + chatHeight;
		int startY = Math.max(5, (this.height - blockHeight) / 2);
		int baseX = 8;
		int nextY = UiUtils.addUiWidgets(mc, baseX, startY, spacing,
			this::addRenderableWidget);
		uiUtilsChatField =
			UiUtils.createChatField(mc, this.font, baseX, nextY + spacing);
		addRenderableWidget(uiUtilsChatField);
	}
	
}
