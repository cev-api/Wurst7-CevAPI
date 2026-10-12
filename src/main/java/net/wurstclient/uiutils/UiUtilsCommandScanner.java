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
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
	private static final int RESPONSE_TIMEOUT_TICKS = 60;
	private static final int MAX_PROBE_ATTEMPTS = 3;
	private static final int EXECUTE_COOLDOWN_TICKS = 40;
	private static final int MAX_SUGGESTIONS_PER_RESPONSE = 1000;
	private static final int MAX_ADAPTIVE_PROBES = 128;
	private static final int MAX_ADAPTIVE_PREFIX_LENGTH = 4;
	private static final char[] LETTERS =
		"abcdefghijklmnopqrstuvwxyz".toCharArray();
	private static final char[] PROBE_SUFFIXES =
		"abcdefghijklmnopqrstuvwxyz0123456789_-.".toCharArray();
	private static final Set<String> ESSENTIALS_COMMANDS =
		new HashSet<>(Arrays.asList("about", "ac", "action", "adventure",
			"adventuremode", "afk", "alts", "amsg", "antioch", "anvil", "away",
			"back", "backup", "bal", "balance", "balancetop", "baltop", "ban",
			"banip", "bc", "bcast", "bcastw", "bcw", "beecannon", "beezooka",
			"bigtree", "blocks", "book", "bottom", "break", "broadcast",
			"broadcastworld", "burn", "butcher", "call", "cartographytable",
			"carttable", "changems", "ci", "ck", "clean", "clear",
			"clearconfirm", "clearconfirmoff", "clearconfirmon", "clearinvent",
			"clearinventory", "clearinventoryconfirmoff",
			"clearinventoryconfirmtoggle", "compact", "compass", "condense",
			"coords", "craft", "createhome", "createjail", "createk",
			"createkit", "createwarp", "creative", "creativemode", "customtext",
			"day", "deletekit", "delhome", "delignore", "deljail", "delkit",
			"delwarp", "depth", "describe", "direction", "disposal", "dura",
			"durability", "eabout", "eac", "eaction", "eadventure",
			"eadventuremode", "eafk", "ealts", "eamsg", "eantioch", "eanvil",
			"eat", "eaway", "eback", "ebackup", "ebal", "ebalance",
			"ebalancetop", "ebaltop", "eban", "ebanip", "ebc", "ebcast",
			"ebcastw", "ebcw", "ebeecannon", "ebeezooka", "ebigtree", "eblocks",
			"ebook", "ebottom", "ebreak", "ebroadcast", "ebroadcastworld",
			"eburn", "ebutcher", "ec", "ecall", "ecartographytable",
			"ecarttable", "echangems", "echest", "echo", "eci", "eclean",
			"eclear", "eclearconfirm", "eclearconfirmoff", "eclearconfirmon",
			"eclearinvent", "eclearinventory", "eclearinventoryconfirmoff",
			"eclearinventoryconfirmtoggle", "eco", "ecompact", "ecompass",
			"econdense", "economy", "ecraft", "ecreatehome", "ecreatejail",
			"ecreatewarp", "ecreative", "ecreativemode", "eday", "edeletekit",
			"edelhome", "edelignore", "edeljail", "edelkit", "edelwarp",
			"edepth", "edescribe", "edirection", "edisposal", "editsign",
			"edura", "edurability", "eeat", "eec", "eechest", "eecho", "eeco",
			"eeconomy", "eecreative", "eeditsign", "eelixer", "eemail",
			"eenchant", "eenchantment", "eenderchest", "eendersee", "eentities",
			"eess", "eessentials", "eexp", "eext", "eextinguish", "efeed",
			"efireball", "efireentity", "efireskull", "efirework", "efix",
			"efly", "eflyspeed", "eformula", "efreeze", "efspeed", "egamemode",
			"egc", "egetloc", "egetlocation", "egetpos", "egive", "egm", "egma",
			"egmc", "egms", "egmsp", "egmt", "egod", "egodmode", "egrenade",
			"egrindstone", "ehat", "ehead", "eheal", "eheight", "ehelp",
			"ehelpop", "ehome", "ehomes", "ei", "eice", "eifo", "eignore",
			"eilore", "einame", "einfo", "einform", "einvsee", "eirename",
			"eitem", "eitemdb", "eitemlore", "eitemname", "eitemno",
			"eitemrename", "ej", "ejail", "ejailed", "ejailedplayers", "ejails",
			"ejp", "ejump", "ejumpto", "ekick", "ekickall", "ekill", "ekillall",
			"ekit", "ekitr", "ekitreset", "ekits", "ekittycannon", "elag",
			"elargetree", "elightning", "elist", "elixer", "eloom", "elore",
			"email", "eme", "emem", "ememo", "ememory", "emethod", "emob",
			"emobkill", "emobspawner", "emoney", "emore", "emotd", "emsg",
			"emsgtoggle", "emute", "enchant", "enchantment", "enderchest",
			"endersee", "enear", "enearby", "enews", "enick", "enickname",
			"enight", "entities", "enuke", "eonline", "epardon", "epardonip",
			"epay", "epayconfirm", "epayconfirmoff", "epayconfirmon",
			"epayconfirmtoggle", "epayoff", "epayon", "epaytoggle", "eping",
			"eplayerlist", "eplayerskull", "eplayertime", "eplayerweather",
			"eplaytime", "eplist", "epm", "epong", "eposition", "epotion",
			"epowertool", "epowertoollist", "epowertooltoggle", "eprice", "ept",
			"eptime", "eptlist", "eptt", "epttoggle", "epweather", "er",
			"erain", "erealname", "erecipe", "erecipes", "eremhome",
			"eremignore", "eremjail", "eremkit", "eremove", "eremwarp",
			"erenamehome", "erepair", "ereply", "ereplytoggle", "eresetkit",
			"erest", "ereturn", "ermhome", "ermignore", "ermjail", "ermkit",
			"ermwarp", "ertoggle", "erules", "eseen", "esell", "esethome",
			"esetjail", "esettpr", "esettprandom", "esetwarp", "esetworth",
			"eshock", "eshout", "eshoutworld", "esign", "esilence", "eskull",
			"esky", "esmite", "esmithingtable", "esmithtable", "esocialspy",
			"espawnentity", "espawner", "espawnmob", "espeed", "ess",
			"essentials", "essversion", "estonecutter", "estorm", "estrike",
			"esudo", "esuicide", "esun", "esurvival", "esurvivalmode", "etele",
			"eteleport", "etell", "etempban", "etempbanip", "etgm", "ethor",
			"ethunder", "etime", "etjail", "etnt", "etoblocks", "etogglejail",
			"etop", "etp", "etp2p", "etpa", "etpaall", "etpacancel",
			"etpaccept", "etpahere", "etpall", "etpask", "etpauto", "etpdeny",
			"etphere", "etpno", "etpo", "etpoffline", "etpohere", "etppos",
			"etpr", "etprandom", "etps", "etptoggle", "etpyes", "etrash",
			"etree", "eul", "eunban", "eunbanip", "eunignore", "eunjail",
			"eunl", "eunlimited", "eunmute", "euptime", "ev", "evanish",
			"ewalkspeed", "ewarp", "ewarpinfo", "ewarps", "ewb", "ewbench",
			"eweather", "ewhereami", "ewhisper", "ewho", "ewhois", "eworkbench",
			"eworld", "eworth", "ewspeed", "exp", "ext", "extinguish", "feed",
			"fireball", "fireentity", "fireskull", "firework", "fix", "fly",
			"flyspeed", "formula", "fspeed", "gamemode", "gc", "getloc",
			"getlocation", "getpos", "give", "gm", "gma", "gmc", "gms", "gmsp",
			"gmt", "god", "godmode", "grenade", "grindstone", "hat", "head",
			"heal", "height", "help", "helpop", "home", "homes", "i", "ice",
			"ifo", "ignore", "ilore", "iname", "info", "inform", "invsee",
			"irename", "item", "itemdb", "itemlore", "itemname", "itemno",
			"itemrename", "j", "jail", "jailedplayers", "jails", "jump",
			"jumpto", "kc", "kick", "kickall", "kill", "killall", "kit",
			"kitcreate", "kitpreview", "kitr", "kitreset", "kits", "kitshow",
			"kittycannon", "lag", "largetree", "lightning", "list", "loom",
			"lore", "m", "mail", "me", "mem", "memo", "memory", "method", "mob",
			"mobkill", "mobspawner", "money", "more", "motd", "msg",
			"msgtoggle", "mute", "near", "nearby", "news", "nick", "nickname",
			"night", "nuke", "offlinetp", "online", "otp", "pardon", "pardonip",
			"pay", "payconfirm", "payconfirmoff", "payconfirmon",
			"payconfirmtoggle", "payoff", "payon", "paytoggle", "ping",
			"playerlist", "playerskull", "playertime", "playerweather",
			"playtime", "plist", "pm", "pong", "position", "potion",
			"powertool", "powertoollist", "powertooltoggle", "preview", "price",
			"pt", "ptime", "ptlist", "ptt", "pttoggle", "pweather", "r", "rain",
			"realname", "recipe", "recipes", "remhome", "remignore", "remjail",
			"remkit", "remove", "remwarp", "renamehome", "repair", "reply",
			"replytoggle", "resetkit", "rest", "return", "rmhome", "rmignore",
			"rmjail", "rmkit", "rmwarp", "rtoggle", "rules", "s", "seen",
			"sell", "sethome", "setjail", "settpr", "settprandom", "setwarp",
			"setworth", "shock", "shout", "shoutworld", "showkit", "sign",
			"silence", "skull", "sky", "smite", "smithingtable", "smithtable",
			"socialspy", "sp", "spawnentity", "spawner", "spawnmob", "spec",
			"spectator", "speed", "stonecutter", "storm", "strike", "sudo",
			"suicide", "sun", "survival", "survivalmode", "t", "tele",
			"teleport", "tell", "tempban", "tempbanip", "tgm", "thor",
			"thunder", "time", "tjail", "tnt", "toblocks", "togglejail", "top",
			"tp", "tp2p", "tpa", "tpaall", "tpacancel", "tpaccept", "tpahere",
			"tpall", "tpask", "tpauto", "tpdeny", "tphere", "tpno", "tpo",
			"tpoff", "tpoffline", "tpohere", "tppos", "tpr", "tprandom", "tps",
			"tptoggle", "tpyes", "trash", "tree", "ul", "unban", "unbanip",
			"unignore", "unjail", "unl", "unlimited", "unmute", "uptime", "v",
			"vanish", "w", "walkspeed", "warp", "warpinfo", "warps", "wb",
			"wbench", "weather", "whereami", "whisper", "who", "whois",
			"workbench", "world", "worth", "wspeed", "xp"));
	
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
	private static final Set<String> knownCommands =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private static boolean scanCompleted;
	private static final Set<String> triggerValues =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private static final Set<Character> truncatedCommandLetters =
		new HashSet<>();
	private static final Set<String> scheduledProbePrefixes = new HashSet<>();
	private static final ArrayDeque<String> commandProbeQueue =
		new ArrayDeque<>();
	private static final ArrayDeque<String> commandsToExecute =
		new ArrayDeque<>();
	
	private static boolean awaitingResponse;
	private static boolean triggerProbePending;
	private static int waitTicks;
	private static int cooldownTicks;
	private static final java.util.concurrent.atomic.AtomicInteger PROBE_IDS =
		new java.util.concurrent.atomic.AtomicInteger(2_000_000);
	private static long nextProbeAtNanos;
	private static int awaitingRequestId;
	private static int probesSent;
	private static boolean active;
	private static Phase phase = Phase.IDLE;
	private static ScanMode activeMode = ScanMode.PACKET_PROBING;
	private static String lastStatus = "Idle.";
	private static final List<String> recentEvents = new ArrayList<>();
	private static List<String> lastFoundCommands = List.of();
	private static String boundServerKey = "";
	private static final List<String> manualCommandOutput = new ArrayList<>();
	private static int manualOutputCaptureTicks;
	private static final Set<String> unavailableCommands = new HashSet<>();
	private static final Set<String> pendingManualCommands = new HashSet<>();
	private static final Set<String> permissionDeniedPaths = new HashSet<>();
	private static String awaitingProbe = "";
	private static final java.util.Map<String, Integer> probeAttempts =
		new java.util.HashMap<>();
	private static final Set<String> unansweredPrefixes = new TreeSet<>();
	private static final Set<String> cappedPrefixes = new TreeSet<>();
	private static int probeTimeouts;
	
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
		scanCompleted = false;
		knownCommands.clear();
		var dispatcher = UiUtilsServerCommands.dispatcher(mc.player.connection);
		if(dispatcher != null)
			for(var child : dispatcher.getRoot().getChildren())
				knownCommands.add(child.getName());
		scannedCommands.clear();
		triggerValues.clear();
		truncatedCommandLetters.clear();
		scheduledProbePrefixes.clear();
		probeAttempts.clear();
		unansweredPrefixes.clear();
		cappedPrefixes.clear();
		probeTimeouts = 0;
		commandProbeQueue.clear();
		commandsToExecute.clear();
		unavailableCommands.clear();
		pendingManualCommands.clear();
		permissionDeniedPaths.clear();
		awaitingResponse = false;
		triggerProbePending = false;
		waitTicks = 0;
		cooldownTicks = 0;
		nextProbeAtNanos = 0;
		awaitingRequestId = -1;
		probesSent = 0;
		awaitingProbe = "";
		phase = Phase.SCANNING;
		activeMode = getScanMode();
		lastStatus = "Scanning commands (" + activeMode.name() + ")...";
		recentEvents.clear();
		lastFoundCommands = List.of();
		boundServerKey = currentServerKey(mc);
		UiUtilsSuggestionCapability.bindTo(boundServerKey);
		if(activeMode == ScanMode.CLIENT_SIDE_ENUMERATION)
		{
			runClientSideEnumerationScan();
			return "[UI-Utils] Command scanner started (CLIENT_SIDE_ENUMERATION).";
		}
		
		if(UiUtilsSuggestionCapability.isSuppressed())
		{
			abortForSuppressedSuggestions();
			return "[UI-Utils] Active probing unavailable; using synced server tree.";
		}
		for(char letter : LETTERS)
			queueProbe(String.valueOf(letter), false);
		sendNextRequest();
		return "[UI-Utils] Command scanner started (PACKET_PROBING).";
	}
	
	public static void onTick()
	{
		if(manualOutputCaptureTicks > 0)
			manualOutputCaptureTicks--;
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
		
		if(UiUtilsSuggestionCapability.isSuppressed())
		{
			abortForSuppressedSuggestions();
			return;
		}
		if(awaitingResponse)
		{
			waitTicks++;
			if(waitTicks >= RESPONSE_TIMEOUT_TICKS)
			{
				probeTimeouts++;
				String prefix =
					triggerProbePending ? "trigger " : awaitingProbe;
				boolean trigger = triggerProbePending;
				awaitingResponse = false;
				awaitingRequestId = -1;
				triggerProbePending = false;
				awaitingProbe = "";
				if(UiUtilsSuggestionCapability.recordUnansweredProbe())
				{
					unansweredPrefixes.add(prefix);
					abortForSuppressedSuggestions();
					return;
				}
				if(probeAttempts.getOrDefault(prefix, 0) < MAX_PROBE_ATTEMPTS)
				{
					if(!trigger)
						commandProbeQueue.addLast(prefix);
					print("Probe timeout: /" + prefix + "; queued retry.");
				}else
				{
					unansweredPrefixes.add(prefix);
					print("Probe unanswered after " + MAX_PROBE_ATTEMPTS
						+ " attempts: /" + prefix);
					if(trigger)
					{
						finishScan();
						return;
					}
				}
				
				nextProbeAtNanos =
					System.nanoTime() + UiUtilsSettings.getProbeIntervalNanos();
			}
			return;
		}
		
		if(System.nanoTime() < nextProbeAtNanos)
			return;
		
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
		UiUtilsSuggestionCapability.recordResponse();
		
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
		String probe = awaitingProbe;
		if(count >= MAX_SUGGESTIONS_PER_RESPONSE)
		{
			if(!probe.isEmpty())
				truncatedCommandLetters.add(probe.charAt(0));
			queueAdaptiveProbes(probe);
		}
		if(UiUtilsSettings.get().commandScannerDebugProbe)
			print("Probe response: /" + probe + " (id=" + awaitingRequestId
				+ ", suggestions=" + count + ")");
		
		readSuggestions(suggestions);
		awaitingResponse = false;
		awaitingRequestId = -1;
		awaitingProbe = "";
		nextProbeAtNanos =
			System.nanoTime() + UiUtilsSettings.getProbeIntervalNanos();
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
		manualOutputCaptureTicks = 200; // Ten seconds of post-send chat/system
										// output.
		String[] parts = raw.split(",");
		int sent = 0;
		for(String part : parts)
		{
			String cmd = part.trim();
			if(cmd.isEmpty())
				continue;
			if(cmd.startsWith("/"))
				cmd = cmd.substring(1);
			pendingManualCommands.add(normalizeCommandPath(cmd));
			manualCommandOutput.add("> /" + cmd);
			mc.player.connection.sendCommand(cmd);
			sent++;
		}
		if(sent == 0)
			manualCommandOutput.add("No command was sent.");
		return "[UI-Utils] Sent " + sent + " packet command(s).";
	}
	
	public static void onChatMessage(String message)
	{
		if(manualOutputCaptureTicks <= 0 || message == null
			|| message.isBlank())
			return;
		if(manualCommandOutput.size() >= 40)
			manualCommandOutput.remove(0);
		String trimmed = message.trim();
		manualCommandOutput.add(trimmed);
		if(isUnavailableResponse(trimmed))
			unavailableCommands.addAll(pendingManualCommands);
	}
	
	public static List<String> getManualCommandOutputSnapshot()
	{
		return List.copyOf(manualCommandOutput);
	}
	
	public static boolean isCommandHiddenToUser(String command)
	{
		updateCommandVisibility(command);
		return permissionDeniedPaths
			.contains(normalizeFullCommandPath(command));
	}
	
	private static String normalizeFullCommandPath(String command)
	{
		if(command == null)
			return "";
		String normalized = command.trim().toLowerCase(Locale.ROOT);
		return normalized.startsWith("/") ? normalized.substring(1)
			: normalized;
	}
	
	public static boolean isCommandUnavailable(String command)
	{
		return unavailableCommands.contains(normalizeCommandPath(command));
	}
	
	private static String normalizeCommandPath(String command)
	{
		if(command == null)
			return "";
		String normalized = command.trim().toLowerCase(Locale.ROOT);
		if(normalized.startsWith("/"))
			normalized = normalized.substring(1);
		int argument = normalized.indexOf(' ');
		return argument < 0 ? normalized : normalized.substring(0, argument);
	}
	
	private static boolean isUnavailableResponse(String message)
	{
		String lower = message.toLowerCase(Locale.ROOT);
		return lower.contains("unknown command")
			|| lower.contains("incomplete command")
			|| lower.contains("do not have permission")
			|| lower.contains("don't have permission")
			|| lower.contains("no permission")
			|| lower.contains("not permitted") || lower.contains("not allowed")
			|| lower.contains("not authorized");
	}
	
	public static void clearManualCommandOutput()
	{
		pendingManualCommands.clear();
		manualCommandOutput.clear();
		manualOutputCaptureTicks = 0;
	}
	
	private static void sendNextRequest()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || mc.player.connection == null)
		{
			finish();
			return;
		}
		
		String probe = commandProbeQueue.pollFirst();
		if(probe == null)
		{
			requestTriggerValues();
			return;
		}
		
		String input = "/" + probe;
		int id = PROBE_IDS.getAndIncrement();
		probesSent++;
		awaitingProbe = probe;
		probeAttempts.merge(probe, 1, Integer::sum);
		if(UiUtilsSettings.get().commandScannerDebugProbe)
			print("Probe sent: " + input + " (id=" + id + ")");
		
		mc.player.connection
			.send(new ServerboundCommandSuggestionPacket(id, input));
		awaitingResponse = true;
		awaitingRequestId = id;
		waitTicks = 0;
		nextProbeAtNanos =
			System.nanoTime() + UiUtilsSettings.getProbeIntervalNanos();
	}
	
	private static void requestTriggerValues()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || mc.player.connection == null)
		{
			finishScan();
			return;
		}
		probeAttempts.merge("trigger ", 1, Integer::sum);
		triggerProbePending = true;
		awaitingProbe = "";
		int id = PROBE_IDS.getAndIncrement();
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
			if(command != null)
			{
				scannedCommands.add(command);
				updateCommandVisibility(command);
				if(UiUtilsSettings.get().commandScannerDebugProbe)
					UiUtils.LOGGER.info("CommandScanner /{}: {} retained ({})",
						awaitingProbe, command, knownCommands.contains(command)
							? "already known" : "newly discovered");
			}else if(UiUtilsSettings.get().commandScannerDebugProbe)
			{
				UiUtils.LOGGER.info(
					"CommandScanner /{}: rejected suggestion {} (empty root)",
					awaitingProbe, suggestion.getText());
			}
		}
	}
	
	private static void updateCommandVisibility(String command)
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null || mc.player == null || mc.player.connection == null)
			return;
			
		// Packet mode only classifies commands the server returned in
		// suggestions.
		// Client mode enumerates the separate server-synchronized command tree.
		var dispatcher = UiUtilsServerCommands.dispatcher(mc.player.connection);
		if(dispatcher == null || dispatcher.getRoot() == null)
		{
			permissionDeniedPaths.add(normalizeFullCommandPath(command));
			return;
		}
		CommandNode<ClientSuggestionProvider> node =
			dispatcher.getRoot().getChild(normalizeCommandPath(command));
		if(node == null
			|| !node.canUse(mc.player.connection.getSuggestionsProvider()))
			permissionDeniedPaths.add(normalizeFullCommandPath(command));
		else
			permissionDeniedPaths.remove(normalizeFullCommandPath(command));
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
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || mc.player.connection == null)
		{
			print("Could not read the server-synchronized command tree.");
			finishScan();
			return;
		}
		
		var dispatcher = UiUtilsServerCommands.dispatcher(mc.player.connection);
		RootCommandNode<ClientSuggestionProvider> root =
			dispatcher == null ? null : dispatcher.getRoot();
		if(root == null)
		{
			print("The server has not supplied a command tree yet.");
			finishScan();
			return;
		}
		
		Set<CommandNode<ClientSuggestionProvider>> visiting =
			Collections.newSetFromMap(new IdentityHashMap<>());
		ClientSuggestionProvider source =
			mc.player.connection.getSuggestionsProvider();
		for(CommandNode<ClientSuggestionProvider> child : root.getChildren())
			collectClientCommandPaths(child, "", source, visiting);
		print("Read " + scannedCommands.size()
			+ " command path(s) from the synced server tree without sending probes.");
		finishScan();
	}
	
	private static void collectClientCommandPaths(
		CommandNode<ClientSuggestionProvider> node, String parentPath,
		ClientSuggestionProvider source,
		Set<CommandNode<ClientSuggestionProvider>> visiting)
	{
		if(node == null || !node.canUse(source) || !visiting.add(node))
			return;
		
		String name = node instanceof ArgumentCommandNode<?, ?>
			? "<" + node.getName() + ">" : node.getName();
		String path = parentPath.isEmpty() ? name : parentPath + " " + name;
		String root = path;
		int firstSpace = root.indexOf(' ');
		if(firstSpace >= 0)
			root = root.substring(0, firstSpace);
		if(!root.isBlank())
			scannedCommands.add(path);
		
		for(CommandNode<ClientSuggestionProvider> child : node.getChildren())
			collectClientCommandPaths(child, path, source, visiting);
		// Redirect nodes are aliases. Expand their target's children beneath
		// the
		// alias path, while the identity-based visiting set prevents redirect
		// cycles.
		if(node.getRedirect() != null)
			collectClientRedirectChildren(node.getRedirect(), path, source,
				visiting);
		visiting.remove(node);
	}
	
	private static void collectClientRedirectChildren(
		CommandNode<ClientSuggestionProvider> target, String aliasPath,
		ClientSuggestionProvider source,
		Set<CommandNode<ClientSuggestionProvider>> visiting)
	{
		if(target == null || !target.canUse(source) || !visiting.add(target))
			return;
		for(CommandNode<ClientSuggestionProvider> child : target.getChildren())
			collectClientCommandPaths(child, aliasPath, source, visiting);
		if(target.getRedirect() != null)
			collectClientRedirectChildren(target.getRedirect(), aliasPath,
				source, visiting);
		visiting.remove(target);
	}
	
	private static void finishScan()
	{
		scanCompleted = true;
		List<String> results = new ArrayList<>(scannedCommands);
		for(String value : triggerValues)
			results.add("trigger (" + value + ")");
		print("Command scanner found " + results.size() + " commands.");
		lastFoundCommands = results;
		lastStatus = "Found " + results.size() + " commands ("
			+ getKnownCommandsSnapshot().size() + " already known, "
			+ getNewCommandsSnapshot().size() + " newly discovered).";
		UiUtilsScanHistory.recordCommands(boundServerKey,
			"command_" + activeMode.name().toLowerCase(Locale.ROOT),
			lastFoundCommands, getDiagnosticsSnapshot());
		if(!unansweredPrefixes.isEmpty() || !cappedPrefixes.isEmpty())
			lastStatus += " Partial: unanswered=" + unansweredPrefixes.size()
				+ ", capped=" + cappedPrefixes.size() + ".";
		if(results.isEmpty())
		{
			finish();
			return;
		}
		
		if(activeMode == ScanMode.PACKET_PROBING
			&& !UiUtilsSuggestionCapability.isSuppressed()
			&& UiUtilsSettings.get().commandScannerRunFoundCommands)
		{
			commandsToExecute.clear();
			unavailableCommands.clear();
			pendingManualCommands.clear();
			Set<String> denyTerms = parseDenyTerms(
				UiUtilsSettings.get().commandScannerDontSendFilter);
			for(String cmd : scannedCommands)
			{
				String lower = cmd.toLowerCase(Locale.ROOT);
				// Discovery is complete; automatic execution retains its
				// conservative exclusions.
				if(isVanillaOrDefaultCommand(lower)
					|| isIgnoredEssentialsCommand(lower))
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
	
	public static List<String> filterVisibleCommands(List<String> commands,
		boolean showVanilla)
	{
		return commands.stream().filter(
			command -> !UiUtilsSettings.get().commandScannerExcludeEssentials
				|| !isIgnoredEssentialsCommand(normalizeCommandPath(command)))
			.filter(command -> showVanilla
				|| !isVanillaOrDefaultCommand(normalizeCommandPath(command)))
			.toList();
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
		awaitingProbe = "";
	}
	
	private static void queueProbe(String rawPrefix, boolean prioritize)
	{
		if(rawPrefix == null || rawPrefix.isBlank())
			return;
		String prefix = rawPrefix.trim().toLowerCase(Locale.ROOT);
		if(prefix.length() > MAX_ADAPTIVE_PREFIX_LENGTH
			|| !scheduledProbePrefixes.add(prefix))
			return;
		if(scheduledProbePrefixes.size() > MAX_ADAPTIVE_PROBES)
		{
			scheduledProbePrefixes.remove(prefix);
			cappedPrefixes.add(prefix);
			return;
		}
		if(prioritize)
			commandProbeQueue.addFirst(prefix);
		else
			commandProbeQueue.addLast(prefix);
	}
	
	private static void queueAdaptiveProbes(String prefix)
	{
		if(prefix == null || prefix.isEmpty()
			|| prefix.length() >= MAX_ADAPTIVE_PREFIX_LENGTH)
			return;
		for(char suffix : PROBE_SUFFIXES)
			queueProbe(prefix + suffix, false);
	}
	
	private static boolean isIgnoredEssentialsCommand(String value)
	{
		if(value == null)
			return false;
		String command = value.trim().toLowerCase(Locale.ROOT);
		if(command.startsWith("/"))
			command = command.substring(1);
		int colon = command.indexOf(':');
		if(colon > 0 && (command.startsWith("essentials:")
			|| command.startsWith("essentialsx:")))
			return true;
		return ESSENTIALS_COMMANDS.contains(command);
	}
	
	public static void resetForServerChange()
	{
		scanCompleted = false;
		knownCommands.clear();
		active = false;
		awaitingResponse = false;
		awaitingRequestId = -1;
		phase = Phase.IDLE;
		cooldownTicks = 0;
		waitTicks = 0;
		nextProbeAtNanos = 0;
		scannedCommands.clear();
		triggerValues.clear();
		truncatedCommandLetters.clear();
		scheduledProbePrefixes.clear();
		probeAttempts.clear();
		unansweredPrefixes.clear();
		cappedPrefixes.clear();
		probeTimeouts = 0;
		commandProbeQueue.clear();
		commandsToExecute.clear();
		unavailableCommands.clear();
		pendingManualCommands.clear();
		permissionDeniedPaths.clear();
		lastFoundCommands = List.of();
		recentEvents.clear();
		lastStatus = "Cleared due to server change.";
		probesSent = 0;
		awaitingProbe = "";
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
			return lastStatus + " [probes " + probesSent + ", queued "
				+ commandProbeQueue.size() + "]";
		if(active && phase == Phase.EXECUTING)
			return lastStatus + " [remaining " + commandsToExecute.size() + "]";
		if(!UiUtilsPluginScanner.getObservedCommandsSnapshot().isEmpty()
			&& lastFoundCommands.isEmpty())
			return "Commands collected during plugin scan.";
		return lastStatus;
	}
	
	public static boolean hasResultsForCurrentServer()
	{
		return scanCompleted
			&& boundServerKey.equals(currentServerKey(Minecraft.getInstance()));
	}
	
	private static boolean isKnownRoot(String command)
	{
		refreshKnownCommandsFromServer();
		String root = normalizeCommandPath(command).split(" ", 2)[0];
		var connection = Minecraft.getInstance().getConnection();
		return knownCommands.contains(root) || (connection != null
			&& UiUtilsServerCommands.dispatcher(connection) != null
			&& UiUtilsServerCommands.dispatcher(connection).getRoot()
				.getChild(root) != null);
	}
	
	public static List<String> getKnownCommandsSnapshot()
	{
		return getFoundCommandsSnapshot().stream()
			.filter(UiUtilsCommandScanner::isKnownRoot).toList();
	}
	
	public static List<String> getNewCommandsSnapshot()
	{
		return getFoundCommandsSnapshot().stream()
			.filter(cmd -> !isKnownRoot(cmd)).toList();
	}
	
	public static void stopScan()
	{
		if(!active)
			return;
		finish();
		commandProbeQueue.clear();
		commandsToExecute.clear();
		pendingManualCommands.clear();
		triggerProbePending = false;
		scanCompleted = true;
		lastFoundCommands = new ArrayList<>(scannedCommands);
		for(String value : triggerValues)
			lastFoundCommands.add("trigger (" + value + ")");
		lastStatus = "Command scan stopped; partial results retained.";
		print(lastStatus);
	}
	
	public static boolean isActive()
	{
		return active;
	}
	
	public static List<String> getFoundCommandsSnapshot()
	{
		Set<String> commands =
			new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		commands.addAll(lastFoundCommands);
		if(active && phase == Phase.SCANNING)
			commands.addAll(scannedCommands);
		commands.addAll(UiUtilsPluginScanner.getObservedCommandsSnapshot());
		return List.copyOf(commands);
	}
	
	public static List<String> getRecentEventsSnapshot()
	{
		return new ArrayList<>(recentEvents);
	}
	
	public static java.util.Map<String, Object> getDiagnosticsSnapshot()
	{
		return java.util.Map.of("probesSent", probesSent, "probeTimeouts",
			probeTimeouts, "probeAttempts",
			new java.util.TreeMap<>(probeAttempts), "unansweredPrefixes",
			List.copyOf(unansweredPrefixes), "cappedPrefixes",
			List.copyOf(cappedPrefixes), "truncatedLetters",
			List.copyOf(truncatedCommandLetters), "probeResponseTimeoutTicks",
			RESPONSE_TIMEOUT_TICKS, "maxProbeAttempts", MAX_PROBE_ATTEMPTS,
			"probesPerSecond", UiUtilsSettings.getProbesPerSecond());
	}
	
	public static boolean hasTruncatedResponses()
	{
		return !truncatedCommandLetters.isEmpty()
			|| !unansweredPrefixes.isEmpty() || !cappedPrefixes.isEmpty();
	}
	
	public static boolean wasResponseTruncated(char letter)
	{
		return truncatedCommandLetters.contains(Character.toLowerCase(letter));
	}
	
	public static void clearResultsForUi()
	{
		UiUtilsSuggestionCapability.clear();
		scanCompleted = false;
		knownCommands.clear();
		active = false;
		awaitingResponse = false;
		awaitingRequestId = -1;
		phase = Phase.IDLE;
		scannedCommands.clear();
		triggerValues.clear();
		truncatedCommandLetters.clear();
		scheduledProbePrefixes.clear();
		probeAttempts.clear();
		unansweredPrefixes.clear();
		cappedPrefixes.clear();
		probeTimeouts = 0;
		commandProbeQueue.clear();
		commandsToExecute.clear();
		unavailableCommands.clear();
		pendingManualCommands.clear();
		lastFoundCommands = List.of();
		recentEvents.clear();
		lastStatus = "Cleared.";
		nextProbeAtNanos = 0;
		cooldownTicks = 0;
		waitTicks = 0;
		probesSent = 0;
		awaitingProbe = "";
	}
	
	private static void abortForSuppressedSuggestions()
	{
		awaitingResponse = false;
		awaitingRequestId = -1;
		triggerProbePending = false;
		commandProbeQueue.clear();
		print(
			"Suggestion probing unavailable; reading the synced server tree.");
		runClientSideEnumerationScan();
	}
	
	public static void onSystemChat(Component message)
	{
		if(message != null)
			onChatMessage(message.getString());
	}
	
	public static void refreshKnownCommandsFromServer()
	{
		var connection = Minecraft.getInstance().getConnection();
		var dispatcher = connection == null ? null
			: UiUtilsServerCommands.dispatcher(connection);
		if(dispatcher != null)
			for(var child : dispatcher.getRoot().getChildren())
				knownCommands.add(child.getName());
	}
	
	public static boolean isClientVisibleCommand(String raw)
	{
		return !isCommandHiddenToUser(raw);
	}
	
	public static boolean hasTruncatedResults()
	{
		return hasTruncatedResponses();
	}
	
	public static boolean isLetterGroupTruncated(char letter)
	{
		return wasResponseTruncated(letter);
	}
	
	public static List<String> getKnownFoundCommandsSnapshot(
		boolean showVanilla)
	{
		return filterVisibleCommands(getKnownCommandsSnapshot(), showVanilla);
	}
	
	public static List<String> getNewFoundCommandsSnapshot(boolean showVanilla)
	{
		return filterVisibleCommands(getNewCommandsSnapshot(), showVanilla);
	}
	
	public static List<String> getVisibleFoundCommandsSnapshot(String filter,
		boolean showVanilla)
	{
		return "KNOWN".equalsIgnoreCase(filter)
			? getKnownFoundCommandsSnapshot(showVanilla)
			: "NEW".equalsIgnoreCase(filter)
				? getNewFoundCommandsSnapshot(showVanilla)
				: filterVisibleCommands(getFoundCommandsSnapshot(),
					showVanilla);
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
