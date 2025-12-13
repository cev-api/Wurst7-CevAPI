/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
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
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.SurfaceXrayHack;

@Mixin(ItemBlockRenderTypes.class)
public abstract class RenderLayersMixin
{
	/**
	 * Puts all blocks on the translucent layer if Opacity X-Ray is enabled.
	 */
	@Inject(at = @At("HEAD"),
		method = "getChunkRenderType(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/renderer/RenderType;",
		cancellable = true)
	private static void onGetBlockLayer(BlockState state,
		CallbackInfoReturnable<RenderType> cir)
	{
		SurfaceXrayHack surface = WurstClient.INSTANCE.getHax().surfaceXrayHack;
		if(surface.isEnabled() && surface.isTarget(state))
		{
			cir.setReturnValue(RenderType.translucent());
			return;
		}
		
		if(!WurstClient.INSTANCE.getHax().xRayHack.isOpacityMode())
			return;
		
		cir.setReturnValue(RenderType.translucent());
	}
	
	/**
	 * Puts all fluids on the translucent layer if Opacity X-Ray is enabled.
	 */
	@Inject(at = @At("HEAD"),
		method = "getRenderLayer(Lnet/minecraft/world/level/material/FluidState;)Lnet/minecraft/client/renderer/RenderType;",
		cancellable = true)
	private static void onGetFluidLayer(FluidState state,
		CallbackInfoReturnable<RenderType> cir)
	{
		SurfaceXrayHack surface = WurstClient.INSTANCE.getHax().surfaceXrayHack;
		if(surface.isEnabled()
			&& surface.isTarget(state.createLegacyBlock().getBlock()))
		{
			cir.setReturnValue(RenderType.translucent());
			return;
		}
		
		if(!WurstClient.INSTANCE.getHax().xRayHack.isOpacityMode())
			return;
		
		cir.setReturnValue(RenderType.translucent());
	}
}
