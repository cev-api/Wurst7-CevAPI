/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatInputListener.ChatInputEvent;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"command scanner", "plugin scanner", "autocomplete scanner",
	"tabcomplete scanner", "tab complete scanner"})
public final class CommandScannerHack extends Hack
	implements UpdateListener, PacketInputListener, ChatInputListener
{
	private static final int RESPONSE_TIMEOUT_TICKS = 20;
	private static final int REQUEST_COOLDOWN_TICKS = 2;
	private static final int EXECUTE_RESPONSE_TIMEOUT_TICKS = 20;
	private static final int EXECUTE_COOLDOWN_TICKS = 4;
	private static final char[] LETTERS =
		"abcdefghijklmnopqrstuvwxyz".toCharArray();
	
	private static final Set<String> VANILLA_COMMANDS =
		new HashSet<>(Arrays.asList("advancement", "attribute", "ban", "ban-ip",
			"banlist", "bossbar", "clear", "clone", "damage", "data",
			"datapack", "debug", "defaultgamemode", "deop", "difficulty",
			"effect", "enchant", "execute", "experience", "fill", "fillbiome",
			"forceload", "function", "gamemode", "gamerule", "give", "help",
			"item", "jfr", "kick", "kill", "list", "locate", "loot", "me",
			"msg", "op", "pardon", "pardon-ip", "particle", "perf", "place",
			"playsound", "publish", "random", "recipe", "reload", "return",
			"ride", "save-all", "save-off", "save-on", "say", "schedule",
			"scoreboard", "seed", "setblock", "setidletimeout", "setworldspawn",
			"spawnpoint", "spectate", "spreadplayers", "stop", "stopsound",
			"summon", "tag", "team", "teammsg", "teleport", "tell", "tellraw",
			"tick", "time", "title", "tm", "tp", "transfer", "trigger", "w",
			"weather", "whitelist", "worldborder", "xp"));
	
	private final HashSet<String> scannedCommands = new HashSet<>();
	private final HashSet<String> packetScannedCommands = new HashSet<>();
	private final EnumSetting<ScanMode> scanMode =
		new EnumSetting<>("Scan mode", "Choose how to collect command roots.",
			ScanMode.values(), ScanMode.PACKET_PROBING);
	private final CheckboxSetting debugProbe =
		new CheckboxSetting("Debug probe",
			"Shows debug messages for each packet probe (sent, response size,"
				+ " timeout).",
			false);
	private final CheckboxSetting runFoundCommands = new CheckboxSetting(
		"Run found commands (packet)",
		"After scanning, sends each found non-vanilla command using only the"
			+ " command packet API (no chat fallback) and prints the first chat"
			+ " response (or timeout).",
		false);
	private final TextFieldSetting dontSendFilter =
		new TextFieldSetting("Don't send filter",
			"Comma-separated terms for commands that should NOT be executed. "
				+ "Example: op,ban,admin",
			"");
	private final TextFieldSetting packetCommandsInput =
		new TextFieldSetting("Packet commands",
			"Comma-separated commands to send manually via command packets. "
				+ "Example: help,list,plugins",
			"");
	private final ButtonSetting sendPacketCommandsButton = new ButtonSetting(
		"Send packet commands", this::sendManualPacketCommands);
	private final ArrayList<String> commandsToExecute = new ArrayList<>();
	
	private Phase phase = Phase.SCANNING;
	
	private boolean awaitingResponse;
	private int waitTicks;
	private int cooldownTicks;
	private int letterIndex;
	private int requestId;
	private boolean finishing;
	private boolean unknownOnlyDiffReady;
	
	public CommandScannerHack()
	{
		super("CommandScanner");
		setCategory(Category.OTHER);
		addSetting(scanMode);
		addSetting(debugProbe);
		addSetting(runFoundCommands);
		addSetting(dontSendFilter);
		addSetting(packetCommandsInput);
		addSetting(sendPacketCommandsButton);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.getConnection() == null || MC.player == null)
		{
			ChatUtils.error("Not connected to a server.");
			setEnabled(false);
			return;
		}
		
		scannedCommands.clear();
		awaitingResponse = false;
		waitTicks = 0;
		cooldownTicks = 0;
		letterIndex = 0;
		requestId = 1;
		finishing = false;
		unknownOnlyDiffReady = false;
		packetScannedCommands.clear();
		commandsToExecute.clear();
		phase = Phase.SCANNING;
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		ChatUtils.message(
			"CommandScanner started (" + scanMode.getSelected() + ").");
		
		if(scanMode.getSelected() == ScanMode.CLIENT_SIDE_ENUMERATION)
		{
			runClientSideEnumerationScan();
			return;
		}
		
		sendNextRequest();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(ChatInputListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(phase == Phase.EXECUTING)
		{
			runExecutionStep();
			return;
		}
		
		if(awaitingResponse)
		{
			waitTicks++;
			if(waitTicks >= RESPONSE_TIMEOUT_TICKS)
			{
				if(debugProbe.isChecked())
					ChatUtils.message("Probe timeout: /" + LETTERS[letterIndex]
						+ " (id=" + requestId + ")");
				
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
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!awaitingResponse)
			return;
		
		Packet<?> packet = event.getPacket();
		if(!(packet instanceof ClientboundCommandSuggestionsPacket suggestionsPacket))
			return;
		
		if(suggestionsPacket.id() != requestId)
			return;
		
		Suggestions suggestions = suggestionsPacket.toSuggestions();
		int count = suggestions == null ? 0 : suggestions.getList().size();
		if(debugProbe.isChecked())
			ChatUtils.message("Probe response: /" + LETTERS[letterIndex]
				+ " (id=" + requestId + ", suggestions=" + count + ")");
		
		readSuggestions(suggestions);
		awaitingResponse = false;
		letterIndex++;
		cooldownTicks = REQUEST_COOLDOWN_TICKS;
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		if(phase != Phase.EXECUTING || !awaitingResponse)
			return;
		
		String msg = event.getComponent().getString();
		if(msg == null || msg.isBlank() || looksLikeOwnMessage(msg))
			return;
		
		String command = getCurrentExecutionCommand();
		awaitingResponse = false;
		letterIndex++;
		cooldownTicks = EXECUTE_COOLDOWN_TICKS;
		ChatUtils.message("/" + command + " -> " + msg.trim());
	}
	
	private void sendNextRequest()
	{
		if(MC.getConnection() == null)
		{
			finishScan();
			return;
		}
		
		if(letterIndex >= LETTERS.length)
		{
			finishScan();
			return;
		}
		
		char letter = LETTERS[letterIndex];
		requestId++;
		waitTicks = 0;
		awaitingResponse = true;
		if(debugProbe.isChecked())
			ChatUtils
				.message("Probe sent: /" + letter + " (id=" + requestId + ")");
		MC.getConnection().send(
			new ServerboundCommandSuggestionPacket(requestId, "/" + letter));
	}
	
	private void runClientSideEnumerationScan()
	{
		Set<String> clientCommands = collectClientSideRootCommands();
		if(clientCommands == null)
			return;
		
		scannedCommands.clear();
		scannedCommands.addAll(clientCommands);
		
		if(debugProbe.isChecked())
			ChatUtils.message("Client-side enumeration found "
				+ scannedCommands.size() + " root command(s).");
		
		finishScan();
	}
	
	private Set<String> collectClientSideRootCommands()
	{
		if(MC.getConnection() == null)
		{
			ChatUtils.error("Not connected to a server.");
			setEnabled(false);
			return null;
		}
		
		var dispatcher = MC.getConnection().getCommands();
		if(dispatcher == null)
		{
			ChatUtils.warning("Client command tree unavailable.");
			setEnabled(false);
			return null;
		}
		
		HashSet<String> clientCommands = new HashSet<>();
		for(var node : dispatcher.getRoot().getChildren())
		{
			String name = node.getName();
			if(name == null || name.isBlank())
				continue;
			
			clientCommands.add(name.toLowerCase(Locale.ROOT));
		}
		
		return clientCommands;
	}
	
	private void readSuggestions(Suggestions suggestions)
	{
		if(suggestions == null)
			return;
		
		for(Suggestion suggestion : suggestions.getList())
		{
			String root = extractRootCommand(suggestion.getText());
			if(root == null)
				continue;
			
			scannedCommands.add(root);
		}
	}
	
	private String extractRootCommand(String text)
	{
		if(text == null)
			return null;
		
		String command = text.trim().toLowerCase(Locale.ROOT);
		if(command.isEmpty())
			return null;
		
		if(command.startsWith("/"))
			command = command.substring(1).trim();
		
		if(command.isEmpty())
			return null;
		
		int space = command.indexOf(' ');
		if(space > 0)
			command = command.substring(0, space);
		
		return command.isEmpty() ? null : command;
	}
	
	private boolean isVanilla(String command)
	{
		if(command == null || command.isEmpty())
			return true;
		
		if(command.startsWith("minecraft:"))
			return true;
		
		return VANILLA_COMMANDS.contains(command);
	}
	
	private void finishScan()
	{
		if(finishing)
			return;
		finishing = true;
		
		if(scanMode.getSelected() == ScanMode.UNKNOWN_ONLY
			&& !unknownOnlyDiffReady)
		{
			packetScannedCommands.clear();
			packetScannedCommands.addAll(scannedCommands);
			
			Set<String> clientCommands = collectClientSideRootCommands();
			if(clientCommands == null)
				return;
			
			scannedCommands.clear();
			for(String command : packetScannedCommands)
				if(!clientCommands.contains(command))
					scannedCommands.add(command);
				
			unknownOnlyDiffReady = true;
			finishing = false;
			if(debugProbe.isChecked())
				ChatUtils.message(
					"Unknown-only diff: packet=" + packetScannedCommands.size()
						+ ", client=" + clientCommands.size() + ", unknown="
						+ scannedCommands.size());
			finishScan();
			return;
		}
		
		TreeSet<String> uniqueNonVanilla =
			new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for(String command : scannedCommands)
		{
			if(!isVanilla(command))
				uniqueNonVanilla.add(command);
		}
		
		if(uniqueNonVanilla.isEmpty())
		{
			ChatUtils.warning(
				"No non-vanilla root commands found (or autocomplete was blocked).");
			setEnabled(false);
			return;
		}
		
		ChatUtils.message(
			"Found " + uniqueNonVanilla.size() + " non-vanilla commands:");
		printCommandList(uniqueNonVanilla);
		
		if(!runFoundCommands.isChecked())
		{
			setEnabled(false);
			return;
		}
		
		commandsToExecute.clear();
		commandsToExecute.addAll(filterCommandsForExecution(uniqueNonVanilla));
		if(commandsToExecute.isEmpty())
		{
			ChatUtils.warning(
				"All discovered commands were filtered out by Don't send filter.");
			setEnabled(false);
			return;
		}
		
		phase = Phase.EXECUTING;
		letterIndex = 0;
		cooldownTicks = 0;
		awaitingResponse = false;
		waitTicks = 0;
		EVENTS.add(ChatInputListener.class, this);
		ChatUtils.message("Running " + commandsToExecute.size()
			+ " discovered commands and waiting for responses...");
	}
	
	private void printCommandList(TreeSet<String> commands)
	{
		ArrayList<String> line = new ArrayList<>();
		for(String command : commands)
		{
			line.add("/" + command);
			if(line.size() >= 8)
			{
				ChatUtils.message(String.join(", ", line));
				line.clear();
			}
		}
		
		if(!line.isEmpty())
			ChatUtils.message(String.join(", ", line));
	}
	
	private void runExecutionStep()
	{
		if(awaitingResponse)
		{
			waitTicks++;
			if(waitTicks < EXECUTE_RESPONSE_TIMEOUT_TICKS)
				return;
			
			String command = getCurrentExecutionCommand();
			awaitingResponse = false;
			letterIndex++;
			cooldownTicks = EXECUTE_COOLDOWN_TICKS;
			ChatUtils.message("/" + command + " -> [no response]");
			return;
		}
		
		if(cooldownTicks > 0)
		{
			cooldownTicks--;
			return;
		}
		
		if(letterIndex >= commandsToExecute.size())
		{
			ChatUtils.message("CommandScanner finished command execution.");
			setEnabled(false);
			return;
		}
		
		if(MC.getConnection() == null)
		{
			ChatUtils.error("Disconnected while running commands.");
			setEnabled(false);
			return;
		}
		
		String command = getCurrentExecutionCommand();
		waitTicks = 0;
		awaitingResponse = true;
		sendExecutionCommandPacket(command);
	}
	
	private void sendExecutionCommandPacket(String command)
	{
		if(MC.getConnection() == null || command == null)
			return;
		
		String normalized = command.trim();
		if(normalized.startsWith("/"))
			normalized = normalized.substring(1).trim();
		
		if(normalized.isEmpty())
			return;
		
		// Packet-only execution path. Intentionally no slash-chat fallback.
		MC.getConnection().sendCommand(normalized);
	}
	
	private String getCurrentExecutionCommand()
	{
		if(letterIndex < 0 || letterIndex >= commandsToExecute.size())
			return "";
		
		return commandsToExecute.get(letterIndex);
	}
	
	private boolean looksLikeOwnMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if(lower.contains("commandscanner"))
			return true;
		
		return msg.startsWith("[Wurst]") || msg.startsWith("Wurst]");
	}
	
	private ArrayList<String> filterCommandsForExecution(Set<String> commands)
	{
		ArrayList<String> filters = getExecutionFilters();
		if(filters.isEmpty())
			return new ArrayList<>(commands);
		
		ArrayList<String> filtered = new ArrayList<>();
		for(String command : commands)
		{
			if(command == null)
				continue;
			
			String lower = command.toLowerCase(Locale.ROOT);
			boolean blocked = false;
			for(String term : filters)
				if(lower.contains(term))
				{
					blocked = true;
					break;
				}
			
			if(!blocked)
				filtered.add(command);
		}
		
		return filtered;
	}
	
	private ArrayList<String> getExecutionFilters()
	{
		ArrayList<String> filters = new ArrayList<>();
		String raw = dontSendFilter.getValue();
		if(raw == null || raw.isBlank())
			return filters;
		
		for(String token : raw.split(","))
		{
			String term = token.trim().toLowerCase(Locale.ROOT);
			if(!term.isEmpty() && !filters.contains(term))
				filters.add(term);
		}
		
		return filters;
	}
	
	private void sendManualPacketCommands()
	{
		if(MC.getConnection() == null)
		{
			ChatUtils.error("Not connected to a server.");
			return;
		}
		
		String raw = packetCommandsInput.getValue();
		if(raw == null || raw.isBlank())
		{
			ChatUtils.warning("Packet commands input is empty.");
			return;
		}
		
		int sent = 0;
		for(String token : raw.split(","))
		{
			String command = token.trim();
			if(command.isEmpty())
				continue;
			
			sendExecutionCommandPacket(command);
			sent++;
		}
		
		if(sent == 0)
			ChatUtils.warning("No valid commands to send.");
		else
			ChatUtils.message("Sent " + sent + " packet command(s).");
	}
	
	private enum Phase
	{
		SCANNING,
		EXECUTING;
	}
	
	private enum ScanMode
	{
		PACKET_PROBING("Packet probing"),
		CLIENT_SIDE_ENUMERATION("Client-side enumeration"),
		UNKNOWN_ONLY("Unknown only");
		
		private final String name;
		
		private ScanMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
}
