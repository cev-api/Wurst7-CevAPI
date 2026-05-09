/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.wurstclient.util.ItemStackRenderStateContext;

@Mixin(ItemStackRenderState.class)
public class ItemStackRenderStateMixin
{
	@Shadow
	ItemDisplayContext displayContext;
	
	@Inject(
		method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
		at = @At("HEAD"))
	private void onSubmitHead(PoseStack poseStack,
		SubmitNodeCollector collector, int light, int overlay, int seed,
		CallbackInfo ci)
	{
		ItemStackRenderStateContext.set(displayContext);
	}
	
	@Inject(
		method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
		at = @At("RETURN"))
	private void onSubmitReturn(PoseStack poseStack,
		SubmitNodeCollector collector, int light, int overlay, int seed,
		CallbackInfo ci)
	{
		ItemStackRenderStateContext.clear();
	}
}
