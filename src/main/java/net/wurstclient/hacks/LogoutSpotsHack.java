/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
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

@SearchTags({"logout spots", "logouts"})
public final class LogoutSpotsHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final class Entry
	{
		final UUID uuid;
		final String name;
		final Box box;
		final float health;
		
		Entry(UUID u, String n, Box b, float h)
		{
			uuid = u;
			name = n;
			box = b;
			health = h;
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
	
	private final Map<UUID, Entry> spots = new HashMap<>();
	private List<PlayerListEntry> lastList = List.of();
	private Map<UUID, PlayerEntity> lastPlayers = Map.of();
	
	public LogoutSpotsHack()
	{
		super("LogoutSpots");
		setCategory(Category.RENDER);
		addSetting(sideColor);
		addSetting(lineColor);
		addSetting(scale);
		addSetting(showTracers);
	}
	
	@Override
	protected void onEnable()
	{
		spots.clear();
		snapshot();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		spots.clear();
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
				lastMap.put(e.getProfile().getId(), e);
			var nowMap = new HashMap<UUID, PlayerListEntry>();
			for(var e : nowList)
				nowMap.put(e.getProfile().getId(), e);
			for(var id : lastMap.keySet())
				if(!nowMap.containsKey(id))
				{
					PlayerEntity p = lastPlayers.get(id);
					if(p != null)
					{
						Box b = p.getBoundingBox();
						float h = p.getHealth();
						spots.put(id,
							new Entry(id, p.getName().getString(), b, h));
					}
				}
			snapshot();
		}
		// cull rejoined players
		if(MC.world != null)
		{
			for(PlayerEntity p : MC.world.getPlayers())
				spots.remove(p.getUuid());
		}
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
		int sides = sideColor.getColorI(0x40);
		int lines = lineColor.getColorI(0xFF);
		var boxes = new java.util.ArrayList<Box>(spots.size());
		for(var e : spots.values())
			boxes.add(e.box);
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
		matrices.multiply(MC.getEntityRenderDispatcher().getRotation());
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
		matrices.pop();
	}
}
