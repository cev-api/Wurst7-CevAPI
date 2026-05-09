/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

public final class NecoModeHack extends Hack
{
	private final CheckboxSetting excludePlayers =
		new CheckboxSetting("Exclude players",
			"description.wurst.setting.necomode.exclude_players", false);
	private final CheckboxSetting onlyPassiveMobs =
		new CheckboxSetting("Only passive mobs",
			"description.wurst.setting.necomode.only_passive_mobs", false);
	private final CheckboxSetting onlyAggressiveMobs =
		new CheckboxSetting("Only aggressive mobs",
			"description.wurst.setting.necomode.only_aggressive_mobs", false);
	
	public NecoModeHack()
	{
		super("NecoMode");
		setCategory(Category.FUN);
		addSetting(excludePlayers);
		addSetting(onlyPassiveMobs);
		addSetting(onlyAggressiveMobs);
	}
	
	public boolean shouldExcludePlayers()
	{
		return excludePlayers.isChecked();
	}
	
	public boolean shouldRenderOnlyPassiveMobs()
	{
		return onlyPassiveMobs.isChecked();
	}
	
	public boolean shouldRenderOnlyAggressiveMobs()
	{
		return onlyAggressiveMobs.isChecked();
	}
}
