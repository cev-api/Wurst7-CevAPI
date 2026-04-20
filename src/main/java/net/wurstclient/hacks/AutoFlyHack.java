/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.events.RenderListener;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.FileSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.MathUtils;
import net.wurstclient.util.RenderUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;
import net.wurstclient.util.chunk.ChunkSearcher.Result;

@SearchTags({"auto fly", "autofly", "waypoint fly", "auto flight"})
public final class AutoFlyHack extends Hack
	implements UpdateListener, GUIRenderListener, RenderListener
{
	private static final int STOP_SCAN_COOLDOWN_TICKS = 10;
	private static final double COMMAND_FORWARD_DISTANCE = 100000.0;
	private static final int CHUNK_TRAIL_LOOKAHEAD = 5;
	private static final ChunkAreaSetting.ChunkArea STOP_BLOCK_AREA =
		ChunkAreaSetting.ChunkArea.A65;
	
	public static enum RouteType
	{
		WAYPOINTS("Waypoints"),
		GRID("Grid"),
		CHUNKS("Chunk trail");
		
		private final String name;
		
		RouteType(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public static enum StopOnType
	{
		OFF("Off"),
		MOBS("Mobs"),
		BLOCKS("Blocks"),
		ITEMS("Items"),
		OLD_CHUNKS("Old chunk"),
		NEW_CHUNKS("New chunk"),
		END_PORTAL("End portal"),
		NETHER_PORTAL("Nether portal");
		
		private final String name;
		
		StopOnType(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private final TextFieldSetting waypointText = new TextFieldSetting(
		"Waypoints",
		"Waypoints list. Format: x y z or x z (no Y). Separate by ';' or new lines.",
		"");
	
	private final EnumSetting<RouteType> routeType = new EnumSetting<>(
		"Route type",
		"Where AutoFly gets its targets from.\n\n"
			+ "Waypoints: Use the Waypoints list above (or JSON if empty).\n"
			+ "Grid: Generate a square search grid from your current position.\n"
			+ "Chunk trail: Follow the green NewerNewChunks corridor.",
		RouteType.values(), RouteType.WAYPOINTS);
	
	private final SliderSetting gridWidthChunks =
		new SliderSetting("Grid width",
			"How many chunks wide the search area should be.\n\n"
				+ "AutoFly flies through the center of each chunk column.",
			2, 1, 512, 1, ValueDisplay.INTEGER.withSuffix(" chunks"));
	
	private final SliderSetting gridDepthChunks = new SliderSetting(
		"Grid depth",
		"How many chunks deep the search area should be.\n\n"
			+ "At the end of each column, AutoFly turns and flies back down the next one.",
		2, 1, 512, 1, ValueDisplay.INTEGER.withSuffix(" chunks"));
	
	private final SliderSetting gridPathWidthChunks = new SliderSetting(
		"Path width",
		"How many chunks AutoFly should skip sideways before starting the next pass.\n\n"
			+ "1 = every chunk column.\n" + "2 = every second chunk column.\n"
			+ "3 = every third chunk column, etc.",
		1, 1, 64, 1, ValueDisplay.INTEGER.withSuffix(" chunks"));
	
	private final CheckboxSetting showGridPath = new CheckboxSetting(
		"Show grid path",
		"Draw the planned grid route in the world (similar to Breadcrumbs).",
		true);
	private final ColorSetting gridPathColor =
		new ColorSetting("Grid path color",
			"Color used for the grid path overlay.", new Color(64, 196, 255));
	private final SliderSetting gridPathThickness =
		new SliderSetting("Grid path thickness", 2.0, 1.0, 10.0, 1.0,
			ValueDisplay.INTEGER.withSuffix(" px"));
	private final SliderSetting gridPathMaxPoints =
		new SliderSetting("Grid path points",
			"How many upcoming grid points to draw (higher = more CPU/GPU).",
			800, 50, 5000, 50, ValueDisplay.INTEGER.withSuffix(" points"));
	
	private final ButtonSetting startGridButton = new ButtonSetting(
		"Start grid",
		"Generate grid targets from your current position and start flying.",
		this::startGridFromPlayer);
	private final TextFieldSetting importFile = new TextFieldSetting(
		"Import file",
		"SeedMapper export JSON filename. Leave empty to use the latest file in seedmapper/exports.",
		"");
	private final FileSetting exportJsonPicker =
		new FileSetting("Export JSON", "", "../seedmapper/exports", folder -> {
			try
			{
				java.nio.file.Files.createDirectories(folder);
				java.nio.file.Path p =
					folder.resolve("autofly-placeholder.json");
				if(!java.nio.file.Files.exists(p))
					java.nio.file.Files.writeString(p, "[]\n");
			}catch(java.io.IOException e)
			{
				throw new RuntimeException(e);
			}
		});
	private final ButtonSetting reloadJsonButton =
		new ButtonSetting("Reload JSON", this::reloadJsonTargets);
	private final ButtonSetting previousButton =
		new ButtonSetting("Previous waypoint", this::selectPreviousTarget);
	private final ButtonSetting nextButton =
		new ButtonSetting("Next waypoint", this::selectNextTargetFromButton);
	private final SliderSetting flightHeight =
		new SliderSetting("Flight height", "Cruise Y level while traveling.",
			80, -64, 320, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting flightSpeed = new SliderSetting("Flight speed",
		"Temporary Flight horizontal speed while AutoFly is active.", 6.0, 0.5,
		10.0, 0.1, ValueDisplay.DECIMAL.withSuffix(" b/s"));
	private final SliderSetting targetRadius = new SliderSetting(
		"Target radius", "Distance to consider a waypoint reached.", 4.0, 1.0,
		64.0, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final CheckboxSetting skipReached =
		new CheckboxSetting("Skip reached",
			"Skip waypoints that are already within the target radius.", true);
	private final CheckboxSetting crosshairInfo =
		new CheckboxSetting("Crosshair info",
			"Shows AutoFly status near the crosshair while active.", true);
	private final CheckboxSetting useAntisocial =
		new CheckboxSetting("Enable Antisocial",
			"Enables Antisocial while AutoFly is active.", true);
	private final CheckboxSetting useAutoEat = new CheckboxSetting(
		"Enable AutoEat", "Enables AutoEat while AutoFly is active.", true);
	private final CheckboxSetting useAutoLeave = new CheckboxSetting(
		"Enable AutoLeave", "Enables AutoLeave while AutoFly is active.", true);
	private final CheckboxSetting ignoreWaypointList = new CheckboxSetting(
		"Ignore Waypoints list",
		"When loading JSON, skip targets within 150 blocks of existing Waypoints.",
		true);
	private final CheckboxSetting allowManualAdjust = new CheckboxSetting(
		"Allow manual adjust",
		"When stuck, release controls so you can move; AutoFly resumes once you move.",
		true);
	private final CheckboxSetting disableFlightOnArrival =
		new CheckboxSetting("Disable Flight on arrival",
			"Turns off Flight when AutoFly reaches a waypoint.", false);
	private final CheckboxSetting disableAutoFlyOnArrival =
		new CheckboxSetting("Disable AutoFly on arrival",
			"Turns off AutoFly when it reaches a waypoint.", false);
	
	private final EnumSetting<StopOnType> stopOn = new EnumSetting<>("Stop on",
		"Stop AutoFly if it detects something while flying.",
		StopOnType.values(), StopOnType.OFF);
	private final TextFieldSetting stopKeyword = new TextFieldSetting(
		"Stop keyword",
		"Keyword to match against the selected Stop on type (ignored for portals).",
		"");
	private final EnumSetting<StopOnType> stopOn2 = new EnumSetting<>(
		"Stop on 2",
		"Optional second stop reason. AutoFly stops if either Stop on setting matches.",
		StopOnType.values(), StopOnType.OFF);
	private final TextFieldSetting stopKeyword2 = new TextFieldSetting(
		"Stop keyword 2",
		"Keyword to match against the second Stop on type (ignored for portals).",
		"");
	private final CheckboxSetting disableAutoFlyOnStop = new CheckboxSetting(
		"Disable on stop",
		"When AutoFly stops due to a Stop on event, fully disable AutoFly (equivalent to .autofly stop) instead of holding position.",
		false);
	private final CheckboxSetting disableOnPlayers = new CheckboxSetting(
		"Disable on players",
		"Disable AutoFly entirely if another player entity is detected nearby.",
		false);
	private final CheckboxSetting disableOnDamage =
		new CheckboxSetting("Disable on damage",
			"Disable AutoFly entirely when you take damage.", false);
	
	private final List<AutoFlyTarget> targets = new ArrayList<>();
	private AutoFlyTarget currentTarget;
	private int currentIndex = -1;
	private final List<ChunkPos> chunkTrailPath = new ArrayList<>();
	private ChunkPos chunkCorridorAnchor;
	private Vec3 chunkCorridorOrigin;
	private Vec3 chunkCorridorHeading;
	private Vec3 chunkCorridorTargetPos;
	private boolean chunkAssistActive;
	private boolean pausedNoY;
	private boolean useExistingTargetsOnEnable;
	private boolean arrivalPause;
	private long arrivalPauseUntilMs;
	private boolean arrivedMessageSent;
	private boolean arrivedHold;
	private boolean manualAdjustHold;
	private long manualAdjustStartMs;
	private Vec3 manualAdjustStartPos;
	private long lastManualInputMs;
	private long lastManualAdjustExitMs;
	private VerticalMode verticalMode = VerticalMode.NONE;
	private long lastAutoControlMs;
	
	private PathFinder pathFinder;
	private PathProcessor pathProcessor;
	private BlockPos recoveryGoal;
	private long lastProgressMs;
	private double lastProgressDist = Double.NaN;
	private long lastRepathMs;
	private int stuckRepathCount;
	private Vec3 lastMovePos;
	private long lastMoveMs;
	private Vec3 lastHorizPos;
	private long lastHorizMoveMs;
	private boolean autoKeyUpDown;
	private boolean autoKeyDownDown;
	private boolean autoKeyLeftDown;
	private boolean autoKeyRightDown;
	private boolean autoKeyJumpDown;
	private boolean autoKeyShiftDown;
	private long climbAttemptUntilMs;
	private long lastClimbAttemptMs;
	private double climbTargetY;
	
	private boolean flightWasEnabled;
	private double savedFlightSpeed = -1;
	private double savedFlightVSpeed = -1;
	private double lastYForProgress = Double.NaN;
	private long lastVerticalProgressMs;
	private boolean verticalAssistActive;
	private boolean enabledAntisocialForAutoFly;
	private boolean enabledAutoEatForAutoFly;
	private boolean enabledAutoLeaveForAutoFly;
	
	private boolean closeHorizLatched;
	private int stopScanCooldown;
	private ChunkSearcherCoordinator stopBlockCoordinator;
	private StopOnType stopBlockCoordinatorType;
	private String stopBlockCoordinatorKeyword;
	private ChunkSearcherCoordinator stopBlockCoordinator2;
	private StopOnType stopBlockCoordinatorType2;
	private String stopBlockCoordinatorKeyword2;
	private boolean stopHold;
	private int stopIgnoreTicks;
	
	public AutoFlyHack()
	{
		super("AutoFly");
		setCategory(Category.MOVEMENT);
		addSetting(waypointText);
		addSetting(routeType);
		addSetting(gridWidthChunks);
		addSetting(gridDepthChunks);
		addSetting(gridPathWidthChunks);
		addSetting(showGridPath);
		addSetting(gridPathColor);
		addSetting(gridPathThickness);
		addSetting(gridPathMaxPoints);
		addSetting(startGridButton);
		addSetting(importFile);
		addSetting(exportJsonPicker);
		addSetting(reloadJsonButton);
		addSetting(previousButton);
		addSetting(nextButton);
		addSetting(flightHeight);
		addSetting(flightSpeed);
		addSetting(targetRadius);
		addSetting(skipReached);
		addSetting(crosshairInfo);
		addSetting(useAntisocial);
		addSetting(useAutoEat);
		addSetting(useAutoLeave);
		addSetting(ignoreWaypointList);
		addSetting(allowManualAdjust);
		addSetting(disableFlightOnArrival);
		addSetting(disableAutoFlyOnArrival);
		addSetting(stopOn);
		addSetting(stopKeyword);
		addSetting(stopOn2);
		addSetting(stopKeyword2);
		addSetting(disableAutoFlyOnStop);
		addSetting(disableOnPlayers);
		addSetting(disableOnDamage);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null || MC.level == null)
		{
			ChatUtils.error("Join a world before enabling AutoFly.");
			setEnabled(false);
			return;
		}
		
		if(useExistingTargetsOnEnable)
			useExistingTargetsOnEnable = false;
		else
		{
			if(routeType.getSelected() == RouteType.GRID)
				loadTargetsFromGrid(MC.player.blockPosition());
			else if(routeType.getSelected() == RouteType.CHUNKS)
			{
				targets.clear();
				currentTarget = null;
				currentIndex = -1;
				chunkTrailPath.clear();
			}else
				loadTargetsFromSettings();
		}
		if(targets.isEmpty())
		{
			ChatUtils.error("No AutoFly waypoints loaded.");
			setEnabled(false);
			return;
		}
		
		pausedNoY = false;
		arrivalPause = false;
		arrivalPauseUntilMs = 0L;
		arrivedMessageSent = false;
		arrivedHold = false;
		manualAdjustHold = false;
		manualAdjustStartMs = 0L;
		manualAdjustStartPos = null;
		lastManualInputMs = 0L;
		lastAutoControlMs = 0L;
		lastManualAdjustExitMs = 0L;
		verticalMode = VerticalMode.NONE;
		recoveryGoal = null;
		pathFinder = null;
		pathProcessor = null;
		lastProgressMs = System.currentTimeMillis();
		lastProgressDist = Double.NaN;
		lastRepathMs = 0L;
		stuckRepathCount = 0;
		lastMovePos = MC.player.position();
		lastMoveMs = System.currentTimeMillis();
		lastHorizPos = lastMovePos;
		lastHorizMoveMs = lastMoveMs;
		autoKeyUpDown = false;
		autoKeyDownDown = false;
		autoKeyLeftDown = false;
		autoKeyRightDown = false;
		autoKeyJumpDown = false;
		autoKeyShiftDown = false;
		climbAttemptUntilMs = 0L;
		lastClimbAttemptMs = 0L;
		climbTargetY = 0.0;
		currentIndex = -1;
		currentTarget = null;
		chunkTrailPath.clear();
		closeHorizLatched = false;
		stopScanCooldown = 0;
		stopBlockCoordinator = null;
		stopBlockCoordinatorType = null;
		stopBlockCoordinatorKeyword = null;
		stopBlockCoordinator2 = null;
		stopBlockCoordinatorType2 = null;
		stopBlockCoordinatorKeyword2 = null;
		stopHold = false;
		stopIgnoreTicks = 0;
		selectNextTarget(false);
		flightWasEnabled = WURST.getHax().flightHack.isEnabled();
		savedFlightSpeed = -1;
		savedFlightVSpeed = -1;
		lastYForProgress = Double.NaN;
		lastVerticalProgressMs = System.currentTimeMillis();
		verticalAssistActive = false;
		applyFlightSettings();
		enabledAntisocialForAutoFly = false;
		enabledAutoEatForAutoFly = false;
		enabledAutoLeaveForAutoFly = false;
		
		var hax = WURST.getHax();
		if(useAntisocial.isChecked() && !hax.antisocialHack.isEnabled())
		{
			hax.antisocialHack.setEnabled(true);
			enabledAntisocialForAutoFly = true;
		}
		if(useAutoEat.isChecked() && !hax.autoEatHack.isEnabled())
		{
			hax.autoEatHack.setEnabled(true);
			enabledAutoEatForAutoFly = true;
		}
		if(useAutoLeave.isChecked() && !hax.autoLeaveHack.isEnabled())
		{
			hax.autoLeaveHack.setEnabled(true);
			enabledAutoLeaveForAutoFly = true;
		}
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(GUIRenderListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(GUIRenderListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		PathProcessor.releaseControls();
		restoreFlightSettings();
		var hax = WURST.getHax();
		if(enabledAntisocialForAutoFly && hax.antisocialHack.isEnabled())
			hax.antisocialHack.setEnabled(false);
		if(enabledAutoEatForAutoFly && hax.autoEatHack.isEnabled())
			hax.autoEatHack.setEnabled(false);
		if(enabledAutoLeaveForAutoFly && hax.autoLeaveHack.isEnabled())
			hax.autoLeaveHack.setEnabled(false);
		enabledAntisocialForAutoFly = false;
		enabledAutoEatForAutoFly = false;
		enabledAutoLeaveForAutoFly = false;
		pausedNoY = false;
		arrivalPause = false;
		arrivalPauseUntilMs = 0L;
		arrivedMessageSent = false;
		arrivedHold = false;
		manualAdjustHold = false;
		manualAdjustStartMs = 0L;
		manualAdjustStartPos = null;
		lastManualInputMs = 0L;
		lastAutoControlMs = 0L;
		lastManualAdjustExitMs = 0L;
		verticalMode = VerticalMode.NONE;
		recoveryGoal = null;
		pathFinder = null;
		pathProcessor = null;
		lastMovePos = null;
		lastMoveMs = 0L;
		lastHorizPos = null;
		lastHorizMoveMs = 0L;
		clearChunkCorridorAssist();
		autoKeyUpDown = false;
		autoKeyDownDown = false;
		autoKeyLeftDown = false;
		autoKeyRightDown = false;
		autoKeyJumpDown = false;
		autoKeyShiftDown = false;
		climbAttemptUntilMs = 0L;
		lastClimbAttemptMs = 0L;
		climbTargetY = 0.0;
		currentTarget = null;
		currentIndex = -1;
		clearChunkCorridorAssist();
		savedFlightVSpeed = -1;
		closeHorizLatched = false;
		stopHold = false;
		stopIgnoreTicks = 0;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
		{
			setEnabled(false);
			return;
		}
		
		if(stopIgnoreTicks > 0)
			stopIgnoreTicks--;
		
		if(stopHold)
		{
			// Allow player to decide what to do next (e.g. cycle waypoint).
			PathProcessor.releaseControls();
			resetAutoKeyFlags();
			clearMovementKeys();
			return;
		}
		
		boolean gridRoute = routeType.getSelected() == RouteType.GRID;
		boolean chunkRoute = routeType.getSelected() == RouteType.CHUNKS;
		boolean cruisingRoute = gridRoute || chunkRoute;
		
		if(!chunkRoute && currentTarget == null)
		{
			selectNextTarget(false);
			if(currentTarget == null)
			{
				setEnabled(false);
				return;
			}
		}
		
		if(checkStopOn(stopOn, stopKeyword, false))
			return;
		
		if(checkStopOn(stopOn2, stopKeyword2, true))
			return;
		
		if(checkStopOnPlayers())
			return;
		
		if(checkDisableOnDamage())
			return;
		
		if(allowManualAdjust.isChecked() && isManualInputActive())
		{
			beginManualAdjust(MC.player.position());
			return;
		}
		
		if(arrivalPause)
		{
			if(System.currentTimeMillis() < arrivalPauseUntilMs)
				return;
			arrivalPause = false;
			arrivalPauseUntilMs = 0L;
		}
		
		if(arrivedHold)
		{
			return;
		}
		
		if(manualAdjustHold)
		{
			handleManualAdjust();
			return;
		}
		
		long now = System.currentTimeMillis();
		if(climbAttemptUntilMs > now)
		{
			ensureFlightEnabled();
			applyFlightSpeed();
			resetAutoKeyFlags();
			PathProcessor.lockControls();
			clearMovementKeys();
			autoSetKey(MC.options.keyJump, true);
			lastAutoControlMs = now;
			return;
		}
		
		ensureFlightEnabled();
		applyFlightSpeed();
		if(chunkAssistActive)
			applyChunkCorridorAssist();
		
		if(pausedNoY)
			return;
		
		if(pathFinder != null)
		{
			// Ground path recovery is unreliable while airborne (e.g. nether
			// roof cruise). Fall back to direct flight controls.
			if(MC.player != null && !MC.player.onGround())
			{
				PathProcessor.releaseControls();
				clearPathingState();
			}
			
			if(processPathFinder())
				return;
			clearPathingState();
		}
		
		double radius = targetRadius.getValue();
		Vec3 playerPos = MC.player.position();
		double targetX = currentTarget.pos.getX() + 0.5;
		double targetZ = currentTarget.pos.getZ() + 0.5;
		if(chunkAssistActive && chunkCorridorTargetPos != null)
		{
			targetX = chunkCorridorTargetPos.x;
			targetZ = chunkCorridorTargetPos.z;
		}
		double dx = targetX - playerPos.x;
		double dz = targetZ - playerPos.z;
		double distHoriz = Math.hypot(dx, dz);
		
		double exitRadius = radius + 3.0;
		if(!closeHorizLatched)
		{
			if(distHoriz <= radius)
				closeHorizLatched = true;
		}else
		{
			if(distHoriz > exitRadius)
				closeHorizLatched = false;
		}
		boolean closeHoriz = closeHorizLatched;
		
		double cruiseY = getCruiseY(currentTarget);
		double desiredY;
		double landingYNoY = Double.NaN;
		// Begin descent early to avoid cruiseY interfering; always at least 20b
		double descentStartRadius =
			Math.max(20.0, Math.max(radius * 2.0, radius + 6.0));
		
		// Grid and chunk-trail routes are meant for horizontal cruising, not
		// landing at each point.
		// Keep a constant cruise altitude and treat targets as reached based on
		// horizontal distance only.
		if(cruisingRoute)
		{
			desiredY = cruiseY;
		}else if(currentTarget.hasY)
		{
			boolean approachHoriz = distHoriz <= descentStartRadius;
			desiredY = (closeHoriz || approachHoriz)
				? getEffectiveTargetY(currentTarget, playerPos, true) : cruiseY;
		}else
		{
			if(closeHoriz)
			{
				BlockPos lp = resolveLandingPosition(
					new BlockPos(currentTarget.pos.getX(),
						MC.level.getMaxY() - 2, currentTarget.pos.getZ()));
				landingYNoY = lp != null ? lp.getY() : playerPos.y;
				desiredY = landingYNoY;
			}else
				desiredY = cruiseY;
		}
		
		double yDiff = desiredY - playerPos.y;
		
		boolean reached = cruisingRoute ? (distHoriz <= radius)
			: isTargetReached(currentTarget, playerPos, radius);
		
		if(reached)
		{
			if(cruisingRoute)
			{
				if(chunkRoute)
					advanceChunkTrailTarget();
				else
					advanceCruiseTarget();
				return;
			}
			
			handleTargetReached();
			return;
		}
		
		now = System.currentTimeMillis();
		resetAutoKeyFlags();
		PathProcessor.lockControls();
		clearMovementKeys();
		
		boolean approachHoriz = distHoriz <= descentStartRadius;
		boolean nearTargetY =
			(closeHoriz || (currentTarget.hasY && approachHoriz))
				&& (currentTarget.hasY || !Double.isNaN(landingYNoY));
		double innerForwardRadius = Math.max(0.6, Math.min(1.5, radius * 0.6));
		double innerYawStopRadius = Math.max(0.5, Math.min(1.0, radius * 0.4));
		boolean needForward = distHoriz > innerForwardRadius;
		boolean adjustYaw = distHoriz > innerYawStopRadius;
		
		if(!closeHoriz || !currentTarget.hasY)
		{
			if(adjustYaw)
				WURST.getRotationFaker().faceVectorClientIgnorePitch(
					new Vec3(targetX, playerPos.y, targetZ));
			autoSetKey(MC.options.keyUp, needForward);
		}else
		{
			if(adjustYaw)
				WURST.getRotationFaker().faceVectorClientIgnorePitch(
					new Vec3(targetX, playerPos.y, targetZ));
			autoSetKey(MC.options.keyUp, needForward);
		}
		
		// Force a final descend near center to ensure landing completes
		boolean finalLanding = distHoriz <= Math.max(radius, 1.0) + 0.5;
		if(finalLanding)
			autoSetKey(MC.options.keyShift, true);
		
		applyVerticalAssist(playerPos, yDiff, nearTargetY);
		updateVerticalControls(yDiff, nearTargetY);
		if(anyAutoKeyDown())
			lastAutoControlMs = now;
		
		updateProgressTracking(playerPos);
		updateMovementTracking(playerPos);
		updateVerticalProgress(playerPos);
		if(shouldRepath(playerPos, distHoriz))
		{
			if(allowManualAdjust.isChecked() && isManualInputActive())
				beginManualAdjust(playerPos);
			else
				startRecoveryPath(playerPos);
			return;
		}
	}
	
	private void advanceCruiseTarget()
	{
		// Stop immediately to avoid overshooting and oscillation at pass turns.
		PathProcessor.releaseControls();
		resetAutoKeyFlags();
		clearMovementKeys();
		clearPathingState();
		closeHorizLatched = false;
		verticalMode = VerticalMode.NONE;
		
		selectNextTarget(false);
		if(currentTarget == null)
		{
			ChatUtils.message(routeType.getSelected() == RouteType.CHUNKS
				? "AutoFly chunk trail completed." : "AutoFly grid completed.");
			setEnabled(false);
		}
	}
	
	private boolean checkStopOn(EnumSetting<StopOnType> stopSetting,
		TextFieldSetting keywordSetting, boolean secondary)
	{
		if(stopIgnoreTicks > 0)
			return false;
		
		StopOnType type = stopSetting.getSelected();
		if(type == null || type == StopOnType.OFF)
			return false;
		
		if(MC.player == null || MC.level == null)
			return false;
		
		switch(type)
		{
			case MOBS ->
			{
				String kw = getStopKeyword(keywordSetting);
				if(kw.isEmpty())
					return false;
					
				// No explicit range cap: scan what the client has loaded
				// (entitiesForRendering).
				for(var e : MC.level.entitiesForRendering())
				{
					if(!(e instanceof Mob m) || !m.isAlive() || m.isRemoved())
						continue;
					
					String name = safeString(m.getName().getString());
					String id = safeString(BuiltInRegistries.ENTITY_TYPE
						.getKey(m.getType()).toString());
					if(containsIgnoreCase(name, kw)
						|| containsIgnoreCase(id, kw))
					{
						stopAutoFly("Stopped: Found " + name);
						return true;
					}
				}
				return false;
			}
			
			case ITEMS ->
			{
				String kw = getStopKeyword(keywordSetting);
				if(kw.isEmpty())
					return false;
				
				// No explicit range cap: scan what the client has loaded.
				for(var ent : MC.level.entitiesForRendering())
				{
					if(!(ent instanceof ItemEntity e) || !e.isAlive()
						|| e.isRemoved())
						continue;
					if(e.getItem() == null || e.getItem().isEmpty())
						continue;
					
					var stack = e.getItem();
					String name = safeString(stack.getHoverName().getString());
					String id = safeString(BuiltInRegistries.ITEM
						.getKey(stack.getItem()).toString());
					if(containsIgnoreCase(name, kw)
						|| containsIgnoreCase(id, kw))
					{
						stopAutoFly("Stopped: Found " + name);
						return true;
					}
				}
				return false;
			}
			
			case BLOCKS ->
			{
				String kw = getStopKeyword(keywordSetting);
				if(kw.isEmpty())
					return false;
				
				return scanBlocksForKeyword(secondary, kw, null);
			}
			
			case OLD_CHUNKS ->
			{
				return checkStopOnChunkSet(
					WURST.getHax().newerNewChunksHack.getOldChunks(),
					"old chunk");
			}
			
			case NEW_CHUNKS ->
			{
				return checkStopOnChunkSet(
					WURST.getHax().newerNewChunksHack.getNewChunks(),
					"new chunk");
			}
			
			case END_PORTAL ->
			{
				return scanBlocksForKeyword(secondary, "", Blocks.END_PORTAL);
			}
			
			case NETHER_PORTAL ->
			{
				return scanBlocksForKeyword(secondary, "",
					Blocks.NETHER_PORTAL);
			}
			
			case OFF ->
			{
				return false;
			}
		}
		
		return false;
	}
	
	private boolean scanBlocksForKeyword(boolean secondary, String keyword,
		net.minecraft.world.level.block.Block mustMatch)
	{
		// Throttle block scanning/update.
		if(stopScanCooldown-- > 0)
			return false;
		stopScanCooldown = STOP_SCAN_COOLDOWN_TICKS;
		
		ensureStopBlockCoordinatorConfigured(secondary, keyword, mustMatch);
		ChunkSearcherCoordinator coordinator =
			secondary ? stopBlockCoordinator2 : stopBlockCoordinator;
		if(coordinator == null)
			return false;
		
		coordinator.update();
		
		Result hit = coordinator.getReadyMatches().findFirst().orElse(null);
		if(hit == null)
			return false;
		
		if(mustMatch != null)
		{
			stopAutoFly("Stopped: Found " + (mustMatch == Blocks.END_PORTAL
				? "End Portal" : "Nether Portal"));
			return true;
		}
		
		String id = safeString(
			BuiltInRegistries.BLOCK.getKey(hit.state().getBlock()).toString());
		stopAutoFly("Stopped: Found " + id);
		return true;
	}
	
	private boolean checkStopOnPlayers()
	{
		if(!disableOnPlayers.isChecked() || MC.player == null
			|| MC.level == null)
			return false;
		
		for(var entity : MC.level.entitiesForRendering())
		{
			if(!(entity instanceof Player p))
				continue;
			
			if(p == MC.player || p.isRemoved() || !p.isAlive()
				|| p.isSpectator())
				continue;
			
			String name = safeString(p.getName().getString());
			ChatUtils.message("Disabled: Player detected"
				+ (name.isBlank() ? "" : " (" + name + ")"));
			setEnabled(false);
			return true;
		}
		
		return false;
	}
	
	private boolean checkDisableOnDamage()
	{
		if(!disableOnDamage.isChecked() || MC.player == null)
			return false;
		
		if(MC.player.hurtTime <= 0)
			return false;
		
		ChatUtils.message("Disabled: You took damage.");
		setEnabled(false);
		return true;
	}
	
	private boolean checkStopOnChunkSet(java.util.Set<ChunkPos> chunks,
		String stopName)
	{
		if(MC.player == null || MC.level == null)
			return false;
		
		if(chunks == null || chunks.isEmpty())
			return false;
		
		ChunkPos playerChunk = ChunkPos.containing(MC.player.blockPosition());
		if(!chunks.contains(playerChunk))
			return false;
		
		stopAutoFly("Stopped: Reached " + stopName);
		return true;
	}
	
	private void ensureStopBlockCoordinatorConfigured(boolean secondary,
		String keyword, net.minecraft.world.level.block.Block mustMatch)
	{
		StopOnType type =
			secondary ? stopOn2.getSelected() : stopOn.getSelected();
		if(type == null)
			return;
		
		String kw = keyword == null ? "" : keyword.trim();
		
		ChunkSearcherCoordinator coordinator =
			secondary ? stopBlockCoordinator2 : stopBlockCoordinator;
		StopOnType coordinatorType =
			secondary ? stopBlockCoordinatorType2 : stopBlockCoordinatorType;
		String coordinatorKeyword = secondary ? stopBlockCoordinatorKeyword2
			: stopBlockCoordinatorKeyword;
		boolean needsReset = coordinator == null || coordinatorType != type
			|| !java.util.Objects.equals(coordinatorKeyword, kw);
		
		if(!needsReset)
			return;
		
		if(secondary)
		{
			stopBlockCoordinatorType2 = type;
			stopBlockCoordinatorKeyword2 = kw;
		}else
		{
			stopBlockCoordinatorType = type;
			stopBlockCoordinatorKeyword = kw;
		}
		
		ChunkAreaSetting area = new ChunkAreaSetting(
			"Stop scan area (internal)", "", STOP_BLOCK_AREA);
		coordinator = new ChunkSearcherCoordinator(area);
		
		if(mustMatch != null)
		{
			coordinator.setTargetBlock(mustMatch);
		}else
		{
			coordinator.setQuery((pos, state) -> {
				BlockState s = state;
				if(s == null)
					return false;
				String id =
					BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
				return containsIgnoreCase(id, kw);
			});
		}
		
		if(secondary)
			stopBlockCoordinator2 = coordinator;
		else
			stopBlockCoordinator = coordinator;
	}
	
	private void stopAutoFly(String message)
	{
		// Stop immediately, even if keys were held from the previous tick.
		PathProcessor.releaseControls();
		resetAutoKeyFlags();
		clearMovementKeys();
		
		if(disableAutoFlyOnStop.isChecked())
		{
			ChatUtils.message(message + " (AutoFly disabled)");
			setEnabled(false);
			return;
		}
		
		ChatUtils.message(message + " (use Next waypoint to continue)");
		stopHold = true;
		stopIgnoreTicks = 0;
	}
	
	private String getStopKeyword(TextFieldSetting setting)
	{
		if(setting == null)
			return "";
		String v = setting.getValue();
		return v == null ? "" : v.trim();
	}
	
	private static boolean containsIgnoreCase(String haystack, String needle)
	{
		if(haystack == null || needle == null)
			return false;
		if(needle.isEmpty())
			return false;
		return haystack.toLowerCase(Locale.ROOT)
			.contains(needle.toLowerCase(Locale.ROOT));
	}
	
	private static String safeString(String s)
	{
		return s == null ? "" : s;
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(!crosshairInfo.isChecked() || MC.player == null)
			return;
		
		String info = buildCrosshairInfo();
		if(info == null || info.isBlank())
			return;
		
		Font font = MC.font;
		int centerX = context.guiWidth() / 2;
		int y = context.guiHeight() / 2 + 10;
		int textWidth = font.width(info);
		int x = centerX - textWidth / 2;
		context.text(font, info, x, y, 0xFFFFFFFF, true);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		renderGridPath(matrixStack);
		
		if(pathFinder == null || pathProcessor == null)
			return;
		pathFinder.renderPath(matrixStack, false, false);
	}
	
	private void renderGridPath(PoseStack matrixStack)
	{
		if(!showGridPath.isChecked())
			return;
		if(routeType.getSelected() != RouteType.GRID
			&& routeType.getSelected() != RouteType.CHUNKS)
			return;
		if(MC.player == null || MC.level == null)
			return;
		if(targets.isEmpty())
			return;
		
		double y = getCruiseY(currentTarget);
		Vec3 playerPos = MC.player.position();
		
		int max = gridPathMaxPoints.getValueI();
		int startIdx = Math.max(0, currentIndex);
		int endIdx = Math.min(targets.size(), startIdx + Math.max(2, max));
		
		List<Vec3> pts = new ArrayList<>(endIdx - startIdx + 1);
		pts.add(new Vec3(playerPos.x, y, playerPos.z));
		for(int i = startIdx; i < endIdx; i++)
		{
			AutoFlyTarget t = targets.get(i);
			pts.add(new Vec3(t.pos.getX() + 0.5, y, t.pos.getZ() + 0.5));
		}
		
		if(pts.size() < 2)
			return;
		
		float[] rgb = gridPathColor.getColorF();
		int c =
			RenderUtils.toIntColor(new float[]{rgb[0], rgb[1], rgb[2]}, 0.9F);
		RenderUtils.drawCurvedLine(matrixStack, pts, c, false,
			gridPathThickness.getValue());
	}
	
	private void loadTargetsFromSettings()
	{
		String text = waypointText.getValue();
		if(text != null && !text.isBlank())
		{
			loadTargetsFromText(text);
			return;
		}
		
		loadTargetsFromJson();
	}
	
	private void startGridFromPlayer()
	{
		if(MC.player == null)
		{
			ChatUtils.error("Join a world before starting a grid.");
			return;
		}
		
		routeType.setSelected(RouteType.GRID);
		loadTargetsFromGrid(MC.player.blockPosition());
		if(targets.isEmpty())
			return;
		
		restartWithExistingTargets();
	}
	
	private void loadTargetsFromGrid(BlockPos start)
	{
		targets.clear();
		if(start == null)
			return;
		
		int widthChunks = gridWidthChunks.getValueI();
		int depthChunks = gridDepthChunks.getValueI();
		int pathWidthChunks = gridPathWidthChunks.getValueI();
		if(widthChunks < 1 || depthChunks < 1 || pathWidthChunks < 1)
		{
			ChatUtils.error(
				"Grid width, depth and path width must all be at least 1 chunk.");
			return;
		}
		
		int passCount = (widthChunks + pathWidthChunks - 1) / pathWidthChunks;
		long estTargets = (long)passCount * depthChunks;
		if(estTargets > 20000L)
		{
			ChatUtils.error("Grid is too large (" + estTargets
				+ " chunk centers). Reduce width, depth or path width.");
			return;
		}
		
		int startChunkX = start.getX() >> 4;
		int startChunkZ = start.getZ() >> 4;
		int y = 0;
		
		for(int pass = 0; pass < passCount; pass++)
		{
			int chunkX = startChunkX + pass * pathWidthChunks;
			boolean ascending = (pass & 1) == 0;
			
			for(int dz = 0; dz < depthChunks; dz++)
			{
				int depthIndex = ascending ? dz : depthChunks - 1 - dz;
				int chunkZ = startChunkZ + depthIndex;
				int centerX = (chunkX << 4) + 8;
				int centerZ = (chunkZ << 4) + 8;
				targets.add(new AutoFlyTarget(new BlockPos(centerX, y, centerZ),
					false));
			}
		}
		
		int minBlockX = (startChunkX << 4) + 8;
		int minBlockZ = (startChunkZ << 4) + 8;
		int maxCoveredChunkX = startChunkX + widthChunks - 1;
		int maxCoveredChunkZ = startChunkZ + depthChunks - 1;
		int maxBlockX = (maxCoveredChunkX << 4) + 8;
		int maxBlockZ = (maxCoveredChunkZ << 4) + 8;
		
		ChatUtils.message(String.format(Locale.ROOT,
			"AutoFly grid: %dx%d chunks, path width=%d, passes=%d, targets=%d (%d,%d -> %d,%d)",
			widthChunks, depthChunks, pathWidthChunks, passCount,
			targets.size(), minBlockX, minBlockZ, maxBlockX, maxBlockZ));
	}
	
	private void restartWithExistingTargets()
	{
		currentTarget = null;
		currentIndex = -1;
		pausedNoY = false;
		stopHold = false;
		stopIgnoreTicks = 0;
		arrivalPause = false;
		arrivalPauseUntilMs = 0L;
		arrivedMessageSent = false;
		arrivedHold = false;
		manualAdjustHold = false;
		manualAdjustStartMs = 0L;
		manualAdjustStartPos = null;
		lastManualInputMs = 0L;
		lastAutoControlMs = 0L;
		lastManualAdjustExitMs = 0L;
		verticalMode = VerticalMode.NONE;
		recoveryGoal = null;
		pathFinder = null;
		pathProcessor = null;
		lastProgressMs = System.currentTimeMillis();
		lastProgressDist = Double.NaN;
		lastRepathMs = 0L;
		stuckRepathCount = 0;
		lastMovePos = MC.player != null ? MC.player.position() : null;
		lastMoveMs = System.currentTimeMillis();
		lastHorizPos = lastMovePos;
		lastHorizMoveMs = lastMoveMs;
		autoKeyUpDown = false;
		autoKeyDownDown = false;
		autoKeyLeftDown = false;
		autoKeyRightDown = false;
		autoKeyJumpDown = false;
		autoKeyShiftDown = false;
		climbAttemptUntilMs = 0L;
		lastClimbAttemptMs = 0L;
		climbTargetY = 0.0;
		closeHorizLatched = false;
		clearPathingState();
		
		if(!isEnabled())
		{
			useExistingTargetsOnEnable = true;
			setEnabled(true);
			return;
		}
		
		selectNextTarget(false);
	}
	
	private void loadTargetsFromText(String text)
	{
		targets.clear();
		if(text == null || text.isBlank())
			return;
		
		String[] entries = text.split("[\\n;]+");
		for(String entry : entries)
		{
			String cleaned = entry.trim();
			if(cleaned.isEmpty())
				continue;
			
			String[] parts = cleaned.split("[,\\s]+");
			if(parts.length < 2)
				continue;
			
			if(!MathUtils.isInteger(parts[0]) || !MathUtils.isInteger(parts[1]))
				continue;
			
			int x = Integer.parseInt(parts[0]);
			if(parts.length >= 3 && MathUtils.isInteger(parts[2]))
			{
				int y = Integer.parseInt(parts[1]);
				int z = Integer.parseInt(parts[2]);
				targets.add(new AutoFlyTarget(new BlockPos(x, y, z), true));
			}else
			{
				int z = Integer.parseInt(parts[1]);
				targets.add(new AutoFlyTarget(new BlockPos(x, 0, z), false));
			}
		}
	}
	
	private void loadTargetsFromJson()
	{
		targets.clear();
		File file = resolveJsonFile();
		if(file == null || !file.exists())
		{
			ChatUtils.error("AutoFly JSON file not found.");
			return;
		}
		
		try(FileReader reader = new FileReader(file))
		{
			JsonElement root = JsonParser.parseReader(reader);
			JsonArray arr = null;
			if(root.isJsonArray())
				arr = root.getAsJsonArray();
			else if(root.isJsonObject())
			{
				JsonObject obj = root.getAsJsonObject();
				if(obj.has("structures") && obj.get("structures").isJsonArray())
					arr = obj.getAsJsonArray("structures");
				else if(obj.has("exports") && obj.get("exports").isJsonArray())
					arr = obj.getAsJsonArray("exports");
			}
			
			if(arr == null)
				return;
			
			for(JsonElement e : arr)
			{
				if(!e.isJsonObject())
					continue;
				JsonObject o = e.getAsJsonObject();
				if(!o.has("x") || !o.has("z"))
					continue;
				
				int x = o.get("x").getAsInt();
				int z = o.get("z").getAsInt();
				boolean hasY = o.has("y");
				int y = hasY ? o.get("y").getAsInt() : 0;
				
				BlockPos pos = new BlockPos(x, y, z);
				if(ignoreWaypointList.isChecked()
					&& WURST.getHax().waypointsHack.hasWaypointNear(pos, 150.0)) // Was
																					// 50,
																					// was
																					// not
																					// effective.
					continue;
				
				targets.add(new AutoFlyTarget(pos, hasY));
			}
			
		}catch(Throwable t)
		{
			t.printStackTrace();
		}
		
		ChatUtils.message("AutoFly loaded " + targets.size() + " targets from "
			+ file.getName());
	}
	
	private File resolveJsonFile()
	{
		File exportDir = getSeedmapperExportDir();
		if(exportDir == null || !exportDir.exists())
			return null;
		
		String custom = importFile.getValue();
		if(custom != null && !custom.isBlank())
		{
			File f = new File(custom);
			if(!f.isAbsolute())
				f = new File(exportDir, custom);
			return f.exists() ? f : null;
		}
		
		// Prefer a file selected via the picker if it looks valid
		try
		{
			java.nio.file.Path selected = exportJsonPicker.getSelectedFile();
			if(selected != null)
			{
				File f = selected.toFile();
				String name = f.getName().toLowerCase(Locale.ROOT);
				if(f.exists() && name.endsWith(".json")
					&& !name.equals("autofly-placeholder.json"))
					return f;
			}
		}catch(Throwable ignored)
		{
			// Fall through to latest-file logic
		}
		
		// Fallback: use the latest JSON in the exports folder
		File[] files = exportDir.listFiles(
			(d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
		if(files == null || files.length == 0)
			return null;
		
		File best = files[0];
		for(File f : files)
			if(f.lastModified() > best.lastModified())
				best = f;
		return best;
	}
	
	private File getSeedmapperExportDir()
	{
		if(MC != null && MC.gameDirectory != null)
			return new File(MC.gameDirectory, "seedmapper/exports");
		return null;
	}
	
	private void reloadJsonTargets()
	{
		loadTargetsFromJson();
		currentTarget = null;
		currentIndex = -1;
		pausedNoY = false;
		stopHold = false;
		stopIgnoreTicks = 0;
		arrivalPause = false;
		arrivalPauseUntilMs = 0L;
		arrivedMessageSent = false;
		arrivedHold = false;
		manualAdjustHold = false;
		manualAdjustStartMs = 0L;
		manualAdjustStartPos = null;
		lastManualInputMs = 0L;
		lastAutoControlMs = 0L;
		lastManualAdjustExitMs = 0L;
		verticalMode = VerticalMode.NONE;
		lastMovePos = MC.player != null ? MC.player.position() : null;
		lastMoveMs = System.currentTimeMillis();
		lastHorizPos = lastMovePos;
		lastHorizMoveMs = lastMoveMs;
		autoKeyUpDown = false;
		autoKeyDownDown = false;
		autoKeyLeftDown = false;
		autoKeyRightDown = false;
		autoKeyJumpDown = false;
		autoKeyShiftDown = false;
		climbAttemptUntilMs = 0L;
		lastClimbAttemptMs = 0L;
		climbTargetY = 0.0;
		closeHorizLatched = false;
		clearPathingState();
	}
	
	private void selectPreviousTarget()
	{
		if(targets.isEmpty())
			return;
		
		int nextIndex =
			currentIndex <= 0 ? targets.size() - 1 : currentIndex - 1;
		selectTarget(nextIndex);
	}
	
	public void cycleNextWaypoint()
	{
		if(targets.isEmpty())
			return;
		
		int nextIndex =
			currentIndex < 0 ? 0 : (currentIndex + 1) % targets.size();
		selectTarget(nextIndex);
	}
	
	public void cyclePreviousWaypoint()
	{
		if(targets.isEmpty())
			return;
		
		int prevIndex =
			currentIndex <= 0 ? targets.size() - 1 : currentIndex - 1;
		selectTarget(prevIndex);
	}
	
	private void selectNextTargetFromButton()
	{
		if(targets.isEmpty())
			return;
		
		int nextIndex =
			currentIndex < 0 ? 0 : (currentIndex + 1) % targets.size();
		selectTarget(nextIndex);
	}
	
	private void selectTarget(int index)
	{
		if(index < 0 || index >= targets.size())
			return;
		if(stopHold)
			stopIgnoreTicks = 60;
		stopHold = false;
		currentIndex = index;
		currentTarget = targets.get(index);
		pausedNoY = false;
		arrivalPause = false;
		arrivalPauseUntilMs = 0L;
		arrivedMessageSent = false;
		arrivedHold = false;
		manualAdjustHold = false;
		manualAdjustStartMs = 0L;
		manualAdjustStartPos = null;
		lastManualInputMs = 0L;
		lastAutoControlMs = 0L;
		lastManualAdjustExitMs = 0L;
		verticalMode = VerticalMode.NONE;
		lastMovePos = MC.player != null ? MC.player.position() : null;
		lastMoveMs = System.currentTimeMillis();
		lastHorizPos = lastMovePos;
		lastHorizMoveMs = lastMoveMs;
		autoKeyUpDown = false;
		autoKeyDownDown = false;
		autoKeyLeftDown = false;
		autoKeyRightDown = false;
		autoKeyJumpDown = false;
		autoKeyShiftDown = false;
		climbAttemptUntilMs = 0L;
		lastClimbAttemptMs = 0L;
		climbTargetY = 0.0;
		lastProgressMs = System.currentTimeMillis();
		lastProgressDist = Double.NaN;
		closeHorizLatched = false;
		clearPathingState();
	}
	
	private void selectNextTarget(boolean wrap)
	{
		if(targets.isEmpty())
		{
			currentTarget = null;
			currentIndex = -1;
			return;
		}
		
		int start = currentIndex;
		int count = targets.size();
		for(int i = 0; i < count; i++)
		{
			int idx = start < 0 ? i : start + 1 + i;
			if(wrap)
				idx = (idx % count + count) % count;
			else if(idx >= count)
				break;
			
			AutoFlyTarget candidate = targets.get(idx);
			if(skipReached.isChecked() && isTargetReached(candidate,
				MC.player.position(), targetRadius.getValue()))
				continue;
			
			if(stopHold)
				stopIgnoreTicks = 60;
			stopHold = false;
			currentIndex = idx;
			currentTarget = candidate;
			pausedNoY = false;
			arrivalPause = false;
			arrivalPauseUntilMs = 0L;
			arrivedMessageSent = false;
			arrivedHold = false;
			manualAdjustHold = false;
			manualAdjustStartMs = 0L;
			manualAdjustStartPos = null;
			lastManualInputMs = 0L;
			lastAutoControlMs = 0L;
			lastManualAdjustExitMs = 0L;
			verticalMode = VerticalMode.NONE;
			lastMovePos = MC.player != null ? MC.player.position() : null;
			lastMoveMs = System.currentTimeMillis();
			lastHorizPos = lastMovePos;
			lastHorizMoveMs = lastMoveMs;
			autoKeyUpDown = false;
			autoKeyDownDown = false;
			autoKeyLeftDown = false;
			autoKeyRightDown = false;
			autoKeyJumpDown = false;
			autoKeyShiftDown = false;
			climbAttemptUntilMs = 0L;
			lastClimbAttemptMs = 0L;
			climbTargetY = 0.0;
			lastProgressMs = System.currentTimeMillis();
			lastProgressDist = Double.NaN;
			closeHorizLatched = false;
			clearPathingState();
			return;
		}
		
		currentTarget = null;
	}
	
	private void handleTargetReached()
	{
		if(currentTarget == null)
			return;
		
		// Ensure no keys are left pressed when switching to arrived-hold.
		PathProcessor.releaseControls();
		resetAutoKeyFlags();
		clearMovementKeys();
		
		manualAdjustHold = false;
		manualAdjustStartMs = 0L;
		manualAdjustStartPos = null;
		
		if(!arrivedMessageSent)
		{
			ChatUtils.message("AutoFly arrived at " + currentTarget.pos.getX()
				+ ", " + currentTarget.pos.getY() + ", "
				+ currentTarget.pos.getZ());
			arrivedMessageSent = true;
		}
		
		arrivalPause = true;
		arrivalPauseUntilMs = System.currentTimeMillis() + 1000L;
		clearPathingState();
		arrivedHold = true;
		if(!isVoidTarget(currentTarget.pos)
			&& disableFlightOnArrival.isChecked()
			&& WURST.getHax().flightHack.isEnabled())
			WURST.getHax().flightHack.setEnabled(false);
		
		if(disableAutoFlyOnArrival.isChecked())
		{
			setEnabled(false);
			return;
		}
		
		if(!currentTarget.hasY)
		{
			pausedNoY = false;
			PathProcessor.releaseControls();
			return;
		}
		
	}
	
	private boolean isTargetReached(AutoFlyTarget target, Vec3 playerPos,
		double radius)
	{
		if(target == null || playerPos == null)
			return false;
		
		double dx = target.pos.getX() + 0.5 - playerPos.x;
		double dz = target.pos.getZ() + 0.5 - playerPos.z;
		double distHoriz = Math.hypot(dx, dz);
		if(distHoriz > radius)
			return false;
		
		if(target.hasY)
		{
			return true;
		}
		
		if(MC.level != null)
		{
			BlockPos lp = resolveLandingPosition(new BlockPos(target.pos.getX(),
				MC.level.getMaxY() - 2, target.pos.getZ()));
			if(lp != null)
			{
				if(MC.player.onGround())
					return true;
				return Math.abs(playerPos.y - lp.getY()) <= 0.7;
			}
		}
		return MC.player.onGround();
	}
	
	private double getCruiseY(AutoFlyTarget target)
	{
		double y = flightHeight.getValue();
		if(target != null && target.hasY)
			y = Math.max(y, target.pos.getY() + 2);
		
		if(MC.level != null)
			y = MathUtils.clamp(y, MC.level.getMinY(), MC.level.getMaxY() - 2);
		return y;
	}
	
	private void ensureFlightEnabled()
	{
		var flight = WURST.getHax().flightHack;
		if(!flight.isEnabled())
			flight.setEnabled(true);
	}
	
	private void applyFlightSettings()
	{
		var flight = WURST.getHax().flightHack;
		if(savedFlightSpeed < 0)
			savedFlightSpeed = flight.horizontalSpeed.getValue();
		if(savedFlightVSpeed < 0)
			savedFlightVSpeed = flight.verticalSpeed.getValue();
		applyFlightSpeed();
	}
	
	private void applyFlightSpeed()
	{
		var flight = WURST.getHax().flightHack;
		double desired = Math.min(flight.horizontalSpeed.getMaximum(),
			flightSpeed.getValue());
		flight.horizontalSpeed.setValue(desired);
	}
	
	private void restoreFlightSettings()
	{
		var flight = WURST.getHax().flightHack;
		if(savedFlightSpeed >= 0)
			flight.horizontalSpeed.setValue(savedFlightSpeed);
		if(savedFlightVSpeed >= 0)
			flight.verticalSpeed.setValue(savedFlightVSpeed);
		savedFlightSpeed = -1;
		savedFlightVSpeed = -1;
		if(!flightWasEnabled && flight.isEnabled())
			flight.setEnabled(false);
		flightWasEnabled = false;
	}
	
	private String buildCrosshairInfo()
	{
		if(currentTarget == null || MC.player == null)
		{
			double speed = MC.player != null
				? MC.player.getDeltaMovement().length() * 20.0 : 0.0;
			return String.format(Locale.ROOT, "AutoFly | %.1fb/s | %s", speed,
				getStateLabel());
		}
		
		double dist;
		if(currentTarget.hasY)
			dist = MC.player.position()
				.distanceTo(Vec3.atCenterOf(currentTarget.pos));
		else
		{
			Vec3 playerPos = MC.player.position();
			double dx = currentTarget.pos.getX() + 0.5 - playerPos.x;
			double dz = currentTarget.pos.getZ() + 0.5 - playerPos.z;
			dist = Math.hypot(dx, dz);
		}
		double speed = MC.player.getDeltaMovement().length() * 20.0;
		int total = targets.isEmpty() ? 1 : targets.size();
		int index = Math.max(1, Math.min(total, currentIndex + 1));
		
		return String.format(Locale.ROOT,
			"AutoFly %d/%d | %.1fm | %.1fb/s | %s", index, total, dist, speed,
			getStateLabel());
	}
	
	private String getStateLabel()
	{
		if(pathFinder != null)
			return "Pathing";
		if(stopHold)
			return "Stopped";
		if(manualAdjustHold)
			return "Adjust";
		if(arrivedHold)
			return "Arrived";
		if(pausedNoY)
			return "Paused";
		if(arrivalPause)
			return "Arrived";
		return currentTarget != null ? "Flying" : "Idle";
	}
	
	public void setTargetFromCommand(BlockPos pos, boolean hasY,
		Double overrideHeight, Double overrideSpeed)
	{
		if(pos == null)
			return;
		
		if(routeType.getSelected() != RouteType.CHUNKS)
		{
			clearChunkCorridorAssist();
			routeType.setSelected(RouteType.WAYPOINTS);
		}
		BlockPos landingPos = pos;
		
		if(overrideHeight != null)
			flightHeight.setValue(overrideHeight);
		if(overrideSpeed != null)
			flightSpeed.setValue(overrideSpeed);
		
		targets.clear();
		targets.add(new AutoFlyTarget(landingPos, hasY));
		currentIndex = 0;
		currentTarget = targets.get(0);
		pausedNoY = false;
		stopHold = false;
		stopIgnoreTicks = 0;
		arrivalPause = false;
		arrivalPauseUntilMs = 0L;
		arrivedMessageSent = false;
		arrivedHold = false;
		manualAdjustHold = false;
		manualAdjustStartMs = 0L;
		manualAdjustStartPos = null;
		lastManualInputMs = 0L;
		lastAutoControlMs = 0L;
		lastManualAdjustExitMs = 0L;
		verticalMode = VerticalMode.NONE;
		lastMovePos = MC.player != null ? MC.player.position() : null;
		lastMoveMs = System.currentTimeMillis();
		lastHorizPos = lastMovePos;
		lastHorizMoveMs = lastMoveMs;
		autoKeyUpDown = false;
		autoKeyDownDown = false;
		autoKeyLeftDown = false;
		autoKeyRightDown = false;
		autoKeyJumpDown = false;
		autoKeyShiftDown = false;
		climbAttemptUntilMs = 0L;
		lastClimbAttemptMs = 0L;
		climbTargetY = 0.0;
		lastProgressMs = System.currentTimeMillis();
		lastProgressDist = Double.NaN;
		closeHorizLatched = false;
		clearPathingState();
		
		if(!isEnabled())
		{
			useExistingTargetsOnEnable = true;
			setEnabled(true);
		}
	}
	
	public void setForwardFromCommand(Double overrideHeight,
		Double overrideSpeed)
	{
		if(MC.player == null)
			return;
		
		Vec3 look = MC.player.getLookAngle();
		if(look.lengthSqr() < 1.0E-6)
			look = new Vec3(0.0, 0.0, 1.0);
		
		Vec3 target = MC.player.position()
			.add(look.normalize().scale(COMMAND_FORWARD_DISTANCE));
		setTargetFromCommand(BlockPos.containing(target), true, overrideHeight,
			overrideSpeed);
	}
	
	public void setChunkTrailFromCommand()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		clearChunkCorridorAssist();
		routeType.setSelected(RouteType.CHUNKS);
		chunkAssistActive = true;
		chunkCorridorOrigin = MC.player.position();
		chunkCorridorHeading = getHorizontalLookDirection();
		targets.clear();
		currentIndex = -1;
		currentTarget = null;
		chunkTrailPath.clear();
		chunkCorridorAnchor = null;
		setForwardFromCommand(null, null);
	}
	
	private void applyChunkCorridorAssist()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		var trail = WURST.getHax().newerNewChunksHack.getOldChunks();
		if(trail.isEmpty())
			return;
		
		if(chunkCorridorOrigin == null)
			chunkCorridorOrigin = MC.player.position();
		if(chunkCorridorHeading == null
			|| chunkCorridorHeading.lengthSqr() < 1.0E-6)
		{
			chunkCorridorHeading = getHorizontalLookDirection();
			if(chunkCorridorHeading.lengthSqr() < 1.0E-6)
				chunkCorridorHeading = new Vec3(0.0, 0.0, 1.0);
		}
		
		Vec3 playerPos = MC.player.position();
		Vec3 targetPos = selectChunkCorridorTargetPos(trail, playerPos,
			chunkCorridorOrigin, chunkCorridorHeading);
		if(targetPos == null)
			return;
		
		chunkCorridorTargetPos = targetPos;
		ChunkPos anchor = ChunkPos.containing(BlockPos.containing(targetPos));
		chunkCorridorAnchor = anchor;
		WURST.getRotationFaker().faceVectorClientIgnorePitch(targetPos);
		autoSetKey(MC.options.keyUp, true);
	}
	
	public void clearChunkCorridorAssist()
	{
		chunkAssistActive = false;
		chunkCorridorAnchor = null;
		chunkCorridorOrigin = null;
		chunkCorridorHeading = null;
		chunkCorridorTargetPos = null;
		chunkTrailPath.clear();
	}
	
	public void setWaypointRouteFromCommand()
	{
		clearChunkCorridorAssist();
		routeType.setSelected(RouteType.WAYPOINTS);
	}
	
	private Vec3 selectChunkCorridorTargetPos(java.util.Set<ChunkPos> trail,
		Vec3 playerPos, Vec3 origin, Vec3 heading)
	{
		if(trail.isEmpty() || playerPos == null || origin == null
			|| heading == null)
			return null;
		
		Vec3 flatHeading = new Vec3(heading.x, 0.0, heading.z);
		if(flatHeading.lengthSqr() < 1.0E-6)
			return null;
		flatHeading = flatHeading.normalize();
		
		double playerProgress = playerPos.subtract(origin).dot(flatHeading);
		double lookaheadBlocks = 64.0;
		Vec3 lookaheadPoint =
			origin.add(flatHeading.scale(playerProgress + lookaheadBlocks));
		ChunkPos centerChunk =
			ChunkPos.containing(BlockPos.containing(lookaheadPoint));
		ChunkPos playerChunk = ChunkPos.containing(MC.player.blockPosition());
		
		Vec3 weightedSum = Vec3.ZERO;
		double totalWeight = 0.0;
		
		for(int radius = 0; radius <= 6; radius++)
		{
			for(int dx = -radius; dx <= radius; dx++)
			{
				for(int dz = -radius; dz <= radius; dz++)
				{
					ChunkPos candidate = new ChunkPos(centerChunk.x() + dx,
						centerChunk.z() + dz);
					if(!trail.contains(candidate))
						continue;
					
					Vec3 center = Vec3.atCenterOf(chunkCenter(candidate));
					double along = center.subtract(origin).dot(flatHeading);
					if(along < playerProgress - 8.0)
						continue;
					
					Vec3 delta = center.subtract(lookaheadPoint);
					double lateral = Math.hypot(delta.x, delta.z);
					double alongError =
						Math.abs(along - (playerProgress + lookaheadBlocks));
					double density = countTrailNeighbors(trail, candidate, 1)
						+ countTrailNeighbors(trail, candidate, 2) * 0.35;
					double weight = Math.max(0.1, density * 4.0 + 1.5
						- lateral * 0.05 - alongError * 0.02);
					if(candidate.equals(centerChunk))
						weight += 0.75;
					if(Math.abs(candidate.x() - playerChunk.x()) <= 1
						&& Math.abs(candidate.z() - playerChunk.z()) <= 1)
						weight += 0.25;
					
					weightedSum = weightedSum.add(center.scale(weight));
					totalWeight += weight;
				}
			}
		}
		
		if(totalWeight <= 0.0)
			return Vec3.atCenterOf(chunkCenter(
				findClosestChunk(new ArrayList<>(trail), playerChunk)));
		
		return weightedSum.scale(1.0 / totalWeight);
	}
	
	private static int countTrailNeighbors(java.util.Set<ChunkPos> trail,
		ChunkPos center, int radius)
	{
		if(trail.isEmpty() || center == null || radius < 1)
			return 0;
		
		int count = 0;
		for(int dx = -radius; dx <= radius; dx++)
		{
			for(int dz = -radius; dz <= radius; dz++)
			{
				if(dx == 0 && dz == 0)
					continue;
				if(trail
					.contains(new ChunkPos(center.x() + dx, center.z() + dz)))
					count++;
			}
		}
		return count;
	}
	
	private void loadTargetsFromChunkTrail(BlockPos start)
	{
		targets.clear();
		currentIndex = -1;
		currentTarget = null;
		chunkTrailPath.clear();
		if(start == null)
			return;
		
		List<ChunkPos> trail =
			new ArrayList<>(WURST.getHax().newerNewChunksHack.getOldChunks());
		if(trail.isEmpty())
		{
			ChatUtils.error(
				"No NewerNewChunks trail loaded. Enable NewerNewChunks or Mapa's 'Show newer new chunks' first.");
			return;
		}
		
		ChunkPos startChunk = ChunkPos.containing(start);
		ChunkPos seed = findClosestChunk(trail, startChunk);
		if(seed == null)
		{
			ChatUtils.error(
				"No NewerNewChunks trail loaded. Enable NewerNewChunks or Mapa's 'Show newer new chunks' first.");
			return;
		}
		
		chunkTrailPath.addAll(orderChunkTrail(trail, seed));
		for(ChunkPos chunk : chunkTrailPath)
			targets.add(new AutoFlyTarget(chunkCenter(chunk), false));
		
		ChatUtils.message(String.format(Locale.ROOT,
			"AutoFly chunk trail: %d chunks from %d,%d", targets.size(),
			seed.x(), seed.z()));
	}
	
	private void prepareChunkTrailTarget()
	{
		if(targets.isEmpty())
		{
			currentIndex = -1;
			currentTarget = null;
			return;
		}
		
		if(currentIndex < 0)
			currentIndex = 0;
		
		int targetIndex =
			Math.min(currentIndex + CHUNK_TRAIL_LOOKAHEAD, targets.size() - 1);
		currentTarget = targets.get(targetIndex);
		pausedNoY = false;
		arrivalPause = false;
		arrivalPauseUntilMs = 0L;
		arrivedMessageSent = false;
		arrivedHold = false;
		manualAdjustHold = false;
		manualAdjustStartMs = 0L;
		manualAdjustStartPos = null;
		lastManualInputMs = 0L;
		lastAutoControlMs = 0L;
		lastManualAdjustExitMs = 0L;
		verticalMode = VerticalMode.NONE;
		lastMovePos = MC.player != null ? MC.player.position() : null;
		lastMoveMs = System.currentTimeMillis();
		lastHorizPos = lastMovePos;
		lastHorizMoveMs = lastMoveMs;
		chunkCorridorAnchor = null;
		autoKeyUpDown = false;
		autoKeyDownDown = false;
		autoKeyLeftDown = false;
		autoKeyRightDown = false;
		autoKeyJumpDown = false;
		autoKeyShiftDown = false;
		climbAttemptUntilMs = 0L;
		lastClimbAttemptMs = 0L;
		climbTargetY = 0.0;
		lastProgressMs = System.currentTimeMillis();
		lastProgressDist = Double.NaN;
		closeHorizLatched = false;
		clearPathingState();
	}
	
	private void advanceChunkTrailTarget()
	{
		if(targets.isEmpty())
		{
			currentIndex = -1;
			currentTarget = null;
			return;
		}
		
		if(currentIndex < targets.size() - 1)
			currentIndex++;
		
		if(currentIndex >= targets.size() - 1)
		{
			ChatUtils.message("AutoFly chunk trail completed.");
			setEnabled(false);
			return;
		}
		
		prepareChunkTrailTarget();
	}
	
	private static ChunkPos findClosestChunk(List<ChunkPos> chunks,
		ChunkPos origin)
	{
		if(chunks.isEmpty() || origin == null)
			return null;
		
		ChunkPos best = chunks.get(0);
		long bestDist = chunkDistanceSq(best, origin);
		for(int i = 1; i < chunks.size(); i++)
		{
			ChunkPos candidate = chunks.get(i);
			long dist = chunkDistanceSq(candidate, origin);
			if(dist < bestDist)
			{
				best = candidate;
				bestDist = dist;
			}
		}
		return best;
	}
	
	private List<ChunkPos> orderChunkTrail(List<ChunkPos> trail, ChunkPos seed)
	{
		List<ChunkPos> ordered = new ArrayList<>();
		if(trail.isEmpty() || seed == null)
			return ordered;
		
		HashSet<ChunkPos> remaining = new HashSet<>(trail);
		ChunkPos current = seed;
		Vec3 heading = getHorizontalLookDirection();
		int limit = remaining.size();
		
		for(int i = 0; i < limit; i++)
		{
			ordered.add(current);
			remaining.remove(current);
			
			ChunkPos next =
				chooseNextChunkTrailChunk(current, remaining, heading);
			if(next == null)
				break;
			
			heading = chunkDirection(current, next);
			current = next;
		}
		
		return ordered;
	}
	
	private ChunkPos selectNextChunkCorridorAnchor(
		java.util.Set<ChunkPos> trail, ChunkPos seed, Vec3 playerPos,
		Vec3 forward)
	{
		if(trail.isEmpty() || seed == null || playerPos == null)
			return null;
		
		ChunkPos best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		ChunkPos playerChunk =
			ChunkPos.containing(BlockPos.containing(playerPos));
		
		for(int radius = 1; radius <= 4; radius++)
		{
			for(int dx = -radius; dx <= radius; dx++)
			{
				for(int dz = -radius; dz <= radius; dz++)
				{
					ChunkPos candidate =
						new ChunkPos(seed.x() + dx, seed.z() + dz);
					if(!trail.contains(candidate))
						continue;
					
					Vec3 to = Vec3.atCenterOf(chunkCenter(candidate))
						.subtract(playerPos);
					Vec3 horiz = new Vec3(to.x, 0.0, to.z);
					double dist = Math.max(1.0, horiz.length());
					Vec3 dir = horiz.scale(1.0 / dist);
					double score = dir.dot(forward) * 6.0 - dist * 0.08;
					if(candidate.equals(seed))
						score += 2.0;
					if(Math.abs(candidate.x() - playerChunk.x()) <= 1
						&& Math.abs(candidate.z() - playerChunk.z()) <= 1)
						score += 1.0;
					
					if(score > bestScore)
					{
						best = candidate;
						bestScore = score;
					}
				}
			}
			
			if(best != null)
				return best;
		}
		
		return findClosestChunk(new ArrayList<>(trail), playerChunk);
	}
	
	private boolean isChunkAnchorReached(Vec3 playerPos, ChunkPos anchor)
	{
		if(playerPos == null || anchor == null)
			return false;
		
		Vec3 center = Vec3.atCenterOf(chunkCenter(anchor));
		double dx = center.x - playerPos.x;
		double dz = center.z - playerPos.z;
		return Math.hypot(dx, dz) <= Math.max(6.0,
			targetRadius.getValue() * 2.0);
	}
	
	private ChunkPos chooseNextChunkTrailChunk(ChunkPos current,
		HashSet<ChunkPos> remaining, Vec3 heading)
	{
		if(current == null || remaining.isEmpty())
			return null;
		
		ChunkPos best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		
		for(int dx = -1; dx <= 1; dx++)
		{
			for(int dz = -1; dz <= 1; dz++)
			{
				if(dx == 0 && dz == 0)
					continue;
				
				ChunkPos candidate =
					new ChunkPos(current.x() + dx, current.z() + dz);
				if(!remaining.contains(candidate))
					continue;
				
				Vec3 dir = chunkDirection(current, candidate);
				double score = heading.dot(dir);
				if(score > bestScore)
				{
					best = candidate;
					bestScore = score;
				}
			}
		}
		
		return best;
	}
	
	private static BlockPos chunkCenter(ChunkPos chunk)
	{
		return new BlockPos((chunk.x() << 4) + 8, 0, (chunk.z() << 4) + 8);
	}
	
	private static Vec3 chunkDirection(ChunkPos from, ChunkPos to)
	{
		double dx = to.x() - from.x();
		double dz = to.z() - from.z();
		double len = Math.hypot(dx, dz);
		if(len < 1.0E-6)
			return new Vec3(0.0, 0.0, 0.0);
		return new Vec3(dx / len, 0.0, dz / len);
	}
	
	private static long chunkDistanceSq(ChunkPos a, ChunkPos b)
	{
		long dx = (long)a.x() - b.x();
		long dz = (long)a.z() - b.z();
		return dx * dx + dz * dz;
	}
	
	private Vec3 getHorizontalLookDirection()
	{
		if(MC.player == null)
			return new Vec3(0.0, 0.0, 1.0);
		
		Vec3 look = MC.player.getLookAngle();
		Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
		if(horizontal.lengthSqr() < 1.0E-6)
			return new Vec3(0.0, 0.0, 1.0);
		return horizontal.normalize();
	}
	
	private static final class AutoFlyTarget
	{
		private final BlockPos pos;
		private final boolean hasY;
		
		private AutoFlyTarget(BlockPos pos, boolean hasY)
		{
			this.pos = pos;
			this.hasY = hasY;
		}
	}
	
	private void updateProgressTracking(Vec3 playerPos)
	{
		if(currentTarget == null)
			return;
		double dist = playerPos.distanceTo(Vec3.atCenterOf(currentTarget.pos));
		if(Double.isNaN(lastProgressDist) || dist < lastProgressDist - 0.2)
		{
			lastProgressDist = dist;
			lastProgressMs = System.currentTimeMillis();
		}
	}
	
	private void updateMovementTracking(Vec3 playerPos)
	{
		if(playerPos == null)
			return;
		if(lastMovePos == null)
		{
			lastMovePos = playerPos;
			lastMoveMs = System.currentTimeMillis();
			lastHorizPos = playerPos;
			lastHorizMoveMs = lastMoveMs;
			return;
		}
		
		if(playerPos.distanceTo(lastMovePos) > 0.15)
		{
			lastMovePos = playerPos;
			lastMoveMs = System.currentTimeMillis();
		}
		
		if(lastHorizPos == null)
		{
			lastHorizPos = playerPos;
			lastHorizMoveMs = System.currentTimeMillis();
			return;
		}
		
		double dx = playerPos.x - lastHorizPos.x;
		double dz = playerPos.z - lastHorizPos.z;
		if(Math.hypot(dx, dz) > 0.1)
		{
			lastHorizPos = playerPos;
			lastHorizMoveMs = System.currentTimeMillis();
		}
	}
	
	private void updateVerticalProgress(Vec3 playerPos)
	{
		if(playerPos == null)
			return;
		if(Double.isNaN(lastYForProgress))
		{
			lastYForProgress = playerPos.y;
			lastVerticalProgressMs = System.currentTimeMillis();
			return;
		}
		if(Math.abs(playerPos.y - lastYForProgress) > 0.15)
		{
			lastYForProgress = playerPos.y;
			lastVerticalProgressMs = System.currentTimeMillis();
		}
	}
	
	private void applyVerticalAssist(Vec3 playerPos, double yDiff,
		boolean nearTargetHoriz)
	{
		if(!nearTargetHoriz || currentTarget == null)
		{
			restoreVerticalIfBoosted();
			verticalAssistActive = false;
			return;
		}
		if(yDiff < -1.0)
		{
			long now = System.currentTimeMillis();
			if(now - lastVerticalProgressMs > 1500L)
			{
				var flight = WURST.getHax().flightHack;
				if(savedFlightVSpeed < 0)
					savedFlightVSpeed = flight.verticalSpeed.getValue();
				double minAssist = 0.8;
				double desired =
					Math.max(flight.verticalSpeed.getValue(), minAssist);
				flight.verticalSpeed.setValue(
					Math.min(flight.verticalSpeed.getMaximum(), desired));
				autoSetKey(MC.options.keyShift, true);
				lastAutoControlMs = now;
				verticalAssistActive = true;
				if(now - lastVerticalProgressMs > 3500L)
				{
					autoSetKey(MC.options.keyUp, true);
					lastAutoControlMs = now;
				}
				return;
			}
		}
		restoreVerticalIfBoosted();
		verticalAssistActive = false;
	}
	
	private void restoreVerticalIfBoosted()
	{
		if(savedFlightVSpeed >= 0)
		{
			var flight = WURST.getHax().flightHack;
			flight.verticalSpeed.setValue(savedFlightVSpeed);
			savedFlightVSpeed = -1;
		}
	}
	
	private boolean shouldRepath(Vec3 playerPos, double distHoriz)
	{
		// Recovery pathing is designed for ground navigation. In mid-air it can
		// cause oscillation/stalls, so keep using direct flight controls.
		if(MC.player != null && !MC.player.onGround())
			return false;
		
		long now = System.currentTimeMillis();
		if(now - lastManualAdjustExitMs < 2000L)
			return false;
		if(now - lastProgressMs < 2000L && now - lastMoveMs < 2000L
			&& now - lastHorizMoveMs < 2000L)
			return false;
		if(now - lastRepathMs < 1000L)
			return false;
		if(distHoriz <= Math.max(2.0, targetRadius.getValue()))
			return false;
		return true;
	}
	
	private void startRecoveryPath(Vec3 playerPos)
	{
		if(MC.level == null || MC.player == null)
			return;
		
		lastRepathMs = System.currentTimeMillis();
		stuckRepathCount++;
		
		BlockPos origin = BlockPos.containing(playerPos);
		BlockPos goal = null;
		long now = System.currentTimeMillis();
		if(hasSkyAbove(origin) && now - lastClimbAttemptMs > 3000L)
		{
			climbTargetY =
				Math.min(getCruiseY(currentTarget) + 8, MC.level.getMaxY() - 2);
			if(playerPos.y + 1.0 < climbTargetY)
			{
				lastClimbAttemptMs = now;
				climbAttemptUntilMs = now + 1200L;
				lastProgressMs = now;
				lastProgressDist = Double.NaN;
				return;
			}
		}
		
		if(!hasSkyAbove(origin))
			goal = findSkyAccessGoal(origin, 12);
		
		if(goal == null && isBlockedAhead(1.2))
			goal = findLateralGoal(origin, 5);
		
		if(goal == null)
			goal = findSkyAccessGoal(origin, 8);
		
		if(goal == null)
			return;
		
		recoveryGoal = goal;
		pathFinder = new PathFinder(goal);
		pathProcessor = null;
		lastProgressMs = System.currentTimeMillis();
		lastProgressDist = Double.NaN;
	}
	
	private boolean processPathFinder()
	{
		if(pathFinder == null)
			return false;
		
		if(!pathFinder.isDone())
		{
			PathProcessor.lockControls();
			lastAutoControlMs = System.currentTimeMillis();
			pathFinder.think();
			if(!pathFinder.isDone())
			{
				if(pathFinder.isFailed())
					clearPathingState();
				return true;
			}
			pathFinder.formatPath();
			pathProcessor = pathFinder.getProcessor();
		}
		
		if(pathProcessor != null)
		{
			pathProcessor.process();
			lastAutoControlMs = System.currentTimeMillis();
			if(pathProcessor.isDone())
			{
				clearPathingState();
				return false;
			}
		}
		
		return true;
	}
	
	private void clearPathingState()
	{
		pathFinder = null;
		pathProcessor = null;
		recoveryGoal = null;
		stuckRepathCount = 0;
		lastProgressMs = System.currentTimeMillis();
		lastProgressDist = Double.NaN;
	}
	
	private void beginManualAdjust(Vec3 playerPos)
	{
		manualAdjustHold = true;
		manualAdjustStartMs = System.currentTimeMillis();
		manualAdjustStartPos = playerPos;
		lastManualInputMs = manualAdjustStartMs;
		PathProcessor.releaseControls();
	}
	
	private void handleManualAdjust()
	{
		if(MC.player == null)
			return;
		
		Vec3 now = MC.player.position();
		if(isManualInputActive())
			lastManualInputMs = System.currentTimeMillis();
		if(!isManualInputActive() && manualAdjustStartPos != null
			&& now.distanceTo(manualAdjustStartPos) <= 0.2
			&& System.currentTimeMillis() - lastManualInputMs < 1000L)
			return;
		if(manualAdjustStartPos != null
			&& now.distanceTo(manualAdjustStartPos) > 0.6)
		{
			manualAdjustHold = false;
			manualAdjustStartMs = 0L;
			manualAdjustStartPos = null;
			lastManualAdjustExitMs = System.currentTimeMillis();
			lastProgressMs = System.currentTimeMillis();
			lastProgressDist = Double.NaN;
			return;
		}
		
		if(!isManualInputActive()
			&& System.currentTimeMillis() - lastManualInputMs >= 1000L)
		{
			manualAdjustHold = false;
			manualAdjustStartMs = 0L;
			manualAdjustStartPos = null;
			lastManualAdjustExitMs = System.currentTimeMillis();
			lastProgressMs = System.currentTimeMillis();
			lastProgressDist = Double.NaN;
			return;
		}
		
		if(System.currentTimeMillis() - manualAdjustStartMs > 3000L)
		{
			manualAdjustHold = false;
			manualAdjustStartMs = 0L;
			manualAdjustStartPos = null;
			startRecoveryPath(now);
		}
	}
	
	private void updateVerticalControls(double yDiff, boolean nearTargetY)
	{
		if(verticalAssistActive)
		{
			autoSetKey(MC.options.keyJump, false);
			autoSetKey(MC.options.keyShift, true);
			return;
		}
		double start = nearTargetY ? 1.5 : 2.0;
		double stop = 0.6;
		
		switch(verticalMode)
		{
			case NONE ->
			{
				if(yDiff > start)
					verticalMode = VerticalMode.ASCEND;
				else if(yDiff < -start)
					verticalMode = VerticalMode.DESCEND;
			}
			case ASCEND ->
			{
				if(yDiff <= stop)
					verticalMode = VerticalMode.NONE;
			}
			case DESCEND ->
			{
				if(yDiff >= -stop)
					verticalMode = VerticalMode.NONE;
			}
		}
		
		if(verticalMode == VerticalMode.ASCEND)
			autoSetKey(MC.options.keyJump, true);
		else if(verticalMode == VerticalMode.DESCEND)
			autoSetKey(MC.options.keyShift, true);
	}
	
	private boolean isManualInputActive()
	{
		if(MC == null || MC.options == null)
			return false;
		
		boolean up = MC.options.keyUp.isDown();
		boolean down = MC.options.keyDown.isDown();
		boolean left = MC.options.keyLeft.isDown();
		boolean right = MC.options.keyRight.isDown();
		boolean jump = MC.options.keyJump.isDown();
		boolean shift = MC.options.keyShift.isDown();
		
		if(left || right || down)
			return true;
		if(up && !autoKeyUpDown)
			return true;
		if(jump && !autoKeyJumpDown)
			return true;
		if(shift && !autoKeyShiftDown)
			return true;
		
		return false;
	}
	
	private void autoSetKey(net.minecraft.client.KeyMapping key, boolean down)
	{
		if(key == null)
			return;
		key.setDown(down);
		if(key == MC.options.keyUp)
			autoKeyUpDown = down;
		else if(key == MC.options.keyDown)
			autoKeyDownDown = down;
		else if(key == MC.options.keyLeft)
			autoKeyLeftDown = down;
		else if(key == MC.options.keyRight)
			autoKeyRightDown = down;
		else if(key == MC.options.keyJump)
			autoKeyJumpDown = down;
		else if(key == MC.options.keyShift)
			autoKeyShiftDown = down;
	}
	
	private void resetAutoKeyFlags()
	{
		autoKeyUpDown = false;
		autoKeyDownDown = false;
		autoKeyLeftDown = false;
		autoKeyRightDown = false;
		autoKeyJumpDown = false;
		autoKeyShiftDown = false;
	}
	
	private boolean anyAutoKeyDown()
	{
		return autoKeyUpDown || autoKeyDownDown || autoKeyLeftDown
			|| autoKeyRightDown || autoKeyJumpDown || autoKeyShiftDown;
	}
	
	private void clearMovementKeys()
	{
		if(MC == null || MC.options == null)
			return;
		
		autoSetKey(MC.options.keyUp, false);
		autoSetKey(MC.options.keyDown, false);
		autoSetKey(MC.options.keyLeft, false);
		autoSetKey(MC.options.keyRight, false);
		autoSetKey(MC.options.keyJump, false);
		autoSetKey(MC.options.keyShift, false);
	}
	
	private boolean isBlockedAhead(double distance)
	{
		if(MC.level == null || MC.player == null)
			return false;
		Vec3 look = MC.player.getLookAngle();
		Vec3 ahead = MC.player.position().add(look.normalize().scale(distance));
		BlockPos pos = BlockPos.containing(ahead);
		return !MC.level.getBlockState(pos).getCollisionShape(MC.level, pos)
			.isEmpty();
	}
	
	private BlockPos findLateralGoal(BlockPos origin, int distance)
	{
		if(MC.player == null)
			return null;
		Vec3 look = MC.player.getLookAngle();
		Vec3 side = new Vec3(-look.z, 0, look.x).normalize();
		BlockPos left = origin.offset((int)Math.round(side.x * distance), 0,
			(int)Math.round(side.z * distance));
		BlockPos right = origin.offset((int)Math.round(-side.x * distance), 0,
			(int)Math.round(-side.z * distance));
		if(isStandable(left))
			return left;
		if(isStandable(right))
			return right;
		return null;
	}
	
	private BlockPos findSkyAccessGoal(BlockPos origin, int radius)
	{
		if(MC.level == null)
			return null;
		
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for(int dx = -radius; dx <= radius; dx++)
		{
			for(int dz = -radius; dz <= radius; dz++)
			{
				BlockPos pos = origin.offset(dx, 0, dz);
				if(!isStandable(pos))
					continue;
				if(!hasSkyAbove(pos))
					continue;
				double dist = pos.distSqr(origin);
				if(dist < bestDist)
				{
					bestDist = dist;
					best = pos.immutable();
				}
			}
		}
		return best;
	}
	
	private boolean hasSkyAbove(BlockPos pos)
	{
		if(MC.level == null)
			return false;
		int maxY = MC.level.getMaxY();
		for(int y = pos.getY() + 2; y <= maxY; y++)
		{
			BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
			if(!MC.level.getBlockState(check).getCollisionShape(MC.level, check)
				.isEmpty())
				return false;
		}
		return true;
	}
	
	private boolean isStandable(BlockPos pos)
	{
		if(MC.level == null)
			return false;
		if(!MC.level.getBlockState(pos).getCollisionShape(MC.level, pos)
			.isEmpty())
			return false;
		BlockPos above = pos.above();
		if(!MC.level.getBlockState(above).getCollisionShape(MC.level, above)
			.isEmpty())
			return false;
		BlockPos below = pos.below();
		return !MC.level.getBlockState(below).getCollisionShape(MC.level, below)
			.isEmpty();
	}
	
	private BlockPos resolveLandingPosition(BlockPos pos)
	{
		if(MC.level == null || pos == null)
			return pos;
		int startY = Math.min(pos.getY(), MC.level.getMaxY() - 2);
		int minY = MC.level.getMinY();
		BlockPos fallback = null;
		for(int y = startY; y >= minY; y--)
		{
			BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
			if(!isStandable(check))
				continue;
			if(MC.level.getBlockState(check).is(Blocks.BEDROCK))
				continue;
			fallback = check;
			if(!isBedrockCeilingAbove(Vec3.atCenterOf(check)))
				break;
		}
		return fallback != null ? fallback : pos;
	}
	
	private boolean isVoidTarget(BlockPos pos)
	{
		if(MC.level == null || pos == null)
			return false;
		int minY = MC.level.getMinY();
		for(int y = pos.getY(); y >= minY; y--)
		{
			BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
			if(!MC.level.getBlockState(check).getCollisionShape(MC.level, check)
				.isEmpty())
				return false;
		}
		return true;
	}
	
	private boolean isBedrockCeilingAbove(Vec3 playerPos)
	{
		if(MC.level == null || playerPos == null)
			return false;
		
		int maxY = MC.level.getMaxY();
		int startY = (int)Math.floor(playerPos.y) + 1;
		int endY = Math.min(maxY, startY + 12);
		int x = (int)Math.floor(playerPos.x);
		int z = (int)Math.floor(playerPos.z);
		for(int y = startY; y <= endY; y++)
		{
			BlockPos check = new BlockPos(x, y, z);
			if(MC.level.getBlockState(check).is(Blocks.BEDROCK))
				return true;
		}
		
		return false;
	}
	
	private double getGroundYAtXZ(BlockPos xz)
	{
		if(MC.level == null || xz == null)
			return Double.NaN;
		
		BlockPos lp = resolveLandingPosition(
			new BlockPos(xz.getX(), MC.level.getMaxY() - 2, xz.getZ()));
		return lp != null ? lp.getY() : Double.NaN;
	}
	
	private double getEffectiveTargetY(AutoFlyTarget target, Vec3 playerPos,
		boolean closeHoriz)
	{
		if(target == null || playerPos == null)
			return playerPos != null ? playerPos.y : 0.0;
		
		if(!target.hasY)
			return Double.NaN;
		
		if(closeHoriz && isBedrockCeilingAbove(playerPos)
			&& playerPos.y < target.pos.getY())
			return playerPos.y;
		
		double groundY = getGroundYAtXZ(target.pos);
		if(!Double.isNaN(groundY))
			return Math.max(target.pos.getY(), groundY);
		
		return target.pos.getY();
	}
	
	private enum VerticalMode
	{
		NONE,
		ASCEND,
		DESCEND
	}
}
