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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Panorama;
import net.wurstclient.WurstClient;
import net.wurstclient.util.ShadertoyBackgroundManager;
import net.wurstclient.util.TitleBackgroundModeManager;
import net.wurstclient.util.TitleScreenBackgroundRenderer;

@Mixin(value = Screen.class, remap = false)
public abstract class ScreenMixin extends AbstractContainerEventHandler
	implements Renderable
{
	@Inject(
		method = "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At("HEAD"),
		cancellable = true)
	public void onExtractBackground(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(WurstClient.INSTANCE.getHax().noBackgroundHack
			.shouldCancelBackground((Screen)(Object)this))
			ci.cancel();
	}
	
	@Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
		at = @At("HEAD"),
		cancellable = true)
	private void onKeyPressed(KeyEvent context,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(context.key() != GLFW.GLFW_KEY_ESCAPE)
			return;
		if(!((Object)this instanceof TitleScreen))
			return;
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		if(!WurstClient.INSTANCE.getOtfs().wurstOptionsOtf
			.isTitleScreenShadertoyBackgroundEnabled())
			return;
		if(ShadertoyBackgroundManager.hasCustomShader())
			return;
		
		TitleBackgroundModeManager.advanceForEnableToggle();
		cir.setReturnValue(true);
	}
	
	@Redirect(
		method = "extractPanorama(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/Panorama;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V"))
	private void onExtractPanorama(Panorama panorama,
		GuiGraphicsExtractor context, int width, int height)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins()
			|| !WurstClient.INSTANCE.getOtfs().wurstOptionsOtf
				.isTitleScreenShadertoyBackgroundEnabled())
		{
			panorama.extractRenderState(context, width, height);
			return;
		}
		
		TitleScreenBackgroundRenderer.addBackground(context, width, height);
	}
}
