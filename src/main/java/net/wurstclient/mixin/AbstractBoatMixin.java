/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.BoatPhaseHack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Applies BoatPhase at the actual vanilla boat movement call. */
@Mixin(AbstractBoat.class)
public abstract class AbstractBoatMixin
{
	@WrapOperation(method = "tick()V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
	private void wurst$boatPhaseMove(Entity boat, MoverType type, Vec3 movement,
		Operation<Void> original)
	{
		BoatPhaseHack phase = WurstClient.INSTANCE.getHax().boatPhaseHack;
		if(!phase.shouldPhase(boat))
		{
			original.call(boat, type, movement);
			return;
		}
		boolean previous = boat.noPhysics;
		try
		{
			boat.noPhysics = true;
			original.call(boat, type, phase.movementFor(boat, movement));
		}finally
		{
			boat.noPhysics = previous;
		}
	}
}
