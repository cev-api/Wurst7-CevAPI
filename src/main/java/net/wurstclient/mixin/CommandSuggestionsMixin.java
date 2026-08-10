/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

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
	
	@Shadow
	public abstract void showSuggestions(boolean narrateFirstSuggestion);
}
