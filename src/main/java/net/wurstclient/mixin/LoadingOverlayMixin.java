/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.ARGB;

@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin
{
	@Shadow
	@Final
	@Mutable
	private static int LOGO_BACKGROUND_COLOR;
	
	@Shadow
	@Final
	@Mutable
	private static int LOGO_BACKGROUND_COLOR_DARK;
	
	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void onClassInit(CallbackInfo ci)
	{
		int black = ARGB.color(255, 0, 0, 0);
		LOGO_BACKGROUND_COLOR = black;
		LOGO_BACKGROUND_COLOR_DARK = black;
	}
}
