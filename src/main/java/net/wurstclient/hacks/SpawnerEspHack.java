/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.CameraTransformViewBobbingListener.CameraTransformViewBobbingEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkUtils;

@SearchTags({"spawner esp", "mob spawner", "monster spawner"})
public final class SpawnerEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final ColorSetting color = new ColorSetting("Spawner color",
		"Color used to highlight mob spawners.", new Color(0xFFFF55));
	private final SliderSetting maxDistance = new SliderSetting("Max distance",
		"Only highlight spawners within this distance. 0 = unlimited.", 256, 0,
		512, 1, ValueDisplay.INTEGER);
	private final SliderSetting overlayScale = new SliderSetting(
		"Overlay scale", 0.5, 0.5, 2.0, 0.05, ValueDisplay.DECIMAL);
	private final CheckboxSetting flashSuspicious = new CheckboxSetting(
		"Flash activated or blocked spawners",
		"Flashes spawners that have activated, are currently ready nearby, or have too much block light to spawn mobs.",
		false);
	private final CheckboxSetting onlyShowSuspicious = new CheckboxSetting(
		"Only show activated or blocked spawners",
		"Hides ordinary spawners and only shows spawners that activated, are active nearby, or are too brightly lit.",
		false);
	private final CheckboxSetting fillBoxes = new CheckboxSetting("Fill boxes",
		"Adds a translucent solid fill to SpawnerESP boxes.", false);
	private final List<SpawnerInfo> spawners = new ArrayList<>();
	private final Set<Long> activatedSpawners = new HashSet<>();
	private final Map<Long, Integer> previousSpawnDelays = new HashMap<>();
	private static final Field SPAWN_DELAY = findSpawnDelayField();
	private static final int MAX_SPAWNER_BLOCK_LIGHT = 11;
	private static final int DEFAULT_ACTIVE_RANGE = 16;
	
	public SpawnerEspHack()
	{
		super("SpawnerESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(color);
		addSetting(maxDistance);
		addSetting(overlayScale);
		addSetting(flashSuspicious);
		addSetting(onlyShowSuspicious);
		addSetting(fillBoxes);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		spawners.clear();
		activatedSpawners.clear();
		previousSpawnDelays.clear();
	}
	
	@Override
	public void onUpdate()
	{
		spawners.clear();
		if(MC.level == null || MC.player == null)
			return;
		
		double max = maxDistance.getValue();
		double maxSq = max <= 0 ? Double.MAX_VALUE : max * max;
		Vec3 player = MC.player.position();
		Set<Long> loadedSpawners = new HashSet<>();
		ChunkUtils.getLoadedBlockEntities()
			.filter(SpawnerBlockEntity.class::isInstance)
			.map(SpawnerBlockEntity.class::cast).forEach(spawner -> {
				BlockPos pos = spawner.getBlockPos();
				Vec3 center = Vec3.atCenterOf(pos);
				long key = pos.asLong();
				loadedSpawners.add(key);
				int spawnDelay = readSpawnDelay(spawner.getSpawner());
				Integer previousDelay =
					previousSpawnDelays.put(key, spawnDelay);
				if(previousDelay != null && previousDelay <= 20
					&& spawnDelay > previousDelay + 10)
					activatedSpawners.add(key);
				if(!MC.level.getBlockState(pos).is(Blocks.SPAWNER)
					|| player.distanceToSqr(center) > maxSq)
					return;
				boolean activeNow = spawnDelay >= 0 && spawnDelay <= 20
					&& player
						.distanceToSqr(center) <= (double)DEFAULT_ACTIVE_RANGE
							* DEFAULT_ACTIVE_RANGE;
				boolean tooBright = MC.level.getBrightness(LightLayer.BLOCK,
					pos) > MAX_SPAWNER_BLOCK_LIGHT && !isLavaLit(pos);
				Entity display = spawner.getSpawner()
					.getOrCreateDisplayEntity(MC.level, pos);
				String name = display == null ? "Unknown"
					: display.getType().getDescription().getString();
				boolean suspicious =
					activatedSpawners.contains(key) || activeNow || tooBright;
				if(onlyShowSuspicious.isChecked() && !suspicious)
					return;
				spawners.add(new SpawnerInfo(pos.immutable(),
					name == null || name.isBlank() ? "Unknown" : name,
					flashSuspicious.isChecked() && suspicious, suspicious));
			});
		previousSpawnDelays.keySet().retainAll(loadedSpawners);
		activatedSpawners.retainAll(loadedSpawners);
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(MC.level == null || spawners.isEmpty())
			return;
		int lineColor = color.getColorI(0xFF);
		List<AABB> boxes = new ArrayList<>();
		List<AABB> flashingBoxes = new ArrayList<>();
		List<RenderUtils.ColoredPoint> tracers = new ArrayList<>();
		List<RenderUtils.ColoredPoint> flashingTracers = new ArrayList<>();
		for(SpawnerInfo info : spawners)
		{
			AABB box = new AABB(info.pos());
			if(style.hasBoxes())
				(info.flash() ? flashingBoxes : boxes).add(box);
			if(style.hasLines())
				(info.flash() ? flashingTracers : tracers).add(
					new RenderUtils.ColoredPoint(box.getCenter(), lineColor));
			if(NiceWurstModule.shouldRenderTarget(box.getCenter()))
				drawLabel(matrices, info, distanceTo(info.pos()));
		}
		int fillColor = color.getColorI(0x45);
		if(fillBoxes.isChecked() && !boxes.isEmpty())
			RenderUtils.drawSolidBoxes(matrices, boxes, fillColor, false);
		if(fillBoxes.isChecked() && !flashingBoxes.isEmpty())
			RenderUtils.drawSolidBoxes(matrices, flashingBoxes,
				RenderUtils.flashColor(fillColor), false);
		if(!boxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrices, boxes, lineColor, false);
		if(!flashingBoxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrices, flashingBoxes,
				RenderUtils.flashColor(lineColor), false);
		if(!tracers.isEmpty())
			RenderUtils.drawTracers("SpawnerESP", matrices, partialTicks,
				tracers, false);
		if(!flashingTracers.isEmpty())
			RenderUtils.drawTracers("SpawnerESP", matrices, partialTicks,
				flashingTracers.stream()
					.map(point -> new RenderUtils.ColoredPoint(point.point(),
						RenderUtils.flashColor(point.color())))
					.toList(),
				false);
	}
	
	private double distanceTo(BlockPos pos)
	{
		return MC.player == null ? 0
			: MC.player.position().distanceTo(Vec3.atCenterOf(pos));
	}
	
	private boolean isLavaLit(BlockPos pos)
	{
		if(MC.level == null)
			return false;
			
		// Lava's light can only keep a spawner above the hostile-mob threshold
		// when it is close. Do not classify that naturally lit dungeon as
		// blocked.
		for(int dx = -4; dx <= 4; dx++)
			for(int dy = -4; dy <= 4; dy++)
				for(int dz = -4; dz <= 4; dz++)
				{
					if(Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 4)
						continue;
					if(MC.level.getBlockState(pos.offset(dx, dy, dz))
						.is(Blocks.LAVA))
						return true;
				}
		return false;
	}
	
	private void drawLabel(PoseStack matrices, SpawnerInfo info,
		double distance)
	{
		if(MC.font == null)
			return;
		String text =
			info.mobName() + " Spawner [" + Math.round(distance) + "]";
		Vec3 cam = RenderUtils.getCameraPos();
		Vec3 pos = Vec3.atCenterOf(info.pos()).add(0, 1.15, 0);
		Vec3 dir = pos.subtract(cam);
		double dist = dir.length();
		if(dist > 12)
			pos = cam.add(dir.scale(12 / dist));
		matrices.pushPose();
		matrices.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
		RenderUtils.applyWorldTextOrientation(matrices);
		RenderUtils.mulPose(matrices, Axis.YP.rotationDegrees(180));
		float scale = 0.025F * RenderUtils
			.getCappedWorldLabelScale(overlayScale.getValueF(), dist);
		matrices.scale(scale, -scale, scale);
		Font font = MC.font;
		int x = -font.width(text) / 2;
		int background =
			(int)(MC.options.getBackgroundOpacity(0.25F) * 255) << 24;
		DisplayMode layer =
			NiceWurstModule.enforceTextLayer(DisplayMode.SEE_THROUGH);
		RenderUtils.drawTextInBatch(font, text, x, 0, 0xFFFFFFFF, false,
			matrices.last().pose(), null, layer, background, 0xF000F0);
		matrices.popPose();
	}
	
	private static int readSpawnDelay(BaseSpawner spawner)
	{
		if(SPAWN_DELAY == null)
			return -1;
		try
		{
			return SPAWN_DELAY.getInt(spawner);
		}catch(ReflectiveOperationException e)
		{
			return -1;
		}
	}
	
	private static Field findSpawnDelayField()
	{
		try
		{
			Field field = BaseSpawner.class.getDeclaredField("spawnDelay");
			field.setAccessible(true);
			return field;
		}catch(ReflectiveOperationException | RuntimeException e)
		{
			return null;
		}
	}
	
	private record SpawnerInfo(BlockPos pos, String mobName, boolean flash,
		boolean suspicious)
	{}
}
