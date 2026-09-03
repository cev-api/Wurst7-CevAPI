/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.villageroll;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class VillagerRollSynchronizer
{
	public static final int INITIAL_SEARCH_HORIZON = 1_000;
	public static final int EXTENDED_SEARCH_HORIZON = 100_000;
	public static final int MAXIMUM_SEARCH_HORIZON = 1_000_000;
	private static final int MAX_STORED_CANDIDATES = 50;
	
	private VillagerRollSynchronizer()
	{}
	
	public static Result synchronize(long worldSeed,
		List<VillagerRollNormalizedRoll> observations, int startOffset,
		int searchBound, BooleanSupplier cancelled)
	{
		if(observations == null || observations.isEmpty())
			return Result.noMatch(0);
		if(startOffset < 0 || searchBound < startOffset)
			throw new IllegalArgumentException("Invalid synchronization range");
		
		List<VillagerRollNormalizedRoll> expected = List.copyOf(observations);
		ArrayDeque<VillagerRollNormalizedRoll> window =
			new ArrayDeque<>(expected.size());
		VillagerRollPredictor.PredictorRandom random =
			VillagerRollPredictor.createRandom(worldSeed);
		ArrayList<Long> candidates = new ArrayList<>();
		boolean moreCandidates = false;
		
		for(int roll = 0; roll < searchBound; roll++)
		{
			if((roll & 0x3FF) == 0 && cancelled.getAsBoolean())
				return Result.cancelled(roll);
			
			VillagerRollNormalizedRoll generated =
				VillagerRollPredictor.nextLibrarianRoll(random).normalize();
			window.addLast(generated);
			if(window.size() > expected.size())
				window.removeFirst();
			
			if(roll < startOffset || window.size() != expected.size()
				|| !matches(window, expected))
				continue;
			
			long firstMatchedRoll = (long)roll - expected.size() + 1;
			if(candidates.size() < MAX_STORED_CANDIDATES)
				candidates.add(firstMatchedRoll);
			else
				moreCandidates = true;
		}
		
		if(candidates.isEmpty())
			return Result.noMatch(searchBound);
		if(candidates.size() == 1 && !moreCandidates)
			return Result.unique(candidates.get(0), searchBound);
		return Result.ambiguous(candidates, moreCandidates, searchBound);
	}
	
	private static boolean matches(
		ArrayDeque<VillagerRollNormalizedRoll> actual,
		List<VillagerRollNormalizedRoll> expected)
	{
		int index = 0;
		for(VillagerRollNormalizedRoll roll : actual)
		{
			if(!roll.equals(expected.get(index++)))
				return false;
		}
		return true;
	}
	
	public enum Status
	{
		NO_MATCH,
		UNIQUE,
		AMBIGUOUS,
		CANCELLED
	}
	
	public record Result(Status status, long uniqueFirstRoll,
		List<Long> candidateRolls, boolean moreCandidates, int searchedRolls)
	{
		public Result
		{
			candidateRolls =
				Collections.unmodifiableList(new ArrayList<>(candidateRolls));
		}
		
		private static Result noMatch(int searchedRolls)
		{
			return new Result(Status.NO_MATCH, -1, List.of(), false,
				searchedRolls);
		}
		
		private static Result unique(long roll, int searchedRolls)
		{
			return new Result(Status.UNIQUE, roll, List.of(roll), false,
				searchedRolls);
		}
		
		private static Result ambiguous(List<Long> rolls, boolean more,
			int searchedRolls)
		{
			return new Result(Status.AMBIGUOUS, -1, rolls, more, searchedRolls);
		}
		
		private static Result cancelled(int searchedRolls)
		{
			return new Result(Status.CANCELLED, -1, List.of(), false,
				searchedRolls);
		}
	}
}
