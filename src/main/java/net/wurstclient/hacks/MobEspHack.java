/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.settings.filters.*;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;

@SearchTags({"mob esp", "MobTracers", "mob tracers"})
public final class MobEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final MobEspStyleSetting style = new MobEspStyleSetting();
	
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting("Box size",
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox of each mob.\n"
			+ "\u00a7lFancy\u00a7r mode shows slightly larger boxes that look better.",
		EspBoxSizeSetting.BoxSize.ACCURATE);
	
	private final CheckboxSetting fillShapes = new CheckboxSetting(
		"Fill shapes", "Render filled versions of the ESP shapes.", true);
	
	// New color options to match MobSearch
	private final CheckboxSetting useRainbow =
		new CheckboxSetting("Rainbow colors",
			"Use a rainbow color instead of the fixed color.", false);
	private final ColorSetting color = new ColorSetting("Color",
		"Fixed color used when Rainbow colors is disabled.", Color.RED);
	
	private final EntityFilterList entityFilters =
		new EntityFilterList(FilterHostileSetting.genericVision(false),
			FilterNeutralSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterPassiveSetting.genericVision(false),
			FilterPassiveWaterSetting.genericVision(false),
			FilterBatsSetting.genericVision(false),
			FilterSlimesSetting.genericVision(false),
			FilterPetsSetting.genericVision(false),
			FilterVillagersSetting.genericVision(false),
			FilterZombieVillagersSetting.genericVision(false),
			FilterGolemsSetting.genericVision(false),
			FilterPiglinsSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterZombiePiglinsSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterEndermenSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterShulkersSetting.genericVision(false),
			FilterAllaysSetting.genericVision(false),
			FilterInvisibleSetting.genericVision(false),
			FilterNamedSetting.genericVision(false),
			FilterArmorStandsSetting.genericVision(true));
	
	private final ArrayList<LivingEntity> mobs = new ArrayList<>();
	private final ArrayList<ShulkerBullet> shulkerBullets = new ArrayList<>();
	
	// New: optionally show detected count in HackList
	private final CheckboxSetting showCountInHackList = new CheckboxSetting(
		"HackList count",
		"Appends the number of detected mobs to this hack's entry in the HackList.",
		false);
	
	// Above-ground filter
	private final CheckboxSetting onlyAboveGround =
		new CheckboxSetting("Above ground only",
			"Only show mobs at or above the configured Y level.", false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	
	private final CheckboxSetting highlightShulkerProjectiles =
		new CheckboxSetting("Highlight shulker projectiles",
			"Also outline the tracking projectiles fired by shulkers.", false);
	
	private int foundCount;
	
	public MobEspHack()
	{
		super("MobESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(boxSize);
		addSetting(fillShapes);
		addSetting(useRainbow);
		addSetting(color);
		entityFilters.forEach(this::addSetting);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		addSetting(highlightShulkerProjectiles);
		addSetting(showCountInHackList);
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
		foundCount = 0;
		shulkerBullets.clear();
	}
	
	@Override
	public void onUpdate()
	{
		mobs.clear();
		shulkerBullets.clear();
		
		Stream<LivingEntity> stream = StreamSupport
			.stream(MC.level.entitiesForRendering().spliterator(), false)
			.filter(LivingEntity.class::isInstance).map(e -> (LivingEntity)e)
			.filter(e -> !(e instanceof Player))
			.filter(e -> !e.isRemoved() && e.getHealth() > 0);
		// optionally filter out mobs below the configured Y level
		if(onlyAboveGround.isChecked())
			stream = stream.filter(e -> e.getY() >= aboveGroundY.getValue());
		
		stream = entityFilters.applyTo(stream);
		
		mobs.addAll(stream.collect(Collectors.toList()));
		
		if(highlightShulkerProjectiles.isChecked())
		{
			for(Entity entity : MC.level.entitiesForRendering())
				if(entity instanceof ShulkerBullet bullet
					&& !bullet.isRemoved())
					shulkerBullets.add(bullet);
		}
		
		// update count for HUD (clamped to 999)
		int highlighted = mobs.size();
		if(highlightShulkerProjectiles.isChecked())
			highlighted += shulkerBullets.size();
		foundCount = Math.min(highlighted, 999);
	}
	
	@Override
	public String getRenderName()
	{
		String base = getName();
		if(showCountInHackList.isChecked() && foundCount > 0)
			return base + " [" + foundCount + "]";
		return base;
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		MobEspStyleSetting.Shape shape = style.getShape();
		boolean glowMode = shape == MobEspStyleSetting.Shape.GLOW;
		boolean drawShape = !glowMode && shape != MobEspStyleSetting.Shape.NONE;
		boolean drawLines = style.hasLines();
		boolean drawFill = drawShape && fillShapes.isChecked();
		
		int anticipatedSize = mobs.size();
		if(highlightShulkerProjectiles.isChecked())
			anticipatedSize += shulkerBullets.size();
		
		ArrayList<ColoredBox> outlineShapes =
			drawShape ? new ArrayList<>(anticipatedSize) : null;
		ArrayList<ColoredBox> filledShapes =
			drawFill ? new ArrayList<>(anticipatedSize) : null;
		ArrayList<ColoredPoint> ends =
			drawLines ? new ArrayList<>(anticipatedSize) : null;
		
		if(drawShape || drawLines)
		{
			double extraSize = drawShape ? boxSize.getExtraSize() / 2D : 0;
			
			for(LivingEntity e : mobs)
			{
				AABB lerpedBox = EntityUtils.getLerpedBox(e, partialTicks);
				float[] rgb = getColorRgb();
				int outlineColor = RenderUtils.toIntColor(rgb, 0.5F);
				
				if(drawShape)
				{
					AABB box =
						lerpedBox.move(0, extraSize, 0).inflate(extraSize);
					outlineShapes.add(new ColoredBox(box, outlineColor));
					
					if(filledShapes != null)
					{
						int fillColor = RenderUtils.toIntColor(rgb, 0.15F);
						filledShapes.add(new ColoredBox(box, fillColor));
					}
				}
				
				if(drawLines && ends != null)
					ends.add(
						new ColoredPoint(lerpedBox.getCenter(), outlineColor));
			}
			
			if(highlightShulkerProjectiles.isChecked())
			{
				for(ShulkerBullet bullet : shulkerBullets)
				{
					AABB lerpedBox =
						EntityUtils.getLerpedBox(bullet, partialTicks);
					float[] rgb = getColorRgb();
					int outlineColor = RenderUtils.toIntColor(rgb, 0.5F);
					
					if(drawShape)
					{
						AABB box =
							lerpedBox.move(0, extraSize, 0).inflate(extraSize);
						outlineShapes.add(new ColoredBox(box, outlineColor));
						
						if(filledShapes != null)
						{
							int fillColor = RenderUtils.toIntColor(rgb, 0.15F);
							filledShapes.add(new ColoredBox(box, fillColor));
						}
					}
					
					if(drawLines && ends != null)
						ends.add(new ColoredPoint(lerpedBox.getCenter(),
							outlineColor));
				}
			}
		}
		
		if(!glowMode)
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
		
		if(ends != null && !ends.isEmpty())
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, false);
		
		if(glowMode && highlightShulkerProjectiles.isChecked()
			&& !shulkerBullets.isEmpty())
			renderShulkerProjectileFallback(matrixStack, partialTicks);
	}
	
	private void renderShulkerProjectileFallback(PoseStack matrixStack,
		float partialTicks)
	{
		ArrayList<ColoredBox> filledShapes =
			new ArrayList<>(shulkerBullets.size());
		double extraSize = boxSize.getExtraSize() / 2D;
		int fillColor = RenderUtils.toIntColor(getColorRgb(), 0.35F);
		
		for(ShulkerBullet bullet : shulkerBullets)
		{
			AABB box = EntityUtils.getLerpedBox(bullet, partialTicks)
				.move(0, extraSize, 0).inflate(extraSize);
			filledShapes.add(new ColoredBox(box, fillColor));
		}
		
		RenderUtils.drawSolidBoxes(matrixStack, filledShapes, false);
	}
	
	public RenderStyleInfo getRenderStyleInfo()
	{
		MobEspStyleSetting.Shape shape = style.getShape();
		RenderShape renderShape = switch(shape)
		{
			case BOX -> RenderShape.BOX;
			case OCTAHEDRON -> RenderShape.OCTAHEDRON;
			case GLOW -> RenderShape.GLOW;
			default -> RenderShape.NONE;
		};
		
		boolean drawShape = renderShape == RenderShape.BOX
			|| renderShape == RenderShape.OCTAHEDRON;
		
		double extra = drawShape ? boxSize.getExtraSize() / 2D : 0;
		boolean fill = drawShape && fillShapes.isChecked();
		
		return new RenderStyleInfo(renderShape, style.hasLines(), fill, extra);
	}
	
	private float[] getColorRgb()
	{
		if(useRainbow.isChecked())
			return RenderUtils.getRainbowColor();
		return color.getColorF();
	}
	
	public boolean shouldRenderEntity(LivingEntity entity)
	{
		if(entity == null || entity instanceof Player)
			return false;
		if(entity.isRemoved() || entity.getHealth() <= 0)
			return false;
		if(onlyAboveGround.isChecked()
			&& entity.getY() < aboveGroundY.getValue())
			return false;
		
		return entityFilters.testOne(entity);
	}
	
	public static enum RenderShape
	{
		NONE,
		BOX,
		OCTAHEDRON,
		GLOW;
	}
	
	public static final class RenderStyleInfo
	{
		public final RenderShape shape;
		public final boolean drawLines;
		public final boolean fillShapes;
		public final double extraSize;
		
		public RenderStyleInfo(RenderShape shape, boolean drawLines,
			boolean fillShapes, double extraSize)
		{
			this.shape = shape;
			this.drawLines = drawLines;
			this.fillShapes = fillShapes;
			this.extraSize = extraSize;
		}
	}
	
	public Integer getGlowColor(LivingEntity entity)
	{
		if(!isEnabled())
			return null;
		if(style.getShape() != MobEspStyleSetting.Shape.GLOW)
			return null;
		if(!mobs.contains(entity))
			return null;
		return RenderUtils.toIntColor(getColorRgb(), 1F);
	}
	
	private static final class MobEspStyleSetting
		extends EnumSetting<MobEspStyleSetting.Style>
	{
		private MobEspStyleSetting()
		{
			super("Style", Style.values(), Style.GLOW);
		}
		
		public Shape getShape()
		{
			return getSelected().shape;
		}
		
		public boolean hasLines()
		{
			return getSelected().lines;
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
			BOXES("Boxes only", Shape.BOX, false),
			OCTAHEDRONS("Octahedrons only", Shape.OCTAHEDRON, false),
			LINES("Lines only", Shape.NONE, true),
			LINES_AND_BOXES("Lines and boxes", Shape.BOX, true),
			LINES_AND_OCTAHEDRONS("Lines and octahedrons", Shape.OCTAHEDRON,
				true),
			GLOW("Glow only", Shape.GLOW, false),
			LINES_AND_GLOW("Lines and glow", Shape.GLOW, true);
			
			private final String name;
			private final Shape shape;
			private final boolean lines;
			
			private Style(String name, Shape shape, boolean lines)
			{
				this.name = name;
				this.shape = shape;
				this.lines = lines;
			}
			
			@Override
			public String toString()
			{
				return name;
			}
		}
	}
}
