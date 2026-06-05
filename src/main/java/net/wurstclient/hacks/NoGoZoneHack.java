/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.util.RenderUtils;

@SearchTags({"no go zone", "nogozone", "zone", "no go", "restrict area",
	"block area"})
@net.wurstclient.hack.DontSaveState
public final class NoGoZoneHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final List<NoGoZone> ZONES = new ArrayList<>();
	private static final Set<Integer> PLAYER_INSIDE_ZONE_IDS = new HashSet<>();
	private static final int RENDER_HEIGHT = 50;
	private static int nextZoneId = 1;
	
	private final CheckboxSetting showRender = new CheckboxSetting(
		"Show render",
		"Renders the zone marker. Disable this to keep enforcing the zone without showing it.",
		true);
	
	private final EnumSetting<RenderShape> renderShape =
		new EnumSetting<>("Render shape", "Shape used to render NoGoZones.",
			RenderShape.values(), RenderShape.BOX);
	
	private final ColorSetting boxColor = new ColorSetting("Box color",
		"Color of the no-go zone box render.", new Color(0xFF0000));
	
	private final ColorSetting octahedronColor =
		new ColorSetting("Octahedron color",
			"Color of the no-go zone octahedron render.", new Color(0x003A8C));
	
	public NoGoZoneHack()
	{
		super("NoGoZone");
		// No category = unlisted, accessible via search/navigator
		addSetting(showRender);
		addSetting(renderShape);
		addSetting(boxColor);
		addSetting(octahedronColor);
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
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null)
			return;
		
		Vec3 playerPos = MC.player.position();
		boolean insideAnyZone = false;
		
		for(NoGoZone zone : ZONES)
		{
			boolean playerInside = zone.contains(playerPos);
			if(playerInside)
				insideAnyZone = true;
			
			if(playerInside && !PLAYER_INSIDE_ZONE_IDS.contains(zone.id))
			{
				keepPlayerOut(zone);
				return;
			}
			
			if(!playerInside && PLAYER_INSIDE_ZONE_IDS.contains(zone.id))
			{
				// Player left the zone - blocked from re-entering
				PLAYER_INSIDE_ZONE_IDS.remove(zone.id);
			}
		}
		
		if(!insideAnyZone)
			return;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.player == null || !showRender.isChecked())
			return;
		
		ArrayList<AABB> boxes = new ArrayList<>();
		
		for(NoGoZone zone : ZONES)
			boxes.add(zone.getRenderBox());
		
		switch(renderShape.getSelected())
		{
			case BOX:
			int boxFillColor = boxColor.getColorI(0x30);
			int boxLineColor = boxColor.getColorI(0xC0);
			RenderUtils.drawSolidBoxes(matrixStack, boxes, boxFillColor, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, boxLineColor,
				false);
			RenderUtils.drawCrossBoxes(matrixStack, boxes, boxLineColor, false);
			break;
			
			case OCTAHEDRON:
			int octahedronFillColor = octahedronColor.getColorI(0x30);
			int octahedronLineColor = octahedronColor.getColorI(0xC0);
			RenderUtils.drawSolidOctahedrons(matrixStack, boxes,
				octahedronFillColor, false);
			RenderUtils.drawOutlinedOctahedrons(matrixStack, boxes,
				octahedronLineColor, false);
			break;
		}
	}
	
	private void keepPlayerOut(NoGoZone zone)
	{
		pushPlayerOut(zone);
	}
	
	private void pushPlayerOut(NoGoZone zone)
	{
		double playerX = MC.player.getX();
		double playerZ = MC.player.getZ();
		
		int zoneMinX = zone.minX;
		int zoneMaxX = zone.maxX + 1;
		int zoneMinZ = zone.minZ;
		int zoneMaxZ = zone.maxZ + 1;
		
		// Distances to each edge (positive = inside zone)
		double distMinX = playerX - zoneMinX;
		double distMaxX = zoneMaxX - playerX;
		double distMinZ = playerZ - zoneMinZ;
		double distMaxZ = zoneMaxZ - playerZ;
		
		// Find closest edge
		double targetX = playerX;
		double targetZ = playerZ;
		double minDist = Math.min(Math.min(distMinX, distMaxX),
			Math.min(distMinZ, distMaxZ));
		
		double pushAmount = 0.35;
		
		if(minDist == distMinX)
			targetX = zoneMinX - pushAmount;
		else if(minDist == distMaxX)
			targetX = zoneMaxX + pushAmount;
		else if(minDist == distMinZ)
			targetZ = zoneMinZ - pushAmount;
		else
			targetZ = zoneMaxZ + pushAmount;
		
		MC.player.setPos(targetX, MC.player.getY(), targetZ);
		stopPlayerMotion();
	}
	
	private void stopPlayerMotion()
	{
		MC.player.setDeltaMovement(0, 0, 0);
		MC.player.xxa = 0;
		MC.player.zza = 0;
	}
	
	public static List<NoGoZone> getZones()
	{
		return ZONES;
	}
	
	public static int addZone(BlockPos center, int blockRadius)
	{
		int id = nextZoneId++;
		NoGoZone zone = new NoGoZone(center, blockRadius, id);
		ZONES.add(zone);
		// Player is inside the zone they just created
		PLAYER_INSIDE_ZONE_IDS.add(id);
		return id;
	}
	
	public static boolean removeZone(int id)
	{
		PLAYER_INSIDE_ZONE_IDS.remove(id);
		return ZONES.removeIf(z -> z.id == id);
	}
	
	public static void clearAllZones()
	{
		PLAYER_INSIDE_ZONE_IDS.clear();
		ZONES.clear();
		nextZoneId = 1;
	}
	
	// ---- Zone data class ----
	
	private enum RenderShape
	{
		BOX("Box"),
		OCTAHEDRON("Octahedron");
		
		private final String name;
		
		private RenderShape(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public static class NoGoZone
	{
		public final int id;
		public final BlockPos center;
		public final int blockRadius;
		public final int minX;
		public final int maxX;
		public final int minZ;
		public final int maxZ;
		
		public NoGoZone(BlockPos center, int blockRadius, int id)
		{
			this.id = id;
			this.center = center;
			this.blockRadius = blockRadius;
			minX = center.getX() - blockRadius;
			maxX = center.getX() + blockRadius;
			minZ = center.getZ() - blockRadius;
			maxZ = center.getZ() + blockRadius;
		}
		
		public boolean containsChunk(ChunkPos chunk)
		{
			return chunk.getMaxBlockX() >= minX && chunk.getMinBlockX() <= maxX
				&& chunk.getMaxBlockZ() >= minZ && chunk.getMinBlockZ() <= maxZ;
		}
		
		public boolean contains(Vec3 pos)
		{
			return pos.x >= minX && pos.x <= maxX + 1 && pos.z >= minZ
				&& pos.z <= maxZ + 1;
		}
		
		public int getBlockRadius()
		{
			return blockRadius;
		}
		
		public AABB getBoundingBox()
		{
			int y = center.getY();
			return new AABB(minX, y, minZ, maxX + 1, y + 1, maxZ + 1);
		}
		
		public AABB getRenderBox()
		{
			int y = center.getY();
			return new AABB(minX, y, minZ, maxX + 1, y + RENDER_HEIGHT,
				maxZ + 1);
		}
	}
}
