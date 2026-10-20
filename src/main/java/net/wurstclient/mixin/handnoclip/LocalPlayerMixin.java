/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.handnoclip;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin
{
	@Inject(
		method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
		at = @At("HEAD"))
	private static void onPick(Entity source, double blockReach,
		double entityReach, float partialTicks,
		CallbackInfoReturnable<HitResult> cir)
	{
		var hax = WurstClient.INSTANCE.getHax();
		if(hax == null || !hax.handNoClipHack.isEnabled())
			return;
		Vec3 eyes = source.getEyePosition(partialTicks);
		Vec3 look = source.getViewVector(partialTicks);
		if(hax.freecamHack.isClickingFromCamera())
		{
			eyes = hax.freecamHack.getCamPos(partialTicks);
			look = hax.freecamHack.getScaledCamDir(1);
		}
		hax.handNoClipHack.updateTargeting(eyes, look, blockReach, entityReach);
	}
}
