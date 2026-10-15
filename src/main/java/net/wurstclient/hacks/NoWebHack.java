/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.wurstclient.Category;
import net.wurstclient.hack.Hack;

public final class NoWebHack extends Hack
{
	public NoWebHack()
	{
		super("NoWeb",
			"Cancels cobweb slowdown in singleplayer. Multiplayer servers apply their own slowdown and reject faster client movement.",
			false);
		setCategory(Category.MOVEMENT);
	}
	
	public boolean shouldCancelCobwebSlowdown(Entity entity)
	{
		if(!isEnabled() || entity == null || MC.player == null)
			return false;
		
		return entity == MC.player || entity instanceof ServerPlayer player
			&& player.getUUID().equals(MC.player.getUUID());
	}
}
