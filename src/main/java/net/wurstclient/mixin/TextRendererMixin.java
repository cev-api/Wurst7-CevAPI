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
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.util.ModMenuTextUtils;

@Mixin(Font.class)
public abstract class TextRendererMixin
{
	
	@ModifyVariable(
		method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private String wurst$hideModMenuCounts(String text)
	{
		return ModMenuTextUtils.adjustModCountText(text);
	}
	
	@ModifyVariable(
		method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private Component wurst$hideModMenuCounts(Component text)
	{
		if(text == null)
			return null;
		
		String adjusted = ModMenuTextUtils.adjustModCountText(text.getString());
		if(adjusted.equals(text.getString()))
			return text;
		
		return Component.literal(adjusted).setStyle(text.getStyle());
	}
	
	@ModifyVariable(
		method = "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0,
		require = 0)
	private FormattedCharSequence wurst$hideModMenuCounts(
		FormattedCharSequence text)
	{
		if(text == null)
			return null;
		
		String raw = net.wurstclient.util.ChatUtils.getAsString(text);
		String adjusted = ModMenuTextUtils.adjustModCountText(raw);
		if(adjusted.equals(raw))
			return text;
		
		return Component.literal(adjusted).getVisualOrderText();
	}
	
	@ModifyVariable(
		method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0,
		require = 0)
	private String wurst$hideModMenuCountsPrepare(String text)
	{
		return ModMenuTextUtils.adjustModCountText(text);
	}
	
	@ModifyVariable(
		method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0,
		require = 0)
	private FormattedCharSequence wurst$hideModMenuCountsPrepare(
		FormattedCharSequence text)
	{
		if(text == null)
			return null;
		
		String raw = net.wurstclient.util.ChatUtils.getAsString(text);
		String adjusted = ModMenuTextUtils.adjustModCountText(raw);
		if(adjusted.equals(raw))
			return text;
		
		return Component.literal(adjusted).getVisualOrderText();
	}
	
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
