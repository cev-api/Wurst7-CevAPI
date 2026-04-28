/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.serverfinder;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.glfw.GLFW;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerData.Type;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.util.MathUtils;

public class ServerFinderScreen extends Screen
{
	private static final int TARGET_LIMIT = 65536;
	private static final int WHITELIST_VERIFY_THREADS = 1;
	private static final String DEFAULT_SERVER_PREFIX = "Found Server #";
	
	private final JoinMultiplayerScreen prevScreen;
	
	private EditBox ipBox;
	private EditBox prefixBox;
	private EditBox versionFilterBox;
	private EditBox maxThreadsBox;
	private Button searchButton;
	private Button ignoreWhitelistButton;
	private Button retryUnverifiedButton;
	private Button backButton;
	
	private boolean ignoreWhitelisted = true;
	
	private ServerFinderState state;
	private int maxThreads;
	private int checked;
	private int working;
	private int totalTargets;
	private int onlineFound;
	private int offlineMode;
	private int whitelisted;
	private int ignoredWhitelisted;
	private int ignoredUnverified;
	private int versionFiltered;
	
	private final List<String> liveLog = new ArrayList<>();
	private final List<DiscoveredServer> discovered = new ArrayList<>();
	private final List<WurstServerPinger.ProbeResult> candidates =
		new ArrayList<>();
	
	public ServerFinderScreen(JoinMultiplayerScreen prevScreen)
	{
		super(Component.literal("Server Finder"));
		this.prevScreen = prevScreen;
	}
	
	@Override
	public void init()
	{
		addRenderableWidget(searchButton =
			Button.builder(Component.literal("Search"), b -> searchOrCancel())
				.bounds(0, 0, 100, 20).build());
		searchButton.active = false;
		
		ignoreWhitelistButton =
			Button.builder(Component.empty(), b -> toggleIgnoreWhitelist())
				.bounds(0, 0, 100, 20).build();
		addRenderableWidget(ignoreWhitelistButton);
		updateWhitelistButtonText();
		
		retryUnverifiedButton =
			Button.builder(Component.literal("Retry"), b -> retryUnverified())
				.bounds(0, 0, 100, 20).build();
		addRenderableWidget(retryUnverifiedButton);
		
		addRenderableWidget(backButton =
			Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(0, 0, 100, 20).build());
		
		ipBox = new EditBox(font, 0, 0, 200, 20, Component.empty());
		ipBox.setMaxLength(200);
		ipBox.setValue("127.0.0.1/32");
		addWidget(ipBox);
		setFocused(ipBox);
		
		prefixBox = new EditBox(font, 0, 0, 200, 20, Component.empty());
		prefixBox.setMaxLength(64);
		prefixBox.setValue(DEFAULT_SERVER_PREFIX);
		addWidget(prefixBox);
		
		versionFilterBox = new EditBox(font, 0, 0, 200, 20, Component.empty());
		versionFilterBox.setMaxLength(64);
		versionFilterBox.setValue("");
		addWidget(versionFilterBox);
		
		maxThreadsBox = new EditBox(font, 0, 0, 42, 20, Component.empty());
		maxThreadsBox.setMaxLength(3);
		maxThreadsBox.setValue("128");
		addWidget(maxThreadsBox);
		
		layoutWidgets();
		state = ServerFinderState.NOT_RUNNING;
	}
	
	private void layoutWidgets()
	{
		int panelX = getPanelX();
		int panelY = getPanelY();
		int leftX = panelX + 18;
		int leftW = getLeftColumnW();
		int y = panelY + 72;
		
		ipBox.setPosition(leftX, y + 38);
		ipBox.setWidth(leftW);
		
		maxThreadsBox.setPosition(leftX, y + 86);
		maxThreadsBox.setWidth(56);
		
		prefixBox.setPosition(leftX, y + 130);
		prefixBox.setWidth(leftW);
		
		versionFilterBox.setPosition(leftX, y + 174);
		versionFilterBox.setWidth(leftW);
		
		ignoreWhitelistButton.setPosition(leftX, y + 226);
		ignoreWhitelistButton.setWidth(leftW);
		
		int buttonY = panelY + getPanelH() - 38;
		int buttonW = (leftW - 12) / 3;
		searchButton.setPosition(leftX, buttonY);
		searchButton.setWidth(buttonW);
		retryUnverifiedButton.setPosition(leftX + buttonW + 6, buttonY);
		retryUnverifiedButton.setWidth(buttonW);
		backButton.setPosition(leftX + (buttonW + 6) * 2, buttonY);
		backButton.setWidth(leftW - buttonW * 2 - 12);
	}
	
	private void toggleIgnoreWhitelist()
	{
		ignoreWhitelisted = !ignoreWhitelisted;
		updateWhitelistButtonText();
	}
	
	private void updateWhitelistButtonText()
	{
		ignoreWhitelistButton.setMessage(Component
			.literal(ignoreWhitelisted ? "Whitelist filter: Hide blocked"
				: "Whitelist filter: Show blocked"));
	}
	
	private void retryUnverified()
	{
		if(state.isRunning())
			return;
		
		List<WurstServerPinger.ProbeResult> unverified = candidates.stream()
			.filter(result -> !result.whitelistVerified).toList();
		if(unverified.isEmpty())
			return;
		
		state = ServerFinderState.VERIFYING;
		searchButton.setMessage(Component.literal("Cancel"));
		addLog("Retrying " + unverified.size() + " unverified servers...");
		new Thread(() -> retryUnverifiedInBackground(unverified),
			"Server Finder Retry Unverified").start();
	}
	
	private void retryUnverifiedInBackground(
		List<WurstServerPinger.ProbeResult> unverified)
	{
		try
		{
			verifyCandidates(unverified, "unverified servers");
			if(state != ServerFinderState.CANCELLED)
			{
				finalizeCandidates();
				state = ServerFinderState.DONE;
				addLog("Unverified retry complete.");
			}
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			state = ServerFinderState.ERROR;
			addLog("Retry interrupted.");
		}finally
		{
			finishSearchUi();
		}
	}
	
	private void searchOrCancel()
	{
		if(state.isRunning())
		{
			state = ServerFinderState.CANCELLED;
			ipBox.active = true;
			prefixBox.active = true;
			versionFilterBox.active = true;
			maxThreadsBox.active = true;
			searchButton.setMessage(Component.literal("Search"));
			return;
		}
		
		if(!hasAuthenticatedSession())
		{
			state = ServerFinderState.AUTH_REQUIRED;
			liveLog.clear();
			addLog("Whitelist detection requires an authenticated account.");
			addLog("Current session is offline/cracked. Scan rejected.");
			return;
		}
		
		state = ServerFinderState.RESOLVING;
		maxThreads = Math.max(1, Integer.parseInt(maxThreadsBox.getValue()));
		ipBox.active = false;
		prefixBox.active = false;
		versionFilterBox.active = false;
		maxThreadsBox.active = false;
		searchButton.setMessage(Component.literal("Cancel"));
		checked = 0;
		working = 0;
		totalTargets = 0;
		onlineFound = 0;
		offlineMode = 0;
		whitelisted = 0;
		ignoredWhitelisted = 0;
		ignoredUnverified = 0;
		versionFiltered = 0;
		liveLog.clear();
		discovered.clear();
		candidates.clear();
		addLog("Preparing scan...");
		
		new Thread(this::findServers, "Server Finder").start();
	}
	
	private void findServers()
	{
		try
		{
			List<ScanTarget> targets = parseTargets(ipBox.getValue());
			totalTargets = targets.size();
			if(totalTargets == 0)
			{
				state = ServerFinderState.ERROR;
				addLog("No targets parsed from input.");
				finishSearchUi();
				return;
			}
			
			addLog("Parsed " + totalTargets + " targets.");
			
			state = ServerFinderState.SEARCHING;
			ArrayList<WurstServerPinger> pingers = new ArrayList<>();
			for(ScanTarget target : targets)
			{
				if(state == ServerFinderState.CANCELLED)
				{
					finishSearchUi();
					return;
				}
				
				WurstServerPinger pinger = new WurstServerPinger();
				pinger.ping(target.host(), target.port());
				pingers.add(pinger);
				while(pingers.size() >= maxThreads)
				{
					if(state == ServerFinderState.CANCELLED)
					{
						finishSearchUi();
						return;
					}
					
					updatePingers(pingers);
					Thread.sleep(2);
				}
			}
			while(pingers.size() > 0)
			{
				if(state == ServerFinderState.CANCELLED)
				{
					finishSearchUi();
					return;
				}
				
				updatePingers(pingers);
				Thread.sleep(2);
			}
			verifyCandidates(candidates, "version-matched servers");
			if(state == ServerFinderState.CANCELLED)
			{
				finishSearchUi();
				return;
			}
			finalizeCandidates();
			state = ServerFinderState.DONE;
			addLog("Scan complete.");
			finishSearchUi();
			
		}catch(UnknownHostException e)
		{
			state = ServerFinderState.UNKNOWN_HOST;
			addLog("Invalid host/range input.");
			finishSearchUi();
			
		}catch(TargetParseException e)
		{
			state = ServerFinderState.ERROR;
			addLog(e.getMessage());
			finishSearchUi();
			
		}catch(Exception e)
		{
			e.printStackTrace();
			state = ServerFinderState.ERROR;
			addLog("Error: " + describeException(e));
			finishSearchUi();
		}
	}
	
	private void finishSearchUi()
	{
		ipBox.active = true;
		prefixBox.active = true;
		versionFilterBox.active = true;
		maxThreadsBox.active = true;
		searchButton.setMessage(Component.literal("Search"));
	}
	
	private void updatePingers(ArrayList<WurstServerPinger> pingers)
	{
		for(int i = 0; i < pingers.size(); i++)
		{
			WurstServerPinger pinger = pingers.get(i);
			if(pinger.isStillPinging())
				continue;
			
			checked++;
			WurstServerPinger.ProbeResult result = pinger.getResult();
			if(result != null && result.online)
			{
				onlineFound++;
				if(result.isCracked())
					offlineMode++;
				if(result.whitelisted)
					whitelisted++;
				
				if(matchesVersionFilter(result))
				{
					candidates.add(result);
					discovered.add(new DiscoveredServer(
						buildDiscoveredText(result), getResultColor(result)));
					trimList(discovered, 400);
				}else
				{
					versionFiltered++;
					addLog("[FILTERED][VERSION] " + result.ipWithPort() + " - "
						+ result.versionName);
				}
			}else if(result != null)
			{
				addLog("[OFFLINE] " + result.ipWithPort() + " - "
					+ result.probeDetail);
			}
			
			if(result != null && result.online)
				addLog("[FOUND] " + result.ipWithPort() + " - "
					+ result.probeDetail
					+ (result.whitelisted ? " [whitelist]" : "")
					+ (result.blocked && !result.whitelisted ? " [blocked]"
						: ""));
			
			pingers.remove(i);
			i--;
		}
	}
	
	private void verifyCandidates(List<WurstServerPinger.ProbeResult> targets,
		String targetLabel) throws InterruptedException
	{
		if(targets.isEmpty())
			return;
		
		state = ServerFinderState.VERIFYING;
		whitelisted = 0;
		for(WurstServerPinger.ProbeResult result : candidates)
			if(result.whitelisted)
				whitelisted++;
		addLog("Verifying " + targets.size() + " " + targetLabel
			+ " for whitelist status...");
		
		AtomicInteger next = new AtomicInteger();
		AtomicInteger verified = new AtomicInteger();
		ArrayList<Thread> workers = new ArrayList<>();
		int workerCount = Math.min(WHITELIST_VERIFY_THREADS, targets.size());
		for(int i = 0; i < workerCount; i++)
		{
			Thread worker = new Thread(() -> {
				while(state != ServerFinderState.CANCELLED)
				{
					int index = next.getAndIncrement();
					if(index >= targets.size())
						return;
					
					WurstServerPinger.ProbeResult result = targets.get(index);
					boolean wasWhitelisted = result.whitelisted;
					addLog("[VERIFY][JOIN] " + result.ipWithPort());
					WurstServerPinger.verifyLogin(result);
					int done = verified.incrementAndGet();
					if(result.whitelisted && !wasWhitelisted)
						whitelisted++;
					else if(!result.whitelisted && wasWhitelisted)
						whitelisted--;
					String verdict =
						result.joinable ? "[VERIFY][PASS] " : "[VERIFY][FAIL] ";
					addLog(verdict + done + " / " + targets.size() + " "
						+ result.ipWithPort() + " - " + result.probeDetail
						+ (result.whitelisted ? " [whitelist]" : "")
						+ (result.blocked && !result.whitelisted ? " [blocked]"
							: ""));
				}
			}, "Server Finder Whitelist Verifier #" + (i + 1));
			worker.start();
			workers.add(worker);
		}
		
		for(Thread worker : workers)
			worker.join();
		
		offlineMode = 0;
		for(WurstServerPinger.ProbeResult result : candidates)
		{
			if(result.isCracked())
				offlineMode++;
		}
		addLog("Whitelist verification complete.");
	}
	
	private void finalizeCandidates()
	{
		discovered.clear();
		working = 0;
		ignoredWhitelisted = 0;
		ignoredUnverified = 0;
		
		for(WurstServerPinger.ProbeResult result : candidates)
		{
			if(!result.whitelistVerified)
			{
				ignoredUnverified++;
				addLog("[IGNORED][UNVERIFIED] " + result.ipWithPort() + " - "
					+ result.probeDetail);
				continue;
			}
			
			if(result.whitelisted)
			{
				ignoredWhitelisted++;
				addLog("[IGNORED][WHITELIST] " + result.ipWithPort());
				continue;
			}
			
			if(result.blocked)
			{
				ignoredWhitelisted++;
				addLog("[IGNORED][BLOCKED] " + result.ipWithPort());
				continue;
			}
			
			if(!result.joinable)
			{
				ignoredUnverified++;
				addLog("[IGNORED][NOT-JOINABLE] " + result.ipWithPort() + " - "
					+ result.probeDetail);
				continue;
			}
			
			if(ignoreWhitelisted && (result.whitelisted || result.blocked))
				continue;
			
			discovered.add(new DiscoveredServer(buildDiscoveredText(result),
				getResultColor(result)));
			trimList(discovered, 400);
			working++;
			addServerToList(buildServerName(result), result.ipWithPort());
		}
	}
	
	private String buildDiscoveredText(WurstServerPinger.ProbeResult result)
	{
		StringBuilder text = new StringBuilder(result.ipWithPort());
		if(result.versionName != null && !result.versionName.isBlank())
			text.append(" ").append(result.versionName);
		if(result.isCracked())
			text.append(" [OFFLINE-MODE]");
		if(result.whitelisted)
			text.append(" [WHITELISTED]");
		else if(result.blocked)
			text.append(" [BLOCKED]");
		if(result.rateLimited)
			text.append(" [RATE-LIMITED]");
		if(result.protocolError)
			text.append(" [PROTOCOL]");
		return text.toString();
	}
	
	private boolean matchesVersionFilter(WurstServerPinger.ProbeResult result)
	{
		String filter = versionFilterBox.getValue();
		if(filter == null || filter.isBlank())
			return true;
		
		String needle = filter.trim().toLowerCase();
		String haystack =
			((result.versionName == null ? "" : result.versionName) + " "
				+ (result.motd == null ? "" : result.motd)).toLowerCase();
		return haystack.contains(needle);
	}
	
	private int getResultColor(WurstServerPinger.ProbeResult result)
	{
		if(result.whitelisted)
			return 0xFFFFD166;
		if(result.blocked)
			return 0xFFFFA77A;
		if(result.isCracked())
			return 0xFF7DE38D;
		return CommonColors.WHITE;
	}
	
	private List<ScanTarget> parseTargets(String input) throws Exception
	{
		String trimmed = input == null ? "" : input.trim();
		if(trimmed.isEmpty())
			return List.of();
		
		int port = 25565;
		if(trimmed.toLowerCase().startsWith("asn:"))
			return parseAsnTargets(trimmed.substring(4).trim(), port);
		if(trimmed.toLowerCase().startsWith("as")
			&& trimmed.substring(2).matches("\\d+"))
			return parseAsnTargets(trimmed.substring(2), port);
		if(trimmed.matches("\\d+"))
			return parseAsnTargets(trimmed, port);
		
		if(trimmed.contains(":") && !trimmed.contains("-"))
		{
			String[] parts = trimmed.split(":");
			if(parts.length == 2)
			{
				trimmed = parts[0].trim();
				port = Integer.parseInt(parts[1].trim());
			}
		}
		
		Set<ScanTarget> out = new LinkedHashSet<>();
		if(trimmed.contains("/"))
			parseCidr(trimmed, port, out);
		else if(trimmed.contains("-"))
			parseRange(trimmed, port, out);
		else
		{
			InetAddress addr = InetAddress.getByName(trimmed);
			out.add(new ScanTarget(formatIPv4(addrToInt(addr)), port));
		}
		
		return new ArrayList<>(out);
	}
	
	private List<ScanTarget> parseAsnTargets(String asnText, int port)
		throws Exception
	{
		int asn = Integer.parseInt(asnText.trim());
		addLog("Fetching IPv4 prefixes for AS" + asn + "...");
		
		List<String> prefixes = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		fetchBgpViewPrefixes(asn, prefixes, failures);
		if(prefixes.isEmpty())
			fetchRipeStatPrefixes(asn, prefixes, failures);
		
		if(prefixes.isEmpty())
			throw new TargetParseException("ASN lookup failed for AS" + asn
				+ ": " + String.join("; ", failures));
		
		Set<ScanTarget> out = new LinkedHashSet<>();
		for(String prefix : prefixes)
		{
			if(out.size() >= TARGET_LIMIT)
				break;
			
			parseCidr(prefix, port, out);
		}
		
		addLog("AS" + asn + " provided " + prefixes.size()
			+ " IPv4 prefixes; queued " + out.size() + " targets.");
		return new ArrayList<>(out);
	}
	
	private void fetchBgpViewPrefixes(int asn, List<String> prefixes,
		List<String> failures)
	{
		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8)).build();
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("https://api.bgpview.io/asn/" + asn + "/prefixes"))
			.timeout(Duration.ofSeconds(15))
			.header("User-Agent", "Wurst-ServerFinder").GET().build();
		
		try
		{
			HttpResponse<String> response =
				client.send(request, HttpResponse.BodyHandlers.ofString());
			if(response.statusCode() < 200 || response.statusCode() >= 300)
			{
				failures.add("BGPView HTTP " + response.statusCode());
				return;
			}
			
			JsonObject root =
				JsonParser.parseString(response.body()).getAsJsonObject();
			JsonObject data = root.getAsJsonObject("data");
			if(data == null || !data.has("ipv4_prefixes"))
			{
				failures.add("BGPView returned no IPv4 prefixes");
				return;
			}
			
			JsonArray jsonPrefixes = data.getAsJsonArray("ipv4_prefixes");
			for(JsonElement element : jsonPrefixes)
			{
				if(!element.isJsonObject())
					continue;
				
				JsonObject prefix = element.getAsJsonObject();
				if(prefix.has("prefix"))
					prefixes.add(prefix.get("prefix").getAsString());
			}
			
			if(!prefixes.isEmpty())
				addLog(
					"BGPView returned " + prefixes.size() + " IPv4 prefixes.");
			else
				failures.add("BGPView returned no IPv4 prefixes");
			
		}catch(Exception e)
		{
			failures.add("BGPView " + describeException(e));
		}
	}
	
	private void fetchRipeStatPrefixes(int asn, List<String> prefixes,
		List<String> failures)
	{
		addLog("BGPView lookup failed; trying RIPEstat...");
		
		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8)).build();
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("https://stat.ripe.net/data/announced-prefixes/"
				+ "data.json?resource=AS" + asn))
			.timeout(Duration.ofSeconds(15))
			.header("User-Agent", "Wurst-ServerFinder").GET().build();
		
		try
		{
			HttpResponse<String> response =
				client.send(request, HttpResponse.BodyHandlers.ofString());
			if(response.statusCode() < 200 || response.statusCode() >= 300)
			{
				failures.add("RIPEstat HTTP " + response.statusCode());
				return;
			}
			
			JsonObject root =
				JsonParser.parseString(response.body()).getAsJsonObject();
			JsonObject data = root.getAsJsonObject("data");
			if(data == null || !data.has("prefixes"))
			{
				failures.add("RIPEstat returned no prefixes");
				return;
			}
			
			JsonArray jsonPrefixes = data.getAsJsonArray("prefixes");
			for(JsonElement element : jsonPrefixes)
			{
				if(!element.isJsonObject())
					continue;
				
				JsonObject prefix = element.getAsJsonObject();
				if(!prefix.has("prefix"))
					continue;
				
				String value = prefix.get("prefix").getAsString();
				if(value.indexOf(':') == -1)
					prefixes.add(value);
			}
			
			if(!prefixes.isEmpty())
				addLog(
					"RIPEstat returned " + prefixes.size() + " IPv4 prefixes.");
			else
				failures.add("RIPEstat returned no IPv4 prefixes");
			
		}catch(Exception e)
		{
			failures.add("RIPEstat " + describeException(e));
		}
	}
	
	private static String describeException(Exception e)
	{
		if(e.getMessage() != null && !e.getMessage().isBlank())
			return e.getMessage();
		
		Throwable cause = e.getCause();
		while(cause != null)
		{
			if(cause.getMessage() != null && !cause.getMessage().isBlank())
				return cause.getMessage();
			cause = cause.getCause();
		}
		
		return e.getClass().getSimpleName();
	}
	
	private void parseCidr(String cidr, int port, Set<ScanTarget> out)
		throws Exception
	{
		String[] parts = cidr.split("/");
		if(parts.length != 2)
			throw new UnknownHostException("Bad CIDR");
		
		int base = addrToInt(InetAddress.getByName(parts[0].trim()));
		int prefix = Integer.parseInt(parts[1].trim());
		if(prefix < 0 || prefix > 32)
			throw new UnknownHostException("CIDR prefix out of range");
		
		long count = 1L << (32 - prefix);
		long limit = Math.min(count, TARGET_LIMIT - out.size());
		int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
		int network = base & mask;
		for(long i = 0; i < limit; i++)
			out.add(new ScanTarget(formatIPv4(network + (int)i), port));
	}
	
	private void parseRange(String range, int port, Set<ScanTarget> out)
		throws Exception
	{
		String[] parts = range.split("-");
		if(parts.length != 2)
			throw new UnknownHostException("Bad IP range");
		
		int start = addrToInt(InetAddress.getByName(parts[0].trim()));
		int end = addrToInt(InetAddress.getByName(parts[1].trim()));
		if(Integer.compareUnsigned(start, end) > 0)
		{
			int tmp = start;
			start = end;
			end = tmp;
		}
		
		long steps =
			Integer.toUnsignedLong(end) - Integer.toUnsignedLong(start) + 1L;
		long limit = Math.min(steps, TARGET_LIMIT - out.size());
		for(long i = 0; i < limit; i++)
			out.add(new ScanTarget(formatIPv4(start + (int)i), port));
	}
	
	private String buildServerName(WurstServerPinger.ProbeResult result)
	{
		String prefix =
			prefixBox.getValue() == null ? "" : prefixBox.getValue();
		if(prefix.isBlank())
			prefix = DEFAULT_SERVER_PREFIX;
		
		String name = prefix + working;
		if(result.isCracked())
			name += " (Offline)";
		return name;
	}
	
	private int addrToInt(InetAddress addr)
	{
		byte[] b = addr.getAddress();
		if(b.length != 4)
			throw new IllegalArgumentException("Only IPv4 is supported");
		return (b[0] & 0xFF) << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8
			| b[3] & 0xFF;
	}
	
	private String formatIPv4(int ip)
	{
		return (ip >>> 24 & 0xFF) + "." + (ip >>> 16 & 0xFF) + "."
			+ (ip >>> 8 & 0xFF) + "." + (ip & 0xFF);
	}
	
	private synchronized void addLog(String line)
	{
		liveLog.add(shortenLogLine(line));
		trimList(liveLog, 400);
		System.out.println("[Server Finder] " + line);
	}
	
	private String shortenLogLine(String line)
	{
		if(line == null)
			return "";
		int max = 96;
		if(line.length() <= max)
			return line;
		return line.substring(0, max - 3) + "...";
	}
	
	private boolean hasAuthenticatedSession()
	{
		if(minecraft == null || minecraft.getUser() == null)
			return false;
		
		String accessToken = minecraft.getUser().getAccessToken();
		if(accessToken == null)
			return false;
		
		String trimmed = accessToken.trim();
		return !trimmed.isEmpty() && !trimmed.equals("0")
			&& !trimmed.equalsIgnoreCase("null");
	}
	
	private <T> void trimList(List<T> list, int max)
	{
		while(list.size() > max)
			list.remove(0);
	}
	
	// Basically what MultiplayerScreen.addEntry() does,
	// but without changing the current screen.
	private void addServerToList(String name, String ip)
	{
		ServerList serverList = prevScreen.getServers();
		ServerData existing = serverList.get(ip);
		if(existing != null)
		{
			existing.name = name;
			serverList.save();
			
			ServerSelectionList listWidget = prevScreen.serverSelectionList;
			listWidget.setSelected(null);
			listWidget.updateOnlineServers(serverList);
			return;
		}
		
		serverList.add(new ServerData(name, ip, Type.OTHER), false);
		serverList.save();
		
		ServerSelectionList listWidget = prevScreen.serverSelectionList;
		listWidget.setSelected(null);
		listWidget.updateOnlineServers(serverList);
	}
	
	@Override
	public void tick()
	{
		boolean validThreads = MathUtils.isInteger(maxThreadsBox.getValue())
			&& Integer.parseInt(maxThreadsBox.getValue()) > 0;
		searchButton.active = state != null && state.isRunning()
			|| validThreads && !ipBox.getValue().isEmpty();
		retryUnverifiedButton.active =
			state != null && !state.isRunning() && candidates.stream()
				.anyMatch(result -> !result.whitelistVerified);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_4)
		{
			onClose();
			return true;
		}
		
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		layoutWidgets();
		
		context.fillGradient(0, 0, width, height, 0xB0101215, 0xD0080A0C);
		
		int panelX = getPanelX();
		int panelY = getPanelY();
		int panelW = getPanelW();
		int panelH = getPanelH();
		int leftW = getLeftColumnW();
		int leftX = panelX + 18;
		int rightX = leftX + leftW + 18;
		int rightW = panelW - leftW - 54;
		
		drawPanel(context, panelX, panelY, panelW, panelH);
		context.fill(panelX, panelY, panelX + panelW, panelY + 42, 0xFF18212B);
		context.fill(panelX, panelY + 41, panelX + panelW, panelY + 42,
			0xFF2E4050);
		
		context.text(font, "Server Finder", panelX + 18, panelY + 10,
			CommonColors.WHITE, false);
		context.text(font, "CIDR, IP range, ASN lookup, offline-mode detection",
			panelX + 18, panelY + 24, 0xFF9FB2C4, false);
		
		String accountText = hasAuthenticatedSession()
			? "Account: authenticated" : "Account: offline/cracked";
		int accountW = font.width(accountText) + 16;
		drawChip(context, panelX + panelW - accountW - 18, panelY + 10,
			accountW, accountText,
			hasAuthenticatedSession() ? 0xFF1E6D42 : 0xFF7B2D2D);
		
		context.fill(leftX + leftW + 9, panelY + 54, leftX + leftW + 10,
			panelY + panelH - 18, 0xFF2A3541);
		
		context.text(font, "Scan Target", leftX, panelY + 58,
			CommonColors.WHITE, false);
		context.text(font,
			trimToWidth("Use CIDR, IP range, host, or ASN", leftW), leftX,
			panelY + 72, 0xFF93A4B4, false);
		context.text(font,
			trimToWidth("Example: 203.0.113.0/24 or AS15169", leftW), leftX,
			panelY + 83, 0xFF93A4B4, false);
		ipBox.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		context.text(font, "Threads", leftX, maxThreadsBox.getY() - 12,
			CommonColors.WHITE, false);
		maxThreadsBox.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		context.text(font, "Server Prefix", leftX, prefixBox.getY() - 12,
			CommonColors.WHITE, false);
		prefixBox.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		context.text(font, "Version / Software Filter", leftX,
			versionFilterBox.getY() - 12, CommonColors.WHITE, false);
		versionFilterBox.extractRenderState(context, mouseX, mouseY,
			partialTicks);
		
		context.text(font, "Whitelist Handling", leftX,
			ignoreWhitelistButton.getY() - 12, CommonColors.WHITE, false);
		
		int progressY = ignoreWhitelistButton.getY() + 28;
		int progress = totalTargets <= 0 ? 0
			: Math.min(100, checked * 100 / Math.max(1, totalTargets));
		drawMetric(context, leftX, progressY, leftW, "Progress",
			checked + " / " + totalTargets, progress);
		drawMiniStats(context, leftX, progressY + 54, leftW);
		
		int logsY = panelY + 58;
		int logsH = panelY + panelH - logsY - 18;
		int gap = 10;
		int logW = (rightW - gap) / 2;
		drawListPanel(context, "Live Scan Log", snapshot(liveLog), rightX,
			logsY, logW, logsH, "Waiting for scan output...");
		drawDiscoveredPanel(context, "Discovered Servers", snapshot(discovered),
			rightX + logW + gap, logsY, rightW - logW - gap, logsH,
			"No servers discovered yet.");
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	private void drawMiniStats(GuiGraphicsExtractor context, int x, int y,
		int w)
	{
		context.fill(x, y, x + w, y + 82, 0xFF101820);
		drawBorder(context, x, y, w, 82, 0xFF34495A);
		context.text(font, "Classified", x + 8, y + 7, 0xFF9FB2C4, false);
		context.text(font, "Online found: " + onlineFound, x + 8, y + 20,
			CommonColors.WHITE, false);
		context.text(font, "Added: " + working, x + 8, y + 32,
			CommonColors.WHITE, false);
		context.text(font, "Offline-mode: " + offlineMode, x + 8, y + 44,
			0xFF7DE38D, false);
		context.text(font, "Whitelisted: " + whitelisted, x + 8, y + 56,
			0xFFFFD166, false);
		context.text(font, "Ignored: " + ignoredWhitelisted, x + w / 2, y + 56,
			0xFFFF8A8A, false);
		context.text(font, "Unverified: " + ignoredUnverified, x + w / 2,
			y + 68, 0xFFFF8A8A, false);
		context.text(font, "Version filtered: " + versionFiltered, x + 8,
			y + 68, 0xFFB39DDB, false);
	}
	
	private void drawStatusBlock(GuiGraphicsExtractor context, int x, int y,
		int w, int h)
	{
		context.fill(x, y, x + w, y + h, 0xFF101820);
		drawBorder(context, x, y, w, h, 0xFF34495A);
		
		int color = state == ServerFinderState.ERROR
			|| state == ServerFinderState.UNKNOWN_HOST
			|| state == ServerFinderState.AUTH_REQUIRED ? 0xFFFF6B6B
				: state.isRunning() ? 0xFF7DE38D : 0xFFB6C2CC;
		context.text(font,
			state.toString().isEmpty() ? "Ready" : state.toString(), x + 10,
			y + 10, color, false);
		context.text(font,
			trimToWidth(
				"Use 203.0.113.0/24, 203.0.113.10-203.0.113.80, or AS15169",
				w - 20),
			x + 10, y + 28, 0xFF8FA3B5, false);
	}
	
	private void drawMetric(GuiGraphicsExtractor context, int x, int y, int w,
		String label, String value, int percent)
	{
		context.fill(x, y, x + w, y + 42, 0xFF101820);
		drawBorder(context, x, y, w, 42, 0xFF34495A);
		context.text(font, label, x + 8, y + 7, 0xFF9FB2C4, false);
		context.text(font, value, x + 8, y + 20, CommonColors.WHITE, false);
		if(percent > 0)
		{
			int barX = x + 82;
			int barY = y + 24;
			int barW = w - 94;
			context.fill(barX, barY, barX + barW, barY + 4, 0xFF26313B);
			context.fill(barX, barY, barX + barW * percent / 100, barY + 4,
				0xFF58A6FF);
		}
	}
	
	private void drawListPanel(GuiGraphicsExtractor context, String title,
		List<String> rows, int x, int y, int w, int h, String emptyText)
	{
		context.fill(x, y, x + w, y + h, 0xFF0C1117);
		context.fill(x, y, x + w, y + 22, 0xFF17212B);
		drawBorder(context, x, y, w, h, 0xFF34495A);
		context.text(font, title, x + 8, y + 7, CommonColors.WHITE, false);
		context.text(font, Integer.toString(rows.size()), x + w - 20, y + 7,
			0xFF9FB2C4, false);
		
		int rowTop = y + 28;
		int visible = Math.max(1, (h - 34) / 11);
		if(rows.isEmpty())
		{
			context.text(font, emptyText, x + 8, rowTop, 0xFF697987, false);
			return;
		}
		
		for(int i = 0; i < visible; i++)
		{
			int index = rows.size() - visible + i;
			if(index < 0 || index >= rows.size())
				continue;
			
			int rowY = rowTop + i * 11;
			if(i % 2 == 0)
				context.fill(x + 4, rowY - 1, x + w - 4, rowY + 10, 0x201D2A36);
			String row = rows.get(index);
			context.text(font, trimToWidth(row, w - 14), x + 8, rowY,
				getLogColor(row), false);
		}
	}
	
	private int getLogColor(String row)
	{
		String lower = row.toLowerCase();
		if(lower.contains("[whitelist]") || lower.contains("whitelisted")
			|| lower.contains("not_whitelisted")
			|| lower.contains("not whitelisted")
			|| lower.contains("[ignored][whitelist]")
			|| lower.contains("[blocked]")
			|| lower.contains("[ignored][blocked]"))
			return 0xFFFFD166;
		if(lower.contains("[ignored][unverified]")
			|| lower.contains("[ignored][not-joinable]")
			|| lower.contains("whitelist verification failed")
			|| lower.contains("authenticated probe failed")
			|| lower.contains("unknown disconnect")
			|| lower.contains("ratelimiter")
			|| lower.contains("unexpected end of stream"))
			return 0xFFFF8A8A;
		if(lower.contains("[filtered][version]"))
			return 0xFFB39DDB;
		if(lower.contains("[found]"))
			return 0xFF7DE38D;
		if(lower.contains("[offline]"))
			return 0xFF8FA3B5;
		return CommonColors.WHITE;
	}
	
	private void drawDiscoveredPanel(GuiGraphicsExtractor context, String title,
		List<DiscoveredServer> rows, int x, int y, int w, int h,
		String emptyText)
	{
		context.fill(x, y, x + w, y + h, 0xFF0C1117);
		context.fill(x, y, x + w, y + 22, 0xFF17212B);
		drawBorder(context, x, y, w, h, 0xFF34495A);
		context.text(font, title, x + 8, y + 7, CommonColors.WHITE, false);
		context.text(font, Integer.toString(rows.size()), x + w - 20, y + 7,
			0xFF9FB2C4, false);
		
		int rowTop = y + 28;
		int visible = Math.max(1, (h - 34) / 11);
		if(rows.isEmpty())
		{
			context.text(font, emptyText, x + 8, rowTop, 0xFF697987, false);
			return;
		}
		
		for(int i = 0; i < visible; i++)
		{
			int index = rows.size() - visible + i;
			if(index < 0 || index >= rows.size())
				continue;
			
			DiscoveredServer row = rows.get(index);
			int rowY = rowTop + i * 11;
			if(i % 2 == 0)
				context.fill(x + 4, rowY - 1, x + w - 4, rowY + 10, 0x201D2A36);
			context.text(font, trimToWidth(row.text(), w - 14), x + 8, rowY,
				row.color(), false);
		}
	}
	
	private void drawPanel(GuiGraphicsExtractor context, int x, int y, int w,
		int h)
	{
		context.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xAA000000);
		context.fill(x, y, x + w, y + h, 0xEE0D1319);
		drawBorder(context, x, y, w, h, 0xFF415366);
	}
	
	private void drawBorder(GuiGraphicsExtractor context, int x, int y, int w,
		int h, int color)
	{
		context.fill(x, y, x + w, y + 1, color);
		context.fill(x, y + h - 1, x + w, y + h, color);
		context.fill(x, y, x + 1, y + h, color);
		context.fill(x + w - 1, y, x + w, y + h, color);
	}
	
	private void drawChip(GuiGraphicsExtractor context, int x, int y, int w,
		String text, int color)
	{
		context.fill(x, y, x + w, y + 15, color);
		context.fill(x, y, x + w, y + 1, 0x55FFFFFF);
		context.centeredText(font, text, x + w / 2, y + 4, CommonColors.WHITE);
	}
	
	private String trimToWidth(String text, int maxWidth)
	{
		if(font.width(text) <= maxWidth)
			return text;
		
		String ellipsis = "...";
		int end = text.length();
		while(end > 0
			&& font.width(text.substring(0, end) + ellipsis) > maxWidth)
			end--;
		
		return text.substring(0, Math.max(0, end)) + ellipsis;
	}
	
	private synchronized <T> List<T> snapshot(List<T> list)
	{
		return new ArrayList<>(list);
	}
	
	private int getPanelW()
	{
		return Math.min(width - 32, 880);
	}
	
	private int getPanelH()
	{
		return Math.min(height - 24, 520);
	}
	
	private int getPanelX()
	{
		return (width - getPanelW()) / 2;
	}
	
	private int getPanelY()
	{
		return Math.max(12, (height - getPanelH()) / 2);
	}
	
	private int getLeftColumnW()
	{
		return Math.min(260, getPanelW() / 3);
	}
	
	@Override
	public void onClose()
	{
		state = ServerFinderState.CANCELLED;
		minecraft.setScreen(prevScreen);
	}
	
	enum ServerFinderState
	{
		NOT_RUNNING(""),
		SEARCHING("\u00a72Searching..."),
		RESOLVING("\u00a72Resolving..."),
		VERIFYING("\u00a72Verifying whitelist..."),
		UNKNOWN_HOST("\u00a74Unknown Host!"),
		AUTH_REQUIRED("\u00a74Authenticated account required!"),
		CANCELLED("\u00a74Cancelled!"),
		DONE("\u00a72Done!"),
		ERROR("\u00a74An error occurred!");
		
		private final String name;
		
		private ServerFinderState(String name)
		{
			this.name = name;
		}
		
		public boolean isRunning()
		{
			return this == SEARCHING || this == RESOLVING || this == VERIFYING;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private record ScanTarget(String host, int port)
	{}
	
	private record DiscoveredServer(String text, int color)
	{}
	
	private static final class TargetParseException extends Exception
	{
		private TargetParseException(String message)
		{
			super(message);
		}
	}
}
