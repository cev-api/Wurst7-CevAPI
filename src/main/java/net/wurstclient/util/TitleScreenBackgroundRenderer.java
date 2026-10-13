/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import org.joml.Matrix3x2f;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.wurstclient.TitleScreenShaderPipelines;
import net.wurstclient.WurstClient;
import net.wurstclient.util.TitleBackgroundModeManager.Mode;

public enum TitleScreenBackgroundRenderer
{
	;
	private static final String CONTROLS_HINT =
		"X: X-Ray, ESC: Change Realm, CTRL: Fly";
	private static final long HINT_DURATION_MS = 3_500L;
	private static final long HINT_FADE_MS = 250L;
	private static boolean cameraMoving;
	private static boolean leftControlDown;
	private static long hintStartTime = -1L;
	private static long lastHintFrameTime = -1L;
	
	public static void addBackground(GuiGraphicsExtractor context, int width,
		int height)
	{
		Matrix3x2f pose = new Matrix3x2f();
		ScreenRectangle bounds = new ScreenRectangle(0, 0, width, height);
		Mode mode = TitleBackgroundModeManager.getCurrentMode();
		int time = (int)((System.currentTimeMillis() / 50L) & 0xFFFF);
		boolean customShader = ShadertoyBackgroundManager.hasCustomShader();
		updateCameraMovement();
		AbstractTexture texture = Minecraft.getInstance().getTextureManager()
			.getTexture(mode.getAtlasId());
		boolean xray = WurstClient.INSTANCE.getHax().xRayHack.isEnabled();
		int packedColor =
			customShader ? packCustomShaderColor(time, mode, xray, cameraMoving)
				: packBuiltinShaderColor(time, mode, cameraMoving);
		context.guiRenderState.addGuiElement(new CustomQuadRenderState(
			customShader
				? TitleScreenShaderPipelines.TITLE_SHADERTOY_BACKGROUND_CUSTOM
				: TitleScreenShaderPipelines.TITLE_SHADERTOY_BACKGROUND,
			ShadertoyBackgroundManager.createCustomTextureSetup(texture), pose,
			-1, -1, 1, -1, 1, 1, -1, 1, packedColor, packedColor, packedColor,
			packedColor, null, bounds));
		drawControlsHint(context, width);
	}
	
	private static void updateCameraMovement()
	{
		boolean leftControlDownNow =
			InputConstants.isKeyDown(InputConstants.KEY_LCONTROL);
		if(leftControlDownNow && !leftControlDown)
			cameraMoving = !cameraMoving;
		leftControlDown = leftControlDownNow;
	}
	
	private static void drawControlsHint(GuiGraphicsExtractor context,
		int width)
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
		context.text(Minecraft.getInstance().font, CONTROLS_HINT, x, 10,
			(alpha << 24) | 0xFFFFFF);
	}
	
	private static int packCustomShaderColor(int time, Mode mode, boolean xray,
		boolean cameraMoving)
	{
		int flags =
			mode.getShaderIndex() | (xray ? 0x4 : 0) | (cameraMoving ? 0x8 : 0);
		return 0xFF000000 | ((time >> 8) << 16) | ((time & 0xFF) << 8) | flags;
	}
	
	private static int packBuiltinShaderColor(int time, Mode mode,
		boolean cameraMoving)
	{
		boolean xray = WurstClient.INSTANCE.getHax().xRayHack.isEnabled();
		int red = ((time & 0xF) << 4) | (mode.getShaderIndex() << 2)
			| (xray ? 0x2 : 0);
		int green = cameraMoving ? 0x81 : 0x80;
		int blue = 0x80;
		int alpha = (time >> 4) & 0xFF;
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}
}
