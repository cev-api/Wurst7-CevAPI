/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Map;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.AboveGroundFilterManager;
import net.wurstclient.hack.CheckboxOverrideManager;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;

@SearchTags({"global toggle", "render global toggle"})
public final class GlobalToggleHack extends Hack implements UpdateListener
{
	private final CheckboxSetting stickyForceOn =
		new CheckboxSetting("Sticky area on",
			"Forces sticky area on for all supported hacks.", false);
	private final CheckboxSetting stickyForceOff =
		new CheckboxSetting("Sticky area off",
			"Forces sticky area off for all supported hacks.", false);
	
	private final CheckboxSetting yLimitForceOn = new CheckboxSetting(
		"Y limit on",
		"Forces the above-ground filter on for all supported hacks.", false);
	private final CheckboxSetting yLimitForceOff = new CheckboxSetting(
		"Y limit off",
		"Forces the above-ground filter off for all supported hacks.", false);
	private final SliderSetting yLimitValue = new SliderSetting(
		"Global Y limit", 62, 0, 255, 1, ValueDisplay.INTEGER);
	
	private Map<CheckboxSetting, Boolean> stickySnapshot = Map.of();
	private Map<CheckboxSetting, Boolean> yLimitSnapshot = Map.of();
	
	private OverrideState lastStickyState = OverrideState.NONE;
	private OverrideState lastYState = OverrideState.NONE;
	private int lastYLimitValue = 62;
	
	public GlobalToggleHack()
	{
		super("GlobalToggle");
		setCategory(Category.OTHER);
		
		addSetting(stickyForceOn);
		addSetting(stickyForceOff);
		addSetting(yLimitForceOn);
		addSetting(yLimitForceOff);
		addSetting(yLimitValue);
		
		lastYLimitValue = yLimitValue.getValueI();
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onEnable()
	{
		setEnabled(false);
	}
	
	@Override
	public void onUpdate()
	{
		var hacks = WURST.getHax();
		
		// Sticky override --------------------------------------------------
		if(stickyForceOn.isChecked() && stickyForceOff.isChecked())
		{
			if(lastStickyState == OverrideState.FORCE_ON)
				stickyForceOff.setChecked(false);
			else
				stickyForceOn.setChecked(false);
			return;
		}
		
		OverrideState stickyState =
			getOverrideState(stickyForceOn, stickyForceOff);
		if(stickyState != lastStickyState)
		{
			if(lastStickyState != OverrideState.NONE)
				CheckboxOverrideManager.restore(stickySnapshot);
			
			if(stickyState == OverrideState.NONE)
			{
				stickySnapshot = Map.of();
				ChatUtils.message("Global sticky area override disabled.");
			}else
			{
				if(lastStickyState == OverrideState.NONE)
					stickySnapshot =
						CheckboxOverrideManager.capture(hacks, "stickyArea");
				CheckboxOverrideManager.apply(hacks, "stickyArea",
					stickyState == OverrideState.FORCE_ON);
				announceSticky(stickyState == OverrideState.FORCE_ON);
			}
			
			lastStickyState = stickyState;
		}
		
		// Y limit override -------------------------------------------------
		if(yLimitForceOn.isChecked() && yLimitForceOff.isChecked())
		{
			if(lastYState == OverrideState.FORCE_ON)
				yLimitForceOff.setChecked(false);
			else
				yLimitForceOn.setChecked(false);
			return;
		}
		
		OverrideState yState = getOverrideState(yLimitForceOn, yLimitForceOff);
		int yValue = yLimitValue.getValueI();
		
		if(yState != lastYState)
		{
			if(lastYState != OverrideState.NONE)
			{
				CheckboxOverrideManager.restore(yLimitSnapshot);
				if(lastYState == OverrideState.FORCE_ON)
					AboveGroundFilterManager.setY(hacks, lastYLimitValue);
			}else
				lastYLimitValue = yValue;
			
			if(yState == OverrideState.NONE)
			{
				yLimitSnapshot = Map.of();
				ChatUtils.message("Global Y limit override disabled.");
			}else
			{
				if(lastYState == OverrideState.NONE)
					yLimitSnapshot = CheckboxOverrideManager.capture(hacks,
						"onlyAboveGround");
				CheckboxOverrideManager.apply(hacks, "onlyAboveGround",
					yState == OverrideState.FORCE_ON);
				if(yState == OverrideState.FORCE_ON)
					AboveGroundFilterManager.setY(hacks, yValue);
				announceYLimit(yState == OverrideState.FORCE_ON);
			}
			
			lastYState = yState;
		}
		
		if(yState == OverrideState.FORCE_ON && yValue != lastYLimitValue)
		{
			AboveGroundFilterManager.setY(hacks, yValue);
			lastYLimitValue = yValue;
		}else if(yValue != lastYLimitValue)
		{
			lastYLimitValue = yValue;
		}
	}
	
	private void announceSticky(boolean forcingOn)
	{
		if(forcingOn)
			ChatUtils.message("Global sticky area override forcing ON.");
		else
			ChatUtils.message("Global sticky area override forcing OFF.");
	}
	
	private void announceYLimit(boolean forcingOn)
	{
		if(forcingOn)
			ChatUtils.message("Global Y limit override forcing ON.");
		else
			ChatUtils.message("Global Y limit override forcing OFF.");
	}
	
	private OverrideState getOverrideState(CheckboxSetting forceOn,
		CheckboxSetting forceOff)
	{
		if(forceOn.isChecked())
			return OverrideState.FORCE_ON;
		if(forceOff.isChecked())
			return OverrideState.FORCE_OFF;
		return OverrideState.NONE;
	}
	
	private enum OverrideState
	{
		NONE,
		FORCE_ON,
		FORCE_OFF;
	}
}
