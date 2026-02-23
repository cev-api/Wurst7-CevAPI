/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.wurstclient.Category;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;

public final class ForceAllowChatsOtf extends OtherFeature
{
	private final CheckboxSetting forceAllowChats =
		new CheckboxSetting("Force Allow Chats", false);
	
	public ForceAllowChatsOtf()
	{
		super("ForceAllowChats",
			"Forces Mojang user properties to allow chat, Realms, and servers.");
		addSetting(forceAllowChats);
	}
	
	public CheckboxSetting getForceAllowChatsSetting()
	{
		return forceAllowChats;
	}
	
	public boolean isForceAllowChatsEnabled()
	{
		return forceAllowChats.isChecked();
	}
	
	@Override
	public Category getCategory()
	{
		return Category.CHAT;
	}
}
