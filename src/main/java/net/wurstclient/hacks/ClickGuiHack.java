/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;

import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@DontSaveState
@DontBlock
@SearchTags({"click gui", "WindowGUI", "window gui", "HackMenu", "hack menu"})
public final class ClickGuiHack extends Hack
{
	private final ColorSetting bgColor =
		new ColorSetting("Background", "Background color", new Color(0x404040));
	
	private final ColorSetting acColor =
		new ColorSetting("Accent", "Accent color", new Color(0x101010));
	
	private final ColorSetting txtColor =
		new ColorSetting("Text", "Text color", new Color(0xF0F0F0));
	
	private final ColorSetting enabledHackColor =
		new ColorSetting("Enabled hacks", "Background color of enabled hacks",
			new Color(0x00D900));
	
	private final ColorSetting dropdownButtonColor =
		new ColorSetting("Dropdown button", "Color of dropdown/minimize arrows",
			new Color(0x00D900));
	
	private final ColorSetting pinButtonColor = new ColorSetting("Pin button",
		"Color of the pin button when unpinned", new Color(0x00D900));
	
	private final SliderSetting opacity = new SliderSetting("Opacity", 0.5,
		0.15, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting ttOpacity = new SliderSetting("Tooltip opacity",
		0.75, 0.15, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private final CheckboxSetting isolateWindows =
		new CheckboxSetting("Isolate windows",
			"Hide overlapping windows behind the front-most window.", false);
	
	private final SliderSetting maxHeight = new SliderSetting("Max height",
		"Maximum window height\n" + "0 = no limit", 200, 0, 1000, 50,
		ValueDisplay.INTEGER);
	
	private final SliderSetting maxSettingsHeight =
		new SliderSetting("Max settings height",
			"Maximum height for settings windows\n" + "0 = no limit", 200, 0,
			1000, 50, ValueDisplay.INTEGER);
	
	public ClickGuiHack()
	{
		super("ClickGUI");
		addSetting(bgColor);
		addSetting(acColor);
		addSetting(txtColor);
		addSetting(enabledHackColor);
		addSetting(dropdownButtonColor);
		addSetting(pinButtonColor);
		addSetting(opacity);
		addSetting(ttOpacity);
		addSetting(isolateWindows);
		addSetting(maxHeight);
		addSetting(maxSettingsHeight);
	}
	
	@Override
	protected void onEnable()
	{
		MC.setScreen(new ClickGuiScreen(WURST.getGui()));
		setEnabled(false);
	}
	
	public float[] getBackgroundColor()
	{
		return bgColor.getColorF();
	}
	
	public float[] getAccentColor()
	{
		return acColor.getColorF();
	}
	
	public int getTextColor()
	{
		return txtColor.getColorI();
	}
	
	public float[] getEnabledHackColor()
	{
		return enabledHackColor.getColorF();
	}
	
	public float[] getDropdownButtonColor()
	{
		return dropdownButtonColor.getColorF();
	}
	
	public float[] getPinButtonColor()
	{
		return pinButtonColor.getColorF();
	}
	
	public float getOpacity()
	{
		return opacity.getValueF();
	}
	
	public float getTooltipOpacity()
	{
		return ttOpacity.getValueF();
	}
	
	public boolean isWindowIsolationEnabled()
	{
		return isolateWindows.isChecked();
	}
	
	public CheckboxSetting getIsolateWindowsSetting()
	{
		return isolateWindows;
	}
	
	public int getMaxHeight()
	{
		return maxHeight.getValueI();
	}
	
	public int getMaxSettingsHeight()
	{
		return maxSettingsHeight.getValueI();
	}
}
