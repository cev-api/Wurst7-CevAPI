/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.world.phys.Vec3;
import net.minecraft.client.player.LocalPlayer;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"speed hack"})
public final class SpeedHackHack extends Hack implements UpdateListener
{
	private static final double AUTO_NORMALISE_BASE_BPS = 5.6;
	private static final double AUTO_NORMALISE_WALK_BPS = 4.317;
	private static final double AUTO_NORMALISE_SPRINT_BPS = 5.612;
	private static final double AUTO_NORMALISE_GROUND_ACCEL = 0.35;
	private static final double AUTO_NORMALISE_AIR_ACCEL = 0.02;
	private static final double AUTO_NORMALISE_SIDEWAYS_DAMPING = 0.15;
	private static final double AUTO_NORMALISE_MAX_BOOST = 2.0;
	private static final double AUTO_NORMALISE_STEP_UP = 0.05;
	private static final double AUTO_NORMALISE_STEP_DOWN = 0.02;
	
	private final SliderSetting maxSpeed = new SliderSetting("Max speed",
		"Maximum horizontal speed while SpeedHack is active.", 0.65, 0.1, 9.9,
		0.1, ValueDisplay.DECIMAL);
	private final SliderSetting groundAccel = new SliderSetting("Ground accel",
		"How quickly speed approaches the target while on ground.", 0.35, 0.01,
		9.9, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting airAccel = new SliderSetting("Air accel",
		"How quickly speed approaches the target while in air.", 0.02, 0.0, 5,
		0.1, ValueDisplay.DECIMAL);
	private final SliderSetting sidewaysDamping = new SliderSetting(
		"Sideways damping",
		"How much sideways inertia is kept while turning. 0 = no drift, 1 = full drift.",
		0.15, 0.0, 1.0, 0.01, ValueDisplay.DECIMAL);
	private final CheckboxSetting autoSprint = new CheckboxSetting(
		"Auto sprint",
		"Automatically sprints while moving forward in normal SpeedHack mode.",
		true);
	private final CheckboxSetting autoNormalise = new CheckboxSetting(
		"Auto normalise",
		"Overrides regular SpeedHack tuning and automatically compensates persistent non-environmental slowdown to maintain a normal walking speed.",
		false);
	private final CheckboxSetting renderSpeed =
		new CheckboxSetting("Show speed in HackList", true);
	
	private double currentNormaliseBoost = 1.0;
	
	public SpeedHackHack()
	{
		super("SpeedHack");
		setCategory(Category.MOVEMENT);
		addSetting(maxSpeed);
		addSetting(groundAccel);
		addSetting(airAccel);
		addSetting(sidewaysDamping);
		addSetting(autoSprint);
		addSetting(autoNormalise);
		addSetting(renderSpeed);
	}
	
	@Override
	public String getRenderName()
	{
		if(!renderSpeed.isChecked())
			return getName();
		
		LocalPlayer player = MC.player;
		if(player == null)
			return getName();
		
		Vec3 velocity = player.getDeltaMovement();
		long blocksPerSecond = Math.round(velocity.length() * 20.0);
		String speedSetting =
			autoNormalise.isChecked() ? "vanilla" : maxSpeed.getValueString();
		return getName() + " [" + blocksPerSecond + "b/s | " + speedSetting
			+ "]";
	}
	
	@Override
	protected void onEnable()
	{
		currentNormaliseBoost = 1.0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		currentNormaliseBoost = 1.0;
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		// ### Improving Wursts Original Method ###
		if(MC.player == null)
			return;
		
		// return if sneaking or not walking
		if(MC.player.isShiftKeyDown()
			|| MC.player.zza == 0 && MC.player.xxa == 0)
			return;
			
		// Avoid doing this while flying or in fluids, where vanilla movement
		// rules differ a lot.
		if(MC.player.getAbilities().flying || MC.player.isInWater()
			|| MC.player.isInLava())
			return;
		
		// activate sprint if walking forward
		if(!autoNormalise.isChecked() && autoSprint.isChecked()
			&& MC.player.zza > 0 && !MC.player.horizontalCollision)
			MC.player.setSprinting(true);
			
		// New approach: set a *target* horizontal velocity based on input +
		// yaw.
		// This keeps control/turning responsive by damping sideways drift
		// instead of amplifying momentum.
		final float forward = MC.player.zza;
		final float strafe = MC.player.xxa;
		
		Vec3 dir = getMoveDir(forward, strafe);
		if(dir == Vec3.ZERO)
			return;
		
		Vec3 v = MC.player.getDeltaMovement();
		Vec3 velH = new Vec3(v.x, 0, v.z);
		double currentHorizontalSpeed =
			Math.sqrt(velH.x * velH.x + velH.z * velH.z);
		boolean useAutoNormalise = autoNormalise.isChecked();
		
		// Dampen sideways inertia so turning doesn't feel like ice.
		double alongSpeed = velH.dot(dir);
		Vec3 along = dir.scale(alongSpeed);
		Vec3 side = velH.subtract(along);
		double damping = useAutoNormalise ? AUTO_NORMALISE_SIDEWAYS_DAMPING
			: sidewaysDamping.getValue();
		velH = along.add(side.scale(damping));
		
		// Accelerate towards target speed (strong on ground, weak in air).
		double accel = MC.player.onGround()
			? (useAutoNormalise ? AUTO_NORMALISE_GROUND_ACCEL
				: groundAccel.getValue())
			: (useAutoNormalise ? AUTO_NORMALISE_AIR_ACCEL
				: airAccel.getValue());
		double maxSpeedValue = useAutoNormalise
			? getAutoNormaliseBaseSpeedPerTick() : maxSpeed.getValue();
		double compensatedTargetSpeed = maxSpeedValue;
		if(useAutoNormalise)
			compensatedTargetSpeed =
				updateNormaliseTarget(maxSpeedValue, currentHorizontalSpeed);
		else
			currentNormaliseBoost = 1.0;
		Vec3 targetH = dir.scale(compensatedTargetSpeed);
		velH = velH.add(targetH.subtract(velH).scale(accel));
		
		// Clamp to max speed.
		double speed = Math.sqrt(velH.x * velH.x + velH.z * velH.z);
		if(speed > compensatedTargetSpeed)
			velH = velH.scale(compensatedTargetSpeed / speed);
		
		MC.player.setDeltaMovement(velH.x, v.y, velH.z);
		
		// ### Wursts Original Code ###
		/*
		 * // activate mini jump if on ground
		 * if(!MC.player.onGround())
		 * return;
		 *
		 * Vec3 v = MC.player.getDeltaMovement();
		 * MC.player.setDeltaMovement(v.x * 1.8, v.y + 0.1, v.z * 1.8);
		 *
		 * v = MC.player.getDeltaMovement();
		 * double currentSpeed = Math.sqrt(Math.pow(v.x, 2) + Math.pow(v.z, 2));
		 *
		 * // limit speed to highest value that works on NoCheat+ version
		 * // 3.13.0-BETA-sMD5NET-b878
		 * // UPDATE: Patched in NoCheat+ version 3.13.2-SNAPSHOT-sMD5NET-b888
		 * double maxSpeed = 0.66F;
		 *
		 * if(currentSpeed > maxSpeed)
		 * MC.player.setDeltaMovement(v.x / currentSpeed * maxSpeed, v.y,
		 * v.z / currentSpeed * maxSpeed);
		 */
	}
	
	private Vec3 getMoveDir(float forward, float strafe)
	{
		// Minecraft yaw: 0 = south (+Z), 90 = west (-X)
		double yaw = Math.toRadians(MC.player.getYRot());
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		
		double x = (-sin * forward) + (cos * strafe);
		double z = (cos * forward) + (sin * strafe);
		
		double len = Math.sqrt(x * x + z * z);
		if(len < 1.0E-6)
			return Vec3.ZERO;
		
		return new Vec3(x / len, 0, z / len);
	}
	
	private double updateNormaliseTarget(double baseTargetSpeed,
		double currentHorizontalSpeed)
	{
		// Only push compensation while we should have stable ground movement.
		boolean candidate = MC.player != null && MC.player.onGround()
			&& !MC.player.horizontalCollision && MC.player.zza > 0
			&& !MC.player.isShiftKeyDown() && !MC.player.isInWater()
			&& !MC.player.isInLava() && !MC.player.getAbilities().flying;
		
		double underperformThreshold = baseTargetSpeed * 0.8;
		double recoveredThreshold = baseTargetSpeed * 0.95;
		
		if(candidate && currentHorizontalSpeed > 0.08
			&& currentHorizontalSpeed < underperformThreshold)
		{
			double cap = AUTO_NORMALISE_MAX_BOOST;
			currentNormaliseBoost =
				Math.min(cap, currentNormaliseBoost + AUTO_NORMALISE_STEP_UP);
		}else if(currentNormaliseBoost > 1.0)
		{
			boolean recovered =
				!candidate || currentHorizontalSpeed >= recoveredThreshold;
			if(recovered)
				currentNormaliseBoost = Math.max(1.0,
					currentNormaliseBoost - AUTO_NORMALISE_STEP_DOWN);
		}
		
		return baseTargetSpeed * currentNormaliseBoost;
	}
	
	private double getAutoNormaliseBaseSpeedPerTick()
	{
		if(MC.player == null)
			return AUTO_NORMALISE_WALK_BPS / 20.0;
		
		boolean movingForward = MC.player.zza > 0;
		boolean sprintRequested = movingForward
			&& (MC.options.keySprint.isDown() || MC.player.isSprinting());
		double bps = sprintRequested ? AUTO_NORMALISE_SPRINT_BPS
			: AUTO_NORMALISE_WALK_BPS;
		return bps / 20.0;
	}
}
