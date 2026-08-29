/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Persists one latest snapshot per scan type and annotates it against the prior
 * result.
 */
public final class UiUtilsScanHistory
{
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	private static final String DIRECTORY = "ui-utils-scan-history";
	
	private UiUtilsScanHistory()
	{}
	
	public static String serverKey(Minecraft mc)
	{
		if(mc == null)
			return "singleplayer";
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
		return "singleplayer";
	}
	
	public static void recordPlugins(String serverKey, String scanType,
		List<UiUtilsPluginScanner.PluginResultRow> rows)
	{
		Map<String, Entry> current = new LinkedHashMap<>();
		for(UiUtilsPluginScanner.PluginResultRow row : rows)
		{
			if(row == null || row.plugin() == null || row.plugin().isBlank())
				continue;
			current.put(normalize(row.plugin()), new Entry(row.plugin(),
				row.evidence(), new ArrayList<>(row.commands())));
		}
		record(serverKey, scanType, current);
	}
	
	// ### ADDED ### Compact verbose fingerprint history; no raw packet or NBT
	// data is persisted.
	/**
	 * Persists a compact, bounded snapshot of all reportable server evidence.
	 * Raw packet data, NBT, player identities, and completion lists stay out of
	 * history.
	 */
	public static void recordVerboseFingerprint(String serverKey,
		UiUtilsServerFingerprintCollector.Snapshot snapshot)
	{
		Map<String, Entry> current = new LinkedHashMap<>();
		for(UiUtilsServerFingerprintCollector.KnownPackInfo pack : snapshot
			.knownPacks())
			putVerboseEntry(current,
				"pack:" + normalize(pack.namespace() + ":" + pack.id()),
				"Known Pack " + pack.namespace() + ":" + pack.id() + " "
					+ compact(pack.version()),
				"KNOWN_PACK", List.of());
		for(UiUtilsServerFingerprintCollector.ChannelInfo channel : snapshot
			.payloads())
			putVerboseEntry(current, "channel:" + normalize(channel.id()),
				"Server channel " + compact(channel.id()),
				"SERVER_CUSTOM_PAYLOAD", List.of());
		for(String dimension : snapshot.dimensions())
			putVerboseEntry(current, "dimension:" + normalize(dimension),
				"Dimension " + compact(dimension), "DIMENSION", List.of());
		for(UiUtilsServerFingerprintCollector.RegistryInfo registry : snapshot
			.registries())
			for(UiUtilsServerFingerprintCollector.RegistryEntryInfo entry : registry
				.entries())
				putVerboseEntry(current,
					"registry:"
						+ normalize(registry.registry() + "/" + entry.id()),
					"Registry " + compact(registry.registry()) + " "
						+ compact(entry.id()),
					"CUSTOM_REGISTRY", List.of());
		if(!snapshot.brand().isBlank())
			putVerboseEntry(current, "brand",
				"Platform / Brand " + compact(snapshot.brand()), "SERVER_BRAND",
				List.of());
		
		for(UiUtilsPluginScanner.PluginResultRow row : UiUtilsPluginScanner
			.getResultsSnapshot())
		{
			if(row == null || row.plugin() == null || row.plugin().isBlank())
				continue;
			String evidence = compact(row.evidence()) + "; commands="
				+ row.commandCount() + "; antiCheat=" + row.anticheatFlagged();
			putVerboseEntry(current, "software:" + normalize(row.plugin()),
				"Software " + compact(row.plugin()), evidence,
				boundedValues(row.commands(), 32));
		}
		for(String advancement : snapshot.advancements())
			putVerboseEntry(current, "advancement:" + normalize(advancement),
				"Advancement / Datapack " + compact(advancement),
				"ADVANCEMENT_OR_DATAPACK", List.of());
		for(String objective : snapshot.objectives())
			putVerboseEntry(current, "objective:" + normalize(objective),
				"Scoreboard objective " + compact(objective), "OBJECTIVE",
				List.of());
		for(String tab : boundedValues(snapshot.tabText(), 8))
			putVerboseEntry(current, "tab:" + normalize(tab),
				"Tab text " + compact(tab), "TAB_TEXT", List.of());
		for(Map.Entry<String, String> config : snapshot.serverConfig()
			.entrySet())
			putVerboseEntry(current, "config:" + normalize(config.getKey()),
				"Server config " + compact(config.getKey()),
				compact(config.getValue()), List.of());
		putVerboseEntry(current, "chat-completions", "Chat completions",
			"total=" + snapshot.chatCompletionCount() + "; emoji="
				+ snapshot.emojiCompletionCount() + "; formatting/action="
				+ snapshot.formattingCompletionCount(),
			List.of());
		record(serverKey, "verbose_server", current);
	}
	
	private static void putVerboseEntry(Map<String, Entry> entries, String key,
		String name, String evidence, List<String> commands)
	{
		if(key == null || key.isBlank())
			return;
		entries.put(key, new Entry(name, evidence, commands));
	}
	
	private static List<String> boundedValues(Iterable<String> values,
		int maxEntries)
	{
		List<String> result = new ArrayList<>();
		if(values == null)
			return result;
		for(String value : values)
		{
			if(value == null || value.isBlank())
				continue;
			result.add(compact(value));
			if(result.size() >= maxEntries)
				break;
		}
		return result;
	}
	
	private static String compact(String value)
	{
		if(value == null)
			return "";
		String trimmed = value.trim();
		return trimmed.length() <= 256 ? trimmed : trimmed.substring(0, 256);
	}
	
	public static void recordCommands(String serverKey, String scanType,
		List<String> commands)
	{
		Map<String, Entry> current = new LinkedHashMap<>();
		for(String command : commands)
		{
			if(command == null || command.isBlank())
				continue;
			String name = command.trim();
			current.put(normalize(name), new Entry(name, null, List.of()));
		}
		record(serverKey, scanType, current);
	}
	
	private static synchronized void record(String serverKey, String scanType,
		Map<String, Entry> current)
	{
		String resolvedServerKey = serverKey == null || serverKey.isBlank()
			? "singleplayer" : serverKey;
		Path path = historyPath(resolvedServerKey);
		JsonObject root = read(path);
		JsonArray history = root.has("scans") && root.get("scans").isJsonArray()
			? root.getAsJsonArray("scans") : new JsonArray();
		Map<String, Entry> previous = previousEntries(history, scanType);
		Set<String> allKeys = new LinkedHashSet<>(previous.keySet());
		allKeys.addAll(current.keySet());
		
		// ### MODIFIED ### Keep one bounded result per scan type instead of
		// appending every scan.
		JsonArray retained = new JsonArray();
		for(JsonElement element : history)
		{
			if(!element.isJsonObject() || !scanType
				.equals(getString(element.getAsJsonObject(), "type")))
				retained.add(element);
		}
		
		JsonArray snapshotEntries = new JsonArray();
		List<String> orderedKeys = new ArrayList<>(allKeys);
		Collections.sort(orderedKeys);
		for(String key : orderedKeys)
		{
			Entry entry = current.get(key);
			Entry old = previous.get(key);
			JsonObject item = new JsonObject();
			item.addProperty("key", key);
			if(entry != null)
			{
				item.addProperty("name", entry.name());
				if(entry.evidence() != null)
					item.addProperty("evidence", entry.evidence());
				JsonArray commands = new JsonArray();
				for(String command : entry.commands())
					commands.add(command);
				item.add("commands", commands);
			}else
			{
				item.addProperty("name", old.name());
				if(old.evidence() != null)
					item.addProperty("evidence", old.evidence());
			}
			String status = entry == null ? "removed" : old == null ? "added"
				: entry.equals(old) ? "unchanged" : "changed";
			item.addProperty("change", status);
			snapshotEntries.add(item);
		}
		
		JsonObject snapshot = new JsonObject();
		snapshot.addProperty("timestamp", Instant.now().toString());
		snapshot.addProperty("type", scanType);
		snapshot.add("entries", snapshotEntries);
		retained.add(snapshot);
		root.addProperty("server", resolvedServerKey);
		root.add("scans", retained);
		try
		{
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			UiUtils.LOGGER.warn("Failed to save {} scan history to {}",
				scanType, path, e);
		}
	}
	
	private static Map<String, Entry> previousEntries(JsonArray history,
		String scanType)
	{
		for(int i = history.size() - 1; i >= 0; i--)
		{
			JsonElement element = history.get(i);
			if(!element.isJsonObject() || !scanType
				.equals(getString(element.getAsJsonObject(), "type")))
				continue;
			JsonArray entries = element.getAsJsonObject().has("entries")
				? element.getAsJsonObject().getAsJsonArray("entries")
				: new JsonArray();
			Map<String, Entry> result = new LinkedHashMap<>();
			for(JsonElement item : entries)
			{
				if(!item.isJsonObject())
					continue;
				JsonObject object = item.getAsJsonObject();
				String name = getString(object, "name");
				if(name == null || name.isBlank())
					continue;
				List<String> commands = new ArrayList<>();
				if(object.has("commands")
					&& object.get("commands").isJsonArray())
					for(JsonElement command : object.getAsJsonArray("commands"))
						commands.add(command.getAsString());
				String storedKey = getString(object, "key");
				result.put(
					storedKey == null || storedKey.isBlank() ? normalize(name)
						: storedKey,
					new Entry(name, getString(object, "evidence"), commands));
			}
			return result;
		}
		return Map.of();
	}
	
	private static JsonObject read(Path path)
	{
		if(!Files.exists(path))
			return new JsonObject();
		try
		{
			JsonElement parsed = JsonParser
				.parseString(Files.readString(path, StandardCharsets.UTF_8));
			return parsed != null && parsed.isJsonObject()
				? parsed.getAsJsonObject() : new JsonObject();
		}catch(Exception e)
		{
			UiUtils.LOGGER.warn("Failed to read scan history from {}", path, e);
			return new JsonObject();
		}
	}
	
	private static Path historyPath(String serverKey)
	{
		String safe =
			serverKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
		if(safe.isBlank())
			safe = "server";
		return FabricLoader.getInstance().getConfigDir().resolve(DIRECTORY)
			.resolve(safe + "-" + Integer.toHexString(serverKey.hashCode())
				+ ".json");
	}
	
	private static String normalize(String value)
	{
		return value.trim().toLowerCase(Locale.ROOT);
	}
	
	private static String getString(JsonObject object, String key)
	{
		return object.has(key) && object.get(key).isJsonPrimitive()
			? object.get(key).getAsString() : null;
	}
	
	private record Entry(String name, String evidence, List<String> commands)
	{}
}
