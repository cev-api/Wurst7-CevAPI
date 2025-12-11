/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.wurstclient.WurstClient;
import net.wurstclient.events.RenderListener;
import net.wurstclient.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;

/** Renders temporary highlight boxes at specific chest positions. */
public final class TargetHighlighter implements RenderListener
{
	private static final long DEFAULT_DURATION_MS = 60_000L;
	public static final TargetHighlighter INSTANCE = new TargetHighlighter();
	
	// key = dimensionId + ":" + x + ":" + y + ":" + z
	private final Map<String, Target> targets = new HashMap<>();
	private int quadColor = 0x4022FF88;
	private int lineColor = 0x8022FF88;
	
	private TargetHighlighter()
	{}
	
	private static final class Target
	{
		long expireAt;
		int minX;
		int minY;
		int minZ;
		int maxX;
		int maxY;
		int maxZ;
	}
	
	public void setColors(int quadColor, int lineColor)
	{
		this.quadColor = quadColor;
		this.lineColor = lineColor;
	}
	
	public void toggle(String dimension, BlockPos pos)
	{
		toggle(dimension, pos, pos, DEFAULT_DURATION_MS);
	}
	
	public void toggle(String dimension, BlockPos min, BlockPos max)
	{
		toggle(dimension, min, max, DEFAULT_DURATION_MS);
	}
	
	public synchronized void toggle(String dimension, BlockPos min,
		BlockPos max, long durationMs)
	{
		if(min == null)
			return;
		BlockPos actualMax = max == null ? min : max;
		String key = key(dimension, min);
		if(targets.containsKey(key))
		{
			targets.remove(key);
			return;
		}
		Target target = new Target();
		target.expireAt =
			System.currentTimeMillis() + Math.max(1000L, durationMs);
		target.minX = Math.min(min.getX(), actualMax.getX());
		target.minY = Math.min(min.getY(), actualMax.getY());
		target.minZ = Math.min(min.getZ(), actualMax.getZ());
		target.maxX = Math.max(min.getX(), actualMax.getX());
		target.maxY = Math.max(min.getY(), actualMax.getY());
		target.maxZ = Math.max(min.getZ(), actualMax.getZ());
		targets.put(key, target);
	}
	
	public synchronized boolean has(String dimension, BlockPos pos)
	{
		return targets.containsKey(key(dimension, pos));
	}
	
	public synchronized boolean clear(String dimension, BlockPos pos)
	{
		return targets.remove(key(dimension, pos)) != null;
	}
	
	private static String key(String dim, BlockPos p)
	{
		return (dim == null ? "" : dim) + "|" + p.getX() + "," + p.getY() + ","
			+ p.getZ();
	}
	
	@Override
	public synchronized void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(WurstClient.MC == null || WurstClient.MC.level == null)
			return;
		String dim = WurstClient.MC.level.dimension().identifier().toString();
		long now = System.currentTimeMillis();
		List<AABB> boxes = new ArrayList<>();
		Iterator<Map.Entry<String, Target>> it = targets.entrySet().iterator();
		while(it.hasNext())
		{
			Map.Entry<String, Target> e = it.next();
			Target target = e.getValue();
			if(target == null)
			{
				it.remove();
				continue;
			}
			if(target.expireAt < now)
			{
				it.remove();
				continue;
			}
			String k = e.getKey();
			if(!k.startsWith(dim + "|"))
				continue;
			boxes.add(new AABB(target.minX, target.minY, target.minZ,
				target.maxX + 1, target.maxY + 1, target.maxZ + 1));
		}
		if(boxes.isEmpty())
			return;
		RenderUtils.drawSolidBoxes(matrixStack, boxes, quadColor, false);
		RenderUtils.drawOutlinedBoxes(matrixStack, boxes, lineColor, false);
	}
}
