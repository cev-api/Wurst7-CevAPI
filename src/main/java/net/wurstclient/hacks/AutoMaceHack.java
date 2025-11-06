/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.EntityUtils;

@SearchTags({"mace", "auto mace", "fall attack"})
public final class AutoMaceHack extends Hack implements UpdateListener
{
	private final SliderSetting minFallDistance =
		new SliderSetting("Min fall distance",
			"Minimum distance you must fall before AutoMace will react.", 3, 1,
			10, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting switchDelayMin =
		new SliderSetting("Switch delay (min)",
			"Shortest delay before AutoMace can switch to your mace.", 100, 0,
			900, 10, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final SliderSetting switchDelayMax =
		new SliderSetting("Switch delay (max)",
			"Longest delay before AutoMace can switch to your mace.", 200, 50,
			1000, 10, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final SliderSetting attackDelayMin =
		new SliderSetting("Attack delay (min)",
			"Shortest delay before AutoMace can swing the mace.", 80, 0, 300,
			10, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final SliderSetting attackDelayMax =
		new SliderSetting("Attack delay (max)",
			"Longest delay before AutoMace can swing the mace.", 150, 50, 500,
			10, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final CheckboxSetting targetPlayers =
		new CheckboxSetting("Target players", true);
	
	private final CheckboxSetting targetMobs =
		new CheckboxSetting("Target mobs", false);
	
	private final CheckboxSetting autoSwitchBack =
		new CheckboxSetting("Auto switch back",
			"Switches back to the previously selected slot afterwards.", true);
	
	private final CheckboxSetting instantSwitchBack =
		new CheckboxSetting("Instant switch back",
			"Switches back immediately if the target is lost mid-air.", true);
	
	private final CheckboxSetting ignorePassiveMobs =
		new CheckboxSetting("Ignore passive mobs", true);
	
	private final CheckboxSetting ignoreInvisible =
		new CheckboxSetting("Ignore invisible entities", true);
	
	private final CheckboxSetting respectCooldown =
		new CheckboxSetting("Respect cooldown",
			"Only attacks once the mace cooldown is ready.", true);
	
	private final CheckboxSetting useAimAssist = new CheckboxSetting(
		"Use AimAssist",
		"Temporarily enables AimAssist while you are falling towards a target.",
		false);
	
	private static final Function<Entity, Vec3d> TOP_HITBOX_AIM =
		AutoMaceHack::getTopAimPoint;
	
	private int previousSlot = -1;
	private boolean hadMaceEquipped;
	private Entity currentTarget;
	private double fallStartY = -1;
	private boolean isInAir;
	private boolean hasSwitchedToMace;
	private long lastSwitchMs;
	private long switchDelayMs;
	private long lastAttackMs;
	private long attackDelayMs;
	private boolean aimAssistTemporarilyEnabled;
	
	public AutoMaceHack()
	{
		super("AutoMace");
		setCategory(Category.COMBAT);
		
		addSetting(minFallDistance);
		addSetting(switchDelayMin);
		addSetting(switchDelayMax);
		addSetting(attackDelayMin);
		addSetting(attackDelayMax);
		addSetting(targetPlayers);
		addSetting(targetMobs);
		addSetting(autoSwitchBack);
		addSetting(instantSwitchBack);
		addSetting(ignorePassiveMobs);
		addSetting(ignoreInvisible);
		addSetting(respectCooldown);
		addSetting(useAimAssist);
	}
	
	@Override
	protected void onEnable()
	{
		resetState();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		if(autoSwitchBack.isChecked() && previousSlot != -1 && !hadMaceEquipped)
			switchToSlot(previousSlot);
		
		updateAimAssist(false, null);
		resetState();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.world == null)
			return;
		
		enforceSliderBounds();
		updateFallTracking();
		updateCurrentTarget();
		
		boolean airborne = !MC.player.isOnGround();
		Vec3d velocity = MC.player.getVelocity();
		boolean falling = velocity.y < -0.1;
		double currentFallDistance = getCurrentFallDistance();
		boolean fallingWindow = airborne && falling
			&& currentFallDistance >= minFallDistance.getValue();
		boolean hasTarget = hasValidTarget(currentTarget);
		
		updateAimAssist(fallingWindow && hasTarget,
			hasTarget ? currentTarget : null);
		
		if(fallingWindow)
		{
			if(hasTarget)
			{
				alignToTargetTop(currentTarget);
				
				if(!isMaceEquipped() && !hasSwitchedToMace && canSwitchWeapon())
				{
					storePreviousSlot();
					hasSwitchedToMace = switchToMace();
				}
				
				if(isMaceEquipped() && shouldAttack())
					attackTarget();
			}else
				handleNoTarget();
		}else if(MC.player.isOnGround())
			handleLanding();
		else if(airborne && !falling)
			handleNotFalling();
	}
	
	private void enforceSliderBounds()
	{
		ensureSliderOrdering(switchDelayMin, switchDelayMax);
		ensureSliderOrdering(attackDelayMin, attackDelayMax);
	}
	
	private void ensureSliderOrdering(SliderSetting minSetting,
		SliderSetting maxSetting)
	{
		double minValue = minSetting.getValue();
		double maxValue = maxSetting.getValue();
		
		if(minValue < maxValue)
			return;
		
		double newMax = minValue + maxSetting.getIncrement();
		if(newMax <= maxSetting.getMaximum())
		{
			maxSetting.setValue(newMax);
			return;
		}
		
		double newMin = maxSetting.getValue() - minSetting.getIncrement();
		newMin = Math.max(newMin, minSetting.getMinimum());
		minSetting.setValue(newMin);
	}
	
	private void updateCurrentTarget()
	{
		if(isTargetCandidate(currentTarget))
			return;
		
		Entity candidate = null;
		if(MC.crosshairTarget != null
			&& MC.crosshairTarget.getType() == HitResult.Type.ENTITY)
		{
			candidate = ((EntityHitResult)MC.crosshairTarget).getEntity();
		}
		
		if(!isTargetCandidate(candidate))
			candidate = findBestFallTarget();
		
		currentTarget = isTargetCandidate(candidate) ? candidate : null;
	}
	
	private void updateFallTracking()
	{
		boolean onGround = MC.player.isOnGround();
		Vec3d velocity = MC.player.getVelocity();
		boolean falling = velocity.y < -0.1;
		double currentY = MC.player.getY();
		
		if(onGround)
		{
			if(isInAir)
			{
				isInAir = false;
				fallStartY = -1;
			}
			return;
		}
		
		if(!isInAir)
		{
			isInAir = true;
			fallStartY = currentY;
			return;
		}
		
		if(falling)
		{
			if(fallStartY == -1 || currentY > fallStartY)
				fallStartY = currentY;
			
			return;
		}
		
		if(velocity.y > 0.1)
			fallStartY =
				Math.max(fallStartY == -1 ? currentY : fallStartY, currentY);
	}
	
	private double getCurrentFallDistance()
	{
		if(!isInAir || fallStartY == -1)
			return 0;
		
		return Math.max(0, fallStartY - MC.player.getY());
	}
	
	private boolean hasValidTarget(Entity entity)
	{
		return isTargetCandidate(entity);
	}
	
	private boolean isTargetCandidate(Entity entity)
	{
		if(entity == null || entity == MC.player)
			return false;
		
		if(!EntityUtils.IS_ATTACKABLE.test(entity))
			return false;
		
		if(!(entity instanceof LivingEntity living) || living.isDead()
			|| !living.isAlive())
			return false;
		
		if(MC.player.isTeammate(entity))
			return false;
		
		if(entity instanceof PlayerEntity)
		{
			if(!targetPlayers.isChecked())
				return false;
		}else
		{
			if(!targetMobs.isChecked())
				return false;
			
			if(ignorePassiveMobs.isChecked() && entity instanceof PassiveEntity)
				return false;
			
			if(entity instanceof TameableEntity tame && tame.isTamed())
				return false;
		}
		
		if(ignoreInvisible.isChecked() && entity.isInvisible())
			return false;
		
		return true;
	}
	
	private boolean canSwitchWeapon()
	{
		long now = System.currentTimeMillis();
		if(now - lastSwitchMs < switchDelayMs)
			return false;
		
		lastSwitchMs = now;
		switchDelayMs =
			nextDelay(switchDelayMin.getValue(), switchDelayMax.getValue());
		return true;
	}
	
	private boolean shouldAttack()
	{
		if(currentTarget == null)
			return false;
		
		if(respectCooldown.isChecked()
			&& MC.player.getAttackCooldownProgress(0) < 0.9F)
			return false;
		
		long now = System.currentTimeMillis();
		if(now - lastAttackMs < attackDelayMs)
			return false;
		
		lastAttackMs = now;
		attackDelayMs =
			nextDelay(attackDelayMin.getValue(), attackDelayMax.getValue());
		return true;
	}
	
	private void attackTarget()
	{
		if(currentTarget == null || MC.interactionManager == null)
			return;
		
		MC.interactionManager.attackEntity(MC.player, currentTarget);
		MC.player.swingHand(Hand.MAIN_HAND);
	}
	
	private void handleLanding()
	{
		isInAir = false;
		fallStartY = -1;
		hasSwitchedToMace = false;
		updateAimAssist(false, null);
		
		if(autoSwitchBack.isChecked() && previousSlot != -1 && !hadMaceEquipped)
			switchToSlot(previousSlot);
		
		previousSlot = -1;
		currentTarget = null;
	}
	
	private void handleNoTarget()
	{
		updateAimAssist(false, null);
		
		if(!autoSwitchBack.isChecked() || !instantSwitchBack.isChecked())
			return;
		
		if(previousSlot == -1 || hadMaceEquipped || !isMaceEquipped())
			return;
		
		switchToSlot(previousSlot);
		previousSlot = -1;
		hasSwitchedToMace = false;
	}
	
	private void handleNotFalling()
	{
		updateAimAssist(false, null);
		handleNoTarget();
	}
	
	private boolean isMaceEquipped()
	{
		ItemStack mainHand = MC.player.getMainHandStack();
		return mainHand.isOf(Items.MACE);
	}
	
	private void storePreviousSlot()
	{
		if(isMaceEquipped())
		{
			hadMaceEquipped = true;
			return;
		}
		
		if(previousSlot == -1)
		{
			previousSlot = MC.player.getInventory().getSelectedSlot();
			hadMaceEquipped = false;
		}
	}
	
	private boolean switchToMace()
	{
		for(int i = 0; i < 9; i++)
		{
			ItemStack stack = MC.player.getInventory().getStack(i);
			if(stack.isOf(Items.MACE))
			{
				MC.player.getInventory().setSelectedSlot(i);
				return true;
			}
		}
		
		return false;
	}
	
	private void switchToSlot(int slot)
	{
		if(slot < 0 || slot >= 9)
			return;
		
		MC.player.getInventory().setSelectedSlot(slot);
	}
	
	private void alignToTargetTop(Entity target)
	{
		if(!useAimAssist.isChecked() || target == null)
			return;
		
		Vec3d topCenter = getTopAimPoint(target);
		
		WURST.getRotationFaker().faceVectorClient(topCenter);
		WURST.getRotationFaker().faceVectorPacket(topCenter);
	}
	
	private Entity findBestFallTarget()
	{
		Vec3d playerPos =
			new Vec3d(MC.player.getX(), MC.player.getY(), MC.player.getZ());
		double maxDistanceSq = 36.0; // 6 blocks
		double maxHorizontal = 5.5;
		
		return EntityUtils.getAttackableEntities()
			.filter(this::isTargetCandidate)
			.filter(e -> MC.player.squaredDistanceTo(e) <= maxDistanceSq)
			.filter(e -> getTopAimPoint(e).y < playerPos.y).filter(e -> {
				Vec3d top = getTopAimPoint(e);
				double dx = top.x - playerPos.x;
				double dz = top.z - playerPos.z;
				double horizontal = Math.hypot(dx, dz);
				return horizontal <= maxHorizontal;
			}).min(Comparator.comparingDouble(e -> {
				Vec3d top = getTopAimPoint(e);
				double verticalDiff = Math.max(0, playerPos.y - top.y - 0.05);
				double dx = top.x - playerPos.x;
				double dz = top.z - playerPos.z;
				double horizontal = Math.hypot(dx, dz);
				return verticalDiff * verticalDiff + horizontal;
			})).orElse(null);
	}
	
	private static Vec3d getTopAimPoint(Entity entity)
	{
		Box box = entity.getBoundingBox();
		double x = (box.minX + box.maxX) * 0.5;
		double y = box.maxY + 0.05;
		double z = (box.minZ + box.maxZ) * 0.5;
		return new Vec3d(x, y, z);
	}
	
	private long nextDelay(double min, double max)
	{
		double minVal = Math.min(min, max);
		double maxVal = Math.max(min, max);
		if(maxVal <= minVal)
			maxVal = minVal + 1;
		
		return Math
			.round(ThreadLocalRandom.current().nextDouble(minVal, maxVal + 1));
	}
	
	private void resetState()
	{
		var aimAssist = WURST.getHax().aimAssistHack;
		aimAssist.setOverrideAimPoint(null);
		aimAssist.clearExternalTarget();
		previousSlot = -1;
		hadMaceEquipped = false;
		currentTarget = null;
		fallStartY = -1;
		isInAir = false;
		hasSwitchedToMace = false;
		lastSwitchMs = 0;
		switchDelayMs = 0;
		lastAttackMs = 0;
		attackDelayMs = 0;
		aimAssistTemporarilyEnabled = false;
	}
	
	private void updateAimAssist(boolean shouldEnable, Entity target)
	{
		if(!useAimAssist.isChecked())
			shouldEnable = false;
		
		AimAssistHack aimAssist = WURST.getHax().aimAssistHack;
		
		if(shouldEnable && target != null)
		{
			aimAssist.setOverrideAimPoint(TOP_HITBOX_AIM);
			aimAssist.setExternalTarget(target);
			
			if(!aimAssist.isEnabled())
			{
				aimAssist.setEnabled(true);
				aimAssistTemporarilyEnabled = true;
			}
			return;
		}
		
		aimAssist.setOverrideAimPoint(null);
		aimAssist.clearExternalTarget();
		
		if(aimAssistTemporarilyEnabled && aimAssist.isEnabled())
			aimAssist.setEnabled(false);
		
		aimAssistTemporarilyEnabled = false;
	}
}
