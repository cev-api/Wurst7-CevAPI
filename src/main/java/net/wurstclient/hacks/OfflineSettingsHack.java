/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.stream.Collectors;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.altmanager.AltManager;
import net.wurstclient.altmanager.CrackedAlt;
import net.wurstclient.altmanager.LoginManager;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.LastServerRememberer;
import net.wurstclient.util.text.WText;

@SearchTags({"offlinesettings", "offline reconnect", "random name",
	"instant reconnect"})
public final class OfflineSettingsHack extends Hack implements UpdateListener
{
	private static final String LOGIN_REASON =
		"You logged in from another location";
	
	private static final String NAME_CHARS =
		"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
	private static final int LOGOUT_PROBE_CONNECT_TIMEOUT_MS = 5000;
	private static final int LOGOUT_PROBE_READ_TIMEOUT_MS = 5000;
	private static final int LOGOUT_PROBE_DURATION_MS = 10000;
	private static final long AUTO_LOGOUT_COOLDOWN_MS = 5000L;
	
	private static final Method PROFILE_NAME_METHOD =
		discoverProfileNameMethod();
	private static final Method PROFILE_ID_METHOD = discoverProfileIdMethod();
	
	public static boolean isValidOfflineNameFormat(String name)
	{
		if(name == null)
			return false;
		
		return name.length() <= 16 && name.matches("[A-Za-z0-9_]+");
	}
	
	private static Method discoverProfileNameMethod()
	{
		try
		{
			Class<?> profileClass =
				Class.forName("com.mojang.authlib.GameProfile");
			Method method = profileClass.getMethod("getName");
			method.setAccessible(true);
			return method;
		}catch(Throwable t)
		{
			return null;
		}
	}
	
	private static Method discoverProfileIdMethod()
	{
		try
		{
			Class<?> profileClass =
				Class.forName("com.mojang.authlib.GameProfile");
			Method method = profileClass.getMethod("getId");
			method.setAccessible(true);
			return method;
		}catch(Throwable t)
		{
			return null;
		}
	}
	
	private final CheckboxSetting autoReconnect =
		new CheckboxSetting("Quick reconnect",
			WText.literal("Immediately reconnect when you are kicked for "
				+ "logging in elsewhere."),
			true);
	
	private final CheckboxSetting autoLogout =
		new CheckboxSetting("Auto logout",
			WText.literal(
				"Automatically re-run the logout probe whenever the selected "
					+ "player rejoins."),
			false);
	
	private final CheckboxSetting randomName =
		new CheckboxSetting("Random name on reconnect",
			WText.literal("Generate a temporary, random offline name (≤ 8 "
				+ "chars) before reconnecting."),
			false);
	
	private final CheckboxSetting crackedDetection =
		new CheckboxSetting("Cracked server detection",
			WText.literal("Detect cracked servers and announce them in chat."),
			true)
		{
			@Override
			public void update()
			{
				super.update();
				updateCrackedDetectionSubscription();
			}
		};
	
	private final TextFieldSetting specifiedName =
		new TextFieldSetting("Specified name",
			WText.literal("Force reconnects to use this offline name."), "",
			s -> s.isEmpty() || isValidOfflineNameFormat(s));
	
	private final StringDropdownSetting otherPlayerName =
		new StringDropdownSetting("Player select",
			WText.literal("Pick another player's name from the last server."));
	
	private final ButtonSetting logoutButton =
		new ButtonSetting("Logout other player",
			WText.literal("Attempt to log in as the selected player in a "
				+ "background probe."),
			this::startLogoutProbe);
	
	private final ButtonSetting reconnectButton =
		new ButtonSetting("Reconnect to server",
			WText.literal(
				"Reconnect with the chosen, specified, or random name."),
			this::reconnectWithSelectedName);
	
	private final TextFieldSetting reconnectCommand =
		new TextFieldSetting("Reconnect command",
			WText.literal(
				"Command to send immediately after reconnecting (optional)."),
			"", s -> true);
	
	private final ButtonSetting reconnectCommandButton =
		new ButtonSetting("Reconnect & run command",
			WText.literal(
				"Reconnect and run the command once chat is available."),
			this::reconnectAndRunCommand);
	
	private final Random random = new Random();
	private CrackedAlt trackedAlt;
	private int playerListTicks;
	private final AtomicBoolean autoReconnectInProgress =
		new AtomicBoolean(false);
	private final AtomicBoolean autoReconnectRequested =
		new AtomicBoolean(false);
	private final AtomicBoolean logoutProbeRunning = new AtomicBoolean(false);
	private volatile ServerData pendingServer;
	private volatile boolean pendingForceRandom;
	private volatile boolean pendingAllowRandomFallback;
	private volatile String pendingReconnectCommand;
	private int reconnectCommandDelayTicks;
	private final UpdateListener crackedDetectionUpdate =
		this::runCrackedDetectionUpdate;
	private boolean crackedDetectionRegistered;
	private int crackedDetectionTicks;
	private volatile long autoLogoutLastAttempt;
	private volatile String autoLogoutTarget;
	private volatile boolean autoLogoutWaitingForLeave;
	
	private final Map<String, Boolean> crackedServers =
		new ConcurrentHashMap<>();
	private final Set<String> announcedCrackedServers =
		Collections.newSetFromMap(new ConcurrentHashMap<>());
	private final UpdateListener reconnectCommandRunner = new UpdateListener()
	{
		@Override
		public void onUpdate()
		{
			tryRunPendingReconnectCommand();
		}
	};
	private boolean reconnectCommandRunnerRegistered;
	
	public OfflineSettingsHack()
	{
		super("OfflineSettings");
		setCategory(Category.OTHER);
		addSetting(crackedDetection);
		addSetting(autoReconnect);
		addSetting(randomName);
		addSetting(specifiedName);
		addSetting(otherPlayerName);
		addSetting(autoLogout);
		addSetting(reconnectCommand);
		addSetting(reconnectCommandButton);
		addSetting(reconnectButton);
		addSetting(logoutButton);
		updateCrackedDetectionSubscription();
	}
	
	public boolean isAutoReconnectEnabled()
	{
		return autoReconnect.isChecked();
	}
	
	public void toggleAutoReconnect()
	{
		autoReconnect.setChecked(!autoReconnect.isChecked());
		if(autoReconnect.isChecked())
			reconnectWithSelectedName();
	}
	
	public void setAutoReconnectEnabled(boolean enabled)
	{
		autoReconnect.setChecked(enabled);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		updateCrackedDetectionSubscription();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		unregisterReconnectCommandRunner();
		cleanupTemporaryAlt();
		pendingReconnectCommand = null;
		reconnectCommandDelayTicks = 0;
		clearAutoLogoutState();
		updateCrackedDetectionSubscription();
	}
	
	public void handleDisconnect(Component reason)
	{
		Minecraft client = Minecraft.getInstance();
		
		if(client == null || !isEnabled())
		{
			cleanupTemporaryAlt();
			autoReconnectRequested.set(false);
			pendingServer = null;
			return;
		}
		
		captureOnlinePlayers(client);
		
		if(!shouldReconnect(reason))
		{
			cleanupTemporaryAlt();
			autoReconnectRequested.set(false);
			pendingServer = null;
			return;
		}
		
		ServerData lastServer = LastServerRememberer.getLastServer();
		if(lastServer == null)
			lastServer = client.getCurrentServer();
		if(lastServer == null)
			return;
		
		pendingServer = lastServer;
		boolean preferRandom = randomName.isChecked();
		pendingForceRandom = preferRandom;
		pendingAllowRandomFallback = preferRandom;
		autoReconnectRequested.set(true);
	}
	
	public boolean consumeAutoReconnectRequest()
	{
		return autoReconnectRequested.getAndSet(false);
	}
	
	public void performAutoReconnect(Screen prevScreen)
	{
		Minecraft client = Minecraft.getInstance();
		ServerData server = pendingServer;
		
		if(server == null)
			server = LastServerRememberer.getLastServer();
		if(server == null || client == null)
			return;
		
		final ServerData targetServer = server;
		final boolean forceRandom = pendingForceRandom;
		final boolean allowRandom = pendingAllowRandomFallback;
		pendingServer = null;
		
		Screen resolvedPrev = resolvePrevScreen(prevScreen, client);
		if(resolvedPrev == null)
			resolvedPrev = client != null ? client.screen : null;
		final Screen prev = resolvedPrev;
		
		if(!autoReconnectInProgress.compareAndSet(false, true))
			return;
		
		client.execute(() -> {
			try
			{
				reconnect(client, prev, targetServer, forceRandom, allowRandom);
			}finally
			{
				autoReconnectInProgress.set(false);
			}
		});
	}
	
	public void reconnectWithRandomName()
	{
		reconnectWithRandomName(null);
	}
	
	public void reconnectWithRandomName(Screen prevScreen)
	{
		ServerData lastServer = LastServerRememberer.getLastServer();
		Minecraft client = Minecraft.getInstance();
		if(lastServer == null || client == null)
			return;
		
		final Screen prev = resolvePrevScreen(prevScreen, client);
		client.execute(() -> reconnect(client, prev, lastServer, true, true));
	}
	
	public void reconnectWithSelectedName()
	{
		reconnectWithSelectedName(null);
	}
	
	public void reconnectWithSelectedName(Screen prevScreen)
	{
		ServerData lastServer = LastServerRememberer.getLastServer();
		Minecraft client = Minecraft.getInstance();
		if(lastServer == null || client == null)
			return;
		
		boolean allowRandom = randomName.isChecked();
		final Screen prev = resolvePrevScreen(prevScreen, client);
		client.execute(
			() -> reconnect(client, prev, lastServer, false, allowRandom));
	}
	
	@Override
	public void onUpdate()
	{
		tryRunPendingReconnectCommand();
		
		if(playerListTicks-- > 0)
			return;
		
		playerListTicks = 40;
		captureOnlinePlayers(Minecraft.getInstance());
	}
	
	private boolean shouldReconnect(Component reason)
	{
		if(reason == null || !autoReconnect.isChecked())
			return false;
		
		String text = StringUtil.stripColor(reason.getString()).trim();
		return text.contains(LOGIN_REASON);
	}
	
	private void reconnect(Minecraft client, Screen prevScreen,
		ServerData server, boolean forceRandomName, boolean allowRandomFallback)
	{
		String nameToUse;
		
		if(forceRandomName)
			nameToUse = generateRandomName();
		else
			nameToUse = determineName(allowRandomFallback);
		
		if(nameToUse != null)
			applyName(nameToUse);
		else
			cleanupTemporaryAlt();
		
		startConnection(client, prevScreen, server);
	}
	
	public void reconnectWithCustomName(String name)
	{
		reconnectWithCustomName(name, null);
	}
	
	public void reconnectWithCustomName(String name, Screen prevScreen)
	{
		if(name == null)
			return;
		
		String trimmed = name.trim();
		if(trimmed.isEmpty() || !isValidOfflineNameFormat(trimmed))
			return;
		
		ServerData lastServer = LastServerRememberer.getLastServer();
		Minecraft client = Minecraft.getInstance();
		if(lastServer == null || client == null)
			return;
		
		final Screen prev = resolvePrevScreen(prevScreen, client);
		client.execute(
			() -> reconnectWithProvidedName(client, prev, lastServer, trimmed));
	}
	
	private void reconnectWithProvidedName(Minecraft client, Screen prevScreen,
		ServerData server, String providedName)
	{
		applyName(providedName);
		startConnection(client, prevScreen, server);
	}
	
	private Screen resolvePrevScreen(Screen requested, Minecraft client)
	{
		if(requested != null)
			return requested;
		
		return client != null ? client.screen : null;
	}
	
	private void startConnection(Minecraft client, Screen prevScreen,
		ServerData server)
	{
		ServerAddress address = ServerAddress.parseString(server.ip);
		Screen previous = resolvePrevScreen(prevScreen, client);
		ConnectScreen.startConnecting(previous, client, address, server, false,
			null);
	}
	
	private String determineName(boolean allowRandomFallback)
	{
		String selectedPlayer = otherPlayerName.getSelected();
		if(selectedPlayer != null && !selectedPlayer.isEmpty())
			return selectedPlayer;
		
		String customName = specifiedName.getValue();
		if(customName != null && !customName.isBlank())
			return customName;
		
		if(allowRandomFallback && randomName.isChecked())
			return generateRandomName();
		
		return null;
	}
	
	private void applyName(String name)
	{
		if(name == null || name.isEmpty())
			return;
		
		cleanupTemporaryAlt();
		AltManager altManager = WURST.getAltManager();
		
		if(!altManager.contains(name))
		{
			CrackedAlt alt = new CrackedAlt(name);
			altManager.add(alt);
			trackedAlt = alt;
		}
		
		LoginManager.changeCrackedName(name);
	}
	
	private String generateRandomName()
	{
		int length = random.nextInt(8) + 1;
		StringBuilder builder = new StringBuilder(length);
		
		for(int i = 0; i < length; i++)
			builder
				.append(NAME_CHARS.charAt(random.nextInt(NAME_CHARS.length())));
		
		return builder.toString();
	}
	
	private void cleanupTemporaryAlt()
	{
		if(trackedAlt == null)
			return;
		
		WURST.getAltManager().remove(trackedAlt);
		trackedAlt = null;
	}
	
	private void captureOnlinePlayers(Minecraft client)
	{
		if(client == null)
			return;
		
		LocalPlayer player = client.player;
		if(player == null || player.connection == null)
			return;
		
		Set<String> players = new LinkedHashSet<>();
		String self =
			client.getUser() != null ? client.getUser().getName() : "";
		
		ServerData server = getCurrentOrLastServer();
		
		for(PlayerInfo entry : player.connection.getOnlinePlayers())
		{
			String name =
				StringUtil.stripColor(extractProfileName(entry.getProfile()));
			if(name.isEmpty() || name.equalsIgnoreCase(self))
				continue;
			
			players.add(name);
		}
		
		if(player != null)
		{
			detectCrackedServerFromPlayerList(player, server);
			detectCrackedServerFromSelf(player, server);
		}
		
		Set<String> dropdownOptions = new LinkedHashSet<>(players);
		String selected = otherPlayerName.getSelected();
		if(selected != null && !selected.isEmpty()
			&& !containsIgnoreCase(dropdownOptions, selected))
			dropdownOptions.add(selected);
		
		otherPlayerName.setOptions(dropdownOptions);
		checkAutoLogout(players, server);
	}
	
	public List<String> getCapturedPlayerNames()
	{
		return otherPlayerName.getValues().stream()
			.filter(name -> name != null && !name.trim().isEmpty())
			.collect(Collectors.toList());
	}
	
	private void checkAutoLogout(Set<String> players, ServerData server)
	{
		if(!autoLogout.isChecked())
		{
			clearAutoLogoutState();
			return;
		}
		
		String selected = otherPlayerName.getSelected();
		if(selected == null || selected.isEmpty())
		{
			clearAutoLogoutState();
			return;
		}
		
		if(autoLogoutTarget == null
			|| !autoLogoutTarget.equalsIgnoreCase(selected))
		{
			autoLogoutTarget = selected;
			autoLogoutLastAttempt = 0;
			autoLogoutWaitingForLeave = false;
		}
		
		boolean isOnline =
			server != null && containsIgnoreCase(players, selected);
		if(!isOnline)
		{
			autoLogoutWaitingForLeave = false;
			autoLogoutLastAttempt = 0;
			return;
		}
		
		if(autoLogoutWaitingForLeave)
			return;
		
		long now = System.currentTimeMillis();
		if(now - autoLogoutLastAttempt < AUTO_LOGOUT_COOLDOWN_MS)
			return;
		
		if(triggerLogoutProbe(selected, server, true, true))
		{
			autoLogoutLastAttempt = now;
			autoLogoutWaitingForLeave = true;
		}
	}
	
	private void clearAutoLogoutState()
	{
		autoLogoutTarget = null;
		autoLogoutLastAttempt = 0;
		autoLogoutWaitingForLeave = false;
	}
	
	private boolean containsIgnoreCase(Iterable<String> names, String target)
	{
		if(names == null || target == null)
			return false;
		
		for(String name : names)
			if(name.equalsIgnoreCase(target))
				return true;
			
		return false;
	}
	
	private void detectCrackedServerFromPlayerList(LocalPlayer player,
		ServerData server)
	{
		if(player == null || player.connection == null
			|| !crackedDetection.isChecked())
			return;
		
		if(server == null)
			return;
		
		for(PlayerInfo entry : player.connection.getOnlinePlayers())
		{
			Object profile = entry.getProfile();
			if(profile == null)
				continue;
			
			String name = extractProfileName(profile);
			if(name.isEmpty())
				continue;
			
			UUID uuid = extractProfileUuid(profile);
			if(uuid == null)
				continue;
			
			if(UUIDUtil.createOfflinePlayerUUID(name).equals(uuid))
			{
				markServerCracked(server,
					"player " + name + " uses offline UUID");
				break;
			}
		}
	}
	
	private void detectCrackedServerFromSelf(LocalPlayer player,
		ServerData server)
	{
		if(player == null || server == null || !crackedDetection.isChecked())
			return;
		
		UUID actualUuid = player.getUUID();
		if(actualUuid == null)
			return;
		
		String name = player.getName().getString();
		if(name == null || name.isBlank())
			return;
		
		UUID offlineUuid = UUIDUtil.createOfflinePlayerUUID(name.trim());
		if(actualUuid.equals(offlineUuid))
			markServerCracked(server, "your session uses offline UUID");
	}
	
	private static String extractProfileName(Object profile)
	{
		if(profile == null)
			return "";
		
		if(PROFILE_NAME_METHOD != null)
		{
			try
			{
				return (String)PROFILE_NAME_METHOD.invoke(profile);
			}catch(Throwable ignored)
			{}
		}
		
		String asString = profile.toString();
		String marker = "name=";
		int index = asString.indexOf(marker);
		if(index >= 0)
		{
			int start = index + marker.length();
			int end = asString.indexOf(',', start);
			if(end < 0)
				end = asString.length();
			return asString.substring(start, end).trim();
		}
		
		return asString;
	}
	
	private static UUID extractProfileUuid(Object profile)
	{
		if(profile == null)
			return null;
		
		if(PROFILE_ID_METHOD != null)
		{
			try
			{
				return (UUID)PROFILE_ID_METHOD.invoke(profile);
			}catch(Throwable ignored)
			{}
		}
		
		String asString = profile.toString();
		String marker = "id=";
		int index = asString.indexOf(marker);
		if(index >= 0)
		{
			int start = index + marker.length();
			int end = asString.indexOf(',', start);
			if(end < 0)
				end = asString.length();
			try
			{
				return UUID.fromString(asString.substring(start, end).trim());
			}catch(IllegalArgumentException ignored)
			{}
		}
		
		return null;
	}
	
	private void tryRunPendingReconnectCommand()
	{
		String command = pendingReconnectCommand;
		if(command == null || command.isEmpty())
		{
			unregisterReconnectCommandRunner();
			return;
		}
		
		Minecraft client = Minecraft.getInstance();
		if(client == null || client.getConnection() == null)
			return;
		
		LocalPlayer player = client.player;
		if(player == null || player.tickCount < 5)
			return;
		
		if(reconnectCommandDelayTicks-- > 0)
			return;
		
		if(command.startsWith("/"))
			client.getConnection().sendCommand(command.substring(1));
		else
			client.getConnection().sendChat(command);
		
		ChatUtils
			.message("[OfflineSettings] Ran reconnect command: " + command);
		pendingReconnectCommand = null;
		reconnectCommandDelayTicks = 0;
		unregisterReconnectCommandRunner();
	}
	
	public void reconnectAndRunCommand()
	{
		reconnectAndRunCommand(null);
	}
	
	public void reconnectAndRunCommand(Screen prevScreen)
	{
		String command = reconnectCommand.getValue().trim();
		if(command.isEmpty())
		{
			ChatUtils.error("OfflineSettings: command cannot be empty.");
			return;
		}
		
		queueReconnectCommand(command);
		reconnectWithSelectedName(prevScreen);
	}
	
	public void queueReconnectCommand(String command)
	{
		String trimmed = command.trim();
		if(trimmed.isEmpty())
			return;
		
		pendingReconnectCommand = trimmed;
		reconnectCommandDelayTicks = 40;
		ChatUtils
			.message("[OfflineSettings] Queued reconnect command: " + trimmed);
		registerReconnectCommandRunner();
	}
	
	private void registerReconnectCommandRunner()
	{
		if(reconnectCommandRunnerRegistered)
			return;
		
		EVENTS.add(UpdateListener.class, reconnectCommandRunner);
		reconnectCommandRunnerRegistered = true;
	}
	
	private void unregisterReconnectCommandRunner()
	{
		if(!reconnectCommandRunnerRegistered)
			return;
		
		EVENTS.remove(UpdateListener.class, reconnectCommandRunner);
		reconnectCommandRunnerRegistered = false;
	}
	
	public boolean wasLastServerCracked()
	{
		ServerData last = LastServerRememberer.getLastServer();
		if(last == null)
			return false;
		
		String key = serverKey(last);
		if(key.isEmpty())
			return false;
		
		return crackedServers.getOrDefault(key, Boolean.FALSE);
	}
	
	public void recordHandshakeEncryption(boolean encryptionUsed)
	{
		if(encryptionUsed || !crackedDetection.isChecked())
			return;
		
		ServerData server = getCurrentOrLastServer();
		if(server == null)
			return;
		
		markServerCracked(server, "login skipped encryption");
	}
	
	private ServerData getCurrentOrLastServer()
	{
		Minecraft client = Minecraft.getInstance();
		if(client != null && client.getCurrentServer() != null)
			return client.getCurrentServer();
		
		return LastServerRememberer.getLastServer();
	}
	
	private String serverKey(ServerData server)
	{
		if(server == null || server.ip == null)
			return "";
		
		return server.ip.trim().toLowerCase(Locale.ROOT);
	}
	
	private void markServerCracked(ServerData server, String reason)
	{
		if(server == null || reason == null || reason.isEmpty())
			return;
		
		String key = serverKey(server);
		if(key.isEmpty())
			return;
		
		Boolean previous = crackedServers.put(key, Boolean.TRUE);
		boolean already = previous != null && previous.booleanValue();
		String announceKey = key;
		
		if(!already && crackedDetection.isChecked()
			&& announcedCrackedServers.add(announceKey))
		{
			Minecraft client = Minecraft.getInstance();
			Runnable messageTask = () -> ChatUtils
				.message("[OfflineSettings] Detected cracked server: "
					+ server.ip + " (" + reason + ").");
			if(client != null)
				client.execute(messageTask);
			else
				messageTask.run();
		}
		
	}
	
	private void runCrackedDetectionUpdate()
	{
		if(isEnabled() || !crackedDetection.isChecked())
			return;
		
		if(crackedDetectionTicks-- > 0)
			return;
		
		crackedDetectionTicks = 40;
		captureOnlinePlayers(Minecraft.getInstance());
	}
	
	private void updateCrackedDetectionSubscription()
	{
		boolean shouldListen = crackedDetection.isChecked() && !isEnabled();
		
		if(shouldListen == crackedDetectionRegistered)
			return;
		
		if(shouldListen)
		{
			EVENTS.add(UpdateListener.class, crackedDetectionUpdate);
			crackedDetectionRegistered = true;
			crackedDetectionTicks = 0;
		}else
		{
			EVENTS.remove(UpdateListener.class, crackedDetectionUpdate);
			crackedDetectionRegistered = false;
		}
	}
	
	private void startLogoutProbe()
	{
		triggerLogoutProbe(otherPlayerName.getSelected(), null, false, false);
	}
	
	private void runLogoutProbe(String host, int port, String username)
	{
		try(Socket socket = new Socket())
		{
			socket.connect(new InetSocketAddress(host, port),
				LOGOUT_PROBE_CONNECT_TIMEOUT_MS);
			socket.setSoTimeout(LOGOUT_PROBE_READ_TIMEOUT_MS);
			DataInputStream input =
				new DataInputStream(socket.getInputStream());
			OutputStream output = socket.getOutputStream();
			byte[] handshakePayload = buildHandshakePayload(host, port);
			byte[] loginPayload = buildLoginPayload(username);
			sendProbePacket(output, 0x00, handshakePayload);
			sendProbePacket(output, 0x00, loginPayload);
			long deadline =
				System.currentTimeMillis() + LOGOUT_PROBE_DURATION_MS;
			int compressionThreshold = -1;
			while(System.currentTimeMillis() < deadline)
			{
				byte[] body = readProbePacket(input, compressionThreshold);
				if(body == null)
					break;
				ByteArrayInputStream packetIn = new ByteArrayInputStream(body);
				int packetId = readVarInt(packetIn);
				byte[] payload = packetIn.readAllBytes();
				if(packetId == 0x00)
				{
					String reason =
						readString(new ByteArrayInputStream(payload));
					sendLogoutMessage(
						"Server disconnected login attempt: " + reason);
					return;
				}
				if(packetId == 0x01)
				{
					sendLogoutMessage(
						"Server requested encryption/auth information.");
					continue;
				}
				if(packetId == 0x02)
				{
					sendLogoutMessage(
						"Server accepted the login for " + username + ".");
					return;
				}
				if(packetId == 0x03)
				{
					int threshold =
						readVarInt(new ByteArrayInputStream(payload));
					compressionThreshold = threshold;
					sendLogoutMessage("Server enabled compression (threshold "
						+ threshold + ").");
					continue;
				}
				sendLogoutMessage(String.format(Locale.ROOT,
					"Unhandled login packet 0x%02X (%d bytes).", packetId,
					payload.length));
			}
			sendLogoutMessage(
				"Login probe timed out without a clear server response.");
		}catch(IOException | DataFormatException e)
		{
			sendLogoutError("Logout probe failed: " + e.getMessage());
		}
	}
	
	private boolean triggerLogoutProbe(String username, ServerData server,
		boolean silentErrors, boolean autoTriggered)
	{
		if(username == null || username.isEmpty())
		{
			if(!silentErrors)
				sendLogoutError(
					"Select another player's name before using logout.");
			return false;
		}
		
		if(server == null)
			server = getCurrentOrLastServer();
		if(server == null)
		{
			if(!silentErrors)
				sendLogoutError("No recent server to probe.");
			return false;
		}
		
		ServerAddress address = ServerAddress.parseString(server.ip);
		String host = address.getHost();
		int port = address.getPort();
		if(host == null || host.isBlank())
		{
			if(!silentErrors)
				sendLogoutError("Invalid server address.");
			return false;
		}
		
		if(!logoutProbeRunning.compareAndSet(false, true))
		{
			if(!silentErrors)
				sendLogoutError("Another logout probe is already running.");
			return false;
		}
		
		String message;
		if(autoTriggered)
			message = String.format(Locale.ROOT,
				"Auto-logging out %s on %s:%d...", username, host, port);
		else
			message = String.format(Locale.ROOT, "Probing %s:%d as %s...", host,
				port, username);
		sendLogoutMessage(message);
		
		Thread thread = new Thread(() -> {
			try
			{
				runLogoutProbe(host, port, username);
			}finally
			{
				logoutProbeRunning.set(false);
			}
		}, "OfflineSettings-LogoutProbe");
		thread.setDaemon(true);
		thread.start();
		return true;
	}
	
	private byte[] readProbePacket(DataInputStream input,
		int compressionThreshold) throws IOException, DataFormatException
	{
		int length = readVarInt(input);
		if(length <= 0)
			return null;
		byte[] data = new byte[length];
		input.readFully(data);
		if(compressionThreshold < 0)
			return data;
		ByteArrayInputStream packetStream = new ByteArrayInputStream(data);
		int dataLength = readVarInt(packetStream);
		if(dataLength == 0)
			return packetStream.readAllBytes();
		byte[] compressed = packetStream.readAllBytes();
		Inflater inflater = new Inflater();
		inflater.setInput(compressed);
		byte[] buffer = new byte[dataLength];
		int read = inflater.inflate(buffer);
		inflater.end();
		if(read < dataLength)
			return Arrays.copyOf(buffer, read);
		return buffer;
	}
	
	private byte[] buildHandshakePayload(String host, int port)
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.writeBytes(encodeVarInt(
			SharedConstants.getCurrentVersion().protocolVersion()));
		writeString(payload, host);
		payload.write((port >> 8) & 0xFF);
		payload.write(port & 0xFF);
		payload.writeBytes(encodeVarInt(2));
		return payload.toByteArray();
	}
	
	private byte[] buildLoginPayload(String username)
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		writeString(payload, username);
		UUID uuid = UUID.nameUUIDFromBytes(
			("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
		ByteBuffer buffer = ByteBuffer.allocate(16);
		buffer.putLong(uuid.getMostSignificantBits());
		buffer.putLong(uuid.getLeastSignificantBits());
		payload.writeBytes(buffer.array());
		return payload.toByteArray();
	}
	
	private void sendProbePacket(OutputStream output, int packetId,
		byte[] payload) throws IOException
	{
		ByteArrayOutputStream packet = new ByteArrayOutputStream();
		packet.writeBytes(encodeVarInt(packetId));
		packet.writeBytes(payload);
		byte[] body = packet.toByteArray();
		output.write(encodeVarInt(body.length));
		output.write(body);
		output.flush();
	}
	
	private static byte[] encodeVarInt(int value)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int v = value;
		do
		{
			int temp = v & 0x7F;
			v >>>= 7;
			if(v != 0)
				temp |= 0x80;
			out.write(temp);
		}while(v != 0);
		return out.toByteArray();
	}
	
	private static int readVarInt(InputStream in) throws IOException
	{
		int numRead = 0;
		int result = 0;
		int read;
		do
		{
			read = in.read();
			if(read == -1)
				throw new IOException("Unexpected end of stream");
			int value = read & 0x7F;
			result |= value << (7 * numRead);
			numRead++;
			if(numRead > 5)
				throw new IOException("VarInt is too large");
		}while((read & 0x80) != 0);
		return result;
	}
	
	private static void writeString(ByteArrayOutputStream out, String value)
	{
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		out.writeBytes(encodeVarInt(bytes.length));
		out.writeBytes(bytes);
	}
	
	private static String readString(ByteArrayInputStream in)
	{
		try
		{
			int length = readVarInt(in);
			if(length <= 0)
				return "";
			byte[] bytes = in.readNBytes(length);
			return new String(bytes, StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			return "(invalid disconnect message)";
		}
	}
	
	private void sendLogoutMessage(String text)
	{
		Minecraft client = Minecraft.getInstance();
		Runnable task = () -> ChatUtils.message("[OfflineSettings] " + text);
		if(client != null)
			client.execute(task);
		else
			task.run();
	}
	
	private void sendLogoutError(String text)
	{
		Minecraft client = Minecraft.getInstance();
		Runnable task = () -> ChatUtils.error("OfflineSettings: " + text);
		if(client != null)
			client.execute(task);
		else
			task.run();
	}
}
