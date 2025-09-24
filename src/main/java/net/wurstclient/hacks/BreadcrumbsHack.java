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
import java.util.List;

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
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.RenderUtils;

@SearchTags({"breadcrumbs", "trail"})
public final class BreadcrumbsHack extends Hack
	implements UpdateListener, RenderListener
{
	private final ColorSetting color =
		new ColorSetting("Color", "Trail color.", new Color(255, 64, 64));
	private final SliderSetting maxSections =
		new SliderSetting("Max sections", 1000, 100, 5000, 50,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	private final SliderSetting sectionLen =
		new SliderSetting("Section length", 0.5, 0.1, 5.0, 0.1,
			net.wurstclient.settings.SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting lineThickness =
		new SliderSetting("Line thickness", 2.0, 1.0, 10.0, 1.0,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	private final CheckboxSetting paused = new CheckboxSetting("Paused", false);
	
	private final Deque<Vec3d> points = new ArrayDeque<>();
	
	public BreadcrumbsHack()
	{
		super("Breadcrumbs");
		setCategory(Category.RENDER);
		addSetting(color);
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
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		points.clear();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null)
			return;
		// Do not add new points while paused
		if(paused.isChecked())
			return;
		Vec3d here = MC.player.getPos();
		if(points.isEmpty())
		{
			points.add(here);
			return;
		}
		Vec3d last = points.peekLast();
		if(movedEnough(last, here, sectionLen.getValue()))
		{
			points.add(here);
			while(points.size() > maxSections.getValueI())
				points.pollFirst();
		}
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		if(points.size() < 2)
			return;
		List<Vec3d> list = new ArrayList<>(points);
		int c = RenderUtils.toIntColor(new float[]{color.getColorF()[0],
			color.getColorF()[1], color.getColorF()[2]}, 0.8F);
		double thickness = lineThickness.getValue();
		RenderUtils.drawCurvedLine(matrixStack, list, c, false, thickness);
	}
	
	private boolean movedEnough(Vec3d a, Vec3d b, double min)
	{
		return MathHelper.abs((float)(a.x - b.x)) >= min
			|| MathHelper.abs((float)(a.y - b.y)) >= min
			|| MathHelper.abs((float)(a.z - b.z)) >= min;
	}
}
