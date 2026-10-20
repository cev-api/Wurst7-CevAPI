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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.commands.CommandSource;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.util.MovementMutationTracker;

@Mixin(Entity.class)
public abstract class EntityMixin
	implements Nameable, EntityAccess, CommandSource
{
	@ModifyVariable(
		method = "makeStuckInBlock(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/Vec3;)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private Vec3 noWeb$cancelCobwebSlowdown(Vec3 multiplier, BlockState state,
		Vec3 originalMultiplier)
	{
		if(!state.is(Blocks.COBWEB))
			return multiplier;
		
		if(!WurstClient.INSTANCE.getHax().noWebHack
			.shouldCancelCobwebSlowdown((Entity)(Object)this))
			return multiplier;
		
		return Vec3.ZERO;
	}
	
	@Inject(method = "teleportRelative(DDD)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onRelativeTeleport(double x, double y, double z,
		CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().antiGeyserHack
			.shouldCancelRelativeTeleport((Entity)(Object)this, x, y, z))
			ci.cancel();
	}
	
	@Inject(at = @At("HEAD"),
		method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
		cancellable = true)
	private void onSetDeltaMovement(Vec3 velocity, CallbackInfo ci)
	{
		if(MovementMutationTracker.shouldBlockLocalPlayerMovementMutation(
			(Entity)(Object)this, "Entity#setDeltaMovement(Vec3)"))
		{
			ci.cancel();
			return;
		}
		MovementMutationTracker.markLocalPlayerVelocityMutation(
			(Entity)(Object)this, "Entity#setDeltaMovement(Vec3)");
	}
	
	@Inject(at = @At("HEAD"),
		method = "addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
		cancellable = true)
	private void onAddDeltaMovement(Vec3 velocity, CallbackInfo ci)
	{
		Entity self = (Entity)(Object)this;
		if(WurstClient.INSTANCE.getHax().antiGeyserHack
			.shouldCancelRelativeTeleport(self, velocity.x, velocity.y,
				velocity.z))
			ci.cancel();
	}
	
	@Inject(at = @At("HEAD"),
		method = "setDeltaMovement(DDD)V",
		cancellable = true)
	private void onSetDeltaMovement(double x, double y, double z,
		CallbackInfo ci)
	{
		if(MovementMutationTracker.shouldBlockLocalPlayerMovementMutation(
			(Entity)(Object)this, "Entity#setDeltaMovement(DDD)"))
		{
			ci.cancel();
			return;
		}
		MovementMutationTracker.markLocalPlayerVelocityMutation(
			(Entity)(Object)this, "Entity#setDeltaMovement(DDD)");
	}
	
	@Inject(method = "setPos(DDD)V", at = @At("HEAD"), cancellable = true)
	private void onSetPos(double x, double y, double z, CallbackInfo ci)
	{
		if(MovementMutationTracker.shouldBlockLocalPlayerMovementMutation(
			(Entity)(Object)this, "Entity#setPos(DDD)"))
			ci.cancel();
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
		
		try
		{
			Integer glowColor = getEspGlowColor(living);
			if(glowColor != null)
				cir.setReturnValue(true);
		}catch(IllegalStateException e)
		{
			// Entity doesn't have an ID yet (e.g. spawner display entity)
		}
	}
	
	@Inject(at = @At("RETURN"), method = "getTeamColor", cancellable = true)
	private void onGetTeamColorValue(CallbackInfoReturnable<Integer> cir)
	{
		if(!((Object)this instanceof LivingEntity living))
			return;
		
		try
		{
			Integer glowColor = getEspGlowColor(living);
			if(glowColor != null)
				cir.setReturnValue(glowColor);
		}catch(IllegalStateException e)
		{
			// Entity doesn't have an ID yet (e.g. spawner display entity)
		}
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
