/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
import net.wurstclient.util.MovementPacketCompat;

@Mixin(Connection.class)
public abstract class ConnectionMixin
	extends SimpleChannelInboundHandler<Packet<?>>
{
	private ConcurrentLinkedQueue<ConnectionPacketOutputEvent> events =
		new ConcurrentLinkedQueue<>();
	
	/**
	 * Since MC 26.3, servers reject multiple position packets in a single
	 * client tick. This is the lowest point where the packets that are really
	 * about to be written can be seen, meaning this runs after the packet
	 * output event has had its chance to modify or cancel them. The fix for
	 * that is implemented by {@link MovementPacketCompat}.
	 */
	@Inject(
		method = "doSendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
		at = @At("HEAD"))
	private void onDoSendPacket(Packet<?> packet,
		@Nullable ChannelFutureListener listener, boolean flush,
		CallbackInfo ci)
	{
		MovementPacketCompat.beforePacketSend((Connection)(Object)this, packet);
	}
	
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
	
	// These mixins target the second "send" method. The one with two arguments.
	
	@ModifyVariable(
		method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
		at = @At("HEAD"))
	public Packet<?> modifyPacket(Packet<?> packet)
	{
		if(MovementPacketCompat.isSendingSyntheticTickEnd())
			return packet;
		
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
			if(move.isOnGround() && WurstClient.INSTANCE.getHax().autoFlyHack
				.shouldApplyPathAntiHunger())
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
		events.add(event);
		EventManager.fire(event);
		
		Packet<?> finalPacket = firewall.enforceVanillaOnly(originalPacket,
			event.getPacket(), event.isCancelled());
		if(finalPacket == null)
			event.cancel();
		else
		{
			event.setPacket(finalPacket);
			event.clearCancellation();
		}
		return finalPacket != null ? finalPacket : originalPacket;
	}
	
	@Inject(
		method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onSend(Packet<?> packet,
		@Nullable ChannelFutureListener callback, CallbackInfo ci)
	{
		if(MovementPacketCompat.isSendingSyntheticTickEnd())
			return;
		
		if(NbtFilterHack.shouldCancelOutgoingPacket(packet))
		{
			ci.cancel();
			return;
		}
		
		ConnectionPacketOutputEvent event = getEvent(packet);
		if(event != null)
		{
			if(event.isCancelled())
			{
				events.remove(event);
				ci.cancel();
				return;
			}
			
			events.remove(event);
		}
	}
	
	@Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V",
		at = @At("HEAD"))
	private void wurst$resetMovementPacketCompat(CallbackInfo ci)
	{
		MovementPacketCompat.reset((Connection)(Object)this);
	}
	
	@Inject(
		method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
		at = @At("HEAD"))
	private void wurst$resetMovementPacketCompat(
		net.minecraft.network.DisconnectionDetails details, CallbackInfo ci)
	{
		MovementPacketCompat.reset((Connection)(Object)this);
	}
	
	private ConnectionPacketOutputEvent getEvent(Packet<?> packet)
	{
		for(ConnectionPacketOutputEvent event : events)
			if(event.getPacket() == packet)
				return event;
			
		return null;
	}
}
