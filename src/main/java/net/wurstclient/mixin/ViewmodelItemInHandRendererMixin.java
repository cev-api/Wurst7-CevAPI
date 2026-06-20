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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.ViewmodelHack;

@Mixin(ItemInHandRenderer.class)
public class ViewmodelItemInHandRendererMixin
{
	@Inject(
		method = "submitArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onSubmitArmWithItem(AbstractClientPlayer player,
		float tickProgress, float pitch, InteractionHand hand,
		float swingProgress, ItemStack item, float equipProgress,
		PoseStack matrices, SubmitNodeCollector collector, int light,
		CallbackInfo ci)
	{
		ViewmodelHack viewmodel = WurstClient.INSTANCE.getHax().viewmodelHack;
		if(viewmodel.shouldHide(player, hand))
			ci.cancel();
	}
	
	@Inject(
		method = "submitArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
	private void onSubmitArmWithItemRenderItem(AbstractClientPlayer player,
		float tickProgress, float pitch, InteractionHand hand,
		float swingProgress, ItemStack item, float equipProgress,
		PoseStack matrices, SubmitNodeCollector collector, int light,
		CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().viewmodelHack.applyTransform(player, hand,
			matrices);
	}
	
	@Inject(method = "renderMapHand", at = @At("HEAD"), cancellable = true)
	private void onRenderMapHand(PoseStack matrices,
		SubmitNodeCollector collector, int light, HumanoidArm arm,
		CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().viewmodelHack.shouldHide(arm))
			ci.cancel();
	}
	
	@Inject(method = "renderMapHand",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;isModelPartShown(Lnet/minecraft/world/entity/player/PlayerModelPart;)Z",
			shift = At.Shift.AFTER))
	private void onRenderMapHandShown(PoseStack matrices,
		SubmitNodeCollector collector, int light, HumanoidArm arm,
		CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().viewmodelHack.applyTransform(arm,
			matrices);
	}
	
	@Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true)
	private void onRenderPlayerArm(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		float swingProgress, HumanoidArm arm, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().viewmodelHack.shouldHide(arm))
			ci.cancel();
	}
	
	@Inject(method = "renderPlayerArm",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/AbstractClientPlayer;isModelPartShown(Lnet/minecraft/world/entity/player/PlayerModelPart;)Z",
			shift = At.Shift.AFTER))
	private void onRenderPlayerArmShown(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		float swingProgress, HumanoidArm arm, CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().viewmodelHack.applyTransform(arm,
			matrices);
	}
	
	@Inject(method = "renderOneHandedMap", at = @At("HEAD"), cancellable = true)
	private void onRenderOneHandedMap(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		HumanoidArm arm, float swingProgress, ItemStack stack, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().viewmodelHack.shouldHide(arm))
			ci.cancel();
	}
	
	@Inject(method = "renderOneHandedMap",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderMap(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/item/ItemStack;)V"))
	private void onRenderOneHandedMapRenderMap(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		HumanoidArm arm, float swingProgress, ItemStack stack, CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().viewmodelHack.applyTransform(arm,
			matrices);
	}
}
