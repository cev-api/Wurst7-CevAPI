/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TrialSpawnerStateData.class)
public interface TrialSpawnerDataAccessor
{
	@Accessor("cooldownEndsAt")
	long getCooldownEnd();
	
	@Accessor("nextMobSpawnsAt")
	long getNextMobSpawnsAt();
	
	@Accessor("totalMobsSpawned")
	int getTotalSpawnedMobs();
	
	@Accessor("currentMobs")
	Set<UUID> getSpawnedMobsAlive();
}
