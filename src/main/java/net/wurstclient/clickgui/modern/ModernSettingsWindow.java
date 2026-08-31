/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.modern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.util.Mth;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.Window;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;

/**
 * Modern settings shell. It deliberately owns no setting values: every child
 * continues to operate on the Feature's existing Setting object.
 */
public final class ModernSettingsWindow extends ModernWindow
{
	private final Feature feature;
	private final List<SettingGroup> sections = new ArrayList<>();
	private String selectedSection = "General";
	private boolean rebuilding;
	
	public ModernSettingsWindow(Feature feature, Window parent, int buttonY)
	{
		super(feature.getDisplayName() + " Settings");
		this.feature = Objects.requireNonNull(feature);
		feature.getSettings().values().stream()
			.filter(SettingGroup.class::isInstance)
			.map(SettingGroup.class::cast)
			.filter(group -> !group.getChildren().isEmpty())
			.forEach(sections::add);
		setClosable(true);
		setMaxHeight(220);
		setPinned(parent.isPinned());
		rebuild();
		setInitialPosition(parent, buttonY);
	}
	
	public Feature getFeature()
	{
		return feature;
	}
	
	public List<SettingGroup> getSections()
	{
		return List.copyOf(sections);
	}
	
	public String getSelectedSection()
	{
		return selectedSection;
	}
	
	public void selectSection(String section)
	{
		if(section == null || selectedSection.equals(section))
			return;
		selectedSection = section;
		setScrollOffset(0);
		rebuild();
	}
	
	public void rebuild()
	{
		if(rebuilding)
			return;
		rebuilding = true;
		try
		{
			clearChildren();
			if(!sections.isEmpty())
				add(new ModernSectionTabs(this));
			SettingGroup selectedGroup = sections.stream()
				.filter(group -> group.getName().equals(selectedSection))
				.findFirst().orElse(null);
			Iterable<Setting> settings = selectedGroup != null
				? selectedGroup.getChildren() : feature.getSettings().values();
			for(Setting setting : settings)
			{
				setting.update();
				if(setting instanceof SettingGroup
					|| !setting.isVisibleInGui() && selectedGroup == null)
					continue;
				Component component = ModernSettingComponent.supports(setting)
					? new ModernSettingComponent(setting)
					: setting.getComponent();
				if(component != null)
				{
					int height = WurstClient.INSTANCE.getGui()
						.getModernComponentHeight(component);
					component
						.setHeight(Math.max(height, component.getHeight()));
					add(component);
				}
			}
			pack();
		}finally
		{
			rebuilding = false;
		}
	}
	
	private void setInitialPosition(Window parent, int buttonY)
	{
		int headerHeight = parent instanceof ModernWindow
			? (WurstClient.INSTANCE.getGuiIfInitialized() == null ? 24
				: WurstClient.INSTANCE.getGuiIfInitialized()
					.getModernHeaderHeight())
			: 13;
		int desiredX = parent.getX() + parent.getWidth() + 2;
		int desiredY =
			parent.getY() + headerHeight + parent.getScrollOffset() + buttonY;
		int screenWidth = WurstClient.MC.getWindow().getGuiScaledWidth();
		int screenHeight = WurstClient.MC.getWindow().getGuiScaledHeight();
		int maxX = Math.max(0, screenWidth - getWidth());
		int maxY = Math.max(20, screenHeight - 24);
		setX(Mth.clamp(desiredX, 0, maxX));
		setY(Mth.clamp(desiredY, 20, maxY));
	}
}
