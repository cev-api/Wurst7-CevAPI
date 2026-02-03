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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.util.ItemUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkUtils;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@SearchTags({"loot runner", "lootrunner", "seedmapper export", "seed mapper"})
public final class LootRunnerHack extends Hack
	implements UpdateListener, GUIRenderListener, RenderListener
{
	private enum LootMode
	{
		ALL("All"),
		LIST("List"),
		ITEM_ID("Item ID"),
		QUERY("Query");
		
		private final String displayName;
		
		private LootMode(String displayName)
		{
			this.displayName = displayName;
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
	
	private enum State
	{
		IDLE,
		RESUMING_POSITION,
		PATHING_TO_TARGET,
		EXITING,
		SEARCHING_CHEST,
		PATHING_TO_CHEST,
		FLIGHTING_TO_CHEST,
		EMERGENCY_ASCEND,
		OPENING_CHEST,
		LOOTING,
		COOLDOWN
	}
	
	private final TextFieldSetting exportFile = new TextFieldSetting(
		"Export file",
		"SeedMapper export JSON filename. Leave empty to use the latest file in seedmapper/exports.",
		"");
	private final CheckboxSetting onlyCurrentDimension =
		new CheckboxSetting("Only current dimension",
			"Skips targets that are not in your current dimension.", true);
	private final SliderSetting targetRadius = new SliderSetting(
		"Target radius", "Distance to consider a target reached.", 6, 2, 64, 1,
		ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting chestSearchRadius =
		new SliderSetting("Chest search radius",
			"Search radius around the target for nearby chests/containers.", 20,
			4, 64, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting chestInteractRange =
		new SliderSetting("Chest interact range",
			"Distance to interact with the chest once baritone arrives.", 3.0,
			2.0, 6.0, 0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting chestOpenTimeoutSec = new SliderSetting(
		"Chest open timeout", "Max seconds to open a chest before skipping.",
		7, 3, 30, 1, ValueDisplay.INTEGER.withSuffix("s"));
	private final SliderSetting lootDelay = new SliderSetting("Loot delay",
		"Delay between shift-clicking item stacks in the chest.", 200, 0, 500,
		10, ValueDisplay.INTEGER.withSuffix("ms"));
	private final SliderSetting chestInteractionSpeed = new SliderSetting(
		"Chest interaction speed",
		"Scales chest open retries and looting clicks. Higher values are faster.",
		0.25, 0.25, 5.0, 0.05, ValueDisplay.DECIMAL.withSuffix("x"));
	private final SliderSetting openRetryMs =
		new SliderSetting("Open retry", "Delay between chest open attempts.",
			400, 100, 2000, 50, ValueDisplay.INTEGER.withSuffix("ms"));
	private final SliderSetting searchTimeoutSec = new SliderSetting(
		"Search timeout", "Max seconds to look for a chest at a target.", 20, 5,
		120, 5, ValueDisplay.INTEGER.withSuffix("s"));
	private final CheckboxSetting retargetNearest = new CheckboxSetting(
		"Retarget nearest",
		"While traveling, switch to a much closer eligible waypoint if one appears nearby.",
		true);
	
	private final EnumSetting<LootMode> lootMode = new EnumSetting<>(
		"Loot mode",
		"Loot filter mode. List/Item ID/Query match the same way as ItemESP.",
		LootMode.values(), LootMode.QUERY);
	private final ItemListSetting lootList = new ItemListSetting("Loot list",
		"Items to take when Loot mode is List.");
	private final TextFieldSetting lootItemId = new TextFieldSetting("Item ID",
		"Exact item ID to take when Loot mode is Item ID.",
		"minecraft:diamond");
	private final TextFieldSetting lootQuery = new TextFieldSetting("Query",
		"Comma-separated keywords to match item IDs or names.", "diamond");
	private final SliderSetting minTargetY = new SliderSetting("Min target Y",
		"Ignore waypoints below this Y level.", 40, -64, 320, 1,
		ValueDisplay.INTEGER.withSuffix(" blocks"));
	
	private final CheckboxSetting useChestEsp =
		new CheckboxSetting("Enable ChestESP",
			"Enables ChestESP while LootRunner is active.", true);
	private final CheckboxSetting useHandNoClip =
		new CheckboxSetting("Enable HandNoClip",
			"Enables HandNoClip while LootRunner is active.", true);
	private final CheckboxSetting useFlight =
		new CheckboxSetting("Enable Flight",
			"Allows LootRunner to toggle Flight during travel between targets.",
			true);
	private final CheckboxSetting useWurstPathing = new CheckboxSetting(
		"Use Wurst pathing",
		"Use Wurst's flight steering to reach each target before Baritone handles chests.",
		true);
	private final SliderSetting flightCruiseHeight =
		new SliderSetting("Flight cruise height",
			"How many blocks above the target to cruise before descending.", 75,
			8, 128, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting flightTravelSpeed =
		new SliderSetting("Flight travel speed",
			"Temporary Flight horizontal speed while traveling to targets.",
			6.0, 0.5, 10.0, 0.1, ValueDisplay.DECIMAL.withSuffix(" b/s"));
	private final SliderSetting flightTravelVSpeed =
		new SliderSetting("Flight vertical speed",
			"Temporary Flight vertical speed while traveling to targets.", 3.0,
			0.5, 6.0, 0.1, ValueDisplay.DECIMAL.withSuffix(" b/s"));
	private final CheckboxSetting smoothFlight = new CheckboxSetting(
		"Smooth flight",
		"Disables Flight's anti-kick and slow sneaking while LootRunner is flying to reduce jitter.",
		false);
	private final CheckboxSetting smoothFlightY =
		new CheckboxSetting("Smooth flight Y",
			"Gradually adjusts cruise height to prevent vertical oscillation.",
			false);
	private final CheckboxSetting breakFlightObstacles = new CheckboxSetting(
		"Break flight obstacles",
		"Break blocks in the way while flying to prevent getting stuck.", true);
	private final CheckboxSetting cruiseAdjust = new CheckboxSetting(
		"Cruise adjust",
		"Continuously nudges cruise height to avoid getting stuck in mid-air.",
		true);
	private final SliderSetting cruiseAdjustAmount =
		new SliderSetting("Cruise adjust amount",
			"How many blocks to oscillate the cruise height by.", 3, 0, 32, 1,
			ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting cruiseAdjustPeriodSec = new SliderSetting(
		"Cruise adjust period", "Seconds per full up/down cycle.", 1, 1, 20, 1,
		ValueDisplay.INTEGER.withSuffix("s"));
	private final SliderSetting flightMaxHeight =
		new SliderSetting("Flight max height",
			"Absolute Y limit while flying. Set to 0 to disable the cap.", 320,
			0, 320, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting exitSearchRadius = new SliderSetting(
		"Exit radius",
		"Baritone will move to a clearer spot after looting before flying away.",
		8, 0, 24, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting exitClearHeight =
		new SliderSetting("Exit clear height",
			"Minimum air blocks above the takeoff spot to avoid awnings.", 9, 2,
			16, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting exitTimeoutSec =
		new SliderSetting("Exit timeout",
			"Max seconds to find a better takeoff spot after looting.", 5, 3,
			30, 1, ValueDisplay.INTEGER.withSuffix("s"));
	private final SliderSetting chestPathTimeoutSec =
		new SliderSetting("Chest path timeout",
			"Max seconds to wait before flying directly to a chest.", 8, 5, 40,
			1, ValueDisplay.INTEGER.withSuffix("s"));
	private final CheckboxSetting preferFlightToChest = new CheckboxSetting(
		"Prefer flight to chest",
		"If the chest is significantly below you, fly to it instead of walking.",
		false);
	private final CheckboxSetting breakChestObstructions =
		new CheckboxSetting("Break chest obstructions",
			"Break blocks above chests if they prevent opening.", true);
	private final SliderSetting obstructionBreakTimeoutSec =
		new SliderSetting("Obstruction break timeout",
			"Max seconds to spend breaking blocks above a chest.", 5, 2, 30, 1,
			ValueDisplay.INTEGER.withSuffix("s"));
	private final SliderSetting travelTimeoutSec =
		new SliderSetting("Travel timeout",
			"Max seconds to spend traveling to a target before skipping.", 30,
			30, 300, 10, ValueDisplay.INTEGER.withSuffix("s"));
	private final SliderSetting flightIdleTimeoutSec = new SliderSetting(
		"Flight idle timeout",
		"Max seconds to hover without moving before recovering or skipping.", 5,
		2, 30, 1, ValueDisplay.INTEGER.withSuffix("s"));
	private final CheckboxSetting voidSafety = new CheckboxSetting(
		"Void safety", "Emergency ascend if falling into the void.", true);
	private final SliderSetting voidSafetyHeight = new SliderSetting(
		"Void safety height", "Y level where emergency ascend kicks in.", 20,
		-64, 64, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final CheckboxSetting useAntisocial =
		new CheckboxSetting("Enable Antisocial",
			"Enables Antisocial while LootRunner is active.", true);
	private final CheckboxSetting useAutoEat = new CheckboxSetting(
		"Enable AutoEat", "Enables AutoEat while LootRunner is active.", true);
	private final CheckboxSetting useAutoLeave =
		new CheckboxSetting("Enable AutoLeave",
			"Enables AutoLeave while LootRunner is active.", true);
	private final CheckboxSetting useQuickShulkerOnFull = new CheckboxSetting(
		"Use QuickShulker on inv full",
		"Runs QuickShulker when your inventory is full while looting.", true);
	private final CheckboxSetting quitOnInventoryFull = new CheckboxSetting(
		"Quit on inv full",
		"If inventory is full and QuickShulker can't run, disconnects with reason 'inventory full'.",
		false);
	private final CheckboxSetting quitOnComplete =
		new CheckboxSetting("Quit on completion",
			"Disconnects with reason 'lootrunner complete' when done.", true);
	
	private final CheckboxSetting persistCompletion = new CheckboxSetting(
		"Persist completion",
		"Store completed targets so they won't be revisited in future runs.",
		true);
	private final CheckboxSetting debugLogs = new CheckboxSetting("Debug logs",
		"Prints LootRunner status messages to chat.", false);
	private final CheckboxSetting crosshairInfo =
		new CheckboxSetting("Crosshair info",
			"Shows LootRunner status near the crosshair while active.", true);
	private final CheckboxSetting renderPath = new CheckboxSetting(
		"Render path", "Draws a line to the current LootRunner goal.", true);
	private final ColorSetting pathColor = new ColorSetting("Path color",
		"Colour of the rendered LootRunner path.", new Color(0, 255, 200, 255));
	private final ButtonSetting savePositionButton =
		new ButtonSetting("Stop & save position", this::stopAndSavePosition);
	private final ButtonSetting loadPositionButton =
		new ButtonSetting("Load position", this::loadSavedPosition);
	private final ButtonSetting reloadButton =
		new ButtonSetting("Reload export", this::reloadTargets);
	
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	
	private final Set<TargetKey> completed = new HashSet<>();
	private final Map<TargetKey, ResultEntry> completedDetails =
		new HashMap<>();
	private final Set<TargetKey> failedThisRun = new HashSet<>();
	private final List<LootTarget> targets = new ArrayList<>();
	
	private final BaritoneBridge baritone = new BaritoneBridge();
	
	private State state = State.IDLE;
	private LootTarget currentTarget;
	private BlockPos currentChestPos;
	private BlockPos exitPos;
	private BlockPos landingPos;
	private int exitAttempts;
	private int flightChestAttempts;
	private long lastExitGoalMs;
	private boolean exitToResumeTarget;
	private boolean emergencyForcedFlight;
	private boolean emergencyPrevFlightEnabled;
	private long chestBreakStartMs;
	private long stateStartMs;
	private long lastOpenAttemptMs;
	private long lastLootClickMs;
	private final ArrayDeque<Integer> lootQueue = new ArrayDeque<>();
	private List<ItemStack> chestBefore = new ArrayList<>();
	private boolean useWurstPathingForTarget;
	private boolean flightTemporarilyDisabled;
	private boolean flightOverridesApplied;
	private boolean waitingForQuickShulker;
	
	// aux hack state snapshots
	private boolean chestEspWasEnabled;
	private boolean handNoClipWasEnabled;
	private boolean flightWasEnabled;
	private boolean antisocialWasEnabled;
	private boolean autoEatWasEnabled;
	private boolean autoLeaveWasEnabled;
	private boolean quickShulkerWasEnabled;
	private boolean lastUseFlightSetting;
	private double savedFlightSpeed = -1;
	private double savedFlightVSpeed = -1;
	private double lastTravelDist = Double.NaN;
	private int ticksWithoutProgress;
	private int flightBobbingTicks;
	private int flightIdleTicks;
	private int flightEvadeTicks;
	private int flightEvadeDir;
	private int flightStuckUpTicks;
	private int forceDescendTicks;
	private boolean flightAscending;
	private boolean flightDescending;
	private double lastFlightY = Double.NaN;
	private int exitStuckTicks;
	private double lastExitDist = Double.NaN;
	private int exitIdleTicks;
	private long lastExitRepathMs;
	private long exitStartedMs;
	private double exitStartTargetDist = Double.NaN;
	private int chestStuckTicks;
	private double lastChestDist = Double.NaN;
	private long lastDebugMs;
	private long lastTravelRecoverMs;
	private long lastRetargetMs;
	private long nearTargetStartMs;
	private double lastDesiredFlightY = Double.NaN;
	private long lastFlightBreakMs;
	private int processedThisRun;
	private BlockPos resumePos;
	private TargetKey resumeTargetKey;
	private boolean resumePending;
	
	public LootRunnerHack()
	{
		super("LootRunner");
		setCategory(Category.OTHER);
		addSetting(exportFile);
		addSetting(savePositionButton);
		addSetting(loadPositionButton);
		addSetting(reloadButton);
		addSetting(onlyCurrentDimension);
		addSetting(targetRadius);
		addSetting(chestSearchRadius);
		addSetting(chestInteractRange);
		addSetting(chestOpenTimeoutSec);
		addSetting(searchTimeoutSec);
		addSetting(retargetNearest);
		addSetting(openRetryMs);
		addSetting(lootDelay);
		addSetting(chestInteractionSpeed);
		addSetting(lootMode);
		addSetting(lootList);
		addSetting(lootItemId);
		addSetting(lootQuery);
		addSetting(minTargetY);
		addSetting(useChestEsp);
		addSetting(useHandNoClip);
		addSetting(useFlight);
		addSetting(useWurstPathing);
		addSetting(flightCruiseHeight);
		addSetting(flightTravelSpeed);
		addSetting(flightTravelVSpeed);
		addSetting(smoothFlight);
		addSetting(smoothFlightY);
		addSetting(breakFlightObstacles);
		addSetting(cruiseAdjust);
		addSetting(cruiseAdjustAmount);
		addSetting(cruiseAdjustPeriodSec);
		addSetting(flightMaxHeight);
		addSetting(exitSearchRadius);
		addSetting(exitClearHeight);
		addSetting(exitTimeoutSec);
		addSetting(chestPathTimeoutSec);
		addSetting(preferFlightToChest);
		addSetting(breakChestObstructions);
		addSetting(obstructionBreakTimeoutSec);
		addSetting(travelTimeoutSec);
		addSetting(flightIdleTimeoutSec);
		addSetting(voidSafety);
		addSetting(voidSafetyHeight);
		addSetting(useAntisocial);
		addSetting(useAutoEat);
		addSetting(useAutoLeave);
		addSetting(useQuickShulkerOnFull);
		addSetting(quitOnInventoryFull);
		addSetting(quitOnComplete);
		addSetting(persistCompletion);
		addSetting(debugLogs);
		addSetting(crosshairInfo);
		addSetting(renderPath);
		addSetting(pathColor);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null || MC.level == null)
		{
			ChatUtils.error("Join a world before enabling LootRunner.");
			setEnabled(false);
			return;
		}
		
		if(!baritone.isAvailable())
		{
			ChatUtils.error("Baritone API not found. Start Baritone first.");
			setEnabled(false);
			return;
		}
		
		failedThisRun.clear();
		savedFlightSpeed = -1;
		savedFlightVSpeed = -1;
		lastTravelDist = Double.NaN;
		ticksWithoutProgress = 0;
		flightBobbingTicks = 0;
		flightIdleTicks = 0;
		flightTemporarilyDisabled = false;
		flightOverridesApplied = false;
		exitAttempts = 0;
		flightChestAttempts = 0;
		lastExitGoalMs = 0L;
		exitToResumeTarget = false;
		emergencyForcedFlight = false;
		emergencyPrevFlightEnabled = false;
		chestBreakStartMs = 0L;
		exitStuckTicks = 0;
		lastExitDist = Double.NaN;
		exitIdleTicks = 0;
		lastExitRepathMs = 0L;
		exitStartedMs = 0L;
		exitStartTargetDist = Double.NaN;
		chestStuckTicks = 0;
		lastChestDist = Double.NaN;
		lastDebugMs = 0L;
		lastTravelRecoverMs = 0L;
		lastRetargetMs = 0L;
		nearTargetStartMs = 0L;
		flightEvadeTicks = 0;
		flightEvadeDir = 1;
		flightStuckUpTicks = 0;
		forceDescendTicks = 0;
		flightAscending = false;
		flightDescending = false;
		lastFlightY = Double.NaN;
		lastDesiredFlightY = Double.NaN;
		lastFlightBreakMs = 0L;
		processedThisRun = 0;
		waitingForQuickShulker = false;
		
		loadCompleted();
		reloadTargets();
		if(targets.isEmpty())
		{
			ChatUtils.error("No SeedMapper export targets loaded.");
			setEnabled(false);
			return;
		}
		
		applyAuxHacksOnEnable();
		lastUseFlightSetting = useFlight.isChecked();
		baritone.applyFlightHints(lastUseFlightSetting);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(GUIRenderListener.class, this);
		EVENTS.add(RenderListener.class, this);
		state = State.IDLE;
		stateStartMs = System.currentTimeMillis();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(GUIRenderListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		log("Disabled.");
		baritone.cancelAll();
		restoreAuxHacksOnDisable();
		baritone.applyFlightHints(false);
		useWurstPathingForTarget = false;
		failedThisRun.clear();
		flightTemporarilyDisabled = false;
		clearFlightOverrides();
		flightOverridesApplied = false;
		savedFlightSpeed = -1;
		savedFlightVSpeed = -1;
		lastTravelDist = Double.NaN;
		ticksWithoutProgress = 0;
		flightBobbingTicks = 0;
		flightIdleTicks = 0;
		exitAttempts = 0;
		flightChestAttempts = 0;
		lastExitGoalMs = 0L;
		exitToResumeTarget = false;
		emergencyForcedFlight = false;
		emergencyPrevFlightEnabled = false;
		chestBreakStartMs = 0L;
		exitStuckTicks = 0;
		lastExitDist = Double.NaN;
		exitIdleTicks = 0;
		lastExitRepathMs = 0L;
		exitStartedMs = 0L;
		exitStartTargetDist = Double.NaN;
		chestStuckTicks = 0;
		lastChestDist = Double.NaN;
		lastDebugMs = 0L;
		lastTravelRecoverMs = 0L;
		lastRetargetMs = 0L;
		nearTargetStartMs = 0L;
		flightEvadeTicks = 0;
		flightEvadeDir = 1;
		flightStuckUpTicks = 0;
		forceDescendTicks = 0;
		flightAscending = false;
		flightDescending = false;
		lastFlightY = Double.NaN;
		lastDesiredFlightY = Double.NaN;
		lastFlightBreakMs = 0L;
		processedThisRun = 0;
		waitingForQuickShulker = false;
		PathProcessor.releaseControls();
		currentTarget = null;
		currentChestPos = null;
		exitPos = null;
		landingPos = null;
		exitAttempts = 0;
		flightChestAttempts = 0;
		lootQueue.clear();
		chestBefore = new ArrayList<>();
		state = State.IDLE;
		stateStartMs = 0L;
		lastOpenAttemptMs = 0L;
		lastLootClickMs = 0L;
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
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(!renderPath.isChecked() || MC.player == null)
			return;
		
		BlockPos goal = getRenderGoalPos();
		if(goal == null)
			return;
		
		Vec3 start = MC.player.getEyePosition(partialTicks);
		Vec3 end = Vec3.atCenterOf(goal);
		RenderUtils.drawLine(matrices, start, end, pathColor.getColorI(),
			false);
	}
	
	@Override
	public void onUpdate()
	{
		if(!isEnabled())
			return;
		
		if(MC.player == null || MC.level == null)
			return;
		
		if(voidSafety.isChecked() && shouldEmergencyAscend())
		{
			state = State.EMERGENCY_ASCEND;
		}
		
		if(debugLogs.isChecked())
			debugTick();
		
		if(useFlight.isChecked() != lastUseFlightSetting)
		{
			lastUseFlightSetting = useFlight.isChecked();
			baritone.applyFlightHints(lastUseFlightSetting);
		}
		
		if(state == State.IDLE && resumePending && resumePos != null)
		{
			startResumePosition();
			return;
		}
		
		if(currentTarget != null && !isTargetEligible(currentTarget))
		{
			log("Current target no longer matches filters; selecting next.");
			baritone.cancelAll();
			PathProcessor.releaseControls();
			setFlightForTravel(false);
			currentTarget = null;
			currentChestPos = null;
			exitPos = null;
			useWurstPathingForTarget = false;
			nearTargetStartMs = 0L;
			state = State.IDLE;
			stateStartMs = System.currentTimeMillis();
		}
		
		if(currentTarget == null)
		{
			selectNextTarget();
			if(currentTarget == null)
			{
				log("No remaining targets; stopping.");
				finishRun();
				return;
			}
		}
		
		switch(state)
		{
			case IDLE -> startPathToTarget();
			case RESUMING_POSITION -> tickResumePosition();
			case PATHING_TO_TARGET -> tickPathToTarget();
			case EXITING -> tickExit();
			case SEARCHING_CHEST -> tickSearchChest();
			case PATHING_TO_CHEST -> tickPathToChest();
			case FLIGHTING_TO_CHEST -> tickFlightToChest();
			case EMERGENCY_ASCEND -> tickEmergencyAscend();
			case OPENING_CHEST -> tickOpenChest();
			case LOOTING -> tickLooting();
			case COOLDOWN -> tickCooldown();
		}
	}
	
	private void startPathToTarget()
	{
		if(currentTarget == null)
			return;
		
		clearSneak();
		flightBobbingTicks = 0;
		
		useWurstPathingForTarget = useWurstPathing.isChecked();
		log("Pathing to target: " + currentTarget.describe()
			+ (useWurstPathingForTarget ? " (flight)" : " (baritone)"));
		if(useWurstPathingForTarget)
		{
			baritone.cancelAll();
			setFlightForTravel(true);
		}else
		{
			baritone.setGoal(currentTarget.pos);
		}
		state = State.PATHING_TO_TARGET;
		stateStartMs = System.currentTimeMillis();
	}
	
	private void tickPathToTarget()
	{
		if(currentTarget == null)
			return;
		
		clearSneak();
		
		if(useWurstPathingForTarget)
		{
			tickFlightToTarget();
			return;
		}
		
		double dist =
			MC.player.position().distanceTo(Vec3.atCenterOf(currentTarget.pos));
		long elapsed = System.currentTimeMillis() - stateStartMs;
		if(maybeRetargetToNearest(dist))
		{
			dist = MC.player.position()
				.distanceTo(Vec3.atCenterOf(currentTarget.pos));
			elapsed = System.currentTimeMillis() - stateStartMs;
		}
		
		if(dist <= targetRadius.getValue() * 2.0)
		{
			if(nearTargetStartMs == 0L)
				nearTargetStartMs = System.currentTimeMillis();
		}else
		{
			nearTargetStartMs = 0L;
		}
		
		if(elapsed > 5000L)
		{
			BlockPos nearby =
				findNearestChest(BlockPos.containing(MC.player.position()),
					chestSearchRadius.getValueI());
			if(nearby != null)
			{
				log("Chest nearby while traveling; switching to chest search.");
				currentChestPos = nearby;
				chestStuckTicks = 0;
				lastChestDist = Double.NaN;
				if(useFlight.isChecked() && preferFlightToChest.isChecked()
					&& MC.player.getY() - nearby.getY() > 6.0)
				{
					state = State.FLIGHTING_TO_CHEST;
				}else
				{
					baritone.setGoal(nearby);
					state = State.PATHING_TO_CHEST;
				}
				stateStartMs = System.currentTimeMillis();
				return;
			}
		}
		if(dist <= targetRadius.getValue()
			|| (elapsed > 6000 && dist <= targetRadius.getValue() * 2.0))
		{
			baritone.cancelAll();
			log("Target reached; searching for chest.");
			state = State.SEARCHING_CHEST;
			stateStartMs = System.currentTimeMillis();
			nearTargetStartMs = 0L;
			return;
		}
		
		if(nearTargetStartMs > 0L && System.currentTimeMillis()
			- nearTargetStartMs > searchTimeoutSec.getValueF() * 1000L)
		{
			log("Near target too long; switching to chest search.");
			state = State.SEARCHING_CHEST;
			stateStartMs = System.currentTimeMillis();
			nearTargetStartMs = 0L;
			return;
		}
		
		if(elapsed > travelTimeoutSec.getValueF() * 1000L)
		{
			log("Target path timeout; marking missing.");
			markTargetComplete("missing", null, null, null);
			moveToNextTarget();
		}
	}
	
	private void tickFlightToTarget()
	{
		if(!useFlight.isChecked())
		{
			useWurstPathingForTarget = false;
			baritone.setGoal(currentTarget.pos);
			state = State.PATHING_TO_TARGET;
			stateStartMs = System.currentTimeMillis();
			return;
		}
		
		clearSneak();
		
		setFlightForTravel(true);
		PathProcessor.lockControls();
		
		Vec3 targetCenter = Vec3.atCenterOf(currentTarget.pos);
		double dx = targetCenter.x - MC.player.getX();
		double dz = targetCenter.z - MC.player.getZ();
		double horizDist = Math.sqrt(dx * dx + dz * dz);
		double radius = targetRadius.getValue();
		if(maybeRetargetToNearest(horizDist))
		{
			targetCenter = Vec3.atCenterOf(currentTarget.pos);
			dx = targetCenter.x - MC.player.getX();
			dz = targetCenter.z - MC.player.getZ();
			horizDist = Math.sqrt(dx * dx + dz * dz);
		}
		
		if(!Double.isNaN(lastTravelDist))
		{
			if(horizDist > lastTravelDist - 0.25)
				ticksWithoutProgress++;
			else
				ticksWithoutProgress = 0;
		}
		lastTravelDist = horizDist;
		
		if(horizDist <= radius * 2.0)
		{
			if(nearTargetStartMs == 0L)
				nearTargetStartMs = System.currentTimeMillis();
		}else
		{
			nearTargetStartMs = 0L;
		}
		
		double cruiseBonus = ticksWithoutProgress > 40
			? Math.min(32, flightCruiseHeight.getValue()) : 0;
		double cruise =
			horizDist < radius * 4 ? Math.min(16, flightCruiseHeight.getValue())
				: flightCruiseHeight.getValue();
		double desiredY = currentTarget.pos.getY()
			+ (horizDist > radius ? cruise + cruiseBonus : 1.0);
		if(cruiseAdjust.isChecked() && MC.player != null)
		{
			int periodTicks =
				Math.max(20, cruiseAdjustPeriodSec.getValueI() * 20);
			double amount = cruiseAdjustAmount.getValue();
			if(amount > 0)
			{
				int t = MC.player.tickCount % periodTicks;
				double phase = (double)t / periodTicks;
				// Triangle wave in [-amount, amount]
				double tri = phase < 0.5 ? (phase * 4 - 1) : (3 - phase * 4);
				desiredY += tri * amount;
			}
		}
		double maxY = flightMaxHeight.getValue();
		if(maxY > 0)
			desiredY = Math.min(desiredY, maxY);
		if(smoothFlightY.isChecked())
		{
			if(Double.isNaN(lastDesiredFlightY))
				lastDesiredFlightY = MC.player.getY();
			double maxStep = Math.max(0.1, flightTravelVSpeed.getValue());
			desiredY = approachValue(lastDesiredFlightY, desiredY, maxStep);
			lastDesiredFlightY = desiredY;
		}else
		{
			lastDesiredFlightY = Double.NaN;
		}
		double yDiff = desiredY - MC.player.getY();
		boolean near = horizDist <= radius;
		double verticalDeadzone = smoothFlightY.isChecked() ? 0.6 : 0.5;
		Vec3 motion = MC.player.getDeltaMovement();
		double horizontalSpeed =
			Math.sqrt(motion.x * motion.x + motion.z * motion.z);
		double verticalSpeed = Math.abs(motion.y);
		boolean bobbing = horizDist > radius * 1.5 && ticksWithoutProgress > 20
			&& Math.abs(yDiff) <= 2.5 && horizontalSpeed < 0.06
			&& verticalSpeed > 0.04;
		if(bobbing)
			flightBobbingTicks++;
		else if(horizontalSpeed > 0.08 || ticksWithoutProgress < 8)
			flightBobbingTicks = Math.max(0, flightBobbingTicks - 2);
		else
			flightBobbingTicks = Math.max(0, flightBobbingTicks - 1);
		
		if(flightBobbingTicks > 30)
		{
			switchToBaritonePathing(
				"Flight bobbing in place; switching to Baritone pathing.");
			return;
		}
		
		double speed = motion.length();
		if(speed < 0.02)
			flightIdleTicks++;
		else
			flightIdleTicks = 0;
		
		if(flightIdleTicks > flightIdleTimeoutSec.getValueF() * 20.0)
		{
			flightIdleTicks = 0;
			if(horizDist <= radius * 2.0)
			{
				log("Flight idle near target; switching to chest search.");
				state = State.SEARCHING_CHEST;
				stateStartMs = System.currentTimeMillis();
				return;
			}
			
			log("Flight idle; running exit search to resume.");
			startExitForTravel();
			return;
		}
		int ceilingClearance =
			countAirAbove(BlockPos.containing(MC.player.position()), 6);
		boolean underAwn = ceilingClearance < 3;
		
		if(near && (Math.abs(yDiff) <= 3.0 || MC.player.onGround()))
		{
			PathProcessor.releaseControls();
			setFlightForTravel(false);
			useWurstPathingForTarget = false;
			landingPos = BlockPos.containing(MC.player.position());
			baritone.setGoal(currentTarget.pos);
			state = State.PATHING_TO_TARGET;
			stateStartMs = System.currentTimeMillis();
			nearTargetStartMs = 0L;
			return;
		}
		
		if(nearTargetStartMs > 0L && System.currentTimeMillis()
			- nearTargetStartMs > searchTimeoutSec.getValueF() * 1000L)
		{
			log("Near target too long; switching to chest search.");
			state = State.SEARCHING_CHEST;
			stateStartMs = System.currentTimeMillis();
			nearTargetStartMs = 0L;
			return;
		}
		
		if(ticksWithoutProgress > 80)
		{
			double dist = MC.player.position()
				.distanceTo(Vec3.atCenterOf(currentTarget.pos));
			long now = System.currentTimeMillis();
			if(dist > targetRadius.getValue() * 2.0
				&& now - lastTravelRecoverMs > 5000L)
			{
				log("Flight stuck en route; running exit search to resume.");
				lastTravelRecoverMs = now;
				ticksWithoutProgress = 0;
				startExitForTravel();
			}else
			{
				switchToBaritonePathing(
					"Flight stalled with no horizontal progress; switching to Baritone pathing.");
			}
			return;
		}
		
		PathProcessor.lockControls();
		WURST.getRotationFaker().faceVectorClientIgnorePitch(targetCenter);
		
		if(horizDist > radius && yDiff > 1.0)
		{
			MC.options.keyJump.setDown(true);
			MC.options.keyShift.setDown(false);
			MC.options.keyUp.setDown(false);
			return;
		}
		
		boolean descendOnly = near && yDiff < -verticalDeadzone;
		boolean climbOnly = (underAwn || MC.player.horizontalCollision)
			&& yDiff > verticalDeadzone;
		
		boolean blockedAhead = isBlockedAhead(1.0);
		boolean blockedAbove = underAwn && yDiff > verticalDeadzone;
		tryBreakFlightObstacle(blockedAhead, blockedAbove);
		updateFlightStuck(yDiff > 0.5, underAwn || blockedAhead);
		if(forceDescendTicks > 0)
		{
			forceDescendTicks--;
			MC.options.keyShift.setDown(true);
			return;
		}
		if((MC.player.horizontalCollision || blockedAhead)
			&& flightEvadeTicks <= 0)
		{
			flightEvadeTicks = 20;
			flightEvadeDir = MC.player.tickCount % 2 == 0 ? 1 : -1;
		}
		
		if(flightEvadeTicks > 0)
		{
			flightEvadeTicks--;
			MC.options.keyDown.setDown(true);
			if(flightEvadeDir > 0)
				MC.options.keyRight.setDown(true);
			else
				MC.options.keyLeft.setDown(true);
			MC.options.keyJump.setDown(yDiff > verticalDeadzone && !underAwn);
			MC.options.keyShift.setDown(yDiff < -verticalDeadzone);
			return;
		}
		
		boolean far = horizDist > radius * 4.0;
		boolean ascend = climbOnly ? yDiff > verticalDeadzone
			: shouldAscend(yDiff, verticalDeadzone);
		boolean descend =
			descendOnly || shouldDescend(yDiff, verticalDeadzone, far);
		
		MC.options.keyUp
			.setDown(!descendOnly && !climbOnly && horizDist > 0.75);
		MC.options.keyJump.setDown(ascend && (climbOnly || !descendOnly));
		MC.options.keyShift.setDown(descend);
	}
	
	private void tickSearchChest()
	{
		if(currentTarget == null)
			return;
		
		long now = System.currentTimeMillis();
		long timeoutMs = (long)searchTimeoutSec.getValueF() * 1000L;
		if(now - stateStartMs > timeoutMs)
		{
			log("Chest search timed out; marking missing.");
			markTargetComplete("missing", null, null, null);
			moveToNextTarget();
			return;
		}
		
		BlockPos found =
			findNearestChest(currentTarget.pos, chestSearchRadius.getValueI());
		if(found == null)
			return;
		
		log("Chest found at " + found.getX() + ", " + found.getY() + ", "
			+ found.getZ());
		currentChestPos = found;
		chestStuckTicks = 0;
		lastChestDist = Double.NaN;
		chestBreakStartMs = 0L;
		if(useFlight.isChecked() && preferFlightToChest.isChecked()
			&& MC.player != null && MC.player.getY() - found.getY() > 6.0)
		{
			log("Chest below player; flying to chest.");
			state = State.FLIGHTING_TO_CHEST;
		}else
		{
			baritone.setGoal(found);
			state = State.PATHING_TO_CHEST;
		}
		stateStartMs = now;
	}
	
	private void tickPathToChest()
	{
		if(currentChestPos == null)
		{
			state = State.SEARCHING_CHEST;
			stateStartMs = System.currentTimeMillis();
			return;
		}
		
		clearSneak();
		
		double dist =
			MC.player.position().distanceTo(Vec3.atCenterOf(currentChestPos));
		if(!Double.isNaN(lastChestDist))
		{
			if(dist > lastChestDist - 0.2)
				chestStuckTicks++;
			else
				chestStuckTicks = 0;
		}
		lastChestDist = dist;
		if(chestStuckTicks > 80)
		{
			chestStuckTicks = 0;
			baritone.cancelAll();
			if(useFlight.isChecked())
			{
				log("Chest path stuck; switching to flight-to-chest.");
				state = State.FLIGHTING_TO_CHEST;
				stateStartMs = System.currentTimeMillis();
				return;
			}
			
			log("Chest path stuck; marking missing.");
			markTargetComplete("missing", null, null, null);
			moveToNextTarget();
			return;
		}
		
		if(dist <= chestInteractRange.getValue())
		{
			baritone.cancelAll();
			state = State.OPENING_CHEST;
			stateStartMs = System.currentTimeMillis();
			lastOpenAttemptMs = 0L;
			return;
		}
		
		long elapsed = System.currentTimeMillis() - stateStartMs;
		if(elapsed > chestPathTimeoutSec.getValueF() * 1000L)
		{
			baritone.cancelAll();
			if(useFlight.isChecked())
			{
				log("Chest path timeout; switching to flight-to-chest.");
				state = State.FLIGHTING_TO_CHEST;
				stateStartMs = System.currentTimeMillis();
			}else
			{
				log("Chest path timeout; marking missing.");
				markTargetComplete("missing", null, null, null);
				moveToNextTarget();
			}
		}
	}
	
	private void tickFlightToChest()
	{
		if(currentChestPos == null)
		{
			state = State.SEARCHING_CHEST;
			stateStartMs = System.currentTimeMillis();
			return;
		}
		
		if(!useFlight.isChecked())
		{
			state = State.PATHING_TO_CHEST;
			stateStartMs = System.currentTimeMillis();
			return;
		}
		
		setFlightForTravel(true);
		PathProcessor.lockControls();
		
		Vec3 targetCenter = Vec3.atCenterOf(currentChestPos);
		double dx = targetCenter.x - MC.player.getX();
		double dz = targetCenter.z - MC.player.getZ();
		double horizDist = Math.sqrt(dx * dx + dz * dz);
		double desiredY = currentChestPos.getY() + 1.0;
		double yDiff = desiredY - MC.player.getY();
		double verticalDeadzone = smoothFlightY.isChecked() ? 0.6 : 0.5;
		if(!Double.isNaN(lastChestDist))
		{
			if(horizDist > lastChestDist - 0.15)
				chestStuckTicks++;
			else
				chestStuckTicks = 0;
		}
		lastChestDist = horizDist;
		if(chestStuckTicks > 60)
		{
			chestStuckTicks = 0;
			switchToChestPathing(
				"Flight-to-chest stuck; switching to pathing.");
			return;
		}
		
		long elapsed = System.currentTimeMillis() - stateStartMs;
		if(elapsed > chestPathTimeoutSec.getValueF() * 2000L)
		{
			flightChestAttempts++;
			if(flightChestAttempts >= 2)
			{
				log("Flight-to-chest failed; marking missing.");
				markTargetComplete("missing", null, null, null);
				moveToNextTarget();
				return;
			}
			
			log("Flight-to-chest retry; re-scanning for chest.");
			state = State.SEARCHING_CHEST;
			stateStartMs = System.currentTimeMillis();
			return;
		}
		
		if(horizDist <= chestInteractRange.getValue() && Math.abs(yDiff) <= 2.5)
		{
			PathProcessor.releaseControls();
			setFlightForTravel(false);
			state = State.OPENING_CHEST;
			stateStartMs = System.currentTimeMillis();
			lastOpenAttemptMs = 0L;
			return;
		}
		
		WURST.getRotationFaker().faceVectorClientIgnorePitch(targetCenter);
		
		boolean blockedAhead = isBlockedAhead(1.0);
		boolean blockedAbove = yDiff > verticalDeadzone
			&& countAirAbove(BlockPos.containing(MC.player.position()), 3) < 2;
		tryBreakFlightObstacle(blockedAhead, blockedAbove);
		updateFlightStuck(yDiff > 0.5, blockedAhead);
		if(forceDescendTicks > 0)
		{
			forceDescendTicks--;
			MC.options.keyShift.setDown(true);
			return;
		}
		if((MC.player.horizontalCollision || blockedAhead)
			&& flightEvadeTicks <= 0)
		{
			flightEvadeTicks = 20;
			flightEvadeDir = MC.player.tickCount % 2 == 0 ? 1 : -1;
		}
		
		if(flightEvadeTicks > 0)
		{
			flightEvadeTicks--;
			MC.options.keyDown.setDown(true);
			if(flightEvadeDir > 0)
				MC.options.keyRight.setDown(true);
			else
				MC.options.keyLeft.setDown(true);
			MC.options.keyJump.setDown(yDiff > verticalDeadzone);
			MC.options.keyShift.setDown(yDiff < -verticalDeadzone);
			return;
		}
		
		boolean ascend = shouldAscend(yDiff, verticalDeadzone);
		boolean descend = shouldDescend(yDiff, verticalDeadzone, false);
		
		MC.options.keyUp.setDown(horizDist > 0.75);
		MC.options.keyJump.setDown(ascend);
		MC.options.keyShift.setDown(descend);
	}
	
	private void switchToBaritonePathing(String reason)
	{
		if(currentTarget == null)
			return;
		
		log(reason);
		PathProcessor.releaseControls();
		clearSneak();
		setFlightForTravel(false);
		useWurstPathingForTarget = false;
		flightBobbingTicks = 0;
		ticksWithoutProgress = 0;
		lastTravelDist = Double.NaN;
		flightIdleTicks = 0;
		baritone.setGoal(currentTarget.pos);
		state = State.PATHING_TO_TARGET;
		stateStartMs = System.currentTimeMillis();
	}
	
	private void switchToChestPathing(String reason)
	{
		if(currentChestPos == null)
		{
			state = State.SEARCHING_CHEST;
			stateStartMs = System.currentTimeMillis();
			return;
		}
		
		log(reason);
		PathProcessor.releaseControls();
		clearSneak();
		setFlightForTravel(false);
		baritone.setGoal(currentChestPos);
		state = State.PATHING_TO_CHEST;
		stateStartMs = System.currentTimeMillis();
	}
	
	private double getCurrentTargetDistance()
	{
		if(MC.player == null || currentTarget == null)
			return Double.NaN;
		return MC.player.position()
			.distanceTo(Vec3.atCenterOf(currentTarget.pos));
	}
	
	private void resetTravelAfterExitStall(String reason)
	{
		log(reason);
		baritone.cancelAll();
		PathProcessor.releaseControls();
		clearSneak();
		setFlightForTravel(false);
		exitToResumeTarget = false;
		exitPos = null;
		exitAttempts = 0;
		lastExitGoalMs = 0L;
		exitIdleTicks = 0;
		exitStuckTicks = 0;
		lastExitDist = Double.NaN;
		lastExitRepathMs = 0L;
		exitStartedMs = 0L;
		exitStartTargetDist = Double.NaN;
		nearTargetStartMs = 0L;
		ticksWithoutProgress = 0;
		lastTravelDist = Double.NaN;
		flightBobbingTicks = 0;
		flightIdleTicks = 0;
		useWurstPathingForTarget = false;
		state = State.IDLE;
		stateStartMs = System.currentTimeMillis();
	}
	
	private void tickOpenChest()
	{
		if(currentChestPos == null)
		{
			state = State.SEARCHING_CHEST;
			stateStartMs = System.currentTimeMillis();
			return;
		}
		
		clearSneak();
		
		if(breakChestObstructions.isChecked())
		{
			BlockPos above = currentChestPos.above();
			if(MC.level != null && !MC.level.getBlockState(above)
				.getCollisionShape(MC.level, above).isEmpty())
			{
				if(chestBreakStartMs == 0L)
					chestBreakStartMs = System.currentTimeMillis();
				
				long elapsed = System.currentTimeMillis() - chestBreakStartMs;
				if(elapsed > obstructionBreakTimeoutSec.getValueF() * 1000L)
				{
					log("Chest obstruction break timed out; marking missing.");
					markTargetComplete("missing", null, null, null);
					moveToNextTarget();
					return;
				}
				
				breakBlock(above);
				return;
			}else
			{
				chestBreakStartMs = 0L;
			}
		}
		
		if(isChestScreenOpen())
		{
			chestBreakStartMs = 0L;
			prepareLootQueue();
			state = State.LOOTING;
			stateStartMs = System.currentTimeMillis();
			return;
		}
		
		if(System.currentTimeMillis()
			- stateStartMs > chestOpenTimeoutSec.getValueF() * 1000L)
		{
			log("Chest open timeout; marking missing.");
			markTargetComplete("missing", null, null, null);
			moveToNextTarget();
			return;
		}
		
		long now = System.currentTimeMillis();
		if(now - lastOpenAttemptMs < getAdjustedChestDelay(
			openRetryMs.getValueI()))
			return;
		
		lastOpenAttemptMs = now;
		BlockHitResult hit =
			new BlockHitResult(Vec3.atCenterOf(currentChestPos), Direction.UP,
				currentChestPos, false);
		InteractionSimulator.rightClickBlock(hit, InteractionHand.MAIN_HAND);
	}
	
	private void tickLooting()
	{
		if(handleInventoryFullDuringLooting())
			return;
		
		if(!isChestScreenOpen())
		{
			markTargetComplete("missing", null, null, null);
			moveToNextTarget();
			return;
		}
		
		if(lootQueue.isEmpty())
		{
			List<ItemStack> chestAfter = readChestItems();
			Map<String, Integer> before = countItems(chestBefore);
			Map<String, Integer> after = countItems(chestAfter);
			Map<String, Integer> taken = diffCounts(before, after);
			String status = taken.isEmpty()
				? (after.isEmpty() ? "empty" : "skipped") : "looted";
			
			closeChestScreen();
			markTargetComplete(status, before, taken, after);
			startExitOrNextTarget();
			return;
		}
		
		long now = System.currentTimeMillis();
		if(now - lastLootClickMs < getAdjustedChestDelay(lootDelay.getValueI()))
			return;
		
		Integer slotIdx = lootQueue.pollFirst();
		lastLootClickMs = now;
		
		var screen = MC.screen;
		if(!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> sc))
			return;
		
		List<Slot> slots = sc.getMenu().slots;
		if(slotIdx < 0 || slotIdx >= slots.size())
			return;
		
		Slot slot = slots.get(slotIdx);
		sc.slotClicked(slot, slot.index, 0,
			net.minecraft.world.inventory.ClickType.QUICK_MOVE);
	}
	
	private void tickCooldown()
	{
		if(System.currentTimeMillis() - stateStartMs > 200L)
			state = State.IDLE;
	}
	
	private long getAdjustedChestDelay(int baseDelayMs)
	{
		if(baseDelayMs <= 0)
			return 0L;
		
		double speed = chestInteractionSpeed.getValue();
		if(speed <= 0)
			return baseDelayMs;
		
		return Math.max(0L, Math.round(baseDelayMs / speed));
	}
	
	private void startResumePosition()
	{
		if(MC.player == null || resumePos == null)
		{
			resumePending = false;
			return;
		}
		
		PathProcessor.releaseControls();
		clearSneak();
		setFlightForTravel(false);
		baritone.cancelAll();
		baritone.setGoal(resumePos);
		state = State.RESUMING_POSITION;
		stateStartMs = System.currentTimeMillis();
		log("Resuming from saved position: " + resumePos.getX() + ", "
			+ resumePos.getY() + ", " + resumePos.getZ());
	}
	
	private void tickResumePosition()
	{
		if(MC.player == null || resumePos == null)
		{
			resumePending = false;
			state = State.IDLE;
			return;
		}
		
		double dist =
			MC.player.position().distanceTo(Vec3.atCenterOf(resumePos));
		if(dist <= 2.0)
		{
			baritone.cancelAll();
			resumePending = false;
			applyResumeTarget();
			ChatUtils.message("LootRunner resumed at saved position.");
			state = State.IDLE;
			stateStartMs = System.currentTimeMillis();
			return;
		}
		
		long now = System.currentTimeMillis();
		if(now - lastExitGoalMs > 1000L)
		{
			baritone.setGoal(resumePos);
			lastExitGoalMs = now;
		}
		
		if(now - stateStartMs > travelTimeoutSec.getValueF() * 1000L)
		{
			baritone.cancelAll();
			resumePending = false;
			ChatUtils.error("Couldn't reach saved position; continuing route.");
			state = State.IDLE;
			stateStartMs = now;
		}
	}
	
	private void applyResumeTarget()
	{
		if(resumeTargetKey == null)
			return;
		
		LootTarget match = targets.stream()
			.filter(t -> new TargetKey(t).equals(resumeTargetKey)).findFirst()
			.orElse(null);
		if(isTargetEligible(match))
			currentTarget = match;
		
		resumeTargetKey = null;
	}
	
	private void moveToNextTarget()
	{
		currentTarget = null;
		currentChestPos = null;
		exitPos = null;
		landingPos = null;
		exitAttempts = 0;
		flightChestAttempts = 0;
		lastExitGoalMs = 0L;
		exitToResumeTarget = false;
		emergencyForcedFlight = false;
		emergencyPrevFlightEnabled = false;
		chestBreakStartMs = 0L;
		exitIdleTicks = 0;
		exitStuckTicks = 0;
		lastExitDist = Double.NaN;
		lastExitRepathMs = 0L;
		exitStartedMs = 0L;
		exitStartTargetDist = Double.NaN;
		chestStuckTicks = 0;
		lastChestDist = Double.NaN;
		lastDebugMs = 0L;
		lastTravelRecoverMs = 0L;
		lastRetargetMs = 0L;
		nearTargetStartMs = 0L;
		lootQueue.clear();
		chestBefore = new ArrayList<>();
		useWurstPathingForTarget = false;
		lastTravelDist = Double.NaN;
		ticksWithoutProgress = 0;
		flightBobbingTicks = 0;
		flightIdleTicks = 0;
		flightEvadeTicks = 0;
		flightEvadeDir = 1;
		flightStuckUpTicks = 0;
		forceDescendTicks = 0;
		flightAscending = false;
		flightDescending = false;
		lastFlightY = Double.NaN;
		lastDesiredFlightY = Double.NaN;
		lastFlightBreakMs = 0L;
		waitingForQuickShulker = false;
		PathProcessor.releaseControls();
		state = State.COOLDOWN;
		stateStartMs = System.currentTimeMillis();
	}
	
	private boolean handleInventoryFullDuringLooting()
	{
		if(MC.player == null)
			return false;
		
		var quickShulker = WURST.getHax().quickShulkerHack;
		if(waitingForQuickShulker)
		{
			if(quickShulker.isBusy())
				return true;
			
			waitingForQuickShulker = false;
			if(!isChestScreenOpen())
			{
				state = currentChestPos != null ? State.OPENING_CHEST
					: State.SEARCHING_CHEST;
				stateStartMs = System.currentTimeMillis();
				lastOpenAttemptMs = 0L;
				chestBreakStartMs = 0L;
				return true;
			}
			
			if(!isInventoryFull())
				return false;
		}
		
		if(!isInventoryFull())
			return false;
		
		if(useQuickShulkerOnFull.isChecked())
		{
			if(!quickShulker.isEnabled())
				quickShulker.setEnabled(true);
			
			if(quickShulker.isBusy())
			{
				waitingForQuickShulker = true;
				return true;
			}
			
			if(quickShulker.hasUsableShulker())
			{
				log("Inventory full; running QuickShulker.");
				quickShulker.triggerFromGui();
				waitingForQuickShulker = quickShulker.isBusy();
				if(waitingForQuickShulker)
					return true;
				if(!isInventoryFull())
					return false;
			}
		}
		
		handleInventoryFullStop();
		return true;
	}
	
	private boolean isInventoryFull()
	{
		return MC.player != null
			&& MC.player.getInventory().getFreeSlot() == -1;
	}
	
	private void handleInventoryFullStop()
	{
		ChatUtils.message("LootRunner stopped: inventory full.");
		setEnabled(false);
		
		if(quitOnInventoryFull.isChecked() && MC.level != null)
			MC.level.disconnect(Component.literal("inventory full"));
	}
	
	private void startExitOrNextTarget()
	{
		if(!useWurstPathing.isChecked() || !useFlight.isChecked())
		{
			moveToNextTarget();
			return;
		}
		
		setFlightForTravel(false);
		exitToResumeTarget = false;
		log("Exit search after looting.");
		
		exitAttempts = 0;
		flightChestAttempts = 0;
		exitIdleTicks = 0;
		exitStuckTicks = 0;
		lastExitDist = Double.NaN;
		lastExitRepathMs = 0L;
		
		int radius = exitSearchRadius.getValueI();
		if(radius <= 0 || MC.player == null || MC.level == null)
		{
			moveToNextTarget();
			return;
		}
		
		if(landingPos == null)
			landingPos = BlockPos.containing(MC.player.position());
		
		BlockPos center = BlockPos.containing(MC.player.position());
		BlockPos found = findExitSpot(center, radius);
		if(found == null)
			found = findExitSpot(offsetLateral(center, radius, true), radius);
		if(found == null)
			found = findExitSpot(offsetLateral(center, radius, false), radius);
		if(found == null)
		{
			moveToNextTarget();
			return;
		}
		
		exitPos = found;
		baritone.setGoal(found);
		state = State.EXITING;
		stateStartMs = System.currentTimeMillis();
	}
	
	private void startExitForTravel()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		setFlightForTravel(false);
		exitToResumeTarget = true;
		exitAttempts = 0;
		lastExitGoalMs = 0L;
		exitIdleTicks = 0;
		exitStuckTicks = 0;
		lastExitDist = Double.NaN;
		lastExitRepathMs = 0L;
		exitStartedMs = System.currentTimeMillis();
		exitStartTargetDist = getCurrentTargetDistance();
		log("Travel stuck; exit search to resume flight.");
		
		BlockPos center = BlockPos.containing(MC.player.position());
		BlockPos found = findExitSpot(center, exitSearchRadius.getValueI());
		if(found == null)
			found = findExitSpot(
				offsetLateral(center, exitSearchRadius.getValueI(), true),
				exitSearchRadius.getValueI());
		if(found == null)
			found = findExitSpot(
				offsetLateral(center, exitSearchRadius.getValueI(), false),
				exitSearchRadius.getValueI());
		if(found == null)
		{
			exitToResumeTarget = false;
			setFlightForTravel(true);
			log("Travel exit search failed; resuming flight.");
			return;
		}
		
		exitPos = found;
		baritone.setGoal(found);
		lastExitGoalMs = System.currentTimeMillis();
		state = State.EXITING;
		stateStartMs = System.currentTimeMillis();
	}
	
	private void tickEmergencyAscend()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		if(!shouldEmergencyAscend())
		{
			PathProcessor.releaseControls();
			if(emergencyForcedFlight && WURST.getHax().flightHack.isEnabled()
				&& !emergencyPrevFlightEnabled)
				WURST.getHax().flightHack.setEnabled(false);
			emergencyForcedFlight = false;
			emergencyPrevFlightEnabled = false;
			state = State.COOLDOWN;
			stateStartMs = System.currentTimeMillis();
			log("Emergency ascend cleared.");
			return;
		}
		
		if(WURST.getHax().flightHack.isEnabled())
		{
			emergencyPrevFlightEnabled = true;
			emergencyForcedFlight = false;
		}else
		{
			emergencyPrevFlightEnabled = false;
			WURST.getHax().flightHack.setEnabled(true);
			emergencyForcedFlight = true;
		}
		
		PathProcessor.lockControls();
		MC.options.keyJump.setDown(true);
		MC.options.keyShift.setDown(false);
		MC.options.keyUp.setDown(false);
	}
	
	private boolean shouldEmergencyAscend()
	{
		if(MC.player == null || MC.level == null)
			return false;
		if(!MC.level.dimension().identifier().toString()
			.equals("minecraft:the_end"))
			return false;
		
		double y = MC.player.getY();
		if(y > voidSafetyHeight.getValue())
			return false;
		
		return !MC.player.onGround();
	}
	
	private void tickExit()
	{
		setFlightForTravel(false);
		PathProcessor.releaseControls();
		clearSneak();
		if(exitPos == null)
		{
			if(exitToResumeTarget)
			{
				log("Exit failed; resuming flight to target.");
				exitToResumeTarget = false;
				exitStartedMs = 0L;
				exitStartTargetDist = Double.NaN;
				setFlightForTravel(true);
				state = State.PATHING_TO_TARGET;
				stateStartMs = System.currentTimeMillis();
				return;
			}
			
			log("Exit goal missing; failing target.");
			failCurrentTarget("exit_failed");
			return;
		}
		
		long now = System.currentTimeMillis();
		if(exitStartedMs == 0L)
		{
			exitStartedMs = now;
			exitStartTargetDist = getCurrentTargetDistance();
		}
		
		if(exitToResumeTarget && currentTarget != null)
		{
			double targetDist = getCurrentTargetDistance();
			double driftBudget =
				Math.max(96.0, exitSearchRadius.getValue() * 12.0);
			if(!Double.isNaN(exitStartTargetDist)
				&& !Double.isNaN(targetDist)
				&& targetDist > exitStartTargetDist + driftBudget)
			{
				resetTravelAfterExitStall(
					"Exit drifted too far from target; resetting travel.");
				return;
			}
			
			long hardTimeoutMs = Math.max(15000L,
				(long)(exitTimeoutSec.getValueF() * 1000.0 * 3.0));
			if(now - exitStartedMs > hardTimeoutMs)
			{
				resetTravelAfterExitStall(
					"Exit recovery took too long; resetting travel.");
				return;
			}
		}
		
		if(now - lastExitGoalMs > 1000L)
		{
			baritone.setGoal(exitPos);
			lastExitGoalMs = now;
		}
		long timeoutMs = (long)exitTimeoutSec.getValueF() * 1000L;
		if(now - stateStartMs > timeoutMs)
		{
			baritone.cancelAll();
			if(landingPos != null && !landingPos.equals(exitPos))
			{
				log("Exit timeout; retrying from landing spot.");
				exitPos = landingPos;
				stateStartMs = System.currentTimeMillis();
				return;
			}
			
			exitAttempts++;
			if(exitAttempts >= 3)
			{
				log("Exit failed after retries.");
				if(exitToResumeTarget)
				{
					exitToResumeTarget = false;
					exitStartedMs = 0L;
					exitStartTargetDist = Double.NaN;
					setFlightForTravel(true);
					state = State.PATHING_TO_TARGET;
					stateStartMs = System.currentTimeMillis();
					return;
				}
				
				failCurrentTarget("exit_failed");
				return;
			}
			
			BlockPos center = BlockPos.containing(MC.player.position());
			BlockPos attemptCenter = switch(exitAttempts)
			{
				case 1 -> center;
				case 2 -> offsetLateral(center, exitSearchRadius.getValueI(),
					true);
				default -> offsetLateral(center, exitSearchRadius.getValueI(),
					false);
			};
			BlockPos found =
				findExitSpot(attemptCenter, exitSearchRadius.getValueI());
			if(found == null)
			{
				log("Exit re-search failed.");
				failCurrentTarget("exit_failed");
				return;
			}
			
			exitPos = found;
			baritone.setGoal(found);
			log("Exit re-search set new goal.");
			stateStartMs = System.currentTimeMillis();
			return;
		}
		
		double dist = MC.player.position().distanceTo(Vec3.atCenterOf(exitPos));
		double speed = MC.player.getDeltaMovement().length();
		if(speed < 0.02)
			exitIdleTicks++;
		else
			exitIdleTicks = 0;
		
		if(exitIdleTicks > 40)
		{
			tryRepathExit("Exit idle; re-pathing.");
			exitIdleTicks = 0;
		}
		if(!Double.isNaN(lastExitDist))
		{
			if(dist > lastExitDist - 0.2)
				exitStuckTicks++;
			else
				exitStuckTicks = 0;
		}
		lastExitDist = dist;
		if(exitStuckTicks > 80)
		{
			exitStuckTicks = 0;
			tryRepathExit("Exit stuck; re-pathing.");
		}
		if(dist <= Math.max(3.0, targetRadius.getValue() / 2.0))
		{
			baritone.cancelAll();
			exitAttempts = 0;
			if(exitToResumeTarget)
			{
				log("Exit reached; resuming flight to target.");
				exitToResumeTarget = false;
				exitStartedMs = 0L;
				exitStartTargetDist = Double.NaN;
				setFlightForTravel(true);
				state = State.PATHING_TO_TARGET;
				stateStartMs = System.currentTimeMillis();
			}else
			{
				log("Exit reached; moving to next target.");
				moveToNextTarget();
			}
		}
	}
	
	private void setFlightForTravel(boolean enable)
	{
		if(!useFlight.isChecked())
			return;
		
		var hax = WURST.getHax();
		if(enable)
		{
			if(!hax.flightHack.isEnabled())
				hax.flightHack.setEnabled(true);
			flightAscending = false;
			flightDescending = false;
			if(savedFlightSpeed < 0)
				savedFlightSpeed = hax.flightHack.horizontalSpeed.getValue();
			if(savedFlightVSpeed < 0)
				savedFlightVSpeed = hax.flightHack.verticalSpeed.getValue();
			hax.flightHack.horizontalSpeed
				.setValue(Math.min(hax.flightHack.horizontalSpeed.getMaximum(),
					flightTravelSpeed.getValue()));
			hax.flightHack.verticalSpeed
				.setValue(Math.min(hax.flightHack.verticalSpeed.getMaximum(),
					flightTravelVSpeed.getValue()));
			applyFlightOverrides();
			flightTemporarilyDisabled = false;
		}else if(hax.flightHack.isEnabled())
		{
			if(savedFlightSpeed >= 0)
				hax.flightHack.horizontalSpeed.setValue(savedFlightSpeed);
			if(savedFlightVSpeed >= 0)
				hax.flightHack.verticalSpeed.setValue(savedFlightVSpeed);
			clearFlightOverrides();
			lastDesiredFlightY = Double.NaN;
			flightAscending = false;
			flightDescending = false;
			hax.flightHack.setEnabled(false);
			flightTemporarilyDisabled = true;
		}
	}
	
	private void applyFlightOverrides()
	{
		var flight = WURST.getHax().flightHack;
		if(!smoothFlight.isChecked())
		{
			clearFlightOverrides();
			return;
		}
		
		flight.setAntiKickOverride(false);
		flight.setSlowSneakingOverride(false);
		flightOverridesApplied = true;
	}
	
	private void clearFlightOverrides()
	{
		if(!flightOverridesApplied)
			return;
		
		var flight = WURST.getHax().flightHack;
		flight.setAntiKickOverride(null);
		flight.setSlowSneakingOverride(null);
		flightOverridesApplied = false;
	}
	
	private boolean maybeRetargetToNearest(double currentDist)
	{
		if(!retargetNearest.isChecked() || currentTarget == null
			|| MC.player == null || MC.level == null)
			return false;
		
		long now = System.currentTimeMillis();
		if(now - lastRetargetMs < 2000L)
			return false;
		
		LootTarget best = findNearestEligibleTarget();
		if(best == null || best.equals(currentTarget))
			return false;
		
		double bestDist =
			MC.player.position().distanceTo(Vec3.atCenterOf(best.pos));
		double minGain = Math.max(64.0, currentDist * 0.2);
		if(bestDist + minGain >= currentDist)
			return false;
		
		lastRetargetMs = now;
		log("Switching to nearer target: " + best.describe());
		currentTarget = best;
		nearTargetStartMs = 0L;
		ticksWithoutProgress = 0;
		lastTravelDist = Double.NaN;
		stateStartMs = now;
		if(state == State.PATHING_TO_TARGET && !useWurstPathingForTarget)
			baritone.setGoal(best.pos);
		return true;
	}
	
	private LootTarget findNearestEligibleTarget()
	{
		if(MC.player == null || MC.level == null)
			return null;
		
		return targets.stream().filter(this::isTargetEligible)
			.min(Comparator
				.comparingDouble(t -> t.pos.distToCenterSqr(MC.player.getX(),
					MC.player.getY(), MC.player.getZ())))
			.orElse(null);
	}
	
	private void selectNextTarget()
	{
		LootTarget best = findNearestEligibleTarget();
		currentTarget = best;
		if(best != null)
			ChatUtils.message("LootRunner target: " + best.describe());
	}
	
	private boolean isCompleted(LootTarget target)
	{
		return completed.contains(new TargetKey(target));
	}
	
	private boolean isFailed(LootTarget target)
	{
		return failedThisRun.contains(new TargetKey(target));
	}
	
	private boolean isTargetEligible(LootTarget target)
	{
		if(target == null || MC.level == null)
			return false;
		if(isCompleted(target) || isFailed(target))
			return false;
		if(target.pos.getY() < minTargetY.getValueI())
			return false;
		
		if(onlyCurrentDimension.isChecked())
		{
			String curDim = MC.level.dimension().identifier().toString();
			if(!curDim.equalsIgnoreCase(target.dimension))
				return false;
		}
		
		return true;
	}
	
	private void finishRun()
	{
		ChatUtils.message("LootRunner complete.");
		baritone.cancelAll();
		setEnabled(false);
		
		if(quitOnComplete.isChecked() && MC.level != null)
			MC.level.disconnect(Component.literal("lootrunner complete"));
	}
	
	private void reloadTargets()
	{
		targets.clear();
		File exportDir = getSeedmapperExportDir();
		if(exportDir == null || !exportDir.exists())
		{
			ChatUtils.error("SeedMapper export folder not found.");
			return;
		}
		
		File exportFileResolved = resolveExportFile(exportDir);
		if(exportFileResolved == null || !exportFileResolved.exists())
		{
			ChatUtils.error("Export file not found.");
			return;
		}
		
		try(FileReader reader = new FileReader(exportFileResolved))
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
				int y = o.has("y") ? o.get("y").getAsInt() : 0;
				int z = o.get("z").getAsInt();
				String feature = o.has("feature")
					? o.get("feature").getAsString() : "feature";
				int number = o.has("number") ? o.get("number").getAsInt() : 0;
				String biome =
					o.has("biome") ? o.get("biome").getAsString() : "";
				String dimension = o.has("dimension")
					? o.get("dimension").getAsString() : "minecraft:overworld";
				
				targets.add(new LootTarget(new BlockPos(x, y, z), feature,
					number, biome, dimension));
			}
			
		}catch(Throwable t)
		{
			t.printStackTrace();
		}
		
		ChatUtils.message("LootRunner loaded " + targets.size()
			+ " targets from " + exportFileResolved.getName());
	}
	
	private File resolveExportFile(File exportDir)
	{
		String custom = exportFile.getValue();
		if((custom == null || custom.isBlank()))
			custom = loadServerExportPreference();
		if(custom != null && !custom.isBlank())
		{
			File f = new File(custom);
			if(!f.isAbsolute())
				f = new File(exportDir, custom);
			if(f.exists())
			{
				saveServerExportPreference(custom);
				return f;
			}
			return null;
		}
		
		File[] files = exportDir
			.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
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
		try
		{
			if(MC != null && MC.gameDirectory != null)
			{
				return new File(MC.gameDirectory, "seedmapper/exports");
			}
		}catch(Throwable ignored)
		{}
		
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		if(os.contains("win"))
		{
			String appdata = System.getenv("APPDATA");
			if(appdata != null && !appdata.isBlank())
				return new File(new File(appdata),
					".minecraft/seedmapper/exports");
		}
		
		String user = System.getProperty("user.home");
		return new File(new File(user, ".minecraft"), "seedmapper/exports");
	}
	
	private void saveServerExportPreference(String exportValue)
	{
		if(exportValue == null || exportValue.isBlank())
			return;
		
		try
		{
			Path dir = getServerDataDir();
			Files.createDirectories(dir);
			Files.writeString(getServerExportPreferencePath(), exportValue,
				StandardCharsets.UTF_8);
		}catch(IOException ignored)
		{}
	}
	
	private String loadServerExportPreference()
	{
		Path file = getServerExportPreferencePath();
		if(!Files.exists(file))
			return "";
		
		try
		{
			String value = Files.readString(file, StandardCharsets.UTF_8);
			return value == null ? "" : value.trim();
		}catch(IOException ignored)
		{}
		return "";
	}
	
	private void stopAndSavePosition()
	{
		if(MC.player == null || MC.level == null)
		{
			ChatUtils.error("Join a world before saving position.");
			return;
		}
		
		if(saveResumeState())
		{
			ChatUtils.message("LootRunner progress saved for this server.");
			if(isEnabled())
				setEnabled(false);
		}
	}
	
	private void loadSavedPosition()
	{
		if(!loadResumeState())
		{
			ChatUtils.error("No saved LootRunner position for this server.");
			return;
		}
		
		resumePending = true;
		ChatUtils.message("Loaded saved LootRunner position.");
		if(isEnabled())
			startResumePosition();
		else
			setEnabled(true);
	}
	
	private boolean saveResumeState()
	{
		if(MC.player == null || MC.level == null)
			return false;
		
		try
		{
			Path dir = getServerDataDir();
			Files.createDirectories(dir);
			JsonObject o = new JsonObject();
			o.addProperty("dimension",
				MC.level.dimension().identifier().toString());
			o.addProperty("x", Mth.floor(MC.player.getX()));
			o.addProperty("y", Mth.floor(MC.player.getY()));
			o.addProperty("z", Mth.floor(MC.player.getZ()));
			if(currentTarget != null)
			{
				o.addProperty("target_dimension", currentTarget.dimension);
				o.addProperty("target_x", currentTarget.pos.getX());
				o.addProperty("target_y", currentTarget.pos.getY());
				o.addProperty("target_z", currentTarget.pos.getZ());
			}
			o.addProperty("time", Instant.now().toString());
			Files.writeString(getResumeStatePath(), gson.toJson(o),
				StandardCharsets.UTF_8);
			return true;
		}catch(IOException e)
		{
			e.printStackTrace();
			return false;
		}
	}
	
	private boolean loadResumeState()
	{
		Path file = getResumeStatePath();
		if(!Files.exists(file))
			return false;
		
		try
		{
			JsonElement root = JsonParser
				.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if(!root.isJsonObject())
				return false;
			
			JsonObject o = root.getAsJsonObject();
			if(!o.has("x") || !o.has("y") || !o.has("z"))
				return false;
			
			resumePos = new BlockPos(o.get("x").getAsInt(),
				o.get("y").getAsInt(), o.get("z").getAsInt());
			resumeTargetKey = null;
			if(o.has("target_dimension") && o.has("target_x")
				&& o.has("target_y") && o.has("target_z"))
				resumeTargetKey = new TargetKey(
					o.get("target_dimension").getAsString(),
					o.get("target_x").getAsInt(), o.get("target_y").getAsInt(),
					o.get("target_z").getAsInt());
			return true;
		}catch(Throwable t)
		{
			return false;
		}
	}
	
	private Path getServerDataDir()
	{
		return WURST.getWurstFolder().resolve("lootrunner").resolve("servers")
			.resolve(getServerScopeKey());
	}
	
	private Path getCompletedFilePath()
	{
		return getServerDataDir().resolve("completed.json");
	}
	
	private Path getLogFilePath()
	{
		return getServerDataDir().resolve("lootrunner_log.jsonl");
	}
	
	private Path getResumeStatePath()
	{
		return getServerDataDir().resolve("resume_state.json");
	}
	
	private Path getServerExportPreferencePath()
	{
		return getServerDataDir().resolve("export_file.txt");
	}
	
	private String getServerScopeKey()
	{
		if(MC != null && MC.hasSingleplayerServer())
			return "singleplayer";
		
		String raw = "unknown";
		if(MC != null && MC.getCurrentServer() != null
			&& MC.getCurrentServer().ip != null
			&& !MC.getCurrentServer().ip.isBlank())
			raw = MC.getCurrentServer().ip.trim().toLowerCase(Locale.ROOT);
		
		return sanitizeServerScope(raw);
	}
	
	private String sanitizeServerScope(String raw)
	{
		String cleaned = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if(cleaned.isEmpty())
			return "unknown";
		
		cleaned = cleaned.replaceAll("[^a-z0-9._-]", "_");
		while(cleaned.contains("__"))
			cleaned = cleaned.replace("__", "_");
		if(cleaned.length() > 96)
			cleaned = cleaned.substring(0, 96);
		return cleaned.isBlank() ? "unknown" : cleaned;
	}
	
	private BlockPos findNearestChest(BlockPos center, int radius)
	{
		if(MC.level == null || center == null)
			return null;
		
		double bestDist = Double.MAX_VALUE;
		BlockPos best = null;
		
		for(BlockEntity be : ChunkUtils.getLoadedBlockEntities().toList())
		{
			if(!isLootContainer(be))
				continue;
			BlockPos pos = be.getBlockPos();
			if(pos == null)
				continue;
			if(pos.distManhattan(center) > radius * 3)
				continue;
			double dist = pos.distToCenterSqr(center.getX() + 0.5,
				center.getY() + 0.5, center.getZ() + 0.5);
			if(dist <= radius * radius && dist < bestDist)
			{
				bestDist = dist;
				best = pos.immutable();
			}
		}
		
		return best;
	}
	
	private BlockPos findExitSpot(BlockPos center, int radius)
	{
		if(MC.level == null || center == null)
			return null;
		
		int minClear = exitClearHeight.getValueI();
		double bestScore = -1;
		BlockPos best = null;
		BlockPos bestAny = null;
		double bestAnyScore = -1;
		
		for(int dx = -radius; dx <= radius; dx++)
		{
			for(int dz = -radius; dz <= radius; dz++)
			{
				int distSq = dx * dx + dz * dz;
				if(distSq > radius * radius)
					continue;
				
				BlockPos pos = center.offset(dx, 0, dz);
				if(!isStandable(pos))
					continue;
				
				int airAbove = countAirAbove(pos, Math.max(6, minClear));
				double score = airAbove * 1000.0 + distSq;
				if(score > bestAnyScore)
				{
					bestAnyScore = score;
					bestAny = pos.immutable();
				}
				if(airAbove < minClear)
					continue;
				if(score > bestScore)
				{
					bestScore = score;
					best = pos.immutable();
				}
			}
		}
		
		return best != null ? best : bestAny;
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
	
	private int countAirAbove(BlockPos pos, int max)
	{
		if(MC.level == null)
			return 0;
		int count = 0;
		for(int i = 1; i <= max; i++)
		{
			BlockPos check = pos.above(i);
			if(!MC.level.getBlockState(check).getCollisionShape(MC.level, check)
				.isEmpty())
				break;
			count++;
		}
		return count;
	}
	
	private static double approachValue(double current, double target,
		double maxStep)
	{
		if(maxStep <= 0)
			return target;
		double delta = target - current;
		if(Math.abs(delta) <= maxStep)
			return target;
		return current + Math.copySign(maxStep, delta);
	}
	
	private boolean shouldAscend(double yDiff, double deadzone)
	{
		double start = deadzone + 0.4;
		double stop = deadzone * 0.5;
		if(flightAscending)
		{
			if(yDiff < stop)
				flightAscending = false;
		}else if(yDiff > start)
		{
			flightAscending = true;
		}
		
		if(flightAscending)
			flightDescending = false;
		
		return flightAscending;
	}
	
	private boolean shouldDescend(double yDiff, double deadzone, boolean far)
	{
		double start = deadzone + (far ? 3.0 : 0.6);
		double stop = deadzone * 0.5;
		if(far && yDiff > -(deadzone + 1.5))
		{
			flightDescending = false;
			return false;
		}
		if(flightDescending)
		{
			if(yDiff > -stop)
				flightDescending = false;
		}else if(yDiff < -start)
		{
			flightDescending = true;
		}
		
		if(flightDescending)
			flightAscending = false;
		
		return flightDescending;
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
	
	private void updateFlightStuck(boolean climbing, boolean blockedAbove)
	{
		if(MC.player == null)
			return;
		
		double y = MC.player.getY();
		if(!Double.isNaN(lastFlightY))
		{
			double dy = Math.abs(y - lastFlightY);
			if(climbing && blockedAbove && dy < 0.02)
				flightStuckUpTicks++;
			else if(dy > 0.05)
				flightStuckUpTicks = 0;
		}
		lastFlightY = y;
		
		if(flightStuckUpTicks > 60)
		{
			flightStuckUpTicks = 0;
			flightEvadeTicks = 40;
			flightEvadeDir = (MC.player.tickCount % 2 == 0 ? 1 : -1)
				* (flightEvadeDir == 0 ? 1 : -flightEvadeDir);
			forceDescendTicks = 12;
		}
	}
	
	private BlockPos offsetLateral(BlockPos center, int distance, boolean left)
	{
		if(MC.player == null)
			return center;
		Vec3 look = MC.player.getLookAngle();
		Vec3 side = new Vec3(-look.z, 0, look.x).normalize()
			.scale(left ? distance : -distance);
		return center.offset((int)Math.round(side.x), 0,
			(int)Math.round(side.z));
	}
	
	private boolean isLootContainer(BlockEntity be)
	{
		return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
			|| be instanceof ShulkerBoxBlockEntity;
	}
	
	private boolean isChestScreenOpen()
	{
		if(!(MC.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen))
			return false;
		return screen.getMenu() instanceof ChestMenu
			|| screen.getMenu() instanceof ShulkerBoxMenu;
	}
	
	private void breakBlock(BlockPos pos)
	{
		if(MC.level == null || MC.gameMode == null || MC.player == null)
			return;
		
		var state = MC.level.getBlockState(pos);
		if(state.isAir() || !state.getFluidState().isEmpty()
			|| state.getDestroySpeed(MC.level, pos) < 0)
			return;
		
		WURST.getHax().autoToolHack.equipIfEnabled(pos);
		
		Direction side = Direction.DOWN;
		if(!MC.gameMode.isDestroying())
			MC.gameMode.startDestroyBlock(pos, side);
		
		if(MC.gameMode.continueDestroyBlock(pos, side))
		{
			MC.level.addBreakingBlockEffect(pos, side);
			MC.player.swing(InteractionHand.MAIN_HAND);
			MC.options.keyAttack.setDown(true);
		}
	}
	
	private void tryBreakFlightObstacle(boolean blockedAhead,
		boolean blockedAbove)
	{
		if(!breakFlightObstacles.isChecked() || MC.player == null
			|| MC.level == null)
			return;
		
		long now = System.currentTimeMillis();
		if(now - lastFlightBreakMs < 150L)
			return;
		
		BlockPos target = null;
		if(blockedAbove)
			target = getBreakAbovePos();
		if(target == null && blockedAhead)
			target = getBreakAheadPos(1.0);
		
		if(target == null || !canBreakBlock(target))
			return;
		
		lastFlightBreakMs = now;
		breakBlock(target);
	}
	
	private boolean canBreakBlock(BlockPos pos)
	{
		if(MC.level == null)
			return false;
		var state = MC.level.getBlockState(pos);
		if(state.isAir() || !state.getFluidState().isEmpty())
			return false;
		return state.getDestroySpeed(MC.level, pos) >= 0;
	}
	
	private BlockPos getBreakAbovePos()
	{
		if(MC.player == null)
			return null;
		
		BlockPos base = BlockPos.containing(MC.player.position());
		BlockPos aboveTwo = base.above(2);
		if(canBreakBlock(aboveTwo))
			return aboveTwo;
		
		BlockPos aboveOne = base.above(1);
		if(canBreakBlock(aboveOne))
			return aboveOne;
		
		return aboveTwo;
	}
	
	private BlockPos getBreakAheadPos(double distance)
	{
		if(MC.player == null)
			return null;
		
		Vec3 look = MC.player.getLookAngle();
		Vec3 ahead = MC.player.position().add(look.normalize().scale(distance));
		return BlockPos.containing(ahead);
	}
	
	private void closeChestScreen()
	{
		if(MC.player != null)
			MC.player.closeContainer();
	}
	
	private void prepareLootQueue()
	{
		lootQueue.clear();
		chestBefore = readChestItems();
		if(chestBefore.isEmpty())
			return;
		
		var screen = MC.screen;
		if(!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> sc))
			return;
		
		int totalSlots = sc.getMenu().slots.size();
		int chestSlots = Math.max(0, totalSlots - 36);
		for(int i = 0; i < chestSlots; i++)
		{
			Slot slot = sc.getMenu().slots.get(i);
			if(slot.getItem().isEmpty())
				continue;
			if(shouldLoot(slot.getItem()))
				lootQueue.add(i);
		}
	}
	
	private List<ItemStack> readChestItems()
	{
		List<ItemStack> out = new ArrayList<>();
		var screen = MC.screen;
		if(!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> sc))
			return out;
		
		int totalSlots = sc.getMenu().slots.size();
		int chestSlots = Math.max(0, totalSlots - 36);
		for(int i = 0; i < chestSlots; i++)
		{
			ItemStack stack = sc.getMenu().slots.get(i).getItem();
			if(stack != null && !stack.isEmpty())
				out.add(stack.copy());
		}
		return out;
	}
	
	private boolean shouldLoot(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		
		LootMode mode = lootMode.getSelected();
		if(mode == LootMode.ALL)
			return true;
		
		Item item = stack.getItem();
		String id = ItemUtils.getStackId(stack);
		if(id == null)
			id = BuiltInRegistries.ITEM.getKey(item).toString();
		final String finalId = id;
		
		switch(mode)
		{
			case LIST:
			if(lootList.contains(item))
				return true;
			for(String s : lootList.getItemNames())
				if(s != null && finalId.equalsIgnoreCase(s.trim()))
					return true;
			return false;
			case ITEM_ID:
			String target = lootItemId.getValue();
			return target != null && !target.isBlank()
				&& id.equalsIgnoreCase(target.trim());
			case QUERY:
			return matchesQuery(item, stack, lootQuery.getValue());
			default:
			return false;
		}
	}
	
	private boolean matchesQuery(Item item, ItemStack stack, String raw)
	{
		if(raw == null)
			return false;
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		if(normalized.isEmpty())
			return false;
		
		String stackId = ItemUtils.getStackId(stack);
		String fullId = stackId != null ? stackId
			: BuiltInRegistries.ITEM.getKey(item).toString();
		String localId = fullId.contains(":")
			? fullId.substring(fullId.indexOf(":") + 1) : fullId;
		String display = item.getName().getString();
		String stackDisplay = stack.getHoverName().getString();
		
		String[] terms = normalized.split(",");
		for(String t : terms)
		{
			String term = t.trim();
			if(term.isEmpty())
				continue;
			if(contains(fullId, term) || contains(localId, term)
				|| contains(display, term) || contains(stackDisplay, term))
				return true;
		}
		
		return false;
	}
	
	private boolean contains(String haystack, String needle)
	{
		return haystack != null
			&& haystack.toLowerCase(Locale.ROOT).contains(needle);
	}
	
	private Map<String, Integer> countItems(List<ItemStack> stacks)
	{
		Map<String, Integer> out = new HashMap<>();
		if(stacks == null)
			return out;
		for(ItemStack st : stacks)
		{
			if(st == null || st.isEmpty())
				continue;
			String id = ItemUtils.getStackId(st);
			if(id == null)
				id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
			out.put(id, out.getOrDefault(id, 0) + st.getCount());
		}
		return out;
	}
	
	private Map<String, Integer> diffCounts(Map<String, Integer> before,
		Map<String, Integer> after)
	{
		Map<String, Integer> out = new HashMap<>();
		for(Map.Entry<String, Integer> e : before.entrySet())
		{
			int b = e.getValue();
			int a = after.getOrDefault(e.getKey(), 0);
			int diff = Math.max(0, b - a);
			if(diff > 0)
				out.put(e.getKey(), diff);
		}
		return out;
	}
	
	private void markTargetComplete(String status, Map<String, Integer> before,
		Map<String, Integer> taken, Map<String, Integer> after)
	{
		if(currentTarget == null)
			return;
		
		TargetKey key = new TargetKey(currentTarget);
		completed.add(key);
		completedDetails.put(key, new ResultEntry(Instant.now().toString(),
			status, currentTarget, before, taken, after));
		if(persistCompletion.isChecked())
			saveCompleted();
		
		appendLog(currentTarget, status, before, taken, after);
		log("Target " + currentTarget.describe() + " -> " + status);
		processedThisRun++;
	}
	
	private void failCurrentTarget(String status)
	{
		if(currentTarget == null)
			return;
		
		TargetKey key = new TargetKey(currentTarget);
		failedThisRun.add(key);
		completedDetails.put(key, new ResultEntry(Instant.now().toString(),
			status, currentTarget, null, null, null));
		appendLog(currentTarget, status, null, null, null);
		log("Target failed: " + currentTarget.describe() + " (" + status + ")");
		processedThisRun++;
		moveToNextTarget();
	}
	
	private void log(String msg)
	{
		if(debugLogs.isChecked())
			ChatUtils.message("[LootRunner] " + msg);
	}
	
	private void clearSneak()
	{
		if(MC == null || MC.options == null)
			return;
		MC.options.keyShift.setDown(false);
		net.wurstclient.mixinterface.IKeyBinding.get(MC.options.keyShift)
			.resetPressedState();
	}
	
	private void debugTick()
	{
		long now = System.currentTimeMillis();
		if(now - lastDebugMs < 5000L)
			return;
		lastDebugMs = now;
		
		String target =
			currentTarget == null ? "none" : currentTarget.describe();
		String chest = currentChestPos == null ? "none" : currentChestPos.getX()
			+ "," + currentChestPos.getY() + "," + currentChestPos.getZ();
		String exit = exitPos == null ? "none"
			: exitPos.getX() + "," + exitPos.getY() + "," + exitPos.getZ();
		String distInfo = "";
		if(currentTarget != null)
		{
			double d = MC.player.position()
				.distanceTo(Vec3.atCenterOf(currentTarget.pos));
			distInfo = " dist=" + String.format(Locale.ROOT, "%.1f", d);
		}
		log("State=" + state + " target=" + target + " chest=" + chest
			+ " exit=" + exit + " exitAttempts=" + exitAttempts
			+ " flightChestAttempts=" + flightChestAttempts + distInfo);
	}
	
	private void tryRepathExit(String reason)
	{
		long now = System.currentTimeMillis();
		if(now - lastExitRepathMs < 1500L)
			return;
		lastExitRepathMs = now;
		
		log(reason);
		baritone.cancelAll();
		exitAttempts = Math.min(exitAttempts + 1, 3);
		BlockPos center = BlockPos.containing(MC.player.position());
		BlockPos attemptCenter = switch(exitAttempts)
		{
			case 1 -> center;
			case 2 -> offsetLateral(center, exitSearchRadius.getValueI(), true);
			default -> offsetLateral(center, exitSearchRadius.getValueI(),
				false);
		};
		BlockPos found =
			findExitSpot(attemptCenter, exitSearchRadius.getValueI());
		if(found != null)
		{
			exitPos = found;
			baritone.setGoal(found);
			lastExitGoalMs = now;
		}
	}
	
	private String buildCrosshairInfo()
	{
		String mode = getStateLabel();
		double speed = MC.player.getDeltaMovement().length() * 20.0;
		
		if(currentTarget == null)
		{
			return String.format(Locale.ROOT, "LootRunner | %.1fb/s | %s",
				speed, mode);
		}
		
		int remaining = getRemainingEligibleTargetCount();
		int total = processedThisRun + remaining;
		int index = Math.min(total, processedThisRun + 1);
		double dist =
			MC.player.position().distanceTo(Vec3.atCenterOf(currentTarget.pos));
		
		return String.format(Locale.ROOT,
			"LootRunner %d/%d | %.1fm | %.1fb/s | %s", index,
			Math.max(total, 1), dist, speed, mode);
	}
	
	private int getRemainingEligibleTargetCount()
	{
		if(MC.level == null)
			return 0;
		
		return (int)targets.stream().filter(this::isTargetEligible).count();
	}
	
	private String getStateLabel()
	{
		return switch(state)
		{
			case RESUMING_POSITION -> "Resuming";
			case EXITING -> "Exiting";
			case FLIGHTING_TO_CHEST -> "Flying";
			case PATHING_TO_TARGET -> useWurstPathingForTarget ? "Flying"
				: "Pathing";
			case PATHING_TO_CHEST -> "Pathing";
			case SEARCHING_CHEST, OPENING_CHEST, LOOTING -> "Looking";
			case EMERGENCY_ASCEND -> "Emergency";
			case COOLDOWN, IDLE -> "Idle";
		};
	}
	
	private BlockPos getRenderGoalPos()
	{
		return switch(state)
		{
			case RESUMING_POSITION -> resumePos;
			case EXITING -> exitPos;
			case PATHING_TO_CHEST, FLIGHTING_TO_CHEST, OPENING_CHEST, LOOTING -> currentChestPos != null
				? currentChestPos
				: currentTarget != null ? currentTarget.pos : null;
			default -> currentTarget != null ? currentTarget.pos : null;
		};
	}
	
	private void appendLog(LootTarget target, String status,
		Map<String, Integer> before, Map<String, Integer> taken,
		Map<String, Integer> after)
	{
		try
		{
			Path dir = getServerDataDir();
			Files.createDirectories(dir);
			Path log = getLogFilePath();
			
			JsonObject obj = new JsonObject();
			obj.addProperty("time", Instant.now().toString());
			obj.addProperty("status", status);
			obj.addProperty("dimension", target.dimension);
			obj.addProperty("x", target.pos.getX());
			obj.addProperty("y", target.pos.getY());
			obj.addProperty("z", target.pos.getZ());
			obj.addProperty("feature", target.feature);
			obj.addProperty("number", target.number);
			obj.addProperty("biome", target.biome);
			if(before != null)
				obj.add("before", mapToJson(before));
			if(taken != null)
				obj.add("taken", mapToJson(taken));
			if(after != null)
				obj.add("after", mapToJson(after));
			
			String line = gson.toJson(obj) + System.lineSeparator();
			Files.writeString(log, line, StandardCharsets.UTF_8,
				java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.APPEND);
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private static JsonObject mapToJson(Map<String, Integer> map)
	{
		JsonObject o = new JsonObject();
		for(Map.Entry<String, Integer> e : map.entrySet())
			o.addProperty(e.getKey(), e.getValue());
		return o;
	}
	
	private void loadCompleted()
	{
		completed.clear();
		completedDetails.clear();
		if(!persistCompletion.isChecked())
			return;
		
		Path file = getCompletedFilePath();
		if(!Files.exists(file))
			return;
		
		try
		{
			String json = Files.readString(file, StandardCharsets.UTF_8);
			JsonElement root = JsonParser.parseString(json);
			if(!root.isJsonArray())
				return;
			for(JsonElement e : root.getAsJsonArray())
			{
				if(!e.isJsonObject())
					continue;
				JsonObject o = e.getAsJsonObject();
				int x = o.get("x").getAsInt();
				int y = o.get("y").getAsInt();
				int z = o.get("z").getAsInt();
				String dim = o.get("dimension").getAsString();
				TargetKey key = new TargetKey(dim, x, y, z);
				completed.add(key);
				
				if(o.has("status"))
				{
					ResultEntry entry = ResultEntry.fromJson(o);
					if(entry != null)
						completedDetails.put(key, entry);
				}
			}
		}catch(Throwable ignored)
		{}
	}
	
	private void saveCompleted()
	{
		try
		{
			Path dir = getServerDataDir();
			Files.createDirectories(dir);
			Path file = getCompletedFilePath();
			JsonArray arr = new JsonArray();
			for(TargetKey k : completed)
			{
				ResultEntry entry = completedDetails.get(k);
				JsonObject o =
					entry != null ? entry.toJson() : new JsonObject();
				if(!o.has("dimension"))
					o.addProperty("dimension", k.dimension);
				if(!o.has("x"))
					o.addProperty("x", k.x);
				if(!o.has("y"))
					o.addProperty("y", k.y);
				if(!o.has("z"))
					o.addProperty("z", k.z);
				arr.add(o);
			}
			Files.writeString(file, gson.toJson(arr), StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private void applyAuxHacksOnEnable()
	{
		var hax = WURST.getHax();
		
		chestEspWasEnabled = hax.chestEspHack.isEnabled();
		handNoClipWasEnabled = hax.handNoClipHack.isEnabled();
		flightWasEnabled = hax.flightHack.isEnabled();
		antisocialWasEnabled = hax.antisocialHack.isEnabled();
		autoEatWasEnabled = hax.autoEatHack.isEnabled();
		autoLeaveWasEnabled = hax.autoLeaveHack.isEnabled();
		quickShulkerWasEnabled = hax.quickShulkerHack.isEnabled();
		
		if(useChestEsp.isChecked() && !chestEspWasEnabled)
			hax.chestEspHack.setEnabled(true);
		if(useHandNoClip.isChecked() && !handNoClipWasEnabled)
			hax.handNoClipHack.setEnabled(true);
		if(useFlight.isChecked() && !flightWasEnabled)
			hax.flightHack.setEnabled(true);
		if(useAntisocial.isChecked() && !antisocialWasEnabled)
			hax.antisocialHack.setEnabled(true);
		if(useAutoEat.isChecked() && !autoEatWasEnabled)
			hax.autoEatHack.setEnabled(true);
		if(useAutoLeave.isChecked() && !autoLeaveWasEnabled)
			hax.autoLeaveHack.setEnabled(true);
		if(useQuickShulkerOnFull.isChecked() && !quickShulkerWasEnabled)
			hax.quickShulkerHack.setEnabled(true);
	}
	
	private void restoreAuxHacksOnDisable()
	{
		var hax = WURST.getHax();
		
		if(useChestEsp.isChecked() && !chestEspWasEnabled)
			hax.chestEspHack.setEnabled(false);
		if(useHandNoClip.isChecked() && !handNoClipWasEnabled)
			hax.handNoClipHack.setEnabled(false);
		if(useFlight.isChecked())
		{
			if(!flightWasEnabled)
				hax.flightHack.setEnabled(false);
			else if(flightTemporarilyDisabled)
				hax.flightHack.setEnabled(true);
			if(savedFlightSpeed >= 0)
				hax.flightHack.horizontalSpeed.setValue(savedFlightSpeed);
			if(savedFlightVSpeed >= 0)
				hax.flightHack.verticalSpeed.setValue(savedFlightVSpeed);
		}
		if(useAntisocial.isChecked() && !antisocialWasEnabled)
			hax.antisocialHack.setEnabled(false);
		if(useAutoEat.isChecked() && !autoEatWasEnabled)
			hax.autoEatHack.setEnabled(false);
		if(useAutoLeave.isChecked() && !autoLeaveWasEnabled)
			hax.autoLeaveHack.setEnabled(false);
		if(useQuickShulkerOnFull.isChecked() && !quickShulkerWasEnabled)
			hax.quickShulkerHack.setEnabled(false);
	}
	
	private record LootTarget(BlockPos pos, String feature, int number,
		String biome, String dimension)
	{
		String describe()
		{
			return feature + " #" + number + " @ " + pos.getX() + ", "
				+ pos.getY() + ", " + pos.getZ();
		}
	}
	
	private record TargetKey(String dimension, int x, int y, int z)
	{
		TargetKey(LootTarget target)
		{
			this(target.dimension, target.pos.getX(), target.pos.getY(),
				target.pos.getZ());
		}
	}
	
	private record ResultEntry(String time, String status, LootTarget target,
		Map<String, Integer> before, Map<String, Integer> taken,
		Map<String, Integer> after)
	{
		JsonObject toJson()
		{
			JsonObject o = new JsonObject();
			o.addProperty("time", time);
			o.addProperty("status", status);
			o.addProperty("dimension", target.dimension);
			o.addProperty("x", target.pos.getX());
			o.addProperty("y", target.pos.getY());
			o.addProperty("z", target.pos.getZ());
			o.addProperty("feature", target.feature);
			o.addProperty("number", target.number);
			o.addProperty("biome", target.biome);
			if(before != null)
				o.add("before", mapToJson(before));
			if(taken != null)
				o.add("taken", mapToJson(taken));
			if(after != null)
				o.add("after", mapToJson(after));
			return o;
		}
		
		static ResultEntry fromJson(JsonObject o)
		{
			try
			{
				String dim = o.get("dimension").getAsString();
				int x = o.get("x").getAsInt();
				int y = o.get("y").getAsInt();
				int z = o.get("z").getAsInt();
				String feature = o.has("feature")
					? o.get("feature").getAsString() : "feature";
				int number = o.has("number") ? o.get("number").getAsInt() : 0;
				String biome =
					o.has("biome") ? o.get("biome").getAsString() : "";
				String time = o.has("time") ? o.get("time").getAsString()
					: Instant.now().toString();
				String status =
					o.has("status") ? o.get("status").getAsString() : "done";
				
				LootTarget target = new LootTarget(new BlockPos(x, y, z),
					feature, number, biome, dim);
				return new ResultEntry(time, status, target, null, null, null);
			}catch(Throwable t)
			{
				return null;
			}
		}
	}
	
	private static final class BaritoneBridge
	{
		private Object baritone;
		private Object settings;
		private java.lang.reflect.Method getCustomGoalProcess;
		private java.lang.reflect.Method setGoalAndPath;
		private java.lang.reflect.Method getPathingBehavior;
		private java.lang.reflect.Method cancelEverything;
		private boolean initialized;
		private final Map<String, Object> savedSettings = new HashMap<>();
		
		boolean isAvailable()
		{
			return init();
		}
		
		void setGoal(BlockPos pos)
		{
			if(!init() || pos == null)
				return;
			try
			{
				Class<?> goalBlock =
					Class.forName("baritone.api.pathing.goals.GoalBlock");
				Object goal =
					goalBlock.getConstructor(int.class, int.class, int.class)
						.newInstance(pos.getX(), pos.getY(), pos.getZ());
				Object customGoal = getCustomGoalProcess.invoke(baritone);
				setGoalAndPath.invoke(customGoal, goal);
			}catch(Throwable t)
			{
				t.printStackTrace();
			}
		}
		
		void cancelAll()
		{
			if(!init())
				return;
			try
			{
				Object behavior = getPathingBehavior.invoke(baritone);
				cancelEverything.invoke(behavior);
			}catch(Throwable ignored)
			{}
		}
		
		void applyFlightHints(boolean enable)
		{
			if(!init())
				return;
			if(enable)
			{
				String[] candidates = {"allowBreak", "allowPlace",
					"allowSprint", "allowParkour", "allowParkourPlace",
					"allowParkourAscend", "allowDiagonalAscend",
					"allowDiagonalDescend", "allowJumpAt256", "freeLook",
					"blockFreeLook", "elytraAutoJump", "elytraFreeLook",
					"elytraSmoothLook", "elytraAllowEmergencyLand"};
				for(String name : candidates)
					setSettingBoolean(name, true);
				setSettingBoolean("cutoffAtLoadBoundary", false);
				setSettingBoolean("pathThroughCachedOnly", false);
			}else
			{
				restoreSettings();
			}
		}
		
		private void restoreSettings()
		{
			if(settings == null || savedSettings.isEmpty())
			{
				savedSettings.clear();
				return;
			}
			for(Map.Entry<String, Object> entry : savedSettings.entrySet())
			{
				setSettingValue(entry.getKey(), entry.getValue());
			}
			savedSettings.clear();
		}
		
		private boolean setSettingBoolean(String name, boolean value)
		{
			Object current = getSettingValue(name);
			if(!(current instanceof Boolean))
				return false;
			if(!savedSettings.containsKey(name))
				savedSettings.put(name, current);
			return setSettingValue(name, value);
		}
		
		private Object getSettingValue(String name)
		{
			try
			{
				if(settings == null)
					return null;
				Object setting = getSettingByName(name);
				if(setting == null)
					return null;
				
				try
				{
					var getter = setting.getClass().getMethod("get");
					return getter.invoke(setting);
				}catch(Throwable ignored)
				{}
				
				try
				{
					var getter = setting.getClass().getMethod("getValue");
					return getter.invoke(setting);
				}catch(Throwable ignored)
				{}
				
				try
				{
					var valueField = setting.getClass().getField("value");
					return valueField.get(setting);
				}catch(Throwable ignored)
				{}
			}catch(Throwable ignored)
			{}
			return null;
		}
		
		private boolean setSettingValue(String name, Object value)
		{
			try
			{
				if(settings == null)
					return false;
				Object setting = getSettingByName(name);
				if(setting == null)
					return false;
				
				try
				{
					var setter =
						setting.getClass().getMethod("set", Object.class);
					setter.invoke(setting, value);
					return true;
				}catch(Throwable ignored)
				{}
				
				try
				{
					var setter =
						setting.getClass().getMethod("setValue", Object.class);
					setter.invoke(setting, value);
					return true;
				}catch(Throwable ignored)
				{}
				
				try
				{
					var valueField = setting.getClass().getField("value");
					valueField.set(setting, value);
					return true;
				}catch(Throwable ignored)
				{}
				
			}catch(Throwable ignored)
			{}
			return false;
		}
		
		private Object getSettingByName(String name)
		{
			try
			{
				if(settings == null)
					return null;
				
				try
				{
					var mapField = settings.getClass().getField("byLowerName");
					Object mapObj = mapField.get(settings);
					if(mapObj instanceof Map<?, ?> map)
					{
						Object found = map.get(name.toLowerCase(Locale.ROOT));
						if(found != null)
							return found;
					}
				}catch(Throwable ignored)
				{}
				
				var field = settings.getClass().getField(name);
				return field.get(settings);
			}catch(Throwable ignored)
			{}
			return null;
		}
		
		private boolean init()
		{
			if(initialized)
				return baritone != null;
			
			initialized = true;
			try
			{
				Class<?> api = Class.forName("baritone.api.BaritoneAPI");
				Object provider = api.getMethod("getProvider").invoke(null);
				baritone = provider.getClass().getMethod("getPrimaryBaritone")
					.invoke(provider);
				try
				{
					settings = api.getMethod("getSettings").invoke(null);
				}catch(Throwable ignored)
				{
					settings = null;
				}
				
				getCustomGoalProcess =
					baritone.getClass().getMethod("getCustomGoalProcess");
				Object customGoal = getCustomGoalProcess.invoke(baritone);
				setGoalAndPath =
					customGoal.getClass().getMethod("setGoalAndPath",
						Class.forName("baritone.api.pathing.goals.Goal"));
				
				getPathingBehavior =
					baritone.getClass().getMethod("getPathingBehavior");
				Object behavior = getPathingBehavior.invoke(baritone);
				cancelEverything =
					behavior.getClass().getMethod("cancelEverything");
				
				return true;
			}catch(Throwable t)
			{
				baritone = null;
				return false;
			}
		}
	}
}
