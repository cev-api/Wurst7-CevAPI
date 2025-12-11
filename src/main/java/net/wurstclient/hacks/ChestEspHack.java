/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.chestesp.ChestEspGroup;
import net.wurstclient.hacks.chestesp.ChestEspGroupManager;
import net.wurstclient.chestsearch.ChestEntry;
import net.wurstclient.chestsearch.ChestManager;
import net.wurstclient.chestsearch.ChestSearchMarkerRenderer;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkUtils;

public class ChestEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final net.wurstclient.settings.CheckboxSetting stickyArea =
		new net.wurstclient.settings.CheckboxSetting("Sticky area",
			"Off: ESP drop-off follows you as chunks change.\n"
				+ "On: Keeps results anchored (useful for pathing back).\n"
				+ "Note: ChestESP tracks loaded block entities; visibility is still limited by server view distance.",
			false);
	private final ChestEspGroupManager groups = new ChestEspGroupManager();
	
	private final CheckboxSetting showCountInHackList = new CheckboxSetting(
		"HackList count",
		"Appends the number of detected chests/containers to this hack's entry in the HackList.",
		false);
	private int foundCount;
	
	private final CheckboxSetting onlyAboveGround =
		new CheckboxSetting("Above ground only",
			"Only show chests/containers at or above the configured Y level.",
			false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private java.util.List<ChestEntry> openedChests = java.util.List.of();
	
	public ChestEspHack()
	{
		super("ChestESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(stickyArea);
		groups.allGroups.stream().flatMap(ChestEspGroup::getSettings)
			.forEach(this::addSetting);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		addSetting(showCountInHackList);
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
		
		groups.allGroups.forEach(ChestEspGroup::clear);
		foundCount = 0;
	}
	
	@Override
	public void onUpdate()
	{
		groups.allGroups.forEach(ChestEspGroup::clear);
		
		double yLimit = aboveGroundY.getValue();
		boolean enforceAboveGround = onlyAboveGround.isChecked();
		
		ChunkUtils.getLoadedBlockEntities().forEach(be -> {
			if(enforceAboveGround && be.getBlockPos().getY() < yLimit)
				return;
			
			groups.blockGroups.forEach(group -> group.addIfMatches(be));
		});
		
		if(MC.level != null)
		{
			for(Entity entity : MC.level.entitiesForRendering())
			{
				if(enforceAboveGround && entity.getY() < yLimit)
					continue;
				
				groups.entityGroups
					.forEach(group -> group.addIfMatches(entity));
			}
		}
		
		int total = groups.allGroups.stream().filter(ChestEspGroup::isEnabled)
			.mapToInt(g -> g.getBoxes().size()).sum();
		foundCount = Math.min(total, 999);
		
		// Always load recorded chests from ChestSearch DB so ChestESP can
		// mark them even if the ChestSearch UI/hack isn't "enabled".
		try
		{
			ChestManager mgr = new ChestManager();
			openedChests = mgr.all();
		}catch(Throwable ignored)
		{
			openedChests = java.util.List.of();
		}
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		groups.entityGroups.stream().filter(ChestEspGroup::isEnabled)
			.forEach(g -> g.updateBoxes(partialTicks));
		
		if(style.hasBoxes())
			renderBoxes(matrixStack);
		
		if(style.hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	private void renderBoxes(PoseStack matrixStack)
	{
		for(ChestEspGroup group : groups.allGroups)
		{
			if(!group.isEnabled())
				continue;
			
			List<AABB> boxes = group.getBoxes();
			int quadsColor = group.getColorI(0x40);
			int linesColor = group.getColorI(0x80);
			
			RenderUtils.drawSolidBoxes(matrixStack, boxes, quadsColor, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, linesColor,
				false);
			
			// If ChestSearch marking is enabled, draw an X through opened
			// chests that match entries in the ChestSearch DB
			try
			{
				var csh = net.wurstclient.WurstClient.INSTANCE
					.getHax().chestSearchHack;
				if(csh != null && csh.isMarkOpenedChest()
					&& !openedChests.isEmpty())
				{
					// base esp line color retained if needed
					String curDimFull = MC.level == null ? "overworld"
						: MC.level.dimension().location().toString();
					String curDim = MC.level == null ? "overworld"
						: MC.level.dimension().location().getPath();
					for(AABB box : boxes)
					{
						int boxMinX = (int)Math.floor(box.minX + 1e-6);
						int boxMaxX = (int)Math.floor(box.maxX - 1e-6);
						int boxMinY = (int)Math.floor(box.minY + 1e-6);
						int boxMaxY = (int)Math.floor(box.maxY - 1e-6);
						int boxMinZ = (int)Math.floor(box.minZ + 1e-6);
						int boxMaxZ = (int)Math.floor(box.maxZ - 1e-6);
						boolean matched = false;
						for(ChestEntry e : openedChests)
						{
							if(e == null || e.dimension == null)
								continue;
							// Accept either namespaced ("minecraft:the_nether")
							// or
							// plain path ("the_nether") dimension identifiers.
							String ed = e.dimension;
							if(!(ed.equals(curDimFull) || ed.equals(curDim)
								|| ed.endsWith(":" + curDim)))
								continue;
							int minX = Math.min(e.x, e.maxX);
							int maxX = Math.max(e.x, e.maxX);
							int minY = Math.min(e.y, e.maxY);
							int maxY = Math.max(e.y, e.maxY);
							int minZ = Math.min(e.z, e.maxZ);
							int maxZ = Math.max(e.z, e.maxZ);
							// check range overlap between the ESP box and
							// recorded chest bounds
							boolean overlap = boxMinX <= maxX && boxMaxX >= minX
								&& boxMinY <= maxY && boxMaxY >= minY
								&& boxMinZ <= maxZ && boxMaxZ >= minZ;
							if(overlap)
							{
								matched = true;
								break;
							}
						}
						if(matched)
						{
							ChestSearchMarkerRenderer.drawMarker(matrixStack,
								box, csh.getMarkXColorARGB(),
								csh.getMarkXThickness(), false);
						}
					}
				}
			}catch(Throwable ignored)
			{
				// don't fail rendering if chestsearch isn't available
			}
		}
	}
	
	private void renderTracers(PoseStack matrixStack, float partialTicks)
	{
		for(ChestEspGroup group : groups.allGroups)
		{
			if(!group.isEnabled())
				continue;
			
			List<AABB> boxes = group.getBoxes();
			List<Vec3> ends = boxes.stream().map(AABB::getCenter).toList();
			int color = group.getColorI(0x80);
			
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, color,
				false);
		}
	}
	
	@Override
	public String getRenderName()
	{
		String base = getName();
		if(showCountInHackList.isChecked() && foundCount > 0)
			return base + " [" + foundCount + "]";
		return base;
	}
}
