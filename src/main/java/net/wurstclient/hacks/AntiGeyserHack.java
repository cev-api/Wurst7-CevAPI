/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

/** Prevents the upward relative teleport used by 26.2 Potent Sulfur geysers. */
@SearchTags({"anti geyser", "no geyser push", "geyser", "potent sulfur"})
public final class AntiGeyserHack extends Hack implements PacketInputListener
{
	private final CheckboxSetting hideGeyserParticles = new CheckboxSetting(
		"Hide geyser particles", "Hide Potent Sulfur geyser particles.", false);
	
	public AntiGeyserHack()
	{
		super("AntiGeyser");
		setCategory(Category.MOVEMENT);
		addSetting(hideGeyserParticles);
	}
	
	public boolean isHidingGeysers()
	{
		return isEnabled() && hideGeyserParticles.isChecked();
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PacketInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		if(!(packet instanceof ClientboundSetEntityMotionPacket motion)
			|| MC.player == null || motion.id() != MC.player.getId())
			return;
		
		Vec3 movement = motion.movement();
		if(movement.y > 0.0 && isOverPotentSulfur(MC.player))
			event.cancel();
	}
	
	public boolean shouldCancelRelativeTeleport(Entity entity, double x,
		double y, double z)
	{
		return isEnabled() && entity == MC.player && x == 0.0 && z == 0.0
			&& Math.abs(y - 0.2) < 1.0E-6;
	}
	
	private boolean isOverPotentSulfur(Entity entity)
	{
		if(MC.level == null)
			return false;
		
		BlockPos playerPos = entity.blockPosition();
		for(int i = 1; i <= 6; i++)
			if(MC.level.getBlockState(playerPos.below(i))
				.is(Blocks.POTENT_SULFUR))
				return true;
			
		return false;
	}
}
