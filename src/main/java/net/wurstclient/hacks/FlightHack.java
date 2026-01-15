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
import net.wurstclient.events.IsPlayerInWaterListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.PlayerRangeAlertManager;

@SearchTags({"FlyHack", "fly hack", "flying"})
public final class FlightHack extends Hack
	implements UpdateListener, IsPlayerInWaterListener,
	AirStrafingSpeedListener, PlayerRangeAlertManager.Listener
{
	public final SliderSetting horizontalSpeed = new SliderSetting(
		"Horizontal Speed", 1, 0.05, 10, 0.05, ValueDisplay.DECIMAL);
	
	public final SliderSetting verticalSpeed = new SliderSetting(
		"Vertical Speed",
		"\u00a7c\u00a7lWARNING:\u00a7r Setting this too high can cause fall damage, even with NoFall.",
		1, 0.05, 5, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting slowSneaking = new CheckboxSetting(
		"Slow sneaking",
		"Reduces your horizontal speed while you are sneaking to prevent you from glitching out.",
		true);
	
	private final CheckboxSetting antiKick = new CheckboxSetting("Anti-Kick",
		"Makes you fall a little bit every now and then to prevent you from getting kicked.",
		false);
	
	private final SliderSetting antiKickInterval =
		new SliderSetting("Anti-Kick Interval",
			"How often Anti-Kick should prevent you from getting kicked.\n"
				+ "Most servers will kick you after 80 ticks.",
			30, 5, 80, 1,
			ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final SliderSetting antiKickDistance = new SliderSetting(
		"Anti-Kick Distance",
		"How far Anti-Kick should make you fall.\n"
			+ "Most servers require at least 0.032m to stop you from getting kicked.",
		0.07, 0.01, 0.2, 0.001, ValueDisplay.DECIMAL.withSuffix("m"));
	
	private final CheckboxSetting dontGetCaught = new CheckboxSetting(
		"Don't get caught",
		"If another player is detected, drops you to the ground and disables Flight.",
		false);
	private final CheckboxSetting ignoreNpcs =
		new CheckboxSetting("Ignore NPCs",
			"Skips players that don't show up on the tab list.", true);
	private final CheckboxSetting ignoreFriends = new CheckboxSetting(
		"Ignore friends",
		"Won't trigger if the detected player is on your friends list.", true);
	private final CheckboxSetting enableNoFallOnFlight =
		new CheckboxSetting("Enable NoFall with Flight",
			"Automatically enables NoFall while Flight is enabled.", false);
	
	private int tickCounter = 0;
	private final PlayerRangeAlertManager alertManager =
		WURST.getPlayerRangeAlertManager();
	private boolean escapeDropActive;
	private double escapeTargetY;
	private boolean triggered;
	private boolean enabledNoFallByFlight;
	private boolean enabledNoFallByEscape;
	
	public FlightHack()
	{
		super("Flight");
		setCategory(Category.MOVEMENT);
		addSetting(horizontalSpeed);
		addSetting(verticalSpeed);
		addSetting(slowSneaking);
		addSetting(antiKick);
		addSetting(antiKickInterval);
		addSetting(antiKickDistance);
		addSetting(dontGetCaught);
		addSetting(ignoreNpcs);
		addSetting(ignoreFriends);
		addSetting(enableNoFallOnFlight);
	}
	
	@Override
	protected void onEnable()
	{
		tickCounter = 0;
		escapeDropActive = false;
		triggered = false;
		
		WURST.getHax().creativeFlightHack.setEnabled(false);
		WURST.getHax().jetpackHack.setEnabled(false);
		
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
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(IsPlayerInWaterListener.class, this);
		EVENTS.remove(AirStrafingSpeedListener.class, this);
		alertManager.removeListener(this);
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		
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
		
		player.getAbilities().flying = false;
		
		player.setDeltaMovement(0, 0, 0);
		Vec3 velocity = player.getDeltaMovement();
		
		if(MC.options.keyJump.isDown())
			player.setDeltaMovement(velocity.x, verticalSpeed.getValue(),
				velocity.z);
		
		if(MC.options.keyShift.isDown())
			player.setDeltaMovement(velocity.x, -verticalSpeed.getValue(),
				velocity.z);
		
		if(antiKick.isChecked())
			doAntiKick(velocity);
		
		Double alignStep =
			WURST.getHax().spearAssistHack.getAutoAlignmentStepForFlight();
		if(alignStep != null)
		{
			Vec3 current = player.getDeltaMovement();
			player.setDeltaMovement(current.x, alignStep, current.z);
		}
	}
	
	@Override
	public void onGetAirStrafingSpeed(AirStrafingSpeedEvent event)
	{
		float speed = horizontalSpeed.getValueF();
		
		if(MC.options.keyShift.isDown() && slowSneaking.isChecked())
			speed = Math.min(speed, 0.85F);
		
		event.setSpeed(speed);
	}
	
	private void doAntiKick(Vec3 velocity)
	{
		if(tickCounter > antiKickInterval.getValueI() + 1)
			tickCounter = 0;
		
		switch(tickCounter)
		{
			case 0 ->
			{
				if(MC.options.keyShift.isDown())
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
		
		triggered = true;
		
		if(!WURST.getHax().noFallHack.isEnabled())
		{
			WURST.getHax().noFallHack.setEnabled(true);
			enabledNoFallByEscape = true;
		}
		
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
		double step = Math.min(10.0, currentY - escapeTargetY);
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
}
