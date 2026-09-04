/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
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
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@DontSaveState
@DontBlock
@SearchTags({"click gui", "WindowGUI", "window gui", "HackMenu", "hack menu"})
public final class ClickGuiHack extends Hack
{
	// Phase 1 Modern ClickGUI: presentation only; feature state stays shared.
	public enum Style
	{
		CLASSIC("Classic"),
		MODERN("Modern");
		
		private final String displayName;
		
		private Style(String displayName)
		{
			this.displayName = displayName;
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
	
	private final EnumSetting<Style> style = new EnumSetting<>("Style",
		"Selects the ClickGUI presentation without changing hack settings.",
		Style.values(), Style.MODERN);
	private final ColorSetting bgColor =
		new ColorSetting("Background", "Background color", new Color(0x404040));
	
	private final ColorSetting acColor =
		new ColorSetting("Accent", "Accent color", new Color(0x101010));
	
	private final ColorSetting txtColor =
		new ColorSetting("Text", "Text color", new Color(0xF0F0F0));
	
	private final ColorSetting topBarColor = new ColorSetting("Top bar color",
		"Color of the top navigation bar", new Color(0x181818));
	private final SliderSetting topBarOpacity = new SliderSetting(
		"Top bar opacity", "Opacity of the top navigation bar", 0.85, 0.15, 1,
		0.01, ValueDisplay.PERCENTAGE);
	private final ColorSetting hackHeaderColor =
		new ColorSetting("Hack header color", "Color of hack window headers",
			new Color(0x181818));
	private final SliderSetting hackHeaderOpacity = new SliderSetting(
		"Hack header opacity", "Opacity of hack window headers", 0.9, 0.15, 1,
		0.01, ValueDisplay.PERCENTAGE);
	
	private final ColorSetting enabledHackColor =
		new ColorSetting("Enabled hacks", "Background color of enabled hacks",
			new Color(0x0BABE3));
	
	private final ColorSetting dropdownButtonColor =
		new ColorSetting("Dropdown button", "Color of dropdown/minimize arrows",
			new Color(0x00A6D9));
	private final ColorSetting dropdownBackgroundColor =
		new ColorSetting("Dropdown background",
			"Color of dropdown option lists", new Color(0x303030));
	
	private final CheckboxSetting highlightEnabledRows =
		new CheckboxSetting("Highlight enabled rows",
			"Highlights the entire Modern ClickGUI row for enabled features.",
			false);
	private final ColorSetting hackRowBorderColor =
		new ColorSetting("Hack row border",
			"Border color between Modern hack rows", new Color(0xA0A0A0));
	
	private final SliderSetting hackRowBorderOpacity =
		new SliderSetting("Hack row border opacity",
			"Opacity of borders between Modern hack rows", 0.35, 0, 1, 0.01,
			ValueDisplay.PERCENTAGE);
	private final ColorSetting pinButtonColor = new ColorSetting("Pin button",
		"Color of the pin button when unpinned", new Color(0x00ABFF));
	
	private final SliderSetting opacity = new SliderSetting("Opacity", 1.0,
		0.15, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting ttOpacity = new SliderSetting("Tooltip opacity",
		1.0, 0.15, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting headerHeight =
		new SliderSetting("Header thickness", "Height of Modern window headers",
			24, 16, 40, 1, ValueDisplay.INTEGER);
	private final SliderSetting rowHeight = new SliderSetting(
		"Global row height",
		"Thickness of Modern buttons, hacks, dropdowns, colors, and settings.",
		20, 15, 25, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting isolateWindows =
		new CheckboxSetting("Isolate windows",
			"Hide overlapping windows behind the front-most window.", true);
	
	private final SliderSetting maxHeight = new SliderSetting("Max height",
		"Maximum window height\n" + "0 = no limit", 350, 0, 1000, 50,
		ValueDisplay.INTEGER);
	
	private final SliderSetting maxSettingsHeight =
		new SliderSetting("Max settings height",
			"Maximum height for settings windows\n" + "0 = no limit", 350, 0,
			1000, 50, ValueDisplay.INTEGER);
	
	public ClickGuiHack()
	{
		super("ClickGUI");
		addSetting(style);
		addSetting(bgColor);
		addSetting(acColor);
		addSetting(txtColor);
		addSetting(topBarColor);
		addSetting(topBarOpacity);
		addSetting(hackHeaderColor);
		addSetting(hackHeaderOpacity);
		addSetting(enabledHackColor);
		addSetting(dropdownButtonColor);
		addSetting(dropdownBackgroundColor);
		addSetting(highlightEnabledRows);
		
		addSetting(hackRowBorderColor);
		addSetting(hackRowBorderOpacity);
		addSetting(pinButtonColor);
		addSetting(headerHeight);
		addSetting(rowHeight);
		addSetting(opacity);
		addSetting(ttOpacity);
		addSetting(isolateWindows);
		addSetting(maxHeight);
		addSetting(maxSettingsHeight);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.gui == null)
		{
			setEnabled(false);
			return;
		}
		
		MC.gui.setScreen(new ClickGuiScreen(WURST.getGui()));
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
	
	public float[] getTopBarColor()
	{
		return topBarColor.getColorF();
	}
	
	public float getTopBarOpacity()
	{
		return topBarOpacity.getValueF();
	}
	
	public float[] getHackHeaderColor()
	{
		return hackHeaderColor.getColorF();
	}
	
	public float getHackHeaderOpacity()
	{
		return hackHeaderOpacity.getValueF();
	}
	
	public int getHeaderHeight()
	{
		return headerHeight.getValueI();
	}
	
	public float[] getEnabledHackColor()
	{
		return enabledHackColor.getColorF();
	}
	
	public float[] getDropdownButtonColor()
	{
		return dropdownButtonColor.getColorF();
	}
	
	public float[] getDropdownBackgroundColor()
	{
		return dropdownBackgroundColor.getColorF();
	}
	
	public boolean isHighlightEnabledRows()
	{
		return highlightEnabledRows.isChecked();
	}
	
	public float[] getHackRowBorderColor()
	{
		return hackRowBorderColor.getColorF();
	}
	
	public float getHackRowBorderOpacity()
	{
		return hackRowBorderOpacity.getValueF();
	}
	
	public float[] getPinButtonColor()
	{
		return pinButtonColor.getColorF();
	}
	
	public int getRowHeight()
	{
		return rowHeight.getValueI();
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
	
	public boolean isModernStyle()
	{
		return style.getSelected() == Style.MODERN;
	}
}
