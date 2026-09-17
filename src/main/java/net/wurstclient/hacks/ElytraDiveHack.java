/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.util.boatphase.PlayerTargeting;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

@SearchTags({"elytra dive", "dive attack"})
public final class ElytraDiveHack extends Hack implements UpdateListener
{
	private enum Phase
	{
		CLIMB,
		PERCH,
		DIVE,
		PULL_UP
	}
	
	private final TextFieldSetting targetName = new TextFieldSetting("Target",
		"description.wurst.setting.elytradive.target", "");
	private final SliderSetting targetRange = new SliderSetting("Target range",
		"description.wurst.setting.elytradive.target_range", 64, 4, 256, 1,
		ValueDisplay.INTEGER);
	private final EnumSetting<PlayerTargeting.Priority> targetPriority =
		new EnumSetting<>("Target priority",
			"description.wurst.setting.elytra.target_priority",
			PlayerTargeting.Priority.values(),
			PlayerTargeting.Priority.LOWEST_DISTANCE);
	private final SliderSetting perchHeight = new SliderSetting("Perch height",
		"description.wurst.setting.elytradive.perch_height", 18, 2, 60, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting perchOffset = new SliderSetting("Perch offset",
		"description.wurst.setting.elytradive.perch_offset", 4, 0, 20, .5,
		ValueDisplay.DECIMAL);
	private final SliderSetting perchPause = new SliderSetting("Perch pause",
		"description.wurst.setting.elytradive.perch_pause", 0, 0, 60, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting diveSpeed = new SliderSetting("Dive speed",
		"description.wurst.setting.elytradive.dive_speed", 1.5, .1, 4, .1,
		ValueDisplay.DECIMAL);
	private final SliderSetting pullupHeight = new SliderSetting(
		"Pull-up height", "description.wurst.setting.elytradive.pullup_height",
		3, .5, 12, .5, ValueDisplay.DECIMAL);
	private final SliderSetting pullupSpeed = new SliderSetting("Pull-up speed",
		"description.wurst.setting.elytradive.pullup_speed", 1.2, .1, 4, .1,
		ValueDisplay.DECIMAL);
	private final SliderSetting maxHorizontalSpeed =
		new SliderSetting("Max horizontal speed",
			"description.wurst.setting.elytradive.max_horizontal_speed", 1.6,
			.1, 4, .1, ValueDisplay.DECIMAL);
	private final CheckboxSetting repeat = new CheckboxSetting("Repeat",
		"description.wurst.setting.elytradive.repeat", true);
	private final CheckboxSetting tightStrike =
		new CheckboxSetting("Tight strike",
			"description.wurst.setting.elytradive.tight_strike", false);
	private final CheckboxSetting faceTarget =
		new CheckboxSetting("Face target",
			"description.wurst.setting.elytradive.face_target", true);
	private final CheckboxSetting autoTakeoff =
		new CheckboxSetting("Auto takeoff",
			"description.wurst.setting.elytradive.auto_takeoff", true);
	private boolean enabledElytraFlight;
	private Phase phase = Phase.CLIMB;
	private int pauseTicks;
	
	public ElytraDiveHack()
	{
		super("ElytraDive");
		setCategory(Category.MOVEMENT);
		addSetting(targetName);
		addSetting(targetRange);
		addSetting(targetPriority);
		addSetting(perchHeight);
		addSetting(perchOffset);
		addSetting(perchPause);
		addSetting(diveSpeed);
		addSetting(pullupHeight);
		addSetting(pullupSpeed);
		addSetting(maxHorizontalSpeed);
		addSetting(repeat);
		addSetting(tightStrike);
		addSetting(faceTarget);
		addSetting(autoTakeoff);
	}
	
	@Override
	protected void onEnable()
	{
		phase = Phase.CLIMB;
		pauseTicks = 0;
		if(!WURST.getHax().elytraFlightHack.isEnabled())
		{
			WURST.getHax().elytraFlightHack.setEnabled(true);
			enabledElytraFlight = true;
		}
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		if(enabledElytraFlight)
		{
			WURST.getHax().elytraFlightHack.setEnabled(false);
			enabledElytraFlight = false;
		}
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer p = MC.player;
		if(p == null || MC.level == null)
			return;
		if(!p.isFallFlying())
		{
			if(!autoTakeoff.isChecked())
				return;
			if(p.onGround())
			{
				p.jumpFromGround();
				return;
			}
			if(p.canGlide())
				p.startFallFlying();
			return;
		}
		Player target = findTarget(p);
		if(target == null)
			return;
		if(faceTarget.isChecked())
			WURST.getRotationFaker().faceVectorClient(target.getEyePosition());
		double tx = target.getX(), ty = target.getY(), tz = target.getZ();
		double dx = p.getX() - tx, dz = p.getZ() - tz;
		double h = Math.hypot(dx, dz);
		double ox =
			h > 1e-6 ? dx / h * perchOffset.getValue() : perchOffset.getValue();
		double oz = h > 1e-6 ? dz / h * perchOffset.getValue() : 0;
		double perchX = tx + ox, perchY = ty + perchHeight.getValue(),
			perchZ = tz + oz;
		if(phase == Phase.CLIMB)
		{
			if(Math.abs(p.getY() - perchY) < 2
				&& Math.hypot(p.getX() - perchX, p.getZ() - perchZ) < 2)
			{
				phase = Phase.PERCH;
				pauseTicks = perchPause.getValueI();
			}else
			{
				setVelocityToward(p, perchX, perchY, perchZ,
					maxHorizontalSpeed.getValue(), pullupSpeed.getValue());
				return;
			}
		}
		if(phase == Phase.PERCH)
		{
			setVelocityToward(p, perchX, perchY, perchZ,
				maxHorizontalSpeed.getValue(), .2);
			if(--pauseTicks <= 0)
				phase = Phase.DIVE;
			return;
		}
		double ground = groundAt(p.getX(), p.getZ());
		double targetGround = groundAt(tx, tz);
		if(phase == Phase.DIVE)
		{
			setVelocityToward(p, tx, ty, tz, maxHorizontalSpeed.getValue(),
				-diveSpeed.getValue());
			if(p.getY() - ground <= pullupHeight.getValue()
				|| p.getY() - targetGround <= pullupHeight.getValue()
				|| p.getY() - ty < 1)
				phase = Phase.PULL_UP;
			return;
		}
		setVelocityToward(p, tx, ty + pullupHeight.getValue() + 1, tz,
			maxHorizontalSpeed.getValue(), pullupSpeed.getValue());
		if(p.getY() >= ty + pullupHeight.getValue() + 1)
			phase = repeat.isChecked()
				? (tightStrike.isChecked() ? Phase.DIVE : Phase.CLIMB)
				: Phase.PERCH;
	}
	
	private void setVelocityToward(LocalPlayer p, double x, double y, double z,
		double maxHorizontal, double vertical)
	{
		double dx = x - p.getX(), dz = z - p.getZ(), h = Math.hypot(dx, dz);
		double speed = Math.min(maxHorizontal, h);
		double vx = h < .001 ? 0 : dx / h * speed;
		double vz = h < .001 ? 0 : dz / h * speed;
		double vy = Math.clamp(vertical, -4.0, 4.0);
		if(p.getY() + vy < MC.level.getMinY() + 1)
			vy = MC.level.getMinY() + 1 - p.getY();
		if(p.getY() + vy > MC.level.getMaxY() - 2)
			vy = MC.level.getMaxY() - 2 - p.getY();
		Vec3 desired = new Vec3(vx, vy, vz);
		if(MC.level.noCollision(p, p.getBoundingBox().move(desired)))
		{
			p.setDeltaMovement(desired);
			return;
		}
		Vec3 verticalOnly = new Vec3(0, vy, 0);
		Vec3 horizontalOnly = new Vec3(vx, 0, vz);
		if(MC.level.noCollision(p, p.getBoundingBox().move(verticalOnly)))
			p.setDeltaMovement(verticalOnly);
		else if(MC.level.noCollision(p,
			p.getBoundingBox().move(horizontalOnly)))
			p.setDeltaMovement(horizontalOnly);
		else
			p.setDeltaMovement(Vec3.ZERO);
	}
	
	private double groundAt(double x, double z)
	{
		if(MC.level == null)
			return -64;
		int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
		if(!MC.level.hasChunkAt(
			new net.minecraft.core.BlockPos(bx, MC.level.getMinY(), bz)))
			return MC.level.getMinY();
		return MC.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx,
			bz);
	}
	
	private Player findTarget(LocalPlayer p)
	{
		return PlayerTargeting.findPlayer(p, MC.level.players(),
			targetName.getValue(), targetRange.getValue(),
			targetPriority.getSelected());
	}
	
	public String getInfoString()
	{
		return phase.name().toLowerCase();
	}
}
