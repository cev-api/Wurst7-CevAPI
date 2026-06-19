/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;

public final class EntityHealthRenderer
{
	private static final Random RANDOM = new Random();
	private static final Identifier ARMOR_EMPTY =
		Identifier.withDefaultNamespace("hud/armor_empty");
	private static final Identifier ARMOR_HALF =
		Identifier.withDefaultNamespace("hud/armor_half");
	private static final Identifier ARMOR_FULL =
		Identifier.withDefaultNamespace("hud/armor_full");
	private static final float HAND_ICON_BASE_SIZE = 16F;
	private static final float HAND_ICON_SPACING = 2F;
	private static final float HAND_INFO_GAP = 2F;
	
	private EntityHealthRenderer()
	{}
	
	public enum DurabilityDisplayMode
	{
		PERCENT_ONLY("Percent only"),
		BAR_ONLY("Bar only");
		
		private final String label;
		
		DurabilityDisplayMode(String label)
		{
			this.label = label;
		}
		
		@Override
		public String toString()
		{
			return label;
		}
	}
	
	public static void drawHeartsAtEntity(GuiGraphicsExtractor context,
		LivingEntity entity, float partialTicks, float yOffsetPx)
	{
		drawHeartsAtEntity(context, entity, partialTicks, yOffsetPx, false,
			false, DurabilityDisplayMode.PERCENT_ONLY, true, 1F);
	}
	
	public static void drawHeartsAtEntity(GuiGraphicsExtractor context,
		LivingEntity entity, float partialTicks, float yOffsetPx,
		boolean showArmor)
	{
		drawHeartsAtEntity(context, entity, partialTicks, yOffsetPx, showArmor,
			false, DurabilityDisplayMode.PERCENT_ONLY, true, 1F);
	}
	
	public static void drawHeartsAtEntity(GuiGraphicsExtractor context,
		LivingEntity entity, float partialTicks, float yOffsetPx,
		boolean showArmor, boolean showHeldItems,
		DurabilityDisplayMode durabilityDisplayMode,
		boolean showPotionEffectStatus)
	{
		drawHeartsAtEntity(context, entity, partialTicks, yOffsetPx, showArmor,
			showHeldItems, durabilityDisplayMode, showPotionEffectStatus, 1F);
	}
	
	public static void drawHeartsAtEntity(GuiGraphicsExtractor context,
		LivingEntity entity, float partialTicks, float yOffsetPx,
		boolean showArmor, boolean showHeldItems,
		DurabilityDisplayMode durabilityDisplayMode,
		boolean showPotionEffectStatus, float scaleMultiplier)
	{
		drawHeartsAtEntity(context, entity, partialTicks, yOffsetPx, showArmor,
			showHeldItems, durabilityDisplayMode, showPotionEffectStatus,
			scaleMultiplier, false);
	}
	
	public static void drawHeartsAtEntity(GuiGraphicsExtractor context,
		LivingEntity entity, float partialTicks, float yOffsetPx,
		boolean showArmor, boolean showHeldItems,
		DurabilityDisplayMode durabilityDisplayMode,
		boolean showPotionEffectStatus, float scaleMultiplier,
		boolean scaleWithNameTagDistance)
	{
		Vec3 worldPos = EntityUtils.getLerpedPos(entity, partialTicks).add(0,
			entity.getBbHeight() + 0.5, 0);
		
		Vec3 projected =
			WurstClient.MC.gameRenderer.projectPointToScreen(worldPos);
		if(projected.z <= -1 || projected.z >= 1)
			return;
		if(projected.x <= -1 || projected.x >= 1 || projected.y <= -1
			|| projected.y >= 1)
			return;
		
		float screenX = (float)((projected.x + 1) * 0.5 * context.guiWidth());
		float screenY =
			(float)((1 - (projected.y + 1) * 0.5) * context.guiHeight());
		
		double distance = WurstClient.MC.gameRenderer.mainCamera().position()
			.distanceTo(worldPos);
		float scale =
			getScale(distance, scaleMultiplier, scaleWithNameTagDistance);
		float heartsY = screenY + yOffsetPx;
		drawHeartRow(context, screenX, heartsY, scale, entity);
		
		boolean hasArmor = showArmor && entity.getArmorValue() > 0;
		float armorY = heartsY - getArmorYOffset(entity, scale);
		if(hasArmor)
			drawArmorRow(context, screenX, armorY, scale, entity);
		
		if(showHeldItems)
			drawHeldItems(context, screenX, heartsY, armorY, scale, entity,
				hasArmor, durabilityDisplayMode, showPotionEffectStatus);
	}
	
	private static float getArmorYOffset(LivingEntity entity, float scale)
	{
		float rowSpacingPx = 10F * scale;
		float absorption = Math.max(0, entity.getAbsorptionAmount());
		return absorption > 0 ? rowSpacingPx * 2F : rowSpacingPx;
	}
	
	private static void drawHeartRow(GuiGraphicsExtractor context,
		float centerX, float y, float scale, LivingEntity entity)
	{
		float currentHealth = Math.max(0, entity.getHealth());
		float maxHealth = Math.max(1, entity.getMaxHealth());
		float absorption = Math.max(0, entity.getAbsorptionAmount());
		int rawBaseHalfHearts = Math.max(1, Math.round(maxHealth * 2));
		int rawHealthHalfHearts = Math.round(currentHealth * 2);
		int rawAbsorptionHalfHearts = Math.round(absorption * 2);
		
		int baseSlots = Math.max(1, Mth.ceil(rawBaseHalfHearts / 2.0F));
		int displayedHealthHalfHearts;
		if(rawBaseHalfHearts > 20)
		{
			float baseRatio = 20F / rawBaseHalfHearts;
			baseSlots = 10;
			displayedHealthHalfHearts =
				Mth.clamp(Math.round(rawHealthHalfHearts * baseRatio), 0, 20);
		}else
			displayedHealthHalfHearts =
				Mth.clamp(rawHealthHalfHearts, 0, rawBaseHalfHearts);
		
		int displayedAbsorptionHalfHearts = 0;
		int absorptionSlots = 0;
		if(rawAbsorptionHalfHearts > 0)
		{
			displayedAbsorptionHalfHearts =
				Mth.clamp(rawAbsorptionHalfHearts, 0, 20);
			absorptionSlots =
				Math.max(1, Mth.ceil(displayedAbsorptionHalfHearts / 2.0F));
		}
		
		boolean blink = entity.hurtTime > 0;
		boolean hardcore = WurstClient.MC.level != null
			&& WurstClient.MC.level.getLevelData().isHardcore();
		HeartType heartType = HeartType.forEntity(entity);
		HeartType absorptionType = heartType == HeartType.WITHERED
			? HeartType.WITHERED : HeartType.ABSORBING;
		int regenIndex = -1;
		if(entity.hasEffect(MobEffects.REGENERATION))
			regenIndex = entity.tickCount % (baseSlots + 5);
		
		drawHeartLayer(context, centerX, y, scale, baseSlots,
			displayedHealthHalfHearts, heartType, hardcore, blink, regenIndex,
			displayedHealthHalfHearts + displayedAbsorptionHalfHearts <= 4);
		
		if(displayedAbsorptionHalfHearts > 0)
		{
			float rowSpacingPx = 10F * scale;
			drawHeartLayer(context, centerX, y - rowSpacingPx, scale,
				absorptionSlots, displayedAbsorptionHalfHearts, absorptionType,
				hardcore, blink, -1, false);
		}
	}
	
	private static void drawHeartLayer(GuiGraphicsExtractor context,
		float centerX, float y, float scale, int slots, int filledHalfHearts,
		HeartType heartType, boolean hardcore, boolean blink, int regenIndex,
		boolean jitter)
	{
		float heartSize = 9F;
		float spacing = 8F;
		float rowWidth = ((slots - 1) * spacing + heartSize) * scale;
		float x0 = centerX - rowWidth / 2F;
		
		context.pose().pushMatrix();
		context.pose().translate(x0, y);
		context.pose().scale(scale, scale);
		
		for(int i = slots - 1; i >= 0; i--)
		{
			int x = Math.round(i * spacing);
			int yOffset = 0;
			if(jitter)
				yOffset += RANDOM.nextInt(2);
			if(i == regenIndex)
				yOffset -= 2;
			
			context.blitSprite(RenderPipelines.GUI_TEXTURED,
				HeartType.CONTAINER.getSprite(hardcore, false, blink), x,
				yOffset, 9, 9);
			
			int halfIndex = i * 2;
			if(filledHalfHearts >= halfIndex + 2)
				context.blitSprite(RenderPipelines.GUI_TEXTURED,
					heartType.getSprite(hardcore, false, blink), x, yOffset, 9,
					9);
			else if(filledHalfHearts == halfIndex + 1)
				context.blitSprite(RenderPipelines.GUI_TEXTURED,
					heartType.getSprite(hardcore, true, blink), x, yOffset, 9,
					9);
		}
		
		context.pose().popMatrix();
	}
	
	private static void drawArmorRow(GuiGraphicsExtractor context,
		float centerX, float y, float scale, LivingEntity entity)
	{
		int armorPoints = Mth.clamp(entity.getArmorValue(), 0, 20);
		if(armorPoints <= 0)
			return;
		
		int slots = 10;
		float iconSize = 9F;
		float spacing = 8F;
		float rowWidth = ((slots - 1) * spacing + iconSize) * scale;
		float x0 = centerX - rowWidth / 2F;
		
		context.pose().pushMatrix();
		context.pose().translate(x0, y);
		context.pose().scale(scale, scale);
		
		for(int i = slots - 1; i >= 0; i--)
		{
			int x = Math.round(i * spacing);
			context.blitSprite(RenderPipelines.GUI_TEXTURED, ARMOR_EMPTY, x, 0,
				9, 9);
			
			int armorForSlot = armorPoints - i * 2;
			if(armorForSlot >= 2)
				context.blitSprite(RenderPipelines.GUI_TEXTURED, ARMOR_FULL, x,
					0, 9, 9);
			else if(armorForSlot == 1)
				context.blitSprite(RenderPipelines.GUI_TEXTURED, ARMOR_HALF, x,
					0, 9, 9);
		}
		
		context.pose().popMatrix();
	}
	
	private static void drawHeldItems(GuiGraphicsExtractor context,
		float centerX, float heartsY, float armorY, float scale,
		LivingEntity entity, boolean hasArmor,
		DurabilityDisplayMode durabilityDisplayMode,
		boolean showPotionEffectStatus)
	{
		float overlayScale = Math.max(scale, 0.65F);
		float iconSize = HAND_ICON_BASE_SIZE * overlayScale;
		float rowWidth = iconSize * 2F + HAND_ICON_SPACING * overlayScale;
		float x0 = centerX - rowWidth / 2F;
		float infoHeight =
			getDurabilityInfoHeight(overlayScale, durabilityDisplayMode);
		float totalBlockHeight = iconSize
			+ (infoHeight > 0 ? HAND_INFO_GAP * overlayScale : 0F) + infoHeight;
		
		float topRowY = heartsY;
		if(getAbsorptionSlots(entity) > 0)
			topRowY -= 10F * overlayScale;
		if(hasArmor)
			topRowY = Math.min(topRowY, armorY);
		float iconY = topRowY - totalBlockHeight - 4F * overlayScale;
		
		ItemStack main = entity.getMainHandItem();
		ItemStack off = entity.getOffhandItem();
		drawHandIconWithDurability(context, main, x0, iconY, iconSize,
			overlayScale, durabilityDisplayMode);
		drawHandIconWithDurability(context, off,
			x0 + iconSize + HAND_ICON_SPACING * overlayScale, iconY, iconSize,
			overlayScale, durabilityDisplayMode);
		
		if(!showPotionEffectStatus)
			return;
		
		List<Integer> colors = getPotionColors(entity);
		if(colors.isEmpty())
			return;
		
		float bottlesY = iconY - iconSize - 2F * overlayScale;
		float bottleSize = Math.max(7F * overlayScale, iconSize * 0.7F);
		float bottleSpacing = 1F * overlayScale;
		float bottlesWidth =
			colors.size() * bottleSize + (colors.size() - 1) * bottleSpacing;
		float bottlesX = x0 + (rowWidth - bottlesWidth) / 2F;
		for(int i = 0; i < colors.size(); i++)
		{
			drawPotionBottle(context,
				bottlesX + i * (bottleSize + bottleSpacing), bottlesY,
				bottleSize, overlayScale, colors.get(i));
		}
	}
	
	private static void drawHandIconWithDurability(GuiGraphicsExtractor context,
		ItemStack stack, float x, float y, float iconSize, float scale,
		DurabilityDisplayMode durabilityDisplayMode)
	{
		ItemStack renderStack =
			stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack;
		drawScaledItem(context, renderStack, x, y, iconSize);
		
		if(renderStack.isEmpty() || !renderStack.isDamageableItem()
			|| renderStack.getMaxDamage() <= 0)
			return;
		
		double fraction = getDurabilityFraction(renderStack);
		if(durabilityDisplayMode == DurabilityDisplayMode.PERCENT_ONLY)
		{
			String label = Math.round(fraction * 100) + "%";
			int color = getDurabilityColor(fraction);
			float textScale = Math.max(0.45F, 0.6F * scale);
			float textY = y + iconSize + HAND_INFO_GAP * scale;
			RenderUtils.drawScaledText(context, WurstClient.MC.font, label,
				Math.round(x), Math.round(textY), color, true, textScale);
		}else
		{
			float barY = y + iconSize + HAND_INFO_GAP * scale;
			float barH = Math.max(2F * scale, 2F);
			RenderUtils.fill2D(context, x, barY, x + iconSize, barY + barH,
				0xFF202020);
			float fill = (float)(iconSize * fraction);
			if(fill > 0)
				RenderUtils.fill2D(context, x, barY, x + fill, barY + barH,
					getDurabilityColor(fraction));
		}
	}
	
	private static void drawScaledItem(GuiGraphicsExtractor context,
		ItemStack stack, float x, float y, float size)
	{
		context.pose().pushMatrix();
		context.pose().translate(x, y);
		float drawScale = size / 16F;
		context.pose().scale(drawScale, drawScale);
		context.item(stack, 0, 0);
		context.pose().popMatrix();
	}
	
	private static List<Integer> getPotionColors(LivingEntity entity)
	{
		LinkedHashSet<Integer> uniqueColors = new LinkedHashSet<>();
		uniqueColors.addAll(EffectParticleTracker.getRecentColors(entity));
		
		boolean hasAnyEffect = false;
		for(MobEffectInstance effect : entity.getActiveEffects())
		{
			if(effect == null || effect.getEffect() == null)
				continue;
			hasAnyEffect = true;
			int color = effect.getEffect().value().getColor();
			if(color != 0)
				uniqueColors.add(0xFF000000 | color);
		}
		
		int swirlColor = getSwirlParticleColor(entity);
		if(swirlColor != 0)
			uniqueColors.add(0xFF000000 | (swirlColor & 0x00FFFFFF));
			
		// Fallback for clients/servers that expose effects but not a usable
		// effect color packet for remote entities.
		if(hasAnyEffect && uniqueColors.isEmpty())
			uniqueColors.add(0xFFFFFFFF);
		
		return new ArrayList<>(uniqueColors);
	}
	
	private static float getDurabilityInfoHeight(float scale,
		DurabilityDisplayMode durabilityDisplayMode)
	{
		if(durabilityDisplayMode == DurabilityDisplayMode.BAR_ONLY)
			return Math.max(2F * scale, 2F);
		
		// Approximate text block height used by drawScaledText.
		return Math.max(6F * scale, 4F);
	}
	
	private static int getSwirlParticleColor(LivingEntity entity)
	{
		for(String methodName : new String[]{"getPotionSwirlColor",
			"getEffectColor", "getSwirlColor"})
		{
			try
			{
				var m = entity.getClass().getMethod(methodName);
				Object value = m.invoke(entity);
				if(value instanceof Integer color)
					return color.intValue();
			}catch(ReflectiveOperationException ignored)
			{}
		}
		
		// Some mappings keep this on LivingEntity as non-public.
		for(Class<?> c : new Class[]{entity.getClass(), LivingEntity.class})
		{
			for(String methodName : new String[]{"getEffectColor",
				"getPotionSwirlColor", "getSwirlColor"})
			{
				try
				{
					var m = c.getDeclaredMethod(methodName);
					if(m.getParameterCount() != 0
						|| (m.getReturnType() != int.class
							&& m.getReturnType() != Integer.class))
						continue;
					m.setAccessible(true);
					Object value = m.invoke(entity);
					if(value instanceof Integer color)
						return color.intValue();
				}catch(ReflectiveOperationException ignored)
				{}
			}
		}
		return 0;
	}
	
	private static void drawPotionBottle(GuiGraphicsExtractor context, float x,
		float y, float size, float scale, int argbColor)
	{
		ItemStack bottle = new ItemStack(Items.POTION);
		PotionContents contents = new PotionContents(Optional.empty(),
			Optional.of(argbColor & 0x00FFFFFF), List.of(), Optional.empty());
		bottle.set(DataComponents.POTION_CONTENTS, contents);
		drawScaledItem(context, bottle, x, y, size);
	}
	
	private static int getAbsorptionSlots(LivingEntity entity)
	{
		float absorption = Math.max(0, entity.getAbsorptionAmount());
		int rawAbsorptionHalfHearts = Math.round(absorption * 2);
		if(rawAbsorptionHalfHearts <= 0)
			return 0;
		
		int displayedAbsorptionHalfHearts =
			Mth.clamp(rawAbsorptionHalfHearts, 0, 20);
		return Math.max(1, Mth.ceil(displayedAbsorptionHalfHearts / 2.0F));
	}
	
	private static float getScale(double distance, float scaleMultiplier,
		boolean scaleWithNameTagDistance)
	{
		float nameTagScale = 1F;
		if(WurstClient.INSTANCE.getHax().nameTagsHack.isEnabled())
			nameTagScale =
				WurstClient.INSTANCE.getHax().nameTagsHack.getScale();
		
		float scale = 0.7F * nameTagScale * scaleMultiplier;
		
		if(scaleWithNameTagDistance)
			return Math.max(0.25F, scale);
		
		// Keep hearts reasonable at long range so they don't dominate nametags.
		if(distance > 12)
			scale *= (float)(12.0 / distance);
		
		return Mth.clamp(scale, 0.25F, 1.6F);
	}
	
	private static double getDurabilityFraction(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return 0.0;
		if(!stack.isDamageableItem())
			return 1.0;
		int maxDamage = stack.getMaxDamage();
		if(maxDamage <= 0)
			return 1.0;
		int damage = stack.getDamageValue();
		return Mth.clamp((maxDamage - damage) / (double)maxDamage, 0.0, 1.0);
	}
	
	private static int getDurabilityColor(double fraction)
	{
		float clamped = (float)Mth.clamp(fraction, 0.0, 1.0);
		int red = (int)Math.round(255 * (1F - clamped));
		int green = (int)Math.round(255 * clamped);
		return 0xFF000000 | (red << 16) | (green << 8);
	}
	
	private enum HeartType
	{
		CONTAINER("hud/heart/container", "hud/heart/container_blinking",
			"hud/heart/container_hardcore",
			"hud/heart/container_hardcore_blinking"),
		
		NORMAL("hud/heart/full", "hud/heart/full_blinking", "hud/heart/half",
			"hud/heart/half_blinking", "hud/heart/hardcore_full",
			"hud/heart/hardcore_full_blinking", "hud/heart/hardcore_half",
			"hud/heart/hardcore_half_blinking"),
		
		POISONED("hud/heart/poisoned_full", "hud/heart/poisoned_full_blinking",
			"hud/heart/poisoned_half", "hud/heart/poisoned_half_blinking",
			"hud/heart/poisoned_hardcore_full",
			"hud/heart/poisoned_hardcore_full_blinking",
			"hud/heart/poisoned_hardcore_half",
			"hud/heart/poisoned_hardcore_half_blinking"),
		
		WITHERED("hud/heart/withered_full", "hud/heart/withered_full_blinking",
			"hud/heart/withered_half", "hud/heart/withered_half_blinking",
			"hud/heart/withered_hardcore_full",
			"hud/heart/withered_hardcore_full_blinking",
			"hud/heart/withered_hardcore_half",
			"hud/heart/withered_hardcore_half_blinking"),
		
		ABSORBING("hud/heart/absorbing_full",
			"hud/heart/absorbing_full_blinking", "hud/heart/absorbing_half",
			"hud/heart/absorbing_half_blinking",
			"hud/heart/absorbing_hardcore_full",
			"hud/heart/absorbing_hardcore_full_blinking",
			"hud/heart/absorbing_hardcore_half",
			"hud/heart/absorbing_hardcore_half_blinking"),
		
		FROZEN("hud/heart/frozen_full", "hud/heart/frozen_full_blinking",
			"hud/heart/frozen_half", "hud/heart/frozen_half_blinking",
			"hud/heart/frozen_hardcore_full",
			"hud/heart/frozen_hardcore_full_blinking",
			"hud/heart/frozen_hardcore_half",
			"hud/heart/frozen_hardcore_half_blinking");
		
		private final Identifier full;
		private final Identifier fullBlinking;
		private final Identifier half;
		private final Identifier halfBlinking;
		private final Identifier hardcoreFull;
		private final Identifier hardcoreFullBlinking;
		private final Identifier hardcoreHalf;
		private final Identifier hardcoreHalfBlinking;
		
		HeartType(String full, String fullBlinking, String half,
			String halfBlinking, String hardcoreFull,
			String hardcoreFullBlinking, String hardcoreHalf,
			String hardcoreHalfBlinking)
		{
			this.full = Identifier.withDefaultNamespace(full);
			this.fullBlinking = Identifier.withDefaultNamespace(fullBlinking);
			this.half = Identifier.withDefaultNamespace(half);
			this.halfBlinking = Identifier.withDefaultNamespace(halfBlinking);
			this.hardcoreFull = Identifier.withDefaultNamespace(hardcoreFull);
			this.hardcoreFullBlinking =
				Identifier.withDefaultNamespace(hardcoreFullBlinking);
			this.hardcoreHalf = Identifier.withDefaultNamespace(hardcoreHalf);
			this.hardcoreHalfBlinking =
				Identifier.withDefaultNamespace(hardcoreHalfBlinking);
		}
		
		HeartType(String full, String fullBlinking, String hardcoreFull,
			String hardcoreFullBlinking)
		{
			this(full, fullBlinking, full, fullBlinking, hardcoreFull,
				hardcoreFullBlinking, hardcoreFull, hardcoreFullBlinking);
		}
		
		public Identifier getSprite(boolean hardcore, boolean half,
			boolean blink)
		{
			if(!hardcore)
			{
				if(half)
					return blink ? halfBlinking : this.half;
				
				return blink ? fullBlinking : full;
			}
			
			if(half)
				return blink ? hardcoreHalfBlinking : hardcoreHalf;
			
			return blink ? hardcoreFullBlinking : hardcoreFull;
		}
		
		static HeartType forEntity(LivingEntity entity)
		{
			if(entity.hasEffect(MobEffects.POISON))
				return POISONED;
			
			if(entity.hasEffect(MobEffects.WITHER))
				return WITHERED;
			
			if(entity.isFullyFrozen())
				return FROZEN;
			
			return NORMAL;
		}
	}
}
