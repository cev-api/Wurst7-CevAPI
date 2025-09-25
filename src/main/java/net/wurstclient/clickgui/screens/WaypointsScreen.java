/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.ArrayList;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.wurstclient.waypoints.Waypoint;
import net.wurstclient.waypoints.WaypointsManager;
import net.wurstclient.waypoints.WaypointDimension;

public final class WaypointsScreen extends Screen
{
	private final Screen prev;
	private final WaypointsManager manager;
	private java.util.List<Waypoint> cachedList;
	private int listStartY;
	
	public WaypointsScreen(Screen prev, WaypointsManager manager)
	{
		super(Text.literal("Waypoints"));
		this.prev = prev;
		this.manager = manager;
	}
	
	@Override
	protected void init()
	{
		int y = 32;
		int x = this.width / 2 - 150;
		
		addDrawableChild(
			ButtonWidget.builder(Text.literal("Create waypoint"), b -> {
				Waypoint w = new Waypoint(java.util.UUID.randomUUID(),
					System.currentTimeMillis());
				w.setName("New Waypoint");
				if(client.player != null)
					w.setPos(BlockPos.ofFloored(client.player.getPos()));
				else
					w.setPos(BlockPos.ORIGIN);
				w.setDimension(currentDim());
				w.setMaxVisible(5000);
				w.setLines(false); // default new waypoints without lines
				client
					.setScreen(new WaypointEditScreen(this, manager, w, true));
			}).dimensions(x, y, 300, 20).build());
		y += 28;
		// Cache list for consistent rendering and color boxes
		cachedList = new ArrayList<>(manager.all());
		listStartY = y;
		for(int i = 0; i < cachedList.size(); i++)
		{
			Waypoint w = cachedList.get(i);
			int rowY = y + i * 24;
			addDrawableChild(
				ButtonWidget.builder(Text.literal(w.getName()), b -> {
					client.setScreen(
						new WaypointEditScreen(this, manager, w, false));
				}).dimensions(x, rowY, 140, 20).build());
			addDrawableChild(ButtonWidget
				.builder(Text.literal(w.isVisible() ? "Hide" : "Show"), b -> {
					w.setVisible(!w.isVisible());
					manager.addOrUpdate(w);
					saveNow();
					// Refresh in-place without stacking a new screen
					client.setScreen(this);
				}).dimensions(x + 145, rowY, 55, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Delete"), b -> {
				manager.remove(w);
				saveNow();
				// Refresh in-place without stacking a new screen
				client.setScreen(this);
			}).dimensions(x + 205, rowY, 55, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Copy"), b -> {
				String s = w.getPos().getX() + ", " + w.getPos().getY() + ", "
					+ w.getPos().getZ();
				client.keyboard.setClipboard(s);
			}).dimensions(x + 265, rowY, 35, 20).build());
		}
		
		addDrawableChild(ButtonWidget
			.builder(Text.literal("Back"), b -> client.setScreen(prev))
			.dimensions(x, this.height - 28, 300, 20).build());
	}
	
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta)
	{
		// No blur - just a translucent background
		context.fill(0, 0, this.width, this.height, 0x88000000);
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(client.textRenderer, "Waypoints",
			this.width / 2, 12, 0xFFFFFFFF);
		
		// Draw small color boxes for each saved waypoint next to the name
		// Use live list so color changes reflect immediately after returning
		int x = this.width / 2 - 150;
		int y = listStartY;
		var liveList = new ArrayList<>(manager.all());
		for(int i = 0; i < liveList.size(); i++)
		{
			Waypoint w = liveList.get(i);
			int rowY = y + i * 24;
			int boxX = x - 20;
			int boxY = rowY + 2;
			int color = w.getColor();
			// border
			context.fill(boxX - 1, boxY - 1, boxX + 17, boxY + 17, 0xFF333333);
			// fill
			context.fill(boxX, boxY, boxX + 16, boxY + 16, color);
		}
	}
	
	private WaypointDimension currentDim()
	{
		if(client.world == null)
			return WaypointDimension.OVERWORLD;
		String key = client.world.getRegistryKey().getValue().getPath();
		switch(key)
		{
			case "the_nether":
			return WaypointDimension.NETHER;
			case "the_end":
			return WaypointDimension.END;
			default:
			return WaypointDimension.OVERWORLD;
		}
	}
	
	private String resolveWorldId()
	{
		net.minecraft.client.network.ServerInfo s =
			client.getCurrentServerEntry();
		if(s != null && s.address != null && !s.address.isEmpty())
			return s.address.replace(':', '_');
		return "singleplayer";
	}
	
	void saveNow()
	{
		manager.save(resolveWorldId());
	}
}
