/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.xray.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.SurfaceXrayHack;
import net.wurstclient.hacks.SurfaceXrayHack.SurfaceState;
import net.wurstclient.hacks.XRayHack;

/**
 * Last updated for <a href=
 * "https://github.com/CaffeineMC/sodium/blob/148316fbfca6c3c88274ad79e1010310c6a3749b/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer.java">Sodium
 * mc26.1-0.8.7</a>
 */
@Pseudo
@Mixin(targets = {
	"net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer"},
	remap = false)
public class BlockRendererMixin extends AbstractBlockRenderContextMixin
{
	/**
	 * Forces hidden blocks onto the translucent render layer in X-Ray opacity
	 * mode.
	 */
	@ModifyExpressionValue(method = "processQuad",
		at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;getRenderType()Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"),
		require = 0)
	private ChunkSectionLayer onProcessQuadRenderType(
		ChunkSectionLayer original)
	{
		SurfaceXrayHack surface = WurstClient.INSTANCE.getHax().surfaceXrayHack;
		if(surface.isEnabled())
		{
			SurfaceState surfaceState = surface.classifyBlock(state, pos);
			if(surfaceState == SurfaceState.INTERIOR)
				return ChunkSectionLayer.TRANSLUCENT;
			if(surfaceState == SurfaceState.SURFACE
				&& surface.getSurfaceOpacity() < 0.99f)
				return ChunkSectionLayer.TRANSLUCENT;
		}
		
		XRayHack xray = WurstClient.INSTANCE.getHax().xRayHack;
		if(!xray.isOpacityMode() || xray.isVisible(state.getBlock(), pos))
			return original;
		
		return ChunkSectionLayer.TRANSLUCENT;
	}
	
	/**
	 * Modifies opacity of blocks when using X-Ray with Sodium installed.
	 */
	@ModifyExpressionValue(
		method = "bufferQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;[FLnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;baseColor(I)I"),
		require = 0)
	private int onBufferQuad(int original)
	{
		SurfaceXrayHack surface = WurstClient.INSTANCE.getHax().surfaceXrayHack;
		if(surface.isEnabled())
		{
			SurfaceState surfaceState = surface.classifyBlock(state, pos);
			if(surfaceState == SurfaceState.INTERIOR)
				return original & 0x01FFFFFF;
			if(surfaceState == SurfaceState.SURFACE)
			{
				float surf = surface.getSurfaceOpacity();
				if(surf >= 0.99f)
					return original; // treat almost-opaque as opaque to avoid
										// translucent sorting
				original &= surface.getSurfaceOpacityMask();
			}
		}
		
		XRayHack xray = WurstClient.INSTANCE.getHax().xRayHack;
		if(!xray.isOpacityMode() || xray.isVisible(state.getBlock(), pos))
			return original;
		float op = xray.getOpacityFloat();
		if(op >= 0.99f)
			return original; // avoid sending quads to Sodium translucent
								// pipeline
		return original & xray.getOpacityColorMask();
	}
}
