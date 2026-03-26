/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.ChatInputListener.ChatInputEvent;
import net.wurstclient.hud.ClientMessageOverlay;

@Mixin(ChatComponent.class)
public class ChatComponentMixin
{
	@Shadow
	@Final
	private List<GuiMessage.Line> trimmedMessages;
	
	@Inject(at = @At("HEAD"),
		method = "addClientSystemMessage(Lnet/minecraft/network/chat/Component;)V",
		cancellable = true)
	private void onAddClientSystemMessage(Component message, CallbackInfo ci)
	{
		if(ClientMessageOverlay.getInstance().captureSingleArgMessage(message))
		{
			ChatInputEvent event = new ChatInputEvent(message, trimmedMessages);
			EventManager.fire(event);
			ci.cancel();
			return;
		}
		
		ClientMessageOverlay.getInstance().notifyVanillaChatMessage(message);
	}
	
	@Inject(at = @At("HEAD"),
		method = "addServerSystemMessage(Lnet/minecraft/network/chat/Component;)V",
		cancellable = true)
	private void onAddServerSystemMessage(Component message, CallbackInfo ci)
	{
		ChatInputEvent event = new ChatInputEvent(message, trimmedMessages);
		EventManager.fire(event);
		if(event.isCancelled())
		{
			ci.cancel();
			return;
		}
		
		if(ClientMessageOverlay.getInstance()
			.captureIfNonPlayerMessage(event.getComponent(), null))
		{
			ci.cancel();
			return;
		}
		
		ClientMessageOverlay.getInstance()
			.notifyVanillaChatMessage(event.getComponent());
	}
	
	@Inject(at = @At("HEAD"),
		method = "addPlayerMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
		cancellable = true)
	private void onAddPlayerMessage(Component messageDontUse,
		@Nullable MessageSignature signature,
		@Nullable GuiMessageTag indicatorDontUse, CallbackInfo ci,
		@Local(argsOnly = true) LocalRef<Component> message,
		@Local(argsOnly = true) LocalRef<GuiMessageTag> indicator)
	{
		ChatInputEvent event =
			new ChatInputEvent(message.get(), trimmedMessages);
		
		EventManager.fire(event);
		if(event.isCancelled())
		{
			ci.cancel();
			return;
		}
		
		message.set(event.getComponent());
		if(ClientMessageOverlay.getInstance()
			.captureIfNonPlayerMessage(message.get(), signature))
		{
			ci.cancel();
			return;
		}
		
		indicator.set(WurstClient.INSTANCE.getOtfs().noChatReportsOtf
			.modifyIndicator(message.get(), signature, indicator.get()));
		ClientMessageOverlay.getInstance()
			.notifyVanillaChatMessage(message.get());
	}
}
