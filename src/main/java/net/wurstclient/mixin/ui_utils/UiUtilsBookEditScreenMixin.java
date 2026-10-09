/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.ui_utils;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import net.wurstclient.uiutils.UiUtils;
import net.wurstclient.uiutils.McCompat;
import net.wurstclient.uiutils.UiUtilsState;

@Mixin(BookEditScreen.class)
public abstract class UiUtilsBookEditScreenMixin extends Screen
{
	@Unique
	private EditBox uiUtilsChatField;
	@Unique
	private final List<AbstractWidget> uiUtilsMainWidgets = new ArrayList<>();
	
	private UiUtilsBookEditScreenMixin(Component title)
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
		int buttonCount = UiUtils.getUiWidgetRows();
		int chatHeight = 20;
		int blockHeight = buttonCount * buttonHeight
			+ (buttonCount - 1) * spacing + spacing + chatHeight;
		int startY = Math.max(5, (this.height - blockHeight) / 2);
		int baseX = 8;
		uiUtilsMainWidgets.clear();
		int nextY = UiUtils.addUiWidgets(mc, baseX, startY, spacing, widget -> {
			uiUtilsMainWidgets.add(widget);
			addRenderableWidget(widget);
		});
		uiUtilsChatField =
			UiUtils.createChatField(mc, this.font, baseX, nextY + spacing);
		addRenderableWidget(uiUtilsChatField);
		uiUtilsMainWidgets.add(uiUtilsChatField);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		if(!net.wurstclient.WurstClient.INSTANCE.shouldHideWurstUiMixins()
			&& UiUtilsState.isUiEnabled()
			&& McCompat.isLeftMouseButton(event.button()))
			for(AbstractWidget widget : uiUtilsMainWidgets)
				if(widget.active && widget.visible
					&& widget.isMouseOver(event.x(), event.y())
					&& widget.mouseClicked(event, doubleClick))
				{
					setFocused(widget);
					setDragging(true);
					return true;
				}
		return super.mouseClicked(event, doubleClick);
	}
	
	@Inject(at = @At("HEAD"),
		method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
		cancellable = true)
	private void uiutils$chatKeyPressed(KeyEvent event,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(net.wurstclient.WurstClient.INSTANCE.shouldHideWurstUiMixins()
			|| !UiUtilsState.isUiEnabled() || uiUtilsChatField == null
			|| !uiUtilsChatField.isFocused())
			return;
		if(event.isEscape())
			setFocused(null);
		else
			uiUtilsChatField.keyPressed(event);
		cir.setReturnValue(true);
	}
}
