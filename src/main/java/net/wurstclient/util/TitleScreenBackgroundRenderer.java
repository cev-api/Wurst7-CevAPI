/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import org.joml.Matrix3x2f;

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
	
	public static void addBackground(GuiGraphicsExtractor context, int width,
		int height)
	{
		Matrix3x2f pose = new Matrix3x2f();
		ScreenRectangle bounds = new ScreenRectangle(0, 0, width, height);
		Mode mode = TitleBackgroundModeManager.getCurrentMode();
		int time = (int)((System.currentTimeMillis() / 50L) & 0xFFFF);
		AbstractTexture texture = Minecraft.getInstance().getTextureManager()
			.getTexture(mode.getAtlasId());
		boolean customShader = ShadertoyBackgroundManager.hasCustomShader();
		int packedColor = customShader ? packCustomShaderColor(time)
			: packBuiltinShaderColor(time, mode);
		context.guiRenderState.addGuiElement(new CustomQuadRenderState(
			customShader
				? TitleScreenShaderPipelines.TITLE_SHADERTOY_BACKGROUND_CUSTOM
				: TitleScreenShaderPipelines.TITLE_SHADERTOY_BACKGROUND,
			ShadertoyBackgroundManager.createCustomTextureSetup(texture), pose,
			-1, -1, 1, -1, 1, 1, -1, 1, packedColor, packedColor, packedColor,
			packedColor, null, bounds));
	}
	
	private static int packCustomShaderColor(int time)
	{
		return 0xFF000000 | ((time >> 8) << 16) | ((time & 0xFF) << 8);
	}
	
	private static int packBuiltinShaderColor(int time, Mode mode)
	{
		boolean xray = WurstClient.INSTANCE.getHax().xRayHack.isEnabled();
		int red = ((time & 0xF) << 4) | (mode.getShaderIndex() << 2)
			| (xray ? 0x2 : 0);
		int green = 0x80;
		int blue = 0x80;
		int alpha = (time >> 4) & 0xFF;
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}
}
