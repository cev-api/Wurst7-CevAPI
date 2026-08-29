/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

public final class UiUtilsLegacyPluginScanner
{
	private static final Set<String> ANTICHEAT_WORDS = Set.of("nocheatplus",
		"negativity", "vulcan", "spartan", "matrix", "grim", "themis", "kauri",
		"godseye", "anticheat", "exploit", "illegal");
	private static final Random RANDOM = new Random();
	
	private static boolean scanning;
	private static int requestId = -1;
	private static final List<String> foundPlugins = new ArrayList<>();
	private static final Set<String> dedupe = new HashSet<>();
	private static String lastStatus = "Idle.";
	private static final List<UiUtilsPluginScanner.PluginResultRow> lastRows =
		new ArrayList<>();
	private static final List<String> recentEvents = new ArrayList<>();
	
	private UiUtilsLegacyPluginScanner()
	{}
	
	public static void init()
	{
		// packet callbacks come from mixin
	}
	
	public static void onTick()
	{
		// legacy scanner is a one-shot probe; no continuous logic needed
	}
	
	public static String startScan()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.getConnection() == null || mc.player == null)
			return "[UI-Utils] Not connected.";
		if(scanning)
			return "[UI-Utils] Legacy plugin scan already in progress.";
		
		requestId = RANDOM.nextInt(100000);
		mc.getConnection()
			.send(new ServerboundCommandSuggestionPacket(requestId, "ver "));
		foundPlugins.clear();
		dedupe.clear();
		lastRows.clear();
		scanning = true;
		lastStatus = "Legacy scanning plugins...";
		print("Legacy plugin scan started.");
		return "[UI-Utils] Legacy scanning plugins...";
	}
	
	public static void onSuggestionsPacket(
		ClientboundCommandSuggestionsPacket packet)
	{
		if(!scanning)
			return;
		if(packet.id() != requestId)
			return;
		
		try
		{
			Suggestions suggestions = packet.toSuggestions();
			if(suggestions != null)
			{
				for(Suggestion suggestion : suggestions.getList())
				{
					addPluginName(suggestion.getText());
				}
			}
		}catch(Exception e)
		{
			UiUtils.LOGGER.warn("Failed to parse legacy plugin scan response.",
				e);
		}
		
		finishAndPrint();
	}
	
	private static void addPluginName(String rawText)
	{
		if(rawText == null)
			return;
		String text = rawText.trim();
		if(text.isEmpty())
			return;
		if(text.startsWith("/"))
			text = text.substring(1);
		
		String normalized = text.toLowerCase(Locale.ROOT);
		if(!dedupe.add(normalized))
			return;
		foundPlugins.add(text);
	}
	
	private static void finishAndPrint()
	{
		scanning = false;
		requestId = -1;
		
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null)
		{
			foundPlugins.clear();
			dedupe.clear();
			lastRows.clear();
			return;
		}
		
		if(foundPlugins.isEmpty())
		{
			UiUtilsScanHistory.recordPlugins(UiUtilsScanHistory.serverKey(mc),
				"legacy_plugin", List.of());
			lastStatus = "Legacy: No plugins found or blocked.";
			Component noPlugins = Component
				.literal("[UI-Utils] Legacy Plugins (0)").withColor(0x55CCFF);
			mc.player.sendSystemMessage(noPlugins);
			foundPlugins.clear();
			dedupe.clear();
			return;
		}
		
		foundPlugins.sort(String.CASE_INSENSITIVE_ORDER);
		lastRows.clear();
		Component line = Component
			.literal(
				"[UI-Utils] Legacy Plugins (" + foundPlugins.size() + "): ")
			.withColor(0x55CCFF);
		boolean first = true;
		for(String plugin : foundPlugins)
		{
			if(!first)
				line = line.copy()
					.append(Component.literal(", ").withColor(0xD0D0D0));
			first = false;
			String lower = plugin.toLowerCase(Locale.ROOT);
			boolean anticheat =
				ANTICHEAT_WORDS.stream().anyMatch(lower::contains);
			boolean vulnerable = UiUtilsVulnerablePlugins.keys()
				.contains(UiUtilsVulnerablePlugins.normalizeKey(plugin));
			String text = (anticheat ? "!" : "") + plugin;
			int color = vulnerable ? 0xFF6B6B : 0x93F7A4;
			line = line.copy().append(Component.literal(text).withColor(color));
			lastRows.add(new UiUtilsPluginScanner.PluginResultRow("LEGACY",
				plugin, 0, anticheat, List.of()));
		}
		mc.player.sendSystemMessage(line);
		UiUtilsScanHistory.recordPlugins(UiUtilsScanHistory.serverKey(mc),
			"legacy_plugin", lastRows);
		lastStatus = "Legacy: Detected " + foundPlugins.size() + " plugins.";
		foundPlugins.clear();
		dedupe.clear();
	}
	
	private static void print(String msg)
	{
		recentEvents.add(msg);
		if(recentEvents.size() > 60)
			recentEvents.remove(0);
		if(UiUtilsSettings.get().commandScannerDebugProbe)
		{
			Minecraft mc = Minecraft.getInstance();
			if(mc.player != null)
				mc.player
					.sendSystemMessage(Component.literal("[UI-Utils] " + msg));
		}
	}
	
	public static String getStatusLine()
	{
		return lastStatus;
	}
	
	public static boolean isActive()
	{
		return scanning;
	}
	
	public static List<UiUtilsPluginScanner.PluginResultRow> getResultsSnapshot()
	{
		return new ArrayList<>(lastRows);
	}
	
	public static List<String> getRecentEventsSnapshot()
	{
		return new ArrayList<>(recentEvents);
	}
	
	public static void clearResultsForUi()
	{
		scanning = false;
		requestId = -1;
		foundPlugins.clear();
		dedupe.clear();
		lastRows.clear();
		recentEvents.clear();
		lastStatus = "Cleared.";
	}
}
