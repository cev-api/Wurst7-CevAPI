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
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"hide render", "no render", "no esp", "hide esp", "no hud",
	"hide hud"})
public final class HideWurstHack extends Hack
{
	private final CheckboxSetting hideUiMixins =
		new CheckboxSetting("Hide UI mixins",
			"Hide Wurst UI injections on menus and container screens.", false);
	private final CheckboxSetting hideFromModMenu =
		new CheckboxSetting("Hide from ModMenu",
			"Remove Wurst from ModMenu's mod list while this hack is enabled.",
			false);
	private final CheckboxSetting hideToggleChat = new CheckboxSetting(
		"Hide toggle chat",
		"Suppress enabled/disabled chat messages while this hack is enabled.",
		false);
	
	public HideWurstHack()
	{
		super("HideWurst");
		setCategory(Category.RENDER);
		addSetting(hideUiMixins);
		addSetting(hideFromModMenu);
		addSetting(hideToggleChat);
	}
	
	// Rendering is blocked in EventManager when this hack is enabled.
	
	public boolean shouldHideUiMixins()
	{
		return isEnabled() && hideUiMixins.isChecked();
	}
	
	public boolean shouldHideFromModMenu()
	{
		return isEnabled() && hideFromModMenu.isChecked();
	}
	
	public boolean shouldHideToggleChatFeedback()
	{
		return isEnabled() && hideToggleChat.isChecked();
	}
}
