/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.font.TextRenderer.TextLayerType;
import net.wurstclient.nicewurst.NiceWurstModule;

@Mixin(TextRenderer.class)
public abstract class TextRendererMixin
{
	@ModifyVariable(
		method = "draw(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private TextLayerType nicewurst$enforceDepthOnStringLabels(
		TextLayerType originalLayer)
	{
		return NiceWurstModule.enforceTextLayer(originalLayer);
	}
	
	@ModifyVariable(
		method = "draw(Lnet/minecraft/text/Text;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private TextLayerType nicewurst$enforceDepthOnComponentLabels(
		TextLayerType originalLayer)
	{
		return NiceWurstModule.enforceTextLayer(originalLayer);
	}
}
