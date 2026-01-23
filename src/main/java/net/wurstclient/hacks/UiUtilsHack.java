/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;

import com.mojang.blaze3d.platform.InputConstants;
import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.uiutils.UiUtilsState;

public final class UiUtilsHack extends Hack
{
	private final CheckboxSetting slotOverlayEnabled = new CheckboxSetting(
		"Slot overlay", "Shows slot numbers over container slots.", true);
	
	private final ColorSetting slotOverlayColor =
		new ColorSetting("Slot overlay color", new Color(0xEFEFEF));
	
	private final SliderSetting slotOverlayAlpha = new SliderSetting(
		"Slot overlay alpha", 255, 0, 255, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting slotOverlayOffsetX = new SliderSetting(
		"Slot overlay offset X", 2, -20, 20, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting slotOverlayOffsetY = new SliderSetting(
		"Slot overlay offset Y", 2, -20, 20, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting slotOverlayHoverOnly =
		new CheckboxSetting("Slot overlay on hover",
			"Only show the slot index when your mouse is over a slot.", false);
	
	private final TextFieldSetting restoreScreenKey =
		new TextFieldSetting("Load GUI key",
			"Determines the key used to restore the saved GUI.\n\n"
				+ "Use translation keys such as \u00a7lkey.keyboard.v\u00a7r.\n"
				+ "You can find these by looking at the F3 debug screen or by\n"
				+ "checking vanilla keybind configuration files.",
			"key.keyboard.v", this::isValidKey);
	
	private final CheckboxSetting logToChat = new CheckboxSetting("Log to chat",
		"Echo UI-Utils actions and diagnostics to chat.", false);
	
	private final SliderSetting fabricateOverlayBgAlpha =
		new SliderSetting("Fabricate overlay background alpha", 120, 0, 255, 1,
			ValueDisplay.INTEGER);
	
	public UiUtilsHack()
	{
		super("UI-Utils");
		setCategory(Category.OTHER);
		
		addSetting(slotOverlayEnabled);
		addSetting(slotOverlayColor);
		addSetting(slotOverlayAlpha);
		addSetting(slotOverlayOffsetX);
		addSetting(slotOverlayOffsetY);
		addSetting(slotOverlayHoverOnly);
		addSetting(restoreScreenKey);
		addSetting(logToChat);
		addSetting(fabricateOverlayBgAlpha);
	}
	
	@Override
	protected void onEnable()
	{
		UiUtilsState.enabled = true;
	}
	
	public boolean isLogToChat()
	{
		return logToChat.isChecked();
	}
	
	@Override
	protected void onDisable()
	{
		UiUtilsState.enabled = false;
	}
	
	public boolean isSlotOverlayEnabled()
	{
		return slotOverlayEnabled.isChecked();
	}
	
	public int getSlotOverlayColorI()
	{
		int alpha = (int)Math.round(slotOverlayAlpha.getValue());
		return slotOverlayColor.getColorI(alpha);
	}
	
	public int getSlotOverlayOffsetX()
	{
		return (int)Math.round(slotOverlayOffsetX.getValue());
	}
	
	public int getSlotOverlayOffsetY()
	{
		return (int)Math.round(slotOverlayOffsetY.getValue());
	}
	
	public boolean isSlotOverlayHoverOnly()
	{
		return slotOverlayHoverOnly.isChecked();
	}
	
	public InputConstants.Key getRestoreKey()
	{
		try
		{
			return InputConstants.getKey(restoreScreenKey.getValue());
			
		}catch(IllegalArgumentException e)
		{
			return null;
		}
	}
	
	private boolean isValidKey(String translationKey)
	{
		try
		{
			return InputConstants.getKey(translationKey) != null;
			
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
	
	public int getFabricateOverlayBgAlpha()
	{
		return (int)Math.round(fabricateOverlayBgAlpha.getValue());
	}
}
