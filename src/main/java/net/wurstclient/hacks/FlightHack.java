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
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.AirStrafingSpeedListener;
import net.wurstclient.events.AirStrafingSpeedListener.AirStrafingSpeedEvent;
import net.wurstclient.events.IsPlayerInWaterListener;
import net.wurstclient.events.IsPlayerInWaterListener.IsPlayerInWaterEvent;
import net.wurstclient.events.MouseScrollListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.PlayerRangeAlertManager;

@SearchTags({"FlyHack", "fly hack", "flying"})
public final class FlightHack extends Hack implements UpdateListener,
	IsPlayerInWaterListener, AirStrafingSpeedListener,
	PlayerRangeAlertManager.Listener, MouseScrollListener
{
	private static final double DEFAULT_SPEED_STEP = 0.5;
	
	final SliderSetting horizontalSpeed = new SliderSetting("Horizontal speed",
		"description.wurst.setting.flight.horizontal_speed", 1, 0.05, 10, 0.05,
		ValueDisplay.DECIMAL);
	
	final SliderSetting verticalSpeed = new SliderSetting("Vertical speed",
		"description.wurst.setting.flight.vertical_speed", 1, 0.05, 10, 0.05,
		v -> ValueDisplay.DECIMAL.getValueString(getActualVerticalSpeed()));
	
	private final CheckboxSetting tieVerticalToHorizontal = new CheckboxSetting(
		"Tie vertical to horizontal",
		"description.wurst.setting.flight.tie_vertical_to_horizontal", false);
	
	private final SliderSetting speedStep = new SliderSetting("Speed step",
		"description.wurst.setting.flight.speed_step", DEFAULT_SPEED_STEP, 0.05,
		5.0, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting allowUnsafeVerticalSpeed =
		new CheckboxSetting("Allow unsafe vertical speed",
			"description.wurst.setting.flight.allow_unsafe_vertical_speed",
			false);
	
	private final CheckboxSetting scrollToChangeSpeed =
		new CheckboxSetting("Scroll to change speed",
			"description.wurst.setting.flight.scroll_to_change_speed", false);
	
	private final CheckboxSetting renderSpeed =
		new CheckboxSetting("Show speed in HackList",
			"description.wurst.setting.flight.show_speed_in_hacklist", true);
	
	private final CheckboxSetting antiKick = new CheckboxSetting("Anti-Kick",
		"description.wurst.setting.flight.anti-kick", false);
	
	private final SliderSetting antiKickInterval =
		new SliderSetting("Anti-Kick Interval",
			"description.wurst.setting.flight.anti-kick_interval", 70, 5, 80, 1,
			ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final SliderSetting antiKickDistance =
		new SliderSetting("Anti-Kick Distance",
			"description.wurst.setting.flight.anti-kick_distance", 0.035, 0.01,
			0.2, 0.001, ValueDisplay.DECIMAL.withSuffix("m"));
	
	private final CheckboxSetting dontGetCaught =
		new CheckboxSetting("Don't get caught",
			"description.wurst.setting.flight.dont_get_caught", false);
	private final CheckboxSetting ignoreNpcs = new CheckboxSetting(
		"Ignore NPCs", "description.wurst.setting.flight.ignore_npcs", true);
	private final CheckboxSetting ignoreFriends =
		new CheckboxSetting("Ignore friends",
			"description.wurst.setting.flight.ignore_friends", true);
	private final SliderSetting escapeDropSpeed =
		new SliderSetting("Escape Drop Speed",
			"description.wurst.setting.flight.escape_drop_speed", 3.9, 0.05,
			3.9, 0.05, ValueDisplay.DECIMAL);
	private final CheckboxSetting enableNoFallOnFlight = new CheckboxSetting(
		"Enable NoFall with Flight",
		"description.wurst.setting.flight.enable_nofall_with_flight", false);
	
	private final CheckboxSetting ignoreVinesWithFlight = new CheckboxSetting(
		"Ignore vines with Flight",
		"Temporarily enables NoSlowdown's \"Ignore vines\" while Flight is enabled.",
		false);
	
	private final CheckboxSetting slowSneaking =
		new CheckboxSetting("Slow sneaking",
			"description.wurst.setting.flight.slow_sneaking", false);
	
	private final CheckboxSetting ignoreShiftInGuis = new CheckboxSetting(
		"Ignore shift in GUIs",
		"Prevents Flight from descending when you hold Shift while a GUI is open (e.g. shift-clicking items in your inventory).",
		true);
	
	private Boolean antiKickOverride;
	private Boolean slowSneakingOverride;
	
	private int tickCounter = 0;
	private final PlayerRangeAlertManager alertManager =
		WURST.getPlayerRangeAlertManager();
	private boolean escapeDropActive;
	private double escapeTargetY;
	private boolean triggered;
	private boolean enabledNoFallByFlight;
	private boolean enabledNoFallByEscape;
	private boolean managedNoSlowdownVineIgnore;
	
	public FlightHack()
	{
		super("Flight");
		setCategory(Category.MOVEMENT);
		addSetting(horizontalSpeed);
		addSetting(verticalSpeed);
		addSetting(tieVerticalToHorizontal);
		addSetting(speedStep);
		addSetting(allowUnsafeVerticalSpeed);
		addSetting(scrollToChangeSpeed);
		addSetting(renderSpeed);
		addSetting(antiKick);
		addSetting(antiKickInterval);
		addSetting(antiKickDistance);
		addSetting(dontGetCaught);
		addSetting(escapeDropSpeed);
		addSetting(ignoreNpcs);
		addSetting(ignoreFriends);
		addSetting(enableNoFallOnFlight);
		addSetting(ignoreVinesWithFlight);
		addSetting(slowSneaking);
		addSetting(ignoreShiftInGuis);
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
		
		return getName() + " [" + blocksPerSecond + "b/s | "
			+ verticalSpeed.getValueString() + "/"
			+ horizontalSpeed.getValueString() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		tickCounter = 0;
		escapeDropActive = false;
		triggered = false;
		managedNoSlowdownVineIgnore = false;
		
		WURST.getHax().creativeFlightHack.setEnabled(false);
		WURST.getHax().jetpackHack.setEnabled(false);
		syncNoSlowdownVineIgnore();
		
		if(enableNoFallOnFlight.isChecked()
			&& !WURST.getHax().noFallHack.isEnabled())
		{
			WURST.getHax().noFallHack.setEnabled(true);
			enabledNoFallByFlight = true;
		}
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(IsPlayerInWaterListener.class, this);
		EVENTS.add(AirStrafingSpeedListener.class, this);
		alertManager.addListener(this);
		EVENTS.add(MouseScrollListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		restoreNoSlowdownVineIgnore();
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(IsPlayerInWaterListener.class, this);
		EVENTS.remove(AirStrafingSpeedListener.class, this);
		alertManager.removeListener(this);
		EVENTS.remove(MouseScrollListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		syncNoSlowdownVineIgnore();
		
		if(enableNoFallOnFlight.isChecked()
			&& !WURST.getHax().noFallHack.isEnabled())
		{
			WURST.getHax().noFallHack.setEnabled(true);
			enabledNoFallByFlight = true;
		}
		
		if(escapeDropActive)
		{
			handleEscapeDrop(player);
			return;
		}
		
		player.setDeltaMovement(Vec3.ZERO);
		player.getAbilities().flying = false;
		
		if(WURST.getHax().freecamHack.isMovingCamera())
			return;
		
		double vSpeed = getActualVerticalSpeed();
		
		if(MC.options.keyJump.isDown())
			player.addDeltaMovement(new Vec3(0, vSpeed, 0));
		
		boolean shiftActuallyDown =
			IKeyMapping.get(MC.options.keyShift).isActuallyDown();
		boolean allowShiftInThisContext =
			!ignoreShiftInGuis.isChecked() || MC.screen == null;
		if(shiftActuallyDown && allowShiftInThisContext)
		{
			MC.options.keyShift.setDown(false);
			player.addDeltaMovement(new Vec3(0, -vSpeed, 0));
		}
		
		if(isAntiKickEnabled())
			doAntiKick();
		
		Double alignStep =
			WURST.getHax().spearAssistHack.getAutoAlignmentStepForFlight();
		if(alignStep != null)
		{
			Vec3 current = player.getDeltaMovement();
			player.setDeltaMovement(current.x, alignStep, current.z);
		}
	}
	
	private void syncNoSlowdownVineIgnore()
	{
		NoSlowdownHack noSlowdown = WURST.getHax().noSlowdownHack;
		if(noSlowdown == null)
			return;
		
		if(!ignoreVinesWithFlight.isChecked())
		{
			if(managedNoSlowdownVineIgnore)
			{
				noSlowdown.setIgnoreVines(false);
				managedNoSlowdownVineIgnore = false;
			}
			return;
		}
		
		if(!managedNoSlowdownVineIgnore)
		{
			if(noSlowdown.shouldIgnoreVines())
				return;
			
			noSlowdown.setIgnoreVines(true);
			managedNoSlowdownVineIgnore = true;
			return;
		}
		
		if(!noSlowdown.shouldIgnoreVines())
			noSlowdown.setIgnoreVines(true);
	}
	
	private void restoreNoSlowdownVineIgnore()
	{
		if(!managedNoSlowdownVineIgnore)
			return;
		
		NoSlowdownHack noSlowdown = WURST.getHax().noSlowdownHack;
		if(noSlowdown != null)
			noSlowdown.setIgnoreVines(false);
		
		managedNoSlowdownVineIgnore = false;
	}
	
	@Override
	public void onGetAirStrafingSpeed(AirStrafingSpeedEvent event)
	{
		if(WURST.getHax().freecamHack.isMovingCamera())
			return;
		
		event.setSpeed(horizontalSpeed.getValueF());
	}
	
	@Override
	public void onMouseScroll(double amount)
	{
		if(!isControllingScrollEvents())
			return;
		
		double step = speedStep.getValue();
		if(amount > 0)
			horizontalSpeed.setValue(horizontalSpeed.getValue() + step);
		else if(amount < 0)
			horizontalSpeed.setValue(horizontalSpeed.getValue() - step);
	}
	
	public boolean isControllingScrollEvents()
	{
		return isEnabled() && scrollToChangeSpeed.isChecked()
			&& MC.screen == null
			&& !WURST.getOtfs().zoomOtf.isControllingScrollEvents()
			&& !WURST.getHax().freecamHack.isMovingCamera();
	}
	
	private void doAntiKick()
	{
		if(tickCounter > antiKickInterval.getValueI() + 1)
			tickCounter = 0;
		
		Vec3 velocity = MC.player.getDeltaMovement();
		
		switch(tickCounter)
		{
			case 0 ->
			{
				if(velocity.y <= -antiKickDistance.getValue())
					tickCounter = 2;
				else
					MC.player.setDeltaMovement(velocity.x,
						-antiKickDistance.getValue(), velocity.z);
			}
			
			case 1 -> MC.player.setDeltaMovement(velocity.x,
				antiKickDistance.getValue(), velocity.z);
		}
		
		tickCounter++;
	}
	
	boolean isAntiKickEnabled()
	{
		return antiKickOverride != null ? antiKickOverride
			: antiKick.isChecked();
	}
	
	boolean isSlowSneakingEnabled()
	{
		return slowSneakingOverride != null ? slowSneakingOverride
			: slowSneaking.isChecked();
	}
	
	void setAntiKickOverride(Boolean override)
	{
		antiKickOverride = override;
	}
	
	void setSlowSneakingOverride(Boolean override)
	{
		slowSneakingOverride = override;
	}
	
	@Override
	public void onIsPlayerInWater(IsPlayerInWaterEvent event)
	{
		event.setInWater(false);
	}
	
	@Override
	public void onPlayerEnter(net.minecraft.world.entity.player.Player player,
		PlayerRangeAlertManager.PlayerInfo info)
	{
		if(!isEnabled() || !dontGetCaught.isChecked() || triggered)
			return;
		
		if(ignoreNpcs.isChecked() && info.isProbablyNpc())
			return;
		
		if(ignoreFriends.isChecked()
			&& ((player != null && WURST.getFriends().isFriend(player))
				|| WURST.getFriends().contains(info.getName())))
			return;
		
		DropTarget target = findDropTarget();
		if(target.type == DropTargetType.LAVA)
		{
			ChatUtils.message("Flight cancelled: lava detected below.");
			setEnabled(false);
			return;
		}
		
		if(target.type == DropTargetType.NONE)
		{
			ChatUtils.message("Flight cancelled: no safe drop target.");
			setEnabled(false);
			return;
		}
		
		triggered = true;
		
		if(!WURST.getHax().noFallHack.isEnabled())
		{
			WURST.getHax().noFallHack.setEnabled(true);
			enabledNoFallByEscape = true;
		}
		
		escapeTargetY = target.targetY;
		escapeDropActive = true;
	}
	
	@Override
	public void onPlayerExit(PlayerRangeAlertManager.PlayerInfo info)
	{
		// not needed
	}
	
	private void handleEscapeDrop(LocalPlayer player)
	{
		if(player == null)
		{
			setEnabled(false);
			return;
		}
		
		player.getAbilities().flying = false;
		double currentY = player.getY();
		if(currentY <= escapeTargetY + 0.05)
		{
			escapeDropActive = false;
			ChatUtils.message("Flight cancelled: reached the ground.");
			setEnabled(false);
			return;
		}
		
		// Drop as fast as possible without teleporting.
		double maxDrop = escapeDropSpeed.getValue();
		double remaining = currentY - escapeTargetY;
		double step = Math.min(Math.max(remaining, 0.0), maxDrop);
		if(step <= 0.0)
			step = remaining;
		player.setDeltaMovement(0, -step, 0);
	}
	
	private DropTarget findDropTarget()
	{
		if(MC.level == null || MC.player == null)
			return new DropTarget(DropTargetType.NONE, 0);
		
		int x = Mth.floor(MC.player.getX());
		int z = Mth.floor(MC.player.getZ());
		int startY = Mth.floor(MC.player.getY());
		int minY = MC.level.getMinY();
		
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for(int y = startY; y >= minY; y--)
		{
			pos.set(x, y, z);
			BlockState state = MC.level.getBlockState(pos);
			
			if(!state.getFluidState().isEmpty())
			{
				if(state.getFluidState().is(FluidTags.LAVA))
					return new DropTarget(DropTargetType.LAVA, 0);
				if(state.getFluidState().is(FluidTags.WATER))
					return new DropTarget(DropTargetType.WATER, y + 0.1);
			}
			
			if(!state.getCollisionShape(MC.level, pos).isEmpty())
				return new DropTarget(DropTargetType.GROUND, y + 1.0);
		}
		
		return new DropTarget(DropTargetType.GROUND, minY);
	}
	
	private enum DropTargetType
	{
		GROUND,
		WATER,
		LAVA,
		NONE
	}
	
	private record DropTarget(DropTargetType type, double targetY)
	{}
	
	public double getHorizontalSpeed()
	{
		return horizontalSpeed.getValue();
	}
	
	public double getActualVerticalSpeed()
	{
		// Can be called for UI/rendering while not in-world.
		if(MC.player == null)
			return Mth.clamp(getRawVerticalSpeed(), 0.05, 10);
		
		boolean limitVerticalSpeed = !allowUnsafeVerticalSpeed.isChecked()
			&& !MC.player.getAbilities().invulnerable;
		
		return Mth.clamp(getRawVerticalSpeed(), 0.05,
			limitVerticalSpeed ? 3.95 : 10);
	}
	
	private double getRawVerticalSpeed()
	{
		if(tieVerticalToHorizontal.isChecked())
			return horizontalSpeed.getValue() * verticalSpeed.getValue();
		
		return verticalSpeed.getValue();
	}
	
	public double getSpeedStep()
	{
		return speedStep.getValue();
	}
}
