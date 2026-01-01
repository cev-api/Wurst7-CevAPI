/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
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
import net.wurstclient.event.EventManager;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.cevapi.security.ResourcePackProtector;
import net.cevapi.security.ResourcePackProtector.PolicyResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonNetworkHandlerMixin
	implements ClientCommonPacketListener
{
	@Shadow
	protected Minecraft minecraft;
	
	@Shadow
	protected Connection connection;
	
	@WrapOperation(at = @At(value = "INVOKE",
		target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"),
		method = "send(Lnet/minecraft/network/protocol/Packet;)V")
	private void wrapSendPacket(Connection connection, Packet<?> packet,
		Operation<Void> original)
	{
		PacketOutputEvent event = new PacketOutputEvent(packet);
		EventManager.fire(event);
		
		if(!event.isCancelled())
			original.call(connection, event.getPacket());
	}
	
	@Inject(at = @At("HEAD"),
		method = "handleResourcePackPush(Lnet/minecraft/network/protocol/common/ClientboundResourcePackPushPacket;)V",
		cancellable = true)
	private void onResourcePackSend(ClientboundResourcePackPushPacket packet,
		CallbackInfo ci)
	{
		try
		{
			PolicyResult result = ResourcePackProtector.evaluate(packet);
			Minecraft mc =
				minecraft != null ? minecraft : Minecraft.getInstance();
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
