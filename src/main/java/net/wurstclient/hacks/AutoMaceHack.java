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
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
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
	
	private final SliderSetting slamDelayMs = new SliderSetting("Slam delay",
		"Extra time to wait after all conditions are met before swinging.\n"
			+ "Helps time the slam right before landing.",
		60, 0, 200, 5, ValueDisplay.INTEGER.withSuffix("ms"));
	
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
	
	private final CheckboxSetting useMaceDmg = new CheckboxSetting(
		"Use MaceDMG", "Enables the MaceDMG hack for guaranteed slam damage. "
			+ "Skips fall distance checks.",
		false);
	
	private final CheckboxSetting debugLogs = new CheckboxSetting("Debug logs",
		"Prints status messages that can help diagnose timing issues.", false);
	
	private static final Function<Entity, Vec3> TOP_HITBOX_AIM =
		AutoMaceHack::getTopAimPoint;
	private static final long POST_SWITCH_ATTACK_BUFFER_MS = 75;
	
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
	private long lastLandingLogMs;
	private boolean landingLogged;
	private boolean attackedThisFall;
	private Entity scheduledTarget;
	private long scheduledAttackTime;
	private boolean maceDmgForced;
	
	public AutoMaceHack()
	{
		super("AutoMace");
		setCategory(Category.COMBAT);
		
		addSetting(minFallDistance);
		addSetting(switchDelayMin);
		addSetting(switchDelayMax);
		addSetting(attackDelayMin);
		addSetting(attackDelayMax);
		addSetting(slamDelayMs);
		addSetting(targetPlayers);
		addSetting(targetMobs);
		addSetting(autoSwitchBack);
		addSetting(instantSwitchBack);
		addSetting(ignorePassiveMobs);
		addSetting(ignoreInvisible);
		addSetting(respectCooldown);
		addSetting(useAimAssist);
		addSetting(useMaceDmg);
		addSetting(debugLogs);
	}
	
	@Override
	protected void onEnable()
	{
		resetState();
		EVENTS.add(UpdateListener.class, this);
		updateMaceDmgState();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		if(autoSwitchBack.isChecked() && previousSlot != -1 && !hadMaceEquipped)
			switchToSlot(previousSlot);
		
		updateAimAssist(false, null);
		disableForcedMaceDmg();
		clearScheduledAttack();
		resetState();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		enforceSliderBounds();
		updateFallTracking();
		updateCurrentTarget();
		
		boolean airborne = !MC.player.onGround();
		Vec3 velocity = MC.player.getDeltaMovement();
		boolean falling = velocity.y < -0.1;
		double currentFallDistance = getCurrentFallDistance();
		boolean fallingWindow = airborne && falling
			&& currentFallDistance >= minFallDistance.getValue();
		if(airborne)
			landingLogged = false;
		boolean hasTarget = hasValidTarget(currentTarget);
		
		if(airborne && falling && !isMaceEquipped() && !hasSwitchedToMace
			&& currentFallDistance >= Math.max(1,
				minFallDistance.getValue() * 0.4)
			&& canSwitchWeapon())
		{
			storePreviousSlot();
			hasSwitchedToMace = switchToMace();
		}
		
		updateAimAssist(fallingWindow && hasTarget,
			hasTarget ? currentTarget : null);
		processScheduledAttack(fallingWindow && hasTarget);
		
		if(fallingWindow)
		{
			if(hasTarget)
			{
				logDebug("Falling - target acquired: "
					+ currentTarget.getName().getString());
				alignToTargetTop(currentTarget);
				
				if(isMaceEquipped())
					tryScheduleAttack();
			}else
				handleNoTarget();
			
		}else if(MC.player.onGround())
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
		if(MC.hitResult != null
			&& MC.hitResult.getType() == HitResult.Type.ENTITY)
		{
			candidate = ((EntityHitResult)MC.hitResult).getEntity();
		}
		
		if(!isTargetCandidate(candidate) || !isFallReachable(candidate))
			candidate = findBestFallTarget();
		
		currentTarget =
			isTargetCandidate(candidate) && isFallReachable(candidate)
				? candidate : null;
	}
	
	private void updateFallTracking()
	{
		boolean onGround = MC.player.onGround();
		Vec3 velocity = MC.player.getDeltaMovement();
		boolean falling = velocity.y < -0.1;
		double currentY = MC.player.getY();
		
		if(onGround)
		{
			if(isInAir)
			{
				isInAir = false;
				fallStartY = -1;
				attackedThisFall = false;
			}
			return;
		}
		
		if(!isInAir)
		{
			isInAir = true;
			fallStartY = currentY;
			attackedThisFall = false;
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
		
		if(!(entity instanceof LivingEntity living) || living.isDeadOrDying()
			|| !living.isAlive())
			return false;
		
		if(MC.player.isAlliedTo(entity))
			return false;
		
		if(entity instanceof Player)
		{
			if(!targetPlayers.isChecked())
				return false;
		}else
		{
			if(!targetMobs.isChecked())
				return false;
			
			if(ignorePassiveMobs.isChecked() && entity instanceof AgeableMob)
				return false;
			
			if(entity instanceof TamableAnimal tame && tame.isTame())
				return false;
		}
		
		if(ignoreInvisible.isChecked() && entity.isInvisible())
			return false;
		
		return true;
	}
	
	private boolean canSwitchWeapon()
	{
		long now = System.currentTimeMillis();
		return now - lastSwitchMs >= switchDelayMs;
	}
	
	private boolean shouldAttack()
	{
		boolean cheatMode = useMaceDmg.isChecked();
		if(currentTarget == null)
		{
			logSkip("no target");
			return false;
		}
		
		if(respectCooldown.isChecked()
			&& MC.player.getAttackStrengthScale(0) < 0.75F)
		{
			logSkip("cooldown="
				+ String.format("%.2f", MC.player.getAttackStrengthScale(0)));
			return false;
		}
		
		long now = System.currentTimeMillis();
		if(now - lastAttackMs < attackDelayMs)
		{
			logSkip("attack delay remaining "
				+ (attackDelayMs - (now - lastAttackMs)) + "ms");
			return false;
		}
		
		if(hasSwitchedToMace
			&& now - lastSwitchMs < POST_SWITCH_ATTACK_BUFFER_MS)
		{
			logSkip("slot sync buffer " + (now - lastSwitchMs) + "ms");
			return false;
		}
		
		if(!isWithinAttackRange(currentTarget))
		{
			logSkip("out of reach");
			return false;
		}
		
		if(attackedThisFall)
		{
			logSkip("already attacked this fall");
			return false;
		}
		
		if(!cheatMode)
		{
			float minCritFall =
				(float)Mth.clamp(minFallDistance.getValue() * 0.8F, 1.2F,
					Math.max(2.0F, minFallDistance.getValue() + 0.4F));
			if(MC.player.fallDistance < minCritFall)
			{
				logSkip("fall distance "
					+ String.format("%.2f", MC.player.fallDistance) + " < min "
					+ String.format("%.2f", minCritFall));
				return false;
			}
		}
		
		lastAttackMs = now;
		attackDelayMs =
			nextDelay(attackDelayMin.getValue(), attackDelayMax.getValue());
		return true;
	}
	
	private void attackTarget(Entity target)
	{
		if(target == null || MC.gameMode == null)
			return;
		
		currentTarget = target;
		MC.gameMode.attack(MC.player, target);
		MC.player.swing(InteractionHand.MAIN_HAND);
		MC.player.resetAttackStrengthTicker();
		attackedThisFall = true;
		double vertical = MC.player.getY() - target.getBoundingBox().maxY;
		logDebug("Attacked target " + target.getName().getString()
			+ " fallDist=" + String.format("%.2f", MC.player.fallDistance)
			+ " vertDiff=" + String.format("%.2f", vertical) + " horDist="
			+ String.format("%.2f", Math.hypot(MC.player.getX() - target.getX(),
				MC.player.getZ() - target.getZ())));
	}
	
	private void tryScheduleAttack()
	{
		if(currentTarget == null || scheduledTarget != null)
			return;
		
		if(!shouldAttack())
			return;
		
		scheduleAttack(currentTarget);
	}
	
	private void scheduleAttack(Entity target)
	{
		if(target == null)
			return;
		
		long delay = useMaceDmg.isChecked() ? 0L
			: Math.max(0L, Math.round(slamDelayMs.getValue()));
		
		if(delay <= 0)
		{
			attackTarget(target);
			return;
		}
		
		scheduledTarget = target;
		scheduledAttackTime = System.currentTimeMillis() + delay;
		logDebug("Scheduled attack in " + delay + "ms");
	}
	
	private void processScheduledAttack(boolean canAttackNow)
	{
		if(scheduledTarget == null)
			return;
		
		if(!canAttackNow || !hasValidTarget(scheduledTarget))
		{
			logDebug("Cancelled scheduled attack");
			clearScheduledAttack();
			return;
		}
		
		if(System.currentTimeMillis() < scheduledAttackTime)
			return;
		
		if(!useMaceDmg.isChecked() && !isWithinAttackRange(scheduledTarget))
		{
			scheduledAttackTime = System.currentTimeMillis() + 10;
			return;
		}
		
		attackTarget(scheduledTarget);
		clearScheduledAttack();
	}
	
	private void handleLanding()
	{
		isInAir = false;
		fallStartY = -1;
		hasSwitchedToMace = false;
		updateAimAssist(false, null);
		clearScheduledAttack();
		attackedThisFall = false;
		if(!landingLogged)
		{
			logLandingState("Landing - resetting state");
			landingLogged = true;
		}
		
		if(autoSwitchBack.isChecked() && previousSlot != -1 && !hadMaceEquipped)
			switchToSlot(previousSlot);
		
		previousSlot = -1;
		currentTarget = null;
	}
	
	private void handleNoTarget()
	{
		updateAimAssist(false, null);
		clearScheduledAttack();
		
		if(!autoSwitchBack.isChecked() || !instantSwitchBack.isChecked())
			return;
		
		if(previousSlot == -1 || hadMaceEquipped || !isMaceEquipped())
			return;
		
		switchToSlot(previousSlot);
		previousSlot = -1;
		hasSwitchedToMace = false;
		logDebug("Lost target - switching back to slot "
			+ slotDescription(previousSlot));
	}
	
	private void handleNotFalling()
	{
		updateAimAssist(false, null);
		handleNoTarget();
	}
	
	private boolean isMaceEquipped()
	{
		ItemStack mainHand = MC.player.getMainHandItem();
		return mainHand.is(Items.MACE);
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
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(stack.is(Items.MACE))
			{
				setSelectedHotbarSlot(i);
				hasSwitchedToMace = true;
				lastSwitchMs = System.currentTimeMillis();
				switchDelayMs = nextDelay(switchDelayMin.getValue(),
					switchDelayMax.getValue());
				logDebug("Switched to mace slot " + i);
				return true;
			}
		}
		
		return false;
	}
	
	private void switchToSlot(int slot)
	{
		if(slot < 0 || slot >= 9)
			return;
		
		setSelectedHotbarSlot(slot);
	}
	
	private void setSelectedHotbarSlot(int slot)
	{
		if(MC.player == null
			|| MC.player.getInventory().getSelectedSlot() == slot)
			return;
		
		MC.player.getInventory().setSelectedSlot(slot);
		if(MC.player.connection != null)
			MC.player.connection
				.send(new ServerboundSetCarriedItemPacket(slot));
	}
	
	private void alignToTargetTop(Entity target)
	{
		if(!useAimAssist.isChecked() || target == null)
			return;
		
		Vec3 topCenter = getTopAimPoint(target);
		
		WURST.getRotationFaker().faceVectorClient(topCenter);
		WURST.getRotationFaker().faceVectorPacket(topCenter);
	}
	
	private boolean isWithinAttackRange(Entity entity)
	{
		if(entity == null)
			return false;
		
		AABB playerBox = MC.player.getBoundingBox();
		AABB targetBox = entity.getBoundingBox();
		
		double playerFeet = playerBox.minY;
		double playerTop = playerBox.maxY;
		double targetTop = targetBox.maxY;
		double targetBottom = targetBox.minY;
		
		if(playerFeet > targetTop + 0.25)
			return false;
		
		if(playerTop < targetBottom - 0.2)
			return false;
		
		double playerCenterX = (playerBox.minX + playerBox.maxX) * 0.5;
		double playerCenterZ = (playerBox.minZ + playerBox.maxZ) * 0.5;
		double targetCenterX = (targetBox.minX + targetBox.maxX) * 0.5;
		double targetCenterZ = (targetBox.minZ + targetBox.maxZ) * 0.5;
		double horizontal = Math.hypot(playerCenterX - targetCenterX,
			playerCenterZ - targetCenterZ);
		
		double maxHorizontal = Math.max(1.4, 0.9 + entity.getBbWidth() * 0.6);
		if(horizontal > maxHorizontal)
		{
			logSkip(String.format("horizontal %.2f > %.2f", horizontal,
				maxHorizontal));
			return false;
		}
		
		double downwardSpeed = MC.player.getDeltaMovement().y;
		if(downwardSpeed > -0.35)
		{
			logSkip(String.format("velocity %.3f too slow", downwardSpeed));
			return false;
		}
		
		if(playerFeet - targetTop > 0.35)
		{
			logSkip(String.format("feet above target by %.2f",
				playerFeet - targetTop));
			return false;
		}
		
		return true;
	}
	
	private boolean isFallReachable(Entity entity)
	{
		if(entity == null)
			return false;
		
		Vec3 playerPos =
			new Vec3(MC.player.getX(), MC.player.getY(), MC.player.getZ());
		Vec3 top = getTopAimPoint(entity);
		
		if(top.y >= playerPos.y)
			return false;
		
		double horizontal =
			Math.hypot(playerPos.x - top.x, playerPos.z - top.z);
		double vertical = playerPos.y - top.y;
		
		if(vertical < 0.2 || vertical > 3.5)
			return false;
		
		double maxHorizontal = Math.min(2.5, vertical + 1.1);
		return horizontal <= maxHorizontal;
	}
	
	private Entity findBestFallTarget()
	{
		Vec3 playerPos =
			new Vec3(MC.player.getX(), MC.player.getY(), MC.player.getZ());
		double maxDistanceSq = 36.0; // 6 blocks
		double maxHorizontal = 5.5;
		
		return EntityUtils.getAttackableEntities()
			.filter(this::isTargetCandidate)
			.filter(e -> MC.player.distanceToSqr(e) <= maxDistanceSq)
			.filter(e -> getTopAimPoint(e).y < playerPos.y)
			.filter(this::isFallReachable).filter(e -> {
				Vec3 top = getTopAimPoint(e);
				double dx = top.x - playerPos.x;
				double dz = top.z - playerPos.z;
				double horizontal = Math.hypot(dx, dz);
				return horizontal <= maxHorizontal;
			}).min(Comparator.comparingDouble(e -> {
				Vec3 top = getTopAimPoint(e);
				double verticalDiff = Math.max(0, playerPos.y - top.y - 0.05);
				double dx = top.x - playerPos.x;
				double dz = top.z - playerPos.z;
				double horizontal = Math.hypot(dx, dz);
				return verticalDiff * verticalDiff + horizontal;
			})).orElse(null);
	}
	
	private static Vec3 getTopAimPoint(Entity entity)
	{
		AABB box = entity.getBoundingBox();
		double x = (box.minX + box.maxX) * 0.5;
		double y = box.maxY + 0.05;
		double z = (box.minZ + box.maxZ) * 0.5;
		return new Vec3(x, y, z);
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
		lastLandingLogMs = 0;
		landingLogged = false;
		attackedThisFall = false;
		clearScheduledAttack();
		maceDmgForced = false;
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
	
	private void logDebug(String message)
	{
		if(!debugLogs.isChecked())
			return;
		
		ChatUtils.message("[AutoMace] " + message);
	}
	
	private void logDebugState(String message)
	{
		if(!debugLogs.isChecked())
			return;
		
		CombinedFormatter formatter = new CombinedFormatter();
		formatter.add(message);
		formatter.add("fallDist=%.2f", MC.player.fallDistance);
		formatter.add("velY=%.3f", MC.player.getDeltaMovement().y);
		formatter.add("hasMace=%s", isMaceEquipped());
		formatter.add("target=%s", currentTarget == null ? "none"
			: currentTarget.getName().getString());
		ChatUtils.message("[AutoMace] " + formatter.toString());
	}
	
	private void logLandingState(String message)
	{
		if(!debugLogs.isChecked())
			return;
		
		long now = System.currentTimeMillis();
		if(now - lastLandingLogMs < 200)
			return;
		
		lastLandingLogMs = now;
		logDebugState(message);
	}
	
	private String slotDescription(int slot)
	{
		return slot < 0 ? "none" : Integer.toString(slot);
	}
	
	private void logSkip(String message)
	{
		if(!debugLogs.isChecked())
			return;
		
		ChatUtils.message("[AutoMace] Skip attack: " + message);
	}
	
	private void clearScheduledAttack()
	{
		scheduledTarget = null;
		scheduledAttackTime = -1;
	}
	
	private void updateMaceDmgState()
	{
		if(!useMaceDmg.isChecked())
		{
			disableForcedMaceDmg();
			return;
		}
		
		MaceDmgHack maceDmg = WURST.getHax().maceDmgHack;
		if(maceDmg != null && !maceDmg.isEnabled())
		{
			maceDmg.setEnabled(true);
			maceDmgForced = true;
		}
	}
	
	private void disableForcedMaceDmg()
	{
		if(!maceDmgForced)
			return;
		
		MaceDmgHack maceDmg = WURST.getHax().maceDmgHack;
		if(maceDmg != null && maceDmg.isEnabled())
			maceDmg.setEnabled(false);
		
		maceDmgForced = false;
	}
	
	private static final class CombinedFormatter
	{
		private final StringBuilder builder = new StringBuilder();
		private boolean first = true;
		
		private void add(String format, Object... args)
		{
			if(!first)
				builder.append(" | ");
			first = false;
			builder.append(String.format(format, args));
		}
		
		@Override
		public String toString()
		{
			return builder.toString();
		}
	}
}
