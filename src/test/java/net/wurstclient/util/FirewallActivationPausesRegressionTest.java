/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.List;

/**
 * CPU regression checks for immediate pause, blocked starts, and restoration.
 */
public final class FirewallActivationPausesRegressionTest
{
	private static final class Feature
	{
		boolean enabled;
		int starts;
		
		Feature(boolean enabled)
		{
			this.enabled = enabled;
		}
	}
	
	public static void main(String[] args)
	{
		Feature untouchable = new Feature(true);
		Feature fastBreak = new Feature(true);
		Feature speedNuker = new Feature(false);
		FirewallActivationPauses<Feature> pauses =
			new FirewallActivationPauses<>(feature -> feature.enabled,
				feature -> feature.enabled = false, feature -> {
					feature.enabled = true;
					feature.starts++;
				});
		var blocked = List.of(untouchable, fastBreak, speedNuker);
		pauses.pause(blocked);
		check(!untouchable.enabled && !fastBreak.enabled && !speedNuker.enabled,
			"activation must pause every running blocked feature immediately");
		check(
			pauses.contains(untouchable) && pauses.contains(fastBreak)
				&& !pauses.contains(speedNuker),
			"features already off must not be restored on disable");
		pauses.pause(blocked);
		pauses.restore();
		check(untouchable.enabled && fastBreak.enabled && !speedNuker.enabled,
			"disable must restore exactly the previously running features");
		check(
			untouchable.starts == 1 && fastBreak.starts == 1
				&& speedNuker.starts == 0,
			"repeated updates must not produce duplicate restores");
		pauses.pause(blocked);
		check(pauses.requestEnable(speedNuker),
			"a blocked start must remember the requested state");
		check(
			!pauses.requestEnable(speedNuker) && !speedNuker.enabled
				&& speedNuker.starts == 0,
			"repeated blocked starts must never execute enable callbacks");
		pauses.restore();
		check(speedNuker.enabled && speedNuker.starts == 1 && pauses.isEmpty(),
			"requested states must be restored once when the firewall stops");
		pauses.restore();
		check(speedNuker.starts == 1, "restoration must be idempotent");
		System.out
			.println("Firewall activation pause regression checks passed.");
	}
	
	private static void check(boolean value, String message)
	{
		if(!value)
			throw new AssertionError(message);
	}
}
