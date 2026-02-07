/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import java.awt.Color;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"durability", "armor", "hud"})
public final class DurabilityHudHack extends Hack
{
	public enum DisplayMode
	{
		BOTH("Both"),
		PERCENT_ONLY("Percent only"),
		BAR_ONLY("Bar only");
		
		private final String label;
		
		DisplayMode(String label)
		{
			this.label = label;
		}
		
		@Override
		public String toString()
		{
			return label;
		}
	}
	
	private final EnumSetting<DisplayMode> displayMode = new EnumSetting<>(
		"Display mode", DisplayMode.values(), DisplayMode.BOTH);
	private final CheckboxSetting bossBarStyle =
		new CheckboxSetting("Boss bar style", false);
	private final CheckboxSetting showOffhand =
		new CheckboxSetting("Show offhand", true);
	private final ColorSetting fontColor =
		new ColorSetting("Font color", new Color(0xFF, 0xFF, 0xFF, 0xFF));
	private final CheckboxSetting gradientFontColor =
		new CheckboxSetting("Durability gradient font",
			"Override the font color with a green→yellow→red gradient.", false);
	private final SliderSetting iconSize = new SliderSetting("Icon size", 24.0,
		16.0, 40.0, 1.0, ValueDisplay.INTEGER);
	private final SliderSetting fontScale = new SliderSetting("Font scale", 1.0,
		0.5, 2.0, 0.05, ValueDisplay.DECIMAL);
	
	public DurabilityHudHack()
	{
		super("DurabilityHUD");
		setCategory(Category.RENDER);
		addSetting(displayMode);
		addSetting(bossBarStyle);
		addSetting(showOffhand);
		addSetting(fontColor);
		addSetting(gradientFontColor);
		addSetting(iconSize);
		addSetting(fontScale);
	}
	
	public DisplayMode getDisplayMode()
	{
		return displayMode.getSelected();
	}
	
	public boolean isBossBarStyle()
	{
		return bossBarStyle.isChecked();
	}
	
	public boolean isShowOffhand()
	{
		return showOffhand.isChecked();
	}
	
	public int getFontColorI()
	{
		return fontColor.getColorI();
	}
	
	public boolean useGradientFontColor()
	{
		return gradientFontColor.isChecked();
	}
	
	public double getIconSize()
	{
		return iconSize.getValue();
	}
	
	public double getFontScale()
	{
		return fontScale.getValue();
	}
}
