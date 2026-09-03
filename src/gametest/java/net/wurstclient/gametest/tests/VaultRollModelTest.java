/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.wurstclient.hacks.vaultroll.VaultRollMode;
import net.wurstclient.hacks.vaultroll.VaultRollObservation;
import net.wurstclient.hacks.vaultroll.VaultRollPredictor;
import net.wurstclient.hacks.vaultroll.VaultRollRandom;
import net.wurstclient.hacks.vaultroll.VaultRollStack;
import net.wurstclient.hacks.vaultroll.VaultRollSynchronizer;

/** Deterministic vectors and state-model checks for VaultRoll. */
public final class VaultRollModelTest
{
	@SuppressWarnings("unused")
	private final ClientGameTestContext context;
	
	public VaultRollModelTest(ClientGameTestContext context)
	{
		this.context = context;
	}
	
	public void run()
	{
		testRandomVectors();
		testOpeningVectors();
		testCapturedOminousSequence();
		testIndependentSequences();
		testSynchronizationAndGapRecovery();
		testObservationParsing();
	}
	
	private void testRandomVectors()
	{
		VaultRollRandom normal =
			VaultRollRandom.forSequence(123L, VaultRollMode.NORMAL);
		check(normal.nextLong() == 2472151320644895493L,
			"normal named-sequence long vector changed");
		check(normal.nextInt(10) == 4, "normal Lemire nextInt vector changed");
		check(
			Float.floatToIntBits(normal.nextFloat()) == Float
				.floatToIntBits(0.30138028F),
			"normal top-24 nextFloat vector changed");
		VaultRollRandom ominous =
			VaultRollRandom.forSequence(123L, VaultRollMode.OMINOUS);
		check(ominous.nextLong() == -1938528456957994456L,
			"ominous named-sequence long vector changed");
	}
	
	private void testOpeningVectors()
	{
		check(VaultRollPredictor.predictOpening(123L, VaultRollMode.NORMAL, 0)
			.describe()
			.equals("minecraft:crossbowx1 [unbreaking 2] [quick_charge 1], "
				+ "minecraft:emeraldx4, minecraft:iron_ingotx4, "
				+ "minecraft:arrowx2"),
			"normal opening vector changed");
		check(
			VaultRollPredictor.predictOpening(123L, VaultRollMode.OMINOUS, 0)
				.describe()
				.equals("minecraft:diamond_axex1 [unbreaking 3], "
					+ "minecraft:ominous_bottlex1 [amplifier=3]"),
			"ominous opening vector changed");
	}
	
	private void testCapturedOminousSequence()
	{
		long seed = 1_054_341_629L;
		VaultRollObservation detailed = new VaultRollObservation(
			VaultRollMode.OMINOUS,
			VaultRollPredictor.predictOpening(seed, VaultRollMode.OMINOUS, 11)
				.stacks().stream().map(stack -> VaultRollStack
					.plain(stack.itemId(), stack.count()))
				.toList());
		VaultRollSynchronizer.Result detailedResult =
			VaultRollSynchronizer.synchronize(seed, VaultRollMode.OMINOUS,
				List.of(detailed), 100_000, () -> false);
		check(
			detailedResult.status() == VaultRollSynchronizer.Status.UNIQUE
				&& detailedResult.uniqueFirstOpening() == 11,
			"one ordered detailed opening must identify the captured sequence");
		
		List<VaultRollObservation> observations = List.of(
			VaultRollObservation.parse(VaultRollMode.OMINOUS,
				"emerald=19, diamond=3, ominous_bottle=1, "
					+ "enchanted_golden_apple=1"),
			VaultRollObservation.parse(VaultRollMode.OMINOUS,
				"wind_charge=10, iron_block=1, tipped_arrow=10, "
					+ "ominous_bottle=1"),
			VaultRollObservation.parse(VaultRollMode.OMINOUS,
				"wind_charge=19, flow_banner_pattern=1, diamond=2"));
		VaultRollSynchronizer.Result result = VaultRollSynchronizer.synchronize(
			seed, VaultRollMode.OMINOUS, observations, 100, () -> false);
		check(
			result.status() == VaultRollSynchronizer.Status.UNIQUE
				&& result.uniqueFirstOpening() == 11,
			"reported ominous observations must match the simulator sequence");
	}
	
	private void testIndependentSequences()
	{
		String normal = VaultRollPredictor
			.predictOpening(123L, VaultRollMode.NORMAL, 0).describe();
		String ominous = VaultRollPredictor
			.predictOpening(123L, VaultRollMode.OMINOUS, 0).describe();
		check(!normal.equals(ominous),
			"normal and ominous sequences must be independent");
	}
	
	private void testSynchronizationAndGapRecovery()
	{
		for(VaultRollMode mode : VaultRollMode.values())
		{
			List<VaultRollObservation> observations =
				VaultRollPredictor.predictOpenings(123L, mode, 7, 4).stream()
					.map(opening -> VaultRollObservation.exact(mode, opening))
					.toList();
			VaultRollSynchronizer.Result sync = VaultRollSynchronizer
				.synchronize(123L, mode, observations, 100, () -> false);
			check(
				sync.status() == VaultRollSynchronizer.Status.UNIQUE
					&& sync.uniqueFirstOpening() == 7,
				mode.id() + " synchronization vector changed");
			VaultRollObservation afterGap = VaultRollObservation.exact(mode,
				VaultRollPredictor.predictOpening(123L, mode, 10));
			VaultRollSynchronizer.RecoveryResult recovery =
				VaultRollSynchronizer.recover(123L, mode, 7, afterGap, 5,
					() -> false);
			check(
				recovery.status() == VaultRollSynchronizer.Status.UNIQUE
					&& recovery.matchedOpening() == 10
					&& recovery.skippedOpenings() == 3,
				mode.id() + " gap recovery vector changed");
		}
	}
	
	private void testObservationParsing()
	{
		VaultRollObservation observation = VaultRollObservation.parse(
			VaultRollMode.OMINOUS, "emerald=7, wind_charge=12, diamond=2");
		check(
			observation.items().get("minecraft:emerald") == 7
				&& observation.items().get("minecraft:wind_charge") == 12
				&& observation.items().get("minecraft:diamond") == 2,
			"manual observation parser changed");
	}
	
	private void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError("VaultRoll model test failed: " + message);
	}
}
