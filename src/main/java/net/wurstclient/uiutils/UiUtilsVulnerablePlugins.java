/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;

public final class UiUtilsVulnerablePlugins
{
	private static final String DEFAULT_RESOURCE =
		"/assets/wurst/vulnerable_plugins.json";
	private static final String CONFIG_FILE_NAME =
		"ui-utils-vulnerable-plugins.json";
	private static volatile boolean initialized;
	private static volatile Map<String, VulnerableEntry> entriesByKey =
		Map.of();
	private static volatile UiUtilsDupeDbDatabase.Data database =
		UiUtilsDupeDbDatabase.empty();
	
	private static Map<String, UiUtilsDupeDbDatabase.Exploit> exploitsById =
		Map.of();
	private static final Map<String, List<Finding>> pluginFindingCache =
		new HashMap<>();
	private static final Map<String, List<Finding>> serverFindingCache =
		new HashMap<>();
	
	private UiUtilsVulnerablePlugins()
	{}
	
	public static synchronized void init()
	{
		if(initialized)
			return;
		reload();
		initialized = true;
	}
	
	public static synchronized void reload()
	{
		UiUtilsDupeDbDatabase.Data bundled = readBundled();
		Path target =
			FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
		if(!Files.exists(target))
			try
			{
				Files.createDirectories(target.getParent());
				Files.writeString(target, UiUtilsDupeDbDatabase.json(bundled),
					StandardCharsets.UTF_8);
			}catch(IOException e)
			{
				UiUtils.LOGGER.warn(
					"Failed to initialize local vulnerability database ({})",
					e.getClass().getSimpleName());
			}
		UiUtilsDupeDbDatabase.Data merged =
			UiUtilsDupeDbDatabase.merge(bundled, readFile(target), false);
		Path online = target.resolveSibling("ui-utils-dupedb-plugins.json");
		if(UiUtilsSettings.get().dupeDbEnabled && Files.exists(online))
			merged =
				UiUtilsDupeDbDatabase.merge(merged, readFile(online), true);
		Map<String, VulnerableEntry> entries = new LinkedHashMap<>();
		for(var plugin : merged.plugins())
		{
			String key = normalizeKey(plugin.name());
			if(key.isEmpty())
				continue;
			VulnerableEntry entry = entries.computeIfAbsent(key,
				ignored -> new VulnerableEntry(plugin.name()));
			if(plugin.version() != null && !plugin.version().isBlank())
				entry.versions.add(plugin.version());
		}
		database = merged;
		Map<String, UiUtilsDupeDbDatabase.Exploit> exploits = new HashMap<>();
		for(var exploit : merged.exploits())
			exploits.put(exploit.id(), exploit);
		exploitsById = Map.copyOf(exploits);
		pluginFindingCache.clear();
		serverFindingCache.clear();
		entriesByKey = Collections.unmodifiableMap(entries);
	}
	
	public static UiUtilsDupeDbDatabase.Data readFile(Path path)
	{
		if(!Files.exists(path))
			return UiUtilsDupeDbDatabase.empty();
		try(Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			return UiUtilsDupeDbDatabase.read(JsonParser.parseReader(r));
		}catch(IOException | RuntimeException e)
		{
			UiUtils.LOGGER.warn(
				"Could not read DupeDB cache {}; using remaining data ({})",
				path, e.getClass().getSimpleName());
			return UiUtilsDupeDbDatabase.empty();
		}
	}
	
	private static UiUtilsDupeDbDatabase.Data readBundled()
	{
		try(InputStream in = UiUtilsVulnerablePlugins.class
			.getResourceAsStream(DEFAULT_RESOURCE))
		{
			if(in == null)
				return UiUtilsDupeDbDatabase.empty();
			return UiUtilsDupeDbDatabase.read(JsonParser.parseReader(
				new InputStreamReader(in, StandardCharsets.UTF_8)));
		}catch(IOException | RuntimeException e)
		{
			UiUtils.LOGGER.warn(
				"Could not read bundled vulnerability database ({})",
				e.getClass().getSimpleName());
			return UiUtilsDupeDbDatabase.empty();
		}
	}
	
	public static UiUtilsDupeDbDatabase.Data database()
	{
		return database;
	}
	
	public static Set<String> keys()
	{
		return entriesByKey.keySet();
	}
	
	public static Map<String, VulnerableEntry> entriesByKey()
	{
		return entriesByKey;
	}
	
	public static String normalizeKey(String raw)
	{
		if(raw == null)
			return "";
		StringBuilder b = new StringBuilder();
		raw = raw.toLowerCase(Locale.ROOT);
		for(int i = 0; i < raw.length(); i++)
			if(Character.isLetterOrDigit(raw.charAt(i)))
				b.append(raw.charAt(i));
		return b.toString();
	}
	
	public static int patchColor(Boolean patched)
	{
		return patched == null ? 0xFFD8D8D8 : patched ? 0xFFFF7A7A : 0xFF93F7A4;
	}
	
	public static int patchDetailColor(String text, int fallback)
	{
		String line = text.trim();
		if(line.equals("Globally marked patched: no")
			|| line.equals("Server patch status: reported not patched"))
			return patchColor(false);
		if(line.equals("Globally marked patched: yes")
			|| line.equals("Server patch status: patched")
			|| line.equals("Exploit status: patched")
			|| line.startsWith("Exploit patched: ")
			|| line.startsWith("Server patched: "))
			return patchColor(true);
		return fallback;
	}
	
	public record Finding(String title, String url, List<String> details,
		Boolean patched)
	{}
	
	public static synchronized List<Finding> pluginFindings(String name)
	{
		return pluginFindingCache.computeIfAbsent(normalizeKey(name),
			ignored -> buildPluginFindings(name));
	}
	
	private static List<Finding> buildPluginFindings(String name)
	{
		Map<String, Finding> results = new LinkedHashMap<>();
		String key = normalizeKey(name);
		for(var plugin : database.plugins())
			if(key.equals(normalizeKey(plugin.name()))
				&& plugin.exploitId() != null)
			{
				var e = exploit(plugin.exploitId());
				if(e == null)
					continue;
				results.put(e.id(),
					new Finding(e.title(),
						UiUtilsDupeDbDatabase.safeUrl(e.url()),
						exploitDetails(e), e.patched()));
			}
		return List.copyOf(results.values());
	}
	
	public static synchronized List<Finding> serverFindings(String address)
	{
		return serverFindingCache.computeIfAbsent(
			UiUtilsDupeDbDatabase.normalizeAddress(address),
			ignored -> buildServerFindings(address));
	}
	
	private static List<Finding> buildServerFindings(String address)
	{
		String key = UiUtilsDupeDbDatabase.normalizeAddress(address);
		if(key.isEmpty())
			return List.of();
		List<Finding> results = new ArrayList<>();
		for(var server : database.servers())
			if(key.equals(
				UiUtilsDupeDbDatabase.normalizeAddress(server.address())))
			{
				var e = exploit(server.exploitId());
				if(e == null)
					continue;
				List<String> details = new ArrayList<>(exploitDetails(e));
				details.add("Server evidence: " + server.source());
				details.add("Server reported: " + date(server.reportedAt()));
				details.add("Server patch status: " + (server.patched() == null
					? "not provided"
					: server.patched() ? "patched" : "reported not patched"));
				if(Boolean.TRUE.equals(server.patched()))
					details.add("Server patched: " + date(server.patchedAt()));
				results.add(new Finding(e.title(),
					UiUtilsDupeDbDatabase.safeUrl(e.url()),
					List.copyOf(details), server.patched()));
			}
		return List.copyOf(results);
	}
	
	private static UiUtilsDupeDbDatabase.Exploit exploit(String id)
	{
		return exploitsById.get(id);
	}
	
	private static List<String> exploitDetails(UiUtilsDupeDbDatabase.Exploit e)
	{
		List<String> details = new ArrayList<>();
		details.add("Submitted: " + date(e.dateSubmitted()));
		details.add("Published: " + date(e.datePublished()));
		if(e.dateDiscovered() != null)
			details.add("Discovered: " + date(e.dateDiscovered()));
		details.add("Exploit status: "
			+ Objects.requireNonNullElse(e.status(), "unknown"));
		details.add("Globally marked patched: "
			+ (e.patched() == null ? "unknown" : e.patched() ? "yes" : "no"));
		if(Boolean.TRUE.equals(e.patched()))
			details.add("Exploit patched: " + date(e.patchedAt()));
		return List.copyOf(details);
	}
	
	private static String date(String date)
	{
		return date == null || date.isBlank() ? "unavailable" : date;
	}
	
	public static final class VulnerableEntry
	{
		private final String displayName;
		private final Set<String> versions = new LinkedHashSet<>();
		
		private VulnerableEntry(String name)
		{
			displayName = name;
		}
		
		public String displayName()
		{
			return displayName;
		}
		
		public Set<String> versions()
		{
			return versions;
		}
	}
}
