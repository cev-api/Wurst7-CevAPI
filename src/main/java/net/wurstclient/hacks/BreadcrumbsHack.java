/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.RenderUtils;

@SearchTags({"breadcrumbs", "trail"})
public final class BreadcrumbsHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final int MAX_SECTIONS_INFINITE = 10050;
	
	private final ColorSetting color =
		new ColorSetting("Color", "Trail color.", new Color(255, 64, 64));
	
	private enum Target
	{
		YOU,
		OTHERS,
		BOTH
	}
	
	private boolean movedEnough(Vec3d a, Vec3d b, double minDist)
	{
		double dx = a.x - b.x;
		double dy = a.y - b.y;
		double dz = a.z - b.z;
		double sq = dx * dx + dy * dy + dz * dz;
		return sq >= minDist * minDist;
	}
	
	private final EnumSetting<Target> target =
		new EnumSetting<>("Target", Target.values(), Target.YOU);
	private final ColorSetting otherColor = new ColorSetting("Other color",
		"Color for other players' trails. Note: PlayerESP overrides Breadcrumbs'\n"
			+ "colors; if PlayerESP is using a static or unique color for a player,\n"
			+ "Breadcrumbs will show that color instead.",
		new Color(64, 160, 255));
	private final CheckboxSetting keepOthersOnLeave =
		new CheckboxSetting("Keep trails when out of view/logout", false);
	// If enabled, assign bright random-ish colors to other players.
	private final CheckboxSetting randomBrightColors = new CheckboxSetting(
		"Unique colors for others",
		"Assign bright deterministic colors per-player so other hacks\n"
			+ "(for example PlayerESP) can share the same color. If you turn\n"
			+ "this off, Breadcrumbs will remove colors it owns from the\n"
			+ "shared registry so other features may take over.",
		false);
	// Build a piecewise sections map: 0..100 (step 1), 200..1000 (step 100),
	// 1200..5000 (step 200), 6000..9000 (step 1000), then 9999 and Infinite.
	private static final int[] SECTIONS_MAP = buildSectionsMap();
	
	private static int[] buildSectionsMap()
	{
		java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
		for(int i = 0; i <= 100; i++)
			list.add(i);
		for(int v = 200; v <= 1000; v += 100)
			list.add(v);
		for(int v = 1200; v <= 5000; v += 200)
			list.add(v);
		for(int v = 6000; v <= 9000; v += 1000)
			list.add(v);
		list.add(9999);
		list.add(MAX_SECTIONS_INFINITE);
		int[] arr = new int[list.size()];
		for(int i = 0; i < list.size(); i++)
			arr[i] = list.get(i);
		return arr;
	}
	
	private static int findIndexFor(int value)
	{
		for(int i = 0; i < SECTIONS_MAP.length; i++)
			if(SECTIONS_MAP[i] == value)
				return i;
		return 0;
	}
	
	// Slider indexes into SECTIONS_MAP. Display the mapped value (actual
	// section count) instead of the raw index so users see values like
	// 2500 or "Infinite".
	private final SliderSetting maxSections = new SliderSetting("Max sections",
		findIndexFor(1000), 0, SECTIONS_MAP.length - 1, 1,
		new net.wurstclient.settings.SliderSetting.ValueDisplay()
		{
			@Override
			public String getValueString(double v)
			{
				int idx = (int)v;
				int mapped = computeMaxSections(idx);
				return mapped >= MAX_SECTIONS_INFINITE ? "Infinite"
					: Integer.toString(mapped);
			}
		});
	private final SliderSetting sectionLen =
		new SliderSetting("Section length", 0.5, 0.1, 5.0, 0.1,
			net.wurstclient.settings.SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting lineThickness =
		new SliderSetting("Line thickness", 2.0, 1.0, 10.0, 1.0,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	private final CheckboxSetting paused = new CheckboxSetting("Paused", false);
	
	private final Deque<Point> points = new ArrayDeque<>();
	// map from player uuid to their breadcrumb points
	private final Map<UUID, Deque<Point>> otherPoints = new HashMap<>();
	// previous target selection to detect changes
	private Target prevTarget = null;
	// previous random toggle so we can clean up registry on toggle off
	private boolean prevRandom = false;
	
	// per-player colors are managed via PlayerColorRegistry
	private static final class Point
	{
		final Vec3d pos;
		final DimensionType dim;
		
		Point(Vec3d pos, DimensionType dim)
		{
			this.pos = pos;
			this.dim = dim;
		}
	}
	
	public BreadcrumbsHack()
	{
		super("Breadcrumbs");
		setCategory(Category.RENDER);
		addSetting(color);
		addSetting(target);
		addSetting(otherColor);
		addSetting(randomBrightColors);
		addSetting(keepOthersOnLeave);
		addSetting(maxSections);
		addSetting(sectionLen);
		addSetting(lineThickness);
		addSetting(paused);
	}
	
	@Override
	public String getRenderName()
	{
		return paused.isChecked() ? getName() + " [Paused]" : getName();
	}
	
	@Override
	protected void onEnable()
	{
		points.clear();
		otherPoints.clear();
		prevTarget = target.getSelected();
		prevRandom = randomBrightColors.isChecked();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		points.clear();
		otherPoints.clear();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null)
			return;
		// handle target changes: clear trails that no longer apply
		Target sel = target.getSelected();
		if(prevTarget == null)
			prevTarget = sel;
		if(sel != prevTarget)
		{
			// if we switched to only YOU, clear other players' trails
			if(sel == Target.YOU)
				otherPoints.clear();
			// if we switched to only OTHERS, clear your own trail
			if(sel == Target.OTHERS)
				points.clear();
			// if we switched to BOTH, clear otherPoints to avoid resurrecting
			// old trails
			if(sel == Target.BOTH)
				otherPoints.clear();
			prevTarget = sel;
		}
		// Do not add new points while paused
		if(paused.isChecked())
			return;
		Vec3d herePos =
			new Vec3d(MC.player.getX(), MC.player.getY(), MC.player.getZ());
		DimensionType hereDim = MC.world.getDimension();
		if(points.isEmpty())
		{
			points.add(new Point(herePos, hereDim));
			return;
		}
		Vec3d last = points.peekLast().pos;
		if(movedEnough(last, herePos, sectionLen.getValue()))
		{
			points.add(new Point(herePos, hereDim));
			int limit = computeMaxSections(maxSections.getValueI());
			boolean infinite = limit >= MAX_SECTIONS_INFINITE;
			while(!infinite && points.size() > limit)
				points.pollFirst();
		}
		
		// Track other players if enabled
		if(sel == Target.OTHERS || sel == Target.BOTH)
		{
			for(var p : MC.world.getPlayers())
			{
				if(p == MC.player)
					continue;
				UUID id = p.getUuid();
				// assign a color if needed via central registry
				if(randomBrightColors.isChecked())
				{
					java.awt.Color assigned =
						net.wurstclient.util.PlayerColorRegistry.get(id);
					if(assigned == null)
					{
						// assign deterministically so all features agree on
						// the color for this player
						net.wurstclient.util.PlayerColorRegistry
							.assignDeterministic(id, "Breadcrumbs");
					}
				}
				Deque<Point> dq =
					otherPoints.computeIfAbsent(id, k -> new ArrayDeque<>());
				Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());
				DimensionType pdim = MC.world.getDimension();
				if(dq.isEmpty())
				{
					dq.add(new Point(pos, pdim));
					continue;
				}
				Vec3d lastp = dq.peekLast().pos;
				if(movedEnough(lastp, pos, sectionLen.getValue()))
				{
					dq.add(new Point(pos, pdim));
					int limit = computeMaxSections(maxSections.getValueI());
					boolean infinite = limit >= MAX_SECTIONS_INFINITE;
					while(!infinite && dq.size() > limit)
						dq.pollFirst();
				}
			}
			// Remove trails for players that left, unless user chose to keep
			if(!keepOthersOnLeave.isChecked())
			{
				otherPoints.keySet()
					.removeIf(uuid -> MC.world.getPlayerByUuid(uuid) == null);
				// If not keeping trails we also remove registry entries
				otherPoints.keySet().forEach(uuid -> {
					if(MC.world.getPlayerByUuid(uuid) == null)
						net.wurstclient.util.PlayerColorRegistry.remove(uuid);
				});
			}
		}
		
		// If the random toggle was turned off, remove Breadcrumbs-owned
		// registry
		// entries so other hacks can take over colors.
		boolean curRandom = randomBrightColors.isChecked();
		if(prevRandom && !curRandom)
		{
			net.wurstclient.util.PlayerColorRegistry
				.removeByOwner("Breadcrumbs");
		}
		prevRandom = curRandom;
	}
	
	/**
	 * Map the slider value to an actual max sections value. Values up to
	 * 1000 are linear; values above 1000 scale up exponentially to allow
	 * very large values without losing slider precision.
	 */
	private int computeMaxSections(int sliderIndex)
	{
		if(sliderIndex < 0)
			return SECTIONS_MAP[0];
		if(sliderIndex >= SECTIONS_MAP.length)
			return SECTIONS_MAP[SECTIONS_MAP.length - 1];
		return SECTIONS_MAP[sliderIndex];
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		double thickness = lineThickness.getValue();
		Target sel = target.getSelected();
		// render your trail only when YOU or BOTH selected
		if((sel == Target.YOU || sel == Target.BOTH))
		{
			DimensionType curDim = MC.world.getDimension();
			List<Vec3d> list = new ArrayList<>();
			for(Point p : points)
			{
				if(p.dim == curDim)
					list.add(p.pos);
			}
			if(list.size() >= 2)
			{
				int c =
					RenderUtils
						.toIntColor(
							new float[]{color.getColorF()[0],
								color.getColorF()[1], color.getColorF()[2]},
							0.8F);
				RenderUtils.drawCurvedLine(matrixStack, list, c, false,
					thickness);
			}
		}
		
		// render other players' trails
		if(sel == Target.OTHERS || sel == Target.BOTH)
		{
			DimensionType curDim = MC.world.getDimension();
			for(var entry : otherPoints.entrySet())
			{
				Deque<Point> dq = entry.getValue();
				UUID id = entry.getKey();
				
				List<Vec3d> l = new ArrayList<>();
				for(Point p : dq)
					if(p.dim == curDim)
						l.add(p.pos);
					
				if(l.size() < 2)
					continue;
				
				int oc;
				if(randomBrightColors.isChecked())
				{
					java.awt.Color col =
						net.wurstclient.util.PlayerColorRegistry.get(id);
					if(col == null)
						col = otherColor.getColor();
					oc = RenderUtils
						.toIntColor(
							new float[]{col.getRed() / 255f,
								col.getGreen() / 255f, col.getBlue() / 255f},
							0.9F);
				}else
				{
					oc = RenderUtils.toIntColor(new float[]{
						otherColor.getColorF()[0], otherColor.getColorF()[1],
						otherColor.getColorF()[2]}, 0.8F);
				}
				
				RenderUtils.drawCurvedLine(matrixStack, l, oc, false,
					thickness);
			}
		}
	}
	
	/**
	 * Generate a bright color. We start from a palette of bright base colors
	 * and if there are more players than base colors we generate darker
	 * or lighter shades by cycling through a brightness multiplier.
	 */
	private Color generateBrightColor(int index)
	{
		Color[] base = new Color[]{new Color(255, 64, 64), // red
			new Color(64, 255, 64), // green
			new Color(64, 64, 255), // blue
			new Color(255, 196, 64), // orange
			new Color(196, 64, 255), // magenta
			new Color(64, 255, 196), // aqua
			new Color(255, 64, 196), // pink
			new Color(196, 255, 64), // lime
			new Color(64, 196, 255) // sky
		};
		int baseCount = base.length;
		int b = index % baseCount;
		int round = index / baseCount;
		float factor = 1.0f - Math.min(0.5f, round * 0.15f); // reduce
																// brightness
																// slightly per
																// round
		Color bc = base[b];
		int r = Math.min(255, Math.max(0, (int)(bc.getRed() * factor)));
		int g = Math.min(255, Math.max(0, (int)(bc.getGreen() * factor)));
		int bl = Math.min(255, Math.max(0, (int)(bc.getBlue() * factor)));
		return new Color(r, g, bl);
	}
	
}
