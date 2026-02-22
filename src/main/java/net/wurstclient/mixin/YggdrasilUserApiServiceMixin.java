/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.minecraft.UserApiService.UserFlag;
import com.mojang.authlib.minecraft.UserApiService.UserProperties;
import com.mojang.authlib.yggdrasil.YggdrasilUserApiService;

import net.wurstclient.WurstClient;

@Mixin(value = YggdrasilUserApiService.class, remap = false)
public class YggdrasilUserApiServiceMixin
{
	@Inject(method = "fetchProperties",
		at = @At("HEAD"),
		cancellable = true,
		remap = false)
	private void wurst$forceAllowChats(
		CallbackInfoReturnable<UserProperties> cir)
	{
		if(WurstClient.INSTANCE.getOtfs() == null)
			return;
		
		if(!WurstClient.INSTANCE.getOtfs().forceAllowChatsOtf
			.isForceAllowChatsEnabled())
			return;
		
		cir.setReturnValue(new UserProperties(Set.of(UserFlag.CHAT_ALLOWED,
			UserFlag.REALMS_ALLOWED, UserFlag.SERVERS_ALLOWED), Map.of()));
	}
}
