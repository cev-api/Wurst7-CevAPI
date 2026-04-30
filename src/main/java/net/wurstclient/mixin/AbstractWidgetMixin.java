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

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.mixinterface.IServerSelectionListExt;

@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetMixin
{
	private static final int WURST_ROW_HEIGHT = 36;
	private static final int WURST_SCROLLBAR_WIDTH = 8;
	
	@Inject(
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
		at = @At("HEAD"),
		cancellable = true)
	private void wurst$onPanelScrollbarClicked(MouseButtonEvent event,
		boolean doubleClick, CallbackInfoReturnable<Boolean> cir)
	{
		if(event.button() != 0)
			return;
		
		ServerSelectionList list = wurst$getPanelServerList();
		if(list == null || !wurst$isOverScrollbar(list, event.x(), event.y()))
			return;
		
		IServerSelectionListExt ext = (IServerSelectionListExt)list;
		double thumbTop = wurst$getThumbTop(list);
		double thumbHeight = wurst$getThumbHeight(list);
		if(event.y() >= thumbTop && event.y() <= thumbTop + thumbHeight)
			ext.wurst$setScrollbarDragOffsetY(event.y() - thumbTop);
		else
			ext.wurst$setScrollbarDragOffsetY(thumbHeight / 2.0);
		
		ext.wurst$setDraggingScrollbar(true);
		wurst$setScrollFromThumb(list,
			event.y() - ext.wurst$getScrollbarDragOffsetY());
		cir.setReturnValue(true);
	}
	
	@Inject(
		method = "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
		at = @At("HEAD"),
		cancellable = true)
	private void wurst$onPanelScrollbarReleased(MouseButtonEvent event,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(event.button() != 0)
			return;
		
		ServerSelectionList list = wurst$getPanelServerList();
		if(list == null)
			return;
		
		IServerSelectionListExt ext = (IServerSelectionListExt)list;
		if(!ext.wurst$isDraggingScrollbar())
			return;
		
		ext.wurst$setDraggingScrollbar(false);
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
	private boolean wurst$isOverScrollbar(ServerSelectionList list,
		double mouseX, double mouseY)
	{
		int scrollbarX = list.getX() + list.getWidth() - WURST_SCROLLBAR_WIDTH;
		return mouseX >= scrollbarX
			&& mouseX <= scrollbarX + WURST_SCROLLBAR_WIDTH
			&& mouseY >= list.getY()
			&& mouseY <= list.getY() + list.getHeight();
	}
	
	@Unique
	private double wurst$getThumbTop(ServerSelectionList list)
	{
		int maxScroll = wurst$getMaxScroll(list);
		if(maxScroll <= 0)
			return list.getY();
		
		double trackHeight = list.getHeight() - wurst$getThumbHeight(list);
		return list.getY() + trackHeight * (list.scrollAmount() / maxScroll);
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
