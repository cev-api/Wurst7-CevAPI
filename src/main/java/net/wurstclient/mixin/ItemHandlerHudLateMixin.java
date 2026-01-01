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

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;

@Mixin(Gui.class)
public class ItemHandlerHudLateMixin
{
	// Do not instantiate a separate HUD here to avoid duplicate instances
	// and potential side-effects. The HUD is rendered via IngameHUD.
	
	@Inject(at = @At("TAIL"),
		method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V")
	private void wurst$renderItemHandlerHud(GuiGraphics context,
		DeltaTracker tickCounter, CallbackInfo ci)
	{
		// Disabled: IngameHUD already handles rendering the ItemHandlerHud
		// to avoid duplicate HUD instances. This mixin left in place for
		// compatibility but does not invoke the HUD render method.
		return;
	}
}
