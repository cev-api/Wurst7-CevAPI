/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;

public record TitleBackgroundRenderState(RenderPipeline pipeline,
	TextureSetup textureSetup, int color, float animationTime,
	ScreenRectangle bounds) implements GuiElementRenderState
{
	@Override
	public ScreenRectangle scissorArea()
	{
		return null;
	}
	
	@Override
	public void buildVertices(VertexConsumer vertices)
	{
		// The shader uses clip-space XY and reads Z as a full-precision clock.
		// Its gl_Position ignores Z, so elapsed time cannot affect depth.
		vertices.addVertex(-1, -1, animationTime).setColor(color);
		vertices.addVertex(1, -1, animationTime).setColor(color);
		vertices.addVertex(1, 1, animationTime).setColor(color);
		vertices.addVertex(-1, 1, animationTime).setColor(color);
	}
}
