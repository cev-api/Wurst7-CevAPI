/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel.ArmPose;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.wurstclient.WurstClient;

@Mixin(LivingEntityRenderer.class)
public abstract class NecoModeLivingEntityRendererMixin
{
	@Unique
	private static final Identifier WURST_NECO_SKIN =
		Identifier.fromNamespaceAndPath("wurst", "neco_skin.png");
	
	@Unique
	private static PlayerModel necoPlayerModel;
	
	@ModifyArg(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"),
		index = 0)
	private Model wurst$useNecoModel(Model original)
	{
		if(!WurstClient.INSTANCE.getHax().necoModeHack.isEnabled())
			return original;
		
		return getNecoPlayerModel();
	}
	
	@ModifyArg(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"),
		index = 1)
	private Object wurst$useNecoRenderState(Object original)
	{
		if(!WurstClient.INSTANCE.getHax().necoModeHack.isEnabled()
			|| !(original instanceof LivingEntityRenderState state))
			return original;
		
		AvatarRenderState avatarState = new AvatarRenderState();
		copyLivingState(state, avatarState);
		return avatarState;
	}
	
	@ModifyArg(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"),
		index = 3)
	private RenderType wurst$useNecoSkin(RenderType original)
	{
		if(!WurstClient.INSTANCE.getHax().necoModeHack.isEnabled())
			return original;
		
		return getNecoPlayerModel().renderType(WURST_NECO_SKIN);
	}
	
	@WrapOperation(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"))
	private boolean wurst$skipMobLayers(LivingEntityRenderer instance,
		LivingEntityRenderState state, Operation<Boolean> original)
	{
		if(WurstClient.INSTANCE.getHax().necoModeHack.isEnabled())
			return false;
		
		return original.call(instance, state);
	}
	
	@Unique
	private static PlayerModel getNecoPlayerModel()
	{
		if(necoPlayerModel == null)
			necoPlayerModel = new PlayerModel(Minecraft.getInstance()
				.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
		
		return necoPlayerModel;
	}
	
	@Unique
	private static void copyLivingState(LivingEntityRenderState from,
		AvatarRenderState to)
	{
		to.bodyRot = from.bodyRot;
		to.yRot = from.yRot;
		to.xRot = from.xRot;
		to.deathTime = from.deathTime;
		to.walkAnimationPos = from.walkAnimationPos;
		to.walkAnimationSpeed = from.walkAnimationSpeed;
		to.scale = from.scale;
		to.ageScale = from.ageScale;
		to.isUpsideDown = from.isUpsideDown;
		to.isFullyFrozen = from.isFullyFrozen;
		to.isBaby = from.isBaby;
		to.isInWater = from.isInWater;
		to.isAutoSpinAttack = from.isAutoSpinAttack;
		to.hasRedOverlay = from.hasRedOverlay;
		to.isInvisibleToPlayer = from.isInvisibleToPlayer;
		to.bedOrientation = from.bedOrientation;
		to.pose = from.pose;
		to.leftArmPose = ArmPose.EMPTY;
		to.rightArmPose = ArmPose.EMPTY;
		to.mainArm = HumanoidArm.RIGHT;
		to.attackArm = HumanoidArm.RIGHT;
		to.showHat = true;
		to.showJacket = true;
		to.showLeftPants = true;
		to.showRightPants = true;
		to.showLeftSleeve = true;
		to.showRightSleeve = true;
	}
}
