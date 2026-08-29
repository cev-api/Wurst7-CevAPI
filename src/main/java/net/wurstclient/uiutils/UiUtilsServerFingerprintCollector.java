/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistrySynchronization.PackedRegistryEntry;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.KnownPack;

/**
 * Compact, server-to-client-only protocol fingerprint cache. This is
 * deliberately
 * called on the network thread, so it only retains deduplicated scalar
 * metadata.
 */
public final class UiUtilsServerFingerprintCollector
{
	private static final int MAX_IDS_PER_SECTION = 256;
	private static final int MAX_CHAT_SAMPLES = 12;
	private static final Map<String, String> FRIENDLY_NAMES = Map.ofEntries(
		Map.entry("essential_commands", "Essential Commands"),
		Map.entry("fabric-convention-tags-v2", "Fabric Convention Tags v2"),
		Map.entry("server_translations_api", "Server Translations API"),
		Map.entry("styled_chat", "StyledChat"),
		Map.entry("styledchat", "StyledChat"),
		Map.entry("worldedit", "WorldEdit"),
		Map.entry("luckperms", "LuckPerms"),
		Map.entry("viaversion", "ViaVersion/ViaFabric"),
		Map.entry("viaver", "ViaVersion/ViaFabric"),
		Map.entry("vvfabric", "ViaVersion/ViaFabric"),
		Map.entry("voicechat", "Simple Voice Chat"),
		Map.entry("shulkerboxtooltip", "ShulkerBoxTooltip"),
		Map.entry("c2me", "C2ME"),
		Map.entry("forgeconfigapiport", "Forge Config API Port"),
		Map.entry("collective", "Collective"), Map.entry("servux", "Servux"),
		Map.entry("ledger", "Ledger"), Map.entry("mintutils", "MintUtils"),
		Map.entry("mineify", "Mineify"), Map.entry("vanish", "Vanish"),
		Map.entry("darkcasino", "DarkCasino"));
	
	private static final Object LOCK = new Object();
	private static volatile Connection activeConnection;
	private static boolean connectionActive;
	private static boolean configurationCaptured;
	// True only after play login; configuration packets arrive before Minecraft
	// exposes its play connection.
	private static boolean playStarted;
	private static String brand = "";
	private static final Map<String, KnownPackInfo> knownPacks =
		new LinkedHashMap<>();
	private static final Map<String, ChannelInfo> payloads =
		new LinkedHashMap<>();
	private static final Map<String, RegistryInfo> registries =
		new LinkedHashMap<>();
	private static final Set<String> dimensions = new LinkedHashSet<>();
	private static final Set<String> advancements = new LinkedHashSet<>();
	private static final Set<String> objectives = new LinkedHashSet<>();
	private static final Set<String> tabText = new LinkedHashSet<>();
	private static int chatCompletionCount;
	private static int emojiCompletionCount;
	private static int formattingCompletionCount;
	private static final List<String> chatSamples = new ArrayList<>();
	private static final Map<String, String> serverConfig =
		new LinkedHashMap<>();
	
	private UiUtilsServerFingerprintCollector()
	{}
	
	// ### ADDED ### Called before packet-tool cancellation; never observes
	// outgoing client packets.
	public static void onIncomingPacket(Connection connection, Packet<?> packet)
	{
		if(packet == null)
			return;
		// ### MODIFIED ### Ignore server-list/status connections entirely; they
		// are not joined-server evidence.
		PacketListener listener = connection.getPacketListener();
		if(!(listener instanceof ClientLoginPacketListener
			|| listener instanceof ClientConfigurationPacketListener
			|| listener instanceof ClientGamePacketListener))
			return;
		// ### ADDED ### A new joined-server Netty Connection is an unambiguous
		// cache boundary.
		if(activeConnection != connection)
		{
			synchronized(LOCK)
			{
				if(activeConnection != connection)
				{
					resetLocked();
					activeConnection = connection;
					connectionActive = true;
				}
			}
		}
		if(packet instanceof ClientboundSelectKnownPacks select)
		{
			beginConfigurationIfNeeded();
			synchronized(LOCK)
			{
				configurationCaptured = true;
				for(KnownPack pack : select.knownPacks())
					knownPacks.put(pack.namespace() + ":" + pack.id(),
						new KnownPackInfo(pack.namespace(), pack.id(),
							pack.version()));
			}
			return;
		}
		if(packet instanceof ClientboundRegistryDataPacket registry)
		{
			beginConfigurationIfNeeded();
			captureRegistry(registry);
			return;
		}
		if(packet instanceof ClientboundFinishConfigurationPacket)
		{
			beginConfigurationIfNeeded();
			return;
		}
		if(packet instanceof ClientboundCustomPayloadPacket custom)
		{
			capturePayload(custom.payload());
			return;
		}
		if(packet instanceof ClientboundLoginPacket login)
		{
			captureLogin(login); // Do not reset: configuration evidence
									// precedes this packet.
			return;
		}
		if(packet instanceof ClientboundUpdateAdvancementsPacket advancementsPacket)
		{
			captureAdvancements(advancementsPacket.getAdded());
			return;
		}
		if(packet instanceof ClientboundCustomChatCompletionsPacket completions)
		{
			captureChatCompletions(completions);
			return;
		}
		if(packet instanceof ClientboundSetObjectivePacket objective)
		{
			addBounded(objectives, objective.getObjectiveName());
			return;
		}
		if(packet instanceof ClientboundSetDisplayObjectivePacket objective)
		{
			addBounded(objectives, objective.getObjectiveName());
			return;
		}
		if(packet instanceof ClientboundTabListPacket tab)
		{
			captureCurrentTab(tab.header(), tab.footer());
		}
	}
	
	public static void onClientTick(Minecraft minecraft)
	{
		// ### MODIFIED ### Do not clear configuration evidence during the
		// configuration -> play hand-off.
		if(minecraft == null || minecraft.getConnection() == null)
		{
			synchronized(LOCK)
			{
				if(connectionActive && playStarted)
					resetLocked();
			}
		}
	}
	
	public static void resetForNewConnection()
	{
		synchronized(LOCK)
		{
			resetLocked();
			connectionActive = true;
		}
	}
	
	public static void onDisconnect()
	{
		synchronized(LOCK)
		{
			resetLocked();
		}
	}
	
	public static Snapshot snapshot()
	{
		synchronized(LOCK)
		{
			return new Snapshot(connectionActive, configurationCaptured, brand,
				List.copyOf(knownPacks.values()),
				List.copyOf(payloads.values()),
				List.copyOf(registries.values()), List.copyOf(dimensions),
				List.copyOf(advancements), List.copyOf(objectives),
				List.copyOf(tabText), chatCompletionCount, emojiCompletionCount,
				formattingCompletionCount, List.copyOf(chatSamples),
				Map.copyOf(serverConfig));
		}
	}
	
	/**
	 * Returns compact evidence for the expandable normal Plugin Scanner row.
	 */
	public static List<String> detailsForSoftware(String displayName)
	{
		String target =
			displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
		List<String> details = new ArrayList<>();
		Snapshot snapshot = snapshot();
		for(KnownPackInfo pack : snapshot.knownPacks())
		{
			String friendly = friendlyName(pack.id()).toLowerCase(Locale.ROOT);
			if(target.equals(friendly) || target.startsWith(friendly + " "))
				details.add("Known Pack: " + pack.namespace() + ":" + pack.id()
					+ " " + pack.version());
		}
		for(ChannelInfo channel : snapshot.payloads())
		{
			String friendly =
				friendlyName(channel.namespace()).toLowerCase(Locale.ROOT);
			if(target.equals(friendly) || target.startsWith(friendly + " "))
				details.add("Server payload: " + channel.id());
		}
		for(RegistryInfo registry : snapshot.registries())
			for(RegistryEntryInfo entry : registry.entries())
			{
				String namespace = entry.id().split(":", 2)[0];
				String friendly =
					friendlyName(namespace).toLowerCase(Locale.ROOT);
				if(target.equals(friendly) || target.startsWith(friendly + " "))
					details.add("Registry: " + entry.id()
						+ (entry.hasCustomData() ? " [custom data]" : ""));
			}
		for(String dimension : snapshot.dimensions())
		{
			String namespace = dimension.split(":", 2)[0];
			String friendly = friendlyName(namespace).toLowerCase(Locale.ROOT);
			if(target.equals(friendly) || target.startsWith(friendly + " "))
				details.add("Dimension: " + dimension);
		}
		return List.copyOf(details);
	}
	
	public static String friendlyName(String raw)
	{
		if(raw == null || raw.isBlank())
			return "Unknown";
		return FRIENDLY_NAMES.getOrDefault(raw.toLowerCase(Locale.ROOT), raw);
	}
	
	private static void beginConfigurationIfNeeded()
	{
		synchronized(LOCK)
		{
			if(!connectionActive)
			{
				resetLocked();
				connectionActive = true;
			}
		}
	}
	
	private static void capturePayload(CustomPacketPayload payload)
	{
		if(payload == null)
			return;
		Identifier id = payload.type().id();
		String rawId = id.toString();
		String phase;
		synchronized(LOCK)
		{
			phase =
				configurationCaptured && !serverConfig.containsKey("maxPlayers")
					? "CONFIGURATION" : "PLAY";
			payloads.putIfAbsent(rawId, new ChannelInfo(rawId,
				id.getNamespace(), id.getPath(), phase, "custom payload"));
			if(payload instanceof BrandPayload brandPayload)
				brand = brandPayload.brand();
		}
	}
	
	private static void captureRegistry(ClientboundRegistryDataPacket packet)
	{
		String registryKey = packet.registry().identifier().toString();
		List<RegistryEntryInfo> entries = new ArrayList<>();
		for(PackedRegistryEntry entry : packet.entries())
		{
			Identifier id = entry.id();
			if(!"minecraft".equals(id.getNamespace()))
				entries.add(new RegistryEntryInfo(id.toString(),
					entry.data().isPresent()));
		}
		synchronized(LOCK)
		{
			registries.put(registryKey,
				new RegistryInfo(registryKey, List.copyOf(entries)));
		}
	}
	
	private static void captureLogin(ClientboundLoginPacket packet)
	{
		synchronized(LOCK)
		{
			connectionActive = true;
			serverConfig.put("maxPlayers",
				Integer.toString(packet.maxPlayers()));
			serverConfig.put("chunkRadius",
				Integer.toString(packet.chunkRadius()));
			serverConfig.put("simulationDistance",
				Integer.toString(packet.simulationDistance()));
			serverConfig.put("onlineMode",
				Boolean.toString(packet.onlineMode()));
			serverConfig.put("enforcesSecureChat",
				Boolean.toString(packet.enforcesSecureChat()));
			serverConfig.put("gameMode",
				packet.commonPlayerSpawnInfo().gameType().getName());
			serverConfig.put("seaLevel",
				Integer.toString(packet.commonPlayerSpawnInfo().seaLevel()));
			serverConfig.put("seedField",
				Long.toString(packet.commonPlayerSpawnInfo().seed()));
			for(ResourceKey<?> level : packet.levels())
				addBounded(dimensions, level.identifier().toString());
		}
	}
	
	private static void captureAdvancements(Collection<AdvancementHolder> added)
	{
		synchronized(LOCK)
		{
			for(AdvancementHolder holder : added)
			{
				Identifier id = holder.id();
				if(!"minecraft".equals(id.getNamespace()))
					addBounded(advancements, id.toString());
			}
		}
	}
	
	private static void captureChatCompletions(
		ClientboundCustomChatCompletionsPacket packet)
	{
		synchronized(LOCK)
		{
			chatCompletionCount += packet.entries().size();
			for(String entry : packet.entries())
			{
				if(entry.startsWith(":"))
					emojiCompletionCount++;
				else
					formattingCompletionCount++;
				if(chatSamples.size() < MAX_CHAT_SAMPLES)
					chatSamples.add(entry);
			}
		}
	}
	
	private static void captureCurrentTab(Component header, Component footer)
	{
		synchronized(LOCK)
		{
			tabText.clear();
			captureTabText(header);
			captureTabText(footer);
		}
	}
	
	private static void captureTabText(Component component)
	{
		if(component == null)
			return;
		String text = component.getString().replaceAll("\\s+", " ").trim();
		if(!text.isEmpty())
			synchronized(LOCK)
			{
				addBounded(tabText, text);
			}
	}
	
	private static void addBounded(Set<String> values, String value)
	{
		if(value != null && !value.isBlank()
			&& values.size() < MAX_IDS_PER_SECTION)
			values.add(value);
	}
	
	private static void resetLocked()
	{
		activeConnection = null;
		connectionActive = false;
		configurationCaptured = false;
		playStarted = false;
		brand = "";
		knownPacks.clear();
		payloads.clear();
		registries.clear();
		dimensions.clear();
		advancements.clear();
		objectives.clear();
		tabText.clear();
		chatCompletionCount = 0;
		emojiCompletionCount = 0;
		formattingCompletionCount = 0;
		chatSamples.clear();
		serverConfig.clear();
	}
	
	public record KnownPackInfo(String namespace, String id, String version)
	{}
	
	public record ChannelInfo(String id, String namespace, String path,
		String phase, String source)
	{}
	
	public record RegistryEntryInfo(String id, boolean hasCustomData)
	{}
	
	public record RegistryInfo(String registry, List<RegistryEntryInfo> entries)
	{}
	
	public record Snapshot(boolean connected, boolean configurationCaptured,
		String brand, List<KnownPackInfo> knownPacks,
		List<ChannelInfo> payloads, List<RegistryInfo> registries,
		List<String> dimensions, List<String> advancements,
		List<String> objectives, List<String> tabText, int chatCompletionCount,
		int emojiCompletionCount, int formattingCompletionCount,
		List<String> chatSamples, Map<String, String> serverConfig)
	{}
}
