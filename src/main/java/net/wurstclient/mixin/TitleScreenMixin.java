/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.io.IOException;
import java.io.InputStream;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.server.packs.resources.Resource;
import net.wurstclient.WurstClient;
import net.wurstclient.config.BuildConfig;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.options.WurstOptionsScreen;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen
{
	private static final Identifier CEVAPI_TITLE =
		Identifier.fromNamespaceAndPath("wurst", "cevapi_title.png");
	private static final int TARGET_LOGO_WIDTH = 256;
	private static final int TARGET_LOGO_TOP = 30;
	
	private static int titleWidth = -1;
	private static int titleHeight = -1;
	
	private AbstractWidget realmsButton = null;
	private Button wurstOptionsButton;
	
	private TitleScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	/**
	 * Adds the Wurst Options button to the title screen. This mixin must not
	 * run in demo mode, as the Realms button doesn't exist there.
	 */
	@Inject(method = "createNormalMenuOptions(II)I", at = @At("RETURN"))
	private void onAddNormalWidgets(int y, int spacingY,
		CallbackInfoReturnable<Integer> cir)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		for(AbstractWidget button : Screens.getButtons(this))
		{
			if(!button.getMessage().getString().equals(I18n.get("menu.online")))
				continue;
			
			realmsButton = button;
			break;
		}
		
		if(realmsButton == null)
			throw new IllegalStateException("Couldn't find realms button!");
		
		if(NiceWurstModule.showAltManager())
		{
			// make Realms button smaller
			realmsButton.setWidth(98);
			
			// add Wurst Options button
			addRenderableWidget(wurstOptionsButton = Button
				.builder(
					Component.literal(
						NiceWurstModule.getOptionsLabel("Wurst Options")),
					b -> minecraft.setScreen(new WurstOptionsScreen(this)))
				.bounds(width / 2 + 2, realmsButton.getY(), 98, 20).build());
		}else
			wurstOptionsButton = null;
	}
	
	@Inject(method = "tick()V", at = @At("RETURN"))
	private void onTick(CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(WurstClient.INSTANCE.getForkUpdateChecker() != null)
			WurstClient.INSTANCE.getForkUpdateChecker().startIfNeeded();
		
		if(realmsButton == null || wurstOptionsButton == null)
			return;
			
		// adjust Wurst Options button if Realms button has been moved
		// happens when ModMenu is installed
		wurstOptionsButton.setY(realmsButton.getY());
	}
	
	@Inject(at = @At("TAIL"),
		method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
	private void onRender(GuiGraphics graphics, int mouseX, int mouseY,
		float partialTicks, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		Font font = minecraft.font;
		String brand = NiceWurstModule.isActive() ? "NiceWurst" : "Wurst";
		String baseText = brand + " " + BuildConfig.MOD_VERSION + " v"
			+ BuildConfig.FORK_RELEASE_VERSION;
		String suffix = WurstClient.INSTANCE.getForkUpdateChecker() == null ? ""
			: WurstClient.INSTANCE.getForkUpdateChecker().getStatusSuffix();
		String text = baseText + suffix;
		graphics.drawString(font, Component.literal(text).getVisualOrderText(),
			4, 4, 0xFFFFFFFF, true);
	}
	
	/**
	 * Replaces the vanilla Minecraft logo on the title screen with the client
	 * supplied CevAPI logo.
	 */
	@Redirect(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V"))
	private void onRenderLogo(LogoRenderer logoRenderer,
		GuiGraphicsExtractor graphics, int width, float fade)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		ensureTitleDimensions();
		if(titleWidth <= 0 || titleHeight <= 0)
			return;
		
		float scale = TARGET_LOGO_WIDTH / (float)titleWidth;
		int x = Math.round((width / 2F - TARGET_LOGO_WIDTH / 2F) / scale);
		int y = Math.round(TARGET_LOGO_TOP / scale);
		graphics.pose().pushMatrix();
		graphics.pose().scale(scale);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CEVAPI_TITLE, x, y, 0.0F,
			0.0F, titleWidth, titleHeight, titleWidth, titleHeight,
			ARGB.white(fade));
		graphics.pose().popMatrix();
	}
	
	@Inject(
		method = "registerTextures(Lnet/minecraft/client/renderer/texture/TextureManager;)V",
		at = @At("TAIL"))
	private static void onRegisterTextures(TextureManager textureManager,
		CallbackInfo ci)
	{
		textureManager.registerForNextReload(CEVAPI_TITLE);
	}
	
	/**
	 * Stops the multiplayer button being grayed out if the user's Microsoft
	 * account is parental-control'd or banned from online play.
	 */
	@Inject(
		method = "getMultiplayerDisabledReason()Lnet/minecraft/network/chat/Component;",
		at = @At("HEAD"),
		cancellable = true)
	private void onGetMultiplayerDisabledText(
		CallbackInfoReturnable<Component> cir)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		cir.setReturnValue(null);
	}
	
	private static void ensureTitleDimensions()
	{
		if(titleWidth > 0 && titleHeight > 0)
			return;
		
		if(WurstClient.MC == null
			|| WurstClient.MC.getResourceManager() == null)
			return;
		
		for(Resource resource : WurstClient.MC.getResourceManager()
			.getResourceStack(CEVAPI_TITLE))
		{
			try(InputStream input = resource.open();
				NativeImage image = NativeImage.read(input))
			{
				titleWidth = image.getWidth();
				titleHeight = image.getHeight();
				return;
			}catch(IOException e)
			{
				// Try the next resource source, then fall back to the default.
			}
		}
		
		if(titleWidth <= 0 || titleHeight <= 0)
		{
			titleWidth = TARGET_LOGO_WIDTH;
			titleHeight = 64;
		}
	}
}
