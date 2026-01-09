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
import net.wurstclient.uiutils.UiUtilsState;

public final class UiUtilsHack extends Hack
{
	public UiUtilsHack()
	{
		super("UI-Utils");
		setCategory(Category.OTHER);
	}
	
	@Override
	protected void onEnable()
	{
		UiUtilsState.enabled = true;
	}
	
	@Override
	protected void onDisable()
	{
		UiUtilsState.enabled = false;
	}
}
