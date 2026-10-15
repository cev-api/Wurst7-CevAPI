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
import net.wurstclient.other_features.VanillaSpoofOtf;
import net.wurstclient.util.VanillaSpoofOutboundHandler;
import net.minecraft.network.protocol.PacketFlow;
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
		Connection connection = (Connection)(Object)this;
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordForensicTiming(packet, "S2C", "INBOUND_CONNECTION");
		UiUtilsServerFingerprintCollector.onIncomingPacket(connection, packet);
		VanillaSpoofOtf.onIncomingPacket(connection, packet);
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
		else
			WurstClient.INSTANCE.getOtfs().packetToolsOtf
				.recordForensicTiming(packet, "S2C", "CLIENT_DISPATCH");
	}
	
	// These mixins target the second "send" method. The one with two arguments.
	
	@ModifyVariable(
		method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
		at = @At("HEAD"))
	public Packet<?> modifyPacket(Packet<?> packet)
	{
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordForensicTiming(packet, "C2S", "SEND_REQUESTED");
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
	
	/**
	 * Install once per client connection, before outbound encoding/splitting.
	 */
	@Inject(method = "channelActive(Lio/netty/channel/ChannelHandlerContext;)V",
		at = @At("RETURN"))
	private void wurst$installVanillaSpoof(ChannelHandlerContext context,
		CallbackInfo ci)
	{
		Connection connection = (Connection)(Object)this;
		if(connection.getReceiving() != PacketFlow.CLIENTBOUND)
			return;
		
		context.pipeline().addBefore(context.name(), "wurst:vanilla_spoof",
			new VanillaSpoofOutboundHandler(
				packet -> WurstClient.INSTANCE.getOtfs().vanillaSpoofOtf
					.filterOutgoing(connection, packet),
				() -> VanillaSpoofOtf.onDisconnect(connection)));
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
			WurstClient.INSTANCE.getOtfs().packetToolsOtf
				.recordForensicTiming(packet, "C2S", "CANCELLED_NBT_FILTER");
			ci.cancel();
			return;
		}
		
		ConnectionPacketOutputEvent event = getEvent(packet);
		if(event != null)
		{
			if(event.isCancelled())
			{
				events.remove(event);
				WurstClient.INSTANCE.getOtfs().packetToolsOtf
					.recordForensicTiming(packet, "C2S",
						"CANCELLED_OUTPUT_EVENT");
				ci.cancel();
				return;
			}
			
			events.remove(event);
		}
	}
	
	@Inject(
		method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
		at = @At("HEAD"))
	private void wurst$passed(Packet<?> packet, ChannelFutureListener listener,
		boolean flush, CallbackInfo ci)
	{
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordForensicTiming(packet, "C2S", "SEND_CHECKS_PASSED");
	}
	
	@Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V",
		at = @At("HEAD"))
	private void wurst$resetMovementPacketCompat(CallbackInfo ci)
	{
		Connection connection = (Connection)(Object)this;
		if(connection
			.getPacketListener() instanceof net.minecraft.client.multiplayer.ClientPacketListener
			&& connection.getPacketListener() == net.minecraft.client.Minecraft
				.getInstance().getConnection())
			WurstClient.INSTANCE.getOtfs().packetToolsOtf
				.closeForensicSession("connection_disconnect");
		MovementPacketCompat.reset(connection);
		VanillaSpoofOtf.onDisconnect(connection);
	}
	
	@Inject(
		method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
		at = @At("HEAD"))
	private void wurst$resetMovementPacketCompat(
		net.minecraft.network.DisconnectionDetails details, CallbackInfo ci)
	{
		Connection connection = (Connection)(Object)this;
		if(connection
			.getPacketListener() instanceof net.minecraft.client.multiplayer.ClientPacketListener
			&& connection.getPacketListener() == net.minecraft.client.Minecraft
				.getInstance().getConnection())
			WurstClient.INSTANCE.getOtfs().packetToolsOtf
				.closeForensicSession("connection_disconnect");
		MovementPacketCompat.reset(connection);
		VanillaSpoofOtf.onDisconnect(connection);
	}
	
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
		method = "doSendPacket",
		at = @At(value = "INVOKE",
			target = "Lio/netty/channel/Channel;writeAndFlush(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;"))
	private io.netty.channel.ChannelFuture wurst$writeFlush(
		io.netty.channel.Channel channel, Object message,
		com.llamalad7.mixinextras.injector.wrapoperation.Operation<io.netty.channel.ChannelFuture> original)
	{
		var callback = WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordForensicTransportSubmitted((Packet<?>)message, true);
		var future = original.call(channel, message);
		future.addListener(done -> callback.accept(done.isSuccess(),
			done.cause() == null ? null : done.cause().toString()));
		return future;
	}
	
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
		method = "doSendPacket",
		at = @At(value = "INVOKE",
			target = "Lio/netty/channel/Channel;write(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;"))
	private io.netty.channel.ChannelFuture wurst$write(
		io.netty.channel.Channel channel, Object message,
		com.llamalad7.mixinextras.injector.wrapoperation.Operation<io.netty.channel.ChannelFuture> original)
	{
		var callback = WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordForensicTransportSubmitted((Packet<?>)message, true);
		var future = original.call(channel, message);
		future.addListener(done -> callback.accept(done.isSuccess(),
			done.cause() == null ? null : done.cause().toString()));
		return future;
	}
	
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
		method = "doSendPacket",
		at = @At(value = "INVOKE",
			target = "Lio/netty/channel/Channel;writeAndFlush(Ljava/lang/Object;Lio/netty/channel/ChannelPromise;)Lio/netty/channel/ChannelFuture;"))
	private io.netty.channel.ChannelFuture wurst$voidWriteFlush(
		io.netty.channel.Channel channel, Object message,
		io.netty.channel.ChannelPromise promise,
		com.llamalad7.mixinextras.injector.wrapoperation.Operation<io.netty.channel.ChannelFuture> original)
	{
		var callback = WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordForensicTransportSubmitted((Packet<?>)message,
				!promise.isVoid());
		var future = original.call(channel, message, promise);
		if(!promise.isVoid())
			future.addListener(done -> callback.accept(done.isSuccess(),
				done.cause() == null ? null : done.cause().toString()));
		return future;
	}
	
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
		method = "doSendPacket",
		at = @At(value = "INVOKE",
			target = "Lio/netty/channel/Channel;write(Ljava/lang/Object;Lio/netty/channel/ChannelPromise;)Lio/netty/channel/ChannelFuture;"))
	private io.netty.channel.ChannelFuture wurst$voidWrite(
		io.netty.channel.Channel channel, Object message,
		io.netty.channel.ChannelPromise promise,
		com.llamalad7.mixinextras.injector.wrapoperation.Operation<io.netty.channel.ChannelFuture> original)
	{
		var callback = WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordForensicTransportSubmitted((Packet<?>)message,
				!promise.isVoid());
		var future = original.call(channel, message, promise);
		if(!promise.isVoid())
			future.addListener(done -> callback.accept(done.isSuccess(),
				done.cause() == null ? null : done.cause().toString()));
		return future;
	}
	
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
		method = "genericsFtw",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
	private static void wurst$directHandler(Packet packet,
		net.minecraft.network.PacketListener listener,
		com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original)
	{
		if(!net.minecraft.client.Minecraft.getInstance().isSameThread())
		{
			original.call(packet, listener);
			return;
		}
		var tools = WurstClient.INSTANCE.getOtfs().packetToolsOtf;
		tools.recordForensicsPreApplication(packet);
		try
		{
			original.call(packet, listener);
		}finally
		{
			tools.recordForensicHandlerEnd(packet);
		}
	}
	
	private ConnectionPacketOutputEvent getEvent(Packet<?> packet)
	{
		for(ConnectionPacketOutputEvent event : events)
			if(event.getPacket() == packet)
				return event;
			
		return null;
	}
}
