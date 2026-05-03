/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.cevapi.security.ResourcePackProtector;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.GUIRenderListener.GUIRenderEvent;
import net.wurstclient.hack.HackList;

@Mixin(Gui.class)
public class GuiMixin
{
	// runs after extractScoreboardSidebar()
	// and before tabList.setVisible()
	@Inject(
		method = "extractTabList(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
		at = @At("HEAD"))
	private void onRenderPlayerList(GuiGraphicsExtractor context,
		DeltaTracker tickCounter, CallbackInfo ci)
	{
		if(WurstClient.MC.debugEntries.isOverlayVisible())
			return;
		
		float tickDelta = tickCounter.getGameTimeDeltaPartialTick(true);
		EventManager.fire(new GUIRenderEvent(context, tickDelta));
	}
	
	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
	private void onRenderVignetteOverlay(GuiGraphicsExtractor context,
		Entity entity, CallbackInfo ci)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null || !hax.noVignetteHack.isEnabled())
			return;
		
		ci.cancel();
	}
	
	@Inject(
		method = "extractScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onExtractScoreboardSidebar(GuiGraphicsExtractor context,
		DeltaTracker tickCounter, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().renderAdjustHack
			.shouldHideScoreboard())
			ci.cancel();
	}
	
	@Inject(
		method = "extractBossOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onExtractBossOverlay(GuiGraphicsExtractor context,
		DeltaTracker tickCounter, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().renderAdjustHack.shouldHideBossBars())
			ci.cancel();
	}
	
	@Inject(at = @At("TAIL"), method = "tick")
	private void onTick(CallbackInfo ci)
	{
		ResourcePackProtector.flushToasts();
	}
}
