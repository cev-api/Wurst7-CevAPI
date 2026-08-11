/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.wurstclient.WurstClient;
import net.wurstclient.command.Command;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.AutoCompleteHack;
import net.wurstclient.other_feature.OtherFeature;

@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin
{
	@Shadow
	@Final
	private EditBox input;
	@Shadow
	private CompletableFuture<Suggestions> pendingSuggestions;
	
	@Inject(method = "updateCommandInfo()V", at = @At("TAIL"))
	private void onRefresh(CallbackInfo ci)
	{
		String draftMessage =
			input.getValue().substring(0, input.getCursorPosition());
		if(wurst$showWurstCommandSuggestions(draftMessage))
			return;
		if(wurst$showArgumentSuggestions(draftMessage))
			return;
		
		AutoCompleteHack autoComplete =
			WurstClient.INSTANCE.getHax().autoCompleteHack;
		if(!autoComplete.isEnabled())
			return;
		
		autoComplete.onRefresh(draftMessage, (builder, suggestion) -> {
			input.setSuggestion(suggestion);
			pendingSuggestions = builder.buildFuture();
			showSuggestions(false);
		});
	}
	
	private boolean wurst$showWurstCommandSuggestions(String draftMessage)
	{
		if(draftMessage == null || draftMessage.isEmpty()
			|| draftMessage.startsWith("/"))
			return false;
		
		String prefix = ".";
		try
		{
			prefix = WurstClient.INSTANCE.getOtfs().commandPrefixOtf
				.getPrefixSetting().getSelected().toString();
		}catch(Throwable ignored)
		{}
		
		if(prefix == null || prefix.isEmpty()
			|| !draftMessage.startsWith(prefix))
			return false;
		
		String lowerDraft = draftMessage.toLowerCase(Locale.ROOT);
		Collection<Command> commands =
			WurstClient.INSTANCE.getCmds().getAllCmds();
		Collection<Hack> hacks = WurstClient.INSTANCE.getHax().getAllHax();
		Collection<OtherFeature> otfs =
			WurstClient.INSTANCE.getOtfs().getAllOtfs();
		SuggestionsBuilder builder = new SuggestionsBuilder(draftMessage, 0);
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		String inlineSuggestion = "";
		int suggestions = 0;
		
		for(Command cmd : commands)
		{
			if(cmd == null || cmd.getName() == null)
				continue;
			
			String cmdName = cmd.getName();
			if(cmdName.startsWith("."))
				cmdName = cmdName.substring(1);
			candidates.add(prefix + cmdName);
		}
		
		for(Hack hack : hacks)
		{
			if(hack == null || hack.getName() == null)
				continue;
			
			candidates.add(prefix + hack.getName());
		}
		
		for(OtherFeature otf : otfs)
		{
			if(otf == null || otf.getName() == null)
				continue;
			
			candidates.add(prefix + otf.getName());
		}
		
		for(String candidate : candidates)
		{
			if(!candidate.toLowerCase(Locale.ROOT).startsWith(lowerDraft))
				continue;
			
			builder.suggest(candidate);
			suggestions++;
			if(inlineSuggestion.isEmpty()
				&& candidate.length() > draftMessage.length())
				inlineSuggestion = candidate.substring(draftMessage.length());
		}
		
		if(suggestions == 0)
			return false;
		
		input.setSuggestion(inlineSuggestion);
		pendingSuggestions = builder.buildFuture();
		showSuggestions(false);
		return true;
	}
	
	/**
	 * Suggests the argument currently being typed for the matched command:
	 * custom suggestions from the command itself (e.g. subcommand names),
	 * saved alts for <code>&lt;alt&gt;</code>/<code>&lt;account&gt;</code>
	 * tokens, and online player names for <code>&lt;player&gt;</code> tokens.
	 * Only display names are suggested; credentials are never exposed.
	 */
	private boolean wurst$showArgumentSuggestions(String draftMessage)
	{
		if(draftMessage == null || draftMessage.isEmpty()
			|| draftMessage.startsWith("/"))
			return false;
		
		String prefix = ".";
		try
		{
			prefix = WurstClient.INSTANCE.getOtfs().commandPrefixOtf
				.getPrefixSetting().getSelected().toString();
		}catch(Throwable ignored)
		{}
		
		if(prefix == null || prefix.isEmpty()
			|| !draftMessage.startsWith(prefix))
			return false;
		
		String raw = draftMessage.substring(prefix.length()).stripLeading();
		if(raw.isEmpty())
			return false;
		
		String[] tokens = raw.split("\\s+", -1);
		if(tokens.length < 2)
			return false;
		
		Command cmd = WurstClient.INSTANCE.getCmds().getCmdByName(tokens[0]);
		if(cmd == null)
			return false;
		
		String[] argTokens = Arrays.copyOfRange(tokens, 1, tokens.length);
		int argIndex = argTokens.length - 1;
		String currentToken = argTokens[argIndex];
		String lowerToken = currentToken.toLowerCase(Locale.ROOT);
		int tokenStart = draftMessage.length() - currentToken.length();
		SuggestionsBuilder builder =
			new SuggestionsBuilder(draftMessage, tokenStart);
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		
		// Custom suggestions from the command (e.g. subcommand names).
		for(String suggestion : cmd.getArgumentSuggestions(argTokens, argIndex,
			currentToken))
			if(suggestion != null && !suggestion.isBlank())
				candidates.add(suggestion);
			
		// Saved alts for <alt>/<account> tokens.
		if(cmd.shouldSuggestAltNames(argIndex))
		{
			var botManager = WurstClient.INSTANCE.getAltBotManager();
			if(botManager != null)
				for(var alt : botManager.getCompatibleAlts())
				{
					String name = alt.getDisplayName();
					if(name != null && !name.isBlank())
						candidates.add(name);
				}
		}
		
		// Online player names for <player> tokens.
		if(cmd.shouldSuggestPlayerNames(argIndex)
			&& WurstClient.MC.getConnection() != null)
			for(PlayerInfo info : WurstClient.MC.getConnection()
				.getOnlinePlayers())
			{
				if(info == null || info.getProfile() == null
					|| info.getProfile().name() == null)
					continue;
				candidates.add(info.getProfile().name());
			}
		
		LinkedHashSet<String> filtered = new LinkedHashSet<>();
		for(String candidate : candidates)
			if(lowerToken.isEmpty()
				|| candidate.toLowerCase(Locale.ROOT).startsWith(lowerToken))
				filtered.add(candidate);
			
		String inlineSuggestion = "";
		for(String candidate : filtered)
		{
			builder.suggest(candidate);
			if(inlineSuggestion.isEmpty()
				&& candidate.length() > currentToken.length())
				inlineSuggestion = candidate.substring(currentToken.length());
		}
		
		if(filtered.isEmpty())
			return false;
		
		input.setSuggestion(inlineSuggestion);
		pendingSuggestions = builder.buildFuture();
		showSuggestions(false);
		return true;
	}
	
	@Shadow
	public abstract void showSuggestions(boolean narrateFirstSuggestion);
}
