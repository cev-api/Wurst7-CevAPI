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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.wurstclient.uiutils.UiUtils;
import net.wurstclient.uiutils.UiUtilsState;

@Mixin(AbstractContainerScreen.class)
public abstract class UiUtilsAbstractContainerScreenMixin<T extends AbstractContainerMenu>
	extends Screen
{
	@Unique
	private EditBox uiUtilsChatField;
	
	private UiUtilsAbstractContainerScreenMixin(Component title)
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
	
	@Inject(at = @At("TAIL"),
		method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
	private void onRender(GuiGraphics graphics, int mouseX, int mouseY,
		float partialTicks, CallbackInfo ci)
	{
		if(!UiUtilsState.isUiEnabled())
			return;
		
		AbstractContainerMenu menu =
			((AbstractContainerScreen<?>)(Object)this).getMenu();
		UiUtils.renderSyncInfo(Minecraft.getInstance(), graphics, menu);
	}
	
	@Inject(at = @At("HEAD"),
		method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
		cancellable = true)
	private void onKeyPressed(KeyEvent keyEvent,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(!UiUtilsState.isUiEnabled())
			return;
		
		if(uiUtilsChatField == null || !uiUtilsChatField.isFocused())
			return;
		
		if(uiUtilsChatField.keyPressed(keyEvent))
		{
			cir.setReturnValue(true);
			return;
		}
		
		if(keyEvent.isEscape())
		{
			uiUtilsChatField.setFocused(false);
			cir.setReturnValue(true);
			return;
		}
		
		cir.setReturnValue(true);
	}
	
}
