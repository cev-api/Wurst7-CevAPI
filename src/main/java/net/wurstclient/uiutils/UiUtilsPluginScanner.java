/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

public final class UiUtilsPluginScanner
{
	private static final Set<String> ANTICHEAT_WORDS = Set.of("nocheatplus",
		"negativity", "vulcan", "spartan", "matrix", "grim", "themis", "kauri",
		"godseye", "anticheat", "exploit", "illegal");
	private static final Set<String> VANILLA_NAMESPACES =
		Set.of("minecraft", "brigadier", "bukkit", "spigot", "paper", "purpur",
			"velocity", "bungeecord", "waterfall");
	private static final String[] COMMON_PLUGIN_NAMESPACES = {"essentials",
		"essentialsx", "worldedit", "worldguard", "luckperms", "vault",
		"citizens", "cmi", "cmilib", "multiverse-core", "multiverse",
		"viaversion", "viabackwards", "viarewind", "geysermc", "geyser",
		"floodgate", "protocollib", "coreprotect", "griefprevention",
		"shopkeepers", "dynmap", "placeholderapi", "skinsrestorer", "skript",
		"advancedanticheat", "vulcan", "grimac", "matrix", "spartan", "aac",
		"karhu", "verus", "nocheatplus", "authme", "deluxemenus", "plotsquared",
		"supervanish", "packetevents", "oraxen", "itemsadder"};
	private static final Map<String, String> ROOT_COMMAND_PLUGIN_ALIASES = Map
		.ofEntries(Map.entry("lp", "luckperms"), Map.entry("we", "worldedit"),
			Map.entry("rg", "worldguard"), Map.entry("mv", "multiverse-core"),
			Map.entry("npc", "citizens"), Map.entry("papi", "placeholderapi"),
			Map.entry("cmi", "cmi"), Map.entry("co", "coreprotect"),
			Map.entry("grim", "grimac"), Map.entry("geyser", "geysermc"),
			Map.entry("floodgate", "floodgate"),
			Map.entry("viaver", "viaversion"), Map.entry("sr", "skinsrestorer"),
			Map.entry("authme", "authme"), Map.entry("dm", "deluxemenus"),
			Map.entry("plots", "plotsquared"), Map.entry("sv", "supervanish"));
	
	private static final int DEFAULT_PROBE_DELAY_TICKS = 1;
	private static final int RESPONSE_TIMEOUT_TICKS = 30;
	private static final int HARD_TIMEOUT_TICKS = 20 * 15;
	
	private static boolean scanning;
	private static int ticksSinceStart;
	private static int ticksSinceLastResponse;
	private static int ticksUntilNextProbe;
	private static int completionAnnounceCooldown;
	
	private static final Set<Integer> pendingProbeIds = new HashSet<>();
	private static final Map<Integer, PluginProbeSpec> probesById =
		new HashMap<>();
	private static final Deque<PluginProbeRequest> queuedProbes =
		new ArrayDeque<>();
	private static final Set<String> observedPluginCommands =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private static final Map<String, PluginScanEntry> entries =
		new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private static String lastStatus = "Idle.";
	private static final List<PluginResultRow> lastRows = new ArrayList<>();
	private static final List<String> recentEvents = new ArrayList<>();
	private static String boundServerKey = "";
	
	private UiUtilsPluginScanner()
	{}
	
	public static void init()
	{
		// packet callbacks come from mixin
	}
	
	public static String startScan()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.getConnection() == null || mc.player == null)
			return "[UI-Utils] Not connected.";
		if(scanning)
			return "[UI-Utils] Plugin scan already in progress.";
		
		resetState();
		scanning = true;
		lastStatus = "Scanning plugins...";
		boundServerKey = currentServerKey(mc);
		parseCommandTree(mc.player.connection);
		mergePassiveKnownPacks();
		queueProbeBatch(buildPluginProbes());
		print("Plugin scan started. Probes: " + queuedProbes.size() + ".");
		sendNextProbeIfReady(mc.player.connection);
		return "[UI-Utils] Scanning plugins...";
	}
	
	public static void onTick()
	{
		Minecraft mc = Minecraft.getInstance();
		String currentServer = currentServerKey(mc);
		if(!boundServerKey.isEmpty() && !currentServer.equals(boundServerKey))
		{
			resetForServerChange();
			return;
		}
		
		if(!scanning)
			return;
		
		if(mc.player == null || mc.getConnection() == null)
		{
			finishAndPrint();
			return;
		}
		
		ticksSinceStart++;
		ticksSinceLastResponse++;
		
		if(ticksSinceStart >= HARD_TIMEOUT_TICKS)
		{
			print("Plugin scan timed out; finalizing with current evidence.");
			lastStatus = "Plugin scan timed out.";
			finishAndPrint();
			return;
		}
		
		if(ticksUntilNextProbe > 0)
			ticksUntilNextProbe--;
		
		sendNextProbeIfReady(mc.player.connection);
		
		if(queuedProbes.isEmpty() && pendingProbeIds.isEmpty())
		{
			if(++completionAnnounceCooldown >= 4)
				finishAndPrint();
			return;
		}
		
		if(queuedProbes.isEmpty()
			&& ticksSinceLastResponse >= RESPONSE_TIMEOUT_TICKS)
		{
			print("Plugin scan response wait timeout; finalizing.");
			lastStatus = "Plugin scan response timeout.";
			finishAndPrint();
		}
	}
	
	public static void onSuggestionsPacket(
		ClientboundCommandSuggestionsPacket packet)
	{
		if(!scanning)
			return;
		if(!pendingProbeIds.contains(packet.id()))
			return;
		
		PluginProbeSpec probe = probesById.get(packet.id());
		pendingProbeIds.remove(packet.id());
		ticksSinceLastResponse = 0;
		
		List<ClientboundCommandSuggestionsPacket.Entry> list =
			packet.suggestions();
		for(ClientboundCommandSuggestionsPacket.Entry s : list)
		{
			String text = s.text();
			String normalizedToken = normalizeCommandToken(text);
			if(!normalizedToken.isEmpty())
				addObservedPluginCommand(normalizedToken);
			
			if(text != null && text.contains(":"))
			{
				String[] parts = text.split(":", 2);
				String ns = parts[0].toLowerCase(Locale.ROOT);
				String cmd = parts[1];
				if(!VANILLA_NAMESPACES.contains(ns))
				{
					mergePluginEntry(ns, List.of(cmd), true,
						PluginEvidence.COMMAND_TREE);
					addObservedPluginCommand(cmd);
				}
			}
			
			if(probe == null || text == null)
				continue;
			if(probe.kind == PluginProbeKind.NAMESPACE && probe.hint != null
				&& !normalizedToken.isEmpty())
			{
				mergePluginEntry(probe.hint, List.of(normalizedToken), true,
					PluginEvidence.NAMESPACE);
				continue;
			}
			if(probe.kind != PluginProbeKind.PLUGIN_LIST
				&& probe.kind != PluginProbeKind.VERSION)
				continue;
			
			String pluginCandidate = text.trim();
			String pluginKey = normalizePluginKey(pluginCandidate);
			if(!isLikelyPluginNameCandidate(pluginCandidate, probe.kind)
				|| UiUtilsCommandScanner.isVanillaOrDefaultCommand(
					pluginCandidate)
				|| isDefaultFabricPlugin(pluginCandidate)
				|| ROOT_COMMAND_PLUGIN_ALIASES.containsKey(pluginKey))
				continue;
			
			mergePluginEntry(pluginCandidate, List.of(), false,
				probe.kind == PluginProbeKind.PLUGIN_LIST
					? PluginEvidence.PLUGIN_LIST : PluginEvidence.VERSION_HINT);
		}
		
		inferPluginsFromObservedCommands();
	}
	
	private static void resetState()
	{
		scanning = false;
		ticksSinceStart = 0;
		ticksSinceLastResponse = 0;
		ticksUntilNextProbe = 0;
		completionAnnounceCooldown = 0;
		pendingProbeIds.clear();
		probesById.clear();
		queuedProbes.clear();
		observedPluginCommands.clear();
		entries.clear();
		lastRows.clear();
		recentEvents.clear();
		boundServerKey = "";
	}
	
	private static void parseCommandTree(ClientPacketListener connection)
	{
		CommandDispatcher<ClientSuggestionProvider> dispatcher =
			connection.getCommands();
		if(dispatcher == null)
			return;
		RootCommandNode<ClientSuggestionProvider> root = dispatcher.getRoot();
		if(root == null)
			return;
		
		for(CommandNode<ClientSuggestionProvider> child : root.getChildren())
		{
			String name = child.getName();
			if(name == null || name.isBlank())
				continue;
			if(name.contains(":"))
			{
				String[] parts = name.split(":", 2);
				String ns = parts[0].toLowerCase(Locale.ROOT);
				String cmd = parts[1];
				if(VANILLA_NAMESPACES.contains(ns))
					continue;
				mergePluginEntry(ns, List.of(cmd), true,
					PluginEvidence.COMMAND_TREE);
				addObservedPluginCommand(cmd);
			}else
			{
				addObservedPluginCommand(name);
			}
		}
		inferPluginsFromObservedCommands();
	}
	
	// ### ADDED ### Configuration-phase Known Packs outlive an active scan
	// reset.
	private static void mergePassiveKnownPacks()
	{
		for(UiUtilsServerFingerprintCollector.KnownPackInfo pack : UiUtilsServerFingerprintCollector
			.snapshot().knownPacks())
		{
			if("minecraft".equalsIgnoreCase(pack.namespace())
				&& "core".equalsIgnoreCase(pack.id()))
				continue;
			String product =
				UiUtilsServerFingerprintCollector.friendlyName(pack.id());
			String display = pack.version() == null || pack.version().isBlank()
				? product : product + " " + pack.version();
			mergePluginEntry(display, List.of(), false,
				PluginEvidence.KNOWN_PACK);
		}
	}
	
	private static void queueProbeBatch(Map<Integer, PluginProbeSpec> probes)
	{
		for(Map.Entry<Integer, PluginProbeSpec> entry : probes.entrySet())
		{
			pendingProbeIds.add(entry.getKey());
			probesById.put(entry.getKey(), entry.getValue());
			queuedProbes.addLast(
				new PluginProbeRequest(entry.getKey(), entry.getValue()));
		}
	}
	
	private static Map<Integer, PluginProbeSpec> buildPluginProbes()
	{
		Map<Integer, PluginProbeSpec> probes = new LinkedHashMap<>();
		int nextId = 1337;
		probes.put(nextId++,
			new PluginProbeSpec("/", PluginProbeKind.ROOT, null));
		probes.put(nextId++,
			new PluginProbeSpec("/ ", PluginProbeKind.ROOT, null));
		nextId = addProbeVariants(probes, nextId, PluginProbeKind.PLUGIN_LIST,
			"/plugins", "/pl", "/bukkit:plugins", "/bukkit:pl");
		nextId = addProbeVariants(probes, nextId, PluginProbeKind.VERSION,
			"/ver", "/version", "/about", "/icanhasbukkit", "/bukkit:ver",
			"/bukkit:version");
		nextId = addProbeVariants(probes, nextId, PluginProbeKind.HELP, "/help",
			"/?", "/bukkit:help", "/minecraft:help");
		
		String roots = "abcdefghijklmnopqrstuvwxyz0123456789";
		for(int i = 0; i < roots.length(); i++)
		{
			char prefix = roots.charAt(i);
			probes.put(nextId++, new PluginProbeSpec("/" + prefix,
				PluginProbeKind.ROOT, String.valueOf(prefix)));
			probes.put(nextId++, new PluginProbeSpec("/help " + prefix,
				PluginProbeKind.HELP, String.valueOf(prefix)));
			probes.put(nextId++, new PluginProbeSpec("/? " + prefix,
				PluginProbeKind.HELP, String.valueOf(prefix)));
		}
		
		for(String namespace : COMMON_PLUGIN_NAMESPACES)
			probes.put(nextId++, new PluginProbeSpec("/" + namespace + ":",
				PluginProbeKind.NAMESPACE, namespace));
		
		return probes;
	}
	
	private static int addProbeVariants(Map<Integer, PluginProbeSpec> probes,
		int nextId, PluginProbeKind kind, String... baseCommands)
	{
		for(String base : baseCommands)
		{
			if(base == null || base.isBlank())
				continue;
			String trimmed = base.trim();
			probes.put(nextId++, new PluginProbeSpec(trimmed, kind, null));
			probes.put(nextId++,
				new PluginProbeSpec(trimmed + " ", kind, null));
		}
		return nextId;
	}
	
	private static void sendNextProbeIfReady(ClientPacketListener connection)
	{
		if(ticksUntilNextProbe > 0)
			return;
		PluginProbeRequest request = queuedProbes.pollFirst();
		if(request == null)
			return;
		try
		{
			connection.send(new ServerboundCommandSuggestionPacket(request.id,
				request.spec.query));
		}catch(Exception e)
		{
			UiUtils.LOGGER.warn("Failed to send plugin probe {}",
				request.spec.query, e);
			pendingProbeIds.remove(request.id);
		}
		ticksUntilNextProbe = DEFAULT_PROBE_DELAY_TICKS;
	}
	
	private static void addObservedPluginCommand(String raw)
	{
		String token = normalizeCommandToken(raw);
		if(token.isEmpty())
			return;
		if(!token.chars().allMatch(ch -> Character.isLetterOrDigit(ch)
			|| ch == '_' || ch == '-' || ch == '.'))
			return;
		if(VANILLA_NAMESPACES.contains(token))
			return;
		if(getOnlinePlayerNames().contains(token))
			return;
		observedPluginCommands.add(token);
	}
	
	private static Set<String> getOnlinePlayerNames()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null || mc.getConnection() == null)
			return Set.of();
		Set<String> names = new HashSet<>();
		for(PlayerInfo info : mc.getConnection().getListedOnlinePlayers())
		{
			if(info == null || info.getProfile() == null
				|| info.getProfile().name() == null)
				continue;
			String name =
				info.getProfile().name().trim().toLowerCase(Locale.ROOT);
			if(!name.isEmpty())
				names.add(name);
		}
		return names;
	}
	
	private static String normalizeCommandToken(String raw)
	{
		if(raw == null)
			return "";
		String token = raw.trim();
		if(token.isEmpty())
			return "";
		while(token.startsWith("/"))
			token = token.substring(1).trim();
		int space = token.indexOf(' ');
		if(space >= 0)
			token = token.substring(0, space).trim();
		while(token.endsWith(":"))
			token = token.substring(0, token.length() - 1).trim();
		return token.toLowerCase(Locale.ROOT);
	}
	
	private static void inferPluginsFromObservedCommands()
	{
		Map<String, Set<String>> inferred =
			new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for(String token : observedPluginCommands)
		{
			String plugin = null;
			if(isKnownPluginNamespace(token))
				plugin = token;
			else if(ROOT_COMMAND_PLUGIN_ALIASES.containsKey(token))
				plugin = ROOT_COMMAND_PLUGIN_ALIASES.get(token);
			if(plugin == null || plugin.isBlank())
				continue;
			inferred
				.computeIfAbsent(plugin,
					ignored -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
				.add(token);
		}
		for(Map.Entry<String, Set<String>> entry : inferred.entrySet())
			mergePluginEntry(entry.getKey(), entry.getValue(), true,
				PluginEvidence.ROOT_HINT);
	}
	
	private static boolean isKnownPluginNamespace(String key)
	{
		for(String namespace : COMMON_PLUGIN_NAMESPACES)
			if(namespace.equalsIgnoreCase(key))
				return true;
		return false;
	}
	
	private static void mergePluginEntry(String pluginName,
		Collection<String> commands, boolean commandBacked,
		PluginEvidence evidence)
	{
		if(pluginName == null || pluginName.isBlank())
			return;
		String key = normalizePluginKey(pluginName);
		if(key.isBlank())
			return;
		
		PluginScanEntry entry =
			entries.computeIfAbsent(key, ignored -> new PluginScanEntry());
		if(entry.displayName == null || entry.displayName.isBlank())
			entry.displayName = pluginName.trim();
		entry.commandBacked = entry.commandBacked || commandBacked;
		entry.evidence = mergeEvidence(entry.evidence, evidence);
		if(commands != null)
			for(String command : commands)
			{
				String normalized = normalizeCommandToken(command);
				if(!normalized.isEmpty())
					entry.commands.add(normalized);
			}
	}
	
	private static PluginEvidence mergeEvidence(PluginEvidence current,
		PluginEvidence candidate)
	{
		if(candidate == null)
			return current == null ? PluginEvidence.UNKNOWN : current;
		if(current == null)
			return candidate;
		return evidenceRank(candidate) < evidenceRank(current) ? candidate
			: current;
	}
	
	private static int evidenceRank(PluginEvidence evidence)
	{
		if(evidence == null)
			return 99;
		return switch(evidence)
		{
			case KNOWN_PACK -> -1;
			case COMMAND_TREE -> 0;
			case NAMESPACE -> 1;
			case ROOT_HINT -> 2;
			case HELP_HINT -> 3;
			case PLUGIN_LIST -> 4;
			case VERSION_HINT -> 5;
			case UNKNOWN -> 6;
		};
	}
	
	private static String normalizePluginKey(String raw)
	{
		if(raw == null)
			return "";
		String token = raw.trim().toLowerCase(Locale.ROOT);
		if(token.startsWith("/"))
			token = token.substring(1);
		if(token.contains(":"))
			token = token.split(":", 2)[0];
		return token.trim();
	}
	
	private static boolean isDefaultFabricPlugin(String candidate)
	{
		String key = normalizePluginKey(candidate);
		return key.equals("fabric") || key.equals("fabricloader")
			|| key.startsWith("fabric-") || key.startsWith("fabric_");
	}
	
	private static boolean isLikelyPluginNameCandidate(String candidate,
		PluginProbeKind kind)
	{
		if(candidate == null)
			return false;
		String clean = candidate.trim();
		String key = normalizePluginKey(clean);
		if(key.isEmpty() || key.length() < 2)
			return false;
		if(clean.contains(" ") || clean.contains("/") || clean.contains(":"))
			return false;
		if(VANILLA_NAMESPACES.contains(key))
			return false;
		if((kind == PluginProbeKind.PLUGIN_LIST
			|| kind == PluginProbeKind.VERSION)
			&& (key.equals("plugins") || key.equals("plugin")
				|| key.equals("version") || key.equals("about")))
			return false;
		return key.chars().allMatch(ch -> Character.isLetterOrDigit(ch)
			|| ch == '_' || ch == '-' || ch == '.');
	}
	
	private static void finishAndPrint()
	{
		scanning = false;
		pendingProbeIds.clear();
		probesById.clear();
		queuedProbes.clear();
		lastRows.clear();
		
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null)
		{
			entries.clear();
			observedPluginCommands.clear();
			return;
		}
		
		Map<PluginEvidence, List<PluginScanEntry>> grouped =
			new EnumMap<>(PluginEvidence.class);
		for(PluginEvidence evidence : PluginEvidence.values())
			grouped.put(evidence, new ArrayList<>());
		for(PluginScanEntry entry : entries.values())
			grouped.get(entry.evidence == null ? PluginEvidence.UNKNOWN
				: entry.evidence).add(entry);
		
		List<String> ordered = new ArrayList<>();
		for(PluginEvidence evidence : List.of(PluginEvidence.KNOWN_PACK,
			PluginEvidence.COMMAND_TREE, PluginEvidence.NAMESPACE,
			PluginEvidence.ROOT_HINT, PluginEvidence.HELP_HINT,
			PluginEvidence.PLUGIN_LIST, PluginEvidence.VERSION_HINT,
			PluginEvidence.UNKNOWN))
		{
			List<PluginScanEntry> list = grouped.get(evidence);
			if(list == null || list.isEmpty())
				continue;
			list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER
				.compare(a.displayName, b.displayName));
			for(PluginScanEntry entry : list)
			{
				String lower = entry.displayName.toLowerCase(Locale.ROOT);
				boolean flagged =
					ANTICHEAT_WORDS.stream().anyMatch(lower::contains);
				String marker = flagged ? "!" : "";
				String suffix = entry.commands.isEmpty() ? ""
					: "{" + entry.commands.size() + "}";
				ordered.add(marker + entry.displayName + suffix);
				lastRows.add(new PluginResultRow(evidence.name(),
					entry.displayName, entry.commands.size(), flagged,
					new ArrayList<>(entry.commands)));
			}
		}
		
		UiUtilsScanHistory.recordPlugins(boundServerKey, "plugin", lastRows);
		
		if(ordered.isEmpty())
		{
			lastStatus = "No plugins found or blocked.";
		}else
		{
			lastStatus = "Detected " + ordered.size() + " plugins.";
			Component line = Component
				.literal("[UI-Utils] Plugins (" + ordered.size() + "): ")
				.withColor(0x55CCFF);
			boolean first = true;
			for(PluginEvidence evidence : List.of(PluginEvidence.KNOWN_PACK,
				PluginEvidence.COMMAND_TREE, PluginEvidence.NAMESPACE,
				PluginEvidence.ROOT_HINT, PluginEvidence.HELP_HINT,
				PluginEvidence.PLUGIN_LIST, PluginEvidence.VERSION_HINT,
				PluginEvidence.UNKNOWN))
			{
				List<PluginScanEntry> list = grouped.get(evidence);
				if(list == null || list.isEmpty())
					continue;
				list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER
					.compare(a.displayName, b.displayName));
				for(PluginScanEntry entry : list)
				{
					if(!first)
						line = line.copy().append(
							Component.literal(", ").withColor(0xD0D0D0));
					first = false;
					String lower = entry.displayName.toLowerCase(Locale.ROOT);
					boolean anticheat =
						ANTICHEAT_WORDS.stream().anyMatch(lower::contains);
					boolean vulnerable = UiUtilsVulnerablePlugins.keys()
						.contains(UiUtilsVulnerablePlugins
							.normalizeKey(entry.displayName));
					String text = (anticheat ? "!" : "") + entry.displayName
						+ (entry.commands.isEmpty() ? ""
							: "{" + entry.commands.size() + "}");
					int color = vulnerable ? 0xFF6B6B : 0x93F7A4;
					line = line.copy()
						.append(Component.literal(text).withColor(color));
				}
			}
			mc.player.sendSystemMessage(line);
		}
		
		entries.clear();
		observedPluginCommands.clear();
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
	
	private static void resetForServerChange()
	{
		scanning = false;
		ticksSinceStart = 0;
		ticksSinceLastResponse = 0;
		ticksUntilNextProbe = 0;
		completionAnnounceCooldown = 0;
		pendingProbeIds.clear();
		probesById.clear();
		queuedProbes.clear();
		observedPluginCommands.clear();
		entries.clear();
		lastRows.clear();
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
		if(scanning)
			return lastStatus + " [queued=" + queuedProbes.size() + ", pending="
				+ pendingProbeIds.size() + "]";
		return lastStatus;
	}
	
	public static boolean hasResultsForCurrentServer()
	{
		return !lastRows.isEmpty()
			&& boundServerKey.equals(currentServerKey(Minecraft.getInstance()));
	}
	
	public static boolean isActive()
	{
		return scanning;
	}
	
	public static List<PluginResultRow> getResultsSnapshot()
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
		ticksSinceStart = 0;
		ticksSinceLastResponse = 0;
		ticksUntilNextProbe = 0;
		completionAnnounceCooldown = 0;
		pendingProbeIds.clear();
		probesById.clear();
		queuedProbes.clear();
		observedPluginCommands.clear();
		entries.clear();
		lastRows.clear();
		recentEvents.clear();
		lastStatus = "Cleared.";
	}
	
	private enum PluginEvidence
	{
		KNOWN_PACK,
		COMMAND_TREE,
		NAMESPACE,
		ROOT_HINT,
		HELP_HINT,
		PLUGIN_LIST,
		VERSION_HINT,
		UNKNOWN
	}
	
	private enum PluginProbeKind
	{
		ROOT,
		HELP,
		PLUGIN_LIST,
		VERSION,
		NAMESPACE
	}
	
	private static final class PluginScanEntry
	{
		private String displayName;
		private boolean commandBacked;
		private PluginEvidence evidence = PluginEvidence.UNKNOWN;
		private final Set<String> commands =
			new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	}
	
	private record PluginProbeSpec(String query, PluginProbeKind kind,
		String hint)
	{}
	
	private record PluginProbeRequest(int id, PluginProbeSpec spec)
	{}
	
	public record PluginResultRow(String evidence, String plugin,
		int commandCount, boolean anticheatFlagged, List<String> commands)
	{}
	
}
