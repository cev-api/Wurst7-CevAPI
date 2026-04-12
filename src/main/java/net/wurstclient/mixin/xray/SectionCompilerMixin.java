/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.xray;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.SurfaceXrayHack;

@Mixin(SectionCompiler.class)
public class SectionCompilerMixin
{
	/**
	 * Puts quads on the translucent layer when opacity-based X-Ray rendering is
	 * active.
	 */
	@ModifyVariable(method = "getOrBeginLayer",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private ChunkSectionLayer forceTranslucentLayer(
		ChunkSectionLayer renderType)
	{
		if(WurstClient.INSTANCE.getHax().xRayHack.isOpacityMode())
			return ChunkSectionLayer.TRANSLUCENT;
		
		SurfaceXrayHack surface = WurstClient.INSTANCE.getHax().surfaceXrayHack;
		if(surface != null && surface.isEnabled()
			&& surface.getSurfaceOpacity() < 0.99f)
			return ChunkSectionLayer.TRANSLUCENT;
		
		return renderType;
	}
}
