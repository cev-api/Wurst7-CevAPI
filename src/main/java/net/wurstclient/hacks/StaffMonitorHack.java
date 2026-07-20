/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.DisconnectContext;
import net.wurstclient.util.FakePlayerEntity;
import net.wurstclient.util.RenderUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;

@SearchTags({"staff monitor", "spectator monitor", "spectator detector",
	"gamemode monitor", "creative monitor", "spec detector",
	"hidden player detector", "vanish detector", "staff detector"})
public final class StaffMonitorHack extends Hack implements UpdateListener
{
	private enum AlertSound
	{
		HARP("minecraft:block.note_block.harp"),
		BASS("minecraft:block.note_block.bass"),
		BASEDRUM("minecraft:block.note_block.basedrum"),
		SNARE("minecraft:block.note_block.snare"),
		HAT("minecraft:block.note_block.hat"),
		GUITAR("minecraft:block.note_block.guitar"),
		FLUTE("minecraft:block.note_block.flute"),
		BELL("minecraft:block.note_block.bell"),
		CHIME("minecraft:block.note_block.chime"),
		XYLOPHONE("minecraft:block.note_block.xylophone"),
		IRON_XYLOPHONE("minecraft:block.note_block.iron_xylophone"),
		COW_BELL("minecraft:block.note_block.cow_bell"),
		DIDGERIDOO("minecraft:block.note_block.didgeridoo"),
		BIT("minecraft:block.note_block.bit"),
		BANJO("minecraft:block.note_block.banjo"),
		PLING("minecraft:block.note_block.pling");
		
		private final String id;
		
		private AlertSound(String id)
		{
			this.id = id;
		}
		
		private SoundEvent resolve()
		{
			return BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id));
		}
		
		@Override
		public String toString()
		{
			return id;
		}
	}
	
	private final CheckboxSetting chatAlert =
		new CheckboxSetting("Chat alert", true);
	private final CheckboxSetting soundAlert =
		new CheckboxSetting("Sound alert", true);
	private final EnumSetting<AlertSound> sound =
		new EnumSetting<>("Sound", AlertSound.values(), AlertSound.CHIME);
	private final SliderSetting volume = new SliderSetting("Volume", 100, 0,
		200, 1, ValueDisplay.INTEGER.withSuffix("%"));
	private final CheckboxSetting monitorModeSwitching = new CheckboxSetting(
		"Monitor mode switching",
		"Alert when players switch gamemodes (survival \u2194 creative \u2194"
			+ " adventure \u2194 spectator).",
		true);
	private final CheckboxSetting ignoreSelf =
		new CheckboxSetting("Ignore self", true);
	private final CheckboxSetting hiddenPlayerAlerts = new CheckboxSetting(
		"Hidden player alerts",
		"Alerts when a player entity is visible to the client but missing from"
			+ " the tab list.\n\n"
			+ "This can reveal vanished players, but some servers also use"
			+ " off-tab player entities for NPCs.",
		true);
	private final CheckboxSetting ignoreNpcNames = new CheckboxSetting(
		"Ignore NPC names",
		"Filters common server NPC/bot names from hidden player alerts, such "
			+ "as CIT-... fake player entries.",
		true);
	private final CheckboxSetting hiddenStaffAlerts = new CheckboxSetting(
		"Hidden staff alerts",
		"Alerts when a player from the local staff list appears in tab.\n\n"
			+ "Staff names are loaded from .minecraft/wurst/staff/<server>.txt"
			+ " and .minecraft/wurst/staff/global.txt, one name per line.",
		true);
	private final Setting savedStaffList =
		new Setting("Saved staff list", net.wurstclient.util.text.WText
			.literal("Names detected by StaffMonitor on this server."))
		{
			@Override
			public net.wurstclient.clickgui.Component getComponent()
			{
				return new SavedStaffListComponent();
			}
			
			@Override
			public void fromJson(com.google.gson.JsonElement json)
			{
				// read-only
			}
			
			@Override
			public com.google.gson.JsonElement toJson()
			{
				return com.google.gson.JsonNull.INSTANCE;
			}
			
			@Override
			public com.google.gson.JsonObject exportWikiData()
			{
				com.google.gson.JsonObject json =
					new com.google.gson.JsonObject();
				json.addProperty("name", getName());
				json.addProperty("description", getDescription());
				json.addProperty("type", "Custom");
				return json;
			}
			
			@Override
			public java.util.Set<net.wurstclient.keybinds.PossibleKeybind> getPossibleKeybinds(
				String featureName)
			{
				return java.util.Collections.emptySet();
			}
		};
	private final TextFieldSetting addStaffUsernameField = new TextFieldSetting(
		"Add staff username", "", s -> s.isEmpty() || isValidStaffUsername(s));
	private final ButtonSetting addStaffUsernameButton =
		new ButtonSetting("Add staff username button",
			"Adds the typed username to this server's saved" + " staff list.",
			this::addStaffUsername);
	private final CheckboxSetting quitOnStaffEnter = new CheckboxSetting(
		"Quit on staff enter",
		"Leaves the server when StaffMonitor detects a staff member entering"
			+ " a monitored mode. AutoReconnect is disabled first.",
		false);
	private final SliderSetting quitDelay = new SliderSetting("Quit delay",
		"Time to wait before quitting after staff are detected.", 0, 0, 60, 1,
		ValueDisplay.INTEGER.withSuffix("s"));
	
	private final Map<UUID, GameType> gamemodeStates = new HashMap<>();
	private final Map<UUID, String> hiddenPlayers = new HashMap<>();
	private final Set<String> staffNames = new HashSet<>();
	private final LinkedHashSet<String> savedStaffNames = new LinkedHashSet<>();
	private final Set<UUID> alertedStaff = new HashSet<>();
	private String lastServerKey = "unknown";
	private String savedStaffServerKey = "unknown";
	private boolean hiddenPlayerAlertsActive;
	private int staffQuitTicks = -1;
	private String staffQuitReason;
	
	/** Used by safety-aware modules without invoking StaffMonitor's action. */
	public boolean hasDetectedStaff()
	{
		return !alertedStaff.isEmpty() || !hiddenPlayers.isEmpty();
	}
	
	public StaffMonitorHack()
	{
		super("StaffMonitor");
		setCategory(Category.INTEL);
		addSetting(chatAlert);
		addSetting(soundAlert);
		addSetting(sound);
		addSetting(volume);
		addSetting(monitorModeSwitching);
		addSetting(ignoreSelf);
		addSetting(hiddenPlayerAlerts);
		addSetting(ignoreNpcNames);
		addSetting(hiddenStaffAlerts);
		addSetting(savedStaffList);
		addSetting(addStaffUsernameField);
		addSetting(addStaffUsernameButton);
		addSetting(quitOnStaffEnter);
		addSetting(quitDelay);
	}
	
	@Override
	protected void onEnable()
	{
		gamemodeStates.clear();
		hiddenPlayers.clear();
		alertedStaff.clear();
		savedStaffNames.clear();
		lastServerKey = resolveServerKey();
		savedStaffServerKey = resolveStorageServerKey();
		hiddenPlayerAlertsActive = hiddenPlayerAlerts.isChecked();
		loadStaffNames();
		loadSavedStaffNames();
		snapshotCurrentStates();
		checkForStaffPresence();
		if(hiddenPlayerAlertsActive)
			snapshotHiddenPlayers();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		gamemodeStates.clear();
		hiddenPlayers.clear();
		alertedStaff.clear();
		staffNames.clear();
		savedStaffNames.clear();
		hiddenPlayerAlertsActive = false;
		cancelStaffQuit();
	}
	
	@Override
	public void onUpdate()
	{
		tickStaffQuit();
		
		if(MC.getConnection() == null)
		{
			gamemodeStates.clear();
			hiddenPlayers.clear();
			hiddenPlayerAlertsActive = false;
			savedStaffNames.clear();
			cancelStaffQuit();
			return;
		}
		
		String serverKeyNow = resolveServerKey();
		if(!serverKeyNow.equals(lastServerKey))
		{
			gamemodeStates.clear();
			hiddenPlayers.clear();
			alertedStaff.clear();
			lastServerKey = serverKeyNow;
			savedStaffServerKey = resolveStorageServerKey();
			hiddenPlayerAlertsActive = hiddenPlayerAlerts.isChecked();
			loadStaffNames();
			loadSavedStaffNames();
			snapshotCurrentStates();
			if(hiddenPlayerAlertsActive)
				snapshotHiddenPlayers();
			cancelStaffQuit();
			return;
		}
		
		boolean modeSwitching = monitorModeSwitching.isChecked();
		
		updateHiddenPlayers();
		
		HashMap<UUID, GameType> nextStates = new HashMap<>();
		for(PlayerInfo info : MC.getConnection().getOnlinePlayers())
		{
			UUID id = info.getProfile().id();
			if(ignoreSelf.isChecked() && MC.player != null
				&& id.equals(MC.player.getUUID()))
				continue;
			
			GameType currentMode = info.getGameMode();
			nextStates.put(id, currentMode);
			
			// Hidden staff alert
			if(hiddenStaffAlerts.isChecked()
				&& isStaffName(info.getProfile().name())
				&& alertedStaff.add(id))
				alertStaff(info);
			
			GameType previous = gamemodeStates.get(id);
			if(previous == null)
			{
				// First time seeing this player.
				if(modeSwitching && isStaffMode(currentMode))
				{
					alert(info, currentMode, true);
					recordSavedStaffMember(info.getProfile().name());
				}
				if(quitOnStaffEnter.isChecked()
					&& isStaffName(info.getProfile().name()))
					queueStaffQuit(info.getProfile().name());
				continue;
			}
			
			// Gamemode changed — alert on any transition
			if(modeSwitching && previous != currentMode)
			{
				alert(info, currentMode, true);
				if(isStaffMode(previous) || isStaffMode(currentMode))
					recordSavedStaffMember(info.getProfile().name());
			}
		}
		
		gamemodeStates.clear();
		gamemodeStates.putAll(nextStates);
		tickStaffQuit();
	}
	
	private void updateHiddenPlayers()
	{
		if(!hiddenPlayerAlerts.isChecked())
		{
			hiddenPlayers.clear();
			hiddenPlayerAlertsActive = false;
			return;
		}
		
		if(!hiddenPlayerAlertsActive)
		{
			hiddenPlayerAlertsActive = true;
			snapshotHiddenPlayers();
			return;
		}
		
		if(MC.level == null)
		{
			hiddenPlayers.clear();
			return;
		}
		
		HashMap<UUID, String> nextHiddenPlayers = new HashMap<>();
		for(Player player : MC.level.players())
		{
			if(shouldIgnorePlayerEntity(player))
				continue;
			
			UUID id = player.getUUID();
			if(MC.getConnection().getPlayerInfo(id) != null)
				continue;
			
			String name = player.getName().getString();
			nextHiddenPlayers.put(id, name);
			if(!hiddenPlayers.containsKey(id))
				alertHiddenPlayer(name, true);
		}
		
		for(Map.Entry<UUID, String> entry : hiddenPlayers.entrySet())
			if(!nextHiddenPlayers.containsKey(entry.getKey()))
				alertHiddenPlayer(entry.getValue(), false);
			
		hiddenPlayers.clear();
		hiddenPlayers.putAll(nextHiddenPlayers);
	}
	
	private void snapshotCurrentStates()
	{
		if(MC.getConnection() == null)
			return;
		
		for(PlayerInfo info : MC.getConnection().getOnlinePlayers())
		{
			UUID id = info.getProfile().id();
			if(ignoreSelf.isChecked() && MC.player != null
				&& id.equals(MC.player.getUUID()))
				continue;
			
			gamemodeStates.put(id, info.getGameMode());
		}
	}
	
	private void snapshotHiddenPlayers()
	{
		hiddenPlayers.clear();
		if(MC.getConnection() == null || MC.level == null)
			return;
		
		for(Player player : MC.level.players())
		{
			if(shouldIgnorePlayerEntity(player))
				continue;
			
			UUID id = player.getUUID();
			if(MC.getConnection().getPlayerInfo(id) != null)
				continue;
			
			hiddenPlayers.put(id, player.getName().getString());
		}
	}
	
	private boolean shouldIgnorePlayerEntity(Player player)
	{
		if(player == null || player instanceof FakePlayerEntity)
			return true;
		
		if(ignoreNpcNames.isChecked()
			&& isLikelyNpcName(player.getName().getString()))
			return true;
		
		return ignoreSelf.isChecked() && MC.player != null
			&& player.getUUID().equals(MC.player.getUUID());
	}
	
	private boolean isLikelyNpcName(String name)
	{
		if(name == null)
			return false;
		
		String stripped = name.strip();
		if(stripped.isEmpty())
			return true;
		
		String lower = stripped.toLowerCase(Locale.ROOT);
		if(lower.matches("cit-[0-9a-f-]{6,}"))
			return true;
		
		return lower.contains("npc") || lower.startsWith("[npc]")
			|| lower.endsWith("_npc") || lower.startsWith("bot_")
			|| lower.endsWith("_bot");
	}
	
	private void alert(PlayerInfo info, GameType mode, boolean entered)
	{
		String name = info.getProfile().name();
		String modeLabel = mode.getName(); // "survival", "creative",
											// "spectator", "adventure"
		if(chatAlert.isChecked())
		{
			String action = entered ? "entered" : "left";
			ChatUtils.component(Component.literal(String.format(Locale.ROOT,
				"[StaffMonitor] %s %s %s mode.", name, action, modeLabel)));
		}
		
		if(soundAlert.isChecked() && MC.level != null && MC.player != null)
		{
			SoundEvent event = sound.getSelected().resolve();
			float target = (float)(volume.getValue() / 100.0);
			if(event == null || target <= 0F)
				return;
			
			int whole = (int)target;
			float remainder = target - whole;
			double x = MC.player.getX();
			double y = MC.player.getY();
			double z = MC.player.getZ();
			for(int i = 0; i < whole; i++)
				MC.level.playLocalSound(x, y, z, event, SoundSource.PLAYERS, 1F,
					entered ? 1.2F : 0.85F, false);
			if(remainder > 0F)
				MC.level.playLocalSound(x, y, z, event, SoundSource.PLAYERS,
					remainder, entered ? 1.2F : 0.85F, false);
		}
	}
	
	private void alertStaff(PlayerInfo info)
	{
		String name = info.getProfile().name();
		if(chatAlert.isChecked())
			ChatUtils.component(Component.literal(String.format(Locale.ROOT,
				"[StaffMonitor] Staff member %s is online.", name)));
		
		if(soundAlert.isChecked() && MC.level != null && MC.player != null)
		{
			SoundEvent event = sound.getSelected().resolve();
			float target = (float)(volume.getValue() / 100.0);
			if(event == null || target <= 0F)
				return;
			
			MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
				MC.player.getZ(), event, SoundSource.PLAYERS,
				Math.max(0.2F, target), 1.6F, false);
		}
	}
	
	private void alertHiddenPlayer(String name, boolean appeared)
	{
		if(chatAlert.isChecked())
		{
			String action = appeared ? "appeared off-tab" : "disappeared";
			ChatUtils.component(Component.literal(String.format(Locale.ROOT,
				"[StaffMonitor] %s %s.", name, action)));
		}
		
		if(soundAlert.isChecked() && MC.level != null && MC.player != null)
		{
			SoundEvent event = sound.getSelected().resolve();
			float target = (float)(volume.getValue() / 100.0);
			if(event == null || target <= 0F)
				return;
			
			MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
				MC.player.getZ(), event, SoundSource.PLAYERS,
				Math.max(0.2F, target), appeared ? 1.6F : 0.7F, false);
		}
	}
	
	private void loadStaffNames()
	{
		staffNames.clear();
		if(!hiddenStaffAlerts.isChecked())
			return;
		
		Path folder = WURST.getWurstFolder().resolve("staff");
		loadStaffFile(folder.resolve("global.txt"));
		loadStaffFile(folder.resolve(lastServerKey + ".txt"));
	}
	
	private void loadSavedStaffNames()
	{
		savedStaffNames.clear();
		Path file = getSavedStaffFile();
		if(!Files.isRegularFile(file))
			return;
		
		try
		{
			for(String line : Files.readAllLines(file, StandardCharsets.UTF_8))
			{
				String name = line.strip();
				if(name.isEmpty()
					|| containsNameIgnoreCase(savedStaffNames, name))
					continue;
				savedStaffNames.add(name);
			}
		}catch(IOException e)
		{
			ChatUtils
				.error("StaffMonitor saved list failed: " + e.getMessage());
		}
	}
	
	private void recordSavedStaffMember(String name)
	{
		if(name == null)
			return;
		
		String trimmed = name.strip();
		if(trimmed.isEmpty()
			|| containsNameIgnoreCase(savedStaffNames, trimmed))
			return;
		
		savedStaffNames.add(trimmed);
		saveSavedStaffNames();
	}
	
	private void addStaffUsername()
	{
		String name = addStaffUsernameField.getValue().strip();
		if(name.isEmpty())
		{
			ChatUtils.error("StaffMonitor: username cannot be empty.");
			return;
		}
		
		if(!isValidStaffUsername(name))
		{
			ChatUtils.error("StaffMonitor: invalid username.");
			return;
		}
		
		if(containsNameIgnoreCase(savedStaffNames, name))
		{
			ChatUtils.message("[StaffMonitor] " + name
				+ " is already in the saved staff list.");
			return;
		}
		
		savedStaffNames.add(name);
		saveSavedStaffNames();
		addStaffUsernameField.setValue("");
		ChatUtils.message("[StaffMonitor] Added staff username: " + name);
	}
	
	private void saveSavedStaffNames()
	{
		Path file = getSavedStaffFile();
		try
		{
			Files.createDirectories(file.getParent());
			Files.write(file, savedStaffNames, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);
		}catch(IOException e)
		{
			ChatUtils
				.error("StaffMonitor saved list failed: " + e.getMessage());
		}
	}
	
	private Path getSavedStaffFile()
	{
		return WURST.getWurstFolder().resolve("staff-monitor")
			.resolve(savedStaffServerKey + ".txt");
	}
	
	private void cancelStaffQuit()
	{
		staffQuitTicks = -1;
		staffQuitReason = null;
	}
	
	private void checkForStaffPresence()
	{
		if(!quitOnStaffEnter.isChecked() || MC.getConnection() == null)
			return;
		
		for(PlayerInfo info : MC.getConnection().getOnlinePlayers())
		{
			String name = info.getProfile().name();
			if(isStaffName(name))
			{
				queueStaffQuit(name);
				return;
			}
		}
	}
	
	private void queueStaffQuit(String staffName)
	{
		if(!quitOnStaffEnter.isChecked() || staffName == null
			|| staffName.isBlank())
			return;
		
		if(staffQuitTicks >= 0)
			return;
		
		staffQuitTicks = quitDelay.getValueI() * 20;
		staffQuitReason = staffName + " joined the server.";
		
		if(chatAlert.isChecked())
			ChatUtils.component(Component.literal(String.format(Locale.ROOT,
				"[StaffMonitor] Staff member %s is online.", staffName)));
		
		if(soundAlert.isChecked() && MC.level != null && MC.player != null)
		{
			SoundEvent event = sound.getSelected().resolve();
			float target = (float)(volume.getValue() / 100.0);
			if(event != null && target > 0F)
			{
				MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
					MC.player.getZ(), event, SoundSource.PLAYERS,
					Math.max(0.2F, target), 1.6F, false);
			}
		}
		
		AutoReconnectHack autoReconnect =
			WURST.getHax() == null ? null : WURST.getHax().autoReconnectHack;
		if(autoReconnect != null && autoReconnect.isEnabled())
			autoReconnect.setEnabled(false);
		
		ChatUtils.message("[StaffMonitor] Staff detected: " + staffName
			+ ". Quitting in " + quitDelay.getValueI() + "s.");
	}
	
	private void tickStaffQuit()
	{
		if(staffQuitTicks < 0)
			return;
		
		if(MC.getConnection() == null || MC.level == null)
		{
			cancelStaffQuit();
			return;
		}
		
		if(staffQuitTicks > 0)
		{
			staffQuitTicks--;
			return;
		}
		
		performStaffQuit();
	}
	
	private void performStaffQuit()
	{
		if(MC.level == null)
		{
			cancelStaffQuit();
			return;
		}
		
		AutoReconnectHack autoReconnect =
			WURST.getHax() == null ? null : WURST.getHax().autoReconnectHack;
		if(autoReconnect != null && autoReconnect.isEnabled())
			autoReconnect.setEnabled(false);
		
		Component reason =
			DisconnectContext.createAutoQuitReason("StaffMonitor",
				MC.player == null ? null : MC.player.blockPosition(),
				staffQuitReason);
		DisconnectContext.markExpectedDisconnect(reason.getString());
		MC.level.disconnect(reason);
		cancelStaffQuit();
	}
	
	private static boolean containsNameIgnoreCase(Set<String> names,
		String name)
	{
		if(names == null || name == null)
			return false;
		
		for(String existing : names)
			if(existing != null && existing.equalsIgnoreCase(name))
				return true;
		return false;
	}
	
	private static boolean isValidStaffUsername(String name)
	{
		return name != null && name.length() <= 16
			&& name.matches("[A-Za-z0-9_]+");
	}
	
	private boolean isStaffMode(GameType mode)
	{
		return mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
	}
	
	private void loadStaffFile(Path file)
	{
		if(!Files.isRegularFile(file))
			return;
		
		try
		{
			for(String line : Files.readAllLines(file))
			{
				String name = line.strip();
				if(name.isEmpty() || name.startsWith("#"))
					continue;
				staffNames.add(name.toLowerCase(Locale.ROOT));
			}
		}catch(IOException e)
		{
			ChatUtils
				.error("StaffMonitor staff list failed: " + e.getMessage());
		}
	}
	
	private boolean isStaffName(String name)
	{
		return name != null
			&& (staffNames.contains(name.toLowerCase(Locale.ROOT))
				|| containsNameIgnoreCase(savedStaffNames, name));
	}
	
	private String resolveServerKey()
	{
		ServerData info = MC.getCurrentServer();
		if(info != null)
		{
			if(info.ip != null && !info.ip.isEmpty())
				return info.ip.replace(':', '_');
			if(info.isRealm())
				return "realms_" + (info.name == null ? "" : info.name);
			if(info.name != null && !info.name.isEmpty())
				return "server_" + info.name;
		}
		if(MC.hasSingleplayerServer())
			return "singleplayer";
		return "unknown";
	}
	
	private String resolveStorageServerKey()
	{
		return resolveServerKey().replaceAll("[^A-Za-z0-9._-]", "_");
	}
	
	private ArrayList<String> getSavedStaffLines(int maxWidth)
	{
		if(savedStaffNames.isEmpty())
			return new ArrayList<>(
				Collections.singletonList("No staff detected yet."));
		
		String joined = String.join(", ", savedStaffNames);
		String wrapped = ChatUtils.wrapText(joined, Math.max(40, maxWidth));
		ArrayList<String> lines = new ArrayList<>();
		for(String line : wrapped.split("\n"))
			lines.add(line);
		return lines;
	}
	
	private final class SavedStaffListComponent
		extends net.wurstclient.clickgui.Component
	{
		private SavedStaffListComponent()
		{
			setWidth(getDefaultWidth());
			setHeight(getDefaultHeight());
		}
		
		@Override
		public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
			int mouseY, float partialTicks)
		{
			int width = getWidth();
			int x1 = getX();
			int y1 = getY();
			int x2 = x1 + width;
			int lineHeight = MC.font.lineHeight + 1;
			ArrayList<String> lines = getSavedStaffLines(width - 8);
			int neededHeight = 6 + lineHeight * (lines.size() + 1);
			if(getHeight() != neededHeight)
				setHeight(neededHeight);
			
			int bgColor = RenderUtils.toIntColor(WURST.getGui().getBgColor(),
				WURST.getGui().getOpacity());
			context.fill(x1, y1, x2, y1 + getHeight(), bgColor);
			
			int txtColor = WURST.getGui().getTxtColor();
			context.text(MC.font,
				"Saved staff on this server (" + savedStaffNames.size() + "):",
				x1 + 4, y1 + 2, txtColor, false);
			
			int y = y1 + 2 + lineHeight;
			for(String line : lines)
			{
				context.text(MC.font, line, x1 + 4, y, txtColor, false);
				y += lineHeight;
			}
		}
		
		@Override
		public int getDefaultWidth()
		{
			return 200;
		}
		
		@Override
		public int getDefaultHeight()
		{
			return 6 + (MC.font.lineHeight + 1) * 2;
		}
	}
}
