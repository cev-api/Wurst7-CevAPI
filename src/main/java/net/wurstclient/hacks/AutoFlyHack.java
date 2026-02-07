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
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
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
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.MathUtils;

@SearchTags({"auto fly", "autofly", "waypoint fly", "auto flight"})
public final class AutoFlyHack extends Hack
	implements UpdateListener, GUIRenderListener, RenderListener
{
	private final TextFieldSetting waypointText = new TextFieldSetting(
		"Waypoints",
		"Waypoints list. Format: x y z or x z (no Y). Separate by ';' or new lines.",
		"");
	private final TextFieldSetting importFile = new TextFieldSetting(
		"Import file",
		"SeedMapper export JSON filename. Leave empty to use the latest file in seedmapper/exports.",
		"");
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
	
	private final List<AutoFlyTarget> targets = new ArrayList<>();
	private AutoFlyTarget currentTarget;
	private int currentIndex = -1;
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
	
	private boolean closeHorizLatched;
	
	public AutoFlyHack()
	{
		super("AutoFly");
		setCategory(Category.MOVEMENT);
		addSetting(waypointText);
		addSetting(importFile);
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
			loadTargetsFromSettings();
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
		closeHorizLatched = false;
		selectNextTarget(false);
		flightWasEnabled = WURST.getHax().flightHack.isEnabled();
		savedFlightSpeed = -1;
		savedFlightVSpeed = -1;
		lastYForProgress = Double.NaN;
		lastVerticalProgressMs = System.currentTimeMillis();
		verticalAssistActive = false;
		applyFlightSettings();
		
		var hax = WURST.getHax();
		if(useAntisocial.isChecked() && !hax.antisocialHack.isEnabled())
			hax.antisocialHack.setEnabled(true);
		if(useAutoEat.isChecked() && !hax.autoEatHack.isEnabled())
			hax.autoEatHack.setEnabled(true);
		if(useAutoLeave.isChecked() && !hax.autoLeaveHack.isEnabled())
			hax.autoLeaveHack.setEnabled(true);
		
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
		savedFlightVSpeed = -1;
		closeHorizLatched = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
		{
			setEnabled(false);
			return;
		}
		
		if(currentTarget == null)
		{
			selectNextTarget(false);
			if(currentTarget == null)
			{
				setEnabled(false);
				return;
			}
		}
		
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
		
		if(pausedNoY)
			return;
		
		if(pathFinder != null)
		{
			if(processPathFinder())
				return;
			clearPathingState();
		}
		
		double radius = targetRadius.getValue();
		Vec3 playerPos = MC.player.position();
		double targetX = currentTarget.pos.getX() + 0.5;
		double targetZ = currentTarget.pos.getZ() + 0.5;
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
		
		if(currentTarget.hasY)
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
		
		if(isTargetReached(currentTarget, playerPos, radius))
		{
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
	
	@Override
	public void onRenderGUI(GuiGraphics context, float partialTicks)
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
		context.drawString(font, info, x, y, 0xFFFFFFFF, true);
	}
	
	@Override
	public void onRender(com.mojang.blaze3d.vertex.PoseStack matrixStack,
		float partialTicks)
	{
		if(pathFinder == null || pathProcessor == null)
			return;
		pathFinder.renderPath(matrixStack, false, false);
	}
	
	private void loadTargetsFromSettings()
	{
		String text = waypointText.getValue();
		if(text != null && !text.isBlank())
		{
			loadTargetsFromText(text);
			return;
		}
		
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
		PathProcessor.releaseControls();
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
