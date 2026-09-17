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

import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;
import net.wurstclient.hacks.ViewmodelHack;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin
{
	@Inject(
		method = "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onRenderHandsWithItems(float tickProgress, PoseStack matrices,
		SubmitNodeCollector entityRenderCommandQueue, PlayerRenderState player,
		FirstPersonHandsAndItemsRenderState handsAndItems, CallbackInfo ci)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax.freecamHack.shouldHideHand()
			|| hax.remoteViewHack.shouldHideHand())
			ci.cancel();
	}
	
	@Inject(
		method = "submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onSubmitArmWithItem(PlayerRenderState player,
		FirstPersonHandsAndItemsRenderState handsAndItems, float tickProgress,
		float pitch, InteractionHand hand, float swingProgress, ItemStack item,
		float equipProgress, PoseStack matrices, SubmitNodeCollector collector,
		int light, CallbackInfo ci)
	{
		ViewmodelHack viewmodel = WurstClient.INSTANCE.getHax().viewmodelHack;
		if(viewmodel.shouldHide(getArm(player, hand)))
			ci.cancel();
	}
	
	@Inject(
		method = "submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void onSubmitItem(PlayerRenderState player,
		FirstPersonHandsAndItemsRenderState handsAndItems, float tickProgress,
		float pitch, InteractionHand hand, float swingProgress, ItemStack item,
		float equipProgress, PoseStack matrices, SubmitNodeCollector collector,
		int light, CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().viewmodelHack
			.applyTransform(getArm(player, hand), matrices);
	}
	
	@Inject(
		method = "renderMapHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onRenderMapHand(PoseStack matrices,
		SubmitNodeCollector collector, int light, HumanoidArm arm,
		PlayerRenderState player, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().viewmodelHack.shouldHide(arm))
			ci.cancel();
	}
	
	@Inject(
		method = "renderMapHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
			shift = At.Shift.AFTER))
	private void onRenderMapHandPush(PoseStack matrices,
		SubmitNodeCollector collector, int light, HumanoidArm arm,
		PlayerRenderState player, CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().viewmodelHack.applyTransform(arm,
			matrices);
	}
	
	@Inject(
		method = "renderPlayerArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFFLnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onRenderPlayerArmHead(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		float swingProgress, HumanoidArm arm, PlayerRenderState player,
		CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().viewmodelHack.shouldHide(arm))
			ci.cancel();
	}
	
	@Inject(
		method = "renderPlayerArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFFLnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;renderPlayerHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V"))
	private void onRenderPlayerArm(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		float swingProgress, HumanoidArm arm, PlayerRenderState player,
		CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().viewmodelHack.applyTransform(arm,
			matrices);
	}
	
	@Inject(
		method = "renderOneHandedMap(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFLnet/minecraft/world/entity/HumanoidArm;FLnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onRenderOneHandedMap(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		HumanoidArm arm, float swingProgress, ItemStack stack,
		PlayerRenderState player,
		FirstPersonHandsAndItemsRenderState handsAndItems, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().viewmodelHack.shouldHide(arm))
			ci.cancel();
	}
	
	@Inject(
		method = "renderOneHandedMap(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFLnet/minecraft/world/entity/HumanoidArm;FLnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;renderMap(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/item/ItemStack;ZLnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V"))
	private void onRenderOneHandedMapItem(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		HumanoidArm arm, float swingProgress, ItemStack stack,
		PlayerRenderState player,
		FirstPersonHandsAndItemsRenderState handsAndItems, CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().viewmodelHack.applyTransform(arm,
			matrices);
	}
	
	private HumanoidArm getArm(PlayerRenderState player, InteractionHand hand)
	{
		HumanoidArm mainArm = player.avatarRenderState.mainArm;
		return hand == InteractionHand.MAIN_HAND ? mainArm
			: mainArm.getOpposite();
	}
}
