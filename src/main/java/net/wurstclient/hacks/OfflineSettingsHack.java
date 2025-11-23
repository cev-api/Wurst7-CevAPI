/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
	
	private static final Method PROFILE_NAME_METHOD =
		discoverProfileNameMethod();
	private static final Method PROFILE_ID_METHOD = discoverProfileIdMethod();
	
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
		new CheckboxSetting("Auto reconnect",
			WText.literal("Immediately reconnect when you are kicked for "
				+ "logging in elsewhere."),
			true);
	
	private final CheckboxSetting randomName =
		new CheckboxSetting("Random name",
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
	
	private final TextFieldSetting specifiedName = new TextFieldSetting(
		"Specified name",
		WText.literal("Force reconnects to use this offline name."), "",
		s -> s.isEmpty() || (s.length() <= 16 && s.matches("[A-Za-z0-9_]+")));
	
	private final StringDropdownSetting otherPlayerName =
		new StringDropdownSetting("Other player name",
			WText.literal("Pick another player's name from the last server."));
	
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
	private volatile ServerData pendingServer;
	private volatile boolean pendingForceRandom;
	private volatile boolean pendingAllowRandomFallback;
	private volatile String pendingReconnectCommand;
	private int reconnectCommandDelayTicks;
	private final UpdateListener crackedDetectionUpdate =
		this::runCrackedDetectionUpdate;
	private boolean crackedDetectionRegistered;
	private int crackedDetectionTicks;
	
	private final Map<String, Boolean> crackedServers =
		new ConcurrentHashMap<>();
	private final Set<String> announcedCrackedServers =
		Collections.newSetFromMap(new ConcurrentHashMap<>());
	
	public OfflineSettingsHack()
	{
		super("OfflineSettings");
		setCategory(Category.OTHER);
		addSetting(autoReconnect);
		addSetting(randomName);
		addSetting(crackedDetection);
		addSetting(specifiedName);
		addSetting(otherPlayerName);
		addSetting(reconnectButton);
		addSetting(reconnectCommand);
		addSetting(reconnectCommandButton);
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
		cleanupTemporaryAlt();
		pendingReconnectCommand = null;
		reconnectCommandDelayTicks = 0;
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
		
		ServerAddress address = ServerAddress.parseString(server.ip);
		Screen previous = resolvePrevScreen(prevScreen, client);
		ConnectScreen.startConnecting(previous, client, address, server, false,
			null);
	}
	
	private Screen resolvePrevScreen(Screen requested, Minecraft client)
	{
		if(requested != null)
			return requested;
		
		return client != null ? client.screen : null;
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
		
		otherPlayerName.setOptions(players);
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
			return;
		
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
		
		scheduleReconnectCommand(command);
		reconnectWithSelectedName(prevScreen);
	}
	
	private void scheduleReconnectCommand(String command)
	{
		String trimmed = command.trim();
		if(trimmed.isEmpty())
			return;
		
		pendingReconnectCommand = trimmed;
		reconnectCommandDelayTicks = 40;
		ChatUtils
			.message("[OfflineSettings] Queued reconnect command: " + trimmed);
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
}
