/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;

public final class EntityHealthRenderer
{
	private static final Random RANDOM = new Random();
	
	private EntityHealthRenderer()
	{}
	
	public static void drawHeartsAtEntity(GuiGraphics context,
		LivingEntity entity, float partialTicks, float yOffsetPx)
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
		
		double distance = WurstClient.MC.gameRenderer.getMainCamera().position()
			.distanceTo(worldPos);
		float scale = getScale(distance);
		drawHeartRow(context, screenX, screenY + yOffsetPx, scale, entity);
	}
	
	private static void drawHeartRow(GuiGraphics context, float centerX,
		float y, float scale, LivingEntity entity)
	{
		float currentHealth = Math.max(0, entity.getHealth());
		float maxHealth = Math.max(1, entity.getMaxHealth());
		float absorption = Math.max(0, entity.getAbsorptionAmount());
		int rawBaseHalfHearts = Math.max(1, Math.round(maxHealth * 2));
		int rawHealthHalfHearts = Math.round(currentHealth * 2);
		int rawAbsorptionHalfHearts = Math.round(absorption * 2);
		int rawTotalHalfHearts = rawBaseHalfHearts + rawAbsorptionHalfHearts;
		
		int slots = Math.max(1, Mth.ceil(rawTotalHalfHearts / 2.0F));
		int displayedHealthHalfHearts;
		int displayedAbsorptionHalfHearts;
		if(rawTotalHalfHearts > 20)
		{
			float ratio = 20F / rawTotalHalfHearts;
			slots = 10;
			displayedHealthHalfHearts =
				Mth.clamp(Math.round(rawHealthHalfHearts * ratio), 0, 20);
			displayedAbsorptionHalfHearts =
				Mth.clamp(Math.round(rawAbsorptionHalfHearts * ratio), 0,
					20 - displayedHealthHalfHearts);
		}else
		{
			displayedHealthHalfHearts =
				Mth.clamp(rawHealthHalfHearts, 0, rawBaseHalfHearts);
			displayedAbsorptionHalfHearts = Mth.clamp(rawAbsorptionHalfHearts,
				0, Math.max(0, rawTotalHalfHearts - displayedHealthHalfHearts));
		}
		
		float heartSize = 9F;
		float spacing = 8F;
		float rowWidth = ((slots - 1) * spacing + heartSize) * scale;
		float x0 = centerX - rowWidth / 2F;
		boolean blink = entity.hurtTime > 0;
		boolean hardcore = WurstClient.MC.level != null
			&& WurstClient.MC.level.getLevelData().isHardcore();
		HeartType heartType = HeartType.forEntity(entity);
		HeartType absorptionType = heartType == HeartType.WITHERED
			? HeartType.WITHERED : HeartType.ABSORBING;
		int regenIndex = -1;
		if(entity.hasEffect(MobEffects.REGENERATION))
			regenIndex = (entity.tickCount
				% Mth.ceil(Math.max(1F, rawBaseHalfHearts / 2F) + 5F));
		
		context.pose().pushMatrix();
		context.pose().translate(x0, y);
		context.pose().scale(scale, scale);
		
		for(int i = slots - 1; i >= 0; i--)
		{
			int x = Math.round(i * spacing);
			int yOffset = 0;
			int halfIndex = i * 2;
			if(displayedHealthHalfHearts + displayedAbsorptionHalfHearts <= 4)
				yOffset += RANDOM.nextInt(2);
			if(i == regenIndex)
				yOffset -= 2;
			
			context.blitSprite(RenderPipelines.GUI_TEXTURED,
				HeartType.CONTAINER.getSprite(hardcore, false, blink), x,
				yOffset, 9, 9);
			
			if(displayedHealthHalfHearts >= halfIndex + 2)
				context.blitSprite(RenderPipelines.GUI_TEXTURED,
					heartType.getSprite(hardcore, false, blink), x, yOffset, 9,
					9);
			else if(displayedHealthHalfHearts == halfIndex + 1)
				context.blitSprite(RenderPipelines.GUI_TEXTURED,
					heartType.getSprite(hardcore, true, blink), x, yOffset, 9,
					9);
			else
			{
				int absorptionIndex = halfIndex - displayedHealthHalfHearts;
				if(absorptionIndex >= 0
					&& displayedAbsorptionHalfHearts >= absorptionIndex + 2)
					context.blitSprite(RenderPipelines.GUI_TEXTURED,
						absorptionType.getSprite(hardcore, false, blink), x,
						yOffset, 9, 9);
				else if(absorptionIndex >= 0
					&& displayedAbsorptionHalfHearts == absorptionIndex + 1)
					context.blitSprite(RenderPipelines.GUI_TEXTURED,
						absorptionType.getSprite(hardcore, true, blink), x,
						yOffset, 9, 9);
			}
		}
		
		context.pose().popMatrix();
	}
	
	private static float getScale(double distance)
	{
		float nameTagScale = 1F;
		if(WurstClient.INSTANCE.getHax().nameTagsHack.isEnabled())
			nameTagScale =
				WurstClient.INSTANCE.getHax().nameTagsHack.getScale();
		
		float scale = 0.7F * nameTagScale;
		
		// Keep hearts reasonable at long range so they don't dominate nametags.
		if(distance > 12)
			scale *= (float)(12.0 / distance);
		
		return Mth.clamp(scale, 0.25F, 1.6F);
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
