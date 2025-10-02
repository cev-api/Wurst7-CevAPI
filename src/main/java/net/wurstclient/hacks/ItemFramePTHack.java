/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RotationUtils;

/**
 * Forwards right-clicks on item frames that contain an item to the block
 * behind the frame (e.g. opens a chest behind an item frame). When the
 * player is sneaking the normal item-frame interaction is preserved.
 */
public final class ItemFramePTHack extends Hack implements RightClickListener
{
	public ItemFramePTHack()
	{
		super("ItemFramePT");
		setCategory(Category.ITEMS);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(RightClickListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(RightClickListener.class, this);
	}
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		// Respect vanilla item use cooldown
		if(MC.itemUseCooldown > 0)
			return;
		
		// Only when the use key is pressed
		if(!MC.options.useKey.isPressed())
			return;
		
		HitResult hr = MC.crosshairTarget;
		if(hr == null)
			return;
		
		EntityHitResult ehr = null;
		
		if(hr.getType() == HitResult.Type.ENTITY)
		{
			ehr = (EntityHitResult)hr;
		}else if(hr.getType() == HitResult.Type.BLOCK)
		{
			// If the crosshair target is a block (common when looking at
			// frames),
			// try to find an item frame entity at the hit position.
			BlockHitResult bh = (BlockHitResult)hr;
			Vec3d hitPos = bh.getPos();
			for(Entity e : MC.world.getEntities())
			{
				if(!(e instanceof ItemFrameEntity))
					continue;
				ItemFrameEntity frame = (ItemFrameEntity)e;
				Box box = EntityUtils.getLerpedBox(frame, 0.0f);
				if(box.contains(hitPos))
				{
					ehr = new EntityHitResult(frame, frame.getPos());
					break;
				}
			}
		}
		
		if(ehr == null)
			return;
		
		if(!(ehr.getEntity() instanceof ItemFrameEntity frame))
			return;
		
		ItemStack stack = frame.getHeldItemStack();
		if(stack == null || stack.isEmpty())
			return;
		
		// If the player is sneaking, keep normal behavior
		if(MC.player.isSneaking())
			return;
			
		// Raycast from the player's eyes past the entity hit position to find
		// the underlying block (the block the frame is attached to). We extend
		// the ray a bit past the frame so that the block behind the frame is
		// detected.
		Vec3d eyes = RotationUtils.getEyesPos();
		Vec3d target = ehr.getPos();
		Vec3d look = RotationUtils.getServerLookVec();
		double dist = eyes.distanceTo(target);
		double extend = 1.5; // extend beyond the frame
		Vec3d end = eyes.add(look.multiply(dist + extend));
		BlockHitResult bhit = BlockUtils.raycast(eyes, end);
		if(bhit == null || bhit.getType() != HitResult.Type.BLOCK)
			return;
			
		// If the ray hit the face of the same block that the frame is attached
		// to,
		// use that block position. Otherwise fall back to the ray result.
		// (Mostly for clarity; bhit should already be correct.)
		// Cancel the normal item-frame interaction and interact with the
		// block behind the frame instead.
		event.cancel();
		IMC.getInteractionManager().rightClickBlock(bhit.getBlockPos(),
			bhit.getSide(), bhit.getPos());
	}
}
