/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.CheckboxSetting;

public final class ChestSearchHack extends Hack
{
	private static final int DISPLAY_RADIUS_UNLIMITED = 2001;
	
	private final EnumSetting<Mode> modeSetting = new EnumSetting<>("Mode",
		"Automatic scans instantly, Manual requires pressing the Scan button,"
			+ " and Off disables ChestSearch entirely.",
		Mode.values(), Mode.AUTOMATIC);
	private final SliderSetting waypointTimeSec = new SliderSetting(
		"Waypoint time (s)", 60, 5, 600, 5, ValueDisplay.INTEGER);
	private final SliderSetting espTimeSec =
		new SliderSetting("ESP time (s)", 60, 5, 600, 5, ValueDisplay.INTEGER);
	// controls for cleaner behaviour (exposed in navigator)
	private final SliderSetting gracePeriodSec = new SliderSetting(
		"Cleaner grace (s)", 10, 0, 60, 1, ValueDisplay.INTEGER);
	private final SliderSetting scanRadius = new SliderSetting(
		"Cleaner scan radius", 64, 8, 512, 8, ValueDisplay.INTEGER);
	private final SliderSetting maxResults = new SliderSetting(
		"Max search results", 50, 10, 1000, 10, ValueDisplay.INTEGER);
	private final SliderSetting displayRadius = new SliderSetting(
		"Display radius", DISPLAY_RADIUS_UNLIMITED, 1, DISPLAY_RADIUS_UNLIMITED,
		1, ValueDisplay.INTEGER.withSuffix(" blocks")
			.withLabel(DISPLAY_RADIUS_UNLIMITED, "Unlimited"));
	private final ColorSetting waypointColor =
		new ColorSetting("Waypoint color", new java.awt.Color(0xFFFF00));
	private final ColorSetting espFillColor =
		new ColorSetting("ESP fill", new java.awt.Color(0x22FF88));
	private final ColorSetting espLineColor =
		new ColorSetting("ESP line", new java.awt.Color(0x22FF88));
	private final ColorSetting markXColor =
		new ColorSetting("Mark X color", new java.awt.Color(0xFF2222));
	private final SliderSetting markXThickness = new SliderSetting(
		"Mark X thickness", 2.0, 0.5, 6.0, 0.5, ValueDisplay.DECIMAL);
	private final CheckboxSetting markOpenedChest = new CheckboxSetting(
		"Mark opened chest",
		"Draw an X through chests that appear in your ChestSearch database.",
		true);
	private final SliderSetting textScale = new SliderSetting("Text scale", 1.0,
		0.5, 1.25, 0.05, ValueDisplay.DECIMAL);
	
	public ChestSearchHack()
	{
		super("ChestSearch");
		setCategory(Category.ITEMS);
		// automatic/manual toggle should appear above the timeouts in the
		// clickui so add it first
		addSetting(modeSetting);
		addSetting(waypointTimeSec);
		addSetting(espTimeSec);
		// expose cleaner settings in navigator so user can tune them
		addSetting(gracePeriodSec);
		addSetting(scanRadius);
		addSetting(maxResults);
		addSetting(displayRadius);
		addSetting(textScale);
		addSetting(waypointColor);
		addSetting(espFillColor);
		addSetting(espLineColor);
		addSetting(markXColor);
		addSetting(markXThickness);
		addSetting(markOpenedChest);
	}
	
	public int getMarkXColorARGB()
	{
		return (0xFF << 24) | (markXColor.getColor().getRGB() & 0x00FFFFFF);
	}
	
	public double getMarkXThickness()
	{
		try
		{
			return markXThickness.getValue();
		}catch(Throwable t)
		{
			return 2.0;
		}
	}
	
	public boolean isMarkOpenedChest()
	{
		return markOpenedChest.isChecked();
	}
	
	public int getCleanerGraceTicks()
	{
		return (int)(gracePeriodSec.getValueI() * 20);
	}
	
	public int getCleanerScanRadius()
	{
		return scanRadius.getValueI();
	}
	
	public int getMaxSearchResults()
	{
		return maxResults.getValueI();
	}
	
	public boolean isDisplayRadiusUnlimited()
	{
		return displayRadius.getValueI() >= DISPLAY_RADIUS_UNLIMITED;
	}
	
	public int getDisplayRadius()
	{
		return isDisplayRadiusUnlimited() ? Integer.MAX_VALUE
			: displayRadius.getValueI();
	}
	
	public int getWaypointTimeMs()
	{
		return (int)(waypointTimeSec.getValue() * 1000);
	}
	
	public boolean isAutomaticMode()
	{
		return getMode() == Mode.AUTOMATIC;
	}
	
	public boolean isManualMode()
	{
		return getMode() == Mode.MANUAL;
	}
	
	public boolean isOffMode()
	{
		return getMode() == Mode.OFF;
	}
	
	public Mode getMode()
	{
		try
		{
			Mode mode = modeSetting.getSelected();
			return mode != null ? mode : Mode.AUTOMATIC;
		}catch(Throwable t)
		{
			return Mode.AUTOMATIC;
		}
	}
	
	public int getEspTimeMs()
	{
		return (int)(espTimeSec.getValue() * 1000);
	}
	
	public float getTextScaleF()
	{
		try
		{
			return (float)textScale.getValueF();
		}catch(Throwable t)
		{
			return 1.0f;
		}
	}
	
	public int getWaypointColorARGB()
	{
		return waypointColor.getColor().getRGB();
	}
	
	public int getEspFillARGB()
	{
		return (0x40 << 24) | (espFillColor.getColor().getRGB() & 0x00FFFFFF);
	}
	
	public int getEspLineARGB()
	{
		return (0x80 << 24) | (espLineColor.getColor().getRGB() & 0x00FFFFFF);
	}
	
	@Override
	protected void onEnable()
	{
		try
		{
			MC.setScreen(new net.wurstclient.clickgui.screens.ChestSearchScreen(
				MC.screen, Boolean.TRUE));
		}catch(Throwable ignored)
		{}
		setEnabled(false);
	}
	
	public enum Mode
	{
		AUTOMATIC("Automatic"),
		MANUAL("Manual"),
		OFF("Off");
		
		private final String displayName;
		
		private Mode(String displayName)
		{
			this.displayName = displayName;
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
}
