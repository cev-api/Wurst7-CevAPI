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
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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
	private final List<SpawnerInfo> spawners = new ArrayList<>();
	
	public SpawnerEspHack()
	{
		super("SpawnerESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(color);
		addSetting(maxDistance);
		addSetting(overlayScale);
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
		ChunkUtils.getLoadedBlockEntities()
			.filter(SpawnerBlockEntity.class::isInstance)
			.map(SpawnerBlockEntity.class::cast).forEach(spawner -> {
				BlockPos pos = spawner.getBlockPos();
				Vec3 center = Vec3.atCenterOf(pos);
				if(!MC.level.getBlockState(pos).is(Blocks.SPAWNER)
					|| player.distanceToSqr(center) > maxSq)
					return;
				Entity display = spawner.getSpawner()
					.getOrCreateDisplayEntity(MC.level, pos);
				String name = display == null ? "Unknown"
					: display.getType().getDescription().getString();
				spawners.add(new SpawnerInfo(pos.immutable(),
					name == null || name.isBlank() ? "Unknown" : name));
			});
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
		List<RenderUtils.ColoredPoint> tracers = new ArrayList<>();
		for(SpawnerInfo info : spawners)
		{
			AABB box = new AABB(info.pos());
			if(style.hasBoxes())
				boxes.add(box);
			if(style.hasLines())
				tracers.add(
					new RenderUtils.ColoredPoint(box.getCenter(), lineColor));
			if(NiceWurstModule.shouldRenderTarget(box.getCenter()))
				drawLabel(matrices, info, distanceTo(info.pos()));
		}
		if(!boxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrices, boxes, lineColor, false);
		if(!tracers.isEmpty())
			RenderUtils.drawTracers("SpawnerESP", matrices, partialTicks,
				tracers, false);
	}
	
	private double distanceTo(BlockPos pos)
	{
		return MC.player == null ? 0
			: MC.player.position().distanceTo(Vec3.atCenterOf(pos));
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
		var camera = MC.getCameraEntity();
		if(camera != null)
		{
			matrices.mulPose(Axis.YP.rotationDegrees(-camera.getYRot()));
			matrices.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
		}
		matrices.mulPose(Axis.YP.rotationDegrees(180));
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
	
	private record SpawnerInfo(BlockPos pos, String mobName)
	{}
}
