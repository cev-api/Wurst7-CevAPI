/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.gui.Font;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;

@SearchTags({"damage esp", "damage numbers", "damage popups"})
public final class DamageEspHack extends Hack
	implements UpdateListener, RenderListener, PacketInputListener
{
	private static final float DAMAGE_EPSILON = 0.05F;
	
	private final CheckboxSetting playersOnly = new CheckboxSetting(
		"Players only",
		"If on, only show damage numbers for players. If off, show players and"
			+ " mobs.",
		false);
	
	private final SliderSetting textSize = new SliderSetting("Text size",
		"Base size of the damage numbers before distance scaling.", 1.0, 0.25,
		3.0, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting fadeTime = new SliderSetting("Fade time",
		"How long damage numbers stay visible before fading out.", 24.0, 6.0,
		80.0, 1.0, ValueDisplay.DECIMAL);
	
	private final SliderSetting opacity = new SliderSetting("Opacity",
		"How solid the damage numbers look while they are visible.", 100.0,
		10.0, 100.0, 5.0, ValueDisplay.PERCENTAGE);
	
	private final ColorSetting textColor = new ColorSetting("Text color",
		"Color of the floating damage numbers.", new Color(0xFF5555));
	
	private final Map<UUID, Float> trackedHealth = new HashMap<>();
	private final List<DamagePopup> popups = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private static final int HEALTH_DATA_ID = resolveHealthDataId();
	
	public DamageEspHack()
	{
		super("DamageESP");
		setCategory(Category.RENDER);
		addSetting(playersOnly);
		addSetting(textSize);
		addSetting(fadeTime);
		addSetting(opacity);
		addSetting(textColor);
	}
	
	@Override
	protected void onEnable()
	{
		trackedHealth.clear();
		popups.clear();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		trackedHealth.clear();
		popups.clear();
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		if(MC.level == null)
			return;
		
		if(packet instanceof ClientboundSetEntityDataPacket entityData)
			handleEntityDataPacket(entityData);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null)
		{
			trackedHealth.clear();
			popups.clear();
			return;
		}
		
		long now = MC.level.getGameTime();
		Set<UUID> seen = new HashSet<>();
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(!(entity instanceof LivingEntity living) || !shouldTrack(living))
				continue;
			
			UUID id = entity.getUUID();
			seen.add(id);
			
			float currentHealth = getTrackedHealthValue(living);
			trackedHealth.put(id, currentHealth);
		}
		
		trackedHealth.keySet().removeIf(id -> !seen.contains(id));
		popups
			.removeIf(popup -> popup.ageTicks(now) > fadeTime.getValue() + 2.0);
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(MC.level == null || MC.player == null || popups.isEmpty())
			return;
		
		long now = MC.level.getGameTime();
		double fadeTicks = Math.max(1.0, fadeTime.getValue());
		float opacityMultiplier = opacity.getValueF() / 100.0F;
		List<DamagePopup> snapshot = new ArrayList<>(popups);
		for(DamagePopup popup : snapshot)
		{
			double age = popup.ageTicks(now + partialTicks);
			if(age < 0 || age > fadeTicks)
				continue;
			
			float progress = (float)(age / fadeTicks);
			double x = popup.x + popup.driftX * age;
			double y = popup.y + popup.rise * age;
			double z = popup.z + popup.driftZ * age;
			
			float distance =
				(float)RenderUtils.getCameraPos().distanceTo(new Vec3(x, y, z));
			float scale = 0.025F * RenderUtils
				.getCappedWorldLabelScale(textSize.getValueF(), distance);
			scale *= 1.0F + (1.0F - progress) * 0.12F;
			
			int baseColor = textColor.getColorI();
			int baseAlpha = (baseColor >>> 24) & 0xFF;
			if(baseAlpha == 0)
				baseAlpha = 255;
			int alpha = Mth.clamp((int)Math.round(
				baseAlpha * opacityMultiplier * Math.pow(1.0 - progress, 1.35)),
				0, 255);
			int argb = (alpha << 24) | (baseColor & 0x00FFFFFF);
			
			drawWorldLabel(matrices, popup.text, x, y, z, argb, scale);
		}
	}
	
	private boolean shouldTrack(LivingEntity entity)
	{
		if(entity.isRemoved())
			return false;
		
		if(playersOnly.isChecked())
			return entity instanceof Player;
		
		return true;
	}
	
	private float getTrackedHealthValue(LivingEntity entity)
	{
		return Math.max(0F, entity.getHealth());
	}
	
	private void handleEntityDataPacket(ClientboundSetEntityDataPacket packet)
	{
		int entityId = packet.id();
		Entity entity = MC.level.getEntity(entityId);
		if(!(entity instanceof LivingEntity living) || !shouldTrack(living))
			return;
		
		UUID id = entity.getUUID();
		float oldHealth =
			trackedHealth.getOrDefault(id, getTrackedHealthValue(living));
		for(var dataValue : packet.packedItems())
		{
			if(dataValue == null || dataValue.id() != HEALTH_DATA_ID)
				continue;
			
			Object raw = dataValue.value();
			if(!(raw instanceof Number number))
				continue;
			
			float newHealth = Math.max(0F, number.floatValue());
			trackedHealth.put(id, newHealth);
			float damage = oldHealth - newHealth;
			if(damage > DAMAGE_EPSILON)
				spawnPopup(living, damage);
			break;
		}
	}
	
	private void spawnPopup(LivingEntity entity, float damage)
	{
		if(MC.level == null)
			return;
		
		double x = entity.getX();
		double y = entity.getY() + entity.getBbHeight() * 0.62;
		double z = entity.getZ();
		double driftX = random.nextDouble() * 0.04 - 0.02;
		double driftZ = random.nextDouble() * 0.04 - 0.02;
		double rise = 0.045 + random.nextDouble() * 0.02;
		
		popups.add(new DamagePopup(formatDamage(damage), x, y, z, driftX,
			driftZ, rise, MC.level.getGameTime()));
	}
	
	private String formatDamage(float damage)
	{
		if(Math.abs(damage - Math.round(damage)) < 0.05F)
		{
			return "-" + Math.round(damage);
		}
		
		return "-" + String.format(Locale.ROOT, "%.1f", damage);
	}
	
	private void drawWorldLabel(PoseStack matrices, String text, double x,
		double y, double z, int argb, float scale)
	{
		if(text == null || text.isEmpty() || MC.player == null)
			return;
		
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
		matrices.scale(scale, -scale, scale);
		
		Font font = MC.font;
		float halfWidth = font.width(text) / 2F;
		int baseAlpha = (argb >>> 24) & 0xFF;
		int bgAlpha =
			(int)Math.round(MC.options.getBackgroundOpacity(0.10F) * baseAlpha);
		int bg = bgAlpha << 24;
		int stroke = (Math.max(0, Math.min(255, baseAlpha)) << 24) | 0x000000;
		RenderUtils.drawOutlinedTextInBatch(font, text, -halfWidth, 0, argb,
			stroke, matrices.last().pose(), Font.DisplayMode.SEE_THROUGH, bg,
			0xF000F0);
		matrices.popPose();
	}
	
	private record DamagePopup(String text, double x, double y, double z,
		double driftX, double driftZ, double rise, long createdTick)
	{
		private double ageTicks(double currentTick)
		{
			return currentTick - createdTick;
		}
	}
	
	private static int resolveHealthDataId()
	{
		try
		{
			Field field = LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
			field.setAccessible(true);
			EntityDataAccessor<?> accessor =
				(EntityDataAccessor<?>)field.get(null);
			return accessor != null ? accessor.id() : -1;
		}catch(ReflectiveOperationException | RuntimeException e)
		{
			return -1;
		}
	}
}
