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

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;
import net.wurstclient.hacks.TextureRotatorHack;

/**
 * Prevents the "texture rotation" coordinate exploit by replacing the
 * position-based seed that Minecraft uses to pick random texture variants.
 *
 * <p>
 * Every caller that renders blocks (BlockRenderDispatcher, SectionCompiler,
 * BlockFeatureRenderer, ...) obtains its seed through
 * {@code BlockStateBase.getSeed(BlockPos)}, which delegates to
 * {@code Block.getSeed(BlockState, BlockPos)} and finally to this method.
 * Cancelling it here therefore covers all of them.
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourMixin
{
	@Inject(
		method = "getSeed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)J",
		at = @At("HEAD"),
		cancellable = true)
	private void onGetSeed(BlockState state, BlockPos pos,
		CallbackInfoReturnable<Long> cir)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null)
			return;
		
		TextureRotatorHack textureRotator = hax.textureRotatorHack;
		if(!textureRotator.isEnabled())
			return;
		if(state.is(Blocks.POTENT_SULFUR))
			return;
		
		if(textureRotator.isNoRotationMode())
			cir.setReturnValue(textureRotator.getNoRotationSeed());
		else
			cir.setReturnValue(textureRotator.getRandomizedSeed(pos));
	}
}
