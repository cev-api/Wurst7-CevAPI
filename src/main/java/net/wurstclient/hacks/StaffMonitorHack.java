/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.FakePlayerEntity;

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
	private final CheckboxSetting hiddenStaffAlerts = new CheckboxSetting(
		"Hidden staff alerts",
		"Alerts when a player from the local staff list appears in tab.\n\n"
			+ "Staff names are loaded from .minecraft/wurst/staff/<server>.txt"
			+ " and .minecraft/wurst/staff/global.txt, one name per line.",
		true);
	
	private final Map<UUID, GameType> gamemodeStates = new HashMap<>();
	private final Map<UUID, String> hiddenPlayers = new HashMap<>();
	private final Set<String> staffNames = new HashSet<>();
	private final Set<UUID> alertedStaff = new HashSet<>();
	private String lastServerKey = "unknown";
	private boolean hiddenPlayerAlertsActive;
	
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
		addSetting(hiddenStaffAlerts);
	}
	
	@Override
	protected void onEnable()
	{
		gamemodeStates.clear();
		hiddenPlayers.clear();
		alertedStaff.clear();
		lastServerKey = resolveServerKey();
		hiddenPlayerAlertsActive = hiddenPlayerAlerts.isChecked();
		loadStaffNames();
		snapshotCurrentStates();
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
		hiddenPlayerAlertsActive = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.getConnection() == null)
		{
			gamemodeStates.clear();
			hiddenPlayers.clear();
			hiddenPlayerAlertsActive = false;
			return;
		}
		
		String serverKeyNow = resolveServerKey();
		if(!serverKeyNow.equals(lastServerKey))
		{
			gamemodeStates.clear();
			hiddenPlayers.clear();
			alertedStaff.clear();
			lastServerKey = serverKeyNow;
			hiddenPlayerAlertsActive = hiddenPlayerAlerts.isChecked();
			loadStaffNames();
			snapshotCurrentStates();
			if(hiddenPlayerAlertsActive)
				snapshotHiddenPlayers();
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
				// First time seeing this player — only alert if they
				// appear in creative or spectator (potential staff)
				if(modeSwitching && (currentMode == GameType.CREATIVE
					|| currentMode == GameType.SPECTATOR))
					alert(info, currentMode, true);
				continue;
			}
			
			// Gamemode changed — alert on any transition
			if(modeSwitching && previous != currentMode)
				alert(info, currentMode, true);
		}
		
		gamemodeStates.clear();
		gamemodeStates.putAll(nextStates);
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
		
		return ignoreSelf.isChecked() && MC.player != null
			&& player.getUUID().equals(MC.player.getUUID());
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
			&& staffNames.contains(name.toLowerCase(Locale.ROOT));
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
}
