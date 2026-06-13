/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.AccountProfileKeyPairManager;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.wurstclient.WurstClient;

@Mixin(AccountProfileKeyPairManager.class)
public abstract class AccountProfileKeyPairManagerMixin
{
	@Inject(method = "prepareKeyPair()Ljava/util/concurrent/CompletableFuture;",
		at = @At("RETURN"))
	private void wurst$logPrepareKeyPair(
		CallbackInfoReturnable<CompletableFuture<Optional<ProfileKeyPair>>> cir)
	{
		CompletableFuture<Optional<ProfileKeyPair>> future =
			cir.getReturnValue();
		if(future == null)
			return;
		
		future.whenComplete((result, throwable) -> {
			if(WurstClient.INSTANCE == null
				|| WurstClient.INSTANCE.getOtfs() == null)
				return;
			
			LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
			fields.put("stage", "prepareKeyPair");
			fields.put("hasKeyPair", result != null && result.isPresent());
			if(result != null && result.isPresent())
			{
				ProfileKeyPair keyPair = result.get();
				fields.put("refreshedAfter",
					String.valueOf(keyPair.refreshedAfter()));
				fields.put("dueRefresh", keyPair.dueRefresh());
			}
			if(throwable != null)
				fields.put("error", throwable.toString());
			WurstClient.INSTANCE.getOtfs().packetToolsOtf
				.logVerboseExternalEvent("AuthCallback", fields);
		});
	}
}
