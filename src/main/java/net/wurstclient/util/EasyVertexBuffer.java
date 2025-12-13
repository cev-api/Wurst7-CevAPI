/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.function.Consumer;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexBuffer.Usage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.wurstclient.nicewurst.NiceWurstModule;

/**
 * Thin helper around {@link VertexBuffer} that keeps the old 1.21.1 rendering
 * flow intact.
 */
public final class EasyVertexBuffer implements AutoCloseable
{
	private final VertexBuffer vertexBuffer;
	
	public static EasyVertexBuffer createAndUpload(Mode drawMode,
		VertexFormat format, Consumer<VertexConsumer> callback)
	{
		BufferBuilder builder =
			Tesselator.getInstance().begin(drawMode, format);
		callback.accept(builder);
		
		MeshData data = builder.build();
		if(data == null)
			return new EasyVertexBuffer();
		
		return new EasyVertexBuffer(data);
	}
	
	private EasyVertexBuffer(MeshData buffer)
	{
		vertexBuffer = new VertexBuffer(Usage.STATIC);
		vertexBuffer.bind();
		vertexBuffer.upload(buffer);
		VertexBuffer.unbind();
	}
	
	private EasyVertexBuffer()
	{
		vertexBuffer = null;
	}
	
	public void draw(PoseStack matrixStack, RenderType layer)
	{
		draw(matrixStack, layer, 1F, 1F, 1F, 1F);
	}
	
	public void draw(PoseStack matrixStack, RenderType layer, float[] rgb,
		float alpha)
	{
		float red = rgb != null && rgb.length > 0 ? rgb[0] : 1F;
		float green = rgb != null && rgb.length > 1 ? rgb[1] : 1F;
		float blue = rgb != null && rgb.length > 2 ? rgb[2] : 1F;
		draw(matrixStack, layer, red, green, blue, alpha);
	}
	
	public void draw(PoseStack matrixStack, RenderType layer, float red,
		float green, float blue, float alpha)
	{
		if(vertexBuffer == null)
			return;
		
		RenderType renderType = NiceWurstModule.enforceDepthTest(layer);
		Matrix4f modelMatrix = matrixStack.last().pose();
		Matrix4f projectionMatrix = RenderSystem.getProjectionMatrix();
		
		renderType.setupRenderState();
		vertexBuffer.bind();
		
		ShaderInstance shader = RenderSystem.getShader();
		if(shader != null)
		{
			RenderSystem.setShaderColor(red, green, blue, alpha);
			vertexBuffer.drawWithShader(modelMatrix, projectionMatrix, shader);
			RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
		}
		
		VertexBuffer.unbind();
		renderType.clearRenderState();
	}
	
	@Override
	public void close()
	{
		if(vertexBuffer != null)
			vertexBuffer.close();
	}
}
