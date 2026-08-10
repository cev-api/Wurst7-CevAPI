/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.util.RenderUtils;

/** Rendering is isolated so automation never depends on ESP implementation. */
public final class ContainerRenderer
{
	public void render(PoseStack matrices, Collection<LogicalContainer> sources,
		Collection<DestinationRule> destinations, LogicalContainer target,
		Set<LogicalContainer> completed, Set<LogicalContainer> unmatched,
		Set<LogicalContainer> unreachable, Set<LogicalContainer> unloaded,
		Minecraft mc, boolean renderLabels, double labelRange)
	{
		for(LogicalContainer source : sources)
		{
			int color = source.equals(target) ? 0x50FFFF00
				: unreachable.contains(source) ? 0x50FF0000
					: unloaded.contains(source) ? 0x50808080
						: unmatched.contains(source) ? 0x50FF8000
							: completed.contains(source) ? 0x5000FF00
								: 0x500000FF;
			draw(matrices, source, color);
		}
		for(DestinationRule destination : destinations)
		{
			if(!destination.isConfigured())
				continue;
			int color = destination.isFull() || destination.isUnreachable()
				? 0x50FF0000
				: destination.isTemporarilyUnavailable()
					|| unloaded.contains(destination.getContainer())
						? 0x50808080 : destination.getContainer().equals(target)
							? 0x50FFFF00 : 0x508000FF;
			draw(matrices, destination.getContainer(), color);
			if(renderLabels && mc.player != null
				&& mc.player.distanceToSqr(Vec3.atCenterOf(
					destination.getContainer().anchor())) <= labelRange
						* labelRange)
				drawLabel(matrices, mc, destination);
		}
	}
	
	private void draw(PoseStack matrices, LogicalContainer container, int color)
	{
		AABB box = new AABB(container.anchor()).deflate(1 / 16.0);
		RenderUtils.drawSolidBox(matrices, box, color, false);
		RenderUtils.drawOutlinedBox(matrices, box, color | 0x80000000, false);
	}
	
	private void drawLabel(PoseStack matrices, Minecraft mc,
		DestinationRule destination)
	{
		String label = destination.getFilters().stream()
			.map(ItemFilter::getDisplayName).collect(Collectors.joining(", "));
		if(label.isEmpty())
			return;
		matrices.pushPose();
		Vec3 pos = Vec3.atCenterOf(destination.getContainer().anchor());
		Vec3 camera = RenderUtils.getCameraPos();
		matrices.translate(pos.x - camera.x, pos.y + 0.8 - camera.y,
			pos.z - camera.z);
		var cameraEntity = mc.getCameraEntity();
		if(cameraEntity != null)
		{
			matrices.mulPose(Axis.YP.rotationDegrees(-cameraEntity.getYRot()));
			matrices.mulPose(Axis.XP.rotationDegrees(cameraEntity.getXRot()));
		}
		matrices.mulPose(Axis.YP.rotationDegrees(180));
		matrices.scale(0.022F, -0.022F, 0.022F);
		float halfWidth = mc.font.width(label) / 2F;
		RenderUtils.drawOutlinedTextInBatch(mc.font, label, -halfWidth, 0,
			0xFFE0C0FF, 0xFF000000, matrices.last().pose(),
			Font.DisplayMode.SEE_THROUGH, 0x40000000, 0xF000F0);
		matrices.popPose();
	}
}
