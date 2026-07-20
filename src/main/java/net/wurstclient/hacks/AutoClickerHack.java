/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.PlayerRangeAlertManager;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.minecraft.client.KeyMapping;

@SearchTags({"auto clicker", "autoclicker", "click spam"})
public final class AutoClickerHack extends Hack
	implements UpdateListener, RenderListener, PlayerRangeAlertManager.Listener
{
	private enum Button
	{
		LEFT("Left"),
		RIGHT("Right");
		
		private final String name;
		
		Button(String n)
		{
			name = n;
		}
		
		public String toString()
		{
			return name;
		}
	}
	
	private enum ClickType
	{
		SINGLE("Single", 1),
		DOUBLE("Double", 2),
		TRIPLE("Triple", 3),
		QUADRUPLE("Quadruple", 4),
		QUINTUPLE("Quintuple", 5);
		
		private final String name;
		final int count;
		
		ClickType(String n, int c)
		{
			name = n;
			count = c;
		}
		
		public String toString()
		{
			return name;
		}
	}
	
	private enum RepeatMode
	{
		TIMES("Repeat times"),
		UNTIL_STOPPED("Until stopped"),
		FOR_TIME("Until time passed"),
		UNTIL_FLAG("Until flag");
		
		private final String name;
		
		RepeatMode(String n)
		{
			name = n;
		}
		
		public String toString()
		{
			return name;
		}
	}
	
	private enum CursorMode
	{
		CURRENT("Currently pointing block"),
		FIXED("Block X/Y/Z"),
		MULTIPLE("Selected blocks");
		
		private final String name;
		
		CursorMode(String n)
		{
			name = n;
		}
		
		public String toString()
		{
			return name;
		}
	}
	
	private final SliderSetting hours =
		new SliderSetting("Hours", 0, 0, 23, 1, ValueDisplay.INTEGER);
	private final SliderSetting minutes =
		new SliderSetting("Minutes", 0, 0, 59, 1, ValueDisplay.INTEGER);
	private final SliderSetting seconds =
		new SliderSetting("Seconds", 0, 0, 59, 1, ValueDisplay.INTEGER);
	private final SliderSetting milliseconds =
		new SliderSetting("Milliseconds", 100, 1, 999, 1, ValueDisplay.INTEGER);
	private final EnumSetting<Button> button =
		new EnumSetting<>("Mouse button", Button.values(), Button.LEFT);
	private final EnumSetting<ClickType> clickType =
		new EnumSetting<>("Click type", ClickType.values(), ClickType.SINGLE);
	private final EnumSetting<RepeatMode> repeatMode = new EnumSetting<>(
		"Click repeat", RepeatMode.values(), RepeatMode.UNTIL_STOPPED);
	private final SliderSetting repeatTimes = new SliderSetting("Repeat times",
		10, 1, 10000, 1, ValueDisplay.INTEGER);
	private final SliderSetting timeHours =
		new SliderSetting("Time hours", 0, 0, 23, 1, ValueDisplay.INTEGER);
	private final SliderSetting timeMinutes =
		new SliderSetting("Time minutes", 0, 0, 59, 1, ValueDisplay.INTEGER);
	private final SliderSetting timeSeconds =
		new SliderSetting("Time seconds", 10, 0, 59, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting antisocial =
		new CheckboxSetting("Antisocial", true);
	private final CheckboxSetting staffMonitor =
		new CheckboxSetting("StaffMonitor", true);
	private final CheckboxSetting healthDamage =
		new CheckboxSetting("Health/damage taken", true);
	private final CheckboxSetting playerMoved =
		new CheckboxSetting("Player moved", false);
	private final CheckboxSetting simulationSonar =
		new CheckboxSetting("SimulationSonar detected", true);
	private final CheckboxSetting allowFlagAction =
		new CheckboxSetting("Allow flag hack defaults", false);
	private final CheckboxSetting continueAfterFlag =
		new CheckboxSetting("Continue if flag is dropped", false);
	private final EnumSetting<CursorMode> cursorMode = new EnumSetting<>(
		"Block target", CursorMode.values(), CursorMode.CURRENT);
	private final SliderSetting cursorX = new SliderSetting("Block X", 0,
		-30000000, 30000000, 1, ValueDisplay.INTEGER);
	private final SliderSetting cursorY =
		new SliderSetting("Block Y", 64, -64, 319, 1, ValueDisplay.INTEGER);
	private final SliderSetting cursorZ = new SliderSetting("Block Z", 0,
		-30000000, 30000000, 1, ValueDisplay.INTEGER);
	private final ButtonSetting selectLocation = new ButtonSetting(
		"Select block(s) (right-click, press Enter)", this::beginSelection);
	private final List<BlockTarget> locations = new ArrayList<>();
	private final ButtonSetting clearLocations =
		new ButtonSetting("Clear multiple locations", locations::clear);
	private final Set<PlayerRangeAlertManager.PlayerInfo> rangeFlags =
		ConcurrentHashMap.newKeySet();
	private final PlayerRangeAlertManager rangeAlerts =
		WURST.getPlayerRangeAlertManager();
	private long nextClick, stopAt;
	private int completedClicks, clickInBurst;
	private boolean selecting;
	private boolean pausedByFlag;
	private String lastPauseReason;
	private BlockTarget hoveredTarget;
	private BlockTarget singleTarget;
	private BlockTarget fixedTarget;
	private boolean useWasDown, enterWasDown;
	private double lastX, lastY, lastZ;
	private float lastHealth;
	
	public AutoClickerHack()
	{
		super("AutoClicker");
		setCategory(Category.BLOCKS);
		addSetting(hours);
		addSetting(minutes);
		addSetting(seconds);
		addSetting(milliseconds);
		addSetting(button);
		addSetting(clickType);
		addSetting(repeatMode);
		addSetting(repeatTimes);
		addSetting(timeHours);
		addSetting(timeMinutes);
		addSetting(timeSeconds);
		addSetting(antisocial);
		addSetting(staffMonitor);
		addSetting(healthDamage);
		addSetting(playerMoved);
		addSetting(simulationSonar);
		addSetting(allowFlagAction);
		addSetting(continueAfterFlag);
		addSetting(cursorMode);
		addSetting(cursorX);
		addSetting(cursorY);
		addSetting(cursorZ);
		addSetting(selectLocation);
		addSetting(clearLocations);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null)
		{
			ChatUtils.error("AutoClicker stopped: no player is loaded.");
			setEnabled(false);
			return;
		}
		lastX = MC.player.getX();
		lastY = MC.player.getY();
		lastZ = MC.player.getZ();
		lastHealth = MC.player.getHealth();
		completedClicks = clickInBurst = 0;
		pausedByFlag = false;
		lastPauseReason = null;
		nextClick = System.currentTimeMillis();
		stopAt = nextClick + timeHours.getValueI() * 3600000L
			+ timeMinutes.getValueI() * 60000L
			+ timeSeconds.getValueI() * 1000L;
		rangeAlerts.addListener(this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		if(!selecting)
		{
			EVENTS.remove(UpdateListener.class, this);
			EVENTS.remove(RenderListener.class, this);
		}
		rangeAlerts.removeListener(this);
		releaseKeys();
		rangeFlags.clear();
	}
	
	@Override
	public void onUpdate()
	{
		if(selecting)
		{
			updateBlockSelection();
			return;
		}
		if(!isEnabled())
		{
			EVENTS.remove(UpdateListener.class, this);
			EVENTS.remove(RenderListener.class, this);
			return;
		}
		if(MC.player == null)
			return;
		// A simulated click is delivered to the active screen as a real mouse
		// event. Never let it operate the ClickGUI, where it could toggle this
		// feature (or change another setting) under its own cursor.
		if(MC.gui.screen() instanceof ClickGuiScreen)
			return;
		boolean moved = MC.player.distanceToSqr(lastX, lastY, lastZ) > 0.0001;
		boolean damaged =
			MC.player.getHealth() < lastHealth || MC.player.hurtTime > 0;
		lastX = MC.player.getX();
		lastY = MC.player.getY();
		lastZ = MC.player.getZ();
		lastHealth = MC.player.getHealth();
		String flagReason = getFlagReason(damaged, moved);
		if(flagReason != null)
		{
			if(!pausedByFlag || !flagReason.equals(lastPauseReason))
				ChatUtils.message("AutoClicker paused: " + flagReason);
			pausedByFlag = true;
			lastPauseReason = flagReason;
			releaseKeys();
			return;
		}
		if(pausedByFlag)
		{
			if(!continueAfterFlag.isChecked())
			{
				releaseKeys();
				return;
			}
			pausedByFlag = false;
			lastPauseReason = null;
			ChatUtils.message("AutoClicker resumed: flag is no longer active.");
		}
		if(repeatMode.getSelected() == RepeatMode.TIMES
			&& completedClicks >= repeatTimes.getValueI())
		{
			stopAndDisable("repeat count reached (" + completedClicks + ").");
			return;
		}
		if(repeatMode.getSelected() == RepeatMode.FOR_TIME
			&& System.currentTimeMillis() >= stopAt)
		{
			stopAndDisable("time limit reached.");
			return;
		}
		if(System.currentTimeMillis() < nextClick)
			return;
		if(clickInBurst < clickType.getSelected().count)
		{
			try
			{
				click();
			}catch(RuntimeException e)
			{
				stopAndDisable("click failed: " + e.getClass().getSimpleName()
					+ (e.getMessage() == null ? "." : " - " + e.getMessage()));
				return;
			}
			clickInBurst++;
			nextClick = System.currentTimeMillis()
				+ Math.max(1, milliseconds.getValueI());
		}else
		{
			clickInBurst = 0;
			completedClicks++;
			nextClick = System.currentTimeMillis() + intervalMillis();
		}
	}
	
	private String getFlagReason(boolean damaged, boolean moved)
	{
		if(antisocial.isChecked() && !rangeFlags.isEmpty())
			return "Antisocial player entered range";
		if(staffMonitor.isChecked() && staffDetected())
			return "StaffMonitor detected staff or a hidden player";
		if(healthDamage.isChecked() && damaged)
			return "health decreased or damage was taken";
		if(playerMoved.isChecked() && moved)
			return "player movement detected";
		if(simulationSonar.isChecked() && sonarDetected())
			return "SimulationSonar detected a source";
		return null;
	}
	
	private void stopAndDisable(String reason)
	{
		ChatUtils.message("AutoClicker stopped: " + reason);
		setEnabled(false);
	}
	
	private long intervalMillis()
	{
		return hours.getValueI() * 3600000L + minutes.getValueI() * 60000L
			+ seconds.getValueI() * 1000L + milliseconds.getValueI();
	}
	
	private void click()
	{
		BlockTarget target = getBlockTarget();
		if(target != null)
		{
			clickBlock(target);
			return;
		}
		KeyMapping key = button.getSelected() == Button.LEFT
			? MC.options.keyAttack : MC.options.keyUse;
		IKeyMapping.get(key).simulatePress(true);
		IKeyMapping.get(key).simulatePress(false);
	}
	
	private BlockTarget getBlockTarget()
	{
		if(cursorMode.getSelected() == CursorMode.CURRENT)
			return null;
		if(cursorMode.getSelected() == CursorMode.FIXED)
		{
			BlockPos pos = new BlockPos(cursorX.getValueI(),
				cursorY.getValueI(), cursorZ.getValueI());
			if(fixedTarget != null && fixedTarget.pos.equals(pos))
				return fixedTarget;
			return new BlockTarget(pos, Direction.UP, Vec3.atCenterOf(pos));
		}
		if(locations.isEmpty())
			return null;
		return locations.get(completedClicks % locations.size());
	}
	
	private void clickBlock(BlockTarget target)
	{
		WURST.getRotationFaker().faceVectorClient(target.hitPos);
		if(button.getSelected() == Button.LEFT)
		{
			MC.gameMode.startDestroyBlock(target.pos, target.side);
			MC.player.swing(InteractionHand.MAIN_HAND);
			return;
		}
		BlockHitResult hit =
			new BlockHitResult(target.hitPos, target.side, target.pos, false);
		MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit);
		MC.player.swing(InteractionHand.MAIN_HAND);
	}
	
	private void releaseKeys()
	{
		IKeyMapping.get(MC.options.keyAttack).simulatePress(false);
		IKeyMapping.get(MC.options.keyUse).simulatePress(false);
	}
	
	private void beginSelection()
	{
		if(MC.player == null)
		{
			ChatUtils
				.error("AutoClicker: join a world before selecting blocks.");
			return;
		}
		selecting = true;
		hoveredTarget = null;
		singleTarget = null;
		useWasDown = enterWasDown = false;
		if(cursorMode.getSelected() == CursorMode.MULTIPLE)
			locations.clear();
		MC.gui.setScreen(null);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		ChatUtils
			.message("AutoClicker: look at a block and right-click to select it"
				+ (cursorMode.getSelected() == CursorMode.MULTIPLE
					? "; press Enter when all blocks are selected."
					: "; press Enter to confirm."));
	}
	
	private boolean staffDetected()
	{
		return WURST.getHax().staffMonitorHack.isEnabled()
			&& WURST.getHax().staffMonitorHack.hasDetectedStaff();
	}
	
	private boolean sonarDetected()
	{
		return WURST.getHax().simulationSonarHack.isEnabled()
			&& WURST.getHax().simulationSonarHack.hasDetectedSource();
	}
	
	@Override
	public void onPlayerEnter(net.minecraft.world.entity.player.Player p,
		PlayerRangeAlertManager.PlayerInfo info)
	{
		rangeFlags.add(info);
	}
	
	@Override
	public void onPlayerExit(PlayerRangeAlertManager.PlayerInfo info)
	{
		rangeFlags.remove(info);
	}
	
	private void updateBlockSelection()
	{
		hoveredTarget = getCrosshairTarget();
		boolean useDown = MC.options.keyUse.isDown();
		if(useDown && !useWasDown && hoveredTarget != null)
		{
			if(cursorMode.getSelected() == CursorMode.MULTIPLE)
			{
				if(!locations.contains(hoveredTarget))
					locations.add(hoveredTarget);
				ChatUtils.message("AutoClicker: selected block "
					+ hoveredTarget.pos.toShortString() + " ("
					+ locations.size() + " total).");
			}else
			{
				singleTarget = hoveredTarget;
				ChatUtils.message("AutoClicker: selected block "
					+ hoveredTarget.pos.toShortString()
					+ ". Press Enter to confirm.");
			}
		}
		useWasDown = useDown;
		boolean enterDown =
			InputConstants.isKeyDown(MC.getWindow(), GLFW.GLFW_KEY_ENTER);
		if(enterDown && !enterWasDown
			&& (cursorMode.getSelected() == CursorMode.MULTIPLE
				? !locations.isEmpty() : singleTarget != null))
			finishBlockSelection();
		enterWasDown = enterDown;
	}
	
	private BlockTarget getCrosshairTarget()
	{
		if(!(MC.hitResult instanceof BlockHitResult hit))
			return null;
		BlockPos pos = hit.getBlockPos();
		if(MC.options.keyShift.isDown())
			pos = pos.relative(hit.getDirection());
		return new BlockTarget(pos, hit.getDirection(), hit.getLocation());
	}
	
	private void finishBlockSelection()
	{
		if(cursorMode.getSelected() != CursorMode.MULTIPLE)
		{
			fixedTarget = singleTarget;
			cursorX.setValue(singleTarget.pos.getX());
			cursorY.setValue(singleTarget.pos.getY());
			cursorZ.setValue(singleTarget.pos.getZ());
			cursorMode.setSelected(CursorMode.FIXED);
		}
		selecting = false;
		ChatUtils.message("AutoClicker: block selection confirmed ("
			+ (cursorMode.getSelected() == CursorMode.MULTIPLE
				? locations.size() : 1)
			+ " block" + (cursorMode.getSelected() == CursorMode.MULTIPLE
				&& locations.size() != 1 ? "s)." : ")."));
		if(!isEnabled())
		{
			EVENTS.remove(UpdateListener.class, this);
			EVENTS.remove(RenderListener.class, this);
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(hoveredTarget != null)
			drawTarget(matrixStack, hoveredTarget, 0x26404040);
		if(singleTarget != null)
			drawTarget(matrixStack, singleTarget, 0x2600FFFF);
		for(BlockTarget target : locations)
			drawTarget(matrixStack, target, 0x2600FF00);
	}
	
	private void drawTarget(PoseStack matrixStack, BlockTarget target,
		int fillColor)
	{
		AABB box = new AABB(target.pos).deflate(1 / 16.0);
		RenderUtils.drawOutlinedBox(matrixStack, box, 0x80000000, false);
		RenderUtils.drawSolidBox(matrixStack, box, fillColor, false);
	}
	
	private record BlockTarget(BlockPos pos, Direction side, Vec3 hitPos)
	{}
}
