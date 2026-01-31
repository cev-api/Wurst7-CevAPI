/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spawnradius;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkUtils;

public final class SpawnRadiusHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final int COLOR_IDLE = 0xFFFFFFFF;
	private static final int COLOR_DETECTED = 0xFF00FF00;
	private static final int SPAWNER_BOX_COLOR = 0xFFFFFFFF;
	private static final float HEIGHT_OFFSET = 0.05F;
	private static final int DEFAULT_RADIUS = 16;
	private static final Method GET_REQUIRED_PLAYER_RANGE;
	
	static
	{
		Method method = null;
		for(String name : new String[]{"getRequiredPlayerRange",
			"getRequiredPlayerDistance", "getRequiredPlayerRange$"})
		{
			try
			{
				method = BaseSpawner.class.getMethod(name);
				break;
			}catch(Exception ignored)
			{
				
			}
		}
		GET_REQUIRED_PLAYER_RANGE = method;
	}
	
	private final List<SpawnerInfo> spawners = new ArrayList<>();
	
	public SpawnRadiusHack()
	{
		super("SpawnRadius");
		setCategory(Category.RENDER);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		spawners.clear();
	}
	
	@Override
	public void onUpdate()
	{
		spawners.clear();
		if(MC.level == null || MC.player == null)
			return;
		
		Vec3 playerPos = MC.player.position();
		ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof SpawnerBlockEntity)
			.map(be -> (SpawnerBlockEntity)be).forEach(spawner -> {
				BlockPos pos = spawner.getBlockPos();
				int radius = readSpawnerRadius(spawner.getSpawner());
				if(radius <= 0)
					return;
				
				Vec3 center = Vec3.atCenterOf(pos);
				double sq = playerPos.distanceToSqr(center);
				boolean detected = sq <= (double)radius * radius;
				spawners
					.add(new SpawnerInfo(pos.immutable(), radius, detected));
			});
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(MC.level == null || spawners.isEmpty())
			return;
		
		for(SpawnerInfo info : spawners)
		{
			Vec3 center = Vec3.atCenterOf(info.pos()).add(0, HEIGHT_OFFSET, 0);
			if(!NiceWurstModule.shouldRenderTarget(center))
				continue;
			
			drawCircle(matrices, center, info.radius(), info.detected());
			// Draw a simple white ESP box on the spawner block itself
			AABB box = BlockUtils.getBoundingBox(info.pos());
			if(box != null)
				RenderUtils.drawOutlinedBoxes(matrices,
					java.util.Collections.singletonList(box), SPAWNER_BOX_COLOR,
					false);
		}
	}
	
	private static void drawCircle(PoseStack matrices, Vec3 center, int radius,
		boolean detected)
	{
		int color = detected ? COLOR_DETECTED : COLOR_IDLE;
		int segments = Math.max(32, radius * 6);
		double step = (Math.PI * 2) / segments;
		Vec3 prev = center.add(radius, 0, 0);
		
		for(int i = 1; i <= segments; i++)
		{
			double angle = i * step;
			Vec3 next = center.add(Math.cos(angle) * radius, 0,
				Math.sin(angle) * radius);
			RenderUtils.drawLine(matrices, prev, next, color, false);
			prev = next;
		}
	}
	
	private static int readSpawnerRadius(BaseSpawner spawner)
	{
		if(GET_REQUIRED_PLAYER_RANGE != null)
		{
			try
			{
				Object value = GET_REQUIRED_PLAYER_RANGE.invoke(spawner);
				if(value instanceof Number number)
					return number.intValue();
			}catch(Exception ignored)
			{
				
			}
		}
		
		return DEFAULT_RADIUS;
	}
	
	private static final class SpawnerInfo
	{
		private final BlockPos pos;
		private final int radius;
		private final boolean detected;
		
		SpawnerInfo(BlockPos pos, int radius, boolean detected)
		{
			this.pos = pos;
			this.radius = radius;
			this.detected = detected;
		}
		
		BlockPos pos()
		{
			return pos;
		}
		
		int radius()
		{
			return radius;
		}
		
		boolean detected()
		{
			return detected;
		}
	}
}
