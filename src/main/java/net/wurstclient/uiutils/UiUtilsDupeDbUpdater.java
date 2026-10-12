/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Updates the full metadata cache, or looks up an explicitly opted-in server
 * address.
 */
public final class UiUtilsDupeDbUpdater
{
	private static final String API = "https://dupedb.net/api";
	private static final int MAX_PAGES = 10000, MAX_RATE_LIMIT_RETRIES = 4;
	private static final AtomicBoolean STARTED = new AtomicBoolean(),
		RUNNING = new AtomicBoolean(), CANCELLED = new AtomicBoolean();
	private static final Object UPDATE_LOCK = new Object();
	private static volatile Thread worker;
	private static volatile boolean finished = true;
	private static volatile String status = "Not refreshed", activeServer;
	private static final Queue<String> CHAT_MESSAGES =
		new ConcurrentLinkedQueue<>();
	private static final Map<String, Long> lookupAttempts =
		new ConcurrentHashMap<>();
	private static final Set<String> stoppedLookups =
		ConcurrentHashMap.newKeySet();
	private static final Map<String, String> lookupStatuses =
		new ConcurrentHashMap<>();
	private static long nextRequestAt;
	private static final ExecutorService EXECUTOR =
		Executors.newSingleThreadExecutor(task -> {
			Thread t = new Thread(task, "DupeDB refresh");
			t.setDaemon(true);
			return t;
		});
	
	private UiUtilsDupeDbUpdater()
	{}
	
	public static void startOnce(String key)
	{
		if(!UiUtilsSettings.get().dupeDbEnabled
			|| !UiUtilsSettings.get().dupeDbScanOnLaunch || key == null
			|| key.isBlank() || !STARTED.compareAndSet(false, true))
			return;
		refreshNow(key);
	}
	
	public static String getStatus()
	{
		return status;
	}
	
	public static boolean isActive()
	{
		return RUNNING.get() && !finished;
	}
	
	public static boolean isServerLookupActive()
	{
		return isActive() && activeServer != null;
	}
	
	public static String currentServer()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null || mc.player == null || mc.getConnection() == null
			|| mc.getCurrentServer() == null)
			return "";
		return UiUtilsDupeDbDatabase.normalizeAddress(mc.getCurrentServer().ip);
	}
	
	public static String serverLookupStatus(String address)
	{
		return lookupStatuses.getOrDefault(
			UiUtilsDupeDbDatabase.normalizeAddress(address),
			"Using cached DupeDB server reports");
	}
	
	public static void onClientTick(Minecraft mc)
	{
		String address = currentServer();
		if(isServerLookupActive() && (!UiUtilsSettings.get().dupeDbCheckServerIp
			|| !UiUtilsSettings.get().dupeDbEnabled
			|| !Objects.equals(address, activeServer)))
			stopScan();
		if(!address.isEmpty() && UiUtilsSettings.get().dupeDbEnabled
			&& UiUtilsSettings.get().dupeDbCheckServerIp && !RUNNING.get()
			&& !stoppedLookups.contains(address) && System.currentTimeMillis()
				- lookupAttempts.getOrDefault(address, 0L) > 15 * 60 * 1000L)
			startJob(UiUtilsSettings.get().dupeDbApiKey, address, false);
		if(mc == null || mc.player == null)
			return;
		String message;
		while((message = CHAT_MESSAGES.poll()) != null)
			mc.player.displayClientMessage(
				Component.literal("[DupeDB] " + message), false);
	}
	
	private static void announce(String message)
	{
		CHAT_MESSAGES.add(message);
	}
	
	public static void toggleRefresh()
	{
		if(isActive())
			stopScan();
		else
			refreshNow(UiUtilsSettings.get().dupeDbApiKey);
	}
	
	public static void toggleServerLookup()
	{
		if(isServerLookupActive())
			stopScan();
		else
			checkCurrentServerNow();
	}
	
	public static void checkCurrentServerNow()
	{
		String address = currentServer();
		if(address.isEmpty())
		{
			announce("Join a multiplayer server before checking its address.");
			return;
		}
		if(!UiUtilsSettings.get().dupeDbCheckServerIp)
		{
			announce(
				"Enable Check current server IP in DupeDB settings first.");
			return;
		}
		startJob(UiUtilsSettings.get().dupeDbApiKey, address, true);
	}
	
	public static void refreshNow(String key)
	{
		startJob(key, null, true);
	}
	
	private static void startJob(String key, String server,
		boolean reportInvalid)
	{
		synchronized(UPDATE_LOCK)
		{
			if(RUNNING.get())
				return;
			if(server != null && reportInvalid)
				stoppedLookups.remove(server);
			if(!UiUtilsSettings.get().dupeDbEnabled)
			{
				if(reportInvalid)
					announce("DupeDB is disabled");
				return;
			}
			String token = key == null ? "" : key.trim();
			if(!token.matches("dupe_pat_[a-fA-F0-9]{64}"))
			{
				if(server != null)
					lookupAttempts.put(server, System.currentTimeMillis());
				status = "Enter a valid DupeDB API key";
				if(reportInvalid)
					announce(status);
				return;
			}
			CANCELLED.set(false);
			finished = false;
			activeServer = server;
			RUNNING.set(true);
			status = server == null ? "Checking database for updates..."
				: "Checking DupeDB for " + server + "...";
			if(server != null)
			{
				lookupAttempts.put(server, System.currentTimeMillis());
				lookupStatuses.put(server, status);
			}
			announce(status);
			EXECUTOR.execute(() -> refresh(token, server));
		}
	}
	
	public static void stopScan()
	{
		synchronized(UPDATE_LOCK)
		{
			if(!isActive())
				return;
			CANCELLED.set(true);
			if(activeServer != null)
				stoppedLookups.add(activeServer);
			status = "Stopping DupeDB scan...";
			if(worker != null)
				worker.interrupt();
		}
	}
	
	private static void checkCancelled() throws InterruptedException
	{
		if(CANCELLED.get() || Thread.currentThread().isInterrupted())
			throw new InterruptedException("DupeDB scan stopped");
	}
	
	private static void refresh(String key, String server)
	{
		worker = Thread.currentThread();
		try
		{
			checkCancelled();
			UiUtilsDupeDbDatabase.Data data = fetchAll(key, server);
			if(server == null && data.exploits().isEmpty())
				throw new IOException("Empty DupeDB database");
			synchronized(UPDATE_LOCK)
			{
				checkCancelled();
				if(!UiUtilsSettings.get().dupeDbEnabled || server != null
					&& !UiUtilsSettings.get().dupeDbCheckServerIp)
					throw new InterruptedException("DupeDB disabled");
				String summary = updateSummary(data);
				if(server != null)
				{
					data = UiUtilsDupeDbDatabase
						.merge(UiUtilsVulnerablePlugins.database(), data, true);
					long hits = data.servers().stream()
						.filter(s -> server.equals(s.address()))
						.map(UiUtilsDupeDbDatabase.Server::exploitId).distinct()
						.count();
					status = server + ": " + hits + " matching exploit(s). "
						+ "Online check confirmed " + lastResponseCount
						+ "; local database updated.";
					lookupStatuses.put(server, status);
				}else
					status = summary;
				writeAndReload(data);
				announce(status);
				finished = true;
			}
		}catch(Exception e)
		{
			if(CANCELLED.get() || e instanceof InterruptedException)
				status = "DupeDB scan stopped; database unchanged.";
			else
			{
				String message = e.getMessage();
				status = message != null
					&& message.matches("DupeDB returned HTTP [0-9]{3}")
						? message + "; keeping current database"
						: "DupeDB scan failed; keeping current database";
				UiUtils.LOGGER.warn("{} ({})", status,
					e.getClass().getSimpleName());
			}
			if(server != null)
				lookupStatuses.put(server, status);
			announce(status);
		}finally
		{
			synchronized(UPDATE_LOCK)
			{
				worker = null;
				activeServer = null;
				finished = true;
				Thread.interrupted();
				RUNNING.set(false);
			}
		}
	}
	
	private static long lastResponseCount;
	
	private static String updateSummary(UiUtilsDupeDbDatabase.Data data)
	{
		var old = UiUtilsVulnerablePlugins.database();
		Set<String> pairs = new HashSet<>(), ids = new HashSet<>(),
			servers = new HashSet<>();
		for(var p : old.plugins())
			pairs.add(UiUtilsVulnerablePlugins.normalizeKey(p.name()) + "|"
				+ Objects.toString(p.version(), "").toLowerCase(Locale.ROOT));
		for(var e : old.exploits())
			ids.add(e.id());
		for(var s : old.servers())
			servers
				.add(s.address() + "|" + s.exploitId() + "|" + s.sightingId());
		int newPairs = 0, newExploits = 0, newServers = 0;
		for(var p : data.plugins())
			if(pairs.add(UiUtilsVulnerablePlugins.normalizeKey(p.name()) + "|"
				+ Objects.toString(p.version(), "").toLowerCase(Locale.ROOT)))
				newPairs++;
		for(var e : data.exploits())
			if(ids.add(e.id()))
				newExploits++;
		for(var s : data.servers())
			if(servers
				.add(s.address() + "|" + s.exploitId() + "|" + s.sightingId()))
				newServers++;
		return "Database checked: added " + newPairs
			+ " plugin/version entries, " + newExploits + " exploits and "
			+ newServers + " server records; refreshed dates and patch status.";
	}
	
	private static UiUtilsDupeDbDatabase.Data fetchAll(String key,
		String server) throws Exception
	{
		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15)).build();
		Set<String> aliases = new LinkedHashSet<>();
		if(server != null)
		{
			aliases.add(server);
			if(!server.contains(":"))
				aliases.add(server + ":25565");
			else if(!server.startsWith("[")
				&& server.chars().filter(c -> c == ':').count() > 1)
			{
				aliases.add("[" + server + "]");
				aliases.add("[" + server + "]:25565");
			}
		}
		String filter = server == null ? "" : "&serverIp=" + URLEncoder
			.encode(String.join(",", aliases), StandardCharsets.UTF_8);
		JsonArray exploits =
			fetchPages(client, "/exploits/search", filter, "exploits", key);
		JsonArray sightings =
			fetchPages(client, "/sightings/search", filter, "sightings", key);
		if(server != null)
		{
			// Exact API filters can miss differently cased stored hosts. Search
			// broadly,
			// then verify normalized addresses locally rather than trusting
			// fuzzy matches.
			String query =
				"&q=" + URLEncoder.encode(server, StandardCharsets.UTF_8);
			exploits.addAll(fetchPages(client, "/exploits/search",
				query + "&scope=serverip", "exploits", key));
			sightings.addAll(fetchPages(client, "/sightings/search", query,
				"sightings", key));
			var candidates = UiUtilsDupeDbDatabase.fromApi(exploits, sightings);
			Set<String> matchedIds = new HashSet<>();
			for(var record : candidates.servers())
				if(server.equals(record.address()))
					matchedIds.add(record.exploitId());
			Map<String, JsonElement> matchedCards = new LinkedHashMap<>();
			for(JsonElement card : exploits)
				if(card.isJsonObject())
				{
					String id = UiUtilsDupeDbDatabase
						.text(card.getAsJsonObject(), "id");
					if(matchedIds.contains(id))
						matchedCards.put(id, card);
				}
			exploits = new JsonArray();
			for(JsonElement card : matchedCards.values())
				exploits.add(card);
			JsonArray matchedSightings = new JsonArray();
			for(JsonElement sighting : sightings)
				if(sighting.isJsonObject() && server.equals(
					UiUtilsDupeDbDatabase.normalizeAddress(UiUtilsDupeDbDatabase
						.text(sighting.getAsJsonObject(), "server_ip", "ip"))))
					matchedSightings.add(sighting);
			sightings = matchedSightings;
		}
		Map<String, UiUtilsDupeDbDatabase.Exploit> known = new HashMap<>();
		for(var e : UiUtilsVulnerablePlugins.database().exploits())
			known.put(e.id(), e);
		for(int i = 0; i < exploits.size(); i++)
			if(exploits.get(i).isJsonObject())
			{
				JsonObject card = exploits.get(i).getAsJsonObject();
				String id = UiUtilsDupeDbDatabase.text(card, "id");
				if(id == null)
					continue;
				var old = known.get(id);
				if(old == null && id.matches("[A-Za-z0-9_-]+"))
				{
					JsonObject detail = requestJson(client,
						URI.create(API + "/exploits/" + id), key);
					for(var entry : detail.entrySet())
						card.add(entry.getKey(), entry.getValue());
				}else if(old != null)
				{
					// Search cards omit publication/discovery dates; preserve
					// previously fetched immutable metadata.
					if(UiUtilsDupeDbDatabase.text(card, "published_at") == null
						&& old.datePublished() != null)
						card.addProperty("published_at", old.datePublished());
					if(UiUtilsDupeDbDatabase.text(card,
						"date_discovered") == null
						&& old.dateDiscovered() != null)
						card.addProperty("date_discovered",
							old.dateDiscovered());
				}
			}
		Set<String> ids = new HashSet<>();
		for(JsonElement e : exploits)
			if(e.isJsonObject())
				ids.add(UiUtilsDupeDbDatabase.text(e.getAsJsonObject(), "id"));
		for(JsonElement s : sightings)
			if(s.isJsonObject())
			{
				String id = UiUtilsDupeDbDatabase.text(s.getAsJsonObject(),
					"exploit_id");
				if(id != null && id.matches("[A-Za-z0-9_-]+") && ids.add(id))
				{
					JsonObject detail = requestJson(client,
						URI.create(API + "/exploits/" + id), key);
					exploits.add(detail);
				}
			}
		var data = UiUtilsDupeDbDatabase.fromApi(exploits, sightings);
		lastResponseCount = server == null ? data.exploits().size()
			: data.servers().stream().filter(s -> server.equals(s.address()))
				.map(UiUtilsDupeDbDatabase.Server::exploitId).distinct()
				.count();
		return data;
	}
	
	private static JsonArray fetchPages(HttpClient client, String endpoint,
		String filter, String field, String key) throws Exception
	{
		JsonArray all = new JsonArray();
		for(int page = 1; page <= MAX_PAGES; page++)
		{
			checkCancelled();
			JsonObject data = requestJson(client,
				URI.create(API + endpoint + "?page=" + page
					+ "&limit=100&sort=date_submitted&order=desc" + filter),
				key);
			JsonElement list = data.get(field);
			if(list == null || !list.isJsonArray())
				throw new IOException("Invalid DupeDB response");
			JsonObject envelope =
				data.has("pagination") && data.get("pagination").isJsonObject()
					? data.getAsJsonObject("pagination") : data;
			if(!envelope.has("hasMore"))
				throw new IOException("Missing DupeDB pagination");
			boolean more = envelope.get("hasMore").getAsBoolean();
			if(more && list.getAsJsonArray().isEmpty())
				throw new IOException("Incomplete DupeDB pagination");
			all.addAll(list.getAsJsonArray());
			if(!more)
				return all;
		}
		throw new IOException("DupeDB page limit exceeded");
	}
	
	private static JsonObject requestJson(HttpClient client, URI uri,
		String key) throws Exception
	{
		for(int retry = 0;; retry++)
		{
			checkCancelled();
			long delay = nextRequestAt - System.nanoTime();
			if(delay > 0)
				TimeUnit.NANOSECONDS.sleep(delay);
			nextRequestAt =
				System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2100);
			HttpRequest request =
				HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
					.header("Authorization", "Bearer " + key)
					.header("Accept", "application/json")
					.header("User-Agent", "UI-Utils-DupeDB-Updater/2.0").GET()
					.build();
			HttpResponse<String> response = client.send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if(response.statusCode() == 429 && retry < MAX_RATE_LIMIT_RETRIES)
			{
				long wait = 5;
				try
				{
					wait = Long.parseLong(response.headers()
						.firstValue("Retry-After").orElse(response.headers()
							.firstValue("RateLimit-Reset").orElse("5")));
				}catch(NumberFormatException ignored)
				{}
				Thread.sleep(Math.max(1, Math.min(60, wait)) * 1000L);
				continue;
			}
			if(response.statusCode() < 200 || response.statusCode() >= 300)
				throw new IOException(
					"DupeDB returned HTTP " + response.statusCode());
			JsonElement result = JsonParser.parseString(response.body());
			if(!result.isJsonObject())
				throw new IOException("Invalid DupeDB JSON");
			return result.getAsJsonObject();
		}
	}
	
	private static Path cachePath()
	{
		return FabricLoader.getInstance().getConfigDir()
			.resolve("ui-utils-dupedb-plugins.json");
	}
	
	private static void writeAndReload(UiUtilsDupeDbDatabase.Data data)
		throws IOException
	{
		Path target = cachePath();
		Files.createDirectories(target.getParent());
		Path temp = Files.createTempFile(target.getParent(),
			target.getFileName().toString(), ".tmp");
		try
		{
			Files.writeString(temp, UiUtilsDupeDbDatabase.json(data),
				StandardCharsets.UTF_8);
			try
			{
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			}catch(AtomicMoveNotSupportedException e)
			{
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}finally
		{
			Files.deleteIfExists(temp);
		}
		UiUtilsVulnerablePlugins.reload();
	}
}
