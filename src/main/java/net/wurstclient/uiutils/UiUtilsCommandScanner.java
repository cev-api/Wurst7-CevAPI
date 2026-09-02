/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

public final class UiUtilsCommandScanner
{
	private static final int RESPONSE_TIMEOUT_TICKS = 20;
	private static final int REQUEST_COOLDOWN_TICKS = 2;
	private static final int EXECUTE_COOLDOWN_TICKS = 4;
	private static final int MANUAL_OUTPUT_TIMEOUT_TICKS = 100;
	private static final int MAX_MANUAL_OUTPUT_LINES = 12;
	private static final char[] LETTERS =
		"abcdefghijklmnopqrstuvwxyz".toCharArray();
	
	private static final Set<String> VANILLA_COMMANDS = new HashSet<>(
		Arrays.asList("advancement", "attribute", "ban", "ban-ip", "banlist",
			"bossbar", "clear", "clone", "damage", "data", "datapack", "debug",
			"defaultgamemode", "deop", "dialog", "difficulty", "effect",
			"enchant", "execute", "experience", "fill", "fillbiome",
			"forceload", "function", "gamemode", "gamerule", "give", "help",
			"item", "jfr", "kick", "kill", "list", "locate", "loot", "me",
			"msg", "op", "pardon", "pardon-ip", "particle", "perf", "place",
			"playsound", "publish", "random", "recipe", "reload", "return",
			"ride", "rotate", "save-all", "save-off", "save-on", "say",
			"schedule", "scoreboard", "seed", "setblock", "setidletimeout",
			"setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stop",
			"stopsound", "stopwatch", "summon", "swing", "tag", "team",
			"teammsg", "teleport", "tell", "tellraw", "test", "tick", "time",
			"title", "tm", "tp", "transfer", "trigger", "version", "w",
			"waypoint", "weather", "whitelist", "worldborder", "xp"));
	
	private static final Set<String> scannedCommands =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private static final Set<String> hiddenCommands =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private static final Set<String> triggerValues =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private static final ArrayDeque<String> commandsToExecute =
		new ArrayDeque<>();
	
	private static boolean awaitingResponse;
	private static boolean triggerProbePending;
	private static int waitTicks;
	private static int cooldownTicks;
	private static int letterIndex;
	private static int requestId;
	private static int awaitingRequestId;
	private static boolean active;
	private static Phase phase = Phase.IDLE;
	private static ScanMode activeMode = ScanMode.PACKET_PROBING;
	private static String lastStatus = "Idle.";
	private static final List<String> recentEvents = new ArrayList<>();
	private static List<String> lastFoundCommands = List.of();
	private static String boundServerKey = "";
	private static final List<String> manualCommandOutput = new ArrayList<>();
	private static int manualOutputTicks;
	
	private UiUtilsCommandScanner()
	{}
	
	public static String startScan()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || mc.player.connection == null)
			return "[UI-Utils] Not connected.";
		if(active)
			return "[UI-Utils] Command scanner already running.";
		
		active = true;
		scannedCommands.clear();
		hiddenCommands.clear();
		commandsToExecute.clear();
		awaitingResponse = false;
		waitTicks = 0;
		cooldownTicks = 0;
		letterIndex = 0;
		requestId = 1;
		awaitingRequestId = -1;
		phase = Phase.SCANNING;
		activeMode = getScanMode();
		lastStatus = "Scanning commands (" + activeMode.name() + ")...";
		recentEvents.clear();
		lastFoundCommands = List.of();
		boundServerKey = currentServerKey(mc);
		if(activeMode == ScanMode.CLIENT_SIDE_ENUMERATION)
		{
			// The client-side enumeration fallback was removed because the
			// merged
			// dispatcher also contains commands registered by client-side mods.
			runClientSideEnumerationScan();
			return "[UI-Utils] Command scanner started (CLIENT_SIDE_ENUMERATION).";
		}
		
		sendNextRequest();
		return "[UI-Utils] Command scanner started (PACKET_PROBING).";
	}
	
	public static void onTick()
	{
		if(manualOutputTicks > 0 && --manualOutputTicks == 0)
			addManualOutput("(No further server response received.)");
		
		Minecraft mc = Minecraft.getInstance();
		String currentServer = currentServerKey(mc);
		if(!boundServerKey.isEmpty() && !currentServer.equals(boundServerKey))
		{
			resetForServerChange();
			return;
		}
		
		if(!active)
			return;
		
		if(phase == Phase.EXECUTING)
		{
			runExecutionStep();
			return;
		}
		
		if(activeMode != ScanMode.PACKET_PROBING)
			return;
		
		if(awaitingResponse)
		{
			waitTicks++;
			if(waitTicks >= RESPONSE_TIMEOUT_TICKS)
			{
				if(triggerProbePending)
				{
					triggerProbePending = false;
					awaitingResponse = false;
					awaitingRequestId = -1;
					finishScan();
					return;
				}
				if(UiUtilsSettings.get().commandScannerDebugProbe)
					print("Probe timeout: /" + LETTERS[letterIndex] + " (id="
						+ requestId + ")");
				lastStatus = "Scanning commands... timed out on /"
					+ LETTERS[letterIndex];
				awaitingResponse = false;
				letterIndex++;
				cooldownTicks = REQUEST_COOLDOWN_TICKS;
			}
			return;
		}
		
		if(cooldownTicks > 0)
		{
			cooldownTicks--;
			return;
		}
		
		sendNextRequest();
	}
	
	public static void onSuggestionsPacket(
		ClientboundCommandSuggestionsPacket packet)
	{
		if(!active || phase != Phase.SCANNING
			|| activeMode != ScanMode.PACKET_PROBING)
			return;
		if(!awaitingResponse)
			return;
		if(packet.id() != awaitingRequestId)
			return;
		
		if(triggerProbePending)
		{
			Suggestions triggerSuggestions;
			try
			{
				triggerSuggestions = packet.toSuggestions();
			}catch(Exception e)
			{
				UiUtils.LOGGER.warn(
					"Command scanner: failed to parse trigger suggestions.", e);
				triggerSuggestions = null;
			}
			readTriggerValues(triggerSuggestions);
			triggerProbePending = false;
			awaitingResponse = false;
			awaitingRequestId = -1;
			finishScan();
			return;
		}
		
		Suggestions suggestions;
		try
		{
			suggestions = packet.toSuggestions();
		}catch(Exception e)
		{
			UiUtils.LOGGER.warn("Command scanner: failed to parse suggestions.",
				e);
			suggestions = null;
		}
		
		int count = suggestions == null ? 0 : suggestions.getList().size();
		if(UiUtilsSettings.get().commandScannerDebugProbe)
			print("Probe response: /" + LETTERS[letterIndex] + " (id="
				+ awaitingRequestId + ", suggestions=" + count + ")");
		
		readSuggestions(suggestions);
		awaitingResponse = false;
		awaitingRequestId = -1;
		letterIndex++;
		cooldownTicks = REQUEST_COOLDOWN_TICKS;
	}
	
	public static String sendManualPacketCommands()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || mc.player.connection == null)
			return "[UI-Utils] Not connected.";
		
		String raw = UiUtilsSettings.get().commandScannerPacketCommands;
		if(raw == null || raw.isBlank())
			return "[UI-Utils] Packet commands list is empty.";
		
		manualCommandOutput.clear();
		manualOutputTicks = MANUAL_OUTPUT_TIMEOUT_TICKS;
		String[] parts = raw.split(",");
		int sent = 0;
		for(String part : parts)
		{
			String cmd = part.trim();
			if(cmd.isEmpty())
				continue;
			if(cmd.startsWith("/"))
				cmd = cmd.substring(1);
			mc.player.connection.sendCommand(cmd);
			sent++;
		}
		if(sent == 0)
			manualOutputTicks = 0;
		return "[UI-Utils] Sent " + sent + " packet command(s).";
	}
	
	public static void onSystemChat(Component message)
	{
		if(manualOutputTicks <= 0 || message == null)
			return;
		addManualOutput(message.getString().trim());
	}
	
	public static List<String> getManualCommandOutputSnapshot()
	{
		return new ArrayList<>(manualCommandOutput);
	}
	
	public static void clearManualCommandOutput()
	{
		manualCommandOutput.clear();
		manualOutputTicks = 0;
	}
	
	private static void addManualOutput(String text)
	{
		if(text == null || text.isBlank())
			return;
		manualCommandOutput.add(text);
		while(manualCommandOutput.size() > MAX_MANUAL_OUTPUT_LINES)
			manualCommandOutput.remove(0);
	}
	
	private static void sendNextRequest()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || mc.player.connection == null)
		{
			finish();
			return;
		}
		
		if(letterIndex >= LETTERS.length)
		{
			requestTriggerValues();
			return;
		}
		
		char c = LETTERS[letterIndex];
		String input = "/" + c;
		int id = requestId++;
		if(UiUtilsSettings.get().commandScannerDebugProbe)
			print("Probe sent: " + input + " (id=" + id + ")");
		
		mc.player.connection
			.send(new ServerboundCommandSuggestionPacket(id, input));
		awaitingResponse = true;
		awaitingRequestId = id;
		waitTicks = 0;
	}
	
	private static void requestTriggerValues()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || mc.player.connection == null)
		{
			finishScan();
			return;
		}
		triggerProbePending = true;
		int id = requestId++;
		awaitingRequestId = id;
		awaitingResponse = true;
		waitTicks = 0;
		mc.player.connection
			.send(new ServerboundCommandSuggestionPacket(id, "/trigger "));
	}
	
	private static void readTriggerValues(Suggestions suggestions)
	{
		if(suggestions == null)
			return;
		for(Suggestion suggestion : suggestions.getList())
		{
			String value =
				suggestion.getText() == null ? "" : suggestion.getText().trim();
			if(!value.isEmpty())
				triggerValues.add(value);
		}
	}
	
	private static void readSuggestions(Suggestions suggestions)
	{
		if(suggestions == null)
			return;
		for(Suggestion suggestion : suggestions.getList())
		{
			String command = extractRootCommand(suggestion.getText());
			if(command != null && !command.equalsIgnoreCase("trigger")
				&& !isVanillaOrDefaultCommand(command))
			{
				scannedCommands.add(command);
				classifyDiscoveredCommand(command);
			}
		}
	}
	
	private static String extractRootCommand(String raw)
	{
		if(raw == null)
			return null;
		String text = raw.trim();
		if(text.isEmpty())
			return null;
		if(text.startsWith("/"))
			text = text.substring(1);
		int space = text.indexOf(' ');
		if(space >= 0)
			text = text.substring(0, space);
		if(text.isBlank())
			return null;
		return text;
	}
	
	private static void runClientSideEnumerationScan()
	{
		// Client-side enumeration was a fallback for packet probing, but the
		// merged dispatcher cannot distinguish local commands from server ones.
		finishScan();
	}
	
	private static void finishScan()
	{
		List<String> results = new ArrayList<>(scannedCommands);
		for(String value : triggerValues)
			results.add("trigger (" + value + ")");
		print("Command scanner found " + results.size() + " commands.");
		lastFoundCommands = results;
		lastStatus = "Found " + results.size() + " commands.";
		UiUtilsScanHistory.recordCommands(boundServerKey,
			"command_" + activeMode.name().toLowerCase(Locale.ROOT),
			lastFoundCommands);
		if(results.isEmpty())
		{
			finish();
			return;
		}
		
		if(UiUtilsSettings.get().commandScannerRunFoundCommands)
		{
			commandsToExecute.clear();
			Set<String> denyTerms = parseDenyTerms(
				UiUtilsSettings.get().commandScannerDontSendFilter);
			for(String cmd : scannedCommands)
			{
				String lower = cmd.toLowerCase(Locale.ROOT);
				if(VANILLA_COMMANDS.contains(lower))
					continue;
				boolean blocked = false;
				for(String deny : denyTerms)
				{
					if(lower.contains(deny))
					{
						blocked = true;
						break;
					}
				}
				if(!blocked)
					commandsToExecute.add(cmd);
			}
			print("Executing " + commandsToExecute.size()
				+ " found non-vanilla command(s) via packets.");
			lastStatus = "Executing " + commandsToExecute.size()
				+ " discovered command(s)...";
			phase = Phase.EXECUTING;
			cooldownTicks = 0;
			return;
		}
		
		finish();
	}
	
	private static void runExecutionStep()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || mc.player.connection == null)
		{
			finish();
			return;
		}
		
		if(cooldownTicks > 0)
		{
			cooldownTicks--;
			return;
		}
		
		String cmd = commandsToExecute.poll();
		if(cmd == null)
		{
			finish();
			print("Command execution pass complete.");
			lastStatus = "Execution pass complete.";
			return;
		}
		
		mc.player.connection
			.sendCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
		print("Sent packet command: /" + cmd);
		cooldownTicks = EXECUTE_COOLDOWN_TICKS;
	}
	
	private static Set<String> parseDenyTerms(String raw)
	{
		Set<String> terms = new HashSet<>();
		if(raw == null || raw.isBlank())
			return terms;
		for(String part : raw.split(","))
		{
			String term = part.trim().toLowerCase(Locale.ROOT);
			if(!term.isEmpty())
				terms.add(term);
		}
		return terms;
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
	
	private static void classifyDiscoveredCommand(String command)
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || mc.player.connection == null)
		{
			hiddenCommands.add(command);
			return;
		}
		CommandDispatcher<ClientSuggestionProvider> dispatcher =
			mc.player.connection.getCommands();
		if(dispatcher == null || dispatcher.getRoot() == null)
		{
			hiddenCommands.add(command);
			return;
		}
		var commandNode = dispatcher.getRoot().getChild(command);
		if(commandNode == null || !commandNode
			.canUse(mc.player.connection.getSuggestionsProvider()))
			hiddenCommands.add(command);
	}
	
	public static boolean isClientVisibleCommand(String raw)
	{
		String command = extractRootCommand(raw);
		if(command == null)
			return false;
		if(hiddenCommands.contains(command))
			return false;
		if(scannedCommands.contains(command))
			return true;
		classifyDiscoveredCommand(command);
		return !hiddenCommands.contains(command);
	}
	
	public static boolean isVanillaOrDefaultCommand(String raw)
	{
		if(raw == null)
			return true;
		String command = raw.trim().toLowerCase(Locale.ROOT);
		if(command.startsWith("/"))
			command = command.substring(1);
		int colon = command.indexOf(':');
		if(colon > 0 && (command.startsWith("minecraft:")
			|| command.startsWith("brigadier:")
			|| command.startsWith("fabric:")))
			return true;
		return VANILLA_COMMANDS.contains(command);
	}
	
	private static ScanMode getScanMode()
	{
		String raw = UiUtilsSettings.get().commandScannerMode;
		if(raw == null)
			return ScanMode.PACKET_PROBING;
		try
		{
			return ScanMode.valueOf(raw.toUpperCase(Locale.ROOT));
		}catch(IllegalArgumentException ignored)
		{
			return ScanMode.PACKET_PROBING;
		}
	}
	
	private static void finish()
	{
		active = false;
		awaitingResponse = false;
		awaitingRequestId = -1;
		phase = Phase.IDLE;
		cooldownTicks = 0;
		waitTicks = 0;
	}
	
	private static void resetForServerChange()
	{
		active = false;
		awaitingResponse = false;
		awaitingRequestId = -1;
		phase = Phase.IDLE;
		cooldownTicks = 0;
		waitTicks = 0;
		letterIndex = 0;
		requestId = 1;
		scannedCommands.clear();
		hiddenCommands.clear();
		commandsToExecute.clear();
		lastFoundCommands = List.of();
		recentEvents.clear();
		lastStatus = "Cleared due to server change.";
		boundServerKey = currentServerKey(Minecraft.getInstance());
	}
	
	private static String currentServerKey(Minecraft mc)
	{
		if(mc == null)
			return "";
		try
		{
			if(mc.getCurrentServer() != null
				&& mc.getCurrentServer().ip != null)
				return mc.getCurrentServer().ip.trim().toLowerCase(Locale.ROOT);
		}catch(Throwable ignored)
		{}
		try
		{
			if(mc.getConnection() != null
				&& mc.getConnection().getConnection() != null && mc
					.getConnection().getConnection().getRemoteAddress() != null)
				return mc.getConnection().getConnection().getRemoteAddress()
					.toString();
		}catch(Throwable ignored)
		{}
		return "";
	}
	
	public static String getStatusLine()
	{
		if(active && phase == Phase.SCANNING)
			return lastStatus + " [probe "
				+ Math.min(letterIndex + 1, LETTERS.length) + "/"
				+ LETTERS.length + "]";
		if(active && phase == Phase.EXECUTING)
			return lastStatus + " [remaining " + commandsToExecute.size() + "]";
		return lastStatus;
	}
	
	public static boolean hasResultsForCurrentServer()
	{
		return !lastFoundCommands.isEmpty()
			&& boundServerKey.equals(currentServerKey(Minecraft.getInstance()));
	}
	
	public static boolean isActive()
	{
		return active;
	}
	
	public static List<String> getFoundCommandsSnapshot()
	{
		return new ArrayList<>(lastFoundCommands);
	}
	
	public static List<String> getRecentEventsSnapshot()
	{
		return new ArrayList<>(recentEvents);
	}
	
	public static void clearResultsForUi()
	{
		active = false;
		awaitingResponse = false;
		awaitingRequestId = -1;
		phase = Phase.IDLE;
		scannedCommands.clear();
		hiddenCommands.clear();
		commandsToExecute.clear();
		lastFoundCommands = List.of();
		recentEvents.clear();
		lastStatus = "Cleared.";
		letterIndex = 0;
		requestId = 1;
		cooldownTicks = 0;
		waitTicks = 0;
	}
	
	public enum ScanMode
	{
		PACKET_PROBING,
		CLIENT_SIDE_ENUMERATION
	}
	
	private enum Phase
	{
		IDLE,
		SCANNING,
		EXECUTING
	}
}
