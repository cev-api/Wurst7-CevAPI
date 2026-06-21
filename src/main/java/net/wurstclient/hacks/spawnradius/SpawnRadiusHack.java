/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spawnradius;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.WurstRenderLayers;
import net.wurstclient.hack.Hack;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EasyVertexBuffer;
import net.wurstclient.util.RegionPos;
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
	
	private EasyVertexBuffer buffer;
	private RegionPos bufferRegion = new RegionPos(0, 0);
	private long bufferSignature;
	private RegionPos pendingRegion = new RegionPos(0, 0);
	private long pendingSignature;
	private boolean dirty = true;
	
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
		closeBuffer();
		dirty = true;
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
		
		RegionPos region = RegionPos.of(MC.player.blockPosition());
		long signature = signature(spawners, region);
		if(signature != bufferSignature)
		{
			pendingRegion = region;
			pendingSignature = signature;
			dirty = true;
		}
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(MC.level == null || spawners.isEmpty())
			return;
		
		if(NiceWurstModule.isActive())
		{
			renderCulled(matrices);
			return;
		}
		
		if(dirty || buffer == null)
			rebuild();
		
		if(buffer == null)
			return;
		
		RenderType layer = WurstRenderLayers.getLines(false);
		matrices.pushPose();
		RenderUtils.applyRegionalRenderOffset(matrices, bufferRegion);
		buffer.draw(matrices, layer, 1, 1, 1, 1);
		matrices.popPose();
	}
	
	private void renderCulled(PoseStack matrices)
	{
		List<AABB> boxes = new ArrayList<>();
		for(SpawnerInfo info : spawners)
		{
			Vec3 center = Vec3.atCenterOf(info.pos()).add(0, HEIGHT_OFFSET, 0);
			if(!NiceWurstModule.shouldRenderTarget(center))
				continue;
			
			int color = info.detected() ? COLOR_DETECTED : COLOR_IDLE;
			int segments = Math.max(32, info.radius() * 6);
			RenderUtils.drawCircle(matrices, center, info.radius(), segments,
				color, false);
			
			AABB box = BlockUtils.getBoundingBox(info.pos());
			if(box != null)
				boxes.add(box);
		}
		
		if(!boxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrices, boxes, SPAWNER_BOX_COLOR,
				false);
	}
	
	private void rebuild()
	{
		closeBuffer();
		bufferRegion = pendingRegion;
		bufferSignature = pendingSignature;
		dirty = false;
		
		if(spawners.isEmpty())
			return;
		
		List<SpawnerInfo> snapshot = new ArrayList<>(spawners);
		RegionPos region = bufferRegion;
		buffer = EasyVertexBuffer.createAndUpload(PrimitiveTopology.LINES,
			DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, b -> {
				for(SpawnerInfo info : snapshot)
					buildSpawner(b, info, region);
			});
	}
	
	private static void buildSpawner(VertexConsumer b, SpawnerInfo info,
		RegionPos region)
	{
		int radius = info.radius();
		double cx = info.pos().getX() + 0.5 - region.x();
		double cy = info.pos().getY() + 0.5 + HEIGHT_OFFSET;
		double cz = info.pos().getZ() + 0.5 - region.z();
		int color = info.detected() ? COLOR_DETECTED : COLOR_IDLE;
		
		int segments = Math.max(32, radius * 6);
		double step = (Math.PI * 2) / segments;
		float py = (float)cy;
		float px = (float)(cx + radius);
		float pz = (float)cz;
		for(int i = 1; i <= segments; i++)
		{
			double angle = i * step;
			float nx = (float)(cx + Math.cos(angle) * radius);
			float nz = (float)(cz + Math.sin(angle) * radius);
			RenderUtils.drawLine(b, px, py, pz, nx, py, nz, color);
			px = nx;
			pz = nz;
		}
		
		float x1 = info.pos().getX() - region.x();
		float x2 = x1 + 1;
		float y1 = info.pos().getY();
		float y2 = y1 + 1;
		float z1 = info.pos().getZ() - region.z();
		float z2 = z1 + 1;
		addBoxOutline(b, x1, y1, z1, x2, y2, z2, SPAWNER_BOX_COLOR);
	}
	
	private static void addBoxOutline(VertexConsumer b, float x1, float y1,
		float z1, float x2, float y2, float z2, int color)
	{
		RenderUtils.drawLine(b, x1, y1, z1, x2, y1, z1, color);
		RenderUtils.drawLine(b, x2, y1, z1, x2, y1, z2, color);
		RenderUtils.drawLine(b, x2, y1, z2, x1, y1, z2, color);
		RenderUtils.drawLine(b, x1, y1, z2, x1, y1, z1, color);
		RenderUtils.drawLine(b, x1, y2, z1, x2, y2, z1, color);
		RenderUtils.drawLine(b, x2, y2, z1, x2, y2, z2, color);
		RenderUtils.drawLine(b, x2, y2, z2, x1, y2, z2, color);
		RenderUtils.drawLine(b, x1, y2, z2, x1, y2, z1, color);
		RenderUtils.drawLine(b, x1, y1, z1, x1, y2, z1, color);
		RenderUtils.drawLine(b, x2, y1, z1, x2, y2, z1, color);
		RenderUtils.drawLine(b, x2, y1, z2, x2, y2, z2, color);
		RenderUtils.drawLine(b, x1, y1, z2, x1, y2, z2, color);
	}
	
	/*
	 * Cheap order-independent hash-combine,
	 * fingerprints current spawner set to cheaply detect changes since last GPU
	 * upload
	 */
	private static long signature(List<SpawnerInfo> list, RegionPos region)
	{
		long set = 0;
		for(SpawnerInfo info : list)
		{
			long h = info.pos().asLong() * 1099511628211L; // FNV prime
			h ^= info.radius() * 31L;
			h ^= info.detected() ? 0x9E3779B97F4A7C15L : 0L; // golden-ratio
																// constant
			set ^= h; // XOR accumulate
		}
		
		long sig = region.x();
		sig = sig * 31 + region.z();
		sig = sig * 31 + list.size();
		sig = sig * 31 + set;
		return sig;
	}
	
	private void closeBuffer()
	{
		if(buffer != null)
		{
			buffer.close();
			buffer = null;
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
