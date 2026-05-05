/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.wurstclient.Category;

import net.wurstclient.SearchTags;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"performance overlay", "benchmark overlay", "lag profiler"})
public final class PerformanceOverlayOtf extends OtherFeature
{
	private final CheckboxSetting enabled = new CheckboxSetting("Enabled",
		"Toggles the performance overlay HUD.", false);
	private final SliderSetting maxRows = new SliderSetting("Rows",
		"Maximum number of hacks shown in the overlay.", 8, 3, 20, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting fontScale = new SliderSetting("Font size", 1.0,
		0.5, 3.0, 0.05, ValueDisplay.DECIMAL);
	private final EnumSetting<SortMode> sortMode =
		new EnumSetting<>("Sort by", SortMode.values(), SortMode.TOTAL_TIME);
	private final CheckboxSetting showUpdate = new CheckboxSetting(
		"Show update time", "Include onUpdate() timing.", true);
	private final CheckboxSetting showRender = new CheckboxSetting(
		"Show world render time", "Include world onRender() timing.", true);
	private final CheckboxSetting showGui = new CheckboxSetting(
		"Show GUI render time", "Include GUI onRenderGUI() timing.", true);
	private final CheckboxSetting showGraph = new CheckboxSetting("Show graph",
		"Show a PerfMon-style mini graph of recent total timing.", true);
	private final SliderSetting backgroundOpacity =
		new SliderSetting("Background opacity", 75, 0, 100, 1,
			ValueDisplay.INTEGER.withSuffix("%"));
	private final SliderSetting hudOffsetX = new SliderSetting("HUD X offset",
		0, -2000, 2000, 1, ValueDisplay.INTEGER);
	private final SliderSetting hudOffsetY = new SliderSetting("HUD Y offset",
		0, -2000, 2000, 1, ValueDisplay.INTEGER);
	
	public PerformanceOverlayOtf()
	{
		super("PerformanceOverlay",
			"description.wurst.other_feature.performance_overlay");
		enabled.setVisibleInGui(false);
		addSetting(enabled);
		addSetting(maxRows);
		addSetting(fontScale);
		addSetting(sortMode);
		addSetting(showUpdate);
		addSetting(showRender);
		addSetting(showGui);
		addSetting(showGraph);
		addSetting(backgroundOpacity);
		addSetting(hudOffsetX);
		addSetting(hudOffsetY);
	}
	
	@Override
	public Category getCategory()
	{
		return Category.TOOLS;
	}
	
	@Override
	public boolean isEnabled()
	{
		return enabled.isChecked();
	}
	
	@Override
	public String getPrimaryAction()
	{
		return enabled.isChecked() ? "Disable" : "Enable";
	}
	
	@Override
	public void doPrimaryAction()
	{
		enabled.setChecked(!enabled.isChecked());
	}
	
	public boolean isEnabledOverlay()
	{
		return enabled.isChecked();
	}
	
	public int getMaxRows()
	{
		return maxRows.getValueI();
	}
	
	public double getFontScale()
	{
		return fontScale.getValue();
	}
	
	public SortMode getSortMode()
	{
		return sortMode.getSelected();
	}
	
	public boolean shouldShowUpdate()
	{
		return showUpdate.isChecked();
	}
	
	public boolean shouldShowRender()
	{
		return showRender.isChecked();
	}
	
	public boolean shouldShowGui()
	{
		return showGui.isChecked();
	}
	
	public boolean shouldShowGraph()
	{
		return showGraph.isChecked();
	}
	
	public int getBackgroundAlpha()
	{
		int percent = backgroundOpacity.getValueI();
		return Math.max(0, Math.min(255, (int)Math.round(percent * 2.55)));
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
	
	public enum SortMode
	{
		TOTAL_TIME("Total time (1s)"),
		PEAK_TIME("Peak callback");
		
		private final String name;
		
		private SortMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
