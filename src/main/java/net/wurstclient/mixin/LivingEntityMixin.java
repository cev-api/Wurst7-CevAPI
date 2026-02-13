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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.wurstclient.WurstClient;

@Mixin(LivingEntity.class)
public class LivingEntityMixin
{
	/**
	 * Stops the other darkness effect in caves when AntiBlind is enabled.
	 */
	@Inject(at = @At("HEAD"),
		method = "getEffectBlendFactor(Lnet/minecraft/core/Holder;F)F",
		cancellable = true)
	private void onGetEffectFadeFactor(Holder<MobEffect> registryEntry,
		float delta, CallbackInfoReturnable<Float> cir)
	{
		if(registryEntry != MobEffects.DARKNESS)
			return;
		
		if(WurstClient.INSTANCE.getHax().antiBlindHack.isEnabled())
			cir.setReturnValue(0F);
	}
	
	@Inject(method = "onClimbable()Z", at = @At("HEAD"), cancellable = true)
	private void onOnClimbable(CallbackInfoReturnable<Boolean> cir)
	{
		Entity entity = (Entity)(Object)this;
		if(!(entity instanceof Player player) || !player.isLocalPlayer())
			return;
		
		var noSlowdown = WurstClient.INSTANCE.getHax().noSlowdownHack;
		if(!noSlowdown.isEnabled() || !noSlowdown.shouldIgnoreVines())
			return;
		
		if(isTouchingVines(entity))
			cir.setReturnValue(false);
	}
	
	private boolean isTouchingVines(Entity entity)
	{
		AABB box = entity.getBoundingBox().inflate(1.0E-4);
		int minX = (int)Math.floor(box.minX);
		int minY = (int)Math.floor(box.minY);
		int minZ = (int)Math.floor(box.minZ);
		int maxX = (int)Math.floor(box.maxX);
		int maxY = (int)Math.floor(box.maxY);
		int maxZ = (int)Math.floor(box.maxZ);
		
		for(int x = minX; x <= maxX; x++)
		{
			for(int y = minY; y <= maxY; y++)
			{
				for(int z = minZ; z <= maxZ; z++)
				{
					BlockState state =
						entity.level().getBlockState(new BlockPos(x, y, z));
					if(isVineState(state))
						return true;
				}
			}
		}
		
		return false;
	}
	
	private boolean isVineState(BlockState state)
	{
		return state.is(Blocks.VINE) || state.is(Blocks.CAVE_VINES)
			|| state.is(Blocks.CAVE_VINES_PLANT)
			|| state.is(Blocks.WEEPING_VINES)
			|| state.is(Blocks.WEEPING_VINES_PLANT)
			|| state.is(Blocks.TWISTING_VINES)
			|| state.is(Blocks.TWISTING_VINES_PLANT);
	}
}
