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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
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
	private static final long RECENT_BREAK_WINDOW_MS = 8000L;
	private static final int RECENT_BREAK_LIMIT = 64;
	private static final int MIN_DROP_AGE_TICKS = 60;
	private static final double SELF_ACTIVITY_RADIUS_SQ = 144.0;
	private static final int SETTINGS_REBUILD_CONFIRM_TICKS = 2;
	
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
		"Color used for persistent mined-here trails.",
		new Color(255, 196, 64, 208));
	private final ColorSetting pointColor = new ColorSetting("Point color",
		"Color used for matched evidence markers.",
		new Color(255, 220, 120, 96));
	private final ColorSetting tracerColor = new ColorSetting("Tracer color",
		"Color used for tracers to detected mining evidence.",
		new Color(255, 64, 64, 208));
	private final SliderSetting lineWidth = new SliderSetting("Line width", 2.0,
		0.5, 8.0, 0.1, ValueDisplay.DECIMAL.withSuffix(" px"));
	private final SliderSetting tracerWidth = new SliderSetting("Tracer width",
		1.5, 0.5, 8.0, 0.1, ValueDisplay.DECIMAL.withSuffix(" px"));
	private final SliderSetting maxSegmentLength =
		new SliderSetting("Join distance",
			"Maximum gap between drops before a matched line splits.", 6, 2, 24,
			1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting minPoints = new SliderSetting(
		"Min drops in line",
		"Minimum number of drops required before a line counts as mining evidence.",
		3, 2, 12, 1, ValueDisplay.INTEGER.withSuffix(" drops"));
	private final SliderSetting maxDetectY = new SliderSetting("Max detect Y",
		"Only drops at or below this Y level can count as mining evidence.", 64,
		-64, 320, 1, ValueDisplay.INTEGER.withSuffix(" Y"));
	private final SliderSetting scanDistance = new SliderSetting(
		"Scan distance",
		"Maximum distance from you where dropped items are scanned for mining evidence.",
		96, 16, 256, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting renderDistance = new SliderSetting(
		"Render distance",
		"Maximum distance from you where detected mining evidence is rendered.",
		96, 16, 512, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting linePointSpacing =
		new SliderSetting("Line point spacing",
			"Higher values reduce rendered line detail and improve FPS.", 1.5,
			0.25, 8.0, 0.25, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting maxLinePoints =
		new SliderSetting("Max line points",
			"Caps how many points each visible trail can render.", 48, 8, 256,
			1, ValueDisplay.INTEGER.withSuffix(" points"));
	private final SliderSetting pointSize = new SliderSetting("Marker size",
		0.4, 0.1, 2.0, 0.05, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting evidenceLifetimeMinutes = new SliderSetting(
		"Evidence lifetime (minutes)", 30, 1, INFINITE_LIFETIME_MARKER, 1,
		ValueDisplay.INTEGER.withLabel(INFINITE_LIFETIME_MARKER, "Infinite"));
	private final CheckboxSetting showMarkers =
		new CheckboxSetting("Show markers", true);
	private final CheckboxSetting showLines =
		new CheckboxSetting("Show lines", true);
	private final CheckboxSetting showTracers =
		new CheckboxSetting("Show tracers", false);
	
	private final ArrayDeque<EvidenceTrail> evidence = new ArrayDeque<>();
	private final ArrayDeque<RecentBreak> recentBreaks = new ArrayDeque<>();
	private String lastServerKey = "unknown";
	private String lastDropSignature = "";
	private String lastDetectionSettings = "";
	private boolean settingsRebuildPending;
	private String pendingRebuildSignature = "";
	private int pendingRebuildStableTicks;
	
	public MiningEvidenceHack()
	{
		super("MiningEvidence");
		setCategory(Category.RENDER);
		addSetting(lineColor);
		addSetting(pointColor);
		addSetting(tracerColor);
		addSetting(lineWidth);
		addSetting(tracerWidth);
		addSetting(maxSegmentLength);
		addSetting(minPoints);
		addSetting(maxDetectY);
		addSetting(scanDistance);
		addSetting(renderDistance);
		addSetting(linePointSpacing);
		addSetting(maxLinePoints);
		addSetting(pointSize);
		addSetting(evidenceLifetimeMinutes);
		addSetting(showMarkers);
		addSetting(showLines);
		addSetting(showTracers);
	}
	
	@Override
	protected void onEnable()
	{
		clearAllEvidence();
		lastServerKey = resolveServerKey();
		lastDetectionSettings = getDetectionSettingsSignature();
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
		if(MC.level == null || MC.player == null)
		{
			clearAllEvidence();
			return;
		}
		
		String serverKeyNow = resolveServerKey();
		if(!serverKeyNow.equals(lastServerKey))
		{
			clearAllEvidence();
			lastServerKey = serverKeyNow;
			lastDetectionSettings = getDetectionSettingsSignature();
		}
		
		String detectionSettingsNow = getDetectionSettingsSignature();
		if(!detectionSettingsNow.equals(lastDetectionSettings))
		{
			lastDetectionSettings = detectionSettingsNow;
			settingsRebuildPending = true;
			pendingRebuildSignature = "";
			pendingRebuildStableTicks = 0;
			lastDropSignature = "";
		}
		
		long now = System.currentTimeMillis();
		pruneExpiredEvidence(now);
		pruneRecentBreaks(now);
		
		ArrayList<Vec3> undergroundDrops = collectUndergroundDrops();
		String dimKey = currentDimKey();
		
		if(settingsRebuildPending)
		{
			ArrayList<EvidenceTrail> rebuiltEvidence =
				buildEvidenceTrails(undergroundDrops, dimKey, now);
			String rebuiltSignature = signatureForTrails(rebuiltEvidence);
			
			if(rebuiltSignature.equals(pendingRebuildSignature))
				pendingRebuildStableTicks++;
			else
			{
				pendingRebuildSignature = rebuiltSignature;
				pendingRebuildStableTicks = 1;
			}
			
			if(pendingRebuildStableTicks >= SETTINGS_REBUILD_CONFIRM_TICKS)
			{
				replaceLocalEvidence(dimKey, MC.player.position(),
					getLocalReplaceDistanceSq(), rebuiltEvidence);
				settingsRebuildPending = false;
				pendingRebuildSignature = "";
				pendingRebuildStableTicks = 0;
				lastDropSignature =
					undergroundDrops.size() < minPoints.getValueI() ? ""
						: signatureFor(undergroundDrops);
			}
			
			return;
		}
		
		if(undergroundDrops.size() < minPoints.getValueI())
		{
			lastDropSignature = "";
			return;
		}
		
		String dropSignature = signatureFor(undergroundDrops);
		if(dropSignature.equals(lastDropSignature))
			return;
		
		lastDropSignature = dropSignature;
		
		for(List<Vec3> segment : detectEvidenceSegments(undergroundDrops))
		{
			String signature = signatureFor(segment);
			if(hasTrailSignature(signature))
				continue;
			
			evidence.addLast(new EvidenceTrail(List.copyOf(segment), signature,
				dimKey, now));
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
		if(evidence.isEmpty() || MC.level == null || MC.player == null)
			return;
		
		String dimKey = currentDimKey();
		int lineArgb = lineColor.getColorI(0xFF);
		int pointFill = pointColor.getColorI(0x50);
		int pointOutline = lineColor.getColorI(0xD0);
		int tracerArgb = tracerColor.getColorI(0xFF);
		double halfSize = pointSize.getValue() / 2.0;
		Vec3 cameraPos = MC.player.getEyePosition(partialTicks);
		Vec3 forward = MC.player.getViewVector(partialTicks);
		Vec3 tracerStart = cameraPos.add(forward.scale(0.25));
		double maxRenderDistanceSq =
			renderDistance.getValue() * renderDistance.getValue();
		
		ArrayList<AABB> boxes = new ArrayList<>();
		for(EvidenceTrail trail : evidence)
		{
			if(!dimKey.equals(trail.dimKey))
				continue;
			
			if(!isTrailWithinDistance(trail, cameraPos, maxRenderDistanceSq))
				continue;
			
			ArrayList<Vec3> renderPoints = buildRenderableTrailPoints(trail,
				cameraPos, maxRenderDistanceSq);
			
			if(showLines.isChecked() && renderPoints.size() >= 2)
				RenderUtils.drawCurvedLine(matrices, renderPoints, lineArgb,
					false, lineWidth.getValue());
			
			if(showTracers.isChecked())
			{
				Vec3 tracerTarget = getNearestTrailEnd(trail, cameraPos);
				if(tracerTarget != null)
				{
					ArrayList<Vec3> tracerPoints = new ArrayList<>();
					tracerPoints.add(tracerStart);
					tracerPoints.add(tracerStart.lerp(tracerTarget, 0.5));
					tracerPoints.add(tracerTarget);
					RenderUtils.drawCurvedLine(matrices, tracerPoints,
						tracerArgb, false, tracerWidth.getValue());
				}
			}
			
			if(showMarkers.isChecked())
				for(Vec3 pos : trail.points)
					if(pos.distanceToSqr(cameraPos) <= maxRenderDistanceSq)
						boxes.add(new AABB(pos.x - halfSize, pos.y - halfSize,
							pos.z - halfSize, pos.x + halfSize,
							pos.y + halfSize, pos.z + halfSize));
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
		Vec3 playerPos = MC.player.position();
		double maxScanDistanceSq =
			scanDistance.getValue() * scanDistance.getValue();
		
		for(var entity : MC.level.entitiesForRendering())
		{
			if(!(entity instanceof ItemEntity itemEntity))
				continue;
			if(itemEntity.getItem().isEmpty())
				continue;
			if(itemEntity.tickCount < MIN_DROP_AGE_TICKS)
				continue;
			
			Vec3 pos = itemEntity.position().add(0, 0.15, 0);
			if(pos.distanceToSqr(playerPos) > maxScanDistanceSq)
				continue;
			if(shouldIgnoreAsOwnActivity(pos))
				continue;
			if(isUnderground(pos))
				result.add(pos);
		}
		
		return result;
	}
	
	private ArrayList<EvidenceTrail> buildEvidenceTrails(List<Vec3> points,
		String dimKey, long now)
	{
		ArrayList<EvidenceTrail> rebuilt = new ArrayList<>();
		if(points.size() < minPoints.getValueI())
			return rebuilt;
		
		HashSet<String> added = new HashSet<>();
		for(List<Vec3> segment : detectEvidenceSegments(points))
		{
			String signature = signatureFor(segment);
			if(!added.add(signature))
				continue;
			
			rebuilt.add(new EvidenceTrail(List.copyOf(segment), signature,
				dimKey, now));
		}
		
		return rebuilt;
	}
	
	private String signatureForTrails(List<EvidenceTrail> trails)
	{
		if(trails.isEmpty())
			return currentDimKey() + "|empty";
		
		ArrayList<String> signatures = new ArrayList<>();
		for(EvidenceTrail trail : trails)
			signatures.add(trail.signature);
		
		signatures.sort(String::compareTo);
		StringBuilder builder = new StringBuilder();
		for(String signature : signatures)
			builder.append(signature).append('|');
		return builder.toString();
	}
	
	private void replaceLocalEvidence(String dimKey, Vec3 origin,
		double maxDistanceSq, List<EvidenceTrail> replacement)
	{
		Iterator<EvidenceTrail> it = evidence.iterator();
		while(it.hasNext())
		{
			EvidenceTrail trail = it.next();
			if(!dimKey.equals(trail.dimKey))
				continue;
			
			if(isTrailWithinDistance(trail, origin, maxDistanceSq))
				it.remove();
		}
		
		for(EvidenceTrail trail : replacement)
			if(!hasTrailSignature(trail.signature))
				evidence.addLast(trail);
	}
	
	private double getLocalReplaceDistanceSq()
	{
		double replaceDistance =
			scanDistance.getValue() + maxSegmentLength.getValue();
		return replaceDistance * replaceDistance;
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
					if(canConnect(current, point, maxGap))
					{
						visited.add(key);
						queue.addLast(point);
					}
				}
			}
			
			addClusterMatch(cluster, matches, required, maxGap);
		}
		
		matches.removeIf(segment -> segment.size() < required);
		return matches;
	}
	
	private void addClusterMatch(List<Vec3> points, List<List<Vec3>> matches,
		int required, double maxGap)
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
		
		ArrayList<ArrayList<Integer>> adjacency =
			buildAdjacency(deduped, maxGap);
		int start = findTrailStart(deduped, adjacency);
		if(start < 0)
			return;
		
		int[] parentFromStart = buildTreeParents(start, adjacency);
		int endA = findFarthestIndex(start, deduped, parentFromStart);
		if(endA < 0)
			return;
		
		int[] parentFromEndA = buildTreeParents(endA, adjacency);
		int endB = findFarthestIndex(endA, deduped, parentFromEndA);
		if(endB < 0)
			return;
		
		ArrayList<Vec3> path = buildPath(endA, endB, parentFromEndA, deduped);
		if(path.size() < required)
			return;
		
		matches.add(path);
	}
	
	private ArrayList<ArrayList<Integer>> buildAdjacency(List<Vec3> points,
		double maxGap)
	{
		ArrayList<ArrayList<Integer>> adjacency = new ArrayList<>();
		for(int i = 0; i < points.size(); i++)
			adjacency.add(new ArrayList<>());
		
		for(int i = 0; i < points.size(); i++)
			for(int j = i + 1; j < points.size(); j++)
				if(canConnect(points.get(i), points.get(j), maxGap))
				{
					adjacency.get(i).add(j);
					adjacency.get(j).add(i);
				}
			
		for(int i = 0; i < adjacency.size(); i++)
		{
			final int index = i;
			adjacency.get(i).sort((a, b) -> {
				Vec3 current = points.get(index);
				Vec3 pointA = points.get(a);
				Vec3 pointB = points.get(b);
				int distanceCompare = Double.compare(current.distanceTo(pointA),
					current.distanceTo(pointB));
				if(distanceCompare != 0)
					return distanceCompare;
				return comparePoints(pointA, pointB);
			});
		}
		
		return adjacency;
	}
	
	private int findTrailStart(List<Vec3> points,
		List<ArrayList<Integer>> adjacency)
	{
		int best = -1;
		for(int i = 0; i < points.size(); i++)
		{
			if(adjacency.get(i).isEmpty())
				continue;
			
			if(adjacency.get(i).size() > 1)
				continue;
			
			if(best < 0 || comparePoints(points.get(i), points.get(best)) < 0)
				best = i;
		}
		
		if(best >= 0)
			return best;
		
		for(int i = 0; i < points.size(); i++)
		{
			if(adjacency.get(i).isEmpty())
				continue;
			
			if(best < 0 || comparePoints(points.get(i), points.get(best)) < 0)
				best = i;
		}
		
		return best;
	}
	
	private int comparePoints(Vec3 a, Vec3 b)
	{
		int x = Double.compare(a.x, b.x);
		if(x != 0)
			return x;
		
		int y = Double.compare(a.y, b.y);
		if(y != 0)
			return y;
		
		return Double.compare(a.z, b.z);
	}
	
	private int[] buildTreeParents(int start,
		List<ArrayList<Integer>> adjacency)
	{
		int[] parent = new int[adjacency.size()];
		Arrays.fill(parent, Integer.MIN_VALUE);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		parent[start] = -1;
		queue.addLast(start);
		while(!queue.isEmpty())
		{
			int current = queue.removeFirst();
			for(int neighbor : adjacency.get(current))
			{
				if(parent[neighbor] != Integer.MIN_VALUE)
					continue;
				
				parent[neighbor] = current;
				queue.addLast(neighbor);
			}
		}
		
		return parent;
	}
	
	private int findFarthestIndex(int start, List<Vec3> points, int[] parent)
	{
		int farthest = start;
		double farthestDistanceSq = -1.0;
		
		for(int i = 0; i < parent.length; i++)
		{
			if(parent[i] == Integer.MIN_VALUE)
				continue;
			
			double distanceSq = points.get(start).distanceToSqr(points.get(i));
			if(distanceSq > farthestDistanceSq)
			{
				farthestDistanceSq = distanceSq;
				farthest = i;
			}
		}
		
		return farthest;
	}
	
	private ArrayList<Vec3> buildPath(int start, int end, int[] parent,
		List<Vec3> points)
	{
		ArrayDeque<Vec3> stack = new ArrayDeque<>();
		int current = end;
		while(current >= 0)
		{
			stack.addFirst(points.get(current));
			if(current == start)
				break;
			current = parent[current];
		}
		
		return new ArrayList<>(stack);
	}
	
	private boolean canConnect(Vec3 a, Vec3 b, double maxGap)
	{
		if(MC.level == null)
			return false;
		
		if(a.distanceTo(b) > maxGap)
			return false;
		
		BlockPos start = BlockPos.containing(a);
		BlockPos end = BlockPos.containing(b);
		if(start.equals(end))
			return true;
		
		int maxSteps = Math.max(1, Mth.ceil(maxGap));
		int minX = Math.min(start.getX(), end.getX()) - 1;
		int maxX = Math.max(start.getX(), end.getX()) + 1;
		int minY = Math.min(start.getY(), end.getY()) - 1;
		int maxY = Math.max(start.getY(), end.getY()) + 1;
		int minZ = Math.min(start.getZ(), end.getZ()) - 1;
		int maxZ = Math.max(start.getZ(), end.getZ()) + 1;
		
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		ArrayDeque<Integer> depths = new ArrayDeque<>();
		HashSet<BlockPos> visited = new HashSet<>();
		queue.addLast(start);
		depths.addLast(0);
		visited.add(start);
		while(!queue.isEmpty())
		{
			BlockPos current = queue.removeFirst();
			int depth = depths.removeFirst();
			if(current.equals(end))
				return true;
			
			if(depth >= maxSteps)
				continue;
			
			for(Direction direction : Direction.values())
			{
				BlockPos next = current.relative(direction);
				if(!withinBounds(next, minX, maxX, minY, maxY, minZ, maxZ))
					continue;
				if(!next.equals(end) && !isPassable(next))
					continue;
				if(!visited.add(next))
					continue;
				
				queue.addLast(next);
				depths.addLast(depth + 1);
			}
		}
		
		return false;
	}
	
	private boolean withinBounds(BlockPos pos, int minX, int maxX, int minY,
		int maxY, int minZ, int maxZ)
	{
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY
			&& pos.getY() <= maxY && pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}
	
	private boolean isPassable(BlockPos pos)
	{
		if(MC.level == null)
			return false;
		
		var state = MC.level.getBlockState(pos);
		if(state.isAir())
			return true;
		
		return state.getCollisionShape(MC.level, pos).isEmpty();
	}
	
	private boolean isUnderground(Vec3 pos)
	{
		return pos.y <= maxDetectY.getValue();
	}
	
	private boolean shouldIgnoreAsOwnActivity(Vec3 pos)
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
		ArrayList<String> keys = new ArrayList<>();
		HashSet<String> seen = new HashSet<>();
		for(Vec3 point : points)
		{
			String key = quantizedPoint(point);
			if(seen.add(key))
				keys.add(key);
		}
		
		keys.sort(String::compareTo);
		StringBuilder builder = new StringBuilder(currentDimKey());
		for(String key : keys)
			builder.append('|').append(key);
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
		settingsRebuildPending = false;
		pendingRebuildSignature = "";
		pendingRebuildStableTicks = 0;
	}
	
	private void pruneRecentBreaks(long now)
	{
		long cutoff = now - RECENT_BREAK_WINDOW_MS;
		while(!recentBreaks.isEmpty()
			&& recentBreaks.peekFirst().createdAtMs <= cutoff)
			recentBreaks.removeFirst();
	}
	
	private boolean isTrailWithinDistance(EvidenceTrail trail, Vec3 origin,
		double maxDistanceSq)
	{
		for(Vec3 point : trail.points)
			if(point.distanceToSqr(origin) <= maxDistanceSq)
				return true;
			
		return false;
	}
	
	private ArrayList<Vec3> buildRenderableTrailPoints(EvidenceTrail trail,
		Vec3 origin, double maxDistanceSq)
	{
		ArrayList<Vec3> visible = new ArrayList<>();
		
		for(Vec3 point : trail.points)
			if(point.distanceToSqr(origin) <= maxDistanceSq)
				visible.add(point);
			
		if(visible.size() <= 2)
			return visible;
		
		ArrayList<Vec3> simplified =
			simplifyTrailPoints(visible, linePointSpacing.getValue());
		int maxPoints = maxLinePoints.getValueI();
		if(simplified.size() <= maxPoints)
			return simplified;
		
		return downsampleTrailPoints(simplified, maxPoints);
	}
	
	private ArrayList<Vec3> simplifyTrailPoints(List<Vec3> points,
		double minSpacing)
	{
		ArrayList<Vec3> simplified = new ArrayList<>();
		if(points.isEmpty())
			return simplified;
		
		double minSpacingSq = minSpacing * minSpacing;
		Vec3 lastAdded = points.get(0);
		simplified.add(lastAdded);
		
		for(int i = 1; i < points.size() - 1; i++)
		{
			Vec3 point = points.get(i);
			if(point.distanceToSqr(lastAdded) < minSpacingSq)
				continue;
			
			simplified.add(point);
			lastAdded = point;
		}
		
		Vec3 end = points.get(points.size() - 1);
		if(!samePoint(lastAdded, end))
			simplified.add(end);
		
		return simplified;
	}
	
	private ArrayList<Vec3> downsampleTrailPoints(List<Vec3> points,
		int maxPoints)
	{
		ArrayList<Vec3> result = new ArrayList<>();
		if(points.size() <= maxPoints)
			return new ArrayList<>(points);
		
		if(maxPoints <= 2)
		{
			result.add(points.get(0));
			result.add(points.get(points.size() - 1));
			return result;
		}
		
		for(int i = 0; i < maxPoints; i++)
		{
			int index =
				(int)Math.round(i * (points.size() - 1.0) / (maxPoints - 1.0));
			Vec3 point = points.get(index);
			if(result.isEmpty()
				|| !samePoint(result.get(result.size() - 1), point))
				result.add(point);
		}
		
		if(result.size() == 1 && points.size() > 1)
			result.add(points.get(points.size() - 1));
		
		return result;
	}
	
	private boolean samePoint(Vec3 a, Vec3 b)
	{
		return Double.compare(a.x, b.x) == 0 && Double.compare(a.y, b.y) == 0
			&& Double.compare(a.z, b.z) == 0;
	}
	
	private Vec3 getNearestTrailEnd(EvidenceTrail trail, Vec3 origin)
	{
		if(trail.points.isEmpty())
			return null;
		
		Vec3 first = trail.points.get(0);
		Vec3 last = trail.points.get(trail.points.size() - 1);
		double firstDistanceSq = first.distanceToSqr(origin);
		double lastDistanceSq = last.distanceToSqr(origin);
		return firstDistanceSq <= lastDistanceSq ? first : last;
	}
	
	private String getDetectionSettingsSignature()
	{
		return maxDetectY.getValueI() + "|" + scanDistance.getValueI() + "|"
			+ maxSegmentLength.getValue() + "|" + minPoints.getValueI();
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
