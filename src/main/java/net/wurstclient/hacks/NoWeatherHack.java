/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.world.level.MoonPhase;
import net.wurstclient.Category;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;

public final class NoWeatherHack extends Hack
	implements UpdateListener, PacketInputListener
{
	private final CheckboxSetting disableRain =
		new CheckboxSetting("Disable Rain", true);
	
	private final CheckboxSetting announceWeatherChanges =
		new CheckboxSetting("Announce Weather Changes", false);
	
	private final CheckboxSetting changeTime =
		new CheckboxSetting("Change World Time", false);
	
	private final SliderSetting time =
		new SliderSetting("Time", 6000, 0, 23900, 100, ValueDisplay.INTEGER);
	
	private final CheckboxSetting changeMoonPhase =
		new CheckboxSetting("Change Moon Phase", false);
	
	private final SliderSetting moonPhase =
		new SliderSetting("Moon Phase", 0, 0, 7, 1, ValueDisplay.INTEGER);
	
	private WeatherState lastKnownWeather;
	private Boolean packetRaining;
	private Boolean packetThundering;
	
	public NoWeatherHack()
	{
		super("NoWeather");
		setCategory(Category.RENDER);
		
		addSetting(disableRain);
		addSetting(announceWeatherChanges);
		addSetting(changeTime);
		addSetting(time);
		addSetting(changeMoonPhase);
		addSetting(moonPhase);
	}
	
	@Override
	protected void onEnable()
	{
		lastKnownWeather = null;
		packetRaining = null;
		packetThundering = null;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		lastKnownWeather = null;
		packetRaining = null;
		packetThundering = null;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null)
		{
			lastKnownWeather = null;
			packetRaining = null;
			packetThundering = null;
			return;
		}
		
		boolean raining =
			packetRaining != null ? packetRaining : getActualRaining();
		boolean thundering =
			packetThundering != null ? packetThundering : getActualThundering();
		WeatherState currentWeather =
			WeatherState.fromLevel(raining, thundering);
		if(lastKnownWeather == null)
		{
			lastKnownWeather = currentWeather;
			return;
		}
		
		if(currentWeather == lastKnownWeather)
			return;
		
		if(announceWeatherChanges.isChecked())
			ChatUtils.message("NoWeather: Weather is now "
				+ currentWeather.getDisplayName() + ".");
		
		lastKnownWeather = currentWeather;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		if(!(packet instanceof ClientboundGameEventPacket gameEvent))
			return;
		
		var type = gameEvent.getEvent();
		float value = gameEvent.getParam();
		
		if(type == ClientboundGameEventPacket.START_RAINING)
		{
			packetRaining = true;
			return;
		}
		
		if(type == ClientboundGameEventPacket.STOP_RAINING)
		{
			packetRaining = false;
			packetThundering = false;
			return;
		}
		
		if(type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE)
		{
			packetRaining = value > 0.2F;
			return;
		}
		
		if(type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE)
		{
			packetThundering = value > 0.2F;
			if(packetThundering)
				packetRaining = true;
		}
	}
	
	private boolean getActualRaining()
	{
		if(MC.level == null)
			return false;
			
		// Read server-synced weather flags directly so alerts still work when
		// NoWeather suppresses visual rain rendering.
		return MC.level.isRaining();
	}
	
	private boolean getActualThundering()
	{
		if(MC.level == null)
			return false;
		
		// Same as above: avoid relying on rendered rain/thunder state.
		return MC.level.isThundering();
	}
	
	public boolean isRainDisabled()
	{
		return isEnabled() && disableRain.isChecked();
	}
	
	public boolean isTimeChanged()
	{
		return isEnabled() && changeTime.isChecked();
	}
	
	public long getChangedTime()
	{
		return time.getValueI();
	}
	
	public boolean isMoonPhaseChanged()
	{
		return isEnabled() && changeMoonPhase.isChecked();
	}
	
	public MoonPhase getChangedMoonPhase()
	{
		return MoonPhase.values()[moonPhase.getValueI()];
	}
	
	private enum WeatherState
	{
		CLEAR("clear"),
		RAIN("rain"),
		THUNDER("thunder");
		
		private final String displayName;
		
		private WeatherState(String displayName)
		{
			this.displayName = displayName;
		}
		
		public String getDisplayName()
		{
			return displayName;
		}
		
		private static WeatherState fromLevel(boolean raining,
			boolean thundering)
		{
			if(thundering)
				return THUNDER;
			
			if(raining)
				return RAIN;
			
			return CLEAR;
		}
	}
}
