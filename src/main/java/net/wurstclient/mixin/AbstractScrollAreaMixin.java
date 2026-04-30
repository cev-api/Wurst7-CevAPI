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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.mixinterface.IServerSelectionListExt;

@Mixin(AbstractScrollArea.class)
public abstract class AbstractScrollAreaMixin
{
	private static final int WURST_ROW_HEIGHT = 36;
	private static final int WURST_SCROLLBAR_WIDTH = 8;
	
	@Inject(method = "scrollBarX()I", at = @At("HEAD"), cancellable = true)
	private void wurst$getPanelScrollBarX(CallbackInfoReturnable<Integer> cir)
	{
		ServerSelectionList list = wurst$getPanelServerList();
		if(list == null)
			return;
		
		cir.setReturnValue(
			list.getX() + list.getWidth() - WURST_SCROLLBAR_WIDTH);
	}
	
	@Inject(
		method = "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z",
		at = @At("HEAD"),
		cancellable = true)
	private void wurst$onPanelScrollbarDragged(MouseButtonEvent event,
		double dragX, double dragY, CallbackInfoReturnable<Boolean> cir)
	{
		if(event.button() != 0)
			return;
		
		ServerSelectionList list = wurst$getPanelServerList();
		if(list == null)
			return;
		
		IServerSelectionListExt ext = (IServerSelectionListExt)list;
		if(!ext.wurst$isDraggingScrollbar())
			return;
		
		wurst$setScrollFromThumb(list,
			event.y() - ext.wurst$getScrollbarDragOffsetY());
		cir.setReturnValue(true);
	}
	
	@Unique
	private ServerSelectionList wurst$getPanelServerList()
	{
		Object self = this;
		if(!(self instanceof ServerSelectionList list))
			return null;
		if(!(self instanceof IServerSelectionListExt ext))
			return null;
		return ext.wurst$isPanelList() ? list : null;
	}
	
	@Unique
	private double wurst$getThumbHeight(ServerSelectionList list)
	{
		int contentHeight =
			Math.max(1, list.children().size() * WURST_ROW_HEIGHT);
		return Math.max(32,
			(list.getHeight() * (double)list.getHeight()) / contentHeight);
	}
	
	@Unique
	private int wurst$getMaxScroll(ServerSelectionList list)
	{
		return Math.max(0,
			list.children().size() * WURST_ROW_HEIGHT - list.getHeight());
	}
	
	@Unique
	private void wurst$setScrollFromThumb(ServerSelectionList list,
		double thumbTop)
	{
		int maxScroll = wurst$getMaxScroll(list);
		if(maxScroll <= 0)
		{
			list.setScrollAmount(0);
			return;
		}
		
		double trackHeight = list.getHeight() - wurst$getThumbHeight(list);
		if(trackHeight <= 0)
		{
			list.setScrollAmount(0);
			return;
		}
		
		double clampedTop = Math.max(list.getY(),
			Math.min(list.getY() + trackHeight, thumbTop));
		double ratio = (clampedTop - list.getY()) / trackHeight;
		list.setScrollAmount(ratio * maxScroll);
	}
}
