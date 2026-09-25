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
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.Holder;
import net.wurstclient.WurstClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin
{
	/**
	 * Lets AntiFov remove the field of view change that potion effects cause,
	 * since the vanilla FOV modifier is derived from the movement speed
	 * attribute that those effects modify.
	 */
	@WrapOperation(method = "getFieldOfViewModifier(ZF)F",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/AbstractClientPlayer;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
	private double wurst$ignoreEffectFov(AbstractClientPlayer player,
		Holder<?> attribute, Operation<Double> original)
	{
		return WurstClient.INSTANCE.getHax().antiFovHack
			.adjustFovMovementSpeed(player, original.call(player, attribute));
	}
}
