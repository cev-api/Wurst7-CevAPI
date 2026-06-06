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
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
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
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"projectile owner", "projectile owners", "owner esp",
	"projectile esp owner"})
public final class ProjectileOwnerHack extends Hack implements RenderListener
{
	private final ColorSetting color = new ColorSetting("Color",
		"Color of projectile owner labels.", new Color(0x55FFFF));
	private final CheckboxSetting showUnknown =
		new CheckboxSetting("Show unknown owners",
			"Show labels for projectiles whose owner is not known client-side.",
			false);
	private final SliderSetting labelScale =
		new SliderSetting("Label scale", "Scale of the owner labels.", 1.0, 0.5,
			2.0, 0.05, ValueDisplay.DECIMAL);
	
	public ProjectileOwnerHack()
	{
		super("ProjectileOwner");
		setCategory(Category.RENDER);
		addSetting(color);
		addSetting(showUnknown);
		addSetting(labelScale);
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
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.level == null)
			return;
		
		int labelColor = color.getColorI();
		float scale = (float)labelScale.getValue();
		
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(!(entity instanceof Projectile projectile))
				continue;
			
			Entity owner = projectile.getOwner();
			if(owner == MC.player)
				continue;
			
			String label = getOwnerLabel(owner);
			if(label == null)
				continue;
			
			AABB box = EntityUtils.getLerpedBox(entity, partialTicks);
			Vec3 center = box.getCenter();
			drawWorldLabel(matrixStack, label, center.x, box.maxY + 0.45,
				center.z, labelColor, scale);
		}
	}
	
	private String getOwnerLabel(Entity owner)
	{
		if(owner == null)
			return showUnknown.isChecked() ? "Owner: Unknown" : null;
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
