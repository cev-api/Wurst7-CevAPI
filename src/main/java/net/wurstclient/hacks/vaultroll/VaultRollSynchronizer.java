/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.vaultroll;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class VaultRollSynchronizer
{
	public static final int INITIAL_SEARCH_HORIZON = 1_000;
	public static final int EXTENDED_SEARCH_HORIZON = 100_000;
	public static final int MAXIMUM_SEARCH_HORIZON = 1_000_000;
	private static final int MAX_STORED_CANDIDATES = 50;
	
	private VaultRollSynchronizer()
	{}
	
	/** Search for a consecutive observation window from opening zero. */
	public static Result synchronize(long worldSeed, VaultRollMode mode,
		List<VaultRollObservation> observations, int searchBound,
		BooleanSupplier cancelled)
	{
		Objects.requireNonNull(mode);
		if(observations == null || observations.isEmpty())
			return Result.noMatch(0);
		if(searchBound < 1)
			throw new IllegalArgumentException("Search bound must be positive");
		for(VaultRollObservation observation : observations)
			if(observation.mode() != mode)
				throw new IllegalArgumentException("Mixed Vault modes");
			
		ArrayDeque<VaultRollOpening> window =
			new ArrayDeque<>(observations.size());
		VaultRollRandom random = VaultRollRandom.forSequence(worldSeed, mode);
		ArrayList<Long> candidates = new ArrayList<>();
		boolean moreCandidates = false;
		for(int opening = 0; opening < searchBound; opening++)
		{
			if((opening & 0x3FF) == 0 && cancelled.getAsBoolean())
				return Result.cancelled(opening);
			window.addLast(VaultRollPredictor.nextOpening(random, mode));
			if(window.size() > observations.size())
				window.removeFirst();
			if(window.size() != observations.size()
				|| !matches(window, observations))
				continue;
			long first = (long)opening - observations.size() + 1;
			if(candidates.size() < MAX_STORED_CANDIDATES)
				candidates.add(first);
			else
				moreCandidates = true;
		}
		if(candidates.isEmpty())
			return Result.noMatch(searchBound);
		if(candidates.size() == 1 && !moreCandidates)
			return Result.unique(candidates.get(0), searchBound);
		return Result.ambiguous(candidates, moreCandidates, searchBound);
	}
	
	/**
	 * Search forward from the known next opening for the earliest opening that
	 * matches the newest observation. This is the multiplayer gap-recovery
	 * path: another player may have consumed openings in between.
	 */
	public static RecoveryResult recover(long worldSeed, VaultRollMode mode,
		long startOpening, VaultRollObservation observation, int horizon,
		BooleanSupplier cancelled)
	{
		if(startOpening < 0 || horizon < 0)
			throw new IllegalArgumentException("Invalid recovery range");
		Objects.requireNonNull(observation);
		if(observation.mode() != mode)
			throw new IllegalArgumentException("Mixed Vault modes");
		VaultRollRandom random = VaultRollRandom.forSequence(worldSeed, mode);
		VaultRollPredictor.advance(random, mode, startOpening);
		for(int offset = 0; offset <= horizon; offset++)
		{
			if((offset & 0x3FF) == 0 && cancelled.getAsBoolean())
				return RecoveryResult.cancelled(offset);
			VaultRollOpening opening =
				VaultRollPredictor.nextOpening(random, mode);
			if(observation.matches(opening))
				return RecoveryResult.found(startOpening + offset, offset,
					offset + 1);
		}
		return RecoveryResult.noMatch(horizon + 1);
	}
	
	private static boolean matches(ArrayDeque<VaultRollOpening> actual,
		List<VaultRollObservation> expected)
	{
		int index = 0;
		for(VaultRollOpening opening : actual)
			if(!expected.get(index++).matches(opening))
				return false;
		return true;
	}
	
	public enum Status
	{
		NO_MATCH,
		UNIQUE,
		AMBIGUOUS,
		CANCELLED
	}
	
	public record Result(Status status, long uniqueFirstOpening,
		List<Long> candidateOpenings, boolean moreCandidates,
		int searchedOpenings)
	{
		public Result
		{
			candidateOpenings = Collections
				.unmodifiableList(new ArrayList<>(candidateOpenings));
		}
		
		private static Result noMatch(int searched)
		{
			return new Result(Status.NO_MATCH, -1, List.of(), false, searched);
		}
		
		private static Result unique(long opening, int searched)
		{
			return new Result(Status.UNIQUE, opening, List.of(opening), false,
				searched);
		}
		
		private static Result ambiguous(List<Long> openings, boolean more,
			int searched)
		{
			return new Result(Status.AMBIGUOUS, -1, openings, more, searched);
		}
		
		private static Result cancelled(int searched)
		{
			return new Result(Status.CANCELLED, -1, List.of(), false, searched);
		}
	}
	
	public record RecoveryResult(Status status, long matchedOpening,
		long skippedOpenings, int searchedOpenings)
	{
		private static RecoveryResult found(long opening, long skipped,
			int searched)
		{
			return new RecoveryResult(Status.UNIQUE, opening, skipped,
				searched);
		}
		
		private static RecoveryResult noMatch(int searched)
		{
			return new RecoveryResult(Status.NO_MATCH, -1, -1, searched);
		}
		
		private static RecoveryResult cancelled(int searched)
		{
			return new RecoveryResult(Status.CANCELLED, -1, -1, searched);
		}
	}
}
