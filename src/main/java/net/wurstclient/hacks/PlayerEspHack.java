/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.EspStyleSetting.EspStyle;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.settings.filters.FilterInvisibleSetting;
import net.wurstclient.settings.filters.FilterSleepingSetting;
import net.wurstclient.settings.ColorSetting;
import java.awt.Color;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.FakePlayerEntity;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;

@SearchTags({"player esp", "PlayerTracers", "player tracers"})
public final class PlayerEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style =
		new EspStyleSetting(EspStyle.LINES_AND_BOXES);
	
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting(
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox of each player.\n"
			+ "\u00a7lFancy\u00a7r mode shows slightly larger boxes that look better.");
	
	private final EntityFilterList entityFilters = new EntityFilterList(
		new FilterSleepingSetting("Won't show sleeping players.", false),
		new FilterInvisibleSetting("Won't show invisible players.", false));
	
	private final ArrayList<PlayerEntity> players = new ArrayList<>();
	private final CheckboxSetting randomBrightColors = new CheckboxSetting(
		"Unique colors for players",
		"When enabled, assigns each player a bright color from a shared\n"
			+ "palette and forces it into the shared color registry.\n"
			+ "PlayerESP takes ownership of these colors (overrides Breadcrumbs).",
		false);
	private final CheckboxSetting filledBoxes = new CheckboxSetting(
		"Filled boxes",
		"When enabled, renders solid filled boxes instead of outlined boxes.",
		false);
	private final CheckboxSetting useStaticPlayerColor = new CheckboxSetting(
		"Use static player color",
		"When enabled, uses the selected static color for all players and\n"
			+ "forces it into the shared color registry. PlayerESP owns the\n"
			+ "assignment while this is enabled (will override Breadcrumbs).",
		false);
	private final ColorSetting playerColor = new ColorSetting("Player color",
		"Static color used when 'Use static player color' is enabled.",
		new Color(255, 196, 64));
	private final net.wurstclient.settings.SliderSetting filledAlpha =
		new net.wurstclient.settings.SliderSetting("Filled box alpha", 35, 0,
			100, 1,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	
	public PlayerEspHack()
	{
		super("PlayerESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(randomBrightColors);
		addSetting(filledBoxes);
		addSetting(filledAlpha);
		addSetting(useStaticPlayerColor);
		addSetting(playerColor);
		addSetting(boxSize);
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		players.clear();
		
		Stream<AbstractClientPlayerEntity> stream = MC.world.getPlayers()
			.parallelStream().filter(e -> !e.isRemoved() && e.getHealth() > 0)
			.filter(e -> e != MC.player)
			.filter(e -> !(e instanceof FakePlayerEntity))
			.filter(e -> Math.abs(e.getY() - MC.player.getY()) <= 1e6);
		
		stream = entityFilters.applyTo(stream);
		
		players.addAll(stream.collect(Collectors.toList()));
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		if(style.hasBoxes())
		{
			double extraSize = boxSize.getExtraSize() / 2;
			
			ArrayList<ColoredBox> boxes = new ArrayList<>(players.size());
			for(PlayerEntity e : players)
			{
				Box box = EntityUtils.getLerpedBox(e, partialTicks)
					.offset(0, extraSize, 0).expand(extraSize);
				int col = getColor(e);
				boxes.add(new ColoredBox(box, col));
			}
			
			if(filledBoxes.isChecked())
			{
				// Draw semi-transparent filled boxes, then draw opaque
				// outlines on top
				ArrayList<ColoredBox> solid = new ArrayList<>(boxes.size());
				ArrayList<ColoredBox> outline = new ArrayList<>(boxes.size());
				for(ColoredBox cb : boxes)
				{
					int base = cb.color();
					int solidAlpha =
						(int)((filledAlpha.getValue() / 100f) * 255) << 24;
					int solidColor = (base & 0x00FFFFFF) | solidAlpha;
					solid.add(new ColoredBox(cb.box(), solidColor));
					int outlineColor = (base & 0x00FFFFFF) | (0xFF << 24);
					outline.add(new ColoredBox(cb.box(), outlineColor));
				}
				RenderUtils.drawSolidBoxes(matrixStack, solid, false);
				RenderUtils.drawOutlinedBoxes(matrixStack, outline, false);
			}else
			{
				RenderUtils.drawOutlinedBoxes(matrixStack, boxes, false);
			}
		}
		
		if(style.hasLines())
		{
			ArrayList<ColoredPoint> ends = new ArrayList<>(players.size());
			for(PlayerEntity e : players)
			{
				Vec3d point =
					EntityUtils.getLerpedBox(e, partialTicks).getCenter();
				ends.add(new ColoredPoint(point, getColor(e)));
			}
			
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, false);
		}
	}
	
	private int getColor(PlayerEntity e)
	{
		if(WURST.getFriends().contains(e.getName().getString()))
			return 0x800000FF;
		// If PlayerESP enforces a static color, force it into the registry so
		// PlayerESP always overrides Breadcrumbs. Return that color.
		if(useStaticPlayerColor.isChecked())
		{
			java.awt.Color pc = playerColor.getColor();
			net.wurstclient.util.PlayerColorRegistry.forceAssign(e.getUuid(),
				pc, "PlayerESP");
			return RenderUtils.toIntColor(new float[]{pc.getRed() / 255f,
				pc.getGreen() / 255f, pc.getBlue() / 255f}, 0.85F);
		}
		
		// If PlayerESP requests random bright colors, generate one
		// deterministically and force-assign it so PlayerESP overrides
		// Breadcrumbs.
		if(randomBrightColors.isChecked())
		{
			int idx = Math.abs(e.getUuid().hashCode());
			java.awt.Color gen = net.wurstclient.util.PlayerColorRegistry
				.generateBrightColor(idx);
			net.wurstclient.util.PlayerColorRegistry.forceAssign(e.getUuid(),
				gen, "PlayerESP");
			return RenderUtils.toIntColor(new float[]{gen.getRed() / 255f,
				gen.getGreen() / 255f, gen.getBlue() / 255f}, 0.9F);
		}
		
		// If neither static nor random are enabled, remove any PlayerESP-owned
		// registry entries so other hacks' colors can show. Then fall back to
		// dynamic distance-based coloring.
		if(!useStaticPlayerColor.isChecked() && !randomBrightColors.isChecked())
		{
			// remove all registry assignments owned by PlayerESP
			net.wurstclient.util.PlayerColorRegistry.removeByOwner("PlayerESP");
			// Continue to distance-based dynamic coloring below
		}
		
		// Only consult the shared registry when PlayerESP has explicitly opted
		// into owning colors. This prevents Breadcrumbs (or other hacks)
		// changing PlayerESP colors unexpectedly.
		if(useStaticPlayerColor.isChecked() || randomBrightColors.isChecked())
		{
			java.awt.Color reg2 =
				net.wurstclient.util.PlayerColorRegistry.get(e.getUuid());
			if(reg2 != null)
			{
				return RenderUtils
					.toIntColor(
						new float[]{reg2.getRed() / 255f,
							reg2.getGreen() / 255f, reg2.getBlue() / 255f},
						0.9F);
			}
		}
		
		// Otherwise fall back to the dynamic distance-based coloring (default).
		
		float f = MC.player.distanceTo(e) / 20F;
		float r = MathHelper.clamp(2 - f, 0, 1);
		float g = MathHelper.clamp(f, 0, 1);
		float[] rgb = {r, g, 0};
		return RenderUtils.toIntColor(rgb, 0.5F);
	}
}
