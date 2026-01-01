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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.wurstclient.nicewurst.NiceWurstModule;

@Mixin(Font.class)
public abstract class TextRendererMixin
{
	@ModifyVariable(
		method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private DisplayMode nicewurst$enforceDepthOnStringLabels(
		DisplayMode originalLayer)
	{
		return NiceWurstModule.enforceTextLayer(originalLayer);
	}
	
	@ModifyVariable(
		method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private DisplayMode nicewurst$enforceDepthOnComponentLabels(
		DisplayMode originalLayer)
	{
		return NiceWurstModule.enforceTextLayer(originalLayer);
	}
}
