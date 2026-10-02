/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import java.util.List;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * Original rectangle vertices, combined behind one conservative screen area.
 */
public record GuiRectangleBatch(List<ColoredRectangleRenderState> rectangles,
	ScreenRectangle bounds) implements GuiElementRenderState
{
	public GuiRectangleBatch
	{
		rectangles = List.copyOf(rectangles);
	}
	
	@Override
	public void buildVertices(VertexConsumer target)
	{
		for(ColoredRectangleRenderState rectangle : rectangles)
			rectangle.buildVertices(target);
	}
	
	@Override
	public RenderPipeline pipeline()
	{
		return rectangles.getFirst().pipeline();
	}
	
	@Override
	public TextureSetup textureSetup()
	{
		return rectangles.getFirst().textureSetup();
	}
	
	@Override
	public ScreenRectangle scissorArea()
	{
		return rectangles.getFirst().scissorArea();
	}
}
