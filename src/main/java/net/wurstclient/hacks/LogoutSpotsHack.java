/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.waypoints.Waypoint;
import net.wurstclient.waypoints.WaypointDimension;

@SearchTags({"logout spots", "logouts"})
public final class LogoutSpotsHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final class Entry
	{
		final String name;
		final Box box;
		final String dimKey;
		
		Entry(UUID u, String n, Box b, float h, String dim)
		{
			name = n;
			box = b;
			dimKey = dim;
		}
	}
	
	private final ColorSetting sideColor = new ColorSetting("Side color",
		"Box sides color.", new Color(64, 128, 255, 64));
	private final ColorSetting lineColor = new ColorSetting("Line color",
		"Box outline color.", new Color(64, 128, 255, 192));
	private final SliderSetting scale =
		new SliderSetting("Name scale", 1.0, 0.5, 3.0, 0.1,
			net.wurstclient.settings.SliderSetting.ValueDisplay.DECIMAL);
	private final net.wurstclient.settings.CheckboxSetting showTracers =
		new net.wurstclient.settings.CheckboxSetting("Show tracers", true);
	private final net.wurstclient.settings.CheckboxSetting addAsWaypoint =
		new net.wurstclient.settings.CheckboxSetting(
			"Add logout spot as waypoint", false);
	
	private final Map<UUID, Entry> spots = new HashMap<>();
	private List<PlayerListEntry> lastList = List.of();
	private Map<UUID, PlayerEntity> lastPlayers = Map.of();
	private final Map<UUID, java.util.UUID> spotToWaypoint = new HashMap<>();
	
	public LogoutSpotsHack()
	{
		super("LogoutSpots");
		setCategory(Category.RENDER);
		addSetting(sideColor);
		addSetting(lineColor);
		addSetting(scale);
		addSetting(showTracers);
		addSetting(addAsWaypoint);
	}
	
	@Override
	protected void onEnable()
	{
		spots.clear();
		snapshot();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		spotToWaypoint.clear();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		spots.clear();
		// remove any temporary waypoints that we created
		if(WURST.getHax().waypointsHack != null)
		{
			for(java.util.UUID wp : new ArrayList<>(spotToWaypoint.values()))
				WURST.getHax().waypointsHack.removeTemporaryWaypoint(wp);
		}
		spotToWaypoint.clear();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.getNetworkHandler() == null || MC.world == null)
			return;
		var nowList = MC.getNetworkHandler().getPlayerList();
		if(nowList.size() != lastList.size())
		{
			// find missing
			var lastMap = new HashMap<UUID, PlayerListEntry>();
			for(var e : lastList)
				lastMap.put(e.getProfile().id(), e);
			var nowMap = new HashMap<UUID, PlayerListEntry>();
			for(var e : nowList)
				nowMap.put(e.getProfile().id(), e);
			for(var id : lastMap.keySet())
				if(!nowMap.containsKey(id))
				{
					PlayerEntity p = lastPlayers.get(id);
					if(p != null)
					{
						Box b = p.getBoundingBox();
						float h = p.getHealth();
						spots.put(id, new Entry(id, p.getName().getString(), b,
							h, currentDimKey()));
						// Optionally add a temporary waypoint for this logout
						// spot
						if(addAsWaypoint.isChecked()
							&& WURST.getHax().waypointsHack != null
							&& WURST.getHax().waypointsHack.isEnabled())
						{
							Waypoint w =
								new Waypoint(java.util.UUID.randomUUID(),
									System.currentTimeMillis());
							w.setName("Logout: " + p.getName().getString());
							w.setIcon("skull");
							w.setColor(0xFF88CCFF);
							w.setPos(new net.minecraft.util.math.BlockPos(
								(int)p.getX(), (int)p.getY(), (int)p.getZ()));
							// set waypoint dimension based on current world
							w.setDimension(
								mapDimKeyToWaypointDim(currentDimKey()));
							w.setActionWhenNear(Waypoint.ActionWhenNear.DELETE);
							w.setActionWhenNearDistance(4);
							w.setLines(showTracers.isChecked());
							// add as temporary waypoint via WaypointsHack API
							java.util.UUID wpUuid = WURST.getHax().waypointsHack
								.addTemporaryWaypoint(w);
							spotToWaypoint.put(id, wpUuid);
						}
					}
				}
			snapshot();
		}
		// cull rejoined players
		if(MC.world != null)
		{
			for(PlayerEntity p : MC.world.getPlayers())
			{
				spots.remove(p.getUuid());
				// remove any temporary waypoint associated with this spot
				java.util.UUID wp = spotToWaypoint.remove(p.getUuid());
				if(wp != null && WURST.getHax().waypointsHack != null)
					WURST.getHax().waypointsHack.removeTemporaryWaypoint(wp);
			}
		}
	}
	
	private String currentDimKey()
	{
		if(MC.world == null)
			return "overworld";
		return MC.world.getRegistryKey().getValue().getPath();
	}
	
	private void snapshot()
	{
		if(MC.getNetworkHandler() != null)
			lastList = new java.util.ArrayList<>(
				MC.getNetworkHandler().getPlayerList());
		var map = new HashMap<UUID, PlayerEntity>();
		if(MC.world != null)
		{
			for(PlayerEntity p : MC.world.getPlayers())
				map.put(p.getUuid(), p);
		}
		lastPlayers = map;
	}
	
	@Override
	public void onRender(MatrixStack matrices, float partialTicks)
	{
		if(spots.isEmpty())
			return;
		String curDim = currentDimKey();
		int sides = sideColor.getColorI(0x40);
		int lines = lineColor.getColorI(0xFF);
		var boxes = new java.util.ArrayList<Box>();
		for(var e : spots.values())
			if(e.dimKey.equals(curDim))
				boxes.add(e.box);
		if(boxes.isEmpty())
			return;
		RenderUtils.drawSolidBoxes(matrices, boxes, sides, false);
		RenderUtils.drawOutlinedBoxes(matrices, boxes, lines, false);
		// (Optional) draw tracers to centers
		if(showTracers.isChecked())
		{
			var ends = boxes.stream().map(Box::getCenter).toList();
			RenderUtils.drawTracers(matrices, partialTicks, ends, lines, false);
		}
		for(var e : spots.values())
		{
			if(!e.dimKey.equals(curDim))
				continue;
			var c = e.box.getCenter();
			drawWorldLabel(matrices, e.name, c.x, e.box.maxY + 0.5, c.z,
				0xFFFFFFFF, scale.getValueF());
		}
	}
	
	private void drawWorldLabel(MatrixStack matrices, String text, double x,
		double y, double z, int argb, float scale)
	{
		matrices.push();
		net.minecraft.util.math.Vec3d cam =
			net.wurstclient.util.RenderUtils.getCameraPos();
		matrices.translate(x - cam.x, y - cam.y, z - cam.z);
		// Face the camera (billboard)
		var camEntity = MC.getCameraEntity();
		if(camEntity != null)
		{
			matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y
				.rotationDegrees(-camEntity.getYaw()));
			matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X
				.rotationDegrees(camEntity.getPitch()));
		}
		matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y
			.rotationDegrees(180.0F));
		float s = 0.025F * scale;
		matrices.scale(s, -s, s);
		TextRenderer tr = MC.textRenderer;
		VertexConsumerProvider.Immediate vcp =
			net.wurstclient.util.RenderUtils.getVCP();
		float w = tr.getWidth(text) / 2F;
		int bg = (int)(MC.options.getTextBackgroundOpacity(0.25F) * 255) << 24;
		var matrix = matrices.peek().getPositionMatrix();
		tr.draw(text, -w, 0, argb, false, matrix, vcp,
			TextRenderer.TextLayerType.SEE_THROUGH, bg, 0xF000F0);
		vcp.draw();
		matrices.pop();
	}
	
	private WaypointDimension mapDimKeyToWaypointDim(String dimKey)
	{
		return switch(dimKey == null ? "overworld" : dimKey)
		{
			case "overworld" -> WaypointDimension.OVERWORLD;
			case "nether" -> WaypointDimension.NETHER;
			case "end" -> WaypointDimension.END;
			default -> WaypointDimension.OVERWORLD;
		};
	}
}
