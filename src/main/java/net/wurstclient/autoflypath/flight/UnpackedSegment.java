/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath.flight;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class UnpackedSegment
{
	private final Stream<BetterBlockPos> path;
	private final boolean finished;
	
	public UnpackedSegment(Stream<BetterBlockPos> path, boolean finished)
	{
		this.path = path;
		this.finished = finished;
	}
	
	public UnpackedSegment append(Stream<BetterBlockPos> other,
		boolean otherFinished)
	{
		return new UnpackedSegment(Stream.concat(this.path, other),
			otherFinished);
	}
	
	public UnpackedSegment prepend(Stream<BetterBlockPos> other)
	{
		return new UnpackedSegment(Stream.concat(other, this.path),
			this.finished);
	}
	
	public List<BetterBlockPos> collect()
	{
		List<BetterBlockPos> path = this.path.collect(Collectors.toList());
		HashMap<BetterBlockPos, Integer> positionFirstSeen =
			new HashMap<BetterBlockPos, Integer>();
		for(int i = 0; i < path.size(); ++i)
		{
			BetterBlockPos pos = path.get(i);
			if(positionFirstSeen.containsKey((Object)pos))
			{
				int j = (Integer)positionFirstSeen.get((Object)pos);
				while(i > j)
				{
					path.remove(i);
					--i;
				}
				continue;
			}
			positionFirstSeen.put(pos, i);
		}
		return path;
	}
	
	public boolean isFinished()
	{
		return this.finished;
	}
}
