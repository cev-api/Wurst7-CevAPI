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
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.wurstclient.TitleScreenShaderPipelines;

public enum TitleScreenBackgroundRenderer
{
	;
	
	private static final Identifier BLOCK_ATLAS =
		Identifier.parse("wurst:textures/shader_blocks.png");
	
	public static void addBackground(GuiGraphicsExtractor context, int width,
		int height)
	{
		Matrix3x2f pose = new Matrix3x2f();
		ScreenRectangle bounds = new ScreenRectangle(0, 0, width, height);
		int time = (int)((System.currentTimeMillis() / 50L) & 0xFFFF);
		int timeColor = 0xFF0000FF | ((time >> 8) << 16) | ((time & 0xFF) << 8);
		AbstractTexture texture =
			Minecraft.getInstance().getTextureManager().getTexture(BLOCK_ATLAS);
		context.guiRenderState.addGuiElement(new CustomQuadRenderState(
			TitleScreenShaderPipelines.TITLE_SHADERTOY_BACKGROUND,
			TextureSetup.singleTexture(texture.getTextureView(),
				texture.getSampler()),
			pose, -1, -1, 1, -1, 1, 1, -1, 1, timeColor, timeColor, timeColor,
			timeColor, null, bounds));
	}
}
