/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.awt.Color;
import java.util.Comparator;

import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;

@SearchTags({"hack list", "HakList", "hak list", "HacksList", "hacks list",
	"HaxList", "hax list", "ArrayList", "array list", "ModList", "mod list",
	"CheatList", "cheat list"})
@DontBlock
public final class HackListOtf extends OtherFeature
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lAuto\u00a7r mode renders the whole list if it fits onto the screen.\n"
			+ "\u00a7lCount\u00a7r mode only renders the number of active hacks.\n"
			+ "\u00a7lHidden\u00a7r mode renders nothing.",
		Mode.values(), Mode.AUTO);
	
	private final EnumSetting<Position> position = new EnumSetting<>("Position",
		"Which side of the screen the HackList should be shown on."
			+ "\nChange this to \u00a7lRight\u00a7r when using TabGUI.",
		Position.values(), Position.TOP_LEFT);
	
	private final ColorSetting color = new ColorSetting("Color",
		"Color of the HackList text.\n"
			+ "Only visible when \u00a76RainbowUI\u00a7r is disabled.",
		Color.WHITE);
	
	// New: Use each hack's own color for its entry (if available)
	private final CheckboxSetting useHackColors = new CheckboxSetting(
		"Use hack colors",
		"When enabled, each entry uses the hack's own color (if available), e.g. ESP highlight colors.\n"
			+ "Has no effect while RainbowUI is enabled.",
		false);
	
	private final CheckboxSetting shadowBox = new CheckboxSetting("Shadow box",
		"Replace the text shadow with a transparent black box background. Useful when scaled fonts produce ugly shadows.",
		false);
	
	// Shadow box alpha (0.0 - 1.0)
	private final net.wurstclient.settings.SliderSetting shadowBoxAlpha =
		new net.wurstclient.settings.SliderSetting("Shadow box alpha", 0.5, 0.0,
			1.0, 0.01,
			net.wurstclient.settings.SliderSetting.ValueDisplay.DECIMAL);
	
	// Transparency (0.0 - 1.0)
	private final net.wurstclient.settings.SliderSetting transparency =
		new net.wurstclient.settings.SliderSetting("Transparency", 1.0, 0.0,
			1.0, 0.01,
			net.wurstclient.settings.SliderSetting.ValueDisplay.DECIMAL);
	
	// X and Y offset from screen edge in pixels (can be negative)
	private final net.wurstclient.settings.SliderSetting xOffset =
		new net.wurstclient.settings.SliderSetting("X offset", 0.0, -200.0,
			200.0, 1.0,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	
	private final net.wurstclient.settings.SliderSetting yOffset =
		new net.wurstclient.settings.SliderSetting("Y offset", 0.0, -200.0,
			200.0, 1.0,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	
	private final EnumSetting<SortBy> sortBy = new EnumSetting<>("Sort by",
		"Determines how the HackList entries are sorted.\n"
			+ "Only visible when \u00a76Mode\u00a7r is set to \u00a76Auto\u00a7r.",
		SortBy.values(), SortBy.NAME);
	
	private final CheckboxSetting revSort =
		new CheckboxSetting("Reverse sorting", false);
	
	private final CheckboxSetting animations = new CheckboxSetting("Animations",
		"When enabled, entries slide into and out of the HackList as hacks are enabled and disabled.",
		true);
	
	private SortBy prevSortBy;
	private Boolean prevRevSort;
	
	private final net.wurstclient.settings.SliderSetting fontSize =
		new net.wurstclient.settings.SliderSetting("Font size", 1.0, 0.5, 2.0,
			0.05, net.wurstclient.settings.SliderSetting.ValueDisplay.DECIMAL);
	
	// New: spacing between entries in screen pixels (can be negative)
	private final net.wurstclient.settings.SliderSetting entrySpacing =
		new net.wurstclient.settings.SliderSetting("Entry spacing", 0.0, 0,
			24.0, 1.0,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	
	public HackListOtf()
	{
		super("HackList", "Shows a list of active hacks on the screen.");
		
		addSetting(mode);
		addSetting(position);
		addSetting(color);
		addSetting(useHackColors);
		addSetting(shadowBox);
		addSetting(shadowBoxAlpha);
		addSetting(fontSize);
		addSetting(entrySpacing);
		addSetting(transparency);
		addSetting(xOffset);
		addSetting(yOffset);
		addSetting(sortBy);
		addSetting(revSort);
		addSetting(animations);
	}
	
	public Mode getMode()
	{
		return mode.getSelected();
	}
	
	public Position getPosition()
	{
		return position.getSelected();
	}
	
	public double getTransparency()
	{
		return transparency.getValue();
	}
	
	public double getFontSize()
	{
		return fontSize.getValue();
	}
	
	// New: get entry spacing in screen pixels
	public int getEntrySpacing()
	{
		return entrySpacing.getValueI();
	}
	
	public int getXOffset()
	{
		return xOffset.getValueI();
	}
	
	public int getYOffset()
	{
		return yOffset.getValueI();
	}
	
	public boolean isAnimations()
	{
		return animations.isChecked();
	}
	
	public boolean useShadowBox()
	{
		return shadowBox.isChecked();
	}
	
	public Comparator<Hack> getComparator()
	{
		if(revSort.isChecked())
			return sortBy.getSelected().comparator.reversed();
		
		return sortBy.getSelected().comparator;
	}
	
	public boolean shouldSort()
	{
		try
		{
			// width of a renderName could change at any time
			// must sort the HackList every tick
			if(sortBy.getSelected() == SortBy.WIDTH)
				return true;
			
			if(sortBy.getSelected() != prevSortBy)
				return true;
			
			if(!Boolean.valueOf(revSort.isChecked()).equals(prevRevSort))
				return true;
			
			return false;
			
		}finally
		{
			prevSortBy = sortBy.getSelected();
			prevRevSort = revSort.isChecked();
		}
	}
	
	public int getColor(int alpha)
	{
		return color.getColorI(alpha);
	}
	
	public boolean useHackColors()
	{
		return useHackColors.isChecked();
	}
	
	public double getShadowBoxAlpha()
	{
		return shadowBoxAlpha.getValue();
	}
	
	public static enum Mode
	{
		AUTO("Auto"),
		
		COUNT("Count"),
		
		HIDDEN("Hidden");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public static enum Position
	{
		// Legacy values for backward compatibility
		LEFT("Left"),
		RIGHT("Right"),
		TOP_LEFT("Top left"),
		
		TOP_RIGHT("Top right"),
		
		BOTTOM_LEFT("Bottom left"),
		
		BOTTOM_RIGHT("Bottom right");
		
		private final String name;
		
		private Position(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public static enum SortBy
	{
		NAME("Name", (a, b) -> a.getName().compareToIgnoreCase(b.getName())),
		
		WIDTH("Width", Comparator
			.comparingInt(h -> WurstClient.MC.font.width(h.getRenderName())));
		
		private final String name;
		private final Comparator<Hack> comparator;
		
		private SortBy(String name, Comparator<Hack> comparator)
		{
			this.name = name;
			this.comparator = comparator;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
