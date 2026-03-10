/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashSet;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"pearl intercept", "ender pearl intercept", "counter pearl",
	"anti pearl"})
public final class PearlInterceptHack extends Hack implements UpdateListener
{
	private final SliderSetting maxHorizontalError =
		new SliderSetting("Max horizontal error", 8, 1, 32, 0.5,
			ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting planeTolerance =
		new SliderSetting("Plane tolerance", 4, 0.5, 16, 0.5,
			ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting cooldownTicks = new SliderSetting("Cooldown",
		10, 0, 60, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final CheckboxSetting switchBack =
		new CheckboxSetting("Switch back", true);
	
	private final HashSet<UUID> handledPearls = new HashSet<>();
	private int cooldown;
	private boolean restoreRotationPending;
	private float restoreYaw;
	private float restorePitch;
	
	public PearlInterceptHack()
	{
		super("PearlIntercept");
		setCategory(Category.COMBAT);
		addSetting(maxHorizontalError);
		addSetting(planeTolerance);
		addSetting(cooldownTicks);
		addSetting(switchBack);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		handledPearls.clear();
		cooldown = 0;
		restoreRotationPending = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		if(restoreRotationPending)
		{
			new Rotation(restoreYaw, restorePitch).applyToClientPlayer();
			new Rotation(restoreYaw, restorePitch).sendPlayerLookPacket();
			restoreRotationPending = false;
		}
		
		if(cooldown > 0)
			cooldown--;
		
		HashSet<UUID> present = new HashSet<>();
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(entity.getType() != EntityType.ENDER_PEARL)
				continue;
			
			UUID pearlId = entity.getUUID();
			present.add(pearlId);
			if(handledPearls.contains(pearlId))
				continue;
			
			if(isOwnPearl(entity))
				continue;
			
			if(cooldown > 0)
				continue;
				
			// Freshly spawned pearls can report unstable/zero velocity for a
			// tick, which causes bad predictions. Wait for trajectory data.
			if(entity.tickCount < 2
				|| entity.getDeltaMovement().lengthSqr() < 1e-4)
				continue;
			
			Vec3 target = predictPearlLanding(entity);
			if(target == null || !isSafeLanding(target))
				continue;
			
			ThrowSolution solution = findBestSolution(target);
			if(solution == null)
				continue;
			
			if(throwPearl(solution))
			{
				handledPearls.add(pearlId);
				cooldown = cooldownTicks.getValueI();
			}
		}
		
		handledPearls.retainAll(present);
	}
	
	private boolean isOwnPearl(Entity entity)
	{
		if(!(entity instanceof Projectile projectile))
			return false;
		
		return projectile.getOwner() == MC.player;
	}
	
	private Vec3 predictPearlLanding(Entity pearl)
	{
		Vec3 pos = pearl.position();
		Vec3 vel = pearl.getDeltaMovement();
		int minY = MC.level.getMinY();
		
		for(int i = 0; i < 160; i++)
		{
			Vec3 next = pos.add(vel);
			BlockHitResult hit = BlockUtils.raycast(pos, next);
			if(hit.getType() != HitResult.Type.MISS)
				return hit.getLocation();
			
			pos = next;
			vel = vel.scale(pearl.isInWater() ? 0.8 : 0.99);
			if(!pearl.isNoGravity())
				vel = vel.add(0, -0.03, 0);
			
			if(pos.y < minY - 2)
				return null;
		}
		
		return null;
	}
	
	private ThrowSolution findBestSolution(Vec3 target)
	{
		Rotation needed = RotationUtils.getNeededRotations(target);
		double bestScore = Double.POSITIVE_INFINITY;
		ThrowSolution best = null;
		
		for(double yawOffset = -24; yawOffset <= 24; yawOffset += 2)
		{
			for(double pitchOffset = -26; pitchOffset <= 26; pitchOffset += 2)
			{
				float yaw = (float)(needed.yaw() + yawOffset);
				float pitch = clampPitch((float)(needed.pitch() + pitchOffset));
				Vec3 landing = simulateLanding(yaw, pitch);
				if(landing == null || !isSafeLanding(landing))
					continue;
				
				double horizontal = horizontalDistance(landing, target);
				if(horizontal > maxHorizontalError.getValue())
					continue;
				
				double planeDiff = Math.abs(landing.y - target.y);
				if(planeDiff > planeTolerance.getValue())
					continue;
				
				double score = horizontal + planeDiff * 0.35;
				if(score >= bestScore)
					continue;
				
				bestScore = score;
				best = new ThrowSolution(yaw, pitch, landing);
			}
		}
		
		return best;
	}
	
	private Vec3 simulateLanding(float yaw, float pitch)
	{
		Vec3 pos = RotationUtils.getEyesPos();
		Vec3 vel = new Rotation(yaw, pitch).toLookVec().scale(1.5);
		int minY = MC.level.getMinY();
		
		for(int i = 0; i < 160; i++)
		{
			Vec3 next = pos.add(vel);
			BlockHitResult hit = BlockUtils.raycast(pos, next);
			if(hit.getType() != HitResult.Type.MISS)
				return hit.getLocation();
			
			pos = next;
			vel = vel.scale(0.99).add(0, -0.03, 0);
			if(pos.y < minY - 2)
				return null;
		}
		
		return null;
	}
	
	private boolean throwPearl(ThrowSolution solution)
	{
		if(MC.rightClickDelay > 0)
			return false;
		
		int oldSlot = MC.player.getInventory().getSelectedSlot();
		
		if(!MC.player.getMainHandItem().is(Items.ENDER_PEARL))
		{
			if(!InventoryUtils.selectItem(Items.ENDER_PEARL, 36))
				return false;
		}
		
		if(!MC.player.getMainHandItem().is(Items.ENDER_PEARL))
			return false;
		
		restoreYaw = MC.player.getYRot();
		restorePitch = MC.player.getXRot();
		
		Rotation aim = new Rotation(solution.yaw(), solution.pitch());
		aim.applyToClientPlayer();
		aim.sendPlayerLookPacket();
		IMC.getInteractionManager().rightClickItem();
		
		if(switchBack.isChecked()
			&& MC.player.getInventory().getSelectedSlot() != oldSlot)
			MC.player.getInventory().setSelectedSlot(oldSlot);
		
		restoreRotationPending = true;
		return true;
	}
	
	private boolean isSafeLanding(Vec3 pos)
	{
		if(pos == null || MC.level == null)
			return false;
		
		int minY = MC.level.getMinY();
		if(pos.y <= minY + 1)
			return false;
		
		BlockPos at = BlockPos.containing(pos);
		BlockPos below = BlockPos.containing(pos.x, pos.y - 0.5, pos.z);
		
		if(isLava(at) || isLava(below))
			return false;
		
		return hasSolidSupportBelow(pos, 4);
	}
	
	private boolean isLava(BlockPos pos)
	{
		BlockState state = MC.level.getBlockState(pos);
		return state.getFluidState().is(FluidTags.LAVA);
	}
	
	private boolean hasSolidSupportBelow(Vec3 pos, int maxDrop)
	{
		int minY = MC.level.getMinY();
		int baseY = (int)Math.floor(pos.y) - 1;
		int x = (int)Math.floor(pos.x);
		int z = (int)Math.floor(pos.z);
		
		for(int i = 0; i <= maxDrop; i++)
		{
			int y = baseY - i;
			if(y < minY)
				break;
			
			BlockPos check = new BlockPos(x, y, z);
			BlockState state = MC.level.getBlockState(check);
			if(state.getFluidState().is(FluidTags.LAVA))
				return false;
			if(!state.getCollisionShape(MC.level, check).isEmpty())
				return true;
		}
		
		return false;
	}
	
	private float clampPitch(float pitch)
	{
		return Math.max(-89.9F, Math.min(89.9F, pitch));
	}
	
	private double horizontalDistance(Vec3 a, Vec3 b)
	{
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
	
	private record ThrowSolution(float yaw, float pitch, Vec3 landing)
	{}
}
