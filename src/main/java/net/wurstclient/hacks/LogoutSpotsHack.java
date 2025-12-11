/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
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
		final AABB box;
		final String dimKey;
		final long createdAtMs;
		
		Entry(String n, AABB b, String dim, long createdAtMs)
		{
			name = n;
			box = b;
			dimKey = dim;
			this.createdAtMs = createdAtMs;
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
	private static final int INFINITE_LIFETIME_MARKER = 121;
	private final SliderSetting spotLifetimeMinutes = new SliderSetting(
		"Spot lifetime (minutes)", 60, 1, INFINITE_LIFETIME_MARKER, 1,
		net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER
			.withLabel(INFINITE_LIFETIME_MARKER, "Infinite"));
	
	private final Map<UUID, Entry> spots = new HashMap<>();
	private List<PlayerInfo> lastList = List.of();
	private Map<UUID, Player> lastPlayers = Map.of();
	private final Map<UUID, java.util.UUID> spotToWaypoint = new HashMap<>();
	private String lastServerKey = "unknown";
	
	public LogoutSpotsHack()
	{
		super("LogoutSpots");
		setCategory(Category.RENDER);
		addSetting(sideColor);
		addSetting(lineColor);
		addSetting(scale);
		addSetting(showTracers);
		addSetting(spotLifetimeMinutes);
		addSetting(addAsWaypoint);
	}
	
	@Override
	protected void onEnable()
	{
		clearAllSpots();
		lastServerKey = resolveServerKey();
		snapshot();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		clearAllSpots();
	}
	
	@Override
	public void onUpdate()
	{
		String serverKeyNow = resolveServerKey();
		if(!serverKeyNow.equals(lastServerKey))
		{
			clearAllSpots();
			lastList = List.of();
			lastPlayers = Map.of();
			lastServerKey = serverKeyNow;
		}
		
		if(MC.getConnection() == null || MC.level == null)
			return;
		var nowList = MC.getConnection().getOnlinePlayers();
		if(nowList.size() != lastList.size())
		{
			// find missing
			var lastMap = new HashMap<UUID, PlayerInfo>();
			for(var e : lastList)
				lastMap.put(e.getProfile().id(), e);
			var nowMap = new HashMap<UUID, PlayerInfo>();
			for(var e : nowList)
				nowMap.put(e.getProfile().id(), e);
			for(var id : lastMap.keySet())
				if(!nowMap.containsKey(id))
				{
					Player p = lastPlayers.get(id);
					if(p != null)
					{
						AABB b = p.getBoundingBox();
						long now = System.currentTimeMillis();
						spots.put(id, new Entry(p.getName().getString(), b,
							currentDimKey(), now));
						// Optionally add a temporary waypoint for this logout
						// spot
						if(addAsWaypoint.isChecked()
							&& WURST.getHax().waypointsHack != null
							&& WURST.getHax().waypointsHack.isEnabled())
						{
							Waypoint w =
								new Waypoint(java.util.UUID.randomUUID(), now);
							w.setName("Logout: " + p.getName().getString());
							w.setIcon("skull");
							w.setColor(0xFF88CCFF);
							w.setPos(new net.minecraft.core.BlockPos(
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
		if(MC.level != null)
		{
			for(Player p : MC.level.players())
			{
				spots.remove(p.getUUID());
				removeTemporaryWaypoint(p.getUUID());
			}
		}
		
		int lifetimeMinutes = spotLifetimeMinutes.getValueI();
		if(lifetimeMinutes < INFINITE_LIFETIME_MARKER && !spots.isEmpty())
		{
			long lifetimeMs = lifetimeMinutes * 60_000L;
			long cutoff = System.currentTimeMillis() - lifetimeMs;
			Iterator<Map.Entry<UUID, Entry>> it = spots.entrySet().iterator();
			while(it.hasNext())
			{
				var entry = it.next();
				if(entry.getValue().createdAtMs <= cutoff)
				{
					removeTemporaryWaypoint(entry.getKey());
					it.remove();
				}
			}
		}
	}
	
	private String currentDimKey()
	{
		if(MC.level == null)
			return "overworld";
		return MC.level.dimension().identifier().getPath();
	}
	
	private void snapshot()
	{
		if(MC.getConnection() != null)
			lastList = new java.util.ArrayList<>(
				MC.getConnection().getOnlinePlayers());
		var map = new HashMap<UUID, Player>();
		if(MC.level != null)
		{
			for(Player p : MC.level.players())
				map.put(p.getUUID(), p);
		}
		lastPlayers = map;
	}
	
	private String resolveServerKey()
	{
		ServerData info = MC.getCurrentServer();
		if(info != null)
		{
			if(info.ip != null && !info.ip.isEmpty())
				return info.ip.replace(':', '_');
			if(info.isRealm())
				return "realms_" + (info.name == null ? "" : info.name);
			if(info.name != null && !info.name.isEmpty())
				return "server_" + info.name;
		}
		if(MC.hasSingleplayerServer())
			return "singleplayer";
		return "unknown";
	}
	
	private void clearAllSpots()
	{
		if(!spotToWaypoint.isEmpty() && WURST.getHax().waypointsHack != null)
		{
			for(java.util.UUID wp : new ArrayList<>(spotToWaypoint.values()))
				WURST.getHax().waypointsHack.removeTemporaryWaypoint(wp);
		}
		spotToWaypoint.clear();
		spots.clear();
	}
	
	private void removeTemporaryWaypoint(UUID playerId)
	{
		java.util.UUID wp = spotToWaypoint.remove(playerId);
		if(wp != null && WURST.getHax().waypointsHack != null)
			WURST.getHax().waypointsHack.removeTemporaryWaypoint(wp);
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(spots.isEmpty())
			return;
		String curDim = currentDimKey();
		int sides = sideColor.getColorI(0x40);
		int lines = lineColor.getColorI(0xFF);
		var boxes = new java.util.ArrayList<AABB>();
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
			var ends = boxes.stream().map(AABB::getCenter).toList();
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
	
	private void drawWorldLabel(PoseStack matrices, String text, double x,
		double y, double z, int argb, float scale)
	{
		matrices.pushPose();
		net.minecraft.world.phys.Vec3 cam =
			net.wurstclient.util.RenderUtils.getCameraPos();
		matrices.translate(x - cam.x, y - cam.y, z - cam.z);
		// Face the camera (billboard)
		var camEntity = MC.getCameraEntity();
		if(camEntity != null)
		{
			matrices.mulPose(
				com.mojang.math.Axis.YP.rotationDegrees(-camEntity.getYRot()));
			matrices.mulPose(
				com.mojang.math.Axis.XP.rotationDegrees(camEntity.getXRot()));
		}
		matrices.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
		float s = 0.025F * scale;
		matrices.scale(s, -s, s);
		Font tr = MC.font;
		MultiBufferSource.BufferSource vcp =
			net.wurstclient.util.RenderUtils.getVCP();
		float w = tr.width(text) / 2F;
		int bg = (int)(MC.options.getBackgroundOpacity(0.25F) * 255) << 24;
		var matrix = matrices.last().pose();
		tr.drawInBatch(text, -w, 0, argb, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, bg, 0xF000F0);
		vcp.endBatch();
		matrices.popPose();
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
