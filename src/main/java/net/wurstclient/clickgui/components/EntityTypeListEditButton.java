/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import java.util.Objects;

import net.wurstclient.settings.EntityTypeListSetting;
import net.wurstclient.settings.Setting;

public final class EntityTypeListEditButton extends AbstractListEditButton
{
	private final EntityTypeListSetting setting;
	
	public EntityTypeListEditButton(EntityTypeListSetting setting)
	{
		this.setting = Objects.requireNonNull(setting);
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	protected void openScreen()
	{
		MC.setScreen(
			new net.wurstclient.clickgui.screens.EditEntityTypeListScreen(
				MC.currentScreen, setting));
	}
	
	@Override
	protected String getText()
	{
		return setting.getName() + ": " + setting.getTypeNames().size();
	}
	
	@Override
	protected Setting getSetting()
	{
		return setting;
	}
}
