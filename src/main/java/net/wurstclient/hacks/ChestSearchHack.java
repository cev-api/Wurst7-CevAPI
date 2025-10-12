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
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.ColorSetting;

public final class ChestSearchHack extends Hack
{
	private final net.wurstclient.settings.CheckboxSetting automaticMode =
		new net.wurstclient.settings.CheckboxSetting("Automatic mode",
			"Automatically scan chests on open.", true);
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
	private final ColorSetting waypointColor =
		new ColorSetting("Waypoint color", new java.awt.Color(0xFFFF00));
	private final ColorSetting espFillColor =
		new ColorSetting("ESP fill", new java.awt.Color(0x22FF88));
	private final ColorSetting espLineColor =
		new ColorSetting("ESP line", new java.awt.Color(0x22FF88));
	
	public ChestSearchHack()
	{
		super("ChestSearch");
		setCategory(Category.ITEMS);
		// automatic/manual toggle should appear above the timeouts in the
		// clickui so add it first
		addSetting(automaticMode);
		addSetting(waypointTimeSec);
		addSetting(espTimeSec);
		// expose cleaner settings in navigator so user can tune them
		addSetting(gracePeriodSec);
		addSetting(scanRadius);
		addSetting(maxResults);
		addSetting(waypointColor);
		addSetting(espFillColor);
		addSetting(espLineColor);
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
	
	public int getWaypointTimeMs()
	{
		return (int)(waypointTimeSec.getValue() * 1000);
	}
	
	public boolean isAutomaticMode()
	{
		return automaticMode.isChecked();
	}
	
	public int getEspTimeMs()
	{
		return (int)(espTimeSec.getValue() * 1000);
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
				MC.currentScreen, Boolean.TRUE));
		}catch(Throwable ignored)
		{}
		setEnabled(false);
	}
}
