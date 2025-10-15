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
import java.util.Arrays;
import java.util.Locale;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"item esp", "ItemTracers", "item tracers"})
public final class ItemEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private enum SpecialMode
	{
		LIST,
		ITEM_ID,
		QUERY
	}
	
	private static final int MAX_SPECIAL_TEXT_LENGTH = 256;
	private final EspStyleSetting style = new EspStyleSetting();
	
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting(
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox of each item.\n"
			+ "\u00a7lFancy\u00a7r mode shows larger boxes that look better.");
	
	private final ColorSetting color = new ColorSetting("Color",
		"Items will be highlighted in this color.", Color.YELLOW);
	
	// Special highlighting for selected items
	private final net.wurstclient.settings.EnumSetting<SpecialMode> specialMode =
		new net.wurstclient.settings.EnumSetting<>("Special mode",
			SpecialMode.values(), SpecialMode.LIST);
	private final ItemListSetting specialList = new ItemListSetting(
		"Special items", "Blocks/items to highlight with a different color.",
		"minecraft:player_head", "minecraft:skeleton_skull",
		"minecraft:wither_skeleton_skull", "minecraft:zombie_head",
		"minecraft:creeper_head", "minecraft:dragon_head",
		"minecraft:piglin_head");
	private final TextFieldSetting specialItemId = new TextFieldSetting(
		"Item ID", "Exact item ID when Special mode is Item_ID.",
		"minecraft:player_head", v -> v.length() <= MAX_SPECIAL_TEXT_LENGTH);
	private final TextFieldSetting specialQuery = new TextFieldSetting("Query",
		"Enter text to match item IDs or names by keyword. Separate multiple terms with commas.",
		"", v -> v.length() <= MAX_SPECIAL_TEXT_LENGTH);
	private final CheckboxSetting specialRainbow = new CheckboxSetting(
		"Special rainbow",
		"If enabled, selected items will cycle through rainbow colors.", false);
	private final ColorSetting specialColor = new ColorSetting("Special color",
		"Color for selected items when not using rainbow.",
		new Color(0xFF00FF));
	private final CheckboxSetting outlineOnly = new CheckboxSetting(
		"Outline-only for special",
		"If enabled, selected items keep the normal box fill color and only the outline changes to the special color.",
		false);
	
	// New: draw tracers only for special items
	private final CheckboxSetting linesOnlyForSpecial =
		new CheckboxSetting("Lines only for special",
			"When enabled, tracers (lines) are drawn only for special items.",
			false);
	
	// New: include special items when equipped by entities (held or worn on
	// head)
	private final CheckboxSetting includeEquippedSpecial = new CheckboxSetting(
		"Highlight equipped special",
		"Also highlight when a special item is held or worn on the head by a player/mob.",
		true);
	
	// New: include item frames holding special items
	private final CheckboxSetting includeItemFrames =
		new CheckboxSetting("Highlight frames with special",
			"Also highlight item frames if the item inside is special.", true);
	
	// New: optionally show detected count in HackList
	private final CheckboxSetting showCountInHackList = new CheckboxSetting(
		"HackList count",
		"Appends the number of detected items to this hack's entry in the HackList.",
		false);
	
	private final ArrayList<ItemEntity> items = new ArrayList<>();
	// Above-ground filter
	private final CheckboxSetting onlyAboveGround =
		new CheckboxSetting("Above ground only",
			"Only show items at or above the configured Y level.", false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Above ground Y", 62, 0, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	// cache for LIST mode: exact IDs and keyword terms
	private java.util.Set<String> specialExactIds;
	private String[] specialKeywords;
	private int lastSpecialListHash;
	private int foundCount; // current number of detected items
	
	public ItemEspHack()
	{
		super("ItemESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(boxSize);
		addSetting(color);
		// special settings
		addSetting(specialMode);
		addSetting(specialList);
		addSetting(specialItemId);
		addSetting(specialQuery);
		addSetting(specialRainbow);
		addSetting(specialColor);
		addSetting(outlineOnly);
		addSetting(linesOnlyForSpecial);
		addSetting(includeEquippedSpecial);
		addSetting(includeItemFrames);
		// above-ground filter
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		// new setting
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
		// reset count
		foundCount = 0;
	}
	
	@Override
	public void onUpdate()
	{
		items.clear();
		for(Entity entity : MC.world.getEntities())
			if(entity instanceof ItemEntity)
				items.add((ItemEntity)entity);
		// update count for HUD (clamped to 999)
		foundCount = Math.min(items.size(), 999);
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
		int baseLines = color.getColorI(0x80);
		int baseQuads = color.getColorI(0x40);
		int specialLines = getSpecialARGB(0x80);
		int specialQuads = getSpecialARGB(0x40);
		
		// Update cached special list parsing when needed
		if(specialMode.getSelected() == SpecialMode.LIST)
		{
			int h = specialList.getItemNames().hashCode();
			if(h != lastSpecialListHash || specialExactIds == null)
			{
				lastSpecialListHash = h;
				java.util.HashSet<String> exact = new java.util.HashSet<>();
				java.util.ArrayList<String> kw = new java.util.ArrayList<>();
				for(String s : specialList.getItemNames())
				{
					if(s == null)
						continue;
					String raw = s.trim();
					if(raw.isEmpty())
						continue;
					Identifier id = Identifier.tryParse(raw);
					if(id != null && Registries.ITEM.containsId(id))
						exact.add(id.toString());
					else
						kw.add(raw.toLowerCase(Locale.ROOT));
				}
				specialExactIds = exact;
				specialKeywords = kw.toArray(new String[0]);
			}
		}
		
		// Partition items into normal vs special
		ArrayList<Box> normalBoxes = new ArrayList<>();
		ArrayList<Box> specialBoxes = new ArrayList<>();
		ArrayList<Vec3d> normalEnds = new ArrayList<>();
		ArrayList<Vec3d> specialEnds = new ArrayList<>();
		
		double extraSize = boxSize.getExtraSize() / 2;
		for(ItemEntity e : items)
		{
			if(onlyAboveGround.isChecked()
				&& e.getY() < aboveGroundY.getValue())
				continue;
			Box box = EntityUtils.getLerpedBox(e, partialTicks)
				.offset(0, extraSize, 0).expand(extraSize);
			boolean isSpecial = isSpecial(e.getStack());
			if(isSpecial)
				specialBoxes.add(box);
			else
				normalBoxes.add(box);
			
			Vec3d center =
				EntityUtils.getLerpedBox(e, partialTicks).getCenter();
			if(isSpecial)
				specialEnds.add(center);
			else
				normalEnds.add(center);
		}
		
		// Item frames holding special items
		if(includeItemFrames.isChecked())
		{
			for(Entity ent : MC.world.getEntities())
			{
				if(!(ent instanceof ItemFrameEntity frame))
					continue;
				ItemStack fs = frame.getHeldItemStack();
				if(fs == null || fs.isEmpty())
					continue;
				if(!isSpecial(fs))
					continue;
				if(onlyAboveGround.isChecked()
					&& frame.getY() < aboveGroundY.getValue())
					continue;
				Box fbox = EntityUtils.getLerpedBox(frame, partialTicks)
					.offset(0, extraSize, 0).expand(extraSize);
				specialBoxes.add(fbox);
				specialEnds.add(fbox.getCenter());
			}
		}
		
		// Equipped specials (held or head) on entities
		if(includeEquippedSpecial.isChecked())
		{
			for(Entity ent : MC.world.getEntities())
			{
				if(!(ent instanceof LivingEntity))
					continue;
				LivingEntity le = (LivingEntity)ent;
				if(le == MC.player)
					continue; // skip local player
				// hands
				ItemStack main = le.getMainHandStack();
				if(main != null && !main.isEmpty() && isSpecial(main))
				{
					Vec3d pos =
						getHeldItemPos(le, Hand.MAIN_HAND, partialTicks);
					if(onlyAboveGround.isChecked() && pos != null
						&& pos.y < aboveGroundY.getValue())
						continue;
					Box b = smallBoxAt(pos);
					if(b != null)
					{
						specialBoxes.add(b);
						specialEnds.add(b.getCenter());
					}
				}
				ItemStack off = le.getOffHandStack();
				if(off != null && !off.isEmpty() && isSpecial(off))
				{
					Vec3d pos = getHeldItemPos(le, Hand.OFF_HAND, partialTicks);
					if(onlyAboveGround.isChecked() && pos != null
						&& pos.y < aboveGroundY.getValue())
						continue;
					Box b = smallBoxAt(pos);
					if(b != null)
					{
						specialBoxes.add(b);
						specialEnds.add(b.getCenter());
					}
				}
				// head worn
				ItemStack head = le.getEquippedStack(EquipmentSlot.HEAD);
				if(head != null && !head.isEmpty() && isSpecial(head))
				{
					Vec3d hp = getHeadPos(le, partialTicks);
					if(hp != null)
					{
						if(onlyAboveGround.isChecked()
							&& hp.y < aboveGroundY.getValue())
							continue;
						Box b = smallBoxAt(hp);
						specialBoxes.add(b);
						specialEnds.add(hp);
					}
				}
			}
		}
		
		if(style.hasBoxes())
		{
			// Normal items: always draw with base color
			if(!normalBoxes.isEmpty())
			{
				RenderUtils.drawSolidBoxes(matrixStack, normalBoxes, baseQuads,
					false);
				RenderUtils.drawOutlinedBoxes(matrixStack, normalBoxes,
					baseLines, false);
			}
			// Special items: either outline-only or full special
			if(!specialBoxes.isEmpty())
			{
				if(outlineOnly.isChecked())
				{
					// keep fill in base color, change outline to special
					RenderUtils.drawSolidBoxes(matrixStack, specialBoxes,
						baseQuads, false);
					RenderUtils.drawOutlinedBoxes(matrixStack, specialBoxes,
						specialLines, false);
				}else
				{
					// fully recolor special items
					RenderUtils.drawSolidBoxes(matrixStack, specialBoxes,
						specialQuads, false);
					RenderUtils.drawOutlinedBoxes(matrixStack, specialBoxes,
						specialLines, false);
				}
			}
		}
		
		if(style.hasLines())
		{
			if(!linesOnlyForSpecial.isChecked() && !normalEnds.isEmpty())
				RenderUtils.drawTracers(matrixStack, partialTicks, normalEnds,
					baseLines, false);
			if(!specialEnds.isEmpty())
				RenderUtils.drawTracers(matrixStack, partialTicks, specialEnds,
					specialLines, false);
		}
	}
	
	private int getSpecialARGB(int alpha)
	{
		float[] rgb = specialRainbow.isChecked() ? RenderUtils.getRainbowColor()
			: specialColor.getColorF();
		return RenderUtils.toIntColor(rgb, alpha / 255f);
	}
	
	private boolean isSpecial(ItemStack stack)
	{
		Item item = stack.getItem();
		switch(specialMode.getSelected())
		{
			case LIST:
			{
				String id = Registries.ITEM.getId(item).toString();
				if(specialExactIds != null && specialExactIds.contains(id))
					return true;
				String localId =
					id.contains(":") ? id.substring(id.indexOf(":") + 1) : id;
				String localSpaced = localId.replace('_', ' ');
				String transKey = item.getTranslationKey();
				String display = item.getName().getString();
				String stackDisplay = stack.getName().getString();
				if(specialKeywords != null)
					for(String term : specialKeywords)
						if(containsNormalized(id, term)
							|| containsNormalized(localId, term)
							|| containsNormalized(localSpaced, term)
							|| containsNormalized(transKey, term)
							|| containsNormalized(display, term)
							|| containsNormalized(stackDisplay, term))
							return true;
				return false;
			}
			case ITEM_ID:
			return itemMatchesId(item, specialItemId.getValue());
			case QUERY:
			return itemOrStackMatchesQuery(item, stack,
				normalizeQuery(specialQuery.getValue()));
			default:
			return false;
		}
	}
	
	private boolean itemMatchesId(Item item, String idStr)
	{
		if(idStr == null || idStr.isBlank())
			return false;
		try
		{
			Identifier id = Identifier.of(idStr.trim());
			Item target = Registries.ITEM.get(id);
			return target != null && target == item;
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
	
	private String normalizeQuery(String raw)
	{
		if(raw == null)
			return "";
		return raw.trim().toLowerCase(Locale.ROOT);
	}
	
	private boolean itemOrStackMatchesQuery(Item item, ItemStack stack,
		String normalizedQuery)
	{
		if(normalizedQuery.isEmpty())
			return false;
		String fullId = Registries.ITEM.getId(item).toString();
		String localId = fullId.contains(":")
			? fullId.substring(fullId.indexOf(":") + 1) : fullId;
		String localSpaced = localId.replace('_', ' ');
		String transKey = item.getTranslationKey();
		String display = item.getName().getString();
		String stackDisplay = stack != null ? stack.getName().getString() : "";
		String[] terms = Arrays.stream(normalizedQuery.split(","))
			.map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
		if(terms.length == 0)
			terms = new String[]{normalizedQuery};
		for(String term : terms)
			if(containsNormalized(fullId, term)
				|| containsNormalized(localId, term)
				|| containsNormalized(localSpaced, term)
				|| containsNormalized(transKey, term)
				|| containsNormalized(display, term)
				|| containsNormalized(stackDisplay, term))
				return true;
		return false;
	}
	
	private boolean containsNormalized(String haystack, String needle)
	{
		return haystack != null
			&& haystack.toLowerCase(Locale.ROOT).contains(needle);
	}
	
	// Helpers to approximate held/head positions and box creation
	private Vec3d getHeldItemPos(LivingEntity e, Hand hand, float partialTicks)
	{
		if(hand == null)
			return null;
		Vec3d base = EntityUtils.getLerpedPos(e, partialTicks);
		double yawRad = Math.toRadians(e.getYaw());
		Arm mainArm = Arm.RIGHT;
		if(e instanceof PlayerEntity pe)
			mainArm = pe.getMainArm();
		boolean rightSide = (mainArm == Arm.RIGHT && hand == Hand.MAIN_HAND)
			|| (mainArm == Arm.LEFT && hand == Hand.OFF_HAND);
		double side = rightSide ? -1 : 1;
		double eyeH = e.getEyeHeight(e.getPose());
		double offX = Math.cos(yawRad) * 0.16 * side;
		double offY = eyeH - 0.1;
		double offZ = Math.sin(yawRad) * 0.16 * side;
		return base.add(offX, offY, offZ);
	}
	
	private Vec3d getHeadPos(LivingEntity e, float partialTicks)
	{
		Vec3d base = EntityUtils.getLerpedPos(e, partialTicks);
		double eyeH = e.getEyeHeight(e.getPose());
		return base.add(0, eyeH + 0.05, 0);
	}
	
	private Box smallBoxAt(Vec3d c)
	{
		if(c == null)
			return null;
		double r = 0.18;
		return new Box(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r);
	}
	
	@Override
	public String getRenderName()
	{
		String base = getName();
		if(showCountInHackList.isChecked() && foundCount > 0)
			return base + " [" + foundCount + "]";
		return base;
	}
}
