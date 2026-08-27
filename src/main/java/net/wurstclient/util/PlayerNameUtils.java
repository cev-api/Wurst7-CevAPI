/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Locale;
import net.wurstclient.WurstClient;

public final class PlayerNameUtils
{
	private PlayerNameUtils()
	{}
	
	public static boolean isManuallyIgnored(String playerName)
	{
		return WurstClient.INSTANCE.getHax().globalToggleHack
			.shouldIgnoreManualPlayer(playerName);
	}
	
	public static boolean matchesAny(String configuredNames, String playerName)
	{
		if(configuredNames == null || playerName == null)
			return false;
		String target = playerName.trim();
		if(target.isEmpty())
			return false;
		String normalizedTarget = target.toLowerCase(Locale.ROOT);
		for(String configured : configuredNames.split("[,\\s]+"))
			if(!configured.isBlank() && configured.trim()
				.toLowerCase(Locale.ROOT).equals(normalizedTarget))
				return true;
		return false;
	}
}
