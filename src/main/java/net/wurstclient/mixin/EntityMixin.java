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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.commands.CommandSource;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.VelocityFromEntityCollisionListener.VelocityFromEntityCollisionEvent;
import net.wurstclient.events.VelocityFromFluidListener.VelocityFromFluidEvent;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.util.MovementMutationTracker;

@Mixin(Entity.class)
public abstract class EntityMixin
	implements Nameable, EntityAccess, CommandSource
{
	/**
	 * This mixin makes the VelocityFromFluidEvent work, which is used by
	 * AntiWaterPush.
	 */
	@WrapOperation(method = "updateFluidInteraction()Z",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;isPushedByFluid()Z",
			ordinal = 0))
	private boolean wrapUpdateFluidInteractionIsPushedByFluid(Entity instance,
		Operation<Boolean> original)
	{
		VelocityFromFluidEvent event = new VelocityFromFluidEvent(instance);
		EventManager.fire(event);
		
		if(event.isCancelled())
			return false;
		
		return original.call(instance);
	}
	
	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onPushAwayFrom(Entity entity, CallbackInfo ci)
	{
		VelocityFromEntityCollisionEvent event =
			new VelocityFromEntityCollisionEvent((Entity)(Object)this);
		EventManager.fire(event);
		
		if(event.isCancelled())
			ci.cancel();
	}
	
	@Inject(at = @At("HEAD"),
		method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V")
	private void onSetDeltaMovement(Vec3 velocity, CallbackInfo ci)
	{
		MovementMutationTracker.markLocalPlayerVelocityMutation(
			(Entity)(Object)this, "Entity#setDeltaMovement(Vec3)");
	}
	
	@Inject(at = @At("HEAD"), method = "setDeltaMovement(DDD)V")
	private void onSetDeltaMovement(double x, double y, double z,
		CallbackInfo ci)
	{
		MovementMutationTracker.markLocalPlayerVelocityMutation(
			(Entity)(Object)this, "Entity#setDeltaMovement(DDD)");
	}
	
	/**
	 * Makes invisible entities render as ghosts if TrueSight is enabled.
	 */
	@Inject(
		method = "isInvisibleTo(Lnet/minecraft/world/entity/player/Player;)Z",
		at = @At("HEAD"),
		cancellable = true)
	private void onIsInvisibleToFreecam(Player player,
		CallbackInfoReturnable<Boolean> cir)
	{
		Entity self = (Entity)(Object)this;
		if(!(self instanceof Player localPlayer)
			|| !localPlayer.isLocalPlayer())
			return;
		
		if(WurstClient.INSTANCE.getHax().freecamHack.isLegacyModeActive())
			cir.setReturnValue(true);
	}
	
	@Inject(method = "isInvisible()Z", at = @At("HEAD"), cancellable = true)
	private void onIsInvisibleFreecam(CallbackInfoReturnable<Boolean> cir)
	{
		Entity self = (Entity)(Object)this;
		if(!(self instanceof Player localPlayer)
			|| !localPlayer.isLocalPlayer())
			return;
		
		if(WurstClient.INSTANCE.getHax().freecamHack.isLegacyModeActive())
			cir.setReturnValue(true);
	}
	
	@Inject(
		method = "isInvisibleTo(Lnet/minecraft/world/entity/player/Player;)Z",
		at = @At("RETURN"),
		cancellable = true)
	private void onIsInvisibleTo(Player player,
		CallbackInfoReturnable<Boolean> cir)
	{
		// Return early if the entity is not invisible
		if(!cir.getReturnValueZ())
			return;
		
		if(WurstClient.INSTANCE.getHax().trueSightHack
			.shouldBeVisible((Entity)(Object)this))
			cir.setReturnValue(false);
	}
	
	@Inject(at = @At("RETURN"),
		method = "isCurrentlyGlowing",
		cancellable = true)
	private void onIsGlowing(CallbackInfoReturnable<Boolean> cir)
	{
		if(cir.getReturnValueZ())
			return;
		
		if(!((Object)this instanceof LivingEntity living))
			return;
		
		Integer glowColor = getEspGlowColor(living);
		if(glowColor != null)
			cir.setReturnValue(true);
	}
	
	@Inject(at = @At("RETURN"), method = "getTeamColor", cancellable = true)
	private void onGetTeamColorValue(CallbackInfoReturnable<Integer> cir)
	{
		if(!((Object)this instanceof LivingEntity living))
			return;
		
		Integer glowColor = getEspGlowColor(living);
		if(glowColor != null)
			cir.setReturnValue(glowColor);
	}
	
	private Integer getEspGlowColor(LivingEntity living)
	{
		var hax = WurstClient.INSTANCE.getHax();
		if(hax != null && hax.hideWurstHack.isEnabled())
			return null;
		
		Integer color = NiceWurstModule.filterGlowColor(living,
			hax.playerEspHack.getGlowColor(living));
		if(color != null)
			return color;
		
		color = NiceWurstModule.filterGlowColor(living,
			hax.spearAssistHack.getGlowColor(living));
		if(color != null)
			return color;
		
		color = NiceWurstModule.filterGlowColor(living,
			hax.mobSearchHack.getGlowColor(living));
		if(color != null)
			return color;
		
		color = NiceWurstModule.filterGlowColor(living,
			hax.wardenEspHack.getGlowColor(living));
		if(color != null)
			return color;
		
		color = NiceWurstModule.filterGlowColor(living,
			hax.mobEspHack.getGlowColor(living));
		if(color != null)
			return color;
		
		return NiceWurstModule.filterGlowColor(living,
			hax.trueSightHack.getGlowColor(living));
	}
}
