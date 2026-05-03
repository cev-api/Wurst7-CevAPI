/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings.filters;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.wurstclient.util.NpcUtils;

public final class FilterNpcsSetting extends EntityFilterCheckbox
{
	public FilterNpcsSetting(String description, boolean checked)
	{
		super("Filter NPCs", description, checked);
	}
	
	@Override
	public boolean test(Entity e)
	{
		if(!(e instanceof Player player))
			return true;
		
		return !NpcUtils.isLikelyNpcPlayer(player);
	}
	
	public static FilterNpcsSetting genericCombat(boolean checked)
	{
		return new FilterNpcsSetting(
			"Won't target likely server-side NPCs (tab-list mismatch, no tab entry, or NPC-style names).",
			checked);
	}
}
