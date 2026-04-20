/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.MouseUpdateListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.AttackSpeedSliderSetting;
import net.wurstclient.settings.AimAtSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.settings.filters.*;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

public final class AimAssistHack extends Hack
	implements UpdateListener, MouseUpdateListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 4.5, 1, 128, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting rotationSpeed =
		new SliderSetting("Rotation Speed", 600, 10, 7200, 10,
			ValueDisplay.DEGREES.withSuffix("/s"));
	
	private final CheckboxSetting lockOn = new CheckboxSetting("Lock-on",
		"Instantly snaps to targets instead of smoothing rotation.", false);
	
	private final SliderSetting fov =
		new SliderSetting("FOV", "description.wurst.setting.aimassist.fov", 120,
			30, 360, 10, ValueDisplay.DEGREES);
	
	private final AimAtSetting aimAt = new AimAtSetting(
		"What point in the target's hitbox AimAssist should aim at.");
	
	private final SliderSetting ignoreMouseInput =
		new SliderSetting("Ignore mouse input",
			"description.wurst.setting.aimassist.ignore_mouse_input", 0, 0, 1,
			0.01, ValueDisplay.PERCENTAGE);
	
	private final CheckboxSetting checkLOS =
		new CheckboxSetting("Check line of sight",
			"description.wurst.setting.aimassist.check_line_of_sight", true);
	
	private final CheckboxSetting aimWhileBlocking =
		new CheckboxSetting("Aim while blocking",
			"description.wurst.setting.aimassist.aim_while_blocking", false);
	
	private final CheckboxSetting rightClickLockOn = new CheckboxSetting(
		"Right-click lock-on",
		"While holding right-click, maintain full horizontal + vertical lock-on to the current target.",
		false);
	
	private final CheckboxSetting rightClickLockOnRangeOverride =
		new CheckboxSetting("Right-click range override",
			"While holding right-click lock-on, use a separate range instead of AimAssist's normal range.",
			false);
	
	private final SliderSetting rightClickLockOnRange = new SliderSetting(
		"Right-click range", "Range used while right-click lock-on is active.",
		12, 1, 128, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting rightClickAutoAttack = new CheckboxSetting(
		"Right-click auto-attack",
		"Automatically attack while right-click lock-on is active. Uses crosshair target first, then AimAssist target.",
		false);
	
	private final AttackSpeedSliderSetting rightClickAttackSpeed =
		new AttackSpeedSliderSetting();
	
	private final EntityFilterList entityFilters =
		new EntityFilterList(FilterPlayersSetting.genericCombat(false),
			FilterSleepingSetting.genericCombat(false),
			FilterFlyingSetting.genericCombat(0),
			FilterHostileSetting.genericCombat(false),
			FilterNeutralSetting
				.genericCombat(AttackDetectingEntityFilter.Mode.OFF),
			FilterPassiveSetting.genericCombat(true),
			FilterPassiveWaterSetting.genericCombat(true),
			FilterBabiesSetting.genericCombat(true),
			FilterBatsSetting.genericCombat(true),
			FilterSlimesSetting.genericCombat(true),
			FilterPetsSetting.genericCombat(true),
			FilterVillagersSetting.genericCombat(true),
			FilterZombieVillagersSetting.genericCombat(true),
			FilterGolemsSetting.genericCombat(false),
			FilterPiglinsSetting
				.genericCombat(AttackDetectingEntityFilter.Mode.OFF),
			FilterZombiePiglinsSetting
				.genericCombat(AttackDetectingEntityFilter.Mode.OFF),
			FilterEndermenSetting
				.genericCombat(AttackDetectingEntityFilter.Mode.OFF),
			FilterShulkersSetting.genericCombat(false),
			FilterInvisibleSetting.genericCombat(true),
			FilterNamedSetting.genericCombat(false),
			FilterShulkerBulletSetting.genericCombat(false),
			FilterArmorStandsSetting.genericCombat(true),
			FilterCrystalsSetting.genericCombat(true));
	
	private Entity target;
	private float nextYaw;
	private float nextPitch;
	private Function<Entity, Vec3> overrideAimPoint;
	private Entity externalTarget;
	private boolean temporaryAllowBlocking;
	private Double rangeOverride;
	private Boolean lockOnOverride;
	
	public AimAssistHack()
	{
		super("AimAssist");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(rotationSpeed);
		addSetting(lockOn);
		addSetting(fov);
		addSetting(aimAt);
		addSetting(ignoreMouseInput);
		addSetting(checkLOS);
		addSetting(aimWhileBlocking);
		addSetting(rightClickLockOn);
		addSetting(rightClickLockOnRangeOverride);
		addSetting(rightClickLockOnRange);
		addSetting(rightClickAutoAttack);
		addSetting(rightClickAttackSpeed);
		
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		// disable incompatible hacks
		WURST.getHax().autoFishHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		rightClickAttackSpeed.resetTimer();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(MouseUpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(MouseUpdateListener.class, this);
		target = null;
		overrideAimPoint = null;
		externalTarget = null;
		temporaryAllowBlocking = false;
		rangeOverride = null;
		lockOnOverride = null;
	}
	
	@Override
	public void onUpdate()
	{
		target = null;
		rightClickAttackSpeed.updateTimer();
		
		// don't aim when a container/inventory screen is open
		if(MC.screen instanceof AbstractContainerScreen)
			return;
		
		boolean useHeld = isUseKeyHeld();
		boolean blockingAllowed =
			aimWhileBlocking.isChecked() || temporaryAllowBlocking || useHeld;
		if(!blockingAllowed && MC.player.isUsingItem())
			return;
		
		Entity forced = externalTarget;
		if(forced != null && !isValidForcedTarget(forced))
			externalTarget = forced = null;
		
		if(forced != null)
			target = forced;
		else
			chooseTarget();
		
		if(target == null)
			return;
		
		Vec3 hitVec = getAimPoint(target);
		if(checkLOS.isChecked() && !BlockUtils.hasLineOfSight(hitVec))
		{
			target = null;
			return;
		}
		
		updateRightClickVerticalAlignment();
		updateRightClickAutoAttack();
		
		WURST.getHax().autoSwordHack.setSlot(target);
		
		// get needed rotation
		Rotation needed = RotationUtils.getNeededRotations(hitVec);
		
		// turn towards center of boundingBox
		if(isLockOnEnabled())
		{
			needed.applyToClientPlayer();
			needed.sendPlayerLookPacket();
			nextYaw = needed.yaw();
			nextPitch = needed.pitch();
			return;
		}
		
		Rotation next = RotationUtils.slowlyTurnTowards(needed,
			rotationSpeed.getValueI() / 20F);
		nextYaw = next.yaw();
		nextPitch = next.pitch();
	}
	
	public void setTemporarilyAllowBlocking(boolean allow)
	{
		temporaryAllowBlocking = allow;
	}
	
	public void setRangeOverride(Double override)
	{
		rangeOverride = override;
	}
	
	public void setLockOnOverride(Boolean override)
	{
		lockOnOverride = override;
	}
	
	private void chooseTarget()
	{
		Stream<Entity> stream = EntityUtils.getAttackableEntities();
		
		double rangeSq = getRangeSq();
		stream =
			stream.filter(e -> EntityUtils.distanceToHitboxSq(e) <= rangeSq);
		
		if(fov.getValue() < 360.0)
			stream = stream.filter(e -> RotationUtils
				.getAngleToLookVec(getAimPoint(e)) <= fov.getValue() / 2.0);
		
		stream = entityFilters.applyTo(stream);
		
		target = stream
			.min(Comparator.comparingDouble(
				e -> RotationUtils.getAngleToLookVec(getAimPoint(e))))
			.orElse(null);
	}
	
	@Override
	public void onMouseUpdate(MouseUpdateEvent event)
	{
		if(target == null || MC.player == null)
			return;
		
		if(isLockOnEnabled())
		{
			event.setDeltaX(0);
			event.setDeltaY(0);
			return;
		}
		
		float curYaw = MC.player.getYRot();
		float curPitch = MC.player.getXRot();
		int diffYaw = (int)(nextYaw - curYaw);
		int diffPitch = (int)(nextPitch - curPitch);
		
		// If we are <1 degree off but still missing the hitbox,
		// slightly exaggerate the difference to fix that.
		if(diffYaw == 0 && diffPitch == 0
			&& !RotationUtils.isFacingBox(target.getBoundingBox(), getRange()))
		{
			diffYaw = nextYaw < curYaw ? -1 : 1;
			diffPitch = nextPitch < curPitch ? -1 : 1;
		}
		
		double inputFactor = 1 - ignoreMouseInput.getValue();
		int mouseInputX = (int)(event.getDefaultDeltaX() * inputFactor);
		int mouseInputY = (int)(event.getDefaultDeltaY() * inputFactor);
		
		event.setDeltaX(mouseInputX + diffYaw);
		event.setDeltaY(mouseInputY + diffPitch);
	}
	
	private Vec3 getAimPoint(Entity entity)
	{
		if(overrideAimPoint != null)
			return overrideAimPoint.apply(entity);
		
		return aimAt.getAimPoint(entity);
	}
	
	public void setOverrideAimPoint(Function<Entity, Vec3> override)
	{
		overrideAimPoint = override;
	}
	
	public void setExternalTarget(Entity entity)
	{
		externalTarget = entity;
	}
	
	public void clearExternalTarget()
	{
		externalTarget = null;
	}
	
	public Entity getCurrentTarget()
	{
		return target;
	}
	
	private double getRange()
	{
		var spearAssist = WURST.getHax().spearAssistHack;
		if(spearAssist != null)
		{
			Double spearRange = spearAssist.getAimAssistRangeOverride();
			if(spearRange != null)
				return spearRange;
		}
		
		if(isRightClickLockOnActive()
			&& rightClickLockOnRangeOverride.isChecked())
			return rightClickLockOnRange.getValue();
		
		return rangeOverride != null ? rangeOverride : range.getValue();
	}
	
	private boolean isLockOnEnabled()
	{
		if(isRightClickLockOnActive())
			return true;
		
		return lockOnOverride != null ? lockOnOverride.booleanValue()
			: lockOn.isChecked();
	}
	
	private boolean isRightClickLockOnActive()
	{
		return rightClickLockOn.isChecked() && isUseKeyHeld();
	}
	
	private boolean isUseKeyHeld()
	{
		return MC.options != null && MC.options.keyUse != null
			&& MC.options.keyUse.isDown();
	}
	
	private double getRangeSq()
	{
		double value = getRange();
		return value * value;
	}
	
	private boolean isValidForcedTarget(Entity entity)
	{
		if(entity == null)
			return false;
		
		if(entity.isRemoved())
			return false;
		
		if(entity instanceof LivingEntity living && !living.isAlive())
			return false;
		
		if(!EntityUtils.IS_ATTACKABLE.test(entity))
			return false;
		
		return true;
	}
	
	private void updateRightClickAutoAttack()
	{
		if(!rightClickAutoAttack.isChecked() || !isRightClickLockOnActive()
			|| target == null || MC.player == null || MC.gameMode == null)
			return;
		
		if(!rightClickAttackSpeed.isTimeToAttack())
			return;
		
		Entity attackTarget = resolveRightClickAttackTarget();
		if(attackTarget == null)
			return;
		
		WURST.getHax().autoSwordHack.setSlot(attackTarget);
		MC.gameMode.attack(MC.player, attackTarget);
		MC.player.swing(InteractionHand.MAIN_HAND);
		rightClickAttackSpeed.resetTimer();
	}
	
	private Entity resolveRightClickAttackTarget()
	{
		if(MC.hitResult instanceof EntityHitResult hit)
		{
			Entity hitEntity = hit.getEntity();
			if(hitEntity != null && isValidForcedTarget(hitEntity)
				&& MC.player.distanceToSqr(hitEntity) <= getRangeSq())
				return hitEntity;
		}
		
		return target;
	}
	
	private void updateRightClickVerticalAlignment()
	{
		if(WURST.getHax().flightHack.isEnabled())
			return;
		
		Double step = getRightClickVerticalAlignmentStepInternal(null);
		if(step == null || MC.player == null)
			return;
		
		Vec3 motion = MC.player.getDeltaMovement();
		MC.player.setDeltaMovement(motion.x, motion.y + step, motion.z);
	}
	
	public Double getRightClickVerticalAlignmentStepForFlight()
	{
		return getRightClickVerticalAlignmentStepInternal(
			WURST.getHax().flightHack.verticalSpeed.getValue());
	}
	
	private Double getRightClickVerticalAlignmentStepInternal(
		Double maxStepOverride)
	{
		if(MC.player == null || target == null || !isRightClickLockOnActive())
			return null;
		
		boolean flying = MC.player.getAbilities().flying
			|| WURST.getHax().flightHack.isEnabled();
		if(!flying)
			return null;
		
		if(target.onGround() && !MC.options.keyShift.isDown())
		{
			double feetDelta = target.getY() - MC.player.getY();
			if(feetDelta < 0)
				return null;
		}
		
		double maxStep = maxStepOverride != null ? maxStepOverride
			: MC.player.getAbilities().getFlyingSpeed();
		if(maxStep <= 0)
			return null;
		
		double targetY = target.getY();
		double playerY = MC.player.getY();
		double delta = targetY - playerY;
		
		if(MC.player.onGround() && delta < 0 && !MC.options.keyShift.isDown())
			return null;
		
		if(Math.abs(delta) < 0.02)
			return null;
		
		return Math.max(-maxStep, Math.min(maxStep, delta));
	}
}
