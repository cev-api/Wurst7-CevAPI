/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import java.util.Objects;

import net.wurstclient.clickgui.screens.EditPlayerMuteScreen;
import net.wurstclient.settings.PlayerMuteListSetting;
import net.wurstclient.settings.Setting;

public final class PlayerMuteListEditButton extends AbstractListEditButton
{
	private final PlayerMuteListSetting setting;
	
	public PlayerMuteListEditButton(PlayerMuteListSetting setting)
	{
		this.setting = Objects.requireNonNull(setting);
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	protected void openScreen()
	{
		MC.gui.setScreen(new EditPlayerMuteScreen(MC.gui.screen(), setting));
	}
	
	@Override
	protected String getText()
	{
		return setting.getName() + ": "
			+ setting.getHack().getMutedNames().size();
	}
	
	@Override
	protected Setting getSetting()
	{
		return setting;
	}
}
