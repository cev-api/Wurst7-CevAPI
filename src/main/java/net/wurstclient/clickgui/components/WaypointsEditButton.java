/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import java.util.Objects;

import net.wurstclient.clickgui.screens.WaypointsScreen;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.WaypointsSetting;

public final class WaypointsEditButton extends AbstractListEditButton
{
	private final WaypointsSetting setting;
	
	public WaypointsEditButton(WaypointsSetting setting)
	{
		this.setting = Objects.requireNonNull(setting);
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	protected void openScreen()
	{
		MC.setScreen(new WaypointsScreen(MC.screen, setting.getManager()));
	}
	
	@Override
	protected String getText()
	{
		return setting.getName();
	}
	
	@Override
	protected Setting getSetting()
	{
		return setting;
	}
}
