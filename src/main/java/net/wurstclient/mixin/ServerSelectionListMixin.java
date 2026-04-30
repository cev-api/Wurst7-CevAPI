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
	
	@Override
	public boolean wurst$isPanelList()
	{
		return wurst$panelList;
	}
	
	@Override
	public boolean wurst$isDraggingScrollbar()
	{
		return wurst$draggingScrollbar;
	}
	
	@Override
	public void wurst$setDraggingScrollbar(boolean draggingScrollbar)
	{
		wurst$draggingScrollbar = draggingScrollbar;
	}
	
	@Override
	public double wurst$getScrollbarDragOffsetY()
	{
		return wurst$scrollbarDragOffsetY;
	}
	
	@Override
	public void wurst$setScrollbarDragOffsetY(double scrollbarDragOffsetY)
	{
		wurst$scrollbarDragOffsetY = scrollbarDragOffsetY;
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
	
}
