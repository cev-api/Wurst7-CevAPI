/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

public final class TitleBackgroundAnimation
{
	private long lastFrame = -1L;
	private double seconds;
	private boolean wasMoving;
	
	public float sample(long now, boolean moving)
	{
		if(lastFrame != -1L && moving && wasMoving)
			seconds += Math.max(0L, now - lastFrame) / 1_000_000_000.0;
		lastFrame = now;
		wasMoving = moving;
		return (float)seconds;
	}
}
