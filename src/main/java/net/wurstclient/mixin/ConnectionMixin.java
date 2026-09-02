/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.ConnectionPacketOutputListener.ConnectionPacketOutputEvent;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.hacks.NbtFilterHack;
import net.wurstclient.other_features.PacketFirewallOtf;
import net.wurstclient.uiutils.UiUtilsServerFingerprintCollector;

@Mixin(Connection.class)
public abstract class ConnectionMixin
	extends SimpleChannelInboundHandler<Packet<?>>
{
	@Inject(
		method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
		at = @At("HEAD"))
	private void serverIntel$captureFingerprint(ChannelHandlerContext context,
		Packet<?> packet, CallbackInfo ci)
	{
		UiUtilsServerFingerprintCollector
			.onIncomingPacket((Connection)(Object)this, packet);
	}
	
	@Inject(
		method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V",
			ordinal = 0),
		cancellable = true)
	private void onChannelRead0(ChannelHandlerContext context, Packet<?> packet,
		CallbackInfo ci)
	{
		PacketInputEvent event = new PacketInputEvent(packet);
		EventManager.fire(event);
		
		if(event.isCancelled())
			ci.cancel();
	}
	
	@WrapMethod(
		method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V")
	private void wrapSend(Packet<?> packet,
		@Nullable ChannelFutureListener listener, boolean flush,
		Operation<Void> original)
	{
		Packet<?> originalPacket = packet;
		PacketFirewallOtf firewall =
			WurstClient.INSTANCE.getOtfs().packetFirewallOtf;
		boolean vanillaOnly = firewall.isVanillaOnlyPacketsMode();
		boolean autoFlyMutated = false;
		boolean noFallMutated = false;
		boolean originalOnGround = false;
		ServerboundMovePlayerPacket beforeNoFall = null;
		if(packet instanceof ServerboundMovePlayerPacket move
			&& WurstClient.INSTANCE != null
			&& WurstClient.INSTANCE.getHax() != null && !vanillaOnly)
		{
			originalOnGround = move.isOnGround();
			boolean pauseNoFallOnFlight =
				WurstClient.INSTANCE.getHax().noFallHack
					.shouldPauseMovementPacketMutation();
			
			if(move.isOnGround() && WurstClient.INSTANCE.getHax().autoFlyHack
				.shouldApplyPathAntiHunger() && !pauseNoFallOnFlight)
			{
				((ServerboundMovePlayerPacketAccessor)move).setOnGround(false);
				autoFlyMutated = true;
			}
			
			beforeNoFall = move;
			packet = WurstClient.INSTANCE.getHax().noFallHack
				.protectFlightMovementPacket(
					(ServerboundMovePlayerPacket)packet);
			noFallMutated = beforeNoFall
				.isOnGround() != ((ServerboundMovePlayerPacket)packet)
					.isOnGround();
		}
		
		if(NbtFilterHack.shouldCancelOutgoingPacket(packet))
			return;
		
		ConnectionPacketOutputEvent event =
			new ConnectionPacketOutputEvent(packet);
		if(autoFlyMutated)
			event.addDebugSource("AutoFly movement mutation: onGround "
				+ originalOnGround + " -> false");
		if(noFallMutated && beforeNoFall != null)
			event.addDebugSource("NoFall movement mutation: onGround "
				+ beforeNoFall.isOnGround() + " -> "
				+ ((ServerboundMovePlayerPacket)packet).isOnGround());
		String senderHack = firewall.resolveSenderHackNameForDebug();
		if(senderHack != null)
			event.addDebugSource("direct sender: " + senderHack);
		EventManager.fire(event);
		
		Packet<?> finalPacket = firewall.enforceVanillaOnly(originalPacket,
			event.getPacket(), event.isCancelled());
		if(finalPacket == null)
			return;
		
		event.clearCancellation();
		original.call(finalPacket, listener, flush);
	}
}
