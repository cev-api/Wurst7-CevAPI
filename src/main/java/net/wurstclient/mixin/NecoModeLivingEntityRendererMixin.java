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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel.ArmPose;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.MobCategory;
import net.wurstclient.WurstClient;

@Mixin(LivingEntityRenderer.class)
public abstract class NecoModeLivingEntityRendererMixin
{
	@Unique
	private static final Identifier WURST_NECO_SKIN =
		Identifier.fromNamespaceAndPath("wurst", "neco_skin.png");
	
	@Unique
	private static PlayerModel necoPlayerModel;
	
	@WrapOperation(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
	private void wurst$replaceSubmittedModel(SubmitNodeCollector collector,
		Model model, Object renderState, PoseStack poseStack,
		RenderType renderType, int lightCoords, int overlay, int tint,
		TextureAtlasSprite sprite, int outlineColor, CrumblingOverlay crumbling,
		Operation<Void> original)
	{
		if(!(renderState instanceof LivingEntityRenderState state)
			|| !shouldApplyToState(state))
		{
			original.call(collector, model, renderState, poseStack, renderType,
				lightCoords, overlay, tint, sprite, outlineColor, crumbling);
			return;
		}
		
		AvatarRenderState avatarState = new AvatarRenderState();
		copyLivingState(state, avatarState);
		PlayerModel necoModel = getNecoPlayerModel();
		original.call(collector, necoModel, avatarState, poseStack,
			necoModel.renderType(WURST_NECO_SKIN), lightCoords, overlay, tint,
			sprite, outlineColor, crumbling);
	}
	
	@WrapOperation(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"))
	private boolean wurst$skipMobLayers(LivingEntityRenderer instance,
		LivingEntityRenderState state, Operation<Boolean> original)
	{
		if(shouldApplyToState(state))
			return false;
		
		return original.call(instance, state);
	}
	
	@Unique
	private static boolean shouldApplyToState(LivingEntityRenderState state)
	{
		var necoMode = WurstClient.INSTANCE.getHax().necoModeHack;
		if(!necoMode.isEnabled())
			return false;
		
		if(state == null)
			return true;
		
		EntityType<?> type = state.entityType;
		if(type == null)
			return true;
		
		if(necoMode.shouldExcludePlayers() && type == EntityType.PLAYER)
			return false;
		
		boolean onlyPassive = necoMode.shouldRenderOnlyPassiveMobs();
		boolean onlyAggressive = necoMode.shouldRenderOnlyAggressiveMobs();
		if(!onlyPassive && !onlyAggressive)
			return true;
		
		MobCategory category = type.getCategory();
		boolean isAggressive = category == MobCategory.MONSTER;
		boolean isPassive =
			category == MobCategory.CREATURE || category == MobCategory.AMBIENT
				|| category == MobCategory.WATER_CREATURE
				|| category == MobCategory.WATER_AMBIENT
				|| category == MobCategory.UNDERGROUND_WATER_CREATURE
				|| type == EntityType.VILLAGER
				|| type == EntityType.WANDERING_TRADER;
		
		if(onlyPassive && onlyAggressive)
			return isPassive || isAggressive;
		
		if(onlyPassive)
			return isPassive;
		
		return isAggressive;
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
