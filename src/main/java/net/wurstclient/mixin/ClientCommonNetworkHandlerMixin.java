/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.ClientCommonPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.cevapi.security.ResourcePackProtector;
import net.cevapi.security.ResourcePackProtector.PolicyResult;

@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientCommonNetworkHandlerMixin
	implements ClientCommonPacketListener
{
	@Shadow
	protected MinecraftClient client;
	
	@Shadow
	protected ClientConnection connection;
	
	@WrapOperation(at = @At(value = "INVOKE",
		target = "Lnet/minecraft/network/ClientConnection;send(Lnet/minecraft/network/packet/Packet;)V"),
		method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V")
	private void wrapSendPacket(ClientConnection connection, Packet<?> packet,
		Operation<Void> original)
	{
		PacketOutputEvent event = new PacketOutputEvent(packet);
		EventManager.fire(event);
		
		if(!event.isCancelled())
			original.call(connection, event.getPacket());
	}
	
	@Inject(at = @At("HEAD"),
		method = "onResourcePackSend(Lnet/minecraft/network/packet/s2c/common/ResourcePackSendS2CPacket;)V",
		cancellable = true)
	private void onResourcePackSend(ResourcePackSendS2CPacket packet,
		CallbackInfo ci)
	{
		try
		{
			PolicyResult result = ResourcePackProtector.evaluate(packet);
			MinecraftClient mc =
				client != null ? client : MinecraftClient.getInstance();
			boolean cancel =
				ResourcePackProtector.applyDecision(result, connection, mc);
			if(cancel)
				ci.cancel();
			
		}catch(Exception e)
		{
			e.printStackTrace();
		}
	}
}
