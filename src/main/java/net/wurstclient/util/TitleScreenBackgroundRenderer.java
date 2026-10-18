/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.wurstclient.TitleScreenShaderPipelines;
import net.wurstclient.WurstClient;
import net.wurstclient.util.TitleBackgroundModeManager.Mode;

public enum TitleScreenBackgroundRenderer
{
	;
	private static final String CONTROLS_HINT =
		"X: X-Ray, ESC: Change Realm, CTRL: Pause / Fly";
	private static final long HINT_DURATION_MS = 3_500L;
	private static final long HINT_FADE_MS = 250L;
	private static final long START_TIME = System.nanoTime();
	private static final TitleBackgroundControls CONTROLS =
		new TitleBackgroundControls();
	private static final TitleBackgroundAnimation ANIMATION =
		new TitleBackgroundAnimation();
	private static long hintStartTime = -1L;
	private static long lastHintFrameTime = -1L;
	
	/**
	 * Backgrounds drawn by {@code Screen.renderBackground()} after the panorama
	 * would otherwise composite over the shader quad: the blur stratum and the
	 * {@code MENU_BACKGROUND} dirt texture.
	 */
	public static boolean shouldRenderShaderBackground()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null || mc.level != null)
			return false;
		
		WurstClient wurst = WurstClient.INSTANCE;
		if(wurst == null || !wurst.isEnabled() || wurst.getOtfs() == null
			|| wurst.shouldHideWurstUiMixins())
			return false;
		
		return wurst.getOtfs().wurstOptionsOtf
			.isTitleScreenShadertoyBackgroundEnabled();
	}
	
	public static void addBackground(GuiGraphics context, int width, int height)
	{
		ScreenRectangle bounds = new ScreenRectangle(0, 0, width, height);
		Mode mode = TitleBackgroundModeManager.getCurrentMode();
		int time =
			(int)(((System.nanoTime() - START_TIME) / 50_000_000L) & 0xFFFF);
		boolean customShader = ShadertoyBackgroundManager.hasCustomShader();
		AbstractTexture texture = Minecraft.getInstance().getTextureManager()
			.getTexture(mode.getAtlasId());
		int packedColor = packShaderColor(time, mode, CONTROLS.isXray(),
			CONTROLS.isCameraMoving());
		float animationTime =
			ANIMATION.sample(System.nanoTime(), CONTROLS.isCameraMoving());
		context.guiRenderState.submitGuiElement(new TitleBackgroundRenderState(
			customShader
				? TitleScreenShaderPipelines.TITLE_SHADERTOY_BACKGROUND_CUSTOM
				: TitleScreenShaderPipelines.TITLE_SHADERTOY_BACKGROUND,
			customShader
				? ShadertoyBackgroundManager.createCustomTextureSetup(texture)
				: TextureSetup.singleTexture(texture.getTextureView(),
					texture.getSampler()),
			packedColor, animationTime, bounds));
		drawPanoramaOverlay(context, width, height);
		drawControlsHint(context, width);
	}
	
	/**
	 * Vanilla draws this gradient inside {@code PanoramaRenderer.render}, which
	 * is skipped together with the panorama. Without it the shader background
	 * washes out and menu text becomes hard to read.
	 */
	private static void drawPanoramaOverlay(GuiGraphics context, int width,
		int height)
	{
		context.blit(RenderPipelines.GUI_TEXTURED,
			PanoramaRenderer.PANORAMA_OVERLAY, 0, 0, 0.0F, 0.0F, width, height,
			16, 128);
	}
	
	public static boolean handleKeyPress(int key, int action)
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null || !(mc.screen instanceof TitleScreen)
			|| mc.getOverlay() != null || !shouldRenderShaderBackground())
			return false;
		
		TitleBackgroundControls.Action result = CONTROLS.handleKey(key, action);
		if(result == TitleBackgroundControls.Action.REALM)
			TitleBackgroundModeManager.advanceForEnableToggle();
		return result != TitleBackgroundControls.Action.NONE;
	}
	
	private static void drawControlsHint(GuiGraphics context, int width)
	{
		long now = System.currentTimeMillis();
		if(hintStartTime < 0L || now - lastHintFrameTime > 1_000L)
			hintStartTime = now;
		lastHintFrameTime = now;
		long elapsed = now - hintStartTime;
		if(elapsed >= HINT_DURATION_MS)
			return;
		
		float fadeIn = Math.min(1.0F, elapsed / (float)HINT_FADE_MS);
		float fadeOut =
			Math.min(1.0F, (HINT_DURATION_MS - elapsed) / (float)HINT_FADE_MS);
		int alpha = Math.round(255.0F * Math.min(fadeIn, fadeOut));
		int x = width - Minecraft.getInstance().font.width(CONTROLS_HINT) - 10;
		int backgroundAlpha = Math.round(alpha * 0.72F);
		context.fill(x - 5, 6, width - 6,
			16 + Minecraft.getInstance().font.lineHeight,
			(backgroundAlpha << 24) | 0x00101010);
		context.drawString(Minecraft.getInstance().font, CONTROLS_HINT, x, 10,
			(alpha << 24) | 0xFFFFFF);
	}
	
	private static int packShaderColor(int time, Mode mode, boolean xray,
		boolean cameraMoving)
	{
		int flags =
			mode.getShaderIndex() | (xray ? 0x4 : 0) | (cameraMoving ? 0x8 : 0);
		return 0xFF000000 | ((time >> 8) << 16) | ((time & 0xFF) << 8) | flags;
	}
	
}
