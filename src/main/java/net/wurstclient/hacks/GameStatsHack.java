/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"game stats", "fps", "tps", "ping", "hud"})
public final class GameStatsHack extends Hack implements PacketInputListener,
	PacketOutputListener, UpdateListener, PlayerAttacksEntityListener
{
	private static final long KILL_TRACK_TTL_MS = 12000L;
	private static final long KILL_TRACK_MISSING_CONFIRM_MS = 750L;
	
	private final SliderSetting fontScale = new SliderSetting("Font size", 1.0,
		0.5, 3.0, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting showFps =
		new CheckboxSetting("Show FPS", true);
	
	private final CheckboxSetting showTps =
		new CheckboxSetting("Show TPS", true);
	
	private final CheckboxSetting showPing =
		new CheckboxSetting("Show Ping", true);
	
	private final CheckboxSetting showPlayTime =
		new CheckboxSetting("Show Play Time", true);
	
	private final CheckboxSetting showCurrentTime =
		new CheckboxSetting("Show Time", true);
	
	private final CheckboxSetting showWorldTime =
		new CheckboxSetting("Show World Time", true);
	
	private final CheckboxSetting showPacketRate =
		new CheckboxSetting("Show Packet Rate", true);
	
	private final CheckboxSetting showDistanceTravelled =
		new CheckboxSetting("Show Distance", true);
	
	private final CheckboxSetting showMobKills =
		new CheckboxSetting("Show Mob Kills", true);
	
	private final CheckboxSetting showPlayerKills =
		new CheckboxSetting("Show Player Kills", true);
	
	private final CheckboxSetting showXpGained =
		new CheckboxSetting("Show XP Gained", true);
	
	private final SliderSetting fontOpacity =
		new SliderSetting("Font opacity", 255, 0, 255, 1, ValueDisplay.INTEGER);
	
	private final ColorSetting fontColor =
		new ColorSetting("Font color", new Color(0xFF, 0xFF, 0xFF, 0xFF));
	
	private final CheckboxSetting fontStroke =
		new CheckboxSetting("Font stroke", true);
	
	private final CheckboxSetting backgroundBox =
		new CheckboxSetting("Background box", true);
	
	private final ColorSetting backgroundColor =
		new ColorSetting("Background color", new Color(0x00, 0x00, 0x00, 0xFF));
	
	private final SliderSetting backgroundOpacity = new SliderSetting(
		"Background opacity", 120, 0, 255, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting hudOffsetX = new SliderSetting("HUD X offset",
		0, -2000, 2000, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting hudOffsetY = new SliderSetting("HUD Y offset",
		0, -2000, 2000, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting showPrefixes =
		new CheckboxSetting("Show Prefixes", true);
	
	private final CheckboxSetting showAverages =
		new CheckboxSetting("Show Averages", true);
	
	private final ArrayDeque<Long> incomingPacketTimes = new ArrayDeque<>();
	private final ArrayDeque<Long> outgoingPacketTimes = new ArrayDeque<>();
	private final Object packetTimesLock = new Object();
	private final Map<Integer, KillTrack> pendingKills = new HashMap<>();
	private int sessionMobKills;
	private int sessionPlayerKills;
	
	public GameStatsHack()
	{
		super("GameStats");
		setCategory(Category.OTHER);
		addSetting(showFps);
		addSetting(showTps);
		addSetting(showPing);
		addSetting(showPlayTime);
		addSetting(showCurrentTime);
		addSetting(showWorldTime);
		addSetting(showPacketRate);
		addSetting(showDistanceTravelled);
		addSetting(showMobKills);
		addSetting(showPlayerKills);
		addSetting(showXpGained);
		addSetting(fontScale);
		addSetting(fontOpacity);
		addSetting(fontColor);
		addSetting(fontStroke);
		addSetting(backgroundBox);
		addSetting(backgroundColor);
		addSetting(backgroundOpacity);
		addSetting(hudOffsetX);
		addSetting(hudOffsetY);
		addSetting(showPrefixes);
		addSetting(showAverages);
	}
	
	@Override
	protected void onEnable()
	{
		synchronized(packetTimesLock)
		{
			incomingPacketTimes.clear();
			outgoingPacketTimes.clear();
		}
		pendingKills.clear();
		sessionMobKills = 0;
		sessionPlayerKills = 0;
		
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PlayerAttacksEntityListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		
		synchronized(packetTimesLock)
		{
			incomingPacketTimes.clear();
			outgoingPacketTimes.clear();
		}
		pendingKills.clear();
		sessionMobKills = 0;
		sessionPlayerKills = 0;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		recordPacket(incomingPacketTimes, System.currentTimeMillis());
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		recordPacket(outgoingPacketTimes, System.currentTimeMillis());
	}
	
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		if(!(target instanceof LivingEntity))
			return;
		
		KillTrack track = new KillTrack();
		track.playerTarget = target instanceof Player;
		track.attackedAtMs = System.currentTimeMillis();
		track.expiresAtMs = track.attackedAtMs + KILL_TRACK_TTL_MS;
		pendingKills.put(target.getId(), track);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || pendingKills.isEmpty())
			return;
		
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<Integer, KillTrack>> it =
			pendingKills.entrySet().iterator();
		while(it.hasNext())
		{
			Map.Entry<Integer, KillTrack> entry = it.next();
			KillTrack track = entry.getValue();
			Entity entity = MC.level.getEntity(entry.getKey());
			
			if(entity != null)
			{
				track.sawEntity = true;
				if(!entity.isAlive())
				{
					countKill(track.playerTarget);
					it.remove();
					continue;
				}
				
				if(entity instanceof LivingEntity living
					&& living.getHealth() <= 0)
				{
					countKill(track.playerTarget);
					it.remove();
					continue;
				}
			}else if(track.sawEntity
				&& now - track.attackedAtMs >= KILL_TRACK_MISSING_CONFIRM_MS)
			{
				countKill(track.playerTarget);
				it.remove();
				continue;
			}
			
			if(now >= track.expiresAtMs)
				it.remove();
		}
	}
	
	public double getFontScale()
	{
		return fontScale.getValue();
	}
	
	public boolean showFps()
	{
		return showFps.isChecked();
	}
	
	public boolean showTps()
	{
		return showTps.isChecked();
	}
	
	public boolean showPing()
	{
		return showPing.isChecked();
	}
	
	public boolean showPlayTime()
	{
		return showPlayTime.isChecked();
	}
	
	public boolean showCurrentTime()
	{
		return showCurrentTime.isChecked();
	}
	
	public boolean showWorldTime()
	{
		return showWorldTime.isChecked();
	}
	
	public boolean showPacketRate()
	{
		return showPacketRate.isChecked();
	}
	
	public boolean showDistanceTravelled()
	{
		return showDistanceTravelled.isChecked();
	}
	
	public boolean showMobKills()
	{
		return showMobKills.isChecked();
	}
	
	public boolean showPlayerKills()
	{
		return showPlayerKills.isChecked();
	}
	
	public boolean showXpGained()
	{
		return showXpGained.isChecked();
	}
	
	public int getFontOpacity()
	{
		return fontOpacity.getValueI();
	}
	
	public int getFontColorI()
	{
		return fontColor.getColorI();
	}
	
	public boolean hasFontStroke()
	{
		return fontStroke.isChecked();
	}
	
	public boolean hasBackgroundBox()
	{
		return backgroundBox.isChecked();
	}
	
	public int getBackgroundColorI()
	{
		return backgroundColor.getColorI();
	}
	
	public int getBackgroundOpacity()
	{
		return backgroundOpacity.getValueI();
	}
	
	public boolean showPrefixes()
	{
		return showPrefixes.isChecked();
	}
	
	public boolean showAverages()
	{
		return showAverages.isChecked();
	}
	
	public int getHudOffsetX()
	{
		return hudOffsetX.getValueI();
	}
	
	public int getHudOffsetY()
	{
		return hudOffsetY.getValueI();
	}
	
	public int getHudOffsetMinX()
	{
		return (int)hudOffsetX.getMinimum();
	}
	
	public int getHudOffsetMaxX()
	{
		return (int)hudOffsetX.getMaximum();
	}
	
	public int getHudOffsetMinY()
	{
		return (int)hudOffsetY.getMinimum();
	}
	
	public int getHudOffsetMaxY()
	{
		return (int)hudOffsetY.getMaximum();
	}
	
	public void setHudOffsets(int x, int y)
	{
		hudOffsetX.setValue(x);
		hudOffsetY.setValue(y);
	}
	
	public int getIncomingPacketRate()
	{
		return getPacketRate(incomingPacketTimes);
	}
	
	public int getOutgoingPacketRate()
	{
		return getPacketRate(outgoingPacketTimes);
	}
	
	public int getSessionMobKills()
	{
		return sessionMobKills;
	}
	
	public int getSessionPlayerKills()
	{
		return sessionPlayerKills;
	}
	
	private void recordPacket(ArrayDeque<Long> queue, long now)
	{
		synchronized(packetTimesLock)
		{
			queue.addLast(now);
			prunePacketTimes(queue, now);
		}
	}
	
	private int getPacketRate(ArrayDeque<Long> queue)
	{
		long now = System.currentTimeMillis();
		synchronized(packetTimesLock)
		{
			prunePacketTimes(queue, now);
			return queue.size();
		}
	}
	
	private static void prunePacketTimes(ArrayDeque<Long> queue, long now)
	{
		long cutoff = now - 1000L;
		while(!queue.isEmpty())
		{
			Long first = queue.peekFirst();
			if(first == null || first > cutoff)
				break;
			queue.removeFirst();
		}
	}
	
	private void countKill(boolean playerTarget)
	{
		if(playerTarget)
			sessionPlayerKills++;
		else
			sessionMobKills++;
	}
	
	private static final class KillTrack
	{
		private boolean playerTarget;
		private long attackedAtMs;
		private long expiresAtMs;
		private boolean sawEntity;
	}
}
