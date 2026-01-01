/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.OfflineSettingsHack;

@Mixin(value = ClientHandshakePacketListenerImpl.class, remap = false)
public class OfflineSettingsHandshakeMixin
{
	@Unique
	private boolean wurst$seenHelloPacket;
	
	@Inject(at = @At("HEAD"),
		method = "handleHello(Lnet/minecraft/network/protocol/login/ClientboundHelloPacket;)V")
	private void wurst$onHello(ClientboundHelloPacket packet, CallbackInfo ci)
	{
		wurst$seenHelloPacket = true;
	}
	
	@Inject(at = @At("TAIL"),
		method = "handleLoginFinished(Lnet/minecraft/network/protocol/login/ClientboundLoginFinishedPacket;)V")
	private void wurst$onLoginFinished(ClientboundLoginFinishedPacket packet,
		CallbackInfo ci)
	{
		OfflineSettingsHack hack =
			WurstClient.INSTANCE.getHax().offlineSettingsHack;
		hack.recordHandshakeEncryption(wurst$seenHelloPacket);
		wurst$seenHelloPacket = false;
	}
}
