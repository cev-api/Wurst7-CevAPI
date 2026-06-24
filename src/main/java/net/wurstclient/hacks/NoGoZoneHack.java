/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.util.RenderUtils;

@SearchTags({"no go zone", "nogozone", "zone", "no go", "restrict area",
	"block area"})
@net.wurstclient.hack.DontSaveState
public final class NoGoZoneHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	private static final List<NoGoZone> ZONES = new ArrayList<>();
	private static final Set<Integer> PLAYER_INSIDE_ZONE_IDS = new HashSet<>();
	private static int nextZoneId = 1;
	private Vec3 lastSafePos;
	private String lastServerKey = "";
	
	private final ColorSetting zoneColor = new ColorSetting("Zone color",
		"Color of the no-go zone ground highlight", new Color(0xFF0000));
	
	public NoGoZoneHack()
	{
		super("NoGoZone");
		// No category = unlisted, accessible via search/navigator
		addSetting(zoneColor);
	}
	
	@Override
	protected void onEnable()
	{
		loadZonesForCurrentServer();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		if(MC.player != null && !isInsideArmedZone(MC.player.position()))
			lastSafePos = MC.player.position();
	}
	
	@Override
	protected void onDisable()
	{
		saveZonesForCurrentServer();
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null)
			return;
		
		// Detect server change and swap zone files
		String currentKey = resolveServerKey();
		if(!currentKey.equals(lastServerKey))
		{
			saveZonesToFile(lastServerKey);
			lastServerKey = currentKey;
			clearAllZones();
			loadZonesFromFile(currentKey);
		}
		
		Vec3 playerPos = MC.player.position();
		boolean insideAnyArmedZone = false;
		
		for(NoGoZone zone : ZONES)
		{
			boolean playerInside = zone.contains(playerPos);
			
			if(playerInside && !PLAYER_INSIDE_ZONE_IDS.contains(zone.id))
			{
				insideAnyArmedZone = true;
				keepPlayerOut(zone);
				return;
			}
			
			if(!playerInside && PLAYER_INSIDE_ZONE_IDS.contains(zone.id))
			{
				// Player left the zone - blocked from re-entering
				PLAYER_INSIDE_ZONE_IDS.remove(zone.id);
			}
		}
		
		if(!insideAnyArmedZone)
			lastSafePos = playerPos;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.player == null)
			return;
		
		// Strong alpha for visibility
		int fillColor = zoneColor.getColorI(0x40);
		int outlineColor = zoneColor.getColorI(0xFF);
		
		for(NoGoZone zone : ZONES)
		{
			AABB box = zone.getBoundingBox();
			if(box == null)
				continue;
			
			if(zone.contains(MC.player.position()))
				renderFloor(matrixStack, zone, fillColor, outlineColor);
			else
				renderWalls(matrixStack, zone, fillColor, outlineColor);
		}
	}
	
	private void renderFloor(PoseStack matrixStack, NoGoZone zone,
		int fillColor, int outlineColor)
	{
		AABB box = zone.getBoundingBox();
		double groundY = zone.center.getY() - 0.02;
		AABB floor = new AABB(box.minX, groundY, box.minZ, box.maxX,
			groundY + 0.06, box.maxZ);
		
		RenderUtils.drawSolidBox(matrixStack, floor, fillColor, false);
		RenderUtils.drawOutlinedBox(matrixStack, floor.expandTowards(0, 1, 0),
			outlineColor, false);
	}
	
	private void renderWalls(PoseStack matrixStack, NoGoZone zone,
		int fillColor, int outlineColor)
	{
		AABB box = zone.getBoundingBox();
		double minY = 0;
		double maxY = 200;
		double minX = box.minX;
		double maxX = box.maxX;
		double minZ = box.minZ;
		double maxZ = box.maxZ;
		double thickness = 0.15;
		
		List<AABB> walls = List.of(
			new AABB(minX - thickness, minY, minZ - thickness, maxX + thickness,
				maxY, minZ + thickness),
			new AABB(minX - thickness, minY, maxZ - thickness, maxX + thickness,
				maxY, maxZ + thickness),
			new AABB(minX - thickness, minY, minZ - thickness, minX + thickness,
				maxY, maxZ + thickness),
			new AABB(maxX - thickness, minY, minZ - thickness, maxX + thickness,
				maxY, maxZ + thickness));
		
		RenderUtils.drawSolidBoxes(matrixStack, walls, fillColor, false);
		RenderUtils.drawOutlinedBoxes(matrixStack, walls, outlineColor, false);
	}
	
	private void keepPlayerOut(NoGoZone zone)
	{
		if(lastSafePos != null && !zone.contains(lastSafePos))
		{
			MC.player.setPos(lastSafePos.x, lastSafePos.y, lastSafePos.z);
			stopPlayerMotion();
			return;
		}
		
		pushPlayerOut(zone);
	}
	
	private void pushPlayerOut(NoGoZone zone)
	{
		double playerX = MC.player.getX();
		double playerZ = MC.player.getZ();
		
		int zoneMinX = zone.minChunk.getMinBlockX();
		int zoneMaxX = zone.maxChunk.getMaxBlockX() + 1;
		int zoneMinZ = zone.minChunk.getMinBlockZ();
		int zoneMaxZ = zone.maxChunk.getMaxBlockZ() + 1;
		
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
		
		double pushAmount = 1.5;
		
		if(minDist == distMinX)
			targetX = zoneMinX - pushAmount;
		else if(minDist == distMaxX)
			targetX = zoneMaxX + pushAmount;
		else if(minDist == distMinZ)
			targetZ = zoneMinZ - pushAmount;
		else
			targetZ = zoneMaxZ + pushAmount;
		
		MC.player.setPos(targetX, MC.player.getY(), targetZ);
		lastSafePos = new Vec3(targetX, MC.player.getY(), targetZ);
		stopPlayerMotion();
	}
	
	private void stopPlayerMotion()
	{
		MC.player.setDeltaMovement(0, 0, 0);
		MC.player.xxa = 0;
		MC.player.zza = 0;
	}
	
	private boolean isInsideArmedZone(Vec3 pos)
	{
		for(NoGoZone zone : ZONES)
			if(zone.contains(pos) && !PLAYER_INSIDE_ZONE_IDS.contains(zone.id))
				return true;
			
		return false;
	}
	
	public static List<NoGoZone> getZones()
	{
		return ZONES;
	}
	
	public static int addZone(BlockPos center, int chunkRadius)
	{
		int id = nextZoneId++;
		NoGoZone zone = new NoGoZone(center, chunkRadius, id);
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
	
	// ---- Per-server persistence ----
	
	private String resolveServerKey()
	{
		net.minecraft.client.multiplayer.ServerData info =
			MC.getCurrentServer();
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
	
	private Path getZonesFolder()
	{
		return WURST.getWurstFolder().resolve("no_go_zone");
	}
	
	private Path getZonesFile(String serverKey)
	{
		return getZonesFolder().resolve(serverKey + ".json");
	}
	
	private void loadZonesForCurrentServer()
	{
		lastServerKey = resolveServerKey();
		clearAllZones();
		loadZonesFromFile(lastServerKey);
	}
	
	private void saveZonesForCurrentServer()
	{
		saveZonesToFile(lastServerKey);
	}
	
	private void loadZonesFromFile(String serverKey)
	{
		if(serverKey == null || serverKey.isEmpty())
			return;
		
		Path file = getZonesFile(serverKey);
		if(!Files.isRegularFile(file))
			return;
		
		try
		{
			String json = Files.readString(file, StandardCharsets.UTF_8);
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			nextZoneId = root.get("nextZoneId").getAsInt();
			JsonArray zonesArray = root.getAsJsonArray("zones");
			for(int i = 0; i < zonesArray.size(); i++)
			{
				JsonObject zoneObj = zonesArray.get(i).getAsJsonObject();
				int id = zoneObj.get("id").getAsInt();
				int centerX = zoneObj.get("centerX").getAsInt();
				int centerY = zoneObj.get("centerY").getAsInt();
				int centerZ = zoneObj.get("centerZ").getAsInt();
				int chunkRadius = zoneObj.get("chunkRadius").getAsInt();
				NoGoZone zone = new NoGoZone(
					new BlockPos(centerX, centerY, centerZ), chunkRadius, id);
				ZONES.add(zone);
			}
		}catch(IOException | RuntimeException e)
		{
			// Corrupt file or missing — start fresh
			clearAllZones();
		}
	}
	
	private void saveZonesToFile(String serverKey)
	{
		if(serverKey == null || serverKey.isEmpty()
			|| serverKey.equals("unknown"))
			return;
		
		Path folder = getZonesFolder();
		try
		{
			Files.createDirectories(folder);
		}catch(IOException e)
		{
			return;
		}
		
		JsonObject root = new JsonObject();
		root.addProperty("nextZoneId", nextZoneId);
		JsonArray zonesArray = new JsonArray();
		for(NoGoZone zone : ZONES)
		{
			JsonObject zoneObj = new JsonObject();
			zoneObj.addProperty("id", zone.id);
			zoneObj.addProperty("centerX", zone.center.getX());
			zoneObj.addProperty("centerY", zone.center.getY());
			zoneObj.addProperty("centerZ", zone.center.getZ());
			zoneObj.addProperty("chunkRadius", zone.chunkRadius);
			zonesArray.add(zoneObj);
		}
		root.add("zones", zonesArray);
		
		try
		{
			Files.writeString(getZonesFile(serverKey), GSON.toJson(root),
				StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			// Silently fail — zones will be lost but game continues
		}
	}
	
	// ---- Zone data class ----
	
	public static class NoGoZone
	{
		public final int id;
		public final BlockPos center;
		public final int chunkRadius;
		public final ChunkPos minChunk;
		public final ChunkPos maxChunk;
		
		public NoGoZone(BlockPos center, int chunkRadius, int id)
		{
			this.id = id;
			this.center = center;
			this.chunkRadius = chunkRadius;
			ChunkPos centerChunk = new ChunkPos(center);
			minChunk = new ChunkPos(centerChunk.x - chunkRadius,
				centerChunk.z - chunkRadius);
			maxChunk = new ChunkPos(centerChunk.x + chunkRadius,
				centerChunk.z + chunkRadius);
		}
		
		public boolean containsChunk(ChunkPos chunk)
		{
			return chunk.x >= minChunk.x && chunk.x <= maxChunk.x
				&& chunk.z >= minChunk.z && chunk.z <= maxChunk.z;
		}
		
		public boolean contains(Vec3 pos)
		{
			return pos.x >= minChunk.getMinBlockX()
				&& pos.x <= maxChunk.getMaxBlockX() + 1
				&& pos.z >= minChunk.getMinBlockZ()
				&& pos.z <= maxChunk.getMaxBlockZ() + 1;
		}
		
		public int getBlockRadius()
		{
			return chunkRadius * 16;
		}
		
		public AABB getBoundingBox()
		{
			int y = center.getY();
			return new AABB(minChunk.getMinBlockX(), y, minChunk.getMinBlockZ(),
				maxChunk.getMaxBlockX() + 1, y + 1,
				maxChunk.getMaxBlockZ() + 1);
		}
	}
}
