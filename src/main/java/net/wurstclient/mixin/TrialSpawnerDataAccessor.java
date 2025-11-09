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

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.block.spawner.TrialSpawnerData;

@Mixin(TrialSpawnerData.class)
public interface TrialSpawnerDataAccessor
{
	@Accessor("cooldownEnd")
	long getCooldownEnd();
	
	@Accessor("nextMobSpawnsAt")
	long getNextMobSpawnsAt();
	
	@Accessor("totalSpawnedMobs")
	int getTotalSpawnedMobs();
	
	@Accessor("spawnedMobsAlive")
	Set<UUID> getSpawnedMobsAlive();
}
