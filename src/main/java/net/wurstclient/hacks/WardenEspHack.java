/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.AngerLevel;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;

/**
 * Simple Warden ESP: color-coded shapes and minimal indicators.
 * Uses client-side Warden state (poses, synced anger) for reliability.
 */
public final class WardenEspHack extends Hack
	implements UpdateListener, RenderListener
{
	// map entity id -> last known highest anger (for trend)
	private final Map<Integer, Integer> lastAnger = new HashMap<>();
	private final Map<Integer, Long> attackExpiry = new HashMap<>();
	
	// detected wardens for rendering
	private final List<LivingEntity> wardens = new ArrayList<>();
	
	// debugging: last found count to log when changes occur
	private int lastFoundCount = -1;
	
	private final WardenEspStyleSetting style = new WardenEspStyleSetting();
	private final CheckboxSetting fillShapes = new CheckboxSetting(
		"Fill shapes", "Render filled versions of the ESP shapes.", false);
	private final EnumSetting<TracerMode> tracerMode =
		new EnumSetting<>("Tracers", TracerMode.values(), TracerMode.TARGETING);
	private final CheckboxSetting showTargetLabel = new CheckboxSetting(
		"Target label", "Show TARGET: YOU/OTHER label.", true);
	private final CheckboxSetting showSniffingPulse =
		new CheckboxSetting("Sniff pulse",
			"Show a brief pulsing overlay when the Warden is sniffing.", true);
	private final CheckboxSetting showImminentIndicator =
		new CheckboxSetting("Combat imminence",
			"Show an indicator when combat/sonic attack is imminent.", true);
	private final CheckboxSetting showAngerTrend =
		new CheckboxSetting("Anger trend",
			"Show a simple up/down indicator when anger changes.", false);
	private final SliderSetting labelScale = new SliderSetting("Label scale",
		1.2, 0.7, 10.0, 0.1, SliderSetting.ValueDisplay.DECIMAL);
	
	public WardenEspHack()
	{
		super("WardenESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(fillShapes);
		addSetting(tracerMode);
		addSetting(showTargetLabel);
		addSetting(showSniffingPulse);
		addSetting(showImminentIndicator);
		addSetting(showAngerTrend);
		addSetting(labelScale);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		wardens.clear();
		lastAnger.clear();
		attackExpiry.clear();
	}
	
	@Override
	public void onUpdate()
	{
		wardens.clear();
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(!(e instanceof LivingEntity le))
				continue;
			if(e.isRemoved() || (le.getHealth() <= 0))
				continue;
			// Prefer checking the entity type to handle mapped/obfuscated names
			if(e.getType() == EntityType.WARDEN)
				wardens.add(le);
		}
		
		int sz = wardens.size();
		if(sz != lastFoundCount)
		{
			lastFoundCount = sz;
			System.out.println("WardenESP: found wardens = " + sz);
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(wardens.isEmpty())
			return;
		
		WardenEspStyleSetting.Shape shape = style.getShape();
		boolean glowMode = shape == WardenEspStyleSetting.Shape.GLOW;
		boolean drawShape =
			!glowMode && shape != WardenEspStyleSetting.Shape.NONE;
		boolean drawFill = drawShape && fillShapes.isChecked();
		List<ColoredBox> outlineShapes = drawShape ? new ArrayList<>() : null;
		List<ColoredBox> filledShapes = drawFill ? new ArrayList<>() : null;
		List<ColoredPoint> tracerPoints =
			tracerMode.getSelected() == TracerMode.OFF ? null
				: new ArrayList<>();
		
		for(LivingEntity w : wardens)
		{
			try
			{
				if(!(w instanceof Warden warden))
					continue;
				
				AABB lerped = EntityUtils.getLerpedBox(w, partialTicks);
				// default state = CALM (green)
				int color = 0xFF55FF55; // green
				
				int anger = warden.getClientAngerLevel();
				// update trend store
				Integer prev = lastAnger.get(w.getId());
				lastAnger.put(w.getId(), anger);
				
				AngerLevel angerLevel = AngerLevel.byAnger(anger);
				
				// sniffing detection
				boolean sniffing = warden.getPose() == Pose.SNIFFING;
				
				// digging/leaving detection
				boolean digging = warden.getPose() == Pose.DIGGING;
				boolean emerging = warden.getPose() == Pose.EMERGING;
				
				// combat imminence: sonic boom charge / melee swing
				long boomChargeTicks = warden.getBrain().getTimeUntilExpiry(
					MemoryModuleType.SONIC_BOOM_SOUND_DELAY);
				boolean sonicCharge = boomChargeTicks > 0;
				boolean attackAnim =
					warden.getAttackAnim(partialTicks) > 0.2F || warden.swinging
						|| warden.attackAnimationState.isStarted();
				boolean imminent = sonicCharge || attackAnim
					|| warden.sonicBoomAnimationState.isStarted();
				long nowTick = warden.tickCount;
				if(imminent)
					attackExpiry.put(w.getId(), nowTick + 100L);
				long expiry = attackExpiry.getOrDefault(w.getId(), 0L);
				boolean attackFlash =
					nowTick <= expiry && !digging && !emerging;
				
				// attack target detection: try direct target or brain memory
				boolean lockedOnYou = false;
				boolean lockedOnOther = false;
				LivingEntity target = warden.getTarget();
				if(target == null)
					target = warden.getBrain()
						.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
				boolean hasTarget = target != null;
				if(hasTarget)
				{
					if(target == MC.player)
						lockedOnYou = true;
					else
						lockedOnOther = true;
				}
				
				boolean locked = hasTarget || angerLevel.isAngry()
					|| lockedOnYou || lockedOnOther;
				boolean calm =
					angerLevel == AngerLevel.CALM && !locked && !sniffing;
				
				// determine color state from anger/sniffing
				if(locked)
					color = 0xFFFF5555; // red LOCKED
				else if(sniffing || angerLevel == AngerLevel.AGITATED)
					color = 0xFFFFAA00; // orange -> sniffing as AGITATED visual
				else if(anger > 0)
					color = 0xFFFFFF55; // yellow SEARCHING/AGITATED
				else
					color = 0xFF55FF55; // green CALM
					
				// if digging/ leaving show blue-ish
				if(digging || emerging)
					color = 0xFF55FFFF;
				
				if(attackFlash)
					color = getFastPulseColor(0xFFFF5555, 0.35F);
				
				if(drawShape)
					outlineShapes.add(new ColoredBox(lerped, color));
				if(filledShapes != null)
				{
					int fill = (color & 0x00FFFFFF) | 0x33000000;
					filledShapes.add(new ColoredBox(lerped, fill));
				}
				
				if(tracerPoints != null)
				{
					boolean drawTracer = switch(tracerMode.getSelected())
					{
						case ALWAYS -> true;
						case TARGETING -> hasTarget || locked || imminent;
						default -> false;
					};
					if(drawTracer)
					{
						int tracerColor;
						if(attackFlash)
							tracerColor = getFastPulseColor(0xFFFF5555, 0.35F);
						else if(lockedOnYou)
							tracerColor = getPulseColor(0xFFFF5555, 0.55F);
						else
							tracerColor = color;
						
						tracerPoints.add(
							new ColoredPoint(lerped.getCenter(), tracerColor));
					}
				}
				
				// sniffing pulsing overlay: add semi-transparent filled box
				if(!glowMode && sniffing && showSniffingPulse.isChecked())
				{
					float pulse = (float)(0.5
						+ 0.5 * Math.sin(System.currentTimeMillis() / 200.0));
					int fill = ((int)(MthClamp(pulse) * 255) << 24)
						| (0xFFFFAA00 & 0x00FFFFFF);
					RenderUtils.drawSolidBoxes(matrixStack,
						java.util.Collections.singletonList(
							new ColoredBox(lerped, fill)),
						false);
				}
				
				// Render text labels above the warden
				float labelScaleValue = getLabelScale();
				float lineSpacing = 12F * (0.9F + 0.1F * labelScaleValue);
				float baseOffset = (float)(lerped.getYsize() / 2.0 + 0.55
					+ 0.25 * (labelScaleValue - 1.0));
				int textColor = color; // reuse box color
				// top: imminent
				int line = 0;
				if(attackFlash && showImminentIndicator.isChecked())
				{
					drawWorldLabel(matrixStack, "⚠ ATTACK",
						lerped.getCenter().x, lerped.getCenter().y + baseOffset,
						lerped.getCenter().z, 0xFFFF5555,
						1.15F * labelScaleValue, -lineSpacing * line);
					line += 1;
				}
				
				// main awareness state
				String mainState = "CALM";
				if(locked)
					mainState = "LOCKED";
				else if(sniffing || angerLevel == AngerLevel.AGITATED)
					mainState = "AGITATED";
				else if(anger > 0)
					mainState = "SEARCHING";
				drawWorldLabel(matrixStack, mainState, lerped.getCenter().x,
					lerped.getCenter().y + baseOffset, lerped.getCenter().z,
					textColor, labelScaleValue, -lineSpacing * line);
				line += 1;
				
				// target
				if((lockedOnYou || lockedOnOther)
					&& showTargetLabel.isChecked())
				{
					String tgt = lockedOnYou ? "TARGET: YOU" : "TARGET: OTHER";
					int tc = lockedOnYou
						? (attackFlash ? getFastPulseColor(0xFFFF5555, 0.35F)
							: getPulseColor(0xFFFF5555, 0.6F))
						: 0xFFAAAAAA;
					drawWorldLabel(matrixStack, tgt, lerped.getCenter().x,
						lerped.getCenter().y + baseOffset, lerped.getCenter().z,
						tc, 0.95F * labelScaleValue, -lineSpacing * line);
					line += 1;
				}
				
				// sniffing
				if(sniffing && showSniffingPulse.isChecked())
				{
					drawWorldLabel(matrixStack, "SNIFFING",
						lerped.getCenter().x, lerped.getCenter().y + baseOffset,
						lerped.getCenter().z, 0xFFFFFFAA,
						0.9F * labelScaleValue, -lineSpacing * line);
					line += 1;
				}
				
				// digging
				if(digging || emerging)
				{
					String label = digging ? "DIGGING" : "EMERGING";
					drawWorldLabel(matrixStack, label, lerped.getCenter().x,
						lerped.getCenter().y + baseOffset, lerped.getCenter().z,
						0xFF55FFFF, 0.9F * labelScaleValue,
						-lineSpacing * line);
					line += 1;
				}
				
				// anger trend arrow
				if(showAngerTrend.isChecked() && prev != null)
				{
					int delta = anger - prev;
					if(delta != 0)
					{
						Vec3 top = lerped.getCenter().add(0,
							lerped.getYsize() / 2.0, 0);
						Vec3 to = top.add(0, delta > 0 ? 0.6 : -0.6, 0);
						int col = delta > 0 ? 0xFFFFFF55 : 0xFF55FF55;
						RenderUtils.drawLine(matrixStack, top, to, col, false);
					}
				}
			}catch(Throwable t)
			{
				// ignore per-entity errors
			}
		}
		
		if(drawShape)
		{
			if(filledShapes != null && !filledShapes.isEmpty())
			{
				switch(shape)
				{
					case BOX -> RenderUtils.drawSolidBoxes(matrixStack,
						filledShapes, false);
					case OCTAHEDRON -> RenderUtils
						.drawSolidOctahedrons(matrixStack, filledShapes, false);
					default ->
						{
						}
				}
			}
			
			if(outlineShapes != null && !outlineShapes.isEmpty())
			{
				switch(shape)
				{
					case BOX -> RenderUtils.drawOutlinedBoxes(matrixStack,
						outlineShapes, false);
					case OCTAHEDRON -> RenderUtils.drawOutlinedOctahedrons(
						matrixStack, outlineShapes, false);
					default ->
						{
						}
				}
			}
		}
		
		if(tracerPoints != null && !tracerPoints.isEmpty())
		{
			double lineWidth =
				tracerMode.getSelected() == TracerMode.ALWAYS ? 1.8 : 2.8;
			RenderUtils.drawTracers(matrixStack, partialTicks, tracerPoints,
				false, lineWidth);
		}
	}
	
	private void drawWorldLabel(PoseStack matrices, String text, double x,
		double y, double z, int argb, float scale, float offsetPx)
	{
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		matrices.translate(x - cam.x, y - cam.y, z - cam.z);
		var camEntity = MC.getCameraEntity();
		if(camEntity != null)
		{
			matrices.mulPose(Axis.YP.rotationDegrees(-camEntity.getYRot()));
			matrices.mulPose(Axis.XP.rotationDegrees(camEntity.getXRot()));
		}
		matrices.mulPose(Axis.YP.rotationDegrees(180.0F));
		float s = 0.025F * scale;
		matrices.scale(s, -s, s);
		matrices.translate(0, offsetPx, 0);
		Font tr = MC.font;
		MultiBufferSource.BufferSource vcp = RenderUtils.getVCP();
		float w = tr.width(text) / 2F;
		int baseAlpha = (argb >>> 24) & 0xFF;
		int bgAlpha =
			(int)Math.round(MC.options.getBackgroundOpacity(0.25F) * baseAlpha);
		int bg = (bgAlpha << 24);
		var matrix = matrices.last().pose();
		// stroke for legibility
		int strokeColor =
			(Math.max(0, Math.min(255, baseAlpha)) << 24) | 0x000000;
		tr.drawInBatch(text, -w - 1, 0, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		tr.drawInBatch(text, -w + 1, 0, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		tr.drawInBatch(text, -w, -1, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		tr.drawInBatch(text, -w, +1, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		tr.drawInBatch(text, -w, 0, argb, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, bg, 0xF000F0);
		vcp.endBatch();
		matrices.popPose();
	}
	
	private static float MthClamp(float v)
	{
		if(Float.isNaN(v))
			return 0F;
		if(v < 0F)
			return 0F;
		if(v > 1F)
			return 1F;
		return v;
	}
	
	private float getLabelScale()
	{
		return (float)labelScale.getValue();
	}
	
	private static int getPulseColor(int argb, float minBrightness)
	{
		return getPulseColor(argb, minBrightness, 180.0);
	}
	
	private static int getFastPulseColor(int argb, float minBrightness)
	{
		return getPulseColor(argb, minBrightness, 90.0);
	}
	
	private static int getPulseColor(int argb, float minBrightness,
		double speedMs)
	{
		float pulse =
			(float)(0.5 + 0.5 * Math.sin(System.currentTimeMillis() / speedMs));
		float t = minBrightness + (1F - minBrightness) * pulse;
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >>> 16) & 0xFF;
		int g = (argb >>> 8) & 0xFF;
		int b = argb & 0xFF;
		r = (int)Math.min(255, r * t);
		g = (int)Math.min(255, g * t);
		b = (int)Math.min(255, b * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
	
	public Integer getGlowColor(LivingEntity entity)
	{
		if(!isEnabled())
			return null;
		if(style.getShape() != WardenEspStyleSetting.Shape.GLOW)
			return null;
		if(!(entity instanceof Warden warden))
			return null;
		if(!wardens.contains(entity))
			return null;
		
		int anger = warden.getClientAngerLevel();
		AngerLevel angerLevel = AngerLevel.byAnger(anger);
		boolean sniffing = warden.getPose() == Pose.SNIFFING;
		boolean digging = warden.getPose() == Pose.DIGGING;
		boolean emerging = warden.getPose() == Pose.EMERGING;
		LivingEntity target = warden.getTarget();
		if(target == null)
			target = warden.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
				.orElse(null);
		boolean lockedOnYou = target == MC.player;
		boolean hasTarget = target != null;
		boolean locked = hasTarget || angerLevel.isAngry() || lockedOnYou;
		long expiry = attackExpiry.getOrDefault(warden.getId(), 0L);
		long now = warden.tickCount;
		boolean attackActive = now <= expiry && !digging && !emerging;
		
		int color = 0xFF55FF55;
		if(locked)
			color = 0xFFFF5555;
		else if(sniffing || angerLevel == AngerLevel.AGITATED)
			color = 0xFFFFAA00;
		else if(anger > 0)
			color = 0xFFFFFF55;
		if(digging || emerging)
			color = 0xFF55FFFF;
		if(lockedOnYou)
			color = getPulseColor(color, 0.6F);
		if(attackActive)
			color = getFastPulseColor(0xFFFF5555, 0.35F);
		
		return color;
	}
	
	private enum TracerMode
	{
		OFF("Off"),
		TARGETING("When targeting"),
		ALWAYS("Always");
		
		private final String name;
		
		private TracerMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private static final class WardenEspStyleSetting
		extends EnumSetting<WardenEspStyleSetting.Style>
	{
		private WardenEspStyleSetting()
		{
			super("ESP type", Style.values(), Style.BOX);
		}
		
		public Shape getShape()
		{
			return getSelected().shape;
		}
		
		enum Shape
		{
			NONE,
			BOX,
			OCTAHEDRON,
			GLOW;
		}
		
		private enum Style
		{
			BOX("Box", Shape.BOX),
			OCTAHEDRON("Octahedron", Shape.OCTAHEDRON),
			GLOW("Glow", Shape.GLOW),
			NONE("None", Shape.NONE);
			
			private final String name;
			private final Shape shape;
			
			private Style(String name, Shape shape)
			{
				this.name = name;
				this.shape = shape;
			}
			
			@Override
			public String toString()
			{
				return name;
			}
		}
	}
}
