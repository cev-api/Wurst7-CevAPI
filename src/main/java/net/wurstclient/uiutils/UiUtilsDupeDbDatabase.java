/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import com.google.gson.*;
import java.net.URI;
import java.util.*;

/**
 * Versioned, credential-free cache of exploit metadata and server sightings.
 */
public final class UiUtilsDupeDbDatabase
{
	private UiUtilsDupeDbDatabase()
	{}
	
	public record Plugin(String name, String version, String exploitId,
		String dateSubmitted, String dateDiscovered, String datePublished,
		String status, Boolean patched, String patchedAt, String url)
	{}
	
	public record Exploit(String id, String title, String type, String status,
		String dateSubmitted, String dateDiscovered, String datePublished,
		Boolean patched, String patchedAt, String url)
	{}
	
	public record Server(String address, String exploitId, String source,
		String reportedAt, Boolean patched, String patchedAt, String sightingId)
	{}
	
	public record Data(int schemaVersion, String source, String updatedAt,
		List<Plugin> plugins, List<Exploit> exploits, List<Server> servers)
	{}
	
	private static final Gson GSON =
		new GsonBuilder().serializeNulls().setPrettyPrinting().create();
	
	public static Data empty()
	{
		return new Data(2, "DupeDB", null, List.of(), List.of(), List.of());
	}
	
	public static String json(Data data)
	{
		return GSON.toJson(data);
	}
	
	public static Data read(JsonElement json)
	{
		if(json == null || json.isJsonNull())
			return empty();
		if(json.isJsonArray())
		{
			List<Plugin> plugins = new ArrayList<>();
			for(JsonElement value : json.getAsJsonArray())
				if(value.isJsonObject())
				{
					JsonObject o = value.getAsJsonObject();
					String name = text(o, "name"), version = text(o, "version");
					if(name != null)
						plugins.add(new Plugin(name, version, null,
							text(o, "dateSubmitted", "date_submitted"),
							text(o, "dateDiscovered"), text(o, "datePublished"),
							text(o, "status"), flag(o, "patched"),
							text(o, "patchedAt", "marked_patched_at"),
							safeUrl(text(o, "url"))));
				}
			return new Data(2, "legacy", null, List.copyOf(plugins), List.of(),
				List.of());
		}
		if(!json.isJsonObject())
			throw new JsonParseException("Invalid DupeDB cache");
		JsonObject root = json.getAsJsonObject();
		if(!root.has("schemaVersion")
			|| root.get("schemaVersion").getAsInt() != 2)
			throw new JsonParseException("Unsupported DupeDB cache version");
		Data data = GSON.fromJson(root, Data.class);
		if(data.plugins() == null || data.exploits() == null
			|| data.servers() == null)
			throw new JsonParseException("Incomplete DupeDB cache");
		// Validate before publishing; a damaged cache must not replace a usable
		// database.
		for(Plugin p : data.plugins())
			if(p == null || p.name() == null)
				throw new JsonParseException("Invalid plugin record");
		for(Exploit e : data.exploits())
			if(e == null || e.id() == null || e.title() == null)
				throw new JsonParseException("Invalid exploit record");
		for(Server s : data.servers())
			if(s == null || s.address() == null || s.exploitId() == null)
				throw new JsonParseException("Invalid server record");
		return data;
	}
	
	public static Data fromApi(JsonArray cards, JsonArray sightings)
	{
		Map<String, Exploit> exploits = new LinkedHashMap<>();
		Map<String, Plugin> plugins = new LinkedHashMap<>();
		Map<String, Server> servers = new LinkedHashMap<>();
		for(JsonElement value : cards)
			if(value.isJsonObject())
			{
				JsonObject o = value.getAsJsonObject();
				String id = text(o, "id");
				if(id == null)
					continue;
				String patchDate = text(o, "marked_patched_at", "patched_at");
				String status = text(o, "status");
				Exploit e = new Exploit(id,
					Objects.requireNonNullElse(text(o, "name"), id),
					text(o, "type"), status, text(o, "date_submitted"),
					text(o, "date_discovered"), text(o, "published_at"),
					"patched".equalsIgnoreCase(status) || patchDate != null,
					patchDate, Objects.requireNonNullElse(
						safeUrl(text(o, "url")), exploitUrl(id)));
				exploits.put(id, e);
				addPlugin(text(o, "plugin_name"), text(o, "plugin_version"), e,
					plugins);
				extractPlugins(o.get("plugins"), e, plugins);
				extractAddresses(o.get("server_ips"), e, "submission", servers);
				extractAddresses(o.get("sighted_server_ips"), e,
					"verified sighting", servers);
				extractAddresses(o.get("sighted_ips"), e, "verified sighting",
					servers);
			}
		for(JsonElement value : sightings)
			if(value.isJsonObject())
			{
				JsonObject o = value.getAsJsonObject();
				String id = text(o, "exploit_id"),
					address = text(o, "server_ip", "ip");
				if(id == null || address == null)
					continue;
				exploits.putIfAbsent(id, new Exploit(id,
					Objects.requireNonNullElse(text(o, "exploit_name"), id),
					null, null, null, null, null, null, null, exploitUrl(id)));
				Server server = new Server(normalizeAddress(address), id,
					"verified sighting",
					text(o, "created_at", "date_submitted"),
					flag(o, "is_patched"), text(o, "patched_at"),
					text(o, "id"));
				servers.put(serverKey(server), server);
			}
		Set<String> detailed = new HashSet<>();
		for(Server s : servers.values())
			if(s.sightingId() != null)
				detailed.add(s.address() + "|" + s.exploitId());
		servers.values()
			.removeIf(s -> "verified sighting".equals(s.source())
				&& s.sightingId() == null
				&& detailed.contains(s.address() + "|" + s.exploitId()));
		return new Data(2, "DupeDB", java.time.Instant.now().toString(),
			List.copyOf(plugins.values()), List.copyOf(exploits.values()),
			List.copyOf(servers.values()));
	}
	
	private static void extractPlugins(JsonElement element, Exploit e,
		Map<String, Plugin> found)
	{
		element = decode(element);
		if(element == null || element.isJsonNull())
			return;
		if(element.isJsonArray())
		{
			for(JsonElement child : element.getAsJsonArray())
				extractPlugins(child, e, found);
		}else if(element.isJsonObject())
		{
			JsonObject o = element.getAsJsonObject();
			addPlugin(text(o, "name", "plugin", "plugin_name", "pluginName"),
				text(o, "version", "plugin_version", "pluginVersion"), e,
				found);
			for(JsonElement child : o.asMap().values())
				if(child.isJsonArray() || child.isJsonObject())
					extractPlugins(child, e, found);
		}
	}
	
	private static void addPlugin(String name, String version, Exploit e,
		Map<String, Plugin> found)
	{
		if(name == null)
			return;
		Plugin p = new Plugin(name, version, e.id(), e.dateSubmitted(),
			e.dateDiscovered(), e.datePublished(), e.status(), e.patched(),
			e.patchedAt(), e.url());
		found.put(pluginKey(p), p);
	}
	
	private static void extractAddresses(JsonElement element, Exploit e,
		String source, Map<String, Server> found)
	{
		element = decode(element);
		if(element == null || element.isJsonNull())
			return;
		if(element.isJsonArray())
		{
			for(JsonElement v : element.getAsJsonArray())
				extractAddresses(v, e, source, found);
		}else
		{
			String address = element.isJsonPrimitive() ? element.getAsString()
				: element.isJsonObject() ? text(element.getAsJsonObject(), "ip",
					"server_ip", "address") : null;
			if(address == null || address.isBlank())
				return;
			Server s = new Server(normalizeAddress(address), e.id(), source,
				"submission".equals(source) ? e.dateSubmitted() : null, null,
				null, null);
			found.putIfAbsent(serverKey(s), s);
		}
	}
	
	private static JsonElement decode(JsonElement e)
	{
		if(e != null && e.isJsonPrimitive()
			&& e.getAsJsonPrimitive().isString())
		{
			String s = e.getAsString().trim();
			if(s.startsWith("[") || s.startsWith("{"))
				try
				{
					return JsonParser.parseString(s);
				}catch(JsonParseException ignored)
				{}
		}
		return e;
	}
	
	public static String text(JsonObject o, String... keys)
	{
		for(String key : keys)
		{
			JsonElement v = o.get(key);
			if(v != null && v.isJsonPrimitive())
			{
				String s = v.getAsString().trim();
				if(!s.isEmpty())
					return s;
			}
		}
		return null;
	}
	
	private static Boolean flag(JsonObject o, String key)
	{
		JsonElement v = o.get(key);
		if(v == null || v.isJsonNull() || !v.isJsonPrimitive())
			return null;
		if(v.getAsJsonPrimitive().isBoolean())
			return v.getAsBoolean();
		String s = v.getAsString();
		return "1".equals(s) || "true".equalsIgnoreCase(s) ? true
			: "0".equals(s) || "false".equalsIgnoreCase(s) ? false : null;
	}
	
	public static String normalizeAddress(String raw)
	{
		if(raw == null)
			return "";
		String address = raw.trim().toLowerCase(Locale.ROOT);
		if(address.endsWith(":25565"))
			address = address.substring(0, address.length() - 6);
		if(address.startsWith("[") && address.endsWith("]"))
			address = address.substring(1, address.length() - 1);
		if(address.endsWith("."))
			address = address.substring(0, address.length() - 1);
		return address;
	}
	
	public static String safeUrl(String raw)
	{
		if(raw == null)
			return null;
		try
		{
			URI uri = URI.create(raw);
			return ("https".equalsIgnoreCase(uri.getScheme())
				|| "http".equalsIgnoreCase(uri.getScheme()))
				&& uri.getHost() != null ? uri.toString() : null;
		}catch(IllegalArgumentException e)
		{
			return null;
		}
	}
	
	public static String exploitUrl(String id)
	{
		return id != null && id.matches("[A-Za-z0-9_-]+")
			? "https://dupedb.net/exploit/" + id : null;
	}
	
	private static String pluginKey(Plugin p)
	{
		return UiUtilsVulnerablePlugins.normalizeKey(p.name()) + "|"
			+ Objects.toString(p.version(), "") + "|"
			+ Objects.toString(p.exploitId(), "");
	}
	
	private static String serverKey(Server s)
	{
		return s.address() + "|" + s.exploitId() + "|" + s.source() + "|"
			+ Objects.toString(s.sightingId(), "");
	}
	
	public static Data merge(Data base, Data update, boolean replaceOnline)
	{
		Map<String, Plugin> plugins = new LinkedHashMap<>();
		Map<String, Exploit> exploits = new LinkedHashMap<>();
		Map<String, Server> servers = new LinkedHashMap<>();
		Set<String> updatedIds = new HashSet<>();
		for(Exploit e : update.exploits())
			updatedIds.add(e.id());
		for(Plugin p : base.plugins())
			if(!replaceOnline || p.exploitId() == null
				|| !updatedIds.contains(p.exploitId()))
				plugins.put(pluginKey(p), p);
		for(Exploit e : base.exploits())
			exploits.put(e.id(), e);
		for(Server s : base.servers())
			servers.put(serverKey(s), s);
		for(Plugin p : update.plugins())
			plugins.put(pluginKey(p), p);
		for(Exploit e : update.exploits())
		{
			Exploit old = exploits.get(e.id());
			if(old != null && e.datePublished() == null)
				e = new Exploit(e.id(), e.title(), e.type(), e.status(),
					e.dateSubmitted(),
					e.dateDiscovered() == null ? old.dateDiscovered()
						: e.dateDiscovered(),
					old.datePublished(), e.patched(), e.patchedAt(), e.url());
			exploits.put(e.id(), e);
		}
		for(Server s : update.servers())
			servers.put(serverKey(s), s);
		return new Data(2, "DupeDB", update.updatedAt(),
			List.copyOf(plugins.values()), List.copyOf(exploits.values()),
			List.copyOf(servers.values()));
	}
}
