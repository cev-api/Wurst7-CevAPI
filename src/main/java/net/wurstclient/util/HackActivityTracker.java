/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.wurstclient.event.Listener;
import net.wurstclient.hack.Hack;

public enum HackActivityTracker
{
	;
	
	private static final Map<Hack, Long> LAST_ACTIVE =
		new ConcurrentHashMap<>();
	
	public static void markActive(Listener listener)
	{
		if(!(listener instanceof Hack hack))
			return;
		
		if(!hack.isEnabled())
			return;
		
		LAST_ACTIVE.put(hack, System.currentTimeMillis());
	}
	
	public static Hack getMostRecentActive(Iterable<Hack> hacks, long windowMs)
	{
		long now = System.currentTimeMillis();
		Hack best = null;
		long bestTime = 0;
		
		for(Hack hack : hacks)
		{
			Long time = LAST_ACTIVE.get(hack);
			if(time == null || now - time > windowMs)
				continue;
			
			if(best == null || time > bestTime)
			{
				best = hack;
				bestTime = time;
			}
		}
		
		return best;
	}
}
