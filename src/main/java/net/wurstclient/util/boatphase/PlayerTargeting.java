/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.boatphase;

import java.util.Comparator;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;

/** Shared named-player and priority selection. */
public final class PlayerTargeting
{
	private PlayerTargeting()
	{}
	
	public enum Priority
	{
		LOWEST_DISTANCE,
		CLOSEST_ANGLE,
		LOWEST_HEALTH
	}
	
	public static Player findPlayer(LocalPlayer self,
		List<? extends Player> players, String name, double range,
		Priority priority)
	{
		if(self == null || players == null)
			return null;
		String wanted = name == null ? "" : name.trim();
		if(!wanted.isEmpty())
			return players.stream()
				.filter(x -> x != self && x.isAlive() && !x.isSpectator()
					&& !WurstClient.INSTANCE.getFriends().isFriend(x)
					&& x.getGameProfile().name().equalsIgnoreCase(wanted)
					&& self.distanceToSqr(x) <= range * range)
				.findFirst().orElse(null);
		Comparator<Player> comparator = switch(priority)
		{
			case CLOSEST_ANGLE -> Comparator
				.comparingDouble(x -> angle(self, x));
			case LOWEST_HEALTH -> Comparator
				.comparingDouble(x -> x.getHealth() + x.getAbsorptionAmount());
			case LOWEST_DISTANCE -> Comparator
				.comparingDouble(self::distanceToSqr);
		};
		return players.stream()
			.filter(x -> x != self && x.isAlive() && !x.isSpectator()
				&& !WurstClient.INSTANCE.getFriends().isFriend(x)
				&& self.distanceToSqr(x) <= range * range)
			.min(comparator).orElse(null);
	}
	
	private static double angle(LocalPlayer self, Player target)
	{
		Vec3 to =
			target.getBoundingBox().getCenter().subtract(self.getEyePosition());
		if(to.lengthSqr() < 1e-9)
			return 0;
		return Math
			.acos(Math.clamp(self.getLookAngle().dot(to.normalize()), -1, 1));
	}
}
