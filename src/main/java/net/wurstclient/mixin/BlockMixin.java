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
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

@Mixin(Block.class)
public abstract class BlockMixin implements ItemLike
{
	@Inject(at = @At("HEAD"), method = "getSpeedFactor()F", cancellable = true)
	private void onGetVelocityMultiplier(CallbackInfoReturnable<Float> cir)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null)
			return;
		
		boolean ignoreViaNoSlowdown = hax.noSlowdownHack.isEnabled();
		boolean ignoreVinesViaFlight =
			hax.flightHack.shouldIgnoreVinesWithFlight()
				&& isVineBlock((Block)(Object)this);
		
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
			|| block == Blocks.TWISTING_VINES_PLANT;
	}
}
