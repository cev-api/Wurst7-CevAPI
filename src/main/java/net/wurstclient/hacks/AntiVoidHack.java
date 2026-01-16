/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;

@SearchTags({"anti void", "void"})
public final class AntiVoidHack extends Hack implements UpdateListener
{
	private final SliderSetting voidLevel = new SliderSetting("Void level",
		"Y level where the void begins.", 0, -256, 0, 1, ValueDisplay.INTEGER);
	
	private Vec3 lastSafePos;
	
	public AntiVoidHack()
	{
		super("AntiVoid");
		setCategory(Category.MOVEMENT);
		addSetting(voidLevel);
	}
	
	@Override
	protected void onEnable()
	{
		lastSafePos = null;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		lastSafePos = null;
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null || MC.level == null)
			return;
		
		if(player.connection == null)
			return;
		
		if(player.onGround() && !player.isInWater() && !player.isInLava())
			lastSafePos = player.position();
		
		if(lastSafePos == null || player.isFallFlying())
			return;
		
		if(player.getDeltaMovement().y >= 0 || player.fallDistance <= 2F)
			return;
		
		if(!isOverVoid(player))
			return;
		
		player.setDeltaMovement(0, 0, 0);
		player.fallDistance = 0;
		player.setOnGround(true);
		player.setPos(lastSafePos.x, lastSafePos.y, lastSafePos.z);
		
		player.connection.send(
			new ServerboundMovePlayerPacket.Pos(lastSafePos.x, lastSafePos.y,
				lastSafePos.z, true, player.horizontalCollision));
	}
	
	private boolean isOverVoid(LocalPlayer player)
	{
		double voidY = voidLevel.getValue();
		if(player.getY() <= voidY)
			return true;
		
		AABB box = player.getBoundingBox();
		AABB checkBox = box.minmax(box.move(0, voidY - player.getY(), 0));
		
		return BlockUtils.getBlockCollisions(checkBox).findAny().isEmpty();
	}
}
