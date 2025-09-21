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
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;

@SearchTags({"mobsearch", "mob search", "entity search", "mob esp search"})
public final class MobSearchHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting(
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox of each mob.\n"
			+ "\u00a7lFancy\u00a7r mode shows slightly larger boxes that look better.");
	private final TextFieldSetting typeId = new TextFieldSetting("Type",
		"The entity type to match when Query is empty (e.g. minecraft:zombie or zombie).",
		"minecraft:zombie", v -> v.length() <= 64);
	private final TextFieldSetting query = new TextFieldSetting("Query",
		"Enter text to match entity IDs or names. Leave empty to match the selected type only.",
		"", v -> v.length() <= 64);
	private final CheckboxSetting useRainbow =
		new CheckboxSetting("Rainbow colors",
			"Use a rainbow color instead of the fixed color.", false);
	private final ColorSetting color = new ColorSetting("Color",
		"Fixed color used when Rainbow colors is disabled.", Color.RED);
	
	private final ArrayList<LivingEntity> matches = new ArrayList<>();
	
	public MobSearchHack()
	{
		super("MobSearch");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(boxSize);
		addSetting(typeId);
		addSetting(query);
		addSetting(useRainbow);
		addSetting(color);
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
		matches.clear();
	}
	
	@Override
	public String getRenderName()
	{
		String q = query.getValue().trim();
		if(!q.isEmpty())
			return getName() + " [" + abbreviate(q) + "]";
		String t = typeId.getValue().trim();
		if(t.startsWith("minecraft:"))
			t = t.substring("minecraft:".length());
		return getName() + " [" + t + "]";
	}
	
	@Override
	public void onUpdate()
	{
		matches.clear();
		String q = normalize(query.getValue());
		Predicate<LivingEntity> predicate = q.isEmpty()
			? byExactType(normalize(typeId.getValue())) : byFuzzyQuery(q);
		
		Stream<LivingEntity> stream = StreamSupport
			.stream(MC.world.getEntities().spliterator(), false)
			.filter(LivingEntity.class::isInstance).map(e -> (LivingEntity)e)
			.filter(e -> !(e instanceof PlayerEntity))
			.filter(e -> !e.isRemoved() && e.getHealth() > 0).filter(predicate);
		
		matches.addAll(stream.collect(Collectors.toList()));
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
		int colorI = getColorI(0.5F);
		
		if(style.hasBoxes())
		{
			double extra = boxSize.getExtraSize() / 2;
			ArrayList<ColoredBox> boxes = new ArrayList<>(matches.size());
			for(LivingEntity e : matches)
			{
				Box box = EntityUtils.getLerpedBox(e, partialTicks)
					.offset(0, extra, 0).expand(extra);
				boxes.add(new ColoredBox(box, colorI));
			}
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, false);
		}
		
		if(style.hasLines())
		{
			ArrayList<ColoredPoint> ends = new ArrayList<>(matches.size());
			for(LivingEntity e : matches)
			{
				Vec3d p = EntityUtils.getLerpedBox(e, partialTicks).getCenter();
				ends.add(new ColoredPoint(p, colorI));
			}
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, false);
		}
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
		return e -> {
			Identifier id = Registries.ENTITY_TYPE.getId(e.getType());
			String s = id == null ? "" : id.toString();
			String local =
				s.contains(":") ? s.substring(s.indexOf(":") + 1) : s;
			String localSpaced = local.replace('_', ' ');
			String transKey = e.getType().getTranslationKey();
			String display = e.getType().getName().getString();
			return containsNormalized(s, q) || containsNormalized(local, q)
				|| containsNormalized(localSpaced, q)
				|| containsNormalized(transKey, q)
				|| containsNormalized(display, q);
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
}
