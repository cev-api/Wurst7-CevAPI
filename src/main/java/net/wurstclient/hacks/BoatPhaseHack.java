/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import org.lwjgl.glfw.GLFW;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.events.KeyPressListener;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.boatphase.BoatPhaseMotion;
import net.wurstclient.hack.Hack;

/** Boat flight with scoped collision phasing and correction recovery. */
@SearchTags({"boat phase", "boat phasing"})
public final class BoatPhaseHack extends Hack implements UpdateListener,
	PacketInputListener, PacketOutputListener, KeyPressListener
{
	private final CheckboxSetting speedometer = new CheckboxSetting(
		"Speedometer", "description.wurst.setting.boatphase.speedometer", true);
	private final SliderSetting meterScale = new SliderSetting("Meter scale",
		"description.wurst.setting.boatphase.meter_scale", 1, .1, 3, .1,
		ValueDisplay.DECIMAL);
	private final CheckboxSetting phaseCollision =
		new CheckboxSetting("Phase collision",
			"description.wurst.setting.boatphase.phase_collision", true);
	private final SliderSetting horizontalSpeed =
		new SliderSetting("Horizontal speed",
			"description.wurst.setting.boatphase.horizontal_speed", 2.8, 0, 9,
			.05, ValueDisplay.DECIMAL);
	private final SliderSetting horizontalLimit =
		new SliderSetting("Horizontal limit",
			"description.wurst.setting.boatphase.horizontal_limit", .99, .01, 9,
			.05, ValueDisplay.DECIMAL);
	private final CheckboxSetting fastAir = new CheckboxSetting("Fast air",
		"description.wurst.setting.boatphase.fast_air", false);
	private final CheckboxSetting airSubsteps =
		new CheckboxSetting("Air substeps",
			"description.wurst.setting.boatphase.air_substeps", true);
	private final SliderSetting substepSpeed = new SliderSetting(
		"Substep speed", "description.wurst.setting.boatphase.substep_speed",
		3.2, .1, 9, .05, ValueDisplay.DECIMAL);
	private final CheckboxSetting extendedSubsteps =
		new CheckboxSetting("Extended substeps",
			"description.wurst.setting.boatphase.extended_substeps", true);
	private final SliderSetting experimentalSpeed =
		new SliderSetting("Experimental speed",
			"description.wurst.setting.boatphase.experimental_speed", 6.5, .1,
			9, .05, ValueDisplay.DECIMAL);
	private final CheckboxSetting adaptivePhase =
		new CheckboxSetting("Adaptive phase",
			"description.wurst.setting.boatphase.adaptive_phase", true);
	private final SliderSetting airAcceleration =
		new SliderSetting("Air acceleration",
			"description.wurst.setting.boatphase.air_acceleration", .1, .01, 1,
			.01, ValueDisplay.DECIMAL);
	private final SliderSetting phaseHorizontalSpeed = new SliderSetting(
		"Phase speed", "description.wurst.setting.boatphase.phase_speed", .24,
		.01, .249, .01, ValueDisplay.DECIMAL);
	private final SliderSetting verticalSpeed = new SliderSetting(
		"Vertical speed", "description.wurst.setting.boatphase.vertical_speed",
		3, .1, 99, .1, ValueDisplay.DECIMAL);
	private final SliderSetting diveDistance =
		new SliderSetting("Quick dive distance",
			"description.wurst.setting.boatphase.dive_distance", 30, 1, 384, 1,
			ValueDisplay.INTEGER);
	private final CheckboxSetting antiKick = new CheckboxSetting("Anti-kick",
		"description.wurst.setting.boatphase.anti_kick", true);
	private final CheckboxSetting stopBeforeLava =
		new CheckboxSetting("Stop before lava",
			"description.wurst.setting.boatphase.stop_lava", true);
	private final CheckboxSetting retryAfterLanding =
		new CheckboxSetting("Retry after landing",
			"description.wurst.setting.boatphase.retry_after_landing", true);
	private final CheckboxSetting retryAfterRemount =
		new CheckboxSetting("Retry after remount",
			"description.wurst.setting.boatphase.retry_after_remount", true);
	private final SliderSetting landedRetrySpeed =
		new SliderSetting("Landed retry speed",
			"description.wurst.setting.boatphase.landed_retry_speed", 3.8, .05,
			2.5, .05, ValueDisplay.DECIMAL);
	private final CheckboxSetting onlyWhilePassenger =
		new CheckboxSetting("Passenger only",
			"description.wurst.setting.boatphase.only_while_passenger", true);
	private final CheckboxSetting ignoreCorrections =
		new CheckboxSetting("Ignore corrections",
			"description.wurst.setting.boatphase.ignore_corrections", true);
	private final CheckboxSetting restoreOnExit =
		new CheckboxSetting("Restore on exit",
			"description.wurst.setting.boatphase.restore_on_exit", true);
	private final CheckboxSetting lockYaw = new CheckboxSetting("Lock boat yaw",
		"description.wurst.setting.boatphase.lock_yaw", true);
	private final CheckboxSetting diveWithPassenger =
		new CheckboxSetting("Dive with passenger",
			"description.wurst.setting.boatphase.dive_with_passenger", false);
	private final SliderSetting correctionHold =
		new SliderSetting("Correction hold",
			"description.wurst.setting.boatphase.correction_hold", 8, 1, 100, 1,
			ValueDisplay.INTEGER);
	private final BoatPhaseMotion motion = new BoatPhaseMotion();
	private Entity phased;
	private boolean previousNoPhysics;
	private boolean sprinting;
	private boolean hadPassenger;
	private int correctionHoldTicks;
	private int retryTicks;
	private int lastVehiclePacketTick = -1;
	private Vec3 lastVehiclePacketPosition;
	private boolean enabledBoatFly;
	private boolean splitting;
	private boolean cruise;
	private float cruiseYaw;
	private boolean surfaceRequested;
	private boolean returnHeightRequested;
	private double boardingHeight = Double.NaN;
	private ServerboundMoveVehiclePacket splitPacket;
	private Vec3 lastWire;
	private Vec3 plannedMovement = Vec3.ZERO;
	private Vec3 previousMovement = Vec3.ZERO;
	private Entity plannedVehicle;
	
	public BoatPhaseHack()
	{
		super("BoatPhase");
		setCategory(Category.MOVEMENT);
		addSetting(speedometer);
		addSetting(meterScale);
		addSetting(phaseCollision);
		addSetting(horizontalSpeed);
		addSetting(horizontalLimit);
		addSetting(fastAir);
		addSetting(airSubsteps);
		addSetting(substepSpeed);
		addSetting(extendedSubsteps);
		addSetting(experimentalSpeed);
		addSetting(adaptivePhase);
		addSetting(airAcceleration);
		addSetting(phaseHorizontalSpeed);
		addSetting(verticalSpeed);
		addSetting(diveDistance);
		addSetting(antiKick);
		addSetting(stopBeforeLava);
		addSetting(retryAfterLanding);
		addSetting(retryAfterRemount);
		addSetting(landedRetrySpeed);
		addSetting(onlyWhilePassenger);
		addSetting(ignoreCorrections);
		addSetting(restoreOnExit);
		addSetting(lockYaw);
		addSetting(diveWithPassenger);
		addSetting(correctionHold);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null)
			return;
		if(!WURST.getHax().boatFlyHack.isEnabled())
		{
			WURST.getHax().boatFlyHack.setEnabled(true);
			enabledBoatFly = true;
		}
		motion.reset();
		sprinting = false;
		hadPassenger = false;
		correctionHoldTicks = 0;
		retryTicks = 0;
		phased = null;
		lastVehiclePacketTick = -1;
		lastVehiclePacketPosition = null;
		lastWire = null;
		cruise = false;
		cruiseYaw = 0;
		surfaceRequested = false;
		returnHeightRequested = false;
		boardingHeight = Double.NaN;
		splitPacket = null;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(KeyPressListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(KeyPressListener.class, this);
		restore();
		if(enabledBoatFly)
		{
			WURST.getHax().boatFlyHack.setEnabled(false);
			enabledBoatFly = false;
		}
	}
	
	@Override
	public void onUpdate()
	{
		motion.nextTick();
		if(correctionHoldTicks > 0)
			correctionHoldTicks--;
		LocalPlayer p = MC.player;
		plannedMovement = Vec3.ZERO;
		previousMovement = Vec3.ZERO;
		plannedVehicle = null;
		if(p == null || MC.level == null
			|| (onlyWhilePassenger.isChecked() && !p.isPassenger()))
		{
			if(restoreOnExit.isChecked())
				restore();
			return;
		}
		Entity vehicle = p.getVehicle();
		if(vehicle == null)
		{
			restore();
			boardingHeight = Double.NaN;
			return;
		}
		if(Double.isNaN(boardingHeight))
			boardingHeight = vehicle.getY();
		if(phased != vehicle)
		{
			boolean remount = phased != null;
			restore();
			phased = vehicle;
			if(remount && retryAfterRemount.isChecked())
				retryTicks = 10;
			previousNoPhysics = vehicle.noPhysics;
		}
		vehicle.noPhysics = phaseCollision.isChecked();
		boolean guest = vehicle.getPassengers().size() > 1;
		if(guest && !hadPassenger && diveWithPassenger.isChecked())
			motion.dive(diveDistance.getValue());
		hadPassenger = guest;
		if(lockYaw.isChecked())
			vehicle.setYRot(p.getYRot());
		boolean sprint = MC.options.keySprint.isDown();
		if(sprint && !sprinting)
			motion.dive(diveDistance.getValue());
		sprinting = sprint;
		if(!phaseCollision.isChecked() || correctionHoldTicks > 0)
			return;
		double forward = cruise ? 1 : (MC.options.keyUp.isDown() ? 1 : 0)
			- (MC.options.keyDown.isDown() ? 1 : 0);
		double sideways = cruise ? 0 : (MC.options.keyLeft.isDown() ? 1 : 0)
			- (MC.options.keyRight.isDown() ? 1 : 0);
		if(surfaceRequested)
		{
			double rise = findSurfaceRise(vehicle);
			if(rise > 0)
				motion.rise(rise);
			surfaceRequested = false;
		}
		if(returnHeightRequested && Double.isFinite(boardingHeight))
		{
			double delta = boardingHeight - vehicle.getY();
			if(delta > .5)
				motion.rise(Math.min(BoatPhaseMotion.MAX_TRAVEL, delta));
			else if(delta < -.5)
				motion.dive(Math.min(BoatPhaseMotion.MAX_TRAVEL, -delta));
			returnHeightRequested = false;
		}
		BoatPhaseMotion.Point origin = new BoatPhaseMotion.Point(vehicle.getX(),
			vehicle.getY(), vehicle.getZ());
		// Clear air can use the configured fast profile. Once embedded,
		// use the proven sub-block phase limit instead.
		boolean clearAir =
			MC.level.noCollision(vehicle, vehicle.getBoundingBox());
		double travelSpeed = retryTicks > 0 ? landedRetrySpeed.getValue()
			: horizontalSpeed.getValue();
		if(extendedSubsteps.isChecked()
			&& (airSubsteps.isChecked() || fastAir.isChecked()))
			travelSpeed = Math.min(travelSpeed, experimentalSpeed.getValue());
		else if(airSubsteps.isChecked() || fastAir.isChecked())
			travelSpeed = Math.min(travelSpeed, substepSpeed.getValue());
		else if(clearAir)
			travelSpeed = Math.min(travelSpeed, horizontalLimit.getValue());
		if(!clearAir)
			travelSpeed =
				Math.min(travelSpeed, phaseHorizontalSpeed.getValue());
		BoatPhaseMotion.Point step =
			motion.plan(origin, forward, sideways, MC.options.keyJump.isDown(),
				sprint, cruise ? cruiseYaw : p.getYRot(), travelSpeed,
				verticalSpeed.getValue(), MC.level.getMinY() + 1,
				MC.level.getMinY() + MC.level.getHeight() - 1,
				antiKick.isChecked());
		var destination =
			vehicle.getBoundingBox().move(step.x(), step.y(), step.z());
		if(!loaded(destination))
		{
			plannedMovement = Vec3.ZERO;
			plannedVehicle = vehicle;
			vehicle.setDeltaMovement(Vec3.ZERO);
			return;
		}
		if(stopBeforeLava.isChecked() && containsLava(destination))
			step = new BoatPhaseMotion.Point(0, 0, 0);
		if(adaptivePhase.isChecked() && clearAir)
			step = BoatPhaseMotion.airStep(step,
				new BoatPhaseMotion.Point(previousMovement.x,
					previousMovement.y, previousMovement.z),
				airAcceleration.getValue(), 1, antiKick.isChecked(),
				MC.options.keyJump.isDown() || MC.options.keySprint.isDown(),
				vehicle.getY() - MC.level.getMinY());
		plannedMovement = new Vec3(step.x(), step.y(), step.z());
		plannedVehicle = vehicle;
		previousMovement = plannedMovement;
		vehicle.setDeltaMovement(plannedMovement);
		// Acknowledge the issued movement so quick dives consume their
		// distance.
		motion.sent(
			new BoatPhaseMotion.Point(vehicle.getX(), vehicle.getY(),
				vehicle.getZ()),
			new BoatPhaseMotion.Point(vehicle.getX() + step.x(),
				vehicle.getY() + step.y(), vehicle.getZ() + step.z()));
		
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!(event.getPacket() instanceof ClientboundMoveVehiclePacket)
			|| MC.player == null || !MC.player.isPassenger()
			|| MC.player.getVehicle() == null || !phaseCollision.isChecked())
			return;
		if(ignoreCorrections.isChecked())
			event.cancel();
		else
		{
			correctionHoldTicks = correctionHold.getValueI();
			if(retryAfterLanding.isChecked())
				retryTicks = 10;
			motion.corrected(correctionHoldTicks);
		}
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!(event.getPacket() instanceof ServerboundMoveVehiclePacket packet)
			|| MC.player == null || MC.player.getVehicle() == null)
			return;
		if(packet == splitPacket)
		{
			lastWire = packet.position();
			return;
		}
		Vec3 pos = packet.position();
		if(lastVehiclePacketTick == motion.tick()
			&& lastVehiclePacketPosition != null
			&& lastVehiclePacketPosition.distanceToSqr(pos) < 1e-8 && Math
				.abs(packet.yRot() - MC.player.getVehicle().getYRot()) < .001)
		{
			event.cancel();
			return;
		}
		if(!splitting && phaseCollision.isChecked() && lastWire != null)
		{
			Vec3 delta = pos.subtract(lastWire);
			int count = Math.max(1, (int)Math.ceil(Math.max(
				delta.horizontalDistance()
					/ Math.max(.01, phaseHorizontalSpeed.getValue()),
				Math.abs(delta.y) / Math.max(.1, verticalSpeed.getValue()))));
			if(count > 1 && count <= 32)
			{
				event.cancel();
				splitting = true;
				try
				{
					for(int i = 1; i <= count; i++)
					{
						Vec3 end = lastWire.add(delta.scale((double)i / count));
						splitPacket = new ServerboundMoveVehiclePacket(end,
							packet.yRot(), packet.xRot(), packet.onGround());
						MC.player.connection.send(splitPacket);
					}
				}finally
				{
					splitPacket = null;
					splitting = false;
				}
				lastWire = pos;
				lastVehiclePacketTick = motion.tick();
				lastVehiclePacketPosition = pos;
				return;
			}
		}
		lastWire = pos;
		lastVehiclePacketTick = motion.tick();
		lastVehiclePacketPosition = pos;
	}
	
	private double findSurfaceRise(Entity vehicle)
	{
		if(MC.level == null || vehicle == null)
			return 0;
		int max = Math.min(128, MC.level.getMaxY() - 1
			- (int)Math.ceil(vehicle.getBoundingBox().maxY));
		for(int dy = 1; dy <= max; dy++)
		{
			AABB body = vehicle.getBoundingBox().move(0, dy, 0);
			if(!loaded(body) || !MC.level.noCollision(vehicle, body)
				|| containsLava(body))
				continue;
			boolean passengersClear = true;
			for(Entity passenger : vehicle.getPassengers())
			{
				AABB passengerBox = passenger.getBoundingBox().move(0, dy, 0);
				if(passengerBox.maxY > MC.level.getMaxY()
					|| !MC.level.noCollision(passenger, passengerBox))
				{
					passengersClear = false;
					break;
				}
			}
			if(passengersClear)
				return dy;
		}
		return 0;
	}
	
	@Override
	public void onKeyPress(KeyPressListener.KeyPressEvent event)
	{
		if(event.getAction() != GLFW.GLFW_PRESS || MC.gui.screen() != null)
			return;
		switch(event.getKeyCode())
		{
			case GLFW.GLFW_KEY_C ->
			{
				cruise = !cruise;
				if(cruise && MC.player != null)
					cruiseYaw = MC.player.getYRot();
			}
			case GLFW.GLFW_KEY_V -> surfaceRequested = true;
			case GLFW.GLFW_KEY_R -> returnHeightRequested = true;
			default ->
				{
				}
		}
	}
	
	public String getInfoString()
	{
		if(!speedometer.isChecked() || MC.player == null
			|| MC.player.getVehicle() == null)
			return null;
		Entity vehicle = MC.player.getVehicle();
		Vec3 velocity = vehicle.getDeltaMovement();
		double horizontal =
			velocity.horizontalDistance() * 20 * meterScale.getValue();
		return String.format("%.1f b/s%s", horizontal,
			correctionHoldTicks > 0 ? " recovering" : "");
	}
	
	/**
	 * Returns the movement selected by BoatPhase for the active vanilla boat
	 * tick.
	 */
	public Vec3 movementFor(Entity vehicle, Vec3 vanilla)
	{
		if(!shouldPhase(vehicle) || plannedVehicle != vehicle)
			return vanilla;
		return plannedMovement;
	}
	
	public boolean shouldPhase(Entity vehicle)
	{
		LocalPlayer p = MC.player;
		return isEnabled() && phaseCollision.isChecked() && p != null
			&& p.isPassenger() && p.getVehicle() == vehicle;
	}
	
	private boolean loaded(net.minecraft.world.phys.AABB box)
	{
		for(int x : new int[]{(int)Math.floor(box.minX),
			(int)Math.floor(box.maxX)})
			for(int z : new int[]{(int)Math.floor(box.minZ),
				(int)Math.floor(box.maxZ)})
				if(!MC.level.hasChunkAt(BlockPos.containing(x, box.minY, z)))
					return false;
		return true;
	}
	
	private boolean containsLava(net.minecraft.world.phys.AABB box)
	{
		for(BlockPos pos : BlockPos.betweenClosed((int)Math.floor(box.minX),
			(int)Math.floor(box.minY), (int)Math.floor(box.minZ),
			(int)Math.floor(box.maxX), (int)Math.floor(box.maxY),
			(int)Math.floor(box.maxZ)))
			if(MC.level.getFluidState(pos).is(FluidTags.LAVA))
				return true;
		return false;
	}
	
	private void restore()
	{
		plannedMovement = Vec3.ZERO;
		previousMovement = Vec3.ZERO;
		plannedVehicle = null;
		if(phased != null)
			phased.noPhysics = previousNoPhysics;
		phased = null;
		lastWire = null;
	}
}
