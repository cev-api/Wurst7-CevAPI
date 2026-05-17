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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.ARGB;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.WurstOptionsOtf;

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
	
	@Unique
	private static int wurst$vanillaLogoBackgroundColor;
	
	@Unique
	private static int wurst$vanillaLogoBackgroundColorDark;
	
	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void onClassInit(CallbackInfo ci)
	{
		wurst$vanillaLogoBackgroundColor = LOGO_BACKGROUND_COLOR;
		wurst$vanillaLogoBackgroundColorDark = LOGO_BACKGROUND_COLOR_DARK;
		wurst$applyConfiguredLogoBackgroundColor();
	}
	
	@Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
	private void onExtractRenderState(GuiGraphics graphics, int mouseX,
		int mouseY, float partialTicks, CallbackInfo ci)
	{
		wurst$applyConfiguredLogoBackgroundColor();
	}
	
	@Unique
	private static void wurst$applyConfiguredLogoBackgroundColor()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		if(wurst == null || wurst.getOtfs() == null)
			return;
		
		WurstOptionsOtf options = wurst.getOtfs().wurstOptionsOtf;
		if(options == null
			|| !options.getCustomMojangLogoBackgroundSetting().isChecked())
		{
			LOGO_BACKGROUND_COLOR = wurst$vanillaLogoBackgroundColor;
			LOGO_BACKGROUND_COLOR_DARK = wurst$vanillaLogoBackgroundColorDark;
			return;
		}
		
		int rgb = options.getMojangLogoBackgroundColorSetting().getColorI();
		int argb =
			ARGB.color(255, ARGB.red(rgb), ARGB.green(rgb), ARGB.blue(rgb));
		LOGO_BACKGROUND_COLOR = argb;
		LOGO_BACKGROUND_COLOR_DARK = argb;
	}
}
