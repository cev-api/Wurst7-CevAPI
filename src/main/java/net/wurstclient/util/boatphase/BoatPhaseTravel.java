/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.boatphase;

import java.util.EnumSet;

public final class BoatPhaseTravel
{
	public enum Mode
	{
		LIMITED,
		SUBSTEPS,
		EXTENDED,
		DIRECT,
		HASH
	}
	
	private final EnumSet<Mode> rejected = EnumSet.noneOf(Mode.class);
	private Mode lastFastMode = Mode.LIMITED;
	private int lastFastTick = -100;
	
	public Mode select(boolean hash, boolean referenceReady, boolean substeps,
		boolean direct)
	{
		return select(hash, referenceReady, substeps, direct, false);
	}
	
	public Mode select(boolean hash, boolean referenceReady, boolean substeps,
		boolean direct, boolean extended)
	{
		if(hash && !rejected(Mode.HASH))
			return referenceReady ? Mode.HASH : Mode.LIMITED;
		if(substeps)
			return rejected(Mode.SUBSTEPS) ? Mode.LIMITED
				: extended && !rejected(Mode.EXTENDED) ? Mode.EXTENDED
					: Mode.SUBSTEPS;
		return direct && !rejected(Mode.DIRECT) ? Mode.DIRECT : Mode.LIMITED;
	}
	
	public void sent(Mode mode, int tick, double horizontal, double limit)
	{
		if(mode != Mode.LIMITED && Double.isFinite(horizontal)
			&& horizontal > limit + 1e-6)
		{
			lastFastMode = mode;
			lastFastTick = tick;
		}
	}
	
	public Mode corrected(int tick)
	{
		int age = tick - lastFastTick;
		if(age < 0 || age > 20 || lastFastMode == Mode.LIMITED
			|| rejected(lastFastMode))
			return Mode.LIMITED;
		reject(lastFastMode);
		return lastFastMode;
	}
	
	public void reject(Mode mode)
	{
		if(mode != Mode.LIMITED)
			rejected.add(mode);
	}
	
	public void retry(Mode mode)
	{
		rejected.remove(mode);
		newRide();
	}
	
	public boolean rejected(Mode mode)
	{
		return rejected.contains(mode);
	}
	
	public boolean flightRejected()
	{
		return rejected(Mode.SUBSTEPS) || rejected(Mode.EXTENDED)
			|| rejected(Mode.DIRECT);
	}
	
	public void retryFlight()
	{
		rejected.remove(Mode.SUBSTEPS);
		rejected.remove(Mode.EXTENDED);
		rejected.remove(Mode.DIRECT);
		newRide();
	}
	
	public void newRide()
	{
		lastFastMode = Mode.LIMITED;
		lastFastTick = -100;
	}
	
	public void retry()
	{
		rejected.clear();
		newRide();
	}
}
