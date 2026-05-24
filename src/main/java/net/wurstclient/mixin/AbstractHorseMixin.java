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
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.wurstclient.WurstClient;

@Mixin(AbstractHorse.class)
public abstract class AbstractHorseMixin
{
	@Inject(method = "isSaddled", at = @At("HEAD"), cancellable = true)
	private void forceSaddled(CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.getHax().entityControlHack
			.shouldEnforceSaddled())
			cir.setReturnValue(true);
	}
	
	@Inject(method = "isMobControlled", at = @At("HEAD"), cancellable = true)
	private void forceMobControlled(CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.getHax().entityControlHack
			.shouldEnforceMobControlled())
			cir.setReturnValue(true);
	}
	
	@Inject(method = "getJumpPower", at = @At("RETURN"), cancellable = true)
	private void forceJumpStrength(CallbackInfoReturnable<Float> cir)
	{
		if(!WurstClient.INSTANCE.getHax().entityControlHack
			.shouldEnforceJumpStrength())
			return;
		
		cir.setReturnValue(Math.max(cir.getReturnValueF(), 0.7F));
	}
}
