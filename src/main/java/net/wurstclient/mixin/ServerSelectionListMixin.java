/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.wurstclient.mixinterface.IServerSelectionListExt;

@Mixin(ServerSelectionList.class)
public class ServerSelectionListMixin implements IServerSelectionListExt
{
	private static final int WURST_ROW_HEIGHT = 36;
	private static final int WURST_SCROLLBAR_WIDTH = 8;
	
	@Unique
	private boolean wurst$panelList;
	@Unique
	private boolean wurst$draggingScrollbar;
	@Unique
	private double wurst$scrollbarDragOffsetY;
	
	@Override
	public void wurst$setPanelList(boolean panelList)
	{
		wurst$panelList = panelList;
	}
	
	@Inject(method = "refreshEntries()V", at = @At("TAIL"))
	private void afterRefreshEntries(CallbackInfo ci)
	{
		if(!wurst$panelList)
			return;
		
		ServerSelectionList list = (ServerSelectionList)(Object)this;
		List<ServerSelectionList.Entry> onlineEntries = new ArrayList<>();
		for(ServerSelectionList.Entry entry : list.children())
			if(entry instanceof ServerSelectionList.OnlineServerEntry)
				onlineEntries.add(entry);
			
		list.replaceEntries(onlineEntries);
	}
	
	@Inject(method = "getRowWidth()I", at = @At("HEAD"), cancellable = true)
	private void getPanelRowWidth(CallbackInfoReturnable<Integer> cir)
	{
		if(!wurst$panelList)
			return;
		
		ServerSelectionList list = (ServerSelectionList)(Object)this;
		cir.setReturnValue(Math.max(120, list.getWidth() - 20));
	}
	
	@Inject(method = "method_65507()I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0,
		remap = false)
	private void getPanelScrollBarX(CallbackInfoReturnable<Integer> cir)
	{
		if(!wurst$panelList)
			return;
		
		ServerSelectionList list = (ServerSelectionList)(Object)this;
		cir.setReturnValue(list.getX() + list.getWidth() - 8);
	}
	
	@Inject(method = "method_25402(Lnet/minecraft/class_11909;Z)Z",
		at = @At("HEAD"),
		cancellable = true,
		require = 0,
		remap = false)
	private void onPanelScrollbarClicked(
		net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(!wurst$panelList || event.button() != 0)
			return;
		
		ServerSelectionList list = (ServerSelectionList)(Object)this;
		if(!wurst$isOverScrollbar(list, event.x(), event.y()))
			return;
		
		double thumbTop = wurst$getThumbTop(list);
		double thumbHeight = wurst$getThumbHeight(list);
		if(event.y() >= thumbTop && event.y() <= thumbTop + thumbHeight)
			wurst$scrollbarDragOffsetY = event.y() - thumbTop;
		else
			wurst$scrollbarDragOffsetY = thumbHeight / 2.0;
		
		wurst$draggingScrollbar = true;
		wurst$setScrollFromThumb(list, event.y() - wurst$scrollbarDragOffsetY);
		cir.setReturnValue(true);
	}
	
	@Inject(method = "method_25403(Lnet/minecraft/class_11909;DD)Z",
		at = @At("HEAD"),
		cancellable = true,
		require = 0,
		remap = false)
	private void onPanelScrollbarDragged(
		net.minecraft.client.input.MouseButtonEvent event, double dragX,
		double dragY, CallbackInfoReturnable<Boolean> cir)
	{
		if(!wurst$panelList || !wurst$draggingScrollbar || event.button() != 0)
			return;
		
		ServerSelectionList list = (ServerSelectionList)(Object)this;
		wurst$setScrollFromThumb(list, event.y() - wurst$scrollbarDragOffsetY);
		cir.setReturnValue(true);
	}
	
	@Inject(method = "method_25406(Lnet/minecraft/class_11909;)Z",
		at = @At("HEAD"),
		cancellable = true,
		require = 0,
		remap = false)
	private void onPanelScrollbarReleased(
		net.minecraft.client.input.MouseButtonEvent event,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(!wurst$panelList || !wurst$draggingScrollbar || event.button() != 0)
			return;
		
		wurst$draggingScrollbar = false;
		cir.setReturnValue(true);
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
