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
import net.minecraft.block.AbstractSignBlock;
import net.wurstclient.Category;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RotationUtils;

/**
 * Forwards right-clicks on item frames that contain an item or on signs to the
 * block behind them (e.g. opens a chest behind an item frame or wall sign).
 * When the player is sneaking the normal interaction is preserved.
 *
 * The hack can be configured to apply to frames, signs, or both.
 */
public class SignFramePTHack extends Hack implements RightClickListener
{
	private final CheckboxSetting framesEnabled =
		new CheckboxSetting("Frames", true);
	private final CheckboxSetting signsEnabled =
		new CheckboxSetting("Signs", true);
	
	public SignFramePTHack()
	{
		super("SignFramePT");
		setCategory(Category.ITEMS);
		addSetting(framesEnabled);
		addSetting(signsEnabled);
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
		BlockHitResult bh = null;
		boolean signHit = false;
		
		if(hr.getType() == HitResult.Type.ENTITY)
		{
			ehr = (EntityHitResult)hr;
		}else if(hr.getType() == HitResult.Type.BLOCK)
		{
			// If the crosshair target is a block (common when looking at
			// frames or signs), try to find an item frame entity at the hit
			// position (if frames are enabled). If not found and signs are
			// enabled, check whether the block is a sign.
			bh = (BlockHitResult)hr;
			Vec3d hitPos = bh.getPos();
			if(framesEnabled.isChecked())
			{
				for(Entity e : MC.world.getEntities())
				{
					if(!(e instanceof ItemFrameEntity))
						continue;
					ItemFrameEntity frame = (ItemFrameEntity)e;
					// Only consider frames that actually hold an item
					ItemStack held = frame.getHeldItemStack();
					if(held == null || held.isEmpty())
						continue;
					Box box = EntityUtils.getLerpedBox(frame, 0.0f);
					if(box.contains(hitPos))
					{
						ehr = new EntityHitResult(frame, frame.getPos());
						break;
					}
				}
			}
			if(ehr == null && signsEnabled.isChecked())
			{
				BlockHitResult blockResult = bh;
				if(blockResult != null && blockResult.getBlockPos() != null)
				{
					var pos = blockResult.getBlockPos();
					var state = MC.world.getBlockState(pos);
					if(state.getBlock() instanceof AbstractSignBlock)
					{
						signHit = true;
					}
				}
			}
		}
		
		if(ehr == null && !signHit)
			return;
			
		// If we have an entity hit it must be an item frame and frames must be
		// enabled
		if(ehr != null)
		{
			if(!(ehr.getEntity() instanceof ItemFrameEntity frame))
				return;
			if(!framesEnabled.isChecked())
				return;
			ItemStack stack = frame.getHeldItemStack();
			if(stack == null || stack.isEmpty())
				return;
			// If the player is sneaking (sneak key pressed), keep normal
			// behavior
			if(MC.options.sneakKey.isPressed())
				return;
		}
		
		// For sign hits, also respect sneaking (keep normal interaction)
		if(signHit && MC.options.sneakKey.isPressed())
			return;
			
		// Raycast from the player's eyes past the entity/block hit position to
		// find the underlying block (the block the frame or sign is attached
		// to). We extend the ray a bit past the hit so that the block behind
		// the object is detected.
		Vec3d eyes = RotationUtils.getEyesPos();
		Vec3d target = null;
		if(ehr != null)
			target = ehr.getPos();
		else if(bh != null)
			target = bh.getPos();
		if(target == null)
			return;
		Vec3d look = RotationUtils.getServerLookVec();
		double dist = eyes.distanceTo(target);
		double extend = 1.5; // extend beyond the frame/sign
		Vec3d end = eyes.add(look.multiply(dist + extend));
		BlockHitResult bhit = BlockUtils.raycast(eyes, end);
		if(bhit == null || bhit.getType() != HitResult.Type.BLOCK)
			return;
			
		// Cancel the normal interaction and interact with the block behind
		// the frame/sign instead. Use InteractionSimulator to mirror vanilla
		// behavior and avoid accidentally starting item use (e.g. eating a
		// carrot) after opening the container.
		event.cancel();
		// match vanilla: set item use cooldown before simulating
		MC.itemUseCooldown = 4;
		net.wurstclient.util.InteractionSimulator.rightClickBlock(bhit);
	}
}
