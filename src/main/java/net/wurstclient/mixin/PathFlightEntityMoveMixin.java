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
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.autoflypath.PathFlightRuntime;
import net.wurstclient.autoflypath.flight.FlightController;

/**
 * Preserves FlyTo's move-time collision validation. The flight controller
 * chooses a velocity during the client tick; this hook applies the same swept
 * collision clamp immediately before Minecraft moves the local player.
 */
@Mixin(Entity.class)
public abstract class PathFlightEntityMoveMixin
{
	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
	private Vec3 validatePathFlightMove(Vec3 delta, MoverType moverType)
	{
		if(moverType != MoverType.SELF && moverType != MoverType.PLAYER)
			return delta;
		
		if((Object)this != Minecraft.getInstance().player)
			return delta;
		
		FlightController controller = PathFlightRuntime.controller();
		return controller == null ? delta : controller.validateMoveTime(delta);
	}
}
