/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.wurstclient.mixinterface.IMultiplayerMultiSelect;

@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class OnlineServerEntryMixin extends ServerSelectionList.Entry
{
	@Shadow
	private JoinMultiplayerScreen screen;
	@Shadow
	private ServerData serverData;
	
	@Inject(
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
		at = @At("HEAD"),
		cancellable = true)
	private void onMultiSelectClick(MouseButtonEvent event, boolean doubleClick,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(((IMultiplayerMultiSelect)screen).wurst$handleServerClick(
			(ServerSelectionList.OnlineServerEntry)(Object)this, event,
			doubleClick))
			cir.setReturnValue(true);
	}
	
	@Inject(method = "extractContent", at = @At("HEAD"))
	private void drawMultiSelection(GuiGraphicsExtractor context, int mouseX,
		int mouseY, boolean hovered, float partialTicks, CallbackInfo ci)
	{
		if(!((IMultiplayerMultiSelect)screen).wurst$isMultiSelected(serverData))
			return;
		
		context.fill(getContentX() - 2, getContentY() - 2,
			getContentRight() + 2, getContentBottom() + 2, 0x663577B8);
		context.fill(getContentX() - 2, getContentY() - 2, getContentX() + 1,
			getContentBottom() + 2, 0xFF5DA9FF);
	}
}
