/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.lwjgl.glfw.GLFW;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.MouseButtonPressListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.vaultroll.VaultRollMode;
import net.wurstclient.hacks.vaultroll.VaultRollObservation;
import net.wurstclient.hacks.vaultroll.VaultRollOpening;
import net.wurstclient.hacks.vaultroll.VaultRollPredictor;
import net.wurstclient.hacks.vaultroll.VaultRollStack;
import net.wurstclient.hacks.vaultroll.VaultRollSynchronizer;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.ItemUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RollStateStore;
import net.wurstclient.util.chunk.ChunkUtils;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.minecraft.world.phys.Vec3;

@SearchTags({"vault roll", "vaultroll", "trial vault", "vault loot",
	"ominous vault"})
public final class VaultRollHack extends Hack implements UpdateListener,
	MouseButtonPressListener, PacketInputListener, RenderListener
{
	private static final int MAX_OBSERVATIONS = 16;
	private static final int MAX_AUTO_ITEM_AGE = 400;
	private static final int MAX_KNOWN_ITEM_TICKS = 800;
	private static final int AUTO_CAPTURE_GRACE_TICKS = 60;
	private static final int MAX_UPCOMING_LINES = 60;
	private static final int AUTO_CAPTURE_RADIUS = 6;
	private final CheckboxSetting chatWarnings =
		new CheckboxSetting("Chat warnings",
			"description.wurst.setting.vaultroll.chat_warnings", true);
	private final CheckboxSetting autoObserve =
		new CheckboxSetting("Auto observe",
			"description.wurst.setting.vaultroll.auto_observe", true);
	private final CheckboxSetting middleClickInfo =
		new CheckboxSetting("Middle-click info",
			"description.wurst.setting.vaultroll.middle_click_info", false);
	private final CheckboxSetting predictionEsp =
		new CheckboxSetting("Prediction ESP",
			"description.wurst.setting.vaultroll.prediction_esp", false);
	private final TextFieldSetting worldSeed = new TextFieldSetting(
		"World seed", "description.wurst.setting.vaultroll.world_seed", "",
		value -> value.isBlank()
			|| VaultRollPredictor.tryParseSeed(value.trim()) != null);
	private final SliderSetting maxDistance = new SliderSetting("Max distance",
		"description.wurst.setting.vaultroll.max_distance", 160, 0, 256, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting overlayScale = new SliderSetting(
		"Overlay scale", "description.wurst.setting.vaultroll.overlay_scale",
		0.5, 0.5, 2.0, 0.05, ValueDisplay.DECIMAL);
	private final SliderSetting warningDistance =
		new SliderSetting("Warning distance",
			"description.wurst.setting.vaultroll.warning_distance", 5, 1, 20, 1,
			ValueDisplay.INTEGER);
	private final SliderSetting searchHorizon = new SliderSetting(
		"Search horizon", "description.wurst.setting.vaultroll.search_horizon",
		1000, 100, 1_000_000, 100, ValueDisplay.INTEGER);
	private final SliderSetting synchronizationHorizon =
		new SliderSetting("Synchronization horizon",
			"description.wurst.setting.vaultroll.synchronization_horizon",
			100_000, 1_000, 1_000_000, 1_000, ValueDisplay.INTEGER);
	
	private final ExecutorService executor =
		Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "Wurst-VaultRoll");
			thread.setDaemon(true);
			return thread;
		});
	private final EnumMap<VaultRollMode, ModeState> states =
		new EnumMap<>(VaultRollMode.class);
	private final EnumMap<VaultRollMode, Future<?>> synchronizationTasks =
		new EnumMap<>(VaultRollMode.class);
	private final EnumMap<VaultRollMode, Future<?>> targetTasks =
		new EnumMap<>(VaultRollMode.class);
	private final EnumMap<VaultRollMode, AtomicLong> synchronizationGenerations =
		new EnumMap<>(VaultRollMode.class);
	private final EnumMap<VaultRollMode, AtomicLong> targetGenerations =
		new EnumMap<>(VaultRollMode.class);
	private final EnumMap<VaultRollMode, Future<?>> heavyCoreTasks =
		new EnumMap<>(VaultRollMode.class);
	private final EnumMap<VaultRollMode, AtomicLong> heavyCoreGenerations =
		new EnumMap<>(VaultRollMode.class);
	private final Map<VaultKey, VaultState> previousVaultStates =
		new HashMap<>();
	private final Map<VaultKey, AutoOpening> autoOpenings = new HashMap<>();
	private final Map<Integer, KnownItem> knownItems = new LinkedHashMap<>();
	
	private Object lastLevel;
	private Long lastEffectiveSeed;
	private Long manualSeed;
	private String lastWorldSeedSettingValue = "";
	private String persistenceServerKey;
	private boolean persistenceLoaded;
	private VaultRollMode selectedMode = VaultRollMode.NORMAL;
	
	public VaultRollHack()
	{
		super("VaultRoll");
		setCategory(Category.OTHER);
		for(VaultRollMode mode : VaultRollMode.values())
		{
			states.put(mode, new ModeState());
			synchronizationGenerations.put(mode, new AtomicLong());
			targetGenerations.put(mode, new AtomicLong());
			heavyCoreGenerations.put(mode, new AtomicLong());
		}
		addSetting(chatWarnings);
		addSetting(autoObserve);
		addSetting(middleClickInfo);
		addSetting(predictionEsp);
		addSetting(worldSeed);
		addSetting(maxDistance);
		addSetting(overlayScale);
		addSetting(warningDistance);
		addSetting(searchHorizon);
		addSetting(synchronizationHorizon);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(MouseButtonPressListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(RenderListener.class, this);
		message("Enabled. Type .vaultroll help for setup instructions.");
	}
	
	@Override
	protected void onDisable()
	{
		savePersistentState();
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(MouseButtonPressListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		clearAllState();
		clearTargets();
		manualSeed = null;
		lastLevel = null;
		lastEffectiveSeed = null;
		persistenceServerKey = null;
		persistenceLoaded = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || MC.player == null)
		{
			if(lastLevel != null || persistenceLoaded)
			{
				savePersistentState();
				clearAllState();
				clearTargets();
				manualSeed = null;
				lastLevel = null;
				lastEffectiveSeed = null;
				persistenceServerKey = null;
				persistenceLoaded = false;
			}
			return;
		}
		preparePersistentState();
		applyWorldSeedSettingChange();
		Long effectiveSeed = getEffectiveSeed();
		if(lastLevel != MC.level)
		{
			lastLevel = MC.level;
			lastEffectiveSeed = effectiveSeed;
		}
		if(lastEffectiveSeed != null && effectiveSeed != null
			&& !lastEffectiveSeed.equals(effectiveSeed))
		{
			clearAllState();
			lastEffectiveSeed = effectiveSeed;
		}
		if(autoObserve.isChecked())
		{
			rememberVisibleItems();
			observeVaultEjections();
		}
		if(predictionEsp.isChecked())
			queueHeavyCoreSearches();
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(!predictionEsp.isChecked() || MC.level == null || MC.player == null
			|| MC.font == null)
			return;
		
		ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof VaultBlockEntity)
			.map(be -> (VaultBlockEntity)be).forEach(blockEntity -> {
				BlockPosAndState vault = getVaultState(blockEntity);
				if(vault == null)
					return;
				Vec3 center = Vec3.atCenterOf(vault.pos());
				double maxRenderDistance = maxDistance.getValue();
				if(maxRenderDistance > 0
					&& MC.player.distanceToSqr(center.x, center.y,
						center.z) > maxRenderDistance * maxRenderDistance
					|| !NiceWurstModule.shouldRenderTarget(center))
					return;
				
				VaultRollMode mode = isOminous(vault.blockState)
					? VaultRollMode.OMINOUS : VaultRollMode.NORMAL;
				ModeState state = states.get(mode);
				if(state.sequenceSeed == null || state.nextOpening == null)
					return;
				
				VaultRollOpening opening = VaultRollPredictor.predictOpening(
					state.sequenceSeed, mode, state.nextOpening);
				List<String> lines = new ArrayList<>();
				int color =
					mode == VaultRollMode.OMINOUS ? 0xFFFF6688 : 0xFF55FFAA;
				lines.add(mode.displayName() + " Vault");
				lines.add("Next #" + (state.nextOpening + 1));
				for(VaultRollStack stack : opening.stacks())
					lines.add(formatPredictedStack(stack));
				if(!state.heavyCoreSearchComplete)
					lines.add("Heavy Core: searching...");
				else if(state.heavyCoreHit == null)
					lines.add("Heavy Core: not in next "
						+ state.heavyCoreSearchHorizon + " openings");
				else if(state.heavyCoreHit.offset() == 0)
					lines.add("Heavy Core: NEXT opening");
				else
					lines.add(
						"Heavy Core: +" + state.heavyCoreHit.offset() + " (#"
							+ (state.heavyCoreHit.absoluteOpening() + 1) + ")");
				drawWorldLabel(matrices, center.add(0, 0.35, 0), lines, color,
					overlayScale.getValueF());
			});
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!autoObserve.isChecked() || !(event
			.getPacket() instanceof ClientboundTakeItemEntityPacket packet))
			return;
		int entityId = packet.getItemId();
		int amount = packet.getAmount();
		MC.execute(() -> rememberPickedUpItem(entityId, amount));
	}
	
	@Override
	public void onMouseButtonPress(
		MouseButtonPressListener.MouseButtonPressEvent event)
	{
		if(!middleClickInfo.isChecked()
			|| event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE
			|| event.getAction() != GLFW.GLFW_PRESS
			|| !(MC.hitResult instanceof BlockHitResult hit))
			return;
		BlockState blockState =
			MC.level == null ? null : MC.level.getBlockState(hit.getBlockPos());
		if(blockState == null || !isVault(blockState))
			return;
		VaultRollMode mode = isOminous(blockState) ? VaultRollMode.OMINOUS
			: VaultRollMode.NORMAL;
		ModeState state = states.get(mode);
		message(mode.displayName() + " Vault prediction at "
			+ formatPosition(hit.getBlockPos()));
		if(state.nextOpening == null || state.sequenceSeed == null)
		{
			message("Sequence position is unknown.");
			printNextStep(mode);
			return;
		}
		VaultRollOpening opening = VaultRollPredictor
			.predictOpening(state.sequenceSeed, mode, state.nextOpening);
		message("Next opening #" + (state.nextOpening + 1) + ": "
			+ opening.describe());
	}
	
	@Override
	public String getRenderName()
	{
		ModeState state = states.get(selectedMode);
		if(state.targetHit != null)
			return getName() + " [" + selectedMode.id() + " +"
				+ state.targetHit.offset() + "]";
		return getName();
	}
	
	public void printSeed()
	{
		Long seed = getEffectiveSeed();
		String source = manualSeed != null ? " (manual)"
			: seed == null ? "" : " (auto-detected)";
		message("World seed: " + (seed == null ? "unknown" : seed) + source);
	}
	
	public void setMode(String input)
	{
		VaultRollMode mode = VaultRollMode.parse(input);
		if(mode == null)
		{
			message("Mode must be normal or ominous.");
			return;
		}
		selectedMode = mode;
		savePersistentState();
		message("Selected " + mode.displayName() + " sequence.");
		printNextStep(mode);
	}
	
	public void printStatus()
	{
		message("Seed: "
			+ (getEffectiveSeed() == null ? "unknown" : getEffectiveSeed()));
		message("Selected mode: " + selectedMode.id());
		message(
			"Counters: normal and ominous are independent, world-wide loot sequences.");
		for(VaultRollMode mode : VaultRollMode.values())
		{
			ModeState state = states.get(mode);
			message(mode.displayName() + " sequence: " + mode.sequenceId());
			message(mode.displayName() + ": " + state.status.displayName
				+ (state.nextOpening == null ? ""
					: ", next #" + (state.nextOpening + 1))
				+ ", observations " + state.observations.size());
			if(state.targetItemId != null)
				message("  Target: " + formatTarget(state));
			if(state.targetHit != null)
				message("  Next target: +" + state.targetHit.offset());
		}
		printNextStep(selectedMode);
	}
	
	public void printHelp()
	{
		message(
			"VaultRoll predicts the vanilla 26.2 trial-chamber Vault loot sequence.");
		message("Set the world seed, then select normal or ominous mode.");
		message(
			"Use .vaultroll seed to check it. The World seed setting can set, change, or clear the seed for this server; leave it blank for automatic singleplayer detection.");
		message(
			"Auto observe records a complete nearby ejection; manual example:");
		message(
			".vaultroll observe ominous emerald=7,wind_charge=12,diamond=2");
		message(
			"Counts may be omitted, but every item type from the opening is required.");
		message(
			"A Vault opening consumes one position; normal and ominous counters are separate.");
		message(
			"Other players opening Vaults on the same server advance that mode's counter.");
		message(
			"Predictions assume the vanilla 26.2 Vault loot tables and no datapack override.");
		message(
			"Sequence state is saved automatically per server and seed in Wurst's config folder.");
		message(
			"Use .vaultroll fresh only before the first opening of that mode.");
		message(
			"Use .vaultroll target <item> [count] or .vaultroll next <openings> after sync.");
		message(
			"Target only searches and warns about a future matching opening; it does not change loot or consume an opening.");
		message(
			"Enable Prediction ESP to show each loaded Vault's next loot and Heavy Core countdown above it.");
		message(
			"If automatic capture is incomplete, let the items finish ejecting and use observe manually.");
		printNextStep(selectedMode);
	}
	
	public void resetFromCommand()
	{
		clearMode(selectedMode);
		savePersistentState();
		message(selectedMode.displayName() + " sequence state reset.");
		printNextStep(selectedMode);
	}
	
	public void resetAllFromCommand()
	{
		clearAllState();
		savePersistentState();
		message("Both Vault sequence states reset.");
		printNextStep(selectedMode);
	}
	
	public void setFreshFromCommand(VaultRollMode mode)
	{
		if(mode == null)
			mode = selectedMode;
		Long seed = getEffectiveSeed();
		if(seed == null)
		{
			message("World seed unknown. Use .vaultroll seed <seed>");
			return;
		}
		clearMode(mode);
		ModeState state = states.get(mode);
		state.sequenceSeed = seed;
		state.nextOpening = 0L;
		state.status = SequenceStatus.FRESH;
		lastEffectiveSeed = seed;
		message(mode.displayName()
			+ " fresh sequence assumption set at opening 0.");
		message("Next: open that Vault and allow every item to eject.");
		queueTargetSearch(mode);
		savePersistentState();
	}
	
	public void setManualSeed(String input)
	{
		Long parsed = VaultRollPredictor.tryParseSeed(input);
		if(parsed == null)
		{
			message("Invalid seed. Use a signed 64-bit value or a text seed.");
			return;
		}
		Long oldSeed = manualSeed;
		manualSeed = parsed;
		if(oldSeed != null && !oldSeed.equals(parsed))
			RollStateStore.clear("vaultRoll", getPersistenceServerKey(),
				oldSeed);
		worldSeed.setValue(input.trim());
		lastWorldSeedSettingValue = worldSeed.getValue();
		clearAllState();
		lastEffectiveSeed = parsed;
		savePersistentState();
		message("Manual seed set to " + parsed + ".");
		printNextStep(selectedMode);
	}
	
	public void clearManualSeed()
	{
		Long oldSeed = manualSeed;
		String oldServerKey = getPersistenceServerKey();
		manualSeed = null;
		worldSeed.setValue("");
		lastWorldSeedSettingValue = "";
		clearAllState();
		if(oldSeed != null)
			RollStateStore.clear("vaultRoll", oldServerKey, oldSeed);
		lastEffectiveSeed = getEffectiveSeed();
		savePersistentState();
		message("Manual seed cleared.");
		printNextStep(selectedMode);
	}
	
	public void observeFromCommand(VaultRollMode mode, String input)
	{
		if(mode == null)
			mode = selectedMode;
		try
		{
			VaultRollObservation observation =
				VaultRollObservation.parse(mode, input);
			handleObservation(observation, true);
		}catch(IllegalArgumentException e)
		{
			message("Invalid observation: " + e.getMessage());
		}
	}
	
	public void resynchronize(int horizon)
	{
		ModeState state = states.get(selectedMode);
		if(state.observations.isEmpty())
		{
			message("No " + selectedMode.id() + " observations yet.");
			printNextStep(selectedMode);
			return;
		}
		startSynchronization(selectedMode, horizon);
	}
	
	public int getSynchronizationHorizon()
	{
		return synchronizationHorizon.getValueI();
	}
	
	public void setTarget(String input, Integer count)
	{
		String itemId = VaultRollPredictor.normalizeItemId(input);
		if(itemId.isBlank() || itemId.equals("minecraft:"))
		{
			message("Target item is empty.");
			return;
		}
		if(count != null && count < 1)
		{
			message("Target count must be positive.");
			return;
		}
		ModeState state = states.get(selectedMode);
		state.targetItemId = itemId;
		state.targetCount = count;
		resetTargetWarnings(state);
		savePersistentState();
		message(
			"Target set for " + selectedMode.id() + ": " + formatTarget(state)
				+ " (search only; does not change the sequence).");
		queueTargetSearch(selectedMode);
	}
	
	public void clearTarget()
	{
		ModeState state = states.get(selectedMode);
		state.targetItemId = null;
		state.targetCount = null;
		cancelTargetSearch(selectedMode);
		resetTargetWarnings(state);
		savePersistentState();
		message("Target cleared for " + selectedMode.id() + ".");
	}
	
	public void search(String input, Integer count)
	{
		String itemId = VaultRollPredictor.normalizeItemId(input);
		ModeState state = states.get(selectedMode);
		if(state.sequenceSeed == null || state.nextOpening == null)
		{
			message("Sequence position is unknown.");
			printNextStep(selectedMode);
			return;
		}
		if(count != null && count < 1)
		{
			message("Search count must be positive.");
			return;
		}
		long generation = targetGenerations.get(selectedMode).incrementAndGet();
		cancelFuture(targetTasks.get(selectedMode));
		long seed = state.sequenceSeed;
		long first = state.nextOpening;
		int horizon = searchHorizon.getValueI();
		VaultRollMode mode = selectedMode;
		targetTasks.put(mode, executor.submit(() -> {
			VaultRollPredictor.TargetHit hit = VaultRollPredictor.findTarget(
				seed, mode, first, horizon, itemId, count,
				() -> generation != targetGenerations.get(mode).get());
			MC.execute(() -> {
				if(generation != targetGenerations.get(mode).get())
					return;
				if(hit == null)
					message("No " + VaultRollPredictor.formatItem(itemId)
						+ " found in " + horizon + " openings.");
				else
				{
					message("+" + hit.offset() + " opening #"
						+ (hit.absoluteOpening() + 1) + ": "
						+ hit.opening().describe());
				}
			});
		}));
	}
	
	public void printUpcoming(int horizon)
	{
		if(horizon < 0 || horizon > 100_000)
		{
			message("Horizon must be between 0 and 100000 openings.");
			return;
		}
		ModeState state = states.get(selectedMode);
		if(state.sequenceSeed == null || state.nextOpening == null)
		{
			message("Sequence position is unknown.");
			printNextStep(selectedMode);
			return;
		}
		long generation = targetGenerations.get(selectedMode).incrementAndGet();
		cancelFuture(targetTasks.get(selectedMode));
		long seed = state.sequenceSeed;
		long first = state.nextOpening;
		VaultRollMode mode = selectedMode;
		targetTasks.put(mode, executor.submit(() -> {
			List<VaultRollOpening> openings =
				VaultRollPredictor.predictOpenings(seed, mode, first, horizon);
			MC.execute(() -> {
				if(generation != targetGenerations.get(mode).get())
					return;
				message("Upcoming " + mode.id() + " Vault openings:");
				for(int i = 0; i < openings.size()
					&& i < MAX_UPCOMING_LINES; i++)
					message("+" + (i + 1) + " #" + (first + i + 1) + ": "
						+ openings.get(i).describe());
				if(openings.size() > MAX_UPCOMING_LINES)
					message("Output limited to " + MAX_UPCOMING_LINES
						+ " openings.");
			});
		}));
	}
	
	private void handleObservation(VaultRollObservation observation,
		boolean manual)
	{
		VaultRollMode mode = observation.mode();
		ModeState state = states.get(mode);
		if(state.sequenceStatusIsFresh() && state.nextOpening != null
			&& state.sequenceSeed != null)
		{
			VaultRollOpening expected = VaultRollPredictor
				.predictOpening(state.sequenceSeed, mode, state.nextOpening);
			if(observation.matches(expected))
			{
				appendObservation(state, observation);
				state.nextOpening++;
				state.status = SequenceStatus.SYNCHRONIZED;
				savePersistentState();
				message(mode.displayName()
					+ " fresh sequence confirmed at opening 0.");
				messageOpening(manual, mode, state.nextOpening - 1, expected);
				queueTargetSearch(mode);
				return;
			}
			message(mode.displayName()
				+ " fresh assumption did not match; synchronizing...");
			state.status = SequenceStatus.UNKNOWN;
			appendObservation(state, observation);
			savePersistentState();
			startSynchronization(mode,
				VaultRollSynchronizer.INITIAL_SEARCH_HORIZON);
			printNextStep(mode);
			return;
		}
		if((state.status == SequenceStatus.SYNCHRONIZED
			|| state.status == SequenceStatus.VERIFYING)
			&& state.nextOpening != null && state.sequenceSeed != null)
		{
			long expectedPosition = state.nextOpening;
			VaultRollOpening expected = VaultRollPredictor
				.predictOpening(state.sequenceSeed, mode, expectedPosition);
			if(observation.matches(expected))
			{
				appendObservation(state, observation);
				state.nextOpening++;
				state.status = SequenceStatus.SYNCHRONIZED;
				savePersistentState();
				message(mode.displayName() + " synchronized at opening #"
					+ (state.nextOpening));
				messageOpening(manual, mode, expectedPosition, expected);
				queueTargetSearch(mode);
				return;
			}
			message(mode.displayName()
				+ " prediction mismatch; searching forward for a skipped opening...");
			appendObservation(state, observation);
			state.status = SequenceStatus.UNKNOWN;
			startGapRecovery(mode, expectedPosition, observation);
			savePersistentState();
			return;
		}
		appendObservation(state, observation);
		startSynchronization(mode,
			VaultRollSynchronizer.INITIAL_SEARCH_HORIZON);
		savePersistentState();
		if(manual)
			message(mode.displayName()
				+ " observation recorded. Synchronizing in the background.");
		printNextStep(mode);
	}
	
	private void messageOpening(boolean manual, VaultRollMode mode,
		long opening, VaultRollOpening expected)
	{
		if(manual)
			message("Opening #" + (opening + 1) + ": " + expected.describe());
	}
	
	private void appendObservation(ModeState state,
		VaultRollObservation observation)
	{
		state.observations.add(observation);
		if(state.observations.size() > MAX_OBSERVATIONS)
			state.observations.remove(0);
		state.stateGeneration++;
		savePersistentState();
	}
	
	private void keepOnlyLatest(ModeState state,
		VaultRollObservation observation)
	{
		state.observations.clear();
		appendObservation(state, observation);
	}
	
	private void startSynchronization(VaultRollMode mode, int horizon)
	{
		Long seed = getEffectiveSeed();
		ModeState state = states.get(mode);
		if(seed == null)
		{
			message("World seed unknown. Use .vaultroll seed <seed>");
			return;
		}
		if(horizon < VaultRollSynchronizer.INITIAL_SEARCH_HORIZON
			|| horizon > VaultRollSynchronizer.MAXIMUM_SEARCH_HORIZON)
		{
			message(
				"Synchronization horizon must be between 1000 and 1000000.");
			return;
		}
		state.sequenceSeed = seed;
		state.nextOpening = null;
		state.status = SequenceStatus.SYNCHRONIZING;
		state.lastSyncHorizon = horizon;
		state.lastSyncResult = null;
		savePersistentState();
		long generation =
			synchronizationGenerations.get(mode).incrementAndGet();
		long capturedStateGeneration = state.stateGeneration;
		List<VaultRollObservation> snapshot = List.copyOf(state.observations);
		cancelFuture(synchronizationTasks.get(mode));
		BooleanSupplier cancelled =
			() -> generation != synchronizationGenerations.get(mode).get();
		long capturedSeed = seed;
		synchronizationTasks.put(mode, executor.submit(() -> {
			VaultRollSynchronizer.Result result = VaultRollSynchronizer
				.synchronize(capturedSeed, mode, snapshot, horizon, cancelled);
			MC.execute(() -> acceptSynchronization(mode, generation,
				capturedStateGeneration, capturedSeed, snapshot, horizon,
				result));
		}));
	}
	
	private void acceptSynchronization(VaultRollMode mode, long generation,
		long capturedStateGeneration, long seed,
		List<VaultRollObservation> snapshot, int horizon,
		VaultRollSynchronizer.Result result)
	{
		ModeState state = states.get(mode);
		if(generation != synchronizationGenerations.get(mode).get()
			|| capturedStateGeneration != state.stateGeneration
			|| !snapshot.equals(state.observations)
			|| !Long.valueOf(seed).equals(getEffectiveSeed()))
			return;
		state.lastSyncResult = result;
		savePersistentState();
		if(result.status() == VaultRollSynchronizer.Status.CANCELLED)
			return;
		if(result.status() == VaultRollSynchronizer.Status.NO_MATCH)
		{
			if(horizon == VaultRollSynchronizer.INITIAL_SEARCH_HORIZON)
			{
				message("No match in 1000 " + mode.id()
					+ " openings; extending synchronization to 100000.");
				startSynchronization(mode,
					VaultRollSynchronizer.EXTENDED_SEARCH_HORIZON);
				return;
			}
			if(!state.gapRecoveryAttempted && state.observations.size() > 1)
			{
				state.gapRecoveryAttempted = true;
				VaultRollObservation latest =
					state.observations.get(state.observations.size() - 1);
				keepOnlyLatest(state, latest);
				state.status = SequenceStatus.UNKNOWN;
				message(
					"No consecutive match found. Another player may have advanced the "
						+ mode.id()
						+ " counter; retrying from the newest opening.");
				startSynchronization(mode, horizon);
				return;
			}
			state.status = SequenceStatus.UNKNOWN;
			savePersistentState();
			message("No matching " + mode.id() + " sequence position found in "
				+ horizon + " openings.");
			printNextStep(mode);
			return;
		}
		if(result.status() == VaultRollSynchronizer.Status.AMBIGUOUS)
		{
			state.status = SequenceStatus.AMBIGUOUS;
			state.nextOpening = null;
			savePersistentState();
			message(mode.displayName()
				+ " sequence position is ambiguous (" + (result.moreCandidates()
					? "50+" : result.candidateOpenings().size())
				+ " candidates).");
			printNextStep(mode);
			return;
		}
		state.sequenceSeed = seed;
		state.nextOpening = result.uniqueFirstOpening() + snapshot.size();
		state.status = SequenceStatus.VERIFYING;
		state.gapRecoveryAttempted = false;
		savePersistentState();
		message(
			mode.displayName() + " possible position found; next opening is #"
				+ (state.nextOpening + 1) + ". Verifying the next Vault.");
		queueTargetSearch(mode);
		printNextStep(mode);
	}
	
	private void startGapRecovery(VaultRollMode mode, long startOpening,
		VaultRollObservation latest)
	{
		ModeState state = states.get(mode);
		Long seed = state.sequenceSeed;
		if(seed == null)
		{
			startSynchronization(mode,
				VaultRollSynchronizer.INITIAL_SEARCH_HORIZON);
			return;
		}
		state.status = SequenceStatus.SYNCHRONIZING;
		savePersistentState();
		long generation =
			synchronizationGenerations.get(mode).incrementAndGet();
		long capturedStateGeneration = state.stateGeneration;
		cancelFuture(synchronizationTasks.get(mode));
		BooleanSupplier cancelled =
			() -> generation != synchronizationGenerations.get(mode).get();
		int horizon = synchronizationHorizon.getValueI();
		synchronizationTasks.put(mode, executor.submit(() -> {
			VaultRollSynchronizer.RecoveryResult result = VaultRollSynchronizer
				.recover(seed, mode, startOpening, latest, horizon, cancelled);
			MC.execute(() -> acceptGapRecovery(mode, generation,
				capturedStateGeneration, latest, result));
		}));
	}
	
	private void acceptGapRecovery(VaultRollMode mode, long generation,
		long capturedStateGeneration, VaultRollObservation latest,
		VaultRollSynchronizer.RecoveryResult result)
	{
		ModeState state = states.get(mode);
		if(generation != synchronizationGenerations.get(mode).get()
			|| capturedStateGeneration != state.stateGeneration)
			return;
		if(result.status() == VaultRollSynchronizer.Status.CANCELLED)
			return;
		if(result.status() == VaultRollSynchronizer.Status.NO_MATCH)
		{
			if(!state.gapRecoveryAttempted)
			{
				state.gapRecoveryAttempted = true;
				keepOnlyLatest(state, latest);
				state.status = SequenceStatus.UNKNOWN;
				message("No match within " + synchronizationHorizon.getValueI()
					+ " openings. Searching from zero using only the newest observation.");
				startSynchronization(mode,
					VaultRollSynchronizer.EXTENDED_SEARCH_HORIZON);
				return;
			}
			state.status = SequenceStatus.UNKNOWN;
			savePersistentState();
			message("Gap recovery failed for the " + mode.id() + " sequence.");
			printNextStep(mode);
			return;
		}
		keepOnlyLatest(state, latest);
		state.nextOpening = result.matchedOpening() + 1;
		state.status = SequenceStatus.VERIFYING;
		state.gapRecoveryAttempted = false;
		savePersistentState();
		if(result.skippedOpenings() > 0)
			message("Skipped " + result.skippedOpenings() + " " + mode.id()
				+ " opening(s); another player may have advanced the shared counter.");
		message("Recovered at opening #" + (result.matchedOpening() + 1)
			+ ". Verify the next Vault opening.");
		queueTargetSearch(mode);
		printNextStep(mode);
	}
	
	private void queueHeavyCoreSearches()
	{
		for(VaultRollMode mode : VaultRollMode.values())
			queueHeavyCoreSearch(mode);
	}
	
	private void queueHeavyCoreSearch(VaultRollMode mode)
	{
		ModeState state = states.get(mode);
		if(state.sequenceSeed == null || state.nextOpening == null)
		{
			state.heavyCoreHit = null;
			state.heavyCoreSearchSeed = null;
			state.heavyCoreSearchStart = null;
			state.heavyCoreSearchComplete = false;
			cancelHeavyCoreSearch(mode);
			return;
		}
		
		int horizon = searchHorizon.getValueI();
		if(Objects.equals(state.heavyCoreSearchSeed, state.sequenceSeed)
			&& Objects.equals(state.heavyCoreSearchStart, state.nextOpening)
			&& state.heavyCoreSearchHorizon == horizon)
			return;
		
		long generation = heavyCoreGenerations.get(mode).incrementAndGet();
		long capturedStateGeneration = state.stateGeneration;
		long seed = state.sequenceSeed;
		long firstOpening = state.nextOpening;
		state.heavyCoreSearchSeed = seed;
		state.heavyCoreSearchStart = firstOpening;
		state.heavyCoreSearchHorizon = horizon;
		state.heavyCoreHit = null;
		state.heavyCoreSearchComplete = false;
		cancelFuture(heavyCoreTasks.get(mode));
		heavyCoreTasks.put(mode, executor.submit(() -> {
			VaultRollPredictor.TargetHit hit = VaultRollPredictor.findTarget(
				seed, mode, firstOpening, horizon, "minecraft:heavy_core", null,
				() -> generation != heavyCoreGenerations.get(mode).get());
			MC.execute(() -> {
				ModeState current = states.get(mode);
				if(generation != heavyCoreGenerations.get(mode).get()
					|| capturedStateGeneration != current.stateGeneration
					|| !Objects.equals(current.sequenceSeed, seed)
					|| !Objects.equals(current.nextOpening, firstOpening)
					|| current.heavyCoreSearchHorizon != horizon)
					return;
				current.heavyCoreHit =
					hit == null ? null : new TargetHit(hit.offset(),
						hit.absoluteOpening(), hit.opening());
				current.heavyCoreSearchComplete = true;
			});
		}));
	}
	
	private void cancelHeavyCoreSearch(VaultRollMode mode)
	{
		heavyCoreGenerations.get(mode).incrementAndGet();
		cancelFuture(heavyCoreTasks.remove(mode));
	}
	
	private void drawWorldLabel(PoseStack matrices, Vec3 position,
		List<String> lines, int color, float labelScale)
	{
		if(lines.isEmpty() || MC.font == null)
			return;
		
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		Vec3 direction = position.subtract(cam);
		double distance = direction.length();
		double x = position.x;
		double y = position.y;
		double z = position.z;
		if(distance > 1)
		{
			Vec3 anchored =
				cam.add(direction.scale(Math.min(distance, 12) / distance));
			x = anchored.x;
			y = anchored.y;
			z = anchored.z;
		}
		matrices.translate(x - cam.x, y - cam.y, z - cam.z);
		var camera = MC.getCameraEntity();
		if(camera != null)
		{
			matrices.mulPose(Axis.YP.rotationDegrees(-camera.getYRot()));
			matrices.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
		}
		matrices.mulPose(Axis.YP.rotationDegrees(180));
		float scale =
			0.025F * RenderUtils.getCappedWorldLabelScale(labelScale, distance);
		matrices.scale(scale, -scale, scale);
		
		int maxWidth = 0;
		for(String line : lines)
			maxWidth = Math.max(maxWidth, MC.font.width(line));
		int background =
			(int)(MC.options.getBackgroundOpacity(0.25F) * 255) << 24;
		int lineHeight = MC.font.lineHeight + 2;
		DisplayMode layer =
			NiceWurstModule.enforceTextLayer(DisplayMode.SEE_THROUGH);
		for(int i = 0; i < lines.size(); i++)
		{
			String line = lines.get(i);
			int lineColor = i == 0 ? color : 0xFFFFFFFF;
			RenderUtils.drawTextInBatch(MC.font, line, -maxWidth / 2F,
				i * lineHeight, lineColor, false, matrices.last().pose(), null,
				layer, background, 0xF000F0);
		}
		matrices.popPose();
	}
	
	private static String formatPredictedStack(VaultRollStack stack)
	{
		StringBuilder result =
			new StringBuilder(VaultRollPredictor.formatItem(stack.itemId()));
		if(stack.count() != 1)
			result.append(" x").append(stack.count());
		if(stack.note() != null && !stack.note().isBlank())
			result.append(" [").append(stack.note()).append(']');
		for(VaultRollStack.Enchantment enchantment : stack.enchantments())
			result.append(" [")
				.append(VaultRollPredictor.formatItem(enchantment.id()))
				.append(' ').append(enchantment.level()).append(']');
		return result.toString();
	}
	
	private void queueTargetSearch(VaultRollMode mode)
	{
		ModeState state = states.get(mode);
		if(state.targetItemId == null || state.sequenceSeed == null
			|| state.nextOpening == null)
		{
			cancelTargetSearch(mode);
			return;
		}
		long generation = targetGenerations.get(mode).incrementAndGet();
		long capturedStateGeneration = state.stateGeneration;
		long seed = state.sequenceSeed;
		long first = state.nextOpening;
		String item = state.targetItemId;
		Integer count = state.targetCount;
		int horizon = searchHorizon.getValueI();
		cancelFuture(targetTasks.get(mode));
		BooleanSupplier cancelled =
			() -> generation != targetGenerations.get(mode).get();
		targetTasks.put(mode, executor.submit(() -> {
			VaultRollPredictor.TargetHit hit = VaultRollPredictor
				.findTarget(seed, mode, first, horizon, item, count, cancelled);
			MC.execute(() -> {
				ModeState current = states.get(mode);
				if(generation != targetGenerations.get(mode).get()
					|| capturedStateGeneration != current.stateGeneration)
					return;
				current.targetHit =
					hit == null ? null : new TargetHit(hit.offset(),
						hit.absoluteOpening(), hit.opening());
				emitTargetWarning(mode);
			});
		}));
	}
	
	private void emitTargetWarning(VaultRollMode mode)
	{
		ModeState state = states.get(mode);
		if(state.targetHit == null || !chatWarnings.isChecked())
			return;
		long offset = state.targetHit.offset();
		if(state.targetHit.absoluteOpening() == state.lastWarningOpening
			&& offset == state.lastWarningOffset)
			return;
		if(offset != 0 && offset != 1 && offset != 2 && offset != 5)
			return;
		if(offset > warningDistance.getValueI())
			return;
		state.lastWarningOpening = state.targetHit.absoluteOpening();
		state.lastWarningOffset = offset;
		String text = formatTarget(state) + " at opening #"
			+ (state.targetHit.absoluteOpening() + 1);
		if(offset == 0)
			message("TARGET NEXT: " + text + " - use the key now.");
		else
			message(text + " in " + offset + " opening(s).");
	}
	
	private void observeVaultEjections()
	{
		if(MC.level == null || MC.player == null)
			return;
		long now = MC.level.getGameTime();
		Set<VaultKey> loaded = new HashSet<>();
		ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof VaultBlockEntity)
			.map(be -> (VaultBlockEntity)be).forEach(be -> {
				BlockPosAndState block = getVaultState(be);
				if(block == null)
					return;
				VaultKey key = new VaultKey(block.pos());
				loaded.add(key);
				VaultState previous =
					previousVaultStates.put(key, block.state());
				if(MC.player.distanceToSqr(block.pos().getX() + 0.5,
					block.pos().getY() + 0.5,
					block.pos().getZ() + 0.5) > 64 * 64)
					return;
				AutoOpening opening = autoOpenings.get(key);
				if(block.state() == VaultState.UNLOCKING
					&& previous != VaultState.UNLOCKING)
				{
					opening = new AutoOpening(key, isOminous(block.blockState),
						snapshotItemIds(block.pos()));
					autoOpenings.put(key, opening);
				}
				if(block.state() == VaultState.EJECTING)
				{
					if(opening == null)
					{
						// A late block-state packet is deliberately accepted
						// only
						// as a best-effort capture; missing items simply fail
						// matching.
						opening = new AutoOpening(key,
							isOminous(block.blockState), Set.of());
						autoOpenings.put(key, opening);
					}
					opening.sawEjecting = true;
					opening.ejectingEndedAt = -1;
					captureItems(opening);
				}else if(opening != null && opening.sawEjecting)
				{
					if(opening.ejectingEndedAt < 0)
						opening.ejectingEndedAt = now;
					captureItems(opening);
					if(now
						- opening.ejectingEndedAt >= AUTO_CAPTURE_GRACE_TICKS)
					{
						autoOpenings.remove(key);
						finishAutoOpening(opening);
					}
				}
			});
		previousVaultStates.keySet().removeIf(key -> !loaded.contains(key));
		autoOpenings.keySet().removeIf(key -> !loaded.contains(key));
	}
	
	private BlockPosAndState getVaultState(VaultBlockEntity blockEntity)
	{
		var pos = blockEntity.getBlockPos().immutable();
		BlockState state = MC.level.getBlockState(pos);
		return isVault(state)
			? new BlockPosAndState(pos, state, state.getValue(VaultBlock.STATE))
			: null;
	}
	
	private void rememberVisibleItems()
	{
		long now = MC.level.getGameTime();
		for(var entity : MC.level.entitiesForRendering())
		{
			if(!(entity instanceof ItemEntity item) || !item.isAlive()
				|| item.isRemoved())
				continue;
			ItemStack stack = item.getItem();
			if(stack == null || stack.isEmpty())
				continue;
			VaultRollStack details = describeItemStack(stack);
			if(details == null)
				continue;
			KnownItem previous = knownItems.get(item.getId());
			boolean sameEntity =
				previous != null && previous.uuid().equals(item.getUUID());
			int count = !sameEntity ? stack.getCount()
				: Math.max(previous.count(), stack.getCount());
			net.minecraft.world.phys.Vec3 firstPosition =
				!sameEntity ? item.position() : previous.firstPosition();
			long firstSeen = !sameEntity ? now : previous.firstSeen();
			knownItems.put(item.getId(),
				new KnownItem(item.getId(), item.getUUID(), details.itemId(),
					count, details.enchantments(), details.note(),
					firstPosition, item.position(), firstSeen, now));
		}
		knownItems.entrySet().removeIf(
			entry -> now - entry.getValue().lastSeen() > MAX_KNOWN_ITEM_TICKS);
	}
	
	private void rememberPickedUpItem(int entityId, int amount)
	{
		KnownItem item = knownItems.get(entityId);
		if(item == null && MC.level != null
			&& MC.level.getEntity(entityId) instanceof ItemEntity entity)
		{
			ItemStack stack = entity.getItem();
			VaultRollStack details = stack == null || stack.isEmpty() ? null
				: describeItemStack(stack);
			if(details != null)
			{
				int count = Math.max(stack.getCount(), amount);
				item = new KnownItem(entity.getId(), entity.getUUID(),
					details.itemId(), count, details.enchantments(),
					details.note(), entity.position(), entity.position(),
					MC.level.getGameTime(), MC.level.getGameTime());
			}
		}
		if(item == null)
			return;
		AutoOpening opening = findActiveOpening(item);
		if(opening == null)
			return;
		int count = Math.max(item.count(), amount);
		CapturedItem captured = opening.items.get(item.uuid());
		int previousCount = captured == null ? 0 : captured.count;
		if(captureItem(opening, item.entityId(), item.uuid(), item.itemId(),
			count, item.enchantments(), item.note()))
			opening.maximumTotals.merge(item.itemId(), count, Integer::sum);
		else if(count > previousCount)
			opening.maximumTotals.merge(item.itemId(), count - previousCount,
				Integer::sum);
		opening.pickedUpItems++;
	}
	
	private AutoOpening findActiveOpening(KnownItem item)
	{
		AutoOpening nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for(AutoOpening opening : autoOpenings.values())
		{
			if(!opening.sawEjecting)
				continue;
			if(opening.items.containsKey(item.uuid()))
				return opening;
			net.minecraft.world.phys.Vec3 center =
				net.minecraft.world.phys.Vec3.atCenterOf(opening.key.pos());
			double distance = Math.min(item.position().distanceToSqr(center),
				item.firstPosition().distanceToSqr(center));
			if(distance <= (AUTO_CAPTURE_RADIUS + 2) * (AUTO_CAPTURE_RADIUS + 2)
				&& distance < nearestDistance)
			{
				nearest = opening;
				nearestDistance = distance;
			}
		}
		return nearest;
	}
	
	private Set<UUID> snapshotItemIds(net.minecraft.core.BlockPos pos)
	{
		Set<UUID> result = new HashSet<>();
		for(ItemEntity item : itemsNear(pos))
			result.add(item.getUUID());
		return result;
	}
	
	private VaultRollStack describeItemStack(ItemStack stack)
	{
		String itemId = ItemUtils.getStackId(stack);
		if(itemId == null || stack.isEmpty())
			return null;
		
		List<VaultRollStack.Enchantment> enchantments = new ArrayList<>();
		for(var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack)
			.entrySet())
		{
			String id = entry.getKey().unwrapKey()
				.map(key -> key.identifier().toString()).orElse(null);
			if(id == null)
			{
				enchantments.clear();
				break;
			}
			enchantments
				.add(new VaultRollStack.Enchantment(id, entry.getIntValue()));
		}
		
		String note = null;
		OminousBottleAmplifier amplifier =
			stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
		if(amplifier != null)
			note = "amplifier=" + amplifier.value();
		else
		{
			PotionContents potionContents = stack.getOrDefault(
				DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
			if(potionContents.potion().isPresent())
				note = potionContents.potion().get().unwrapKey()
					.map(key -> "potion=" + key.identifier()).orElse(null);
		}
		return new VaultRollStack(VaultRollObservation.normalizeItemId(itemId),
			stack.getCount(), enchantments, note);
	}
	
	private void captureItems(AutoOpening opening)
	{
		Map<String, Integer> currentTotals = new HashMap<>();
		for(ItemEntity item : itemsNear(opening.key.pos()))
		{
			if(item.getAge() > MAX_AUTO_ITEM_AGE
				|| opening.baselineItemIds.contains(item.getUUID()))
				continue;
			ItemStack stack = item.getItem();
			if(stack == null || stack.isEmpty())
				continue;
			VaultRollStack details = describeItemStack(stack);
			if(details == null)
				continue;
			KnownItem known = knownItems.get(item.getId());
			if(known != null && findActiveOpening(known) != opening)
				continue;
			captureItem(opening, item.getId(), item.getUUID(), details.itemId(),
				details.count(), details.enchantments(), details.note());
			currentTotals.merge(details.itemId(), details.count(),
				Integer::sum);
		}
		for(var entry : currentTotals.entrySet())
			opening.maximumTotals.merge(entry.getKey(), entry.getValue(),
				Math::max);
		captureKnownItems(opening);
	}
	
	private void captureKnownItems(AutoOpening opening)
	{
		long now = MC.level.getGameTime();
		for(KnownItem item : knownItems.values())
		{
			if(now - item.lastSeen() > MAX_KNOWN_ITEM_TICKS
				|| item.firstSeen() < opening.startedAt
				|| opening.baselineItemIds.contains(item.uuid())
				|| !isNearOpening(item, opening)
				|| findActiveOpening(item) != opening)
				continue;
			CapturedItem captured = opening.items.get(item.uuid());
			int previousCount = captured == null ? 0 : captured.count;
			if(captureItem(opening, item.entityId(), item.uuid(), item.itemId(),
				item.count(), item.enchantments(), item.note()))
				opening.maximumTotals.merge(item.itemId(), item.count(),
					Integer::sum);
			else if(item.count() > previousCount)
				opening.maximumTotals.merge(item.itemId(),
					item.count() - previousCount, Integer::sum);
		}
	}
	
	private boolean isNearOpening(KnownItem item, AutoOpening opening)
	{
		net.minecraft.world.phys.Vec3 center =
			net.minecraft.world.phys.Vec3.atCenterOf(opening.key.pos());
		return Math.min(item.firstPosition().distanceToSqr(center),
			item.position().distanceToSqr(center)) <= (AUTO_CAPTURE_RADIUS + 2)
				* (AUTO_CAPTURE_RADIUS + 2);
	}
	
	private boolean captureItem(AutoOpening opening, int entityId, UUID uuid,
		String itemId, int count, List<VaultRollStack.Enchantment> enchantments,
		String note)
	{
		CapturedItem captured = opening.items.get(uuid);
		if(captured != null)
		{
			if(count > captured.count)
				opening.sawMergedStack = true;
			captured.itemId = itemId;
			captured.count = Math.max(captured.count, count);
			if(!enchantments.isEmpty())
				captured.enchantments = enchantments;
			if(note != null)
				captured.note = note;
			captured.entityId = entityId;
			return false;
		}
		opening.items.put(uuid,
			new CapturedItem(entityId, itemId, count, enchantments, note));
		return true;
	}
	
	private void finishAutoOpening(AutoOpening opening)
	{
		if(opening.maximumTotals.isEmpty())
		{
			message("Could not read the " + opening.mode.id()
				+ " Vault drops. Keep the Vault loaded and let all items eject, or use "
				+ ".vaultroll observe.");
			return;
		}
		try
		{
			VaultRollObservation captured =
				new VaultRollObservation(opening.mode, opening.maximumTotals);
			VaultRollObservation observation;
			if(opening.sawMergedStack)
				observation = captured;
			else
			{
				List<VaultRollStack> stacks = new ArrayList<>();
				for(CapturedItem item : opening.items.values().stream()
					.sorted((first, second) -> Integer.compare(first.entityId,
						second.entityId))
					.toList())
					stacks.add(new VaultRollStack(item.itemId, item.count,
						item.enchantments, item.note));
				observation =
					new VaultRollObservation(opening.mode, stacks, true);
			}
			message("Auto-captured " + opening.mode.id() + " Vault: "
				+ captured.describe()
				+ (opening.pickedUpItems == 0 ? ""
					: " (including picked-up drops)")
				+ (opening.sawMergedStack
					? ". Matching exact aggregate totals because item stacks merged."
					: ". Matching ordered item stacks and exact counts."));
			handleObservation(observation, false);
		}catch(IllegalArgumentException ignored)
		{
			message(
				"Automatic Vault capture was incomplete; use .vaultroll observe "
					+ opening.mode.id() + " <items>.");
		}
	}
	
	private List<ItemEntity> itemsNear(net.minecraft.core.BlockPos pos)
	{
		AABB box = AABB.ofSize(net.minecraft.world.phys.Vec3.atCenterOf(pos),
			AUTO_CAPTURE_RADIUS * 2, AUTO_CAPTURE_RADIUS * 2,
			AUTO_CAPTURE_RADIUS * 2);
		return MC.level.getEntitiesOfClass(ItemEntity.class, box);
	}
	
	private boolean isVault(BlockState state)
	{
		return state != null && state.is(Blocks.VAULT)
			&& state.hasProperty(VaultBlock.STATE)
			&& state.hasProperty(VaultBlock.OMINOUS);
	}
	
	private boolean isOminous(BlockState state)
	{
		return isVault(state) && state.getValue(VaultBlock.OMINOUS);
	}
	
	private String formatTarget(ModeState state)
	{
		String item = VaultRollPredictor.formatItem(state.targetItemId);
		return state.targetCount == null ? item
			: item + " x" + state.targetCount;
	}
	
	private void printNextStep(VaultRollMode mode)
	{
		ModeState state = states.get(mode);
		if(getEffectiveSeed() == null)
		{
			message("Next: set the world seed with .vaultroll seed <seed>. "
				+ "Singleplayer seeds are detected automatically.");
			return;
		}
		switch(state.status)
		{
			case UNKNOWN:
			if(state.observations.isEmpty())
				message("Next: open one " + mode.id()
					+ " Vault and let all reward items eject, or use .vaultroll observe.");
			else
				message(
					"Next: verify the mode/seed, then collect another complete "
						+ mode.id() + " Vault opening.");
			break;
			case FRESH:
			message("Next: open the first " + mode.id()
				+ " Vault once; fresh mode assumes opening 0.");
			break;
			case SYNCHRONIZING:
			message(
				"Next: let the synchronization search finish, then open a new "
					+ mode.id() + " Vault for verification.");
			break;
			case VERIFYING:
			message("Next: open one more complete " + mode.id()
				+ " Vault opening to verify the position.");
			break;
			case SYNCHRONIZED:
			message("Next: use a " + mode.id()
				+ " Vault key. Each complete opening advances this shared mode counter.");
			break;
			case AMBIGUOUS:
			message("Next: collect 1-2 more complete " + mode.id()
				+ " Vault openings; do not count a partial ejection.");
			break;
		}
	}
	
	private void resetTargetWarnings(ModeState state)
	{
		state.targetHit = null;
		state.lastWarningOpening = Long.MIN_VALUE;
		state.lastWarningOffset = -1;
	}
	
	private void clearAllState()
	{
		for(VaultRollMode mode : VaultRollMode.values())
			clearMode(mode);
		previousVaultStates.clear();
		autoOpenings.clear();
		knownItems.clear();
	}
	
	private void clearMode(VaultRollMode mode)
	{
		ModeState state = states.get(mode);
		state.observations.clear();
		state.sequenceSeed = null;
		state.nextOpening = null;
		state.stateGeneration++;
		state.lastSyncResult = null;
		state.lastSyncHorizon = 0;
		state.status = SequenceStatus.UNKNOWN;
		state.gapRecoveryAttempted = false;
		resetTargetWarnings(state);
		synchronizationGenerations.get(mode).incrementAndGet();
		targetGenerations.get(mode).incrementAndGet();
		state.heavyCoreHit = null;
		state.heavyCoreSearchSeed = null;
		state.heavyCoreSearchStart = null;
		state.heavyCoreSearchHorizon = 0;
		state.heavyCoreSearchComplete = false;
		heavyCoreGenerations.get(mode).incrementAndGet();
		cancelFuture(synchronizationTasks.remove(mode));
		cancelFuture(targetTasks.remove(mode));
		cancelFuture(heavyCoreTasks.remove(mode));
	}
	
	private void cancelTargetSearch(VaultRollMode mode)
	{
		targetGenerations.get(mode).incrementAndGet();
		cancelFuture(targetTasks.remove(mode));
	}
	
	private static void cancelFuture(Future<?> future)
	{
		if(future != null)
			future.cancel(true);
	}
	
	private void preparePersistentState()
	{
		String serverKey = getPersistenceServerKey();
		if(lastLevel != null && lastLevel != MC.level)
		{
			savePersistentState(persistenceServerKey, lastEffectiveSeed);
			resetForContextChange();
			persistenceServerKey = null;
			persistenceLoaded = false;
		}
		if(persistenceLoaded && Objects.equals(persistenceServerKey, serverKey))
			return;
		if(persistenceLoaded)
			savePersistentState(persistenceServerKey, lastEffectiveSeed);
		resetForContextChange();
		persistenceServerKey = serverKey;
		persistenceLoaded = true;
		loadPersistentState(serverKey);
	}
	
	private void loadPersistentState(String serverKey)
	{
		if(serverKey == null)
		{
			lastEffectiveSeed = getEffectiveSeed();
			syncWorldSeedSetting();
			return;
		}
		Long knownSeed = getEffectiveSeed();
		JsonObject data =
			RollStateStore.load("vaultRoll", serverKey, knownSeed);
		if(data == null)
		{
			lastEffectiveSeed = knownSeed;
			syncWorldSeedSetting();
			return;
		}
		manualSeed = readLong(data, "manualSeed");
		if(manualSeed == null && MC.getSingleplayerServer() == null)
			manualSeed = readLong(data, "seed");
		VaultRollMode loadedMode =
			VaultRollMode.parse(readString(data, "selectedMode"));
		if(loadedMode != null)
			selectedMode = loadedMode;
		for(VaultRollMode mode : VaultRollMode.values())
		{
			JsonElement value = data.get(mode.id());
			if(value == null || !value.isJsonObject())
				continue;
			readModeState(mode, states.get(mode), value.getAsJsonObject());
		}
		lastEffectiveSeed = getEffectiveSeed();
		syncWorldSeedSetting();
		for(VaultRollMode mode : VaultRollMode.values())
		{
			ModeState state = states.get(mode);
			if(state.status == SequenceStatus.SYNCHRONIZING
				&& !state.observations.isEmpty() && getEffectiveSeed() != null)
				startSynchronization(mode,
					state.lastSyncHorizon >= VaultRollSynchronizer.INITIAL_SEARCH_HORIZON
						? state.lastSyncHorizon
						: VaultRollSynchronizer.INITIAL_SEARCH_HORIZON);
		}
		if(manualSeed != null || hasSavedVaultState())
			message("Restored saved state for " + serverKey + "."
				+ (getEffectiveSeed() == null ? ""
					: " Seed: " + getEffectiveSeed()));
	}
	
	private void readModeState(VaultRollMode mode, ModeState state,
		JsonObject data)
	{
		state.sequenceSeed = readLong(data, "sequenceSeed");
		state.nextOpening = readLong(data, "nextOpening");
		state.lastSyncHorizon = readInt(data, "lastSyncHorizon", 0);
		state.gapRecoveryAttempted =
			readBoolean(data, "gapRecoveryAttempted", false);
		state.targetItemId = readString(data, "targetItemId");
		state.targetCount = readInteger(data, "targetCount");
		state.status = readEnum(data, "status", SequenceStatus.class,
			SequenceStatus.UNKNOWN);
		JsonElement observations = data.get("observations");
		if(observations != null && observations.isJsonArray())
			for(JsonElement element : observations.getAsJsonArray())
			{
				if(!element.isJsonObject())
					continue;
				try
				{
					JsonObject object = element.getAsJsonObject();
					JsonElement stackValue = object.get("stacks");
					if(stackValue != null && stackValue.isJsonArray())
					{
						List<VaultRollStack> stacks = new ArrayList<>();
						for(JsonElement stackElement : stackValue
							.getAsJsonArray())
						{
							if(!stackElement.isJsonObject())
								continue;
							stacks.add(readVaultRollStack(
								stackElement.getAsJsonObject()));
						}
						if(!stacks.isEmpty())
						{
							state.observations.add(new VaultRollObservation(
								mode, stacks, readBoolean(object,
									"allowAggregateFallback", false)));
							continue;
						}
					}
					Map<String, Integer> items = new LinkedHashMap<>();
					for(var entry : object.entrySet())
					{
						JsonElement value = entry.getValue();
						items.put(entry.getKey(),
							value.isJsonNull() ? null : value.getAsInt());
					}
					state.observations
						.add(new VaultRollObservation(mode, items));
				}catch(RuntimeException ignored)
				{}
			}
		while(state.observations.size() > MAX_OBSERVATIONS)
			state.observations.remove(0);
		state.stateGeneration++;
	}
	
	private VaultRollStack readVaultRollStack(JsonObject data)
	{
		String itemId =
			VaultRollObservation.normalizeItemId(readString(data, "itemId"));
		int count = readInt(data, "count", 1);
		List<VaultRollStack.Enchantment> enchantments = new ArrayList<>();
		JsonElement enchantmentValue = data.get("enchantments");
		if(enchantmentValue != null && enchantmentValue.isJsonArray())
			for(JsonElement element : enchantmentValue.getAsJsonArray())
				if(element.isJsonObject())
				{
					JsonObject enchantment = element.getAsJsonObject();
					enchantments.add(new VaultRollStack.Enchantment(
						VaultRollObservation
							.normalizeItemId(readString(enchantment, "id")),
						readInt(enchantment, "level", 1)));
				}
		return new VaultRollStack(itemId, count, enchantments,
			readString(data, "note"));
	}
	
	private boolean hasSavedVaultState()
	{
		for(ModeState state : states.values())
			if(state.sequenceSeed != null || !state.observations.isEmpty()
				|| state.targetItemId != null)
				return true;
		return false;
	}
	
	private void savePersistentState()
	{
		savePersistentState(null, null);
	}
	
	private void savePersistentState(String serverKeyOverride,
		Long seedOverride)
	{
		String serverKey = serverKeyOverride == null ? getPersistenceServerKey()
			: serverKeyOverride;
		if(serverKey == null)
			serverKey = persistenceServerKey;
		Long seed = seedOverride == null ? getEffectiveSeed() : seedOverride;
		if(seed == null)
			for(ModeState state : states.values())
				if(state.sequenceSeed != null)
				{
					seed = state.sequenceSeed;
					break;
				}
		if(seed == null)
			seed = manualSeed;
		if(serverKey == null || seed == null)
			return;
		JsonObject data = new JsonObject();
		data.addProperty("seed", seed);
		if(manualSeed != null)
			data.addProperty("manualSeed", manualSeed);
		data.addProperty("selectedMode", selectedMode.id());
		for(VaultRollMode mode : VaultRollMode.values())
		{
			ModeState state = states.get(mode);
			JsonObject modeData = new JsonObject();
			if(state.sequenceSeed != null)
				modeData.addProperty("sequenceSeed", state.sequenceSeed);
			if(state.nextOpening != null)
				modeData.addProperty("nextOpening", state.nextOpening);
			modeData.addProperty("status", state.status.name());
			modeData.addProperty("lastSyncHorizon", state.lastSyncHorizon);
			modeData.addProperty("gapRecoveryAttempted",
				state.gapRecoveryAttempted);
			if(state.targetItemId != null)
				modeData.addProperty("targetItemId", state.targetItemId);
			if(state.targetCount != null)
				modeData.addProperty("targetCount", state.targetCount);
			JsonArray savedObservations = new JsonArray();
			for(VaultRollObservation observation : state.observations)
			{
				JsonObject items = new JsonObject();
				if(!observation.stacks().isEmpty())
				{
					JsonArray stacks = new JsonArray();
					for(var stack : observation.stacks())
					{
						JsonObject stackData = new JsonObject();
						stackData.addProperty("itemId", stack.itemId());
						stackData.addProperty("count", stack.count());
						if(stack.note() != null)
							stackData.addProperty("note", stack.note());
						if(!stack.enchantments().isEmpty())
						{
							JsonArray enchantments = new JsonArray();
							for(var enchantment : stack.enchantments())
							{
								JsonObject enchantmentData = new JsonObject();
								enchantmentData.addProperty("id",
									enchantment.id());
								enchantmentData.addProperty("level",
									enchantment.level());
								enchantments.add(enchantmentData);
							}
							stackData.add("enchantments", enchantments);
						}
						stacks.add(stackData);
					}
					items.add("stacks", stacks);
					if(observation.allowAggregateFallback())
						items.addProperty("allowAggregateFallback", true);
				}else
					for(var entry : observation.items().entrySet())
						if(entry.getValue() == null)
							items.add(entry.getKey(), JsonNull.INSTANCE);
						else
							items.addProperty(entry.getKey(), entry.getValue());
				savedObservations.add(items);
			}
			modeData.add("observations", savedObservations);
			data.add(mode.id(), modeData);
		}
		RollStateStore.save("vaultRoll", serverKey, seed, data);
	}
	
	private void resetForContextChange()
	{
		clearAllState();
		clearTargets();
		manualSeed = null;
		selectedMode = VaultRollMode.NORMAL;
	}
	
	private void clearTargets()
	{
		for(ModeState state : states.values())
		{
			state.targetItemId = null;
			state.targetCount = null;
		}
	}
	
	private String getPersistenceServerKey()
	{
		return RollStateStore.getCurrentServerKey();
	}
	
	private static String readString(JsonObject object, String name)
	{
		JsonElement value = object == null ? null : object.get(name);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}
	
	private static Long readLong(JsonObject object, String name)
	{
		try
		{
			JsonElement value = object.get(name);
			return value == null || value.isJsonNull() ? null
				: value.getAsLong();
		}catch(RuntimeException e)
		{
			return null;
		}
	}
	
	private static Integer readInteger(JsonObject object, String name)
	{
		Long value = readLong(object, name);
		return value == null ? null : value.intValue();
	}
	
	private static int readInt(JsonObject object, String name, int fallback)
	{
		Long value = readLong(object, name);
		return value == null ? fallback : value.intValue();
	}
	
	private static boolean readBoolean(JsonObject object, String name,
		boolean fallback)
	{
		try
		{
			JsonElement value = object.get(name);
			return value == null || value.isJsonNull() ? fallback
				: value.getAsBoolean();
		}catch(RuntimeException e)
		{
			return fallback;
		}
	}
	
	private static <T extends Enum<T>> T readEnum(JsonObject object,
		String name, Class<T> type, T fallback)
	{
		String value = readString(object, name);
		if(value == null)
			return fallback;
		try
		{
			return Enum.valueOf(type, value);
		}catch(IllegalArgumentException e)
		{
			return fallback;
		}
	}
	
	private void applyWorldSeedSettingChange()
	{
		String value = worldSeed.getValue().trim();
		if(value.equals(lastWorldSeedSettingValue))
			return;
		if(value.isEmpty())
		{
			worldSeed.setValue("");
			lastWorldSeedSettingValue = "";
			if(manualSeed == null)
				return;
			Long oldSeed = manualSeed;
			manualSeed = null;
			RollStateStore.clear("vaultRoll", getPersistenceServerKey(),
				oldSeed);
			clearAllState();
			clearTargets();
			lastEffectiveSeed = getEffectiveSeed();
			savePersistentState();
			message("World seed cleared for this server.");
			return;
		}
		Long parsed = VaultRollPredictor.tryParseSeed(value);
		if(parsed == null)
		{
			syncWorldSeedSetting();
			return;
		}
		Long oldSeed = manualSeed;
		worldSeed.setValue(value);
		lastWorldSeedSettingValue = worldSeed.getValue();
		manualSeed = parsed;
		if(oldSeed != null && !oldSeed.equals(parsed))
			RollStateStore.clear("vaultRoll", getPersistenceServerKey(),
				oldSeed);
		clearAllState();
		clearTargets();
		lastEffectiveSeed = parsed;
		savePersistentState();
		message("World seed set to " + parsed + ".");
	}
	
	private void syncWorldSeedSetting()
	{
		String value = manualSeed == null ? "" : Long.toString(manualSeed);
		worldSeed.setValue(value);
		lastWorldSeedSettingValue = value;
	}
	
	private Long getEffectiveSeed()
	{
		if(manualSeed != null)
			return manualSeed;
		IntegratedServer server = MC.getSingleplayerServer();
		if(server == null || MC.level == null)
			return null;
		var serverLevel = server.getLevel(MC.level.dimension());
		return serverLevel == null ? null : serverLevel.getSeed();
	}
	
	private static String formatPosition(net.minecraft.core.BlockPos pos)
	{
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}
	
	private void message(String text)
	{
		ChatUtils.message("[VaultRoll] " + text);
	}
	
	private final class ModeState
	{
		private final ArrayList<VaultRollObservation> observations =
			new ArrayList<>();
		private Long sequenceSeed;
		private Long nextOpening;
		private long stateGeneration;
		private int lastSyncHorizon;
		private VaultRollSynchronizer.Result lastSyncResult;
		private SequenceStatus status = SequenceStatus.UNKNOWN;
		private boolean gapRecoveryAttempted;
		private String targetItemId;
		private Integer targetCount;
		private TargetHit targetHit;
		private TargetHit heavyCoreHit;
		private Long heavyCoreSearchSeed;
		private Long heavyCoreSearchStart;
		private int heavyCoreSearchHorizon;
		private boolean heavyCoreSearchComplete;
		private long lastWarningOpening = Long.MIN_VALUE;
		private long lastWarningOffset = -1;
		
		private boolean sequenceStatusIsFresh()
		{
			return status == SequenceStatus.FRESH;
		}
	}
	
	private record TargetHit(long offset, long absoluteOpening,
		VaultRollOpening opening)
	{}
	
	private record VaultKey(net.minecraft.core.BlockPos pos)
	{}
	
	private record KnownItem(int entityId, UUID uuid, String itemId, int count,
		List<VaultRollStack.Enchantment> enchantments, String note,
		net.minecraft.world.phys.Vec3 firstPosition,
		net.minecraft.world.phys.Vec3 position, long firstSeen, long lastSeen)
	{}
	
	private record BlockPosAndState(net.minecraft.core.BlockPos pos,
		BlockState blockState, VaultState state)
	{}
	
	private final class AutoOpening
	{
		private final VaultKey key;
		private final VaultRollMode mode;
		private final Set<UUID> baselineItemIds;
		private final Map<UUID, CapturedItem> items = new LinkedHashMap<>();
		private final Map<String, Integer> maximumTotals = new HashMap<>();
		private final long startedAt;
		private int pickedUpItems;
		private boolean sawEjecting;
		private boolean sawMergedStack;
		private long ejectingEndedAt = -1;
		
		private AutoOpening(VaultKey key, boolean ominous,
			Set<UUID> baselineItemIds)
		{
			this.key = key;
			mode = ominous ? VaultRollMode.OMINOUS : VaultRollMode.NORMAL;
			this.baselineItemIds = baselineItemIds;
			startedAt = MC.level.getGameTime();
		}
	}
	
	private static final class CapturedItem
	{
		private int entityId;
		private String itemId;
		private int count;
		private List<VaultRollStack.Enchantment> enchantments;
		private String note;
		
		private CapturedItem(int entityId, String itemId, int count,
			List<VaultRollStack.Enchantment> enchantments, String note)
		{
			this.entityId = entityId;
			this.itemId = itemId;
			this.count = count;
			this.enchantments = List.copyOf(enchantments);
			this.note = note;
		}
	}
	
	private enum SequenceStatus
	{
		UNKNOWN("unknown"),
		SYNCHRONIZING("synchronizing"),
		VERIFYING("verifying"),
		SYNCHRONIZED("synchronized"),
		AMBIGUOUS("ambiguous"),
		FRESH("fresh assumption");
		
		private final String displayName;
		
		SequenceStatus(String displayName)
		{
			this.displayName = displayName;
		}
	}
}
