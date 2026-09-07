/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * Registry lookups for content that only exists in newer Minecraft versions.
 *
 * <p>
 * This client builds against 1.21.11, so content added in 26.2/26.3 (potent
 * sulfur, geyser particles, ...) cannot be referenced directly. The constants
 * below resolve to {@code null} on versions that don't have them, and the
 * features that use them only activate while playing on a server that actually
 * sends that content (typically a 26.3+ server reached through ViaFabricPlus).
 */
public enum McVersionCompat
{
	;
	
	public static final Block POTENT_SULFUR = block("potent_sulfur");
	
	public static final ParticleType<?> GEYSER = particle("geyser");
	public static final ParticleType<?> GEYSER_BASE = particle("geyser_base");
	public static final ParticleType<?> GEYSER_POOF = particle("geyser_poof");
	public static final ParticleType<?> GEYSER_PLUME = particle("geyser_plume");
	
	/**
	 * Returns the block with the given path in the {@code minecraft} namespace,
	 * or {@code null} if this Minecraft version doesn't have it.
	 */
	public static Block block(String path)
	{
		return BuiltInRegistries.BLOCK.getOptional(id(path)).orElse(null);
	}
	
	/**
	 * Returns the particle type with the given path in the {@code minecraft}
	 * namespace, or {@code null} if this Minecraft version doesn't have it.
	 */
	public static ParticleType<?> particle(String path)
	{
		return BuiltInRegistries.PARTICLE_TYPE.getOptional(id(path))
			.orElse(null);
	}
	
	/**
	 * Checks a block state against a block that may not exist on this
	 * Minecraft version.
	 */
	public static boolean is(
		net.minecraft.world.level.block.state.BlockState state, Block block)
	{
		return block != null && state != null && state.is(block);
	}
	
	/**
	 * Returns true if the given entity is a filled sulfur cube (26.3+ only).
	 */
	public static boolean isFilledSulfurCube(
		net.minecraft.world.entity.Entity entity)
	{
		return SulfurCubeCompat.isFilledSulfurCube(entity);
	}
	
	/**
	 * Checks whether the given particle options belong to one of the geyser
	 * particle types.
	 */
	public static boolean isGeyserParticle(
		net.minecraft.core.particles.ParticleOptions options)
	{
		if(options == null)
			return false;
		
		ParticleType<?> type = options.getType();
		return type != null
			&& (matches(type, GEYSER) || matches(type, GEYSER_BASE)
				|| matches(type, GEYSER_POOF) || matches(type, GEYSER_PLUME));
	}
	
	private static boolean matches(ParticleType<?> a, ParticleType<?> b)
	{
		return b != null && a == b;
	}
	
	private static Identifier id(String path)
	{
		return Identifier.fromNamespaceAndPath("minecraft", path);
	}
}
