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
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
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
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.OwnerResolver;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"mob owners", "pet owners", "projectile owners"})
public final class MobOwnersHack extends Hack implements RenderListener
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
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(RenderListener.class, this);
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
			String raw = owner.toString();
			name = raw.substring(0, Math.min(8, raw.length()));
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
		return OwnerResolver.lookupPlayerName(owner);
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
			if(name != null)
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
		
		Font font = MC.font;
		MultiBufferSource.BufferSource vcp = RenderUtils.getVCP();
		float w = font.width(text) / 2F;
		int baseAlpha = (argb >>> 24) & 0xFF;
		int bgAlpha =
			(int)Math.round(MC.options.getBackgroundOpacity(0.25F) * baseAlpha);
		int bg = bgAlpha << 24;
		int strokeColor =
			(Math.max(0, Math.min(255, baseAlpha)) << 24) | 0x000000;
		var matrix = matrices.last().pose();
		
		font.drawInBatch(text, -w - 1, 0, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w + 1, 0, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w, -1, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w, 1, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w, 0, argb, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, bg, 0xF000F0);
		
		vcp.endBatch();
		matrices.popPose();
	}
}
