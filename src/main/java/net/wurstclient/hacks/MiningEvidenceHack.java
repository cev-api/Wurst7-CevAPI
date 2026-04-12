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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.BlockBreakingProgressListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;

@SearchTags({"mining evidence", "mined here", "item trail", "mine trail",
	"ore trail"})
public final class MiningEvidenceHack extends Hack
	implements UpdateListener, RenderListener, BlockBreakingProgressListener
{
	private static final int INFINITE_LIFETIME_MARKER = 121;
	private static final long RESCAN_INTERVAL_MS = 350L;
	private static final long RECENT_BREAK_WINDOW_MS = 8000L;
	private static final int RECENT_BREAK_LIMIT = 64;
	private static final int MIN_DROP_AGE_TICKS = 60;
	private static final double SELF_ACTIVITY_RADIUS_SQ = 144.0;
	
	private static final class EvidenceTrail
	{
		private final List<Vec3> points;
		private final String signature;
		private final String dimKey;
		private final long createdAtMs;
		
		private EvidenceTrail(List<Vec3> points, String signature,
			String dimKey, long createdAtMs)
		{
			this.points = points;
			this.signature = signature;
			this.dimKey = dimKey;
			this.createdAtMs = createdAtMs;
		}
	}
	
	private record RecentBreak(BlockPos pos, long createdAtMs)
	{}
	
	private final ColorSetting lineColor = new ColorSetting("Line color",
		"Color used for persistent underground mined-here trails.",
		new Color(255, 196, 64, 208));
	private final ColorSetting pointColor = new ColorSetting("Point color",
		"Color used for matched underground evidence markers.",
		new Color(255, 220, 120, 96));
	private final SliderSetting lineWidth = new SliderSetting("Line width", 2.0,
		0.5, 8.0, 0.1, ValueDisplay.DECIMAL.withSuffix(" px"));
	private final SliderSetting maxSegmentLength = new SliderSetting(
		"Join distance",
		"Maximum gap between underground drops before a matched line splits.",
		6, 2, 24, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting minPoints = new SliderSetting(
		"Min drops in line",
		"Minimum number of underground drops required before a line counts as mining evidence.",
		3, 2, 12, 1, ValueDisplay.INTEGER.withSuffix(" drops"));
	private final SliderSetting undergroundClearance = new SliderSetting(
		"Underground depth",
		"How far below the local surface a drop must be to count as underground evidence.",
		3, 1, 24, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting pointSize = new SliderSetting("Marker size",
		0.4, 0.1, 2.0, 0.05, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting evidenceLifetimeMinutes = new SliderSetting(
		"Evidence lifetime (minutes)", 30, 1, INFINITE_LIFETIME_MARKER, 1,
		ValueDisplay.INTEGER.withLabel(INFINITE_LIFETIME_MARKER, "Infinite"));
	private final CheckboxSetting showMarkers =
		new CheckboxSetting("Show markers", true);
	
	private final ArrayDeque<EvidenceTrail> evidence = new ArrayDeque<>();
	private final ArrayDeque<RecentBreak> recentBreaks = new ArrayDeque<>();
	private String lastServerKey = "unknown";
	private long lastScanAtMs;
	private String lastDropSignature = "";
	
	public MiningEvidenceHack()
	{
		super("MiningEvidence");
		setCategory(Category.RENDER);
		addSetting(lineColor);
		addSetting(pointColor);
		addSetting(lineWidth);
		addSetting(maxSegmentLength);
		addSetting(minPoints);
		addSetting(undergroundClearance);
		addSetting(pointSize);
		addSetting(evidenceLifetimeMinutes);
		addSetting(showMarkers);
	}
	
	@Override
	protected void onEnable()
	{
		clearAllEvidence();
		lastServerKey = resolveServerKey();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(BlockBreakingProgressListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(BlockBreakingProgressListener.class, this);
		clearAllEvidence();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null)
		{
			clearAllEvidence();
			return;
		}
		
		String serverKeyNow = resolveServerKey();
		if(!serverKeyNow.equals(lastServerKey))
		{
			clearAllEvidence();
			lastServerKey = serverKeyNow;
		}
		
		long now = System.currentTimeMillis();
		pruneExpiredEvidence(now);
		pruneRecentBreaks(now);
		if(now - lastScanAtMs < RESCAN_INTERVAL_MS)
			return;
		
		ArrayList<Vec3> undergroundDrops = collectUndergroundDrops();
		if(undergroundDrops.size() < minPoints.getValueI())
		{
			lastDropSignature = "";
			lastScanAtMs = now;
			return;
		}
		
		String dropSignature = signatureFor(undergroundDrops);
		if(dropSignature.equals(lastDropSignature))
		{
			lastScanAtMs = now;
			return;
		}
		lastDropSignature = dropSignature;
		lastScanAtMs = now;
		
		for(List<Vec3> segment : detectEvidenceSegments(undergroundDrops))
		{
			String signature = signatureFor(segment);
			if(hasTrailSignature(signature))
				continue;
			
			evidence.addLast(new EvidenceTrail(List.copyOf(segment), signature,
				currentDimKey(), now));
		}
	}
	
	@Override
	public void onBlockBreakingProgress(BlockBreakingProgressEvent event)
	{
		if(event == null || event.getBlockPos() == null)
			return;
		
		recentBreaks.addLast(
			new RecentBreak(event.getBlockPos(), System.currentTimeMillis()));
		while(recentBreaks.size() > RECENT_BREAK_LIMIT)
			recentBreaks.removeFirst();
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(evidence.isEmpty() || MC.level == null)
			return;
		
		String dimKey = currentDimKey();
		int lineArgb = lineColor.getColorI(0xFF);
		int pointFill = pointColor.getColorI(0x50);
		int pointOutline = lineColor.getColorI(0xD0);
		double halfSize = pointSize.getValue() / 2.0;
		
		ArrayList<AABB> boxes = new ArrayList<>();
		for(EvidenceTrail trail : evidence)
		{
			if(!dimKey.equals(trail.dimKey))
				continue;
			
			if(trail.points.size() >= 2)
				RenderUtils.drawCurvedLine(matrices, trail.points, lineArgb,
					false, lineWidth.getValue());
			
			if(showMarkers.isChecked())
				for(Vec3 pos : trail.points)
					boxes.add(new AABB(pos.x - halfSize, pos.y - halfSize,
						pos.z - halfSize, pos.x + halfSize, pos.y + halfSize,
						pos.z + halfSize));
		}
		
		if(!boxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrices, boxes, pointFill, false);
			RenderUtils.drawOutlinedBoxes(matrices, boxes, pointOutline, false);
		}
	}
	
	private ArrayList<Vec3> collectUndergroundDrops()
	{
		ArrayList<Vec3> result = new ArrayList<>();
		for(var entity : MC.level.entitiesForRendering())
		{
			if(!(entity instanceof ItemEntity itemEntity))
				continue;
			if(itemEntity.getItem().isEmpty())
				continue;
			if(itemEntity.tickCount < MIN_DROP_AGE_TICKS)
				continue;
			
			Vec3 pos = itemEntity.position().add(0, 0.15, 0);
			if(shouldIgnoreAsOwnActivity(itemEntity, pos))
				continue;
			if(isUnderground(pos))
				result.add(pos);
		}
		
		return result;
	}
	
	private List<List<Vec3>> detectEvidenceSegments(List<Vec3> points)
	{
		int required = minPoints.getValueI();
		double maxGap = maxSegmentLength.getValue();
		ArrayList<Vec3> candidates = new ArrayList<>(points);
		ArrayList<List<Vec3>> matches = new ArrayList<>();
		HashSet<String> visited = new HashSet<>();
		for(Vec3 seed : candidates)
		{
			String seedKey = quantizedPoint(seed);
			if(!visited.add(seedKey))
				continue;
			
			ArrayList<Vec3> cluster = new ArrayList<>();
			ArrayDeque<Vec3> queue = new ArrayDeque<>();
			queue.add(seed);
			while(!queue.isEmpty())
			{
				Vec3 current = queue.removeFirst();
				cluster.add(current);
				for(Vec3 point : candidates)
				{
					String key = quantizedPoint(point);
					if(visited.contains(key))
						continue;
					if(point.distanceTo(current) <= maxGap)
					{
						visited.add(key);
						queue.addLast(point);
					}
				}
			}
			
			addClusterMatch(cluster, matches, required);
		}
		
		matches.removeIf(segment -> segment.size() < required);
		return matches;
	}
	
	private void addClusterMatch(List<Vec3> points, List<List<Vec3>> matches,
		int required)
	{
		if(points.size() < required)
			return;
		
		ArrayList<Vec3> deduped = new ArrayList<>();
		HashSet<String> local = new HashSet<>();
		for(Vec3 point : points)
		{
			String key = quantizedPoint(point);
			if(local.add(key))
				deduped.add(point);
		}
		
		if(deduped.size() < required)
			return;
		
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		for(Vec3 point : deduped)
		{
			minX = Math.min(minX, point.x);
			minY = Math.min(minY, point.y);
			minZ = Math.min(minZ, point.z);
			maxX = Math.max(maxX, point.x);
			maxY = Math.max(maxY, point.y);
			maxZ = Math.max(maxZ, point.z);
		}
		
		double spanX = maxX - minX;
		double spanY = maxY - minY;
		double spanZ = maxZ - minZ;
		double longest = Math.max(spanX, Math.max(spanY, spanZ));
		double shortest = Math.min(spanX, Math.min(spanY, spanZ));
		if(longest < 3.0)
			return;
		if(shortest > longest * 0.75)
			return;
		
		deduped
			.sort((a, b) -> Double.compare(a.x + a.y + a.z, b.x + b.y + b.z));
		matches.add(deduped);
	}
	
	private boolean isUnderground(Vec3 pos)
	{
		if(MC.level == null)
			return false;
		
		int x = Mth.floor(pos.x);
		int z = Mth.floor(pos.z);
		int surfaceY =
			MC.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		return pos.y <= surfaceY - undergroundClearance.getValue();
	}
	
	private boolean shouldIgnoreAsOwnActivity(ItemEntity itemEntity, Vec3 pos)
	{
		if(MC.player != null && pos
			.distanceToSqr(MC.player.position()) <= SELF_ACTIVITY_RADIUS_SQ)
			return true;
		
		for(RecentBreak recentBreak : recentBreaks)
			if(Vec3.atCenterOf(recentBreak.pos).distanceToSqr(pos) <= 25.0)
				return true;
			
		return false;
	}
	
	private boolean hasTrailSignature(String signature)
	{
		for(EvidenceTrail trail : evidence)
			if(trail.signature.equals(signature))
				return true;
		return false;
	}
	
	private String signatureFor(List<Vec3> points)
	{
		StringBuilder builder = new StringBuilder(currentDimKey());
		for(Vec3 point : points)
			builder.append('|').append(quantizedPoint(point));
		return builder.toString();
	}
	
	private String quantizedPoint(Vec3 point)
	{
		int x = (int)Math.round(point.x * 2.0);
		int y = (int)Math.round(point.y * 2.0);
		int z = (int)Math.round(point.z * 2.0);
		return String.format(Locale.ROOT, "%d,%d,%d", x, y, z);
	}
	
	private void pruneExpiredEvidence(long now)
	{
		int lifetimeMinutes = evidenceLifetimeMinutes.getValueI();
		if(lifetimeMinutes >= INFINITE_LIFETIME_MARKER)
			return;
		
		long cutoff = now - lifetimeMinutes * 60_000L;
		Iterator<EvidenceTrail> it = evidence.iterator();
		while(it.hasNext())
			if(it.next().createdAtMs <= cutoff)
				it.remove();
	}
	
	private void clearAllEvidence()
	{
		evidence.clear();
		recentBreaks.clear();
		lastDropSignature = "";
		lastScanAtMs = 0L;
	}
	
	private void pruneRecentBreaks(long now)
	{
		long cutoff = now - RECENT_BREAK_WINDOW_MS;
		while(!recentBreaks.isEmpty()
			&& recentBreaks.peekFirst().createdAtMs <= cutoff)
			recentBreaks.removeFirst();
	}
	
	private String currentDimKey()
	{
		if(MC.level == null)
			return "overworld";
		return MC.level.dimension().identifier().getPath();
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
}
