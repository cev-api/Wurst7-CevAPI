/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.noslowdown;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

@Mixin(Entity.class)
public abstract class EntityMixin
{
	/**
	 * Removes block slowdown for the local player while NoSlowdown is enabled.
	 */
	@Inject(method = "getBlockSpeedFactor()F",
		at = @At("RETURN"),
		cancellable = true)
	private void onGetBlockSpeedFactor(CallbackInfoReturnable<Float> cir)
	{
		Entity self = (Entity)(Object)this;
		if(self != WurstClient.MC.player)
			return;
		
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null)
			return;
		
		boolean ignoreViaNoSlowdown = hax.noSlowdownHack.isEnabled();
		boolean ignoreVinesViaFlight =
			hax.flightHack.shouldIgnoreVinesWithFlight() && isVineBlock(
				self.level().getBlockState(self.blockPosition()).getBlock());
		
		if(!ignoreViaNoSlowdown && !ignoreVinesViaFlight)
			return;
		
		if(cir.getReturnValueF() < 1)
			cir.setReturnValue(1F);
	}
	
	private boolean isVineBlock(Block block)
	{
		return block == Blocks.VINE || block == Blocks.CAVE_VINES
			|| block == Blocks.CAVE_VINES_PLANT || block == Blocks.WEEPING_VINES
			|| block == Blocks.WEEPING_VINES_PLANT
			|| block == Blocks.TWISTING_VINES
			|| block == Blocks.TWISTING_VINES_PLANT
			|| block == Blocks.SCULK_VEIN;
	}
}
