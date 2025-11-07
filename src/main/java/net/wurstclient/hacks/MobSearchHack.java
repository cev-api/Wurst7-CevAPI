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
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;

@SearchTags({"mobsearch", "mob search", "entity search", "mob esp search"})
public final class MobSearchHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private enum SearchMode
	{
		LIST,
		TYPE_ID,
		QUERY
	}
	
	private static final int MAX_TEXT_LENGTH = 256;
	private final net.wurstclient.settings.EnumSetting<SearchMode> mode =
		new net.wurstclient.settings.EnumSetting<>("Mode", SearchMode.values(),
			SearchMode.TYPE_ID);
	private final net.wurstclient.settings.EntityTypeListSetting entityList =
		new net.wurstclient.settings.EntityTypeListSetting("Entity List",
			"Entities to match when Mode is set to List.");
	private final MobSearchStyleSetting style = new MobSearchStyleSetting();
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting("Box size",
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox of each mob.\n"
			+ "\u00a7lFancy\u00a7r mode shows slightly larger boxes that look better.",
		EspBoxSizeSetting.BoxSize.ACCURATE);
	
	private final CheckboxSetting fillShapes = new CheckboxSetting(
		"Fill shapes", "Render filled versions of the ESP shapes.", true);
	private final TextFieldSetting typeId = new TextFieldSetting("Type",
		"The entity type to match when Query is empty (e.g. minecraft:zombie or zombie).",
		"minecraft:zombie", v -> v.length() <= MAX_TEXT_LENGTH);
	private final TextFieldSetting query = new TextFieldSetting("Query",
		"Enter text to match entity IDs or names by keyword. Separate multiple terms with commas.",
		"", v -> v.length() <= MAX_TEXT_LENGTH);
	private final CheckboxSetting useRainbow =
		new CheckboxSetting("Rainbow colors",
			"Use a rainbow color instead of the fixed color.", false);
	
	// Above-ground filter
	private final CheckboxSetting onlyAboveGround =
		new CheckboxSetting("Above ground only",
			"Only show mobs at or above the configured Y level.", false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private final ColorSetting color = new ColorSetting("Color",
		"Fixed color used when Rainbow colors is disabled.", Color.PINK);
	// New: optionally show detected count in HackList
	private final CheckboxSetting showCountInHackList = new CheckboxSetting(
		"HackList count",
		"Appends the number of matched mobs to this hack's entry in the HackList.",
		false);
	
	private final ArrayList<LivingEntity> matches = new ArrayList<>();
	private SearchMode lastMode;
	private int lastEntityListHash;
	// Caches for LIST mode
	private java.util.Set<String> listExactIds;
	private String[] listKeywords;
	private int foundCount;
	
	public MobSearchHack()
	{
		super("MobSearch");
		setCategory(Category.RENDER);
		addSetting(mode);
		addSetting(entityList);
		addSetting(style);
		addSetting(boxSize);
		addSetting(fillShapes);
		addSetting(typeId);
		addSetting(query);
		addSetting(useRainbow);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		addSetting(color);
		addSetting(showCountInHackList);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		lastMode = mode.getSelected();
		lastEntityListHash = entityList.getTypeNames().hashCode();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		matches.clear();
		foundCount = 0;
	}
	
	@Override
	public String getRenderName()
	{
		String q = query.getValue().trim();
		switch(mode.getSelected())
		{
			case LIST:
			return appendCountIfEnabled(
				getName() + " [List:" + entityList.getTypeNames().size() + "]");
			case QUERY:
			if(!q.isEmpty())
				return appendCountIfEnabled(
					getName() + " [" + abbreviate(q) + "]");
			return appendCountIfEnabled(getName() + " [query]");
			case TYPE_ID:
			default:
			String t = typeId.getValue().trim();
			if(t.startsWith("minecraft:"))
				t = t.substring("minecraft:".length());
			return appendCountIfEnabled(getName() + " [" + t + "]");
		}
	}
	
	@Override
	public void onUpdate()
	{
		matches.clear();
		SearchMode currentMode = mode.getSelected();
		if(currentMode != lastMode)
			lastMode = currentMode;
		if(currentMode == SearchMode.LIST)
		{
			int h = entityList.getTypeNames().hashCode();
			if(h != lastEntityListHash)
				lastEntityListHash = h;
			// rebuild caches
			java.util.HashSet<String> exact = new java.util.HashSet<>();
			java.util.ArrayList<String> kw = new java.util.ArrayList<>();
			for(String s : entityList.getTypeNames())
			{
				if(s == null)
					continue;
				String raw = s.trim();
				if(raw.isEmpty())
					continue;
				Identifier id = Identifier.tryParse(raw);
				if(id != null && Registries.ENTITY_TYPE.containsId(id))
					exact.add(id.toString());
				else
					kw.add(raw.toLowerCase(Locale.ROOT));
			}
			listExactIds = exact;
			listKeywords = kw.toArray(new String[0]);
		}
		java.util.function.Predicate<LivingEntity> predicate;
		switch(currentMode)
		{
			case LIST:
			predicate = e -> {
				Identifier id = Registries.ENTITY_TYPE.getId(e.getType());
				String idFull = id == null ? "" : id.toString();
				if(listExactIds != null && listExactIds.contains(idFull))
					return true;
				String local = idFull.contains(":")
					? idFull.substring(idFull.indexOf(":") + 1) : idFull;
				String localSpaced = local.replace('_', ' ');
				String transKey = e.getType().getTranslationKey();
				String display = e.getType().getName().getString();
				if(listKeywords != null)
					for(String term : listKeywords)
						if(containsNormalized(idFull, term)
							|| containsNormalized(local, term)
							|| containsNormalized(localSpaced, term)
							|| containsNormalized(transKey, term)
							|| containsNormalized(display, term))
							return true;
				return false;
			};
			break;
			case QUERY:
			predicate = byFuzzyQuery(normalize(query.getValue()));
			break;
			case TYPE_ID:
			default:
			predicate = byExactType(normalize(typeId.getValue()));
		}
		
		Stream<LivingEntity> stream = StreamSupport
			.stream(MC.world.getEntities().spliterator(), false)
			.filter(LivingEntity.class::isInstance).map(e -> (LivingEntity)e)
			.filter(e -> !(e instanceof PlayerEntity))
			.filter(e -> !e.isRemoved() && e.getHealth() > 0).filter(predicate);
		
		// apply above-ground filter if enabled
		if(onlyAboveGround.isChecked())
			stream = stream.filter(e -> e.getY() >= aboveGroundY.getValue());
		
		matches.addAll(stream.collect(Collectors.toList()));
		// update count for HUD (clamped to 999)
		foundCount = Math.min(matches.size(), 999);
	}
	
	private String appendCountIfEnabled(String base)
	{
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
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		if(matches.isEmpty())
			return;
		
		MobSearchStyleSetting.Shape shape = style.getShape();
		boolean glowMode = shape == MobSearchStyleSetting.Shape.GLOW;
		boolean drawShape =
			!glowMode && shape != MobSearchStyleSetting.Shape.NONE;
		boolean drawLines = style.hasLines();
		boolean drawFill = drawShape && fillShapes.isChecked();
		
		ArrayList<ColoredBox> outlineShapes =
			drawShape ? new ArrayList<>(matches.size()) : null;
		ArrayList<ColoredBox> filledShapes =
			drawFill ? new ArrayList<>(matches.size()) : null;
		ArrayList<ColoredPoint> ends =
			drawLines ? new ArrayList<>(matches.size()) : null;
		
		if(drawShape || drawLines)
		{
			double extra = drawShape ? boxSize.getExtraSize() / 2D : 0;
			
			for(LivingEntity e : matches)
			{
				Box lerpedBox = EntityUtils.getLerpedBox(e, partialTicks);
				int outlineColor = getColorI(0.5F);
				
				if(drawShape)
				{
					Box box = lerpedBox.offset(0, extra, 0).expand(extra);
					outlineShapes.add(new ColoredBox(box, outlineColor));
					
					if(filledShapes != null)
					{
						int fillColor = getColorI(0.15F);
						filledShapes.add(new ColoredBox(box, fillColor));
					}
				}
				
				if(drawLines && ends != null)
					ends.add(
						new ColoredPoint(lerpedBox.getCenter(), outlineColor));
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
	}
	
	private int getColorI(float alpha)
	{
		if(useRainbow.isChecked())
		{
			float[] rgb = RenderUtils.getRainbowColor();
			return RenderUtils.toIntColor(rgb, alpha);
		}
		return RenderUtils.toIntColor(color.getColorF(), alpha);
	}
	
	private Predicate<LivingEntity> byExactType(String normalizedType)
	{
		return e -> {
			Identifier id = Registries.ENTITY_TYPE.getId(e.getType());
			String s = id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
			String local =
				s.contains(":") ? s.substring(s.indexOf(":") + 1) : s;
			return s.equals(normalizedType) || local.equals(normalizedType);
		};
	}
	
	private Predicate<LivingEntity> byFuzzyQuery(String q)
	{
		// Support multiple comma-separated terms, match if any term matches
		String[] terms = Stream.of(q.split(",")).map(String::trim)
			.filter(s -> !s.isEmpty()).toArray(String[]::new);
		return e -> {
			Identifier id = Registries.ENTITY_TYPE.getId(e.getType());
			String s = id == null ? "" : id.toString();
			String local =
				s.contains(":") ? s.substring(s.indexOf(":") + 1) : s;
			String localSpaced = local.replace('_', ' ');
			String transKey = e.getType().getTranslationKey();
			String display = e.getType().getName().getString();
			for(String term : terms)
				if(containsNormalized(s, term)
					|| containsNormalized(local, term)
					|| containsNormalized(localSpaced, term)
					|| containsNormalized(transKey, term)
					|| containsNormalized(display, term))
					return true;
			return false;
		};
	}
	
	private String normalize(String s)
	{
		if(s == null)
			return "";
		return s.trim().toLowerCase(Locale.ROOT);
	}
	
	private boolean containsNormalized(String haystack, String needle)
	{
		return haystack != null
			&& haystack.toLowerCase(Locale.ROOT).contains(needle);
	}
	
	private String abbreviate(String text)
	{
		if(text.length() <= 32)
			return text;
		return text.substring(0, 32) + "...";
	}
	
	public Integer getGlowColor(LivingEntity entity)
	{
		if(!isEnabled())
			return null;
		if(style.getShape() != MobSearchStyleSetting.Shape.GLOW)
			return null;
		if(!matches.contains(entity))
			return null;
		return getColorI(1F);
	}
	
	// Local style setting that mirrors MobEsp's style but for MobSearch
	private static final class MobSearchStyleSetting extends
		net.wurstclient.settings.EnumSetting<MobSearchStyleSetting.Style>
	{
		private MobSearchStyleSetting()
		{
			super("Style", Style.values(), Style.LINES_AND_GLOW);
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
