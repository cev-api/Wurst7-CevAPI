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
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.wurstclient.WurstClient;

/**
 * Keeps server holograms in the see-through text pass while SurfaceXray is
 * active.
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public abstract class TextDisplayRendererMixin
{
	@ModifyArg(
		method = "submitInner(Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitText(Lcom/mojang/blaze3d/vertex/PoseStack;FFLnet/minecraft/util/FormattedCharSequence;ZLnet/minecraft/client/gui/Font$DisplayMode;IIII)V"),
		index = 5,
		require = 0)
	private DisplayMode wurst$keepHologramTextOpaque(DisplayMode original)
	{
		return WurstClient.INSTANCE.getHax().surfaceXrayHack.isEnabled()
			? DisplayMode.SEE_THROUGH : original;
	}
}
