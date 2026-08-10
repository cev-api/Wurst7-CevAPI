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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;
import net.wurstclient.hacks.TextureRotatorHack;

/**
 * Handles the "position offset" half of the coordinate exploit: blocks such
 * as flowers are shifted away from the center of their cell by a value that
 * is derived from the block position. This either removes that offset
 * entirely ("No rotation" mode) or randomizes it ("Random" mode).
 */
@Mixin(BlockStateBase.class)
public abstract class BlockStateBaseOffsetMixin
{
	@Inject(
		method = "getOffset(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;",
		at = @At("HEAD"),
		cancellable = true)
	private void onGetOffset(BlockPos pos, CallbackInfoReturnable<Vec3> cir)
	{
		TextureRotatorHack textureRotator = getTextureRotator();
		if(textureRotator == null || !textureRotator.isEnabled())
			return;
		
		if(textureRotator.isNoRotationMode())
			cir.setReturnValue(Vec3.ZERO);
	}
	
	@ModifyVariable(
		method = "getOffset(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private BlockPos randomizeOffsetPos(BlockPos pos)
	{
		TextureRotatorHack textureRotator = getTextureRotator();
		if(textureRotator == null || !textureRotator.isEnabled()
			|| textureRotator.isNoRotationMode())
			return pos;
		
		return textureRotator.getRandomizedOffsetPos(pos);
	}
	
	private static TextureRotatorHack getTextureRotator()
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null)
			return null;
		return hax.textureRotatorHack;
	}
}
