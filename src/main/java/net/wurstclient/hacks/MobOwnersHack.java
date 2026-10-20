/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.OwnerResolver;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"mob owners", "pet owners", "projectile owners"})
public final class MobOwnersHack extends Hack
	implements RenderListener, UpdateListener
{
	private final CheckboxSetting projectiles =
		new CheckboxSetting("Projectiles", false);
	private final ColorSetting color = new ColorSetting("Color",
		"Color of projectile owner labels.", new Color(0x55FFFF));
	private final SliderSetting labelScale =
		new SliderSetting("Label scale", "Scale of the owner labels.", 1.0, 0.5,
			2.0, 0.05, ValueDisplay.DECIMAL);
	private final CheckboxSetting showUuidWhenUnknown = new CheckboxSetting(
		"Show UUID when unknown",
		"Shows the owner's shortened UUID when the player name is not known.",
		true);
	private final CheckboxSetting chatAlerts = new CheckboxSetting(
		"Chat alerts",
		"Reports nearby named mobs and mobs with a known owner, including their coordinates, in chat.",
		false);
	
	private final Map<UUID, String> reportedEntities = new LinkedHashMap<>();
	private int scanCooldown;
	
	public MobOwnersHack()
	{
		super("MobOwners",
			"Shows which player owns tamed mobs, horses, and optionally projectiles.",
			false);
		setCategory(Category.RENDER);
		addSetting(projectiles);
		addSetting(color);
		addSetting(labelScale);
		addSetting(showUuidWhenUnknown);
		addSetting(chatAlerts);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		reportedEntities.clear();
		scanCooldown = 0;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		reportedEntities.clear();
		scanCooldown = 0;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || MC.player == null || !chatAlerts.isChecked())
		{
			reportedEntities.clear();
			scanCooldown = 0;
			return;
		}
		
		if(scanCooldown > 0)
		{
			scanCooldown--;
			return;
		}
		scanCooldown = 20;
		
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(entity == null || entity == MC.player || !entity.isAlive()
				|| entity.isRemoved())
				continue;
			
			UUID ownerUuid = getOwnerUuid(entity);
			String customName =
				entity instanceof Mob ? readCustomName(entity) : "";
			if(customName.isEmpty() && ownerUuid == null)
				continue;
			
			String ownerName =
				ownerUuid == null ? null : resolveOwnerName(ownerUuid);
			if(ownerName == null && ownerUuid != null
				&& showUuidWhenUnknown.isChecked())
				ownerName = shortenedUuid(ownerUuid);
			if(customName.isEmpty() && ownerName == null)
				continue;
			
			reportEntity(entity, customName, ownerName, ownerUuid);
		}
	}
	
	private void reportEntity(Entity entity, String customName,
		String ownerName, UUID ownerUuid)
	{
		String entityName =
			customName.isEmpty() ? entityDisplayName(entity) : customName;
		String reportKey = entityName + "\u0000"
			+ (ownerUuid == null ? "" : ownerUuid.toString());
		String previous = reportedEntities.put(entity.getUUID(), reportKey);
		if(reportKey.equals(previous))
			return;
		
		// Bound this session cache in case the player explores continuously.
		while(reportedEntities.size() > 512)
			reportedEntities
				.remove(reportedEntities.keySet().iterator().next());
		
		BlockPos pos = entity.blockPosition();
		String type =
			customName.isEmpty() ? "owned entity " : "named entity \"";
		String displayName =
			customName.isEmpty() ? entityName : customName + "\"";
		String attribution = ownerName == null ? " (namer unavailable)"
			: " (owner: " + ownerName + ")";
		ChatUtils.message("MobOwners: " + type + displayName + attribution
			+ " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
	}
	
	private static String readCustomName(Entity entity)
	{
		if(!entity.hasCustomName() || entity.getCustomName() == null)
			return "";
		
		String text = entity.getCustomName().getString();
		if(text == null)
			return "";
		
		String trimmed = text.trim();
		if(trimmed.isEmpty())
			return "";
		if(trimmed.length() > 80)
			return trimmed.substring(0, 77) + "...";
		return trimmed;
	}
	
	private static String entityDisplayName(Entity entity)
	{
		String name = entity.getName().getString();
		if(name != null && !name.isBlank())
			return name;
		
		String path =
			net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
				.getKey(entity.getType()).getPath();
		return path.replace('_', ' ');
	}
	
	private static String shortenedUuid(UUID uuid)
	{
		String raw = uuid.toString();
		return raw.substring(0, Math.min(8, raw.length()));
	}
	
	public Component addOwnerInfo(Entity entity, Component original)
	{
		Component ownerNameTag = buildOwnerNameTag(entity, original);
		return ownerNameTag == null ? original : ownerNameTag;
	}
	
	public boolean shouldForceMobNametag(Mob mob)
	{
		return buildOwnerNameTag(mob, null) != null;
	}
	
	private Component buildOwnerNameTag(Entity entity, Component original)
	{
		if(!isEnabled())
			return null;
		
		UUID owner = getOwnerUuid(entity);
		if(owner == null)
			return null;
		
		String name = resolveOwnerName(owner);
		if(name == null)
		{
			if(!showUuidWhenUnknown.isChecked())
				return null;
			name = shortenedUuid(owner);
		}
		
		Component base = original == null ? entity.getName() : original;
		return Component.literal(name + "'s ").append(base);
	}
	
	private UUID getOwnerUuid(Entity entity)
	{
		if(entity instanceof AbstractHorse horse)
			return OwnerResolver.getOwnerUuid(horse, projectiles.isChecked());
		return OwnerResolver.getOwnerUuid(entity, projectiles.isChecked());
	}
	
	private String resolveOwnerName(UUID owner)
	{
		String name = OwnerResolver.lookupPlayerName(owner);
		return OwnerResolver.isResolvablePlayerName(name) ? name : null;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.level == null || !projectiles.isChecked())
			return;
		
		int labelColor = color.getColorI();
		float scale = (float)labelScale.getValue();
		
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(!(entity instanceof Projectile projectile))
				continue;
			
			Entity owner = projectile.getOwner();
			UUID ownerUuid = OwnerResolver.getOwnerUuid(projectile);
			if(owner == MC.player || ownerUuid != null && MC.player != null
				&& ownerUuid.equals(MC.player.getUUID()))
				continue;
			
			String label = getProjectileOwnerLabel(projectile, owner);
			if(label == null)
				continue;
			
			AABB box = EntityUtils.getLerpedBox(entity, partialTicks);
			Vec3 center = box.getCenter();
			drawWorldLabel(matrixStack, label, center.x, box.maxY + 0.45,
				center.z, labelColor, scale);
		}
	}
	
	private String getProjectileOwnerLabel(Entity projectile, Entity owner)
	{
		UUID ownerUuid = OwnerResolver.getOwnerUuid(projectile);
		if(ownerUuid != null)
		{
			String name = OwnerResolver.lookupPlayerName(ownerUuid);
			if(OwnerResolver.isResolvablePlayerName(name))
				return "Owner: " + name;
		}
		
		if(owner == null)
			return showUuidWhenUnknown.isChecked() ? "Owner: Unknown" : null;
		if(owner instanceof Player player)
			return "Owner: " + player.getName().getString();
		
		String name = owner.getName().getString();
		if(name == null || name.isBlank())
			name = owner.getType().toShortString();
		return "Owner: " + name;
	}
	
	private void drawWorldLabel(PoseStack matrices, String text, double x,
		double y, double z, int argb, float scale)
	{
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		Vec3 target = new Vec3(x, y, z);
		Vec3 dir = target.subtract(cam);
		double dist = dir.length();
		double lx = x;
		double ly = y;
		double lz = z;
		if(dist > 1.0)
		{
			double anchor = Math.min(dist, 12.0);
			Vec3 anchored = cam.add(dir.scale(anchor / dist));
			lx = anchored.x;
			ly = anchored.y;
			lz = anchored.z;
		}
		matrices.translate(lx - cam.x, ly - cam.y, lz - cam.z);
		
		RenderUtils.applyWorldTextOrientation(matrices);
		
		RenderUtils.mulPose(matrices, Axis.YP.rotationDegrees(180.0F));
		float s = 0.025F * RenderUtils.getCappedWorldLabelScale(scale, dist);
		matrices.scale(s, -s, s);
		
		Font font = MC.font;
		float w = font.width(text) / 2F;
		int baseAlpha = (argb >>> 24) & 0xFF;
		int bgAlpha =
			(int)Math.round(MC.options.getBackgroundOpacity(0.25F) * baseAlpha);
		int bg = bgAlpha << 24;
		int strokeColor =
			(Math.max(0, Math.min(255, baseAlpha)) << 24) | 0x000000;
		var matrix = matrices.last().pose();
		
		net.wurstclient.util.RenderUtils.drawOutlinedTextInBatch(font, text, -w,
			0, argb, strokeColor, matrix, Font.DisplayMode.SEE_THROUGH, bg,
			0xF000F0);
		
		matrices.popPose();
	}
}
