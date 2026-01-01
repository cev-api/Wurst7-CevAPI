/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.timer;

import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;

public final class TimerManager implements UpdateListener
{
	private final RequestHandler<Float> requestHandler = new RequestHandler<>();
	
	public float getTimerSpeed()
	{
		Float speed = requestHandler.getActiveRequestValue();
		return speed != null ? speed : 1.0F;
	}
	
	public void requestTimerSpeed(float timerSpeed, TimerPriority priority,
		Hack provider, int resetAfterTicks)
	{
		requestHandler.request(new RequestHandler.Request<>(resetAfterTicks + 1,
			priority.getPriority(), provider, timerSpeed));
	}
	
	@Override
	public void onUpdate()
	{
		requestHandler.tick();
	}
}
