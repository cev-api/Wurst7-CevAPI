/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
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

@SearchTags({"spectator monitor", "spectator detector", "gamemode monitor",
	"spec detector"})
public final class SpectatorMonitorHack extends Hack implements UpdateListener
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
	private final CheckboxSetting ignoreSelf =
		new CheckboxSetting("Ignore self", true);
	
	private final Map<UUID, Boolean> spectatorStates = new HashMap<>();
	private String lastServerKey = "unknown";
	
	public SpectatorMonitorHack()
	{
		super("SpectatorMonitor");
		setCategory(Category.INTEL);
		addSetting(chatAlert);
		addSetting(soundAlert);
		addSetting(sound);
		addSetting(volume);
		addSetting(ignoreSelf);
	}
	
	@Override
	protected void onEnable()
	{
		spectatorStates.clear();
		lastServerKey = resolveServerKey();
		snapshotCurrentStates();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		spectatorStates.clear();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.getConnection() == null)
		{
			spectatorStates.clear();
			return;
		}
		
		String serverKeyNow = resolveServerKey();
		if(!serverKeyNow.equals(lastServerKey))
		{
			spectatorStates.clear();
			lastServerKey = serverKeyNow;
			snapshotCurrentStates();
			return;
		}
		
		HashMap<UUID, Boolean> nextStates = new HashMap<>();
		for(PlayerInfo info : MC.getConnection().getOnlinePlayers())
		{
			UUID id = info.getProfile().id();
			if(ignoreSelf.isChecked() && MC.player != null
				&& id.equals(MC.player.getUUID()))
				continue;
			
			boolean isSpectator = info.getGameMode() == GameType.SPECTATOR;
			nextStates.put(id, isSpectator);
			
			Boolean previous = spectatorStates.get(id);
			if(previous == null)
			{
				if(isSpectator)
					alert(info, true);
				continue;
			}
			
			if(previous.booleanValue() != isSpectator)
				alert(info, isSpectator);
		}
		
		spectatorStates.clear();
		spectatorStates.putAll(nextStates);
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
			
			spectatorStates.put(id, info.getGameMode() == GameType.SPECTATOR);
		}
	}
	
	private void alert(PlayerInfo info, boolean enteredSpectator)
	{
		String name = info.getProfile().name();
		if(chatAlert.isChecked())
		{
			String action = enteredSpectator ? "entered" : "left";
			ChatUtils.component(Component.literal(String.format(Locale.ROOT,
				"[SpectatorMonitor] %s %s spectator mode.", name, action)));
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
					enteredSpectator ? 1.2F : 0.85F, false);
			if(remainder > 0F)
				MC.level.playLocalSound(x, y, z, event, SoundSource.PLAYERS,
					remainder, enteredSpectator ? 1.2F : 0.85F, false);
		}
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
