/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.antiknockback;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin
{
	/**
	 * Filters explosion knockback once, then lets vanilla add the velocity.
	 * AntiBlast takes priority when enabled; otherwise AntiKnockback applies.
	 */
	@ModifyExpressionValue(
		method = "handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;playerKnockback()Ljava/util/Optional;"),
		require = 1)
	private Optional<Vec3> onHandleExplosionPlayerKnockback(
		Optional<Vec3> original)
	{
		var hax = WurstClient.INSTANCE.getHax();
		return original.map(vec -> hax.antiBlastHack
			.modifyExplosionKnockback(vec, hax.antiKnockbackHack));
	}
}
