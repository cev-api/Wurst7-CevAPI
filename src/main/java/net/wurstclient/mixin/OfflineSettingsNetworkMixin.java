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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.OfflineSettingsHack;
import net.minecraft.network.DisconnectionDetails;

@Mixin(value = ClientCommonPacketListenerImpl.class, remap = false)
public abstract class OfflineSettingsNetworkMixin
{
	@Inject(at = @At("TAIL"),
		method = "onDisconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
		remap = false)
	private void wurst$handleDisconnect(DisconnectionDetails details,
		CallbackInfo ci)
	{
		OfflineSettingsHack hack =
			WurstClient.INSTANCE.getHax().offlineSettingsHack;
		
		hack.handleDisconnect(details.reason());
		WurstClient.INSTANCE.getServerObserver().handleDisconnect();
	}
}
