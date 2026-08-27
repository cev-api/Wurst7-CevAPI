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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.wurstclient.WurstClient;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin
{
	@Inject(
		method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
		at = @At("HEAD"),
		cancellable = true)
	private void wurst$filterMutedSound(SoundInstance sound,
		CallbackInfoReturnable<SoundEngine.PlayResult> cir)
	{
		if(sound != null && WurstClient.INSTANCE.getHax().soundMuteHack
			.isMuted(sound.getIdentifier()))
			cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
	}
}
