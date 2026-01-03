/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"livestream detector", "livestream", "stream detector",
	"streaming", "twitch", "youtube", "tiktok", "kick"})
public final class LivestreamDetectorHack extends Hack implements UpdateListener
{
	private static final int TICKS_PER_MINUTE = 20 * 60;
	
	private final CheckboxSetting autoScanEnabled =
		new CheckboxSetting("Auto scan",
			"description.wurst.setting.livestreamdetector.auto_scan", true);
	private final SliderSetting scanIntervalMinutes =
		new SliderSetting("Scan interval (minutes)",
			"description.wurst.setting.livestreamdetector.scan_interval", 2, 1,
			30, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting scanOnlyJoin = new CheckboxSetting(
		"Scan only players who join",
		"description.wurst.setting.livestreamdetector.scan_only_join", false);
	private final SliderSetting maxPlayersSetting =
		new SliderSetting("Skip scan above (players)",
			"description.wurst.setting.livestreamdetector.skip_above_players",
			80, 0, 500, 10, ValueDisplay.INTEGER);
	private final ButtonSetting cancelScanButton =
		new ButtonSetting("Cancel active scan",
			"description.wurst.setting.livestreamdetector.cancel_scan",
			this::cancelScan);
	private final CheckboxSetting onlyShowLiveResults = new CheckboxSetting(
		"Only show live results",
		"description.wurst.setting.livestreamdetector.only_show_live_results",
		true);
	private final ButtonSetting manualScanAllButton =
		new ButtonSetting("Manual scan all",
			"description.wurst.setting.livestreamdetector.manual_scan_all",
			this::manualScanAll);
	
	private final TextFieldSetting youtubeApiKey =
		new TextFieldSetting("YouTube API key",
			"description.wurst.setting.livestreamdetector.youtube_api_key", "");
	
	private final TextFieldSetting twitchClientId = new TextFieldSetting(
		"Twitch client ID",
		"description.wurst.setting.livestreamdetector.twitch_client_id", "");
	
	private final TextFieldSetting twitchOAuthToken = new TextFieldSetting(
		"Twitch OAuth token",
		"description.wurst.setting.livestreamdetector.twitch_oauth_token", "");
	
	private final HttpClient httpClient =
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	
	private final AtomicBoolean scanInProgress = new AtomicBoolean(false);
	private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
	private final Map<String, EnumSet<Platform>> lastLiveByPlayer =
		new HashMap<>();
	private final Set<String> lastSeenPlayers = new HashSet<>();
	private final Object stateLock = new Object();
	
	private int ticksSinceScan;
	private boolean warnedLargeServer;
	private boolean wasConnected;
	
	public LivestreamDetectorHack()
	{
		super("LivestreamDetector");
		setCategory(Category.OTHER);
		addSetting(autoScanEnabled);
		addSetting(scanIntervalMinutes);
		addSetting(scanOnlyJoin);
		addSetting(maxPlayersSetting);
		addSetting(cancelScanButton);
		addSetting(onlyShowLiveResults);
		addSetting(manualScanAllButton);
		addSetting(youtubeApiKey);
		addSetting(twitchClientId);
		addSetting(twitchOAuthToken);
	}
	
	@Override
	protected void onEnable()
	{
		ticksSinceScan = 0;
		lastLiveByPlayer.clear();
		lastSeenPlayers.clear();
		warnedLargeServer = false;
		wasConnected = false;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		synchronized(stateLock)
		{
			lastLiveByPlayer.clear();
		}
		lastSeenPlayers.clear();
		scanInProgress.set(false);
		cancelRequested.set(false);
	}
	
	@Override
	public void onUpdate()
	{
		boolean connected = MC.getConnection() != null && MC.player != null;
		if(!connected)
		{
			wasConnected = false;
			lastSeenPlayers.clear();
			return;
		}
		
		if(!wasConnected)
		{
			wasConnected = true;
			ticksSinceScan = 0;
			PlayerSnapshot snapshot = snapshotPlayers();
			lastSeenPlayers.clear();
			lastSeenPlayers.addAll(snapshot.names);
			if(autoScanEnabled.isChecked() && !scanOnlyJoin.isChecked())
				startScanWithSnapshot(snapshot, false);
			return;
		}
		
		if(!autoScanEnabled.isChecked())
			return;
		
		if(scanOnlyJoin.isChecked())
		{
			PlayerSnapshot snapshot = snapshotPlayers();
			Set<String> newPlayers = new HashSet<>(snapshot.names);
			newPlayers.removeAll(lastSeenPlayers);
			lastSeenPlayers.clear();
			lastSeenPlayers.addAll(snapshot.names);
			if(!newPlayers.isEmpty())
				startScanWithNames(newPlayers, snapshot.onlineCount, false);
			return;
		}
		
		ticksSinceScan++;
		int intervalTicks =
			Math.max(1, scanIntervalMinutes.getValueI()) * TICKS_PER_MINUTE;
		if(ticksSinceScan < intervalTicks)
			return;
		
		ticksSinceScan = 0;
		PlayerSnapshot snapshot = snapshotPlayers();
		startScanWithSnapshot(snapshot, false);
	}
	
	private void startScanWithSnapshot(PlayerSnapshot snapshot, boolean manual)
	{
		startScanWithNames(snapshot.names, snapshot.onlineCount, manual);
	}
	
	private void startScanWithNames(Set<String> names, int onlineCount,
		boolean manual)
	{
		if(!scanInProgress.compareAndSet(false, true))
		{
			if(manual)
				postMessage("LivestreamDetector: scan already running.");
			return;
		}
		
		if(names.isEmpty())
		{
			scanInProgress.set(false);
			if(manual)
				postMessage("LivestreamDetector: no players to scan.");
			return;
		}
		
		int maxPlayers = maxPlayersSetting.getValueI();
		if(maxPlayers > 0 && onlineCount > maxPlayers)
		{
			if(manual)
			{
				postMessage("LivestreamDetector: scan skipped (" + onlineCount
					+ " players, limit " + maxPlayers + ").");
			}else if(!warnedLargeServer)
			{
				postMessage("LivestreamDetector: scan skipped (" + onlineCount
					+ " players, limit " + maxPlayers + ").");
				warnedLargeServer = true;
			}
			scanInProgress.set(false);
			return;
		}
		
		warnedLargeServer = false;
		
		Thread.ofVirtual().name("LivestreamDetector")
			.uncaughtExceptionHandler((t, e) -> e.printStackTrace())
			.start(() -> scanPlayers(names));
	}
	
	private void cancelScan()
	{
		if(scanInProgress.get())
		{
			cancelRequested.set(true);
			postMessage("LivestreamDetector: cancel requested.");
		}else
			postMessage("LivestreamDetector: no active scan.");
	}
	
	private void manualScanAll()
	{
		if(MC.getConnection() == null || MC.player == null)
		{
			postError("LivestreamDetector: not connected.");
			return;
		}
		
		PlayerSnapshot snapshot = snapshotPlayers();
		startScanWithSnapshot(snapshot, true);
	}
	
	public void manualCheck(String rawName)
	{
		String name = normalizeName(rawName);
		if(name == null)
		{
			postError("Usage: .livestream <username>");
			return;
		}
		
		Thread.ofVirtual().name("LivestreamDetector-Manual")
			.uncaughtExceptionHandler((t, e) -> e.printStackTrace())
			.start(() -> manualCheckInternal(name));
	}
	
	private void scanPlayers(Set<String> names)
	{
		try
		{
			synchronized(stateLock)
			{
				lastLiveByPlayer.keySet()
					.removeIf(name -> !names.contains(name));
			}
			
			for(String name : names)
			{
				if(cancelRequested.get())
				{
					postMessage("LivestreamDetector: scan cancelled.");
					return;
				}
				checkPlayer(name);
			}
			
		}catch(Exception e)
		{
			e.printStackTrace();
			
		}finally
		{
			scanInProgress.set(false);
			cancelRequested.set(false);
		}
	}
	
	private PlayerSnapshot snapshotPlayers()
	{
		Set<String> names = new HashSet<>();
		int onlineCount = 0;
		for(PlayerInfo info : MC.getConnection().getOnlinePlayers())
		{
			onlineCount++;
			if(info.getProfile() != null && info.getProfile().name() != null)
				names.add(info.getProfile().name());
		}
		
		String selfName = MC.player.getGameProfile().name();
		names.remove(selfName);
		
		return new PlayerSnapshot(names, onlineCount);
	}
	
	private void checkPlayer(String name)
	{
		EnumSet<Platform> previous;
		synchronized(stateLock)
		{
			previous = lastLiveByPlayer.getOrDefault(name,
				EnumSet.noneOf(Platform.class));
		}
		EnumSet<Platform> current = EnumSet.copyOf(previous);
		
		for(Platform platform : Platform.values())
		{
			LiveResult result = checkPlatform(platform, name);
			switch(result.status)
			{
				case LIVE ->
				{
					if(!previous.contains(platform))
						announceLive(name, platform, result.url);
					current.add(platform);
				}
				case OFFLINE ->
				{
					if(previous.contains(platform)
						&& !onlyShowLiveResults.isChecked())
						announceOffline(name, platform);
					current.remove(platform);
				}
				case UNKNOWN ->
				{
					// Keep previous state to avoid flapping on API errors.
				}
			}
		}
		
		synchronized(stateLock)
		{
			if(current.isEmpty())
				lastLiveByPlayer.remove(name);
			else
				lastLiveByPlayer.put(name, current);
		}
	}
	
	private void manualCheckInternal(String name)
	{
		postMessage("LivestreamDetector: checking " + name + "...");
		for(Platform platform : Platform.values())
		{
			LiveResult result = checkPlatform(platform, name);
			switch(result.status)
			{
				case LIVE ->
				{
					String url = result.url != null ? " - " + result.url : "";
					postMessage("LivestreamDetector: " + platform.label
						+ " LIVE" + url);
				}
				case OFFLINE -> postMessage(
					"LivestreamDetector: " + platform.label + " offline");
				case UNKNOWN -> postMessage(
					"LivestreamDetector: " + platform.label + " unknown");
			}
		}
	}
	
	private LiveResult checkPlatform(Platform platform, String name)
	{
		return switch(platform)
		{
			case YOUTUBE -> checkYouTube(name);
			case TWITCH -> checkTwitch(name);
			case TIKTOK -> checkTikTok(name);
			case KICK -> checkKick(name);
		};
	}
	
	private LiveResult checkYouTube(String name)
	{
		String key = youtubeApiKey.getValue().trim();
		if(key.isEmpty())
			return checkYouTubePublic(name);
		
		try
		{
			String query = URLEncoder.encode(name, StandardCharsets.UTF_8);
			String url = "https://www.googleapis.com/youtube/v3/search?part="
				+ "snippet&eventType=live&type=video&maxResults=1&q=" + query
				+ "&key=" + key;
			HttpResponse<String> response = httpGet(URI.create(url));
			if(response.statusCode() / 100 != 2)
				return LiveResult.unknown();
			
			JsonObject root =
				JsonParser.parseString(response.body()).getAsJsonObject();
			JsonArray items = root.getAsJsonArray("items");
			if(items == null || items.isEmpty())
				return LiveResult.offline();
			
			JsonObject first = items.get(0).getAsJsonObject();
			JsonObject id = first.getAsJsonObject("id");
			if(id == null || !id.has("videoId"))
				return LiveResult.live("https://www.youtube.com");
			
			String videoId = id.get("videoId").getAsString();
			return LiveResult
				.live("https://www.youtube.com/watch?v=" + videoId);
			
		}catch(Exception e)
		{
			return LiveResult.unknown();
		}
	}
	
	private LiveResult checkYouTubePublic(String name)
	{
		String url = "https://www.youtube.com/@" + name + "/live";
		try
		{
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent",
					"Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
				.header("Accept-Language", "en-US,en;q=0.9").GET().build();
			HttpResponse<String> response = httpClient.send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if(response.statusCode() == 404)
				return LiveResult.offline();
			if(response.statusCode() / 100 != 2)
				return LiveResult.unknown();
			
			return isYouTubeLive(response.body()) ? LiveResult.live(url)
				: LiveResult.offline();
			
		}catch(Exception e)
		{
			return LiveResult.unknown();
		}
	}
	
	private LiveResult checkTwitch(String name)
	{
		String clientId = twitchClientId.getValue().trim();
		String token = twitchOAuthToken.getValue().trim();
		if(clientId.isEmpty() || token.isEmpty())
			return checkTwitchPublic(name);
		
		try
		{
			String query = URLEncoder.encode(name, StandardCharsets.UTF_8);
			String url =
				"https://api.twitch.tv/helix/streams?user_login=" + query;
			String authHeader =
				token.toLowerCase(Locale.ROOT).startsWith("bearer ") ? token
					: "Bearer " + token;
			
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10)).header("Client-Id", clientId)
				.header("Authorization", authHeader).GET().build();
			HttpResponse<String> response = httpClient.send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if(response.statusCode() / 100 != 2)
				return LiveResult.unknown();
			
			JsonObject root =
				JsonParser.parseString(response.body()).getAsJsonObject();
			JsonArray data = root.getAsJsonArray("data");
			if(data == null || data.isEmpty())
				return LiveResult.offline();
			
			return LiveResult.live("https://www.twitch.tv/" + name);
			
		}catch(Exception e)
		{
			return LiveResult.unknown();
		}
	}
	
	private LiveResult checkTwitchPublic(String name)
	{
		String url = "https://www.twitch.tv/" + name;
		try
		{
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent",
					"Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
				.header("Accept-Language", "en-US,en;q=0.9").GET().build();
			HttpResponse<String> response = httpClient.send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if(response.statusCode() == 404)
				return LiveResult.offline();
			if(response.statusCode() / 100 != 2)
				return LiveResult.unknown();
			
			return isTwitchLive(response.body()) ? LiveResult.live(url)
				: LiveResult.offline();
			
		}catch(Exception e)
		{
			return LiveResult.unknown();
		}
	}
	
	private LiveResult checkTikTok(String name)
	{
		String url = "https://www.tiktok.com/@" + name + "/live";
		try
		{
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent",
					"Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
				.header("Accept-Language", "en-US,en;q=0.9").GET().build();
			HttpResponse<String> response = httpClient.send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if(response.statusCode() == 404)
				return LiveResult.offline();
			if(response.statusCode() / 100 != 2)
				return LiveResult.unknown();
			
			if(isTikTokLive(response.body()))
				return LiveResult.live(url);
			
			return LiveResult.offline();
			
		}catch(Exception e)
		{
			return LiveResult.unknown();
		}
	}
	
	private LiveResult checkKick(String name)
	{
		try
		{
			String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)
				.replace("+", "%20");
			String url = "https://kick.com/api/v2/channels/" + encoded;
			HttpResponse<String> response = httpGet(URI.create(url));
			if(response.statusCode() == 404)
				return LiveResult.offline();
			if(response.statusCode() / 100 != 2)
				return LiveResult.unknown();
			
			JsonObject root =
				JsonParser.parseString(response.body()).getAsJsonObject();
			JsonElement livestream = root.get("livestream");
			if(livestream == null || livestream.isJsonNull())
				return LiveResult.offline();
			
			if(livestream.isJsonObject()
				&& livestream.getAsJsonObject().has("is_live"))
			{
				boolean live =
					livestream.getAsJsonObject().get("is_live").getAsBoolean();
				if(!live)
					return LiveResult.offline();
			}
			
			return LiveResult.live("https://kick.com/" + name);
			
		}catch(Exception e)
		{
			return LiveResult.unknown();
		}
	}
	
	private HttpResponse<String> httpGet(URI uri) throws Exception
	{
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(10)).GET().build();
		return httpClient.send(request,
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}
	
	private boolean isTikTokLive(String body)
	{
		if(body == null || body.isEmpty())
			return false;
		
		if(body.contains("\"isLive\":true")
			|| body.contains("\"is_live\":true"))
			return true;
		
		if(body.contains("\"liveRoomId\"") && !body.contains("\"liveRoomId\":0")
			&& !body.contains("\"liveRoomId\":null"))
			return true;
		
		return false;
	}
	
	private boolean isYouTubeLive(String body)
	{
		if(body == null || body.isEmpty())
			return false;
		
		if(body.contains("\"isLiveNow\":true"))
			return true;
		
		if(body.contains("\"isLive\":true")
			&& body.contains("\"liveStreamability\""))
			return true;
		
		if(body.contains("LIVE NOW") || body.contains("Live now"))
			return true;
		
		return false;
	}
	
	private boolean isTwitchLive(String body)
	{
		if(body == null || body.isEmpty())
			return false;
		
		if(body.contains("\"isLiveBroadcast\":true"))
			return true;
		
		if(body.contains("\"stream\"") && body.contains("\"type\":\"live\""))
			return true;
		
		if(body.contains("\"isLive\":true"))
			return true;
		
		return false;
	}
	
	private void announceLive(String name, Platform platform, String url)
	{
		StringBuilder message = new StringBuilder("LivestreamDetector: ")
			.append(name).append(" is live on ").append(platform.label);
		if(url != null && !url.isEmpty())
			message.append(" - ").append(url);
		
		postMessage(message.toString());
	}
	
	private void announceOffline(String name, Platform platform)
	{
		postMessage("LivestreamDetector: " + name + " is no longer live on "
			+ platform.label);
	}
	
	private enum Platform
	{
		YOUTUBE("YouTube"),
		TWITCH("Twitch"),
		TIKTOK("TikTok"),
		KICK("Kick");
		
		private final String label;
		
		Platform(String label)
		{
			this.label = label;
		}
	}
	
	private void postMessage(String message)
	{
		MC.execute(() -> ChatUtils.message(message));
	}
	
	private void postError(String message)
	{
		MC.execute(() -> ChatUtils.error(message));
	}
	
	private String normalizeName(String rawName)
	{
		if(rawName == null)
			return null;
		
		String name = rawName.trim();
		if(name.isEmpty())
			return null;
		
		if(name.startsWith("@"))
			name = name.substring(1);
		
		return name.isEmpty() ? null : name;
	}
	
	private enum Status
	{
		LIVE,
		OFFLINE,
		UNKNOWN
	}
	
	private static final class LiveResult
	{
		private final Status status;
		private final String url;
		
		private LiveResult(Status status, String url)
		{
			this.status = status;
			this.url = url;
		}
		
		private static LiveResult live(String url)
		{
			return new LiveResult(Status.LIVE, url);
		}
		
		private static LiveResult offline()
		{
			return new LiveResult(Status.OFFLINE, null);
		}
		
		private static LiveResult unknown()
		{
			return new LiveResult(Status.UNKNOWN, null);
		}
	}
	
	private static final class PlayerSnapshot
	{
		private final Set<String> names;
		private final int onlineCount;
		
		private PlayerSnapshot(Set<String> names, int onlineCount)
		{
			this.names = names;
			this.onlineCount = onlineCount;
		}
	}
}
