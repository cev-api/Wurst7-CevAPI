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
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
	
	private static final int RESPONSE_GRACE_TICKS = 20 * 3;
	private static final int MIN_HARD_TIMEOUT_TICKS = 20 * 15;
	private static final int MAX_PROBES_PER_TICK = 2;
	/**
	 * How long the server may stay completely silent before the suggestion
	 * probes are treated as suppressed. This must comfortably exceed realistic
	 * network latency, otherwise a slow-but-working server gets misdiagnosed as
	 * suppressing and the scan gives up on it.
	 */
	private static final int NO_RESPONSE_ABORT_TICKS = 20 * 3;
	private static final int MAX_ORACLE_CANDIDATES = 12;
	private static final String ACTIVE_PROBING_UNAVAILABLE =
		"Active plugin probing unavailable. Using passive evidence.";
	private static final String UNATTRIBUTED_NAME =
		"Unattributed plugin commands";
	private static final String SERVER_PLATFORM_NAME = "Server platform";
	/**
	 * Phrases meaning the oracle command itself does not exist, so no candidate
	 * can ever be confirmed and the rest of the queue is pointless.
	 */
	private static final String[] ORACLE_UNSUPPORTED_PATTERNS =
		{"unknown command", "unknown or incomplete", "command not found",
			"incorrect argument", "too many arguments"};
	/**
	 * Phrases meaning we are not allowed to run the oracle command at all.
	 */
	private static final String[] ORACLE_DENIED_PATTERNS = {"no permission",
		"you do not have", "insufficient permission", "not allowed"};
	/**
	 * Phrases meaning this one candidate is absent, while the oracle itself
	 * still works.
	 */
	private static final String[] ORACLE_NEGATIVE_PATTERNS =
		{"cannot find", "not found", "does not exist", "is not installed",
			"usage:", "invalid plugin", "no such plugin", "unknown plugin"};
	private static final int ORACLE_TIMEOUT_TICKS = 15;
	/**
	 * Matches tokens such as {@code v22.4}, {@code 5.4.102} or
	 * {@code 1.20-R0.1}.
	 */
	private static final Pattern VERSION_PATTERN =
		Pattern.compile("v?\\d+(?:\\.\\d+)+(?:-[A-Za-z0-9.]+)?");
	
	private static boolean scanning;
	private static int ticksSinceStart;
	private static int ticksSinceLastActivity;
	private static int ticksSinceLastResponse;
	private static int completionAnnounceCooldown;
	private static int hardTimeoutTicks = MIN_HARD_TIMEOUT_TICKS;
	private static long nextProbeAtNanos;
	private static int probesSent;
	private static int probesAwaitingResponse;
	private static int awaitingReported;
	private static boolean suggestionPhaseDone;
	private static boolean activeProbingAborted;
	
	private static final Deque<String> oracleQueue = new ArrayDeque<>();
	private static String oracleInFlight;
	private static int oracleWaitTicks;
	private static StringBuilder oracleResponse;
	
	private static final Map<Integer, PluginProbeSpec> pendingProbes =
		new HashMap<>();
	private static final Deque<PluginProbeRequest> queuedProbes =
		new ArrayDeque<>();
	private static final Set<String> observedPluginCommands =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private static final Set<String> unattributedCommands =
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
		UiUtilsSuggestionCapability.bindTo(boundServerKey);
		parseCommandTree(mc.player.connection);
		mergePassiveFingerprintEvidence();
		inferPluginsFromEvidence();
		
		if(UiUtilsSuggestionCapability.isSuppressed())
		{
			// No point sending another ~179 packets into a black hole.
			activeProbingAborted = true;
			lastStatus = ACTIVE_PROBING_UNAVAILABLE;
			print(ACTIVE_PROBING_UNAVAILABLE);
			finishAndPrint();
			return "[UI-Utils] " + ACTIVE_PROBING_UNAVAILABLE;
		}
		
		queueProbeBatch(buildPluginProbes());
		queueVersionOracle();
		hardTimeoutTicks = computeHardTimeoutTicks(queuedProbes.size());
		print("Plugin scan started. Probes: " + queuedProbes.size() + ".");
		sendProbesIfDue(mc.player.connection);
		return "[UI-Utils] Scanning plugins...";
	}
	
	private static int computeHardTimeoutTicks(int probeCount)
	{
		double ticksPerProbe = 20.0 / UiUtilsSettings.getProbesPerSecond();
		int sendTicks = (int)Math.ceil(probeCount * ticksPerProbe);
		// Each oracle command is a chat round trip rather than a suggestion
		// probe, so it gets its own timeout allowance.
		int oracleTicks = oracleQueue.size() * (ORACLE_TIMEOUT_TICKS + 4);
		return Math.max(MIN_HARD_TIMEOUT_TICKS,
			sendTicks + oracleTicks + RESPONSE_GRACE_TICKS + 40);
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
		ticksSinceLastActivity++;
		ticksSinceLastResponse++;
		
		if(ticksSinceStart >= hardTimeoutTicks)
		{
			print("Plugin scan timed out; finalizing with current evidence.");
			lastStatus = "Plugin scan timed out.";
			finishAndPrint();
			return;
		}
		
		if(oracleInFlight != null)
		{
			if(++oracleWaitTicks >= ORACLE_TIMEOUT_TICKS)
				finishOracleProbe();
			return;
		}
		
		if(!suggestionPhaseDone)
		{
			if(!queuedProbes.isEmpty())
			{
				if(checkEarlyAbort())
					return;
				if(!suggestionPhaseDone)
					sendProbesIfDue(mc.getConnection());
				if(abortIfProbingSuppressed())
					return;
				if(!queuedProbes.isEmpty())
					return;
			}
			
			// Wait for the outstanding responses before moving on, but never
			// block the oracle forever.
			if(!pendingProbes.isEmpty())
			{
				if(ticksSinceLastActivity < RESPONSE_GRACE_TICKS)
					return;
				print("Plugin scan response wait timeout; continuing.");
				pendingProbes.clear();
			}
			suggestionPhaseDone = true;
		}
		
		// The oracle is a chat round trip, so it works even when the server
		// suppresses suggestion packets. That is exactly the case it exists
		// for.
		if(!oracleQueue.isEmpty())
		{
			runVersionOracle(mc.getConnection());
			if(oracleInFlight != null)
				return;
		}
		
		if(++completionAnnounceCooldown >= 4)
			finishAndPrint();
	}
	
	/**
	 * Routes system chat into the version oracle while one is awaiting a reply.
	 * Called from the packet listener mixin.
	 */
	public static void onSystemChat(Component message)
	{
		if(!scanning)
			return;
		onOracleChat(message);
	}
	
	/**
	 * Counts probes that were actually sent but stayed unanswered for far
	 * longer
	 * than any healthy server needs. This catches a server that black-holes
	 * suggestion requests after a handful of packets instead of after the whole
	 * batch.
	 *
	 * @return true if the suggestion phase was aborted
	 */
	private static boolean checkEarlyAbort()
	{
		if(probesAwaitingResponse <= 0
			|| ticksSinceLastResponse < NO_RESPONSE_ABORT_TICKS)
			return false;
		
		int overdue = probesAwaitingResponse - awaitingReported;
		if(overdue <= 0)
			return false;
		awaitingReported = probesAwaitingResponse;
		
		if(!UiUtilsSuggestionCapability.recordUnansweredProbes(overdue))
			return false;
		return abortIfProbingSuppressed();
	}
	
	/**
	 * Drops the remaining suggestion probes as soon as the server is known to
	 * swallow suggestion responses. The version oracle is deliberately left
	 * intact: it talks over chat, so a server that suppresses suggestion
	 * packets
	 * is precisely the case where it is still useful.
	 *
	 * @return true if the suggestion phase ended here
	 */
	private static boolean abortIfProbingSuppressed()
	{
		if(!UiUtilsSuggestionCapability.isSuppressed())
			return false;
		activeProbingAborted = true;
		pendingProbes.clear();
		queuedProbes.clear();
		suggestionPhaseDone = true;
		print(ACTIVE_PROBING_UNAVAILABLE);
		lastStatus = ACTIVE_PROBING_UNAVAILABLE;
		return true;
	}
	
	public static void onSuggestionsPacket(
		ClientboundCommandSuggestionsPacket packet)
	{
		if(!scanning)
			return;
		PluginProbeSpec probe = pendingProbes.remove(packet.id());
		if(probe == null)
			return;
		ticksSinceLastActivity = 0;
		ticksSinceLastResponse = 0;
		probesAwaitingResponse = 0;
		awaitingReported = 0;
		UiUtilsSuggestionCapability.recordResponse();
		
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
				addNamespaceEvidence(parts[0].toLowerCase(Locale.ROOT));
				addObservedPluginCommand(parts[1]);
			}
			
			if(probe == null || text == null)
				continue;
			
			if(probe.kind == PluginProbeKind.NAMESPACE && probe.hint != null
				&& !normalizedToken.isEmpty())
			{
				// The namespace completed, so it definitely exists.
				addNamespaceEvidence(probe.hint);
				addObservedPluginCommand(normalizedToken);
				continue;
			}
			
			if(probe.kind != PluginProbeKind.PLUGIN_LIST
				&& probe.kind != PluginProbeKind.VERSION)
				continue;
			
			String pluginCandidate = text.trim();
			String pluginKey = normalizePluginKey(pluginCandidate);
			if(!isLikelyPluginNameCandidate(pluginCandidate, probe.kind)
				|| UiUtilsCommandScanner
					.isVanillaOrDefaultCommand(pluginCandidate)
				|| isDefaultFabricPlugin(pluginCandidate)
				|| ((probe.kind == PluginProbeKind.PLUGIN_LIST
					|| probe.kind == PluginProbeKind.VERSION)
					&& getOnlinePlayerNames().contains(pluginKey)))
				continue;
			
			if(probe.kind == PluginProbeKind.PLUGIN_LIST)
			{
				mergeEvidenceItem(pluginCandidate, pluginCandidate,
					UiUtilsPluginSignatures.Source.PLUGIN_LIST,
					pluginCandidate);
				continue;
			}
			
			// A version query completed for this exact candidate, so the plugin
			// exists. Any version-looking token in the response is kept.
			if(probe.hint != null && probe.hint.equalsIgnoreCase(pluginKey))
			{
				mergeEvidenceItem(probe.hint,
					UiUtilsPluginSignatures.displayName(probe.hint),
					UiUtilsPluginSignatures.Source.VERSION,
					"/ver " + probe.hint);
				String version = extractVersion(pluginCandidate);
				if(version != null)
					setVersion(probe.hint,
						UiUtilsPluginSignatures.displayName(probe.hint),
						version);
			}else
			{
				mergeEvidenceItem(pluginCandidate, pluginCandidate,
					UiUtilsPluginSignatures.Source.VERSION_HINT,
					pluginCandidate);
			}
		}
		
		inferPluginsFromEvidence();
	}
	
	/**
	 * Pulls a version-looking token out of a {@code /version <plugin>}
	 * response,
	 * e.g. {@code CoreProtect v22.4} or {@code LuckPerms 5.4.102}.
	 */
	private static String extractVersion(String text)
	{
		if(text == null)
			return null;
		Matcher matcher = VERSION_PATTERN.matcher(text);
		return matcher.find() ? matcher.group() : null;
	}
	
	private static void resetState()
	{
		scanning = false;
		ticksSinceStart = 0;
		ticksSinceLastActivity = 0;
		completionAnnounceCooldown = 0;
		hardTimeoutTicks = MIN_HARD_TIMEOUT_TICKS;
		nextProbeAtNanos = 0;
		probesSent = 0;
		ticksSinceLastResponse = 0;
		probesAwaitingResponse = 0;
		awaitingReported = 0;
		suggestionPhaseDone = false;
		activeProbingAborted = false;
		oracleQueue.clear();
		oracleInFlight = null;
		oracleWaitTicks = 0;
		oracleResponse = null;
		pendingProbes.clear();
		queuedProbes.clear();
		observedPluginCommands.clear();
		unattributedCommands.clear();
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
				// Paper's send-namespaced option leaks plugin namespaces here.
				String[] parts = name.split(":", 2);
				String ns = parts[0].toLowerCase(Locale.ROOT);
				String cmd = parts[1];
				if(VANILLA_NAMESPACES.contains(ns))
					continue;
				addNamespaceEvidence(ns);
				addObservedPluginCommand(cmd);
			}else
			{
				addObservedPluginCommand(name);
			}
		}
		inferPluginsFromEvidence();
	}
	
	/**
	 * Records a plugin namespace (from a namespaced root or a probe) and
	 * attributes it through the signature database where possible.
	 *
	 * An unknown namespace keeps its own identity instead of collapsing into
	 * the
	 * shared unattributed bucket, otherwise every unknown namespace produced
	 * another row all labelled "Unattributed plugin commands".
	 */
	private static void addNamespaceEvidence(String namespace)
	{
		if(namespace == null || namespace.isBlank())
			return;
		String ns = namespace.toLowerCase(Locale.ROOT);
		if(VANILLA_NAMESPACES.contains(ns))
			return;
		String plugin = UiUtilsPluginSignatures.pluginForNamespace(ns);
		String target = plugin == null ? ns : plugin;
		mergeEvidenceItem(target, UiUtilsPluginSignatures.displayName(target),
			UiUtilsPluginSignatures.Source.NAMESPACE, ns + ":*");
	}
	
	/**
	 * Records a clientbound plugin-message channel namespace. Bukkit/Paper
	 * plugins register namespaced channels, so namespaces such as
	 * {@code worldedit:cui} are strong evidence even when tab completion is
	 * suppressed.
	 */
	private static void addChannelEvidence(
		UiUtilsServerFingerprintCollector.ChannelInfo channel)
	{
		if(channel == null || channel.namespace() == null
			|| channel.namespace().isBlank())
			return;
		String ns = channel.namespace().toLowerCase(Locale.ROOT);
		String plugin = UiUtilsPluginSignatures.pluginForChannel(ns);
		String target = plugin == null ? ns : plugin;
		mergeEvidenceItem(target, UiUtilsPluginSignatures.displayName(target),
			UiUtilsPluginSignatures.Source.CHANNEL, channel.id());
	}
	
	/**
	 * Mines the passive protocol fingerprint (channels, known packs, foreign
	 * registry/advancement/dimension namespaces, brand) for plugin evidence.
	 */
	private static void mergePassiveFingerprintEvidence()
	{
		UiUtilsServerFingerprintCollector.Snapshot snapshot =
			UiUtilsServerFingerprintCollector.snapshot();
		
		for(UiUtilsServerFingerprintCollector.KnownPackInfo pack : snapshot
			.knownPacks())
		{
			if("minecraft".equalsIgnoreCase(pack.namespace())
				&& "core".equalsIgnoreCase(pack.id()))
				continue;
			String product =
				UiUtilsServerFingerprintCollector.friendlyName(pack.id());
			mergeEvidenceItem(product,
				UiUtilsPluginSignatures.displayName(product),
				UiUtilsPluginSignatures.Source.KNOWN_PACK,
				pack.namespace() + ":" + pack.id() + " " + pack.version());
		}
		
		for(UiUtilsServerFingerprintCollector.ChannelInfo channel : snapshot
			.payloads())
			addChannelEvidence(channel);
		
		for(String namespace : foreignNamespaces(snapshot))
			addForeignNamespaceEvidence(namespace);
		
		if(!snapshot.brand().isBlank())
			mergeEvidenceItem(SERVER_PLATFORM_NAME, SERVER_PLATFORM_NAME,
				UiUtilsPluginSignatures.Source.BRAND, snapshot.brand());
	}
	
	/**
	 * Collects namespaces used by server-provided registries, advancements and
	 * dimensions. These are usually datapacks rather than plugins, so they are
	 * reported as signatures rather than confirmations.
	 */
	private static Set<String> foreignNamespaces(
		UiUtilsServerFingerprintCollector.Snapshot snapshot)
	{
		Set<String> namespaces = new TreeSet<>();
		for(UiUtilsServerFingerprintCollector.RegistryInfo registry : snapshot
			.registries())
			for(UiUtilsServerFingerprintCollector.RegistryEntryInfo entry : registry
				.entries())
				addNamespaceOf(namespaces, entry.id());
		for(String advancement : snapshot.advancements())
			addNamespaceOf(namespaces, advancement);
		for(String dimension : snapshot.dimensions())
			addNamespaceOf(namespaces, dimension);
		return namespaces;
	}
	
	private static void addNamespaceOf(Set<String> namespaces, String id)
	{
		if(id == null)
			return;
		int colon = id.indexOf(':');
		if(colon <= 0)
			return;
		String ns = id.substring(0, colon).toLowerCase(Locale.ROOT);
		if(VANILLA_NAMESPACES.contains(ns))
			return;
		namespaces.add(ns);
	}
	
	private static void addForeignNamespaceEvidence(String namespace)
	{
		if(namespace == null || namespace.isBlank())
			return;
		String plugin = UiUtilsPluginSignatures.pluginForNamespace(namespace);
		String target = plugin == null ? namespace : plugin;
		mergeEvidenceItem(target, UiUtilsPluginSignatures.displayName(target),
			UiUtilsPluginSignatures.Source.FOREIGN_NAMESPACE, namespace + ":*");
	}
	
	private static void queueProbeBatch(Map<Integer, PluginProbeSpec> probes)
	{
		for(Map.Entry<Integer, PluginProbeSpec> entry : probes.entrySet())
		{
			pendingProbes.put(entry.getKey(), entry.getValue());
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
		
		nextId = addNamespaceProbes(probes, nextId);
		return probes;
	}
	
	/**
	 * Probes every known plugin namespace, e.g. {@code /luckperms:}. When Paper
	 * sends namespaced commands these complete, and when the namespace exists
	 * but is hidden from the root tree the response still reveals it.
	 */
	private static int addNamespaceProbes(Map<Integer, PluginProbeSpec> probes,
		int nextId)
	{
		for(String namespace : UiUtilsPluginSignatures.knownNamespaces())
			probes.put(nextId++, new PluginProbeSpec("/" + namespace + ":",
				PluginProbeKind.NAMESPACE, namespace));
		return nextId;
	}
	
	/**
	 * Queues {@code /ver <candidate>} oracle commands. This is a chat-response
	 * oracle rather than a tab-completion probe, so it still works on servers
	 * that suppress {@code ClientboundCommandSuggestionsPacket}. Suspected
	 * plugins are tested first, then known plugin names from the signature
	 * database fill the remaining budget.
	 *
	 * Strictly opt-in: it sends real commands and produces visible chat.
	 */
	private static void queueVersionOracle()
	{
		if(!UiUtilsSettings.get().pluginScanVersionOracle)
			return;
		
		Set<String> candidates = new LinkedHashSet<>();
		for(PluginScanEntry entry : entries.values())
		{
			if(entry.displayName == null || entry.displayName.isBlank())
				continue;
			if(entry.confidence() == PluginConfidence.LOW)
				continue;
			addOracleCandidate(candidates, entry.displayName);
		}
		for(String known : UiUtilsPluginSignatures.candidatePluginNames())
		{
			if(candidates.size() >= MAX_ORACLE_CANDIDATES)
				break;
			addOracleCandidate(candidates, known);
		}
		if(candidates.isEmpty())
			return;
		
		for(String candidate : candidates)
			oracleQueue.addLast(candidate);
		print("Queued " + oracleQueue.size() + " version-oracle command(s).");
	}
	
	private static void addOracleCandidate(Set<String> candidates,
		String candidate)
	{
		if(candidate == null || candidates.size() >= MAX_ORACLE_CANDIDATES)
			return;
		String token = candidate.trim();
		if(token.isEmpty() || token.contains(" ") || token.contains("/"))
			return;
		if(!token.chars().allMatch(ch -> Character.isLetterOrDigit(ch)
			|| ch == '_' || ch == '-' || ch == '.'))
			return;
		if(VANILLA_NAMESPACES.contains(token.toLowerCase(Locale.ROOT)))
			return;
		candidates.add(token);
	}
	
	private static void runVersionOracle(ClientPacketListener connection)
	{
		if(oracleQueue.isEmpty() || oracleInFlight != null
			|| connection == null)
			return;
		
		oracleInFlight = oracleQueue.pollFirst();
		oracleWaitTicks = 0;
		oracleResponse = new StringBuilder();
		try
		{
			connection.sendCommand("ver " + oracleInFlight);
		}catch(Exception e)
		{
			UiUtils.LOGGER.warn("Failed to send version oracle for {}",
				oracleInFlight, e);
			oracleInFlight = null;
		}
	}
	
	/**
	 * Collects chat while a version-oracle command is in flight.
	 *
	 * @return true if the response was consumed
	 */
	private static boolean onOracleChat(Component message)
	{
		if(oracleInFlight == null || message == null)
			return false;
		String text = message.getString();
		if(text == null || text.isBlank())
			return true;
		if(oracleResponse.length() < 512)
		{
			if(oracleResponse.length() > 0)
				oracleResponse.append(' ');
			oracleResponse.append(text.trim());
		}
		return true;
	}
	
	private static void finishOracleProbe()
	{
		String candidate = oracleInFlight;
		oracleInFlight = null;
		if(candidate == null)
			return;
		
		String raw = oracleResponse == null ? "" : oracleResponse.toString();
		if(raw.isBlank())
			return; // No reply at all says nothing about this candidate.
			
		String lower = raw.toLowerCase(Locale.ROOT);
		
		// An error echo can contain the candidate name, e.g. "Unknown or
		// incomplete command ... ver LuckPerms<--[HERE]", so a rejection always
		// wins over a name match.
		if(matchesAny(lower, ORACLE_UNSUPPORTED_PATTERNS))
		{
			abandonVersionOracle(
				"/ver is not usable on this server (" + abbreviate(raw) + ")");
			return;
		}
		if(matchesAny(lower, ORACLE_DENIED_PATTERNS))
		{
			abandonVersionOracle(
				"version queries are not permitted on this server");
			return;
		}
		if(matchesAny(lower, ORACLE_NEGATIVE_PATTERNS))
			return; // This candidate is absent; the oracle still works.
			
		// Confirmation needs a version token in the reply, which is what
		// Bukkit's /version <plugin> prints.
		String version = extractVersion(raw);
		if(version == null)
			return;
			
		// A reply that names any OTHER plugin is a whole-plugin listing rather
		// than an answer about this candidate. Every candidate would otherwise
		// be "confirmed" by one listing, so the oracle is not per-plugin here.
		if(looksLikePluginListing(lower, candidate))
		{
			abandonVersionOracle(
				"/ver prints the whole plugin list instead of one plugin");
			return;
		}
		
		// The candidate has to be named near the start of the reply, not merely
		// appear somewhere inside a long listing.
		String candidateLower = candidate.toLowerCase(Locale.ROOT);
		if(lower.indexOf(candidateLower) < 0
			|| lower.indexOf(candidateLower) > 160)
			return;
		
		String display = UiUtilsPluginSignatures.displayName(candidate);
		mergeEvidenceItem(candidate, display,
			UiUtilsPluginSignatures.Source.VERSION, "/ver " + candidate);
		setVersion(candidate, display, version);
	}
	
	/**
	 * True when a reply mentions a known plugin other than the candidate, which
	 * means the command prints every plugin rather than the one asked about.
	 */
	private static boolean looksLikePluginListing(String lower,
		String candidate)
	{
		String exclude = candidate.toLowerCase(Locale.ROOT);
		for(String known : UiUtilsPluginSignatures.candidatePluginNames())
		{
			if(known == null || known.length() < 4)
				continue;
			String token = known.toLowerCase(Locale.ROOT);
			if(token.equals(exclude) || token.equals(exclude + "x")
				|| exclude.equals(token + "x"))
				continue;
			if(lower.contains(token))
				return true;
		}
		return false;
	}
	
	private static void abandonVersionOracle(String reason)
	{
		int dropped = oracleQueue.size();
		oracleQueue.clear();
		if(dropped > 0)
			print("Abandoned " + dropped
				+ " remaining version-oracle command(s): " + reason + ".");
	}
	
	private static String abbreviate(String text)
	{
		if(text == null)
			return "";
		String trimmed = text.replaceAll("\\s+", " ").trim();
		return trimmed.length() <= 80 ? trimmed
			: trimmed.substring(0, 77) + "...";
	}
	
	private static boolean matchesAny(String text, String[] patterns)
	{
		for(String pattern : patterns)
			if(text.contains(pattern))
				return true;
		return false;
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
	
	private static void sendProbesIfDue(ClientPacketListener connection)
	{
		if(connection == null || queuedProbes.isEmpty())
			return;
		
		long interval = UiUtilsSettings.getProbeIntervalNanos();
		long now = System.nanoTime();
		if(nextProbeAtNanos == 0)
			nextProbeAtNanos = now;
		
		int sent = 0;
		while(sent < MAX_PROBES_PER_TICK && !queuedProbes.isEmpty()
			&& now >= nextProbeAtNanos)
		{
			PluginProbeRequest request = queuedProbes.pollFirst();
			try
			{
				connection.send(new ServerboundCommandSuggestionPacket(
					request.id, request.spec.query));
				probesAwaitingResponse++;
			}catch(Exception e)
			{
				UiUtils.LOGGER.warn("Failed to send plugin probe {}",
					request.spec.query, e);
				pendingProbes.remove(request.id);
			}
			sent++;
			ticksSinceLastActivity = 0;
			nextProbeAtNanos += interval;
		}
		
		if(sent == 0)
			return;
		probesSent += sent;
		
		// Frame hitches must not turn into a burst of catch-up probes.
		if(nextProbeAtNanos < now)
			nextProbeAtNanos = now + interval;
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
	
	private static void inferPluginsFromEvidence()
	{
		for(String token : observedPluginCommands)
		{
			String plugin = UiUtilsPluginSignatures.pluginForCommand(token);
			if(plugin == null)
			{
				// Roots such as /home or /tpa are provided by so many plugins
				// that naming one would be a guess, so they stay unattributed.
				if(UiUtilsPluginSignatures.isAmbiguousRoot(token))
					unattributedCommands.add(token);
				else
					unattributedCommands.add(token);
				continue;
			}
			mergeEvidenceItem(plugin,
				UiUtilsPluginSignatures.displayName(plugin),
				UiUtilsPluginSignatures.Source.COMMAND, "/" + token);
		}
	}
	
	/**
	 * Records one piece of evidence, creating the entry on demand.
	 *
	 * @param pluginName
	 *            the plugin key the evidence belongs to
	 * @param displayName
	 *            a nicer display name, or null to keep the existing one
	 * @param source
	 *            which kind of evidence this is
	 * @param value
	 *            the concrete evidence text shown in the UI
	 */
	private static void mergeEvidenceItem(String pluginName, String displayName,
		UiUtilsPluginSignatures.Source source, String value)
	{
		if(pluginName == null || pluginName.isBlank() || source == null)
			return;
		String key = normalizePluginKey(pluginName);
		if(key.isBlank())
			return;
		
		PluginScanEntry entry =
			entries.computeIfAbsent(key, ignored -> new PluginScanEntry());
		String resolved = displayName == null ? pluginName : displayName;
		if(entry.displayName == null || entry.displayName.isBlank()
			|| entry.displayName.equalsIgnoreCase(key))
			entry.displayName = resolved.trim();
		entry.addEvidence(source, value);
	}
	
	private static void setVersion(String pluginName, String displayName,
		String version)
	{
		if(pluginName == null || pluginName.isBlank() || version == null
			|| version.isBlank())
			return;
		String key = normalizePluginKey(pluginName);
		if(key.isBlank())
			return;
		PluginScanEntry entry =
			entries.computeIfAbsent(key, ignored -> new PluginScanEntry());
		String resolved = displayName == null ? pluginName : displayName;
		if(entry.displayName == null || entry.displayName.isBlank()
			|| entry.displayName.equalsIgnoreCase(key))
			entry.displayName = resolved.trim();
		entry.version = version.trim();
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
		pendingProbes.clear();
		queuedProbes.clear();
		oracleQueue.clear();
		oracleInFlight = null;
		lastRows.clear();
		
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null)
		{
			entries.clear();
			observedPluginCommands.clear();
			unattributedCommands.clear();
			return;
		}
		
		if(activeProbingAborted)
			print(ACTIVE_PROBING_UNAVAILABLE);
		
		if(!unattributedCommands.isEmpty())
		{
			String joined =
				String.join(", ", bounded(unattributedCommands, 16));
			mergeEvidenceItem(UNATTRIBUTED_NAME, UNATTRIBUTED_NAME,
				UiUtilsPluginSignatures.Source.COMMAND, joined);
		}
		
		Map<PluginConfidence, List<PluginScanEntry>> grouped =
			new EnumMap<>(PluginConfidence.class);
		for(PluginConfidence confidence : PluginConfidence.values())
			grouped.put(confidence, new ArrayList<>());
		for(PluginScanEntry entry : entries.values())
			grouped.get(entry.confidence()).add(entry);
		for(List<PluginScanEntry> list : grouped.values())
			list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER
				.compare(a.displayName, b.displayName));
		
		for(PluginConfidence confidence : PluginConfidence.values())
			for(PluginScanEntry entry : grouped.get(confidence))
				lastRows.add(new PluginResultRow(confidence.name(),
					entry.displayName, entry.commands.size(),
					isAnticheat(entry.displayName),
					new ArrayList<>(entry.commands), entry.evidenceLines()));
			
		UiUtilsScanHistory.recordPlugins(boundServerKey, "plugin", lastRows);
		
		int total = lastRows.size();
		if(total == 0)
		{
			lastStatus = activeProbingAborted ? ACTIVE_PROBING_UNAVAILABLE
				: "No plugins found or blocked.";
		}else
		{
			lastStatus = "Detected " + total + " software signature(s)"
				+ (activeProbingAborted ? " (passive evidence only)." : ".");
			if(total <= 12)
			{
				Component line =
					Component.literal("[UI-Utils] Detected (" + total + "): ")
						.withColor(0x55CCFF);
				boolean first = true;
				for(PluginResultRow row : lastRows)
				{
					if(!first)
						line = line.copy().append(
							Component.literal(", ").withColor(0xD0D0D0));
					first = false;
					String text = (row.anticheatFlagged() ? "!" : "")
						+ row.plugin() + " [" + row.confidence() + "]";
					int color = UiUtilsVulnerablePlugins.keys().contains(
						UiUtilsVulnerablePlugins.normalizeKey(row.plugin()))
							? 0xFF6B6B : confidenceColor(row.confidenceLevel());
					line = line.copy()
						.append(Component.literal(text).withColor(color));
				}
				mc.player.sendSystemMessage(line);
			}else
				mc.player.sendSystemMessage(Component
					.literal("[UI-Utils] Detected " + total
						+ " software signatures; open the ServerIntel screen for the full evidence list.")
					.withColor(0x55CCFF));
		}
		
		entries.clear();
		observedPluginCommands.clear();
		unattributedCommands.clear();
	}
	
	private static <T> List<T> bounded(Set<T> values, int max)
	{
		List<T> result = new ArrayList<>();
		for(T value : values)
		{
			if(result.size() >= max)
				break;
			result.add(value);
		}
		return result;
	}
	
	private static boolean isAnticheat(String displayName)
	{
		if(displayName == null)
			return false;
		String lower = displayName.toLowerCase(Locale.ROOT);
		return ANTICHEAT_WORDS.stream().anyMatch(lower::contains);
	}
	
	private static int confidenceColor(PluginConfidence confidence)
	{
		return switch(confidence)
		{
			case HIGH -> 0x93F7A4;
			case MEDIUM -> 0xFFD479;
			case LOW -> 0xB8B8B8;
		};
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
		ticksSinceLastActivity = 0;
		completionAnnounceCooldown = 0;
		hardTimeoutTicks = MIN_HARD_TIMEOUT_TICKS;
		nextProbeAtNanos = 0;
		probesSent = 0;
		ticksSinceLastResponse = 0;
		probesAwaitingResponse = 0;
		awaitingReported = 0;
		suggestionPhaseDone = false;
		activeProbingAborted = false;
		oracleQueue.clear();
		oracleInFlight = null;
		oracleWaitTicks = 0;
		oracleResponse = null;
		pendingProbes.clear();
		queuedProbes.clear();
		observedPluginCommands.clear();
		unattributedCommands.clear();
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
				+ pendingProbes.size() + "]";
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
		ticksSinceLastActivity = 0;
		completionAnnounceCooldown = 0;
		hardTimeoutTicks = MIN_HARD_TIMEOUT_TICKS;
		nextProbeAtNanos = 0;
		probesSent = 0;
		ticksSinceLastResponse = 0;
		probesAwaitingResponse = 0;
		awaitingReported = 0;
		suggestionPhaseDone = false;
		activeProbingAborted = false;
		oracleQueue.clear();
		oracleInFlight = null;
		oracleWaitTicks = 0;
		oracleResponse = null;
		pendingProbes.clear();
		queuedProbes.clear();
		observedPluginCommands.clear();
		unattributedCommands.clear();
		entries.clear();
		lastRows.clear();
		recentEvents.clear();
		lastStatus = "Cleared.";
	}
	
	public enum PluginConfidence
	{
		/**
		 * Direct proof: namespaced command, channel, known pack or server
		 * listing.
		 */
		HIGH,
		/**
		 * Several characteristic signatures, or one signature plus a namespace.
		 */
		MEDIUM,
		/** Weak or ambiguous evidence only; the attribution may be wrong. */
		LOW
	}
	
	private enum PluginProbeKind
	{
		ROOT,
		HELP,
		PLUGIN_LIST,
		VERSION,
		NAMESPACE
	}
	
	/**
	 * Accumulated evidence for one candidate plugin, plus the confidence the
	 * evidence justifies.
	 */
	private static final class PluginScanEntry
	{
		private String displayName;
		private String version;
		private final Map<UiUtilsPluginSignatures.Source, Set<String>> evidence =
			new EnumMap<>(UiUtilsPluginSignatures.Source.class);
		private final Set<String> commands =
			new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		
		private void addEvidence(UiUtilsPluginSignatures.Source source,
			String value)
		{
			if(source == null || value == null || value.isBlank())
				return;
			Set<String> values =
				evidence.computeIfAbsent(source, ignored -> new TreeSet<>());
			if(values.size() >= 96)
				return;
			values.add(value.trim());
			if(source == UiUtilsPluginSignatures.Source.COMMAND)
			{
				String command =
					value.startsWith("/") ? value.substring(1) : value;
				if(!command.isBlank())
					commands.add(command);
			}
		}
		
		private boolean has(UiUtilsPluginSignatures.Source source)
		{
			Set<String> values = evidence.get(source);
			return values != null && !values.isEmpty();
		}
		
		/**
		 * Counts roots that actually fingerprint a plugin, i.e. roots the
		 * signature database knows and that are not shared by many plugins.
		 * Ambiguous roots such as /home and unattributed foreign roots both
		 * score zero, so they can never lift an entry above LOW.
		 */
		private int distinctiveRoots()
		{
			Set<String> roots =
				evidence.get(UiUtilsPluginSignatures.Source.COMMAND);
			if(roots == null)
				return 0;
			int count = 0;
			for(String root : roots)
			{
				String token = root.startsWith("/") ? root.substring(1) : root;
				if(token.isBlank()
					|| UiUtilsPluginSignatures.isAmbiguousRoot(token))
					continue;
				if(UiUtilsPluginSignatures.pluginForCommand(token) != null)
					count++;
			}
			return count;
		}
		
		private PluginConfidence confidence()
		{
			// The server printed the plugin name itself; that needs no
			// corroboration.
			if(has(UiUtilsPluginSignatures.Source.PLUGIN_LIST)
				|| has(UiUtilsPluginSignatures.Source.KNOWN_PACK))
				return PluginConfidence.HIGH;
			
			boolean namespace = has(UiUtilsPluginSignatures.Source.NAMESPACE);
			boolean channel = has(UiUtilsPluginSignatures.Source.CHANNEL);
			boolean version = has(UiUtilsPluginSignatures.Source.VERSION);
			// A foreign namespace usually means a datapack rather than a
			// plugin,
			// so it is treated as a signature and cannot raise the confidence
			// on
			// its own.
			int direct =
				(namespace ? 1 : 0) + (channel ? 1 : 0) + (version ? 1 : 0);
			int distinctive = distinctiveRoots();
			
			if(direct > 0 && distinctive > 0)
				return PluginConfidence.HIGH;
			if(direct >= 2)
				return PluginConfidence.HIGH;
			if(distinctive >= 3)
				return PluginConfidence.HIGH;
			if(direct >= 1 || distinctive >= 1)
				return PluginConfidence.MEDIUM;
			return PluginConfidence.LOW;
		}
		
		/** Human-readable evidence lines, strongest source first. */
		private List<String> evidenceLines()
		{
			List<String> lines = new ArrayList<>();
			for(UiUtilsPluginSignatures.Source source : UiUtilsPluginSignatures.Source
				.values())
			{
				Set<String> values = evidence.get(source);
				if(values == null || values.isEmpty())
					continue;
				lines.add(source.label() + ": " + String.join(", ", values));
			}
			if(version != null && !version.isBlank())
				lines.add("version: " + version);
			return lines;
		}
	}
	
	private record PluginProbeSpec(String query, PluginProbeKind kind,
		String hint)
	{}
	
	private record PluginProbeRequest(int id, PluginProbeSpec spec)
	{}
	
	public record PluginResultRow(String confidence, String plugin,
		int commandCount, boolean anticheatFlagged, List<String> commands,
		List<String> evidence)
	{
		public PluginConfidence confidenceLevel()
		{
			try
			{
				return PluginConfidence.valueOf(confidence);
			}catch(IllegalArgumentException | NullPointerException ignored)
			{
				return PluginConfidence.LOW;
			}
		}
	}
	
}
