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
import net.minecraft.util.math.MathHelper;
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
	
	private final EnumSetting<Target> target =
		new EnumSetting<>("Target", Target.values(), Target.YOU);
	private final ColorSetting otherColor = new ColorSetting("Other color",
		"Color for other players' trails.", new Color(64, 160, 255));
	private final CheckboxSetting keepOthersOnLeave =
		new CheckboxSetting("Keep other trails when out of view", false);
	// If enabled, assign bright random-ish colors to other players.
	private final CheckboxSetting randomBrightColors =
		new CheckboxSetting("Random bright colors for others", false);
	private final SliderSetting maxSections =
		new SliderSetting("Max sections", 1000, 100, MAX_SECTIONS_INFINITE, 50,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER
				.withLabel(MAX_SECTIONS_INFINITE, "Infinite"));
	private final SliderSetting sectionLen =
		new SliderSetting("Section length", 0.5, 0.1, 5.0, 0.1,
			net.wurstclient.settings.SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting lineThickness =
		new SliderSetting("Line thickness", 2.0, 1.0, 10.0, 1.0,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	private final CheckboxSetting paused = new CheckboxSetting("Paused", false);
	
	private final Deque<Vec3d> points = new ArrayDeque<>();
	// map from player uuid to their breadcrumb points
	private final Map<UUID, Deque<Vec3d>> otherPoints = new HashMap<>();
	// previous target selection to detect changes
	private Target prevTarget = null;
	// per-player assigned colors when randomBrightColors is enabled
	private final Map<UUID, Color> playerColors = new HashMap<>();
	
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
		Vec3d here =
			new Vec3d(MC.player.getX(), MC.player.getY(), MC.player.getZ());
		if(points.isEmpty())
		{
			points.add(here);
			return;
		}
		Vec3d last = points.peekLast();
		if(movedEnough(last, here, sectionLen.getValue()))
		{
			points.add(here);
			int limit = maxSections.getValueI();
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
				// assign a color if needed
				if(randomBrightColors.isChecked()
					&& !playerColors.containsKey(id))
				{
					playerColors.put(id,
						generateBrightColor(playerColors.size()));
				}
				Deque<Vec3d> dq =
					otherPoints.computeIfAbsent(id, k -> new ArrayDeque<>());
				Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());
				if(dq.isEmpty())
				{
					dq.add(pos);
					continue;
				}
				Vec3d lastp = dq.peekLast();
				if(movedEnough(lastp, pos, sectionLen.getValue()))
				{
					dq.add(pos);
					int limit = maxSections.getValueI();
					boolean infinite = limit >= MAX_SECTIONS_INFINITE;
					while(!infinite && dq.size() > limit)
						dq.pollFirst();
				}
			}
			// Remove trails for players that left, unless user chose to keep
			if(!keepOthersOnLeave.isChecked())
			{
				otherPoints.keySet().removeIf(uuid -> {
					boolean gone = MC.world.getPlayerByUuid(uuid) == null;
					if(gone)
						playerColors.remove(uuid);
					return gone;
				});
			}
		}
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		double thickness = lineThickness.getValue();
		Target sel = target.getSelected();
		// render your trail only when YOU or BOTH selected
		if((sel == Target.YOU || sel == Target.BOTH) && points.size() >= 2)
		{
			List<Vec3d> list = new ArrayList<>(points);
			int c = RenderUtils.toIntColor(new float[]{color.getColorF()[0],
				color.getColorF()[1], color.getColorF()[2]}, 0.8F);
			RenderUtils.drawCurvedLine(matrixStack, list, c, false, thickness);
		}
		
		// render other players' trails
		if(sel == Target.OTHERS || sel == Target.BOTH)
		{
			for(var entry : otherPoints.entrySet())
			{
				Deque<Vec3d> dq = entry.getValue();
				if(dq.size() < 2)
					continue;
				UUID id = entry.getKey();
				int oc;
				if(randomBrightColors.isChecked())
				{
					Color col =
						playerColors.getOrDefault(id, otherColor.getColor());
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
				List<Vec3d> l = new ArrayList<>(dq);
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
	
	private boolean movedEnough(Vec3d a, Vec3d b, double min)
	{
		return MathHelper.abs((float)(a.x - b.x)) >= min
			|| MathHelper.abs((float)(a.y - b.y)) >= min
			|| MathHelper.abs((float)(a.z - b.z)) >= min;
	}
}
