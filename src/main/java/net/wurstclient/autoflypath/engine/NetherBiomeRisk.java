/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath.engine;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

public final class NetherBiomeRisk
{
	private static final Set<ResourceKey<Biome>> FEATURE_RISK_BIOMES = Set
		.of(Biomes.CRIMSON_FOREST, Biomes.WARPED_FOREST, Biomes.BASALT_DELTAS);
	private static volatile HolderLookup.Provider vanillaLookup;
	private final Climate.ParameterList<ResourceKey<Biome>> parameters;
	private final Climate.Sampler sampler;
	private final ConcurrentHashMap<Long, Boolean> chunkRisk =
		new ConcurrentHashMap();
	
	private NetherBiomeRisk(
		Climate.ParameterList<ResourceKey<Biome>> parameters,
		Climate.Sampler sampler)
	{
		this.parameters = parameters;
		this.sampler = sampler;
	}
	
	public static NetherBiomeRisk create(long seed)
	{
		HolderLookup.Provider lookup = vanillaLookup;
		if(lookup == null)
		{
			vanillaLookup = lookup = VanillaRegistries.createLookup();
		}
		Map presets = MultiNoiseBiomeSourceParameterList.knownPresets();
		Climate.ParameterList parameters = (Climate.ParameterList)presets
			.get(MultiNoiseBiomeSourceParameterList.Preset.NETHER);
		RandomState randomState =
			RandomState.create((HolderGetter.Provider)lookup,
				(ResourceKey)NoiseGeneratorSettings.NETHER, (long)seed);
		return new NetherBiomeRisk(
			(Climate.ParameterList<ResourceKey<Biome>>)parameters,
			randomState.sampler());
	}
	
	public ResourceKey<Biome> biomeAt(int blockX, int blockZ)
	{
		return (ResourceKey)this.parameters
			.findValue(this.sampler.sample(QuartPos.fromBlock((int)blockX),
				QuartPos.fromBlock((int)64), QuartPos.fromBlock((int)blockZ)));
	}
	
	public boolean isRiskyChunk(int chunkX, int chunkZ)
	{
		return this.chunkRisk
			.computeIfAbsent(ChunkPos.asLong((int)chunkX, (int)chunkZ), key -> {
				int[][] offs;
				int bx = (chunkX << 4) + 8;
				int bz = (chunkZ << 4) + 8;
				for(int[] o : offs =
					new int[][]{{0, 0}, {-8, -8}, {-8, 7}, {7, -8}, {7, 7}})
				{
					if(!FEATURE_RISK_BIOMES
						.contains(this.biomeAt(bx + o[0], bz + o[1])))
						continue;
					return true;
				}
				return false;
			});
	}
}
