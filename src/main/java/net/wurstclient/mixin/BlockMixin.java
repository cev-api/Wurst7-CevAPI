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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.wurstclient.WurstClient;

@Mixin(Block.class)
public class BlockMixin
{
	@WrapOperation(method = "pushEntitiesUp",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;teleportRelative(DDD)V"))
	private static void onPushEntityUp(Entity entity, double x, double y,
		double z, Operation<Void> original)
	{
		if(WurstClient.INSTANCE.getHax().antiGeyserHack
			.shouldCancelRelativeTeleport(entity, x, y, z))
			return;
		
		original.call(entity, x, y, z);
	}
}
