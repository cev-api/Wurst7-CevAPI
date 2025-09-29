/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
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
		mobs.clear();
		
		Stream<LivingEntity> stream = StreamSupport
			.stream(MC.world.getEntities().spliterator(), false)
			.filter(LivingEntity.class::isInstance).map(e -> (LivingEntity)e)
			.filter(e -> !(e instanceof PlayerEntity))
			.filter(e -> !e.isRemoved() && e.getHealth() > 0);
		
		stream = entityFilters.applyTo(stream);
		
		mobs.addAll(stream.collect(Collectors.toList()));
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
		MobEspStyleSetting.Shape shape = style.getShape();
		boolean drawShape = shape != MobEspStyleSetting.Shape.NONE;
		boolean drawLines = style.hasLines();
		boolean drawFill = drawShape && fillShapes.isChecked();
		
		ArrayList<ColoredBox> outlineShapes =
			drawShape ? new ArrayList<>(mobs.size()) : null;
		ArrayList<ColoredBox> filledShapes =
			drawFill ? new ArrayList<>(mobs.size()) : null;
		ArrayList<ColoredPoint> ends =
			drawLines ? new ArrayList<>(mobs.size()) : null;
		
		if(drawShape || drawLines)
		{
			double extraSize = drawShape ? boxSize.getExtraSize() / 2D : 0;
			
			for(LivingEntity e : mobs)
			{
				Box lerpedBox = EntityUtils.getLerpedBox(e, partialTicks);
				float[] rgb = getColorRgb();
				int outlineColor = RenderUtils.toIntColor(rgb, 0.5F);
				
				if(drawShape)
				{
					Box box =
						lerpedBox.offset(0, extraSize, 0).expand(extraSize);
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
		}
		
		if(filledShapes != null && !filledShapes.isEmpty())
		{
			switch(shape)
			{
				case BOX -> RenderUtils.drawSolidBoxes(matrixStack,
					filledShapes, false);
				case OCTAHEDRON -> RenderUtils.drawSolidOctahedrons(matrixStack,
					filledShapes, false);
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
				case OCTAHEDRON -> RenderUtils
					.drawOutlinedOctahedrons(matrixStack, outlineShapes, false);
				default ->
					{
					}
			}
		}
		
		if(ends != null && !ends.isEmpty())
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, false);
	}
	
	private float[] getColorRgb()
	{
		if(useRainbow.isChecked())
			return RenderUtils.getRainbowColor();
		return color.getColorF();
	}
	
	private static final class MobEspStyleSetting
		extends EnumSetting<MobEspStyleSetting.Style>
	{
		private MobEspStyleSetting()
		{
			super("Style", Style.values(), Style.OCTAHEDRONS);
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
			OCTAHEDRON;
		}
		
		private enum Style
		{
			BOXES("Boxes only", Shape.BOX, false),
			OCTAHEDRONS("Octahedrons only", Shape.OCTAHEDRON, false),
			LINES("Lines only", Shape.NONE, true),
			LINES_AND_BOXES("Lines and boxes", Shape.BOX, true),
			LINES_AND_OCTAHEDRONS("Lines and octahedrons", Shape.OCTAHEDRON,
				true);
			
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
