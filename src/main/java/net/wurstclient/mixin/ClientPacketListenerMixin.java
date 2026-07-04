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

import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessagesTracker;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AutoSignHack;
import net.wurstclient.util.ChatUtils;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin
	extends ClientCommonPacketListenerImpl
	implements TickablePacketListener, ClientGamePacketListener
{
	@Shadow
	private LastSeenMessagesTracker lastSeenMessages;
	
	private ClientPacketListenerMixin(WurstClient wurst, Minecraft client,
		Connection connection, CommonListenerCookie connectionState)
	{
		super(client, connection, connectionState);
	}
	
	@Inject(
		method = "handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V",
		at = @At("TAIL"))
	public void onOnGameJoin(ClientboundLoginPacket packet, CallbackInfo ci)
	{
		WurstClient wurst = WurstClient.INSTANCE;
		if(!wurst.isEnabled())
			return;
		
		wurst.getHax().instantCommandHack.onServerJoin();
		wurst.getHax().joinDropHack.onServerJoin();
		
		// Remove Mojang's dishonest warning toast on safe servers
		if(!packet.enforcesSecureChat())
		{
			minecraft.gui.toastManager().queued.removeIf(toast -> toast
				.getToken() == SystemToast.SystemToastId.UNSECURE_SERVER_WARNING);
			return;
		}
		
		// Add an honest warning toast on unsafe servers (if enabled)
		if(!wurst.getOtfs().noChatReportsOtf.isUnsafeChatToastEnabled())
			return;
		
		MutableComponent title = Component.literal(ChatUtils.WURST_PREFIX
			+ wurst.translate("toast.wurst.nochatreports.unsafe_server.title"));
		MutableComponent message = Component.literal(
			wurst.translate("toast.wurst.nochatreports.unsafe_server.message"));
		
		SystemToast systemToast = new SystemToast(
			SystemToast.SystemToastId.UNSECURE_SERVER_WARNING, title, message);
		minecraft.gui.toastManager().addToast(systemToast);
	}
	
	@Inject(
		method = "handleOpenSignEditor(Lnet/minecraft/network/protocol/game/ClientboundOpenSignEditorPacket;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
			shift = At.Shift.AFTER),
		cancellable = true)
	private void wurst$autoSignWithoutScreen(
		ClientboundOpenSignEditorPacket packet, CallbackInfo ci)
	{
		WurstClient wurst = WurstClient.INSTANCE;
		if(wurst == null || !wurst.isEnabled())
			return;
		
		AutoSignHack autoSign = wurst.getHax().autoSignHack;
		if(autoSign == null || !autoSign.isAuraActive())
			return;
		
		BlockPos pos = packet.getPos();
		if(minecraft.getConnection() == null)
			return;
		
		if(autoSign.writeTextWithoutScreen(pos, packet.isFrontText()))
			ci.cancel();
	}
	
	@Inject(method = "sendChat(Ljava/lang/String;)V", at = @At("HEAD"))
	private void wurst$logSendChat(String message, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getOtfs() == null)
			return;
		
		WurstClient.INSTANCE.getOtfs().packetToolsOtf.logVerboseChatAction(
			"ClientPacketListener.sendChat", message, false);
	}
	
	@Inject(method = "sendCommand(Ljava/lang/String;)V", at = @At("HEAD"))
	private void wurst$logSendCommand(String command, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getOtfs() == null)
			return;
		
		WurstClient.INSTANCE.getOtfs().packetToolsOtf.logVerboseChatAction(
			"ClientPacketListener.sendCommand", command, true);
	}
	
	@Inject(
		method = "sendUnattendedCommand(Ljava/lang/String;Lnet/minecraft/client/gui/screens/Screen;)V",
		at = @At("HEAD"))
	private void wurst$logSendUnattendedCommand(String command,
		net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getOtfs() == null)
			return;
		
		WurstClient.INSTANCE.getOtfs().packetToolsOtf.logVerboseChatAction(
			"ClientPacketListener.sendUnattendedCommand", command, true);
	}
	
	@Inject(
		method = "markMessageAsProcessed(Lnet/minecraft/network/chat/MessageSignature;Z)V",
		at = @At("HEAD"))
	private void wurst$logChatAcknowledgement(MessageSignature signature,
		boolean acknowledged, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getOtfs() == null)
			return;
		
		LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
		fields.put("stage", "markMessageAsProcessed");
		fields.put("signature",
			signature != null ? signature.toString() : null);
		fields.put("acknowledged", acknowledged);
		fields.put("lastSeenOffset",
			lastSeenMessages != null ? lastSeenMessages.offset() : null);
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.logVerboseExternalEvent("ChatAck", fields);
	}
	
	@Inject(method = "sendChatAcknowledgement()V", at = @At("HEAD"))
	private void wurst$logChatAcknowledgementSend(CallbackInfo ci)
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getOtfs() == null)
			return;
		
		LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
		fields.put("stage", "sendChatAcknowledgement");
		fields.put("lastSeenOffset",
			lastSeenMessages != null ? lastSeenMessages.offset() : null);
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.logVerboseExternalEvent("ChatAck", fields);
	}
	
	@Inject(
		method = "initializeChatSession(Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket$Entry;Lnet/minecraft/client/multiplayer/PlayerInfo;)V",
		at = @At("RETURN"))
	private void wurst$logChatSessionSync(
		net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry entry,
		PlayerInfo playerInfo, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getOtfs() == null)
			return;
		
		LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
		fields.put("stage", "initializeChatSession");
		fields.put("profileName", playerInfo.getProfile() != null
			? playerInfo.getProfile().name() : null);
		fields.put("profileId", playerInfo.getProfile() != null
			? String.valueOf(playerInfo.getProfile().id()) : null);
		fields.put("hasChatSession", playerInfo.getChatSession() != null);
		fields.put("hasVerifiableChat", playerInfo.hasVerifiableChat());
		fields.put("sessionId", playerInfo.getChatSession() != null
			? String.valueOf(playerInfo.getChatSession().sessionId()) : null);
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.logVerboseExternalEvent("ChatAuthCallback", fields);
	}
	
	@Inject(
		method = "setKeyPair(Lnet/minecraft/world/entity/player/ProfileKeyPair;)V",
		at = @At("HEAD"))
	private void wurst$logKeyPairUpdate(ProfileKeyPair keyPair, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getOtfs() == null)
			return;
		
		LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
		fields.put("stage", "setKeyPair");
		fields.put("hasKeyPair", keyPair != null);
		fields.put("refreshedAfter",
			keyPair != null ? String.valueOf(keyPair.refreshedAfter()) : null);
		fields.put("dueRefresh", keyPair != null && keyPair.dueRefresh());
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.logVerboseExternalEvent("AuthCallback", fields);
	}
	
	@Inject(
		method = "updateLevelChunk(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)V",
		at = @At("TAIL"))
	private void onLoadChunk(int x, int z,
		ClientboundLevelChunkPacketData chunkData, CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().newChunksHack.afterLoadChunk(x, z);
		WurstClient.INSTANCE.getHax().newerNewChunksHack.afterLoadChunk(x, z);
	}
	
	@Inject(
		method = "handleBlockUpdate(Lnet/minecraft/network/protocol/game/ClientboundBlockUpdatePacket;)V",
		at = @At("TAIL"))
	private void onOnBlockUpdate(ClientboundBlockUpdatePacket packet,
		CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().newChunksHack
			.afterUpdateBlock(packet.getPos());
		WurstClient.INSTANCE.getHax().newerNewChunksHack
			.afterUpdateBlock(packet.getPos());
	}
	
	@Inject(
		method = "handleChunkBlocksUpdate(Lnet/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket;)V",
		at = @At("TAIL"))
	private void onOnChunkDeltaUpdate(
		ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci)
	{
		packet.runUpdates((pos, state) -> {
			WurstClient.INSTANCE.getHax().newChunksHack.afterUpdateBlock(pos);
			WurstClient.INSTANCE.getHax().newerNewChunksHack
				.afterChunkDeltaUpdate(pos, state);
		});
	}
	
	@Inject(
		method = "handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V",
		at = @At("HEAD"))
	private void wurst$funCreepersParticles(ClientboundExplodePacket packet,
		CallbackInfo ci)
	{
		Vec3 center = packet.center();
		var funCreepers = WurstClient.INSTANCE.getHax().funCreepersHack;
		if(!funCreepers.shouldPartyifyExplosion(center))
			return;
			
		// Schedule on render thread to avoid concurrent access to
		// LegacyRandomSource in the particle engine (MC 26.1+ threading
		// detector). handleExplosion runs on the network thread, but
		// addParticle must only be called from the render thread.
		minecraft.execute(() -> funCreepers.spawnPartyEffects(center));
	}
	
	@Redirect(
		method = "handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
	private void wurst$funCreepersSound(ClientLevel level, double x, double y,
		double z, SoundEvent sound, SoundSource source, float volume,
		float pitch, boolean useDistanceDelay, ClientboundExplodePacket packet)
	{
		var funCreepers = WurstClient.INSTANCE.getHax().funCreepersHack;
		if(funCreepers.shouldPartyifyExplosion(packet.center()))
		{
			SoundEvent replacement = funCreepers.getReplacementExplosionSound();
			if(replacement != null)
			{
				level.playLocalSound(x, y, z, replacement,
					funCreepers.getReplacementSoundSource(),
					funCreepers.getReplacementVolume(),
					funCreepers.getReplacementPitch(), useDistanceDelay);
				return;
			}
		}
		
		level.playLocalSound(x, y, z, sound, source, volume, pitch,
			useDistanceDelay);
	}
	
	@Inject(
		method = "handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V",
		at = @At(value = "INVOKE",
			target = "Ljava/util/Optional;ifPresent(Ljava/util/function/Consumer;)V"),
		cancellable = true)
	private void wurst$handleExplosionKnockback(ClientboundExplodePacket packet,
		CallbackInfo ci)
	{
		LocalPlayer player = minecraft.player;
		if(player == null)
			return;
		
		Optional<Vec3> knockback = packet.playerKnockback();
		if(knockback.isEmpty())
			return;
		
		Vec3 vec = knockback.get();
		Vec3 adjusted = WurstClient.INSTANCE.getHax().antiBlastHack
			.modifyKnockback(vec.x, vec.y, vec.z);
		
		player.push(adjusted.x, adjusted.y, adjusted.z);
		ci.cancel();
	}
}
