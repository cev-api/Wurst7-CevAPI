/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;

public final class EffectParticleTracker
{
	private static final long PARTICLE_TTL_MS = 1600L;
	private static final double MAX_PARTICLE_DISTANCE_SQ = 2.6 * 2.6;
	private static final Map<LivingEntity, LinkedHashMap<Integer, Long>> RECENT_COLORS =
		new WeakHashMap<>();
	
	private EffectParticleTracker()
	{}
	
	public static void recordParticle(double x, double y, double z, int color)
	{
		if(WurstClient.MC == null || WurstClient.MC.level == null)
			return;
		
		color &= 0x00FFFFFF;
		if(color == 0)
			return;
		
		LivingEntity nearest = findNearestLivingEntity(x, y, z);
		if(nearest == null)
			return;
		
		long expiresAt = System.currentTimeMillis() + PARTICLE_TTL_MS;
		synchronized(RECENT_COLORS)
		{
			LinkedHashMap<Integer, Long> colors = RECENT_COLORS
				.computeIfAbsent(nearest, e -> new LinkedHashMap<>());
			colors.put(0xFF000000 | color, expiresAt);
		}
	}
	
	public static List<Integer> getRecentColors(LivingEntity entity)
	{
		if(entity == null)
			return List.of();
		
		long now = System.currentTimeMillis();
		synchronized(RECENT_COLORS)
		{
			LinkedHashMap<Integer, Long> colors = RECENT_COLORS.get(entity);
			if(colors == null)
				return List.of();
			
			colors.entrySet().removeIf(entry -> entry.getValue() < now);
			if(colors.isEmpty())
			{
				RECENT_COLORS.remove(entity);
				return List.of();
			}
			
			return new ArrayList<>(colors.keySet());
		}
	}
	
	private static LivingEntity findNearestLivingEntity(double x, double y,
		double z)
	{
		Vec3 particlePos = new Vec3(x, y, z);
		LivingEntity nearest = null;
		double nearestDistanceSq = MAX_PARTICLE_DISTANCE_SQ;
		
		for(Entity entity : WurstClient.MC.level.entitiesForRendering())
		{
			if(!(entity instanceof LivingEntity living) || !living.isAlive())
				continue;
			
			AABB box = living.getBoundingBox().inflate(0.85, 0.55, 0.85);
			double distanceSq = box.distanceToSqr(particlePos);
			if(distanceSq >= nearestDistanceSq)
				continue;
			
			nearest = living;
			nearestDistanceSq = distanceSq;
		}
		
		return nearest;
	}
}
