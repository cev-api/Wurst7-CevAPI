/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.events.KeyPressListener.KeyPressEvent;
import net.wurstclient.events.RightClickListener.RightClickEvent;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InteractionSimulator;

/**
 * Owns the run lifecycle. The hack class only adapts Wurst settings/events;
 * model, rendering, planning and transfers remain independently testable.
 */
public final class LootSorterController
{
	/** Main inventory plus hotbar. Armour and offhand are never storage. */
	private static final int PLAYER_STORAGE_SLOT_COUNT = 36;
	private final Minecraft mc;
	private final ContainerInteractionController interactions;
	private final InventoryProvenanceLedger ledger =
		new InventoryProvenanceLedger();
	private final ContainerRenderer renderer = new ContainerRenderer();
	private final SortPlanner planner = new SortPlanner();
	private final List<LogicalContainer> sources = new ArrayList<>();
	private final List<DestinationRule> destinations = new ArrayList<>();
	private final Map<LogicalContainer, List<ItemStack>> scannedSources =
		new HashMap<>();
	private final Set<LogicalContainer> completedSources = new HashSet<>();
	private final Set<LogicalContainer> unmatchedSources = new HashSet<>();
	private final Set<LogicalContainer> unreachableSources = new HashSet<>();
	private final Set<LogicalContainer> unloadedContainers = new HashSet<>();
	private final Set<DestinationRule> warnedFullDestinations = new HashSet<>();
	private final Set<LogicalContainer> warnedUnreachable = new HashSet<>();
	private LootSorterState state = LootSorterState.DISABLED;
	private Supplier<ItemFilter> selectedFilter;
	private final Consumer<DestinationRule> destinationEditor;
	private final Consumer<List<SourceContentsSnapshot>> sourceContentsSaver;
	private PathFinder pathFinder;
	private PathProcessor processor;
	private LogicalContainer activeSource;
	private DestinationRule activeDestination;
	private BlockPos pathGoal;
	private long stateStarted;
	private long lastAction;
	private int sourceIndex;
	private int scanIndex;
	private int destinationIndex;
	private PendingAction pending;
	private int movedItems;
	private int containerTrips;
	private int consecutiveFailures;
	private int maximumFailures = 3;
	private SortRoute activeRoute;
	private String runDimension;
	private Vec3 lastPlayerPosition;
	private boolean returningRemainder;
	private boolean closingAfterDeposit;
	private boolean automaticallyCloseScreens = true;
	private boolean chatNotifications = true;
	private boolean fullCompletionSummary = true;
	private boolean debugLogging;
	private boolean summaryReported;
	private boolean selectionUseHeld;
	private LootSorterState pausedResumeState;
	private SlotTransfer slotTransfer;
	private SlotTransfer lastFinishedSlotTransfer;
	private static final long CLIENT_PREDICTION_SETTLE_MS = 25;
	/**
	 * Normal clicks are asynchronous. Keep a completed cursor transaction alive
	 * briefly so a delayed server correction cannot strand the next stack on
	 * the cursor (especially when a hopper changes the chest at the same time).
	 */
	private static final long SLOT_TRANSFER_SETTLE_MS = 75;
	private static final long STALLED_TICK_GAP_MS = 750;
	private static final int MAX_OPEN_RETRIES = 1;
	private static final long PATH_STALL_MS = 4500;
	private static final int MAX_OFF_PATH_TICKS = 80;
	private static final int MAX_PATH_RECOVERY_ATTEMPTS = 3;
	private static final double POSITION_CORRECTION_SQR = 0.1 * 0.1;
	private static final double NAVIGATION_REPLAN_SQR = 2.0 * 2.0;
	private long automatedInputUntil;
	private int openingRetries;
	private long lastControllerTickAt;
	private Vec3 pathProgressPosition;
	private long pathProgressAt;
	private int pathRecoveryAttempts;
	private boolean sourceContentsPublished;
	private boolean directEverythingMove;
	private boolean positionCorrectionPending;
	
	public LootSorterController(Minecraft mc,
		Supplier<ItemFilter> selectedFilter,
		Consumer<DestinationRule> destinationEditor,
		Consumer<List<SourceContentsSnapshot>> sourceContentsSaver)
	{
		this.mc = mc;
		this.interactions = new ContainerInteractionController(mc);
		this.selectedFilter = selectedFilter;
		this.destinationEditor = destinationEditor;
		this.sourceContentsSaver = sourceContentsSaver;
	}
	
	public void begin()
	{
		if(!prepareNewSelection())
			return;
		transition(LootSorterState.SELECTING_SOURCES);
		message("right-click source containers; press Enter to confirm.");
	}
	
	/**
	 * Starts a standalone destination selection. This intentionally does not
	 * require sources and never starts a sorting run by itself.
	 */
	public void beginDestinationSelection()
	{
		if(!prepareNewSelection())
			return;
		transition(LootSorterState.SELECTING_DESTINATIONS);
		message("right-click destinations to configure; press Enter to save.");
	}
	
	private boolean prepareNewSelection()
	{
		if(mc.player == null || mc.level == null)
		{
			error("join a world before selecting containers.");
			return false;
		}
		sources.clear();
		destinations.clear();
		scannedSources.clear();
		completedSources.clear();
		unmatchedSources.clear();
		unreachableSources.clear();
		unloadedContainers.clear();
		warnedFullDestinations.clear();
		warnedUnreachable.clear();
		activeSource = null;
		activeDestination = null;
		sourceIndex =
			scanIndex = destinationIndex = movedItems = containerTrips = 0;
		consecutiveFailures = 0;
		activeRoute = null;
		slotTransfer = null;
		lastFinishedSlotTransfer = null;
		sourceContentsPublished = false;
		directEverythingMove = false;
		positionCorrectionPending = false;
		returningRemainder = false;
		summaryReported = false;
		selectionUseHeld = false;
		pausedResumeState = null;
		openingRetries = 0;
		lastControllerTickAt = System.currentTimeMillis();
		runDimension = mc.level.dimension().identifier().toString();
		lastPlayerPosition = mc.player.position();
		return true;
	}
	
	public void stop(String reason)
	{
		LootSorterState stoppedState = state;
		persistSourceContents();
		pathFinder = null;
		processor = null;
		pending = null;
		slotTransfer = null;
		lastFinishedSlotTransfer = null;
		directEverythingMove = false;
		positionCorrectionPending = false;
		PathProcessor.releaseControls();
		if(interactions.hasCarriedStack())
		{
			transition(LootSorterState.ERROR);
			error(
				"stopped with a cursor stack. Resolve the cursor manually; no items were dropped.");
			reportSummary("error");
			return;
		}else
			interactions.close();
		transition(LootSorterState.DISABLED);
		if(reason != null && !reason.isBlank())
		{
			message(reason);
			reportSummary("manually cancelled");
		}else if(stoppedState == LootSorterState.ERROR)
			reportSummary("error");
		else if(stoppedState != LootSorterState.COMPLETED
			&& stoppedState != LootSorterState.DISABLED
			&& stoppedState != LootSorterState.SELECTING_SOURCES
			&& stoppedState != LootSorterState.SELECTING_DESTINATIONS
			&& stoppedState != LootSorterState.PAUSED)
			reportSummary("manually cancelled");
	}
	
	public void onRightClick(RightClickEvent event)
	{
		if(state != LootSorterState.SELECTING_SOURCES
			&& state != LootSorterState.SELECTING_DESTINATIONS)
			return;
		if(!(mc.hitResult instanceof BlockHitResult hit))
			return;
		LogicalContainer container =
			LogicalContainer.fromTarget(mc.level, hit.getBlockPos());
		if(container == null)
			return;
		event.cancel();
		if(selectionUseHeld)
			return;
		selectionUseHeld = true;
		if(state == LootSorterState.SELECTING_SOURCES)
		{
			if(destinations.stream()
				.anyMatch(rule -> rule.getContainer().equals(container)))
			{
				error("a container cannot be both source and destination.");
				return;
			}
			if(sources.remove(container))
				message("removed source " + container.anchor().toShortString());
			else
			{
				sources.add(container);
				message(
					"selected source " + container.anchor().toShortString());
			}
			// A cache belongs to one exact source layout only.
			scannedSources.clear();
			sourceContentsPublished = false;
			return;
		}
		DestinationRule existing = destinations.stream()
			.filter(rule -> rule.getContainer().equals(container)).findFirst()
			.orElse(null);
		if(existing != null)
		{
			if(mc.player.isShiftKeyDown())
			{
				removeDestination(existing);
				message("removed destination "
					+ container.anchor().toShortString());
				return;
			}
			destinationEditor.accept(existing);
			return;
		}
		if(sources.contains(container))
		{
			error("a container cannot be both source and destination.");
			return;
		}
		DestinationRule rule =
			new DestinationRule(container, destinations.size());
		rule.addFilter(selectedFilter.get());
		destinations.add(rule);
		destinationEditor.accept(rule);
	}
	
	public void onKeyPress(KeyPressEvent event)
	{
		if(event.getAction() != GLFW.GLFW_PRESS)
			return;
		// Chat and ClickGUI keystrokes belong to the active screen. In
		// particular, Enter submits a command and Shift may capitalise its
		// preset name; neither is a LootSorter control while a screen is open.
		if(mc.gui.screen() != null)
			return;
		if(event.getKeyCode() == GLFW.GLFW_KEY_RIGHT_CONTROL
			|| event.getKeyCode() == GLFW.GLFW_KEY_RIGHT_SHIFT)
		{
			stop("safe stop requested");
			return;
		}
		if(event.getKeyCode() != GLFW.GLFW_KEY_ENTER)
			return;
		if(state == LootSorterState.SELECTING_SOURCES)
		{
			if(sources.isEmpty())
				error("select at least one source.");
			else
			{
				transition(LootSorterState.SELECTING_DESTINATIONS);
				message(
					"right-click destinations to configure; sneak-right-click "
						+ "one to remove it; press Enter to start.");
			}
		}else if(state == LootSorterState.SELECTING_DESTINATIONS)
			startSorting(true);
		else if(state == LootSorterState.PAUSED && pausedResumeState != null)
		{
			if(interactions.getSupportedScreen() != null)
			{
				message(
					"close the current container, then press Enter to resume.");
				return;
			}
			LootSorterState resume = pausedResumeState;
			pausedResumeState = null;
			transition(resume);
		}else if(state == LootSorterState.PAUSED)
			startSorting(true);
	}
	
	/**
	 * Confirms the destination phase. This is shared by the global Enter key
	 * and the destination editor's explicit final-action button.
	 */
	public void confirmDestinationSelection()
	{
		startSorting(true);
	}
	
	/**
	 * Starts a confirmed layout, either from a complete cache or a fresh scan.
	 */
	public boolean startSorting(boolean recheckSources)
	{
		if((state != LootSorterState.SELECTING_DESTINATIONS
			&& state != LootSorterState.PAUSED) || mc.player == null)
			return false;
		if(destinations.stream().noneMatch(DestinationRule::isConfigured))
		{
			error("select at least one destination.");
			return false;
		}
		ledger.snapshot(mc.player.getInventory());
		sourceIndex = destinationIndex = 0;
		directEverythingMove = false;
		DestinationRule everythingDestination = findEverythingDestination();
		if(everythingDestination != null)
		{
			// "Everything" is a move operation, not a categorisation pass. Open
			// each source only when it is time to withdraw from it; do not make
			// a
			// separate full-layout scan first.
			directEverythingMove = true;
			activeRoute = null;
			activeDestination = everythingDestination;
			destinationIndex = destinations.indexOf(everythingDestination);
			scannedSources.clear();
			scanIndex = 0;
			sourceContentsPublished = false;
			if(startNextDirectEverythingSource())
			{
				message(
					"Everything filter: collecting sources directly without "
						+ "a separate rescan.");
				return true;
			}
			directEverythingMove = false;
		}
		if(!recheckSources && hasSavedSourceContents())
		{
			if(hasCompleteSourceContents())
			{
				transition(LootSorterState.PLANNING);
				message("using saved source contents.");
			}else
			{
				scanIndex = 0;
				transition(LootSorterState.RESCANNING);
				message("using saved source contents and scanning only new "
					+ "sources.");
			}
			return true;
		}
		scannedSources.clear();
		scanIndex = 0;
		sourceContentsPublished = false;
		transition(LootSorterState.RESCANNING);
		return true;
	}
	
	/** Whether every selected source has a saved scan, including empty ones. */
	public boolean hasCompleteSourceContents()
	{
		return !sources.isEmpty()
			&& scannedSources.keySet().containsAll(sources);
	}
	
	/** Whether this layout has at least one source cached from a prior run. */
	public boolean hasSavedSourceContents()
	{
		return !scannedSources.isEmpty();
	}
	
	public boolean hasConfiguredDestinations()
	{
		return destinations.stream().anyMatch(DestinationRule::isConfigured);
	}
	
	/**
	 * Replaces the restored destination layout while retaining selected sources
	 * and any source-content cache. This lets a resumed job use a fresh set of
	 * targets without forcing another source selection or scan.
	 */
	public boolean beginDestinationReplacement()
	{
		if((state != LootSorterState.SELECTING_DESTINATIONS
			&& state != LootSorterState.PAUSED) || mc.player == null)
			return false;
		destinations.clear();
		warnedFullDestinations.clear();
		activeDestination = null;
		destinationIndex = 0;
		directEverythingMove = false;
		transition(LootSorterState.SELECTING_DESTINATIONS);
		message("right-click destinations to configure; sneak-right-click one "
			+ "to remove it; press Enter to start.");
		return true;
	}
	
	/**
	 * Moves a source preset directly into destination selection. It is kept
	 * separate from {@link #onKeyPress(KeyPressEvent)} so command loading has
	 * exactly the same validation as pressing Enter.
	 */
	public boolean confirmLoadedSources()
	{
		if(state != LootSorterState.SELECTING_SOURCES || sources.isEmpty())
			return false;
		transition(LootSorterState.SELECTING_DESTINATIONS);
		message("right-click destinations to configure; sneak-right-click one "
			+ "to remove it; press Enter to start.");
		return true;
	}
	
	/**
	 * Starts an explicitly accepted restored layout without auto-running it.
	 */
	public void continueRestoredLayout()
	{
		startSorting(false);
	}
	
	public void onManualInventoryInput()
	{
		if(System.currentTimeMillis() <= automatedInputUntil)
			return;
		if(state == LootSorterState.SELECTING_SOURCES
			|| state == LootSorterState.SELECTING_DESTINATIONS
			|| state == LootSorterState.DISABLED)
			return;
		if(interactions.getSupportedScreen() != null)
		{
			PathProcessor.releaseControls();
			pathFinder = null;
			processor = null;
			pending = null;
			activeRoute = null;
			returningRemainder = false;
			closingAfterDeposit = false;
			scannedSources.clear();
			scanIndex = 0;
			pausedResumeState = LootSorterState.RESCANNING;
			transition(LootSorterState.PAUSED);
			message(
				"manual container input: paused; close the screen and press "
					+ "Enter to rescan.");
		}
	}
	
	public void tick(long navigationTimeoutMs, long interactionTimeoutMs,
		long actionDelayMs, int maximumFailures,
		boolean automaticallyCloseScreens, boolean chatNotifications,
		boolean fullCompletionSummary, boolean debugLogging)
	{
		this.maximumFailures = Math.max(1, maximumFailures);
		this.automaticallyCloseScreens = automaticallyCloseScreens;
		this.chatNotifications = chatNotifications;
		this.fullCompletionSummary = fullCompletionSummary;
		this.debugLogging = debugLogging;
		if(state == LootSorterState.DISABLED)
			return;
		compensateForStalledTicks();
		if(!mc.options.keyUse.isDown())
			selectionUseHeld = false;
		if(mc.player == null || mc.level == null)
		{
			stop("world disconnected or unloaded");
			return;
		}
		if(!mc.player.isAlive())
		{
			stop("player died");
			return;
		}
		if(runDimension != null && !runDimension
			.equals(mc.level.dimension().identifier().toString()))
		{
			stop("dimension changed");
			return;
		}
		Vec3 currentPlayerPosition = mc.player.position();
		if(isAutomating() && positionCorrectionPending && pending == null
			&& slotTransfer == null)
		{
			positionCorrectionPending = false;
			correctForPlayerMovement(currentPlayerPosition);
			return;
		}
		if(lastPlayerPosition != null && isAutomating())
		{
			double moved =
				currentPlayerPosition.distanceToSqr(lastPlayerPosition);
			boolean navigating = state == LootSorterState.NAVIGATING_TO_SOURCE
				|| state == LootSorterState.NAVIGATING_TO_DESTINATION;
			// A path can settle by a fraction of a block just after it reaches
			// a
			// container. That is normal physics, not a player correction. Once
			// the container is open/active, small player movement is still a
			// correction and causes a safe replan.
			boolean opening = state == LootSorterState.OPENING_SOURCE
				|| state == LootSorterState.OPENING_DESTINATION;
			boolean closing = state == LootSorterState.CLOSING_SOURCE;
			boolean containerOpen = interactions.getSupportedScreen() != null;
			if((!navigating && !opening && !closing && !containerOpen
				&& moved > POSITION_CORRECTION_SQR)
				|| ((navigating || opening || closing || containerOpen)
					&& moved > NAVIGATION_REPLAN_SQR))
			{
				lastPlayerPosition = currentPlayerPosition;
				if(pending != null || slotTransfer != null)
				{
					// Completing a normal click is safer than closing a screen
					// with
					// an in-flight cursor stack. Replan immediately afterwards.
					positionCorrectionPending = true;
					debug("player movement detected; correction deferred until "
						+ "the current item transfer is confirmed.");
					return;
				}
				correctForPlayerMovement(currentPlayerPosition);
				return;
			}
		}
		lastPlayerPosition = currentPlayerPosition;
		if(interactions.hasCarriedStack() && slotTransfer == null)
		{
			if(recoverLateCursorTransfer())
				return;
			stop("cursor stack became occupied");
			return;
		}
		if(slotTransfer != null)
		{
			tickSlotTransfer(interactionTimeoutMs);
			return;
		}
		switch(state)
		{
			case RESCANNING -> scanNextSource();
			case PLANNING -> planNextRoute();
			case NAVIGATING_TO_SOURCE, NAVIGATING_TO_DESTINATION -> tickPath(
				navigationTimeoutMs);
			case OPENING_SOURCE, OPENING_DESTINATION -> tickOpening(
				interactionTimeoutMs);
			case WITHDRAWING -> tickWithdraw(interactionTimeoutMs,
				actionDelayMs);
			case CLOSING_SOURCE -> tickClosingSource(interactionTimeoutMs);
			case DEPOSITING -> tickDeposit(interactionTimeoutMs, actionDelayMs);
			case RETURNING_REMAINDER -> tickReturnRemainder(
				interactionTimeoutMs, actionDelayMs);
			default ->
				{
				}
		}
	}
	
	/**
	 * Treats a player/server position correction as a new start point rather
	 * than cancelling an active sort. Inventory transfers are never discarded;
	 * an open supported screen is closed normally before the path is rebuilt.
	 */
	private void correctForPlayerMovement(Vec3 correctedPosition)
	{
		if(state == LootSorterState.CLOSING_SOURCE)
		{
			PathProcessor.releaseControls();
			interactions.close();
			lastPlayerPosition = correctedPosition;
			debug("player movement corrected while closing a container.");
			return;
		}
		boolean destination = state == LootSorterState.NAVIGATING_TO_DESTINATION
			|| state == LootSorterState.OPENING_DESTINATION
			|| state == LootSorterState.DEPOSITING;
		LogicalContainer expected = destination
			? activeDestination == null ? null
				: activeDestination.getContainer()
			: state == LootSorterState.NAVIGATING_TO_SOURCE
				|| state == LootSorterState.OPENING_SOURCE
				|| state == LootSorterState.WITHDRAWING
				|| state == LootSorterState.RETURNING_REMAINDER ? activeSource
					: null;
		if(expected == null)
		{
			lastPlayerPosition = correctedPosition;
			debug("player movement accepted; the next planning step will use "
				+ "the corrected position.");
			return;
		}
		PathProcessor.releaseControls();
		pathFinder = null;
		processor = null;
		if(interactions.getSupportedScreen() != null)
			interactions.close();
		lastPlayerPosition = correctedPosition;
		debug("player movement corrected; replanning path to "
			+ expected.anchor().toShortString());
		startPath(expected.anchor(),
			destination ? LootSorterState.NAVIGATING_TO_DESTINATION
				: LootSorterState.NAVIGATING_TO_SOURCE);
	}
	
	private void scanNextSource()
	{
		if(scanIndex >= sources.size())
		{
			publishSourceContents();
			transition(LootSorterState.PLANNING);
			return;
		}
		activeSource = sources.get(scanIndex);
		if(scannedSources.containsKey(activeSource)
			|| unreachableSources.contains(activeSource))
		{
			scanIndex++;
			return;
		}
		if(!mc.level.hasChunkAt(activeSource.anchor()))
		{
			unloadedContainers.add(activeSource);
			scanIndex++;
			return;
		}
		startPath(activeSource.anchor(), LootSorterState.NAVIGATING_TO_SOURCE);
	}
	
	private void planNextRoute()
	{
		activeRoute = planner.plan(mc.player.position(), scannedSources,
			destinations, sources);
		if(activeRoute == null)
		{
			updateSourceVisualStates();
			if(hasStrandedMatchingItems())
			{
				transition(LootSorterState.ERROR);
				error(
					"matching source items remain, but every destination that "
						+ "accepts them is unavailable or full. The carried items were "
						+ "returned to their source; no items were put into a source as "
						+ "a destination.");
				return;
			}
			transition(LootSorterState.COMPLETED);
			reportSummary("normal completion");
			return;
		}
		activeSource = activeRoute.source();
		activeDestination = activeRoute.destination();
		debug("route selected: " + activeRoute.sourceItemCount()
			+ " matching items from " + activeRoute.sourceItemKeys().size()
			+ " source container"
			+ (activeRoute.sourceItemKeys().size() == 1 ? "" : "s")
			+ ", starting at " + activeSource.anchor().toShortString() + " to "
			+ activeDestination.getContainer().anchor().toShortString()
			+ " via "
			+ activeDestination.getFilters().stream()
				.filter(filter -> filter.matches(findRouteSourceStack()))
				.map(ItemFilter::getDisplayName).findFirst()
				.orElse("configured filter"));
		startPath(activeSource.anchor(), LootSorterState.NAVIGATING_TO_SOURCE);
	}
	
	/** A bare Everything filter is the explicit direct-move mode. */
	private DestinationRule findEverythingDestination()
	{
		return destinations.stream().filter(DestinationRule::isConfigured)
			.filter(rule -> rule.getFilters().size() == 1
				&& rule.getFilters().get(0) == BuiltInItemFilter.ALL)
			.findFirst().orElse(null);
	}
	
	/**
	 * Chooses the next unvisited source, or a previously full source with a
	 * recorded remainder, for direct Everything movement.
	 */
	private boolean startNextDirectEverythingSource()
	{
		if(!directEverythingMove)
			return false;
		for(LogicalContainer source : sources)
		{
			if(unreachableSources.contains(source))
				continue;
			List<ItemStack> contents = scannedSources.get(source);
			if(contents != null && contents.isEmpty())
				continue;
			activeSource = source;
			startPath(activeSource.anchor(),
				LootSorterState.NAVIGATING_TO_SOURCE);
			return true;
		}
		return false;
	}
	
	private void startPath(BlockPos container, LootSorterState next)
	{
		LogicalContainer expected =
			next == LootSorterState.NAVIGATING_TO_DESTINATION
				? activeDestination == null ? null
					: activeDestination.getContainer()
				: activeSource;
		if(expected != null && !mc.level.hasChunkAt(expected.anchor()))
		{
			unloadedContainers.add(expected);
			if(returningRemainder)
			{
				transition(LootSorterState.ERROR);
				error(
					"the original source chunk unloaded while carrying movable items.");
				return;
			}
			if(next == LootSorterState.NAVIGATING_TO_DESTINATION)
				markActiveDestinationUnavailable(false);
			else
				markActiveSourceUnreachable();
			return;
		}
		if(expected == null || !expected.isStillSupported(mc.level))
		{
			if(returningRemainder)
			{
				transition(LootSorterState.ERROR);
				error(
					"the original source was removed while carrying movable items.");
				return;
			}
			if(next == LootSorterState.NAVIGATING_TO_DESTINATION)
				markActiveDestinationUnavailable(true);
			else
				markActiveSourceUnreachable();
			return;
		}
		// The generic PathFinder requires its goal block to be a walkable cell.
		// A container is not walkable, and picking one neighbouring cell here
		// made
		// a nearby chest look unreachable when that particular side was
		// blocked.
		// This finder instead completes at any normal walking cell from which
		// the
		// actual container is within vanilla interaction range.
		pathGoal = container;
		pathFinder = new ContainerPathFinder(container);
		processor = null;
		pathProgressPosition = mc.player.position();
		pathProgressAt = System.currentTimeMillis();
		pathRecoveryAttempts = 0;
		debug("path requested: " + next + " -> " + pathGoal.toShortString());
		transition(next);
	}
	
	private void tickPath(long timeoutMs)
	{
		LogicalContainer expected =
			state == LootSorterState.NAVIGATING_TO_SOURCE ? activeSource
				: activeDestination == null ? null
					: activeDestination.getContainer();
		if(expected == null || !mc.level.hasChunkAt(expected.anchor()))
		{
			if(returningRemainder)
			{
				transition(LootSorterState.ERROR);
				error(
					"the return destination became unavailable while carrying items.");
				return;
			}
			PathProcessor.releaseControls();
			if(state == LootSorterState.NAVIGATING_TO_DESTINATION)
			{
				if(expected != null)
					unloadedContainers.add(expected);
				markActiveDestinationUnavailable(false);
			}else
				markActiveSourceUnreachable();
			return;
		}
		if(System.currentTimeMillis() - stateStarted > timeoutMs)
		{
			if(returningRemainder)
			{
				transition(LootSorterState.ERROR);
				error("timed out returning carried items to the source.");
				return;
			}
			markActiveUnreachable();
			return;
		}
		if(!pathFinder.isDone() && !pathFinder.isFailed())
		{
			PathProcessor.lockControls();
			pathFinder.think();
			if(!pathFinder.isDone() && !pathFinder.isFailed())
				return;
			pathFinder.formatPath();
			processor = pathFinder.getProcessor();
		}
		if(pathFinder.isFailed() || processor == null)
		{
			if(returningRemainder)
			{
				transition(LootSorterState.ERROR);
				error("no path back to the original source.");
				return;
			}
			markActiveUnreachable();
			return;
		}
		if(isPathStalled())
		{
			recoverStalledPath();
			return;
		}
		processor.process();
		if(!processor.isDone())
			return;
		PathProcessor.releaseControls();
		containerTrips++;
		debug("path arrived at " + pathGoal.toShortString());
		lastPlayerPosition = mc.player.position();
		BlockPos target = state == LootSorterState.NAVIGATING_TO_SOURCE
			? activeSource.anchor() : activeDestination.getContainer().anchor();
		expectAutomatedInput();
		BlockHitResult interaction =
			interactionHitResult(target, mc.player.getEyePosition(1.0F));
		if(interaction == null)
		{
			recoverStalledPath();
			return;
		}
		InteractionSimulator.rightClickBlock(interaction,
			InteractionHand.MAIN_HAND);
		transition(state == LootSorterState.NAVIGATING_TO_SOURCE
			? LootSorterState.OPENING_SOURCE
			: LootSorterState.OPENING_DESTINATION);
	}
	
	private boolean isPathStalled()
	{
		long now = System.currentTimeMillis();
		Vec3 position = mc.player.position();
		if(pathProgressPosition == null
			|| position.distanceToSqr(pathProgressPosition) > 0.25 * 0.25)
		{
			pathProgressPosition = position;
			pathProgressAt = now;
			return false;
		}
		return now - pathProgressAt > PATH_STALL_MS
			|| processor.getTicksOffPath() >= MAX_OFF_PATH_TICKS;
	}
	
	/**
	 * Rebuilds a no-progress walking path before treating its target as lost.
	 */
	private void recoverStalledPath()
	{
		if(++pathRecoveryAttempts > MAX_PATH_RECOVERY_ATTEMPTS)
		{
			debug("path recovery exhausted after " + MAX_PATH_RECOVERY_ATTEMPTS
				+ " attempts");
			markActiveUnreachable();
			return;
		}
		PathProcessor.releaseControls();
		pathFinder = new ContainerPathFinder(pathGoal);
		processor = null;
		stateStarted = System.currentTimeMillis();
		pathProgressPosition = mc.player.position();
		pathProgressAt = stateStarted;
		debug("path made no progress; rebuilding route attempt "
			+ pathRecoveryAttempts + "/" + MAX_PATH_RECOVERY_ATTEMPTS);
	}
	
	private void tickOpening(long timeoutMs)
	{
		if(interactions.getSupportedScreen() != null)
		{
			openingRetries = 0;
			// This is a confirmed successful interaction. Failures are only
			// consecutive when no container can be opened between them; a large
			// layout must not stop after four unrelated blocked chests.
			consecutiveFailures = 0;
			debug("opened expected "
				+ (state == LootSorterState.OPENING_SOURCE ? "source"
					: "destination")
				+ " screen at revision " + interactions.getRevision());
			if(state == LootSorterState.OPENING_SOURCE && returningRemainder)
				transition(LootSorterState.RETURNING_REMAINDER);
			else if(state == LootSorterState.OPENING_SOURCE
				&& activeRoute == null && !directEverythingMove)
				scanOpenedSource();
			else
				transition(state == LootSorterState.OPENING_SOURCE
					? LootSorterState.WITHDRAWING : LootSorterState.DEPOSITING);
			return;
		}
		if(mc.gui.screen() != null)
		{
			if(returningRemainder)
			{
				transition(LootSorterState.ERROR);
				error(
					"the server opened an unexpected screen while returning items.");
				return;
			}
			markActiveUnreachable();
			return;
		}
		if(System.currentTimeMillis() - stateStarted > timeoutMs)
		{
			if(returningRemainder)
			{
				transition(LootSorterState.ERROR);
				error(
					"timed out reopening the original source for carried items.");
				return;
			}
			if(openingRetries++ < MAX_OPEN_RETRIES)
			{
				debug(
					"container screen did not stay open; retrying interaction.");
				BlockPos target = state == LootSorterState.OPENING_SOURCE
					? activeSource.anchor()
					: activeDestination.getContainer().anchor();
				startPath(target,
					state == LootSorterState.OPENING_SOURCE
						? LootSorterState.NAVIGATING_TO_SOURCE
						: LootSorterState.NAVIGATING_TO_DESTINATION);
				return;
			}
			markActiveUnreachable();
		}
	}
	
	private void tickWithdraw(long timeoutMs, long delayMs)
	{
		if(confirmPending(timeoutMs)
			&& (state != LootSorterState.WITHDRAWING || pending != null))
			return;
		if(pending != null || System.currentTimeMillis() - lastAction < delayMs)
			return;
		var screen = interactions.getSupportedScreen();
		if(screen == null)
		{
			reopenContainerAfterScreenClosed(
				LootSorterState.NAVIGATING_TO_SOURCE);
			return;
		}
		List<Slot> candidates = screen.getMenu().slots.stream()
			.filter(slot -> !interactions.isPlayerSlot(slot)
				&& !slot.getItem().isEmpty()
				&& matchesActiveSourceItem(slot.getItem()))
			.sorted((a, b) -> Integer.compare(b.getItem().getCount(),
				a.getItem().getCount()))
			.toList();
		for(Slot slot : candidates)
		{
			Slot target =
				findWithdrawalTarget(screen.getMenu().slots, slot.getItem());
			if(target == null)
				break;
			// Servers can reject or delay quick-move even when its client-side
			// target prediction looks valid. One normal pickup transfer keeps
			// the
			// stack's origin explicit and gives us an unambiguous confirmation
			// for
			// every source withdrawal.
			if(!startSlotTransfer(slot, target, true))
			{
				transition(LootSorterState.ERROR);
				error(
					"could not start a safe source-to-inventory slot transfer.");
				return;
			}
			return;
		}
		if(candidates.isEmpty() && getMovableRouteItemCount() <= 0)
		{
			updateActiveSourceContents(screen);
			activeRoute = null;
			closingAfterDeposit = false;
			closeOrPause(directEverythingMove ? LootSorterState.CLOSING_SOURCE
				: LootSorterState.PLANNING);
			return;
		}
		if(!candidates.isEmpty() && getMovableRouteItemCount() <= 0)
		{
			transition(LootSorterState.ERROR);
			error(
				"no safe inventory capacity is available for the next loot stack.");
			return;
		}
		// The cache started this visit, but the live screen is authoritative.
		// Capture it after all confirmed withdrawals so later route planning
		// never
		// relies on a stale amount or an externally changed source container.
		updateActiveSourceContents(screen);
		closingAfterDeposit = false;
		closeOrPause(LootSorterState.CLOSING_SOURCE);
		return;
	}
	
	private boolean matchesActiveSourceItem(ItemStack stack)
	{
		if(activeDestination == null || !activeDestination.matches(stack))
			return false;
		return directEverythingMove
			|| activeRoute != null && activeRoute.itemKeysFor(activeSource)
				.contains(ItemStackEquivalenceKey.of(stack));
	}
	
	private void tickDeposit(long timeoutMs, long delayMs)
	{
		if(confirmPending(timeoutMs)
			&& (state != LootSorterState.DEPOSITING || pending != null))
			return;
		if(pending != null || System.currentTimeMillis() - lastAction < delayMs)
			return;
		var screen = interactions.getSupportedScreen();
		if(screen == null)
		{
			reopenContainerAfterScreenClosed(
				LootSorterState.NAVIGATING_TO_DESTINATION);
			return;
		}
		Slot source = findMovableRouteSlot(screen.getMenu().slots, true);
		if(source != null)
		{
			Slot target =
				findDepositTarget(screen.getMenu().slots, source.getItem());
			if(target != null)
			{
				int requested = source.getItem().getCount();
				// The source is a wholly movable player stack. A normal
				// quick-move
				// is therefore safe here and is deliberately preferable to a
				// cursor transfer: it fills every available matching chest slot
				// in
				// one action. A hopper removing items after the click cannot
				// affect
				// confirmation, which is based solely on the player inventory.
				pending = PendingAction.deposit(source.getItem(),
					countInventory(source.getItem()),
					interactions.getRevision());
				expectAutomatedInput();
				if(!interactions.moveAsMuchAsPossible(source, target))
				{
					pending = null;
					transition(LootSorterState.ERROR);
					error(
						"could not complete a safe inventory-to-destination slot transfer.");
					return;
				}
				debug("depositing up to " + requested + " movable items");
				return;
			}
		}
		if(findMovableRouteStack() != null)
		{
			activeDestination.setFull(true);
			warnFullDestination("has no safe slot for the matching item");
		}
		closingAfterDeposit = true;
		closeOrPause(LootSorterState.CLOSING_SOURCE);
		return;
	}
	
	private void tickClosingSource(long timeoutMs)
	{
		if(mc.gui.screen() != null)
		{
			if(System.currentTimeMillis() - stateStarted <= timeoutMs)
				return;
			transition(LootSorterState.ERROR);
			error("container screen did not close before navigation.");
			return;
		}
		if(directEverythingMove)
		{
			tickClosingDirectEverything();
			return;
		}
		if(activeRoute == null)
		{
			transition(LootSorterState.RESCANNING);
			return;
		}
		if(!closingAfterDeposit)
		{
			if(getMovableRouteItemCount() <= 0)
			{
				activeRoute = null;
				transition(LootSorterState.PLANNING);
				return;
			}
			LogicalContainer nextSource = findNextPickupSource();
			if(nextSource != null)
			{
				activeSource = nextSource;
				startPath(activeSource.anchor(),
					LootSorterState.NAVIGATING_TO_SOURCE);
				return;
			}
			destinationIndex = destinations.indexOf(activeDestination);
			startPath(activeDestination.getContainer().anchor(),
				LootSorterState.NAVIGATING_TO_DESTINATION);
			return;
		}
		if(getMovableRouteItemCount() <= 0)
		{
			activeRoute = null;
			transition(LootSorterState.PLANNING);
			return;
		}
		continueDeliveryOrReturn();
	}
	
	private void tickClosingDirectEverything()
	{
		int carried = getMovableRouteItemCount();
		if(closingAfterDeposit)
		{
			if(carried > 0)
			{
				continueDirectEverythingDeliveryOrReturn();
				return;
			}
			if(startNextDirectEverythingSource())
				return;
			directEverythingMove = false;
			transition(LootSorterState.PLANNING);
			return;
		}
		if(carried <= 0)
		{
			if(startNextDirectEverythingSource())
				return;
			directEverythingMove = false;
			transition(LootSorterState.PLANNING);
			return;
		}
		// Keep filling one inventory load when the source was drained and a
		// normal storage slot remains. If the current source still has a
		// remainder, empty the carried load first and revisit it afterwards.
		if(isActiveSourceDrained() && hasVacantStorageSlot()
			&& startNextDirectEverythingSource())
			return;
		startPath(activeDestination.getContainer().anchor(),
			LootSorterState.NAVIGATING_TO_DESTINATION);
	}
	
	private boolean isActiveSourceDrained()
	{
		return activeSource != null
			&& scannedSources.getOrDefault(activeSource, List.of()).isEmpty();
	}
	
	private boolean hasVacantStorageSlot()
	{
		if(mc.player == null)
			return false;
		for(int i = 0; i < PLAYER_STORAGE_SLOT_COUNT; i++)
			if(mc.player.getInventory().getItem(i).isEmpty())
				return true;
		return false;
	}
	
	private void continueDirectEverythingDeliveryOrReturn()
	{
		ItemStack stack = findMovableRouteStack();
		if(stack == null)
		{
			transition(LootSorterState.ERROR);
			error("movable inventory no longer contains a complete stack.");
			return;
		}
		while(++destinationIndex < destinations.size())
		{
			activeDestination = destinations.get(destinationIndex);
			if(activeDestination.matches(stack))
			{
				startPath(activeDestination.getContainer().anchor(),
					LootSorterState.NAVIGATING_TO_DESTINATION);
				return;
			}
		}
		// No alternate receiver remains. Return the carried stack safely, then
		// let normal planning report the incomplete route rather than declaring
		// a successful Everything move.
		returningRemainder = true;
		directEverythingMove = false;
		startPath(activeSource.anchor(), LootSorterState.NAVIGATING_TO_SOURCE);
	}
	
	private void tickReturnRemainder(long timeoutMs, long delayMs)
	{
		if(confirmPending(timeoutMs)
			&& (state != LootSorterState.RETURNING_REMAINDER
				|| pending != null))
			return;
		if(pending != null || System.currentTimeMillis() - lastAction < delayMs)
			return;
		var screen = interactions.getSupportedScreen();
		if(screen == null)
		{
			reopenContainerAfterScreenClosed(
				LootSorterState.NAVIGATING_TO_SOURCE);
			return;
		}
		Slot source = findMovableRouteSlot(screen.getMenu().slots, false);
		if(source != null)
		{
			Slot target =
				findDepositTarget(screen.getMenu().slots, source.getItem());
			if(target != null)
			{
				if(!startSlotTransfer(source, target, false))
				{
					transition(LootSorterState.ERROR);
					error(
						"could not safely return the carried stack to its source.");
					return;
				}
				return;
			}
		}
		if(getMovableRouteItemCount() > 0)
		{
			transition(LootSorterState.ERROR);
			error(
				"remainder cannot be returned without touching protected inventory.");
			return;
		}
		// The source was opened and may have received a safety return, so make
		// the persisted cache reflect its live post-return contents as well.
		updateActiveSourceContents(screen);
		returningRemainder = false;
		activeRoute = null;
		closeOrPause(LootSorterState.PLANNING);
	}
	
	private boolean confirmPending(long timeoutMs)
	{
		if(pending == null)
			return false;
		int now = countInventory(pending.stack);
		int revision = interactions.getRevision();
		int moved = pending.withdrawal ? now - pending.inventoryBefore
			: pending.inventoryBefore - now;
		boolean revisionChanged = revision != pending.revisionBefore;
		boolean predictionSettled = System.currentTimeMillis()
			- pending.issuedAt >= CLIENT_PREDICTION_SETTLE_MS;
		if(moved > 0 && (revisionChanged || predictionSettled))
		{
			consecutiveFailures = 0;
			if(pending.withdrawal)
			{
				ledger.confirmWithdrawal(pending.stack, moved);
				removeScannedAmount(activeSource, pending.stack, moved);
			}else
			{
				ledger.confirmDeposit(pending.stack, moved);
				if(returningRemainder)
					restoreScannedAmount(activeSource, pending.stack, moved);
				else if(state == LootSorterState.DEPOSITING)
					movedItems += moved;
			}
			debug((pending.withdrawal ? "withdrawal" : "deposit")
				+ " confirmed: " + moved + " items, revision " + revision
				+ (revisionChanged ? "" : " (stable inventory update)"));
			lastAction = System.currentTimeMillis();
			pending = null;
			return true;
		}
		if(revisionChanged && moved == 0)
		{
			if(!pending.withdrawal && !returningRemainder)
			{
				// Hoppers and other container automation can alter the menu
				// between
				// our capacity check and the quick-move click. A changed
				// revision
				// with no player-inventory movement is not proof that the chest
				// is
				// full; retry against its current slots. A genuinely full chest
				// is
				// still detected by findDepositTarget() on the next deposit
				// step.
				debug(
					"destination changed without moving the stack at revision "
						+ revision
						+ "; refreshing its capacity before retrying");
				pending = null;
				return true;
			}
			transition(LootSorterState.ERROR);
			error(pending.withdrawal
				? "source inventory changed without confirming withdrawal."
				: returningRemainder
					? "source inventory changed while returning movable items."
					: "destination inventory changed without confirming deposit.");
			return true;
		}
		if(System.currentTimeMillis() - pending.issuedAt > timeoutMs)
		{
			if(!pending.withdrawal && !returningRemainder)
			{
				activeDestination.setFull(true);
				warnFullDestination("cannot accept another matching stack");
				pending = null;
				return true;
			}
			transition(LootSorterState.ERROR);
			error(pending.withdrawal
				? "timed out waiting for source withdrawal confirmation."
				: "timed out while returning movable items to the original source.");
			pending = null;
		}
		return true;
	}
	
	/**
	 * Uses one normal pickup click per client tick when a quick-move could
	 * merge
	 * new loot into an inventory stack that existed before the run. That keeps
	 * protected items physically separate as well as logically tracked.
	 */
	private boolean startSlotTransfer(Slot from, Slot target,
		boolean withdrawal)
	{
		var screen = interactions.getSupportedScreen();
		if(screen == null)
			return false;
		int fromMenuIndex = screen.getMenu().slots.indexOf(from);
		int targetMenuIndex = screen.getMenu().slots.indexOf(target);
		if(fromMenuIndex < 0 || targetMenuIndex < 0)
			return false;
		ItemStack stack = from.getItem().copy();
		lastFinishedSlotTransfer = null;
		SlotTransfer transfer = new SlotTransfer(fromMenuIndex, targetMenuIndex,
			stack, countInventory(stack), interactions.getRevision(),
			withdrawal, SlotTransferStage.WAITING_FOR_CURSOR,
			System.currentTimeMillis(), target.getItem().getCount());
		expectAutomatedInput();
		if(!interactions.pickup(from))
			return false;
		slotTransfer = transfer;
		debug("using explicit slot transfer to keep protected "
			+ "inventory separate");
		return true;
	}
	
	private void tickSlotTransfer(long timeoutMs)
	{
		var screen = interactions.getSupportedScreen();
		if(screen == null)
		{
			abortSlotTransfer(
				"container closed during a protected-item transfer.");
			return;
		}
		Slot from =
			findSlot(screen.getMenu().slots, slotTransfer.fromMenuIndex());
		Slot target =
			findSlot(screen.getMenu().slots, slotTransfer.targetMenuIndex());
		if(from == null || target == null)
		{
			abortSlotTransfer(
				"container changed during a protected-item transfer.");
			return;
		}
		ItemStack carried = interactions.getCarried();
		switch(slotTransfer.stage())
		{
			case WAITING_FOR_CURSOR ->
			{
				// Some servers apply a normal slot click as a direct
				// source-to-target update rather than exposing an intermediate
				// cursor stack to the client. That is still a successful safe
				// transfer; do not wait for a cursor state that will never
				// come.
				int inventoryCount = countInventory(slotTransfer.stack());
				// A pickup click is applied asynchronously on some servers. For
				// a
				// deposit, the client can briefly show the player slot as empty
				// before it receives the carried stack. Do not mistake that
				// prediction for a completed deposit: wait for the cursor, then
				// place it normally. The target count is still a valid
				// direct-click
				// confirmation, while a hopper may drain it only after that
				// point.
				boolean inventoryChanged = slotTransfer.withdrawal()
					&& inventoryCount > slotTransfer.inventoryBefore();
				if(carried.isEmpty()
					&& (target.getItem().getCount() > slotTransfer
						.targetBefore() || inventoryChanged))
				{
					awaitSlotTransferSettlement();
					return;
				}
				if(!carried.isEmpty() && ItemStack
					.isSameItemSameComponents(carried, slotTransfer.stack()))
				{
					expectAutomatedInput();
					if(!interactions.pickup(target))
					{
						abortSlotTransfer(
							"could not place a protected-item transfer into its target slot.");
						return;
					}
					slotTransfer = slotTransfer
						.advance(SlotTransferStage.WAITING_FOR_TARGET);
					return;
				}
			}
			case WAITING_FOR_TARGET ->
			{
				boolean targetAccepted = target.getItem()
					.getCount() > slotTransfer.targetBefore()
					|| !carried.isEmpty()
						&& carried.getCount() < slotTransfer.stack().getCount();
				// A hopper can remove the newly deposited stack before the
				// next client update. An empty cursor is the authoritative
				// indication that the normal placement completed, even when
				// the destination's visible count did not increase.
				if(!slotTransfer.withdrawal() && carried.isEmpty())
				{
					awaitSlotTransferSettlement();
					return;
				}
				if(targetAccepted)
				{
					if(carried.isEmpty())
					{
						awaitSlotTransferSettlement();
						return;
					}
					expectAutomatedInput();
					if(!interactions.pickup(from))
					{
						abortSlotTransfer(
							"could not return the remainder of a protected-item transfer.");
						return;
					}
					slotTransfer = slotTransfer
						.advance(SlotTransferStage.WAITING_FOR_SOURCE);
					return;
				}
				if(!slotTransfer.withdrawal()
					&& System.currentTimeMillis()
						- slotTransfer.stageStarted() >= SLOT_TRANSFER_SETTLE_MS
					&& !carried.isEmpty() && ItemStack.isSameItemSameComponents(
						carried, slotTransfer.stack()))
				{
					// The destination rejected the click. Return the stack to
					// the
					// exact player slot rather than timing out with it on the
					// cursor.
					expectAutomatedInput();
					if(!interactions.pickup(from))
					{
						abortSlotTransfer(
							"could not return a rejected destination transfer.");
						return;
					}
					slotTransfer = slotTransfer
						.advance(SlotTransferStage.WAITING_FOR_SOURCE);
					return;
				}
			}
			case WAITING_FOR_SOURCE ->
			{
				if(carried.isEmpty())
				{
					awaitSlotTransferSettlement();
					return;
				}
			}
			case WAITING_FOR_SETTLEMENT ->
			{
				if(carried.isEmpty())
				{
					if(System.currentTimeMillis() - slotTransfer
						.stageStarted() >= SLOT_TRANSFER_SETTLE_MS)
						finishSlotTransfer();
					return;
				}
				if(!ItemStack.isSameItemSameComponents(carried,
					slotTransfer.stack()))
				{
					abortSlotTransfer(
						"server placed an unexpected stack on the cursor.");
					return;
				}
				// A delayed correction revealed that the placement did not
				// finish.
				// Put the full or partial remainder back into its original
				// player
				// slot, then let normal confirmation decide whether any items
				// moved.
				expectAutomatedInput();
				if(!interactions.pickup(from))
				{
					abortSlotTransfer(
						"could not recover a delayed cursor transfer.");
					return;
				}
				slotTransfer =
					slotTransfer.advance(SlotTransferStage.WAITING_FOR_SOURCE);
				return;
			}
		}
		if(System.currentTimeMillis() - slotTransfer.stageStarted() > timeoutMs)
			abortSlotTransfer(
				"timed out waiting for a protected-item transfer.");
	}
	
	private Slot findSlot(List<Slot> slots, int index)
	{
		return index >= 0 && index < slots.size() ? slots.get(index) : null;
	}
	
	private void finishSlotTransfer()
	{
		SlotTransfer transfer = slotTransfer;
		slotTransfer = null;
		lastFinishedSlotTransfer = transfer;
		pending = transfer.withdrawal()
			? PendingAction.withdraw(transfer.stack(),
				transfer.inventoryBefore(), transfer.revisionBefore())
			: PendingAction.deposit(transfer.stack(),
				transfer.inventoryBefore(), transfer.revisionBefore());
	}
	
	/**
	 * Handles a server correction that arrives just after a normal transfer was
	 * locally observed as complete. The exact source and target indexes are
	 * retained until the next transfer, so the carried stack can be placed by
	 * the same transaction instead of disabling the sorter with an open cursor.
	 */
	private boolean recoverLateCursorTransfer()
	{
		SlotTransfer transfer = lastFinishedSlotTransfer;
		ItemStack carried = interactions.getCarried();
		if(transfer == null || carried.isEmpty()
			|| !ItemStack.isSameItemSameComponents(carried, transfer.stack()))
			return false;
		var screen = interactions.getSupportedScreen();
		if(screen == null)
			return false;
		Slot target =
			findSlot(screen.getMenu().slots, transfer.targetMenuIndex());
		if(target == null)
			return false;
		// The old confirmation belongs to the client prediction that just
		// changed. Re-run the target click and confirm the server's final
		// state.
		pending = null;
		lastFinishedSlotTransfer = null;
		expectAutomatedInput();
		if(!interactions.pickup(target))
			return false;
		slotTransfer = transfer.advance(SlotTransferStage.WAITING_FOR_TARGET);
		debug("recovering delayed cursor update for protected transfer");
		return true;
	}
	
	private void awaitSlotTransferSettlement()
	{
		slotTransfer =
			slotTransfer.advance(SlotTransferStage.WAITING_FOR_SETTLEMENT);
	}
	
	private void abortSlotTransfer(String reason)
	{
		slotTransfer = null;
		lastFinishedSlotTransfer = null;
		transition(LootSorterState.ERROR);
		error(reason);
	}
	
	private void removeScannedAmount(LogicalContainer source, ItemStack stack,
		int amount)
	{
		List<ItemStack> contents = scannedSources.get(source);
		if(contents == null)
			return;
		ItemStackEquivalenceKey key = ItemStackEquivalenceKey.of(stack);
		for(int i = 0; i < contents.size() && amount > 0; i++)
		{
			ItemStack scanned = contents.get(i);
			if(scanned.isEmpty())
				continue;
			if(!key.equals(ItemStackEquivalenceKey.of(scanned)))
				continue;
			int remove = Math.min(amount, scanned.getCount());
			scanned.shrink(remove);
			amount -= remove;
		}
		contents.removeIf(ItemStack::isEmpty);
	}
	
	private void restoreScannedAmount(LogicalContainer source, ItemStack stack,
		int amount)
	{
		if(source == null || stack.isEmpty() || amount <= 0)
			return;
		List<ItemStack> contents = scannedSources.computeIfAbsent(source,
			ignored -> new ArrayList<>());
		ItemStackEquivalenceKey key = ItemStackEquivalenceKey.of(stack);
		for(ItemStack scanned : contents)
			if(key.equals(ItemStackEquivalenceKey.of(scanned)))
			{
				scanned.grow(amount);
				return;
			}
		ItemStack restored = stack.copy();
		restored.setCount(amount);
		contents.add(restored);
	}
	
	/**
	 * Minecraft can stop client ticks while the window is unfocused. The sorter
	 * measures operation time only while ticks are arriving, so restoring a
	 * minimized game never turns a healthy pending transfer into a timeout.
	 */
	private void compensateForStalledTicks()
	{
		long now = System.currentTimeMillis();
		if(lastControllerTickAt == 0)
		{
			lastControllerTickAt = now;
			return;
		}
		long gap = now - lastControllerTickAt;
		lastControllerTickAt = now;
		if(gap <= STALLED_TICK_GAP_MS)
			return;
		stateStarted += gap;
		lastAction += gap;
		automatedInputUntil += gap;
		if(pending != null)
			pending = pending.delayedBy(gap);
		debug("paused client tick gap of " + gap
			+ "ms excluded from LootSorter timeouts.");
	}
	
	private void expectAutomatedInput()
	{
		automatedInputUntil = System.currentTimeMillis() + 750;
	}
	
	private void reopenContainerAfterScreenClosed(LootSorterState next)
	{
		if(mc.gui.screen() != null)
		{
			pausedResumeState = next;
			transition(LootSorterState.PAUSED);
			message(
				"another screen is open; close it and press Enter to resume.");
			return;
		}
		debug("container screen was closed; reopening it safely.");
		BlockPos target = next == LootSorterState.NAVIGATING_TO_SOURCE
			? activeSource.anchor() : activeDestination.getContainer().anchor();
		startPath(target, next);
	}
	
	private int countInventory(ItemStack target)
	{
		if(target.isEmpty())
			return 0;
		ItemStackEquivalenceKey key = ItemStackEquivalenceKey.of(target);
		int total = 0;
		for(int i = 0; i < mc.player.getInventory().getContainerSize(); i++)
		{
			ItemStack stack = mc.player.getInventory().getItem(i);
			if(!stack.isEmpty()
				&& key.equals(ItemStackEquivalenceKey.of(stack)))
				total += stack.getCount();
		}
		return total;
	}
	
	private Slot findWithdrawalTarget(List<Slot> slots, ItemStack stack)
	{
		for(Slot slot : slots)
			if(isTransferInventorySlot(slot) && slot.getItem().isEmpty()
				&& canAccept(slot, stack))
				return slot;
		for(Slot slot : slots)
			if(isTransferInventorySlot(slot)
				&& ItemStack.isSameItemSameComponents(slot.getItem(), stack)
				&& ledger.isEntireStackMovable(slot.getItem())
				&& canAccept(slot, stack))
				return slot;
		return null;
	}
	
	private Slot findDepositTarget(List<Slot> slots, ItemStack stack)
	{
		for(Slot slot : slots)
			if(!interactions.isPlayerSlot(slot)
				&& ItemStack.isSameItemSameComponents(slot.getItem(), stack)
				&& canAccept(slot, stack))
				return slot;
		for(Slot slot : slots)
			if(!interactions.isPlayerSlot(slot) && slot.getItem().isEmpty()
				&& canAccept(slot, stack))
				return slot;
		return null;
	}
	
	private boolean canAccept(Slot target, ItemStack incoming)
	{
		if(!target.mayPlace(incoming))
			return false;
		ItemStack current = target.getItem();
		if(!current.isEmpty()
			&& !ItemStack.isSameItemSameComponents(current, incoming))
			return false;
		int limit = target.getMaxStackSize(incoming);
		return limit > current.getCount();
	}
	
	private ItemStack findMovableRouteStack()
	{
		if((activeRoute == null && !directEverythingMove) || mc.player == null)
			return null;
		for(int i = 0; i < PLAYER_STORAGE_SLOT_COUNT; i++)
		{
			ItemStack stack = mc.player.getInventory().getItem(i);
			if(!stack.isEmpty()
				&& (directEverythingMove || activeRoute.itemKeys()
					.contains(ItemStackEquivalenceKey.of(stack)))
				&& ledger.isEntireStackMovable(stack))
				return stack;
		}
		return null;
	}
	
	private int getMovableRouteItemCount()
	{
		if(mc.player == null || (activeRoute == null && !directEverythingMove))
			return 0;
		if(directEverythingMove)
		{
			int total = 0;
			for(int i = 0; i < PLAYER_STORAGE_SLOT_COUNT; i++)
			{
				ItemStack stack = mc.player.getInventory().getItem(i);
				if(!stack.isEmpty() && ledger.isEntireStackMovable(stack))
					total += stack.getCount();
			}
			return total;
		}
		return activeRoute.itemKeys().stream().mapToInt(ledger::getMovable)
			.sum();
	}
	
	private ItemStack findRouteSourceStack()
	{
		if(activeRoute == null && !directEverythingMove)
			return ItemStack.EMPTY;
		List<ItemStack> contents = scannedSources.get(activeSource);
		if(contents == null)
			return ItemStack.EMPTY;
		return contents.stream().filter(stack -> !stack.isEmpty()
			&& (directEverythingMove || activeRoute.itemKeysFor(activeSource)
				.contains(ItemStackEquivalenceKey.of(stack))))
			.findFirst().orElse(ItemStack.EMPTY);
	}
	
	/**
	 * Finds the nearest other source that still has a stack for this route.
	 * This deliberately happens before the destination leg so one inventory
	 * load can combine compatible loot from several containers.
	 */
	private LogicalContainer findNextPickupSource()
	{
		if(activeRoute == null || mc.player == null)
			return null;
		return activeRoute.sourceItemKeys().keySet().stream()
			.filter(source -> !source.equals(activeSource)
				&& !unreachableSources.contains(source))
			.filter(this::sourceHasRouteItems)
			.filter(this::hasCapacityForRouteItems)
			.min(java.util.Comparator.comparingDouble(source -> source.anchor()
				.distToCenterSqr(mc.player.position())))
			.orElse(null);
	}
	
	private boolean sourceHasRouteItems(LogicalContainer source)
	{
		if(activeRoute == null)
			return false;
		Set<ItemStackEquivalenceKey> keys = activeRoute.itemKeysFor(source);
		return scannedSources.getOrDefault(source, List.of()).stream()
			.anyMatch(stack -> !stack.isEmpty()
				&& keys.contains(ItemStackEquivalenceKey.of(stack)));
	}
	
	/**
	 * A full inventory may still contain a partially filled stack, but that
	 * only
	 * helps when the next source has the exact same item and components.
	 * Checking
	 * that here prevents bouncing between sources with no slot for the loot
	 * (for
	 * example, after filling every slot with unstackable Totems).
	 */
	private boolean hasCapacityForRouteItems(LogicalContainer source)
	{
		if(mc.player == null || activeRoute == null)
			return false;
		return scannedSources.getOrDefault(source, List.of()).stream()
			.filter(stack -> !stack.isEmpty() && activeRoute.itemKeysFor(source)
				.contains(ItemStackEquivalenceKey.of(stack)))
			.anyMatch(this::hasCapacityFor);
	}
	
	private boolean hasCapacityFor(ItemStack incoming)
	{
		for(int i = 0; i < PLAYER_STORAGE_SLOT_COUNT; i++)
		{
			ItemStack stack = mc.player.getInventory().getItem(i);
			if(stack.isEmpty())
				return true;
			if(ItemStack.isSameItemSameComponents(stack, incoming)
				&& ledger.isEntireStackMovable(stack) && stack.getCount() < Math
					.min(stack.getMaxStackSize(), incoming.getMaxStackSize()))
				return true;
		}
		return false;
	}
	
	private boolean isTransferInventorySlot(Slot slot)
	{
		// The supported chest and shulker menus expose only the main inventory
		// and hotbar as player slots. Armour and offhand are not menu slots
		// here,
		// so filtering by Slot#getContainerSlot() is both unnecessary and can
		// reject valid slots on modded menu implementations.
		return interactions.isPlayerSlot(slot);
	}
	
	/**
	 * Finds a menu slot for loot proven movable in the player's actual storage
	 * inventory. Matching by stack identity makes this independent of menu-slot
	 * numbering, which varies in some server/container implementations.
	 */
	private Slot findMovableRouteSlot(List<Slot> slots,
		boolean mustMatchDestination)
	{
		if(mc.player == null || (activeRoute == null && !directEverythingMove))
			return null;
		for(Slot slot : slots)
		{
			ItemStack stack = slot.getItem();
			if(!interactions.isPlayerSlot(slot) || stack.isEmpty()
				|| !isStorageInventoryStack(stack)
				|| !(directEverythingMove || activeRoute.itemKeys()
					.contains(ItemStackEquivalenceKey.of(stack)))
				|| !ledger.isEntireStackMovable(stack))
				continue;
			if(!mustMatchDestination || activeDestination.matches(stack))
				return slot;
		}
		return null;
	}
	
	private boolean isStorageInventoryStack(ItemStack candidate)
	{
		for(int i = 0; i < PLAYER_STORAGE_SLOT_COUNT; i++)
			if(mc.player.getInventory().getItem(i) == candidate)
				return true;
		return false;
	}
	
	private void closeOrPause(LootSorterState next)
	{
		if(interactions.getSupportedScreen() == null)
		{
			transition(next);
			return;
		}
		if(automaticallyCloseScreens)
		{
			interactions.close();
			transition(next);
			return;
		}
		pausedResumeState = next;
		transition(LootSorterState.PAUSED);
		message(
			"container left open by setting; close it and press Enter to resume.");
	}
	
	private BlockHitResult interactionHitResult(BlockPos target, Vec3 eyes)
	{
		Direction side = interactionSide(target, eyes);
		Vec3 hit = Vec3.atCenterOf(target).add(side.getStepX() * 0.499,
			side.getStepY() * 0.499, side.getStepZ() * 0.499);
		BlockHitResult sight = mc.level.clip(new ClipContext(eyes, hit,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		if(sight.getType() == HitResult.Type.BLOCK
			&& !sight.getBlockPos().equals(target))
			return null;
		return new BlockHitResult(hit, side, target, false);
	}
	
	private Direction interactionSide(BlockPos target, Vec3 eyes)
	{
		Vec3 center = Vec3.atCenterOf(target);
		double x = eyes.x - center.x;
		double z = eyes.z - center.z;
		if(Math.abs(x) >= Math.abs(z))
			return x >= 0 ? Direction.EAST : Direction.WEST;
		return z >= 0 ? Direction.SOUTH : Direction.NORTH;
	}
	
	/** Finds a walking position that has a clear horizontal face to use. */
	private final class ContainerPathFinder extends PathFinder
	{
		private static final double INTERACTION_RANGE_SQR = 4.5 * 4.5;
		private final BlockPos container;
		
		private ContainerPathFinder(BlockPos container)
		{
			super(container);
			this.container = container.immutable();
		}
		
		@Override
		protected boolean checkDone()
		{
			// Path positions refer to the player's feet. A reach-only target
			// lets
			// the path end on top of a shulker cluster or behind another box.
			// The
			// final walking cell must also have a clear line to a horizontal
			// face.
			Vec3 eyes = new Vec3(current.getX() + 0.5, current.getY() + 1.62,
				current.getZ() + 0.5);
			return done = eyes.distanceToSqr(
				Vec3.atCenterOf(container)) <= INTERACTION_RANGE_SQR
				&& interactionHitResult(container, eyes) != null;
		}
	}
	
	private void markActiveUnreachable()
	{
		PathProcessor.releaseControls();
		if(state == LootSorterState.NAVIGATING_TO_DESTINATION
			|| state == LootSorterState.OPENING_DESTINATION)
		{
			markActiveDestinationUnavailable(true);
			return;
		}
		markActiveSourceUnreachable();
	}
	
	private void markActiveSourceUnreachable()
	{
		boolean routeWasActive = activeRoute != null || directEverythingMove;
		warnUnreachable(activeSource, "source");
		recordFailure();
		if(state == LootSorterState.ERROR)
			return;
		scannedSources.remove(activeSource);
		unreachableSources.add(activeSource);
		if(directEverythingMove)
		{
			if(startNextDirectEverythingSource())
				return;
			directEverythingMove = false;
			transition(LootSorterState.PLANNING);
			return;
		}
		if(!routeWasActive)
			scanIndex++;
		activeRoute = null;
		transition(routeWasActive ? LootSorterState.PLANNING
			: LootSorterState.RESCANNING);
	}
	
	/**
	 * Leaves an unavailable destination out of this run, then either carries
	 * the confirmed loot to another matching destination or puts it back in
	 * its original source. This prevents confirmed withdrawals from becoming
	 * orphaned when a destination disappears after the source has closed.
	 */
	private void markActiveDestinationUnavailable(boolean unreachable)
	{
		if(activeDestination != null)
		{
			if(unreachable)
			{
				activeDestination.setUnreachable(true);
				warnUnreachable(activeDestination.getContainer(),
					"destination");
			}else
				activeDestination.setTemporarilyUnavailable(true);
		}
		recordFailure();
		if(state == LootSorterState.ERROR)
			return;
		if(directEverythingMove)
		{
			continueDirectEverythingDeliveryOrReturn();
			return;
		}
		continueDeliveryOrReturn();
	}
	
	private void continueDeliveryOrReturn()
	{
		if(activeRoute == null || getMovableRouteItemCount() <= 0)
		{
			activeRoute = null;
			transition(LootSorterState.PLANNING);
			return;
		}
		ItemStack routeStack = findMovableRouteStack();
		if(routeStack == null)
		{
			int deferred = deferUnsafeMovableRouteItems();
			if(deferred > 0)
			{
				error("left " + deferred + " matching item"
					+ (deferred == 1 ? "" : "s")
					+ " in your inventory because they merged with protected "
					+ "stacks; continuing with the remaining routes.");
				activeRoute = null;
				transition(LootSorterState.PLANNING);
				return;
			}
			transition(LootSorterState.ERROR);
			error(
				"movable ledger no longer corresponds to a complete inventory stack.");
			return;
		}
		while(++destinationIndex < destinations.size())
		{
			activeDestination = destinations.get(destinationIndex);
			if(activeDestination.matches(routeStack))
			{
				startPath(activeDestination.getContainer().anchor(),
					LootSorterState.NAVIGATING_TO_DESTINATION);
				return;
			}
		}
		returningRemainder = true;
		startPath(activeSource.anchor(), LootSorterState.NAVIGATING_TO_SOURCE);
	}
	
	/**
	 * Legacy quick-moves may already have mixed loot with a protected stack.
	 * We cannot safely separate identical items after that, so keep them in the
	 * player inventory and let the rest of the run finish.
	 */
	private int deferUnsafeMovableRouteItems()
	{
		if(activeRoute == null)
			return 0;
		int deferred = 0;
		for(ItemStackEquivalenceKey key : activeRoute.itemKeys())
		{
			int movable = ledger.getMovable(key);
			if(movable <= 0 || hasWholeMovableInventoryStack(key, movable))
				continue;
			deferred += ledger.deferMovable(key);
		}
		return deferred;
	}
	
	private boolean hasWholeMovableInventoryStack(ItemStackEquivalenceKey key,
		int movable)
	{
		for(int i = 0; i < mc.player.getInventory().getContainerSize(); i++)
		{
			ItemStack stack = mc.player.getInventory().getItem(i);
			if(!stack.isEmpty() && key.equals(ItemStackEquivalenceKey.of(stack))
				&& stack.getCount() <= movable)
				return true;
		}
		return false;
	}
	
	private void scanOpenedSource()
	{
		var screen = interactions.getSupportedScreen();
		if(screen == null)
			return;
		updateActiveSourceContents(screen);
		scanIndex++;
		closeOrPause(LootSorterState.RESCANNING);
	}
	
	/**
	 * Replaces one cached source with the live contents currently on screen.
	 */
	private void updateActiveSourceContents(
		net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)
	{
		List<ItemStack> contents = new ArrayList<>();
		for(Slot slot : screen.getMenu().slots)
			if(!interactions.isPlayerSlot(slot) && !slot.getItem().isEmpty())
				contents.add(slot.getItem().copy());
		scannedSources.put(activeSource, contents);
		if(contents.isEmpty())
			completedSources.add(activeSource);
		else
			completedSources.remove(activeSource);
		// A named source preset keeps its live cache even if the run stops
		// between containers. This makes a later "continue without
		// re-checking" restart resume from the latest observed contents.
		persistSourceContents();
	}
	
	private void publishSourceContents()
	{
		if(sourceContentsPublished || !hasCompleteSourceContents())
			return;
		sourceContentsPublished = true;
		persistSourceContents();
	}
	
	private void persistSourceContents()
	{
		if(sourceContentsSaver == null || scannedSources.isEmpty())
			return;
		sourceContentsSaver.accept(exportSourceContents());
	}
	
	private int getUnmatchedCount()
	{
		return scannedSources.values().stream().flatMap(List::stream)
			.filter(stack -> destinations.stream()
				.noneMatch(destination -> destination.matches(stack)))
			.mapToInt(ItemStack::getCount).sum();
	}
	
	/**
	 * A normal completion is only valid when every remaining source item is
	 * genuinely unmatched. If a configured filter still matches an item but
	 * its destination was disabled during this run, report that incomplete
	 * route instead of silently treating a safety return as success.
	 */
	private boolean hasStrandedMatchingItems()
	{
		return scannedSources.values().stream().flatMap(List::stream)
			.filter(stack -> !stack.isEmpty())
			.anyMatch(stack -> destinations.stream()
				.filter(DestinationRule::isConfigured)
				.anyMatch(destination -> destination.getFilters().stream()
					.anyMatch(filter -> filter.matches(stack))));
	}
	
	private void updateSourceVisualStates()
	{
		for(Map.Entry<LogicalContainer, List<ItemStack>> entry : scannedSources
			.entrySet())
		{
			if(entry.getValue().isEmpty())
				completedSources.add(entry.getKey());
			else if(entry.getValue().stream().allMatch(stack -> destinations
				.stream().noneMatch(destination -> destination.matches(stack))))
				unmatchedSources.add(entry.getKey());
		}
	}
	
	private int getDisabledDestinations()
	{
		return (int)destinations.stream().filter(rule -> rule.isConfigured()
			&& (rule.isFull() || rule.isUnreachable())).count();
	}
	
	private void transition(LootSorterState next)
	{
		if(state == LootSorterState.ERROR && next != LootSorterState.DISABLED)
			return;
		LootSorterState previous = state;
		state = next;
		stateStarted = System.currentTimeMillis();
		debug("state " + previous + " -> " + next);
	}
	
	private void recordFailure()
	{
		if(++consecutiveFailures <= maximumFailures)
			return;
		transition(LootSorterState.ERROR);
		error("stopped after " + consecutiveFailures
			+ " consecutive interaction/navigation failures.");
	}
	
	private void warnFullDestination(String cause)
	{
		if(activeDestination == null
			|| !warnedFullDestinations.add(activeDestination))
			return;
		ItemStack affected = findMovableRouteStack();
		if(affected == null || affected.isEmpty())
			affected = findRouteSourceStack();
		ItemStack stackForFilter = affected;
		String filter = activeDestination.getFilters().stream()
			.filter(value -> value.matches(stackForFilter))
			.map(ItemFilter::getDisplayName).findFirst()
			.orElse("configured filter");
		error("destination "
			+ activeDestination.getContainer().anchor().toShortString()
			+ " is disabled for " + filter + " this run: " + cause + ".");
	}
	
	private void warnUnreachable(LogicalContainer container, String kind)
	{
		if(container != null && warnedUnreachable.add(container))
			error(kind + " unreachable: " + container.anchor().toShortString());
	}
	
	private void message(String text)
	{
		if(chatNotifications)
			ChatUtils.message("LootSorter: " + text);
	}
	
	private void error(String text)
	{
		ChatUtils.error("LootSorter: " + text);
	}
	
	private void debug(String text)
	{
		if(debugLogging)
			ChatUtils.message("LootSorter debug: " + text);
	}
	
	private void reportSummary(String outcome)
	{
		if(summaryReported)
			return;
		summaryReported = true;
		persistSourceContents();
		if(!chatNotifications)
			return;
		if(fullCompletionSummary)
			ChatUtils.message("LootSorter " + outcome + ": moved " + movedItems
				+ " items in " + containerTrips + " container trips; "
				+ getUnmatchedCount() + " unmatched remain; "
				+ getDisabledDestinations() + " destinations disabled; "
				+ unreachableSources.size() + " sources unreachable.");
		else
			ChatUtils.message(
				"LootSorter " + outcome + ": " + movedItems + " items moved.");
	}
	
	public void render(PoseStack matrices, boolean renderLabels,
		double labelRange)
	{
		if(mc.level == null)
			return;
		LogicalContainer target =
			state == LootSorterState.NAVIGATING_TO_DESTINATION
				|| state == LootSorterState.OPENING_DESTINATION
				|| state == LootSorterState.DEPOSITING
					? activeDestination == null ? null
						: activeDestination.getContainer()
					: activeSource;
		unloadedContainers.clear();
		for(LogicalContainer source : sources)
			if(!mc.level.hasChunkAt(source.anchor()))
				unloadedContainers.add(source);
		for(DestinationRule destination : destinations)
			if(!mc.level.hasChunkAt(destination.getContainer().anchor()))
				unloadedContainers.add(destination.getContainer());
		renderer.render(matrices, sources, destinations, target,
			completedSources, unmatchedSources, unreachableSources,
			unloadedContainers, mc, renderLabels, labelRange);
	}
	
	public LootSorterState getState()
	{
		return state;
	}
	
	public void moveDestinationPriority(DestinationRule rule, int direction)
	{
		int from = destinations.indexOf(rule);
		int to = from + direction;
		if(from < 0 || to < 0 || to >= destinations.size())
			return;
		java.util.Collections.swap(destinations, from, to);
		for(int i = 0; i < destinations.size(); i++)
			destinations.get(i).setPriority(i);
	}
	
	public void removeDestination(DestinationRule rule)
	{
		if(state != LootSorterState.SELECTING_DESTINATIONS || rule == null)
			return;
		destinations.remove(rule);
		for(int i = 0; i < destinations.size(); i++)
			destinations.get(i).setPriority(i);
		message("removed destination "
			+ rule.getContainer().anchor().toShortString());
	}
	
	/**
	 * Captures configuration only. No controller state is changed by export.
	 */
	public LootSorterProfile createProfile(String name, String serverIdentifier,
		String dimensionKey, String dimensionType)
	{
		return new LootSorterProfile(name, serverIdentifier, dimensionKey,
			dimensionType, exportSources(), exportDestinations(),
			exportSourceContents());
	}
	
	/** Exports only the selected sources for a named source preset. */
	public List<LootSorterProfile.ContainerPos> exportSources()
	{
		return sources.stream()
			.map(c -> new LootSorterProfile.ContainerPos(c.anchor().getX(),
				c.anchor().getY(), c.anchor().getZ()))
			.toList();
	}
	
	/**
	 * Exports every fully scanned source with component-safe stack snapshots.
	 */
	public List<SourceContentsSnapshot> exportSourceContents()
	{
		if(mc.level == null)
			return List.of();
		return sources.stream().filter(scannedSources::containsKey)
			.map(source -> new SourceContentsSnapshot(
				new LootSorterProfile.ContainerPos(source.anchor().getX(),
					source.anchor().getY(), source.anchor().getZ()),
				scannedSources.get(source).stream()
					.map(stack -> ItemStackSnapshotCodec.encode(stack,
						mc.level.registryAccess()))
					.filter(java.util.Objects::nonNull).toList()))
			.toList();
	}
	
	/**
	 * Restores every valid saved source snapshot. Partial caches are retained:
	 * continuing without a re-check keeps those records and scans only sources
	 * that have not yet been opened, rather than discarding useful progress.
	 */
	public boolean restoreSourceContents(List<SourceContentsSnapshot> saved)
	{
		scannedSources.clear();
		sourceContentsPublished = false;
		if(mc.level == null || saved == null || saved.isEmpty()
			|| sources.isEmpty())
			return false;
		Map<LootSorterProfile.ContainerPos, SourceContentsSnapshot> byPosition =
			new HashMap<>();
		for(SourceContentsSnapshot snapshot : saved)
			if(snapshot != null && snapshot.position() != null
				&& byPosition.put(snapshot.position(), snapshot) != null)
				return false;
		for(LogicalContainer source : sources)
		{
			LootSorterProfile.ContainerPos position =
				new LootSorterProfile.ContainerPos(source.anchor().getX(),
					source.anchor().getY(), source.anchor().getZ());
			SourceContentsSnapshot snapshot = byPosition.get(position);
			if(snapshot == null)
				continue;
			List<ItemStack> contents = new ArrayList<>();
			for(String token : snapshot.items())
			{
				ItemStack stack = ItemStackSnapshotCodec.decode(token,
					mc.level.registryAccess());
				if(stack.isEmpty())
				{
					scannedSources.clear();
					return false;
				}
				contents.add(stack);
			}
			scannedSources.put(source, contents);
		}
		if(scannedSources.isEmpty())
			return false;
		scanIndex = 0;
		sourceContentsPublished = hasCompleteSourceContents();
		return true;
	}
	
	/** Exports configured destinations and their complete filter rules. */
	public List<LootSorterProfile.DestinationProfile> exportDestinations()
	{
		if(mc.level == null)
			return List.of();
		return destinations.stream().filter(DestinationRule::isConfigured)
			.map(rule -> new LootSorterProfile.DestinationProfile(
				new LootSorterProfile.ContainerPos(
					rule.getContainer().anchor().getX(),
					rule.getContainer().anchor()
						.getY(),
					rule.getContainer().anchor().getZ()),
				rule.getPriority(),
				rule.getFilters().stream().map(filter -> ItemFilterCodec
					.encode(filter, mc.level.registryAccess())).toList()))
			.toList();
	}
	
	/** Restores sources without changing the active selection phase. */
	public boolean restoreSources(List<LootSorterProfile.ContainerPos> saved)
	{
		if(mc.level == null || saved == null)
			return false;
		sources.clear();
		scannedSources.clear();
		sourceContentsPublished = false;
		for(LootSorterProfile.ContainerPos pos : saved)
		{
			if(pos == null)
				continue;
			LogicalContainer container = LogicalContainer.fromTarget(mc.level,
				new BlockPos(pos.x(), pos.y(), pos.z()));
			if(container != null && !sources.contains(container))
				sources.add(container);
		}
		return !sources.isEmpty();
	}
	
	/**
	 * Restores destinations and every encoded filter without starting a run.
	 */
	public boolean restoreDestinations(
		List<LootSorterProfile.DestinationProfile> saved)
	{
		if(mc.level == null || saved == null)
			return false;
		destinations.clear();
		for(LootSorterProfile.DestinationProfile savedRule : saved)
		{
			if(savedRule == null || savedRule.position() == null)
				continue;
			LogicalContainer container = LogicalContainer.fromTarget(mc.level,
				new BlockPos(savedRule.position().x(), savedRule.position().y(),
					savedRule.position().z()));
			if(container == null || sources.contains(container)
				|| destinations.stream()
					.anyMatch(rule -> rule.getContainer().equals(container)))
				continue;
			DestinationRule rule =
				new DestinationRule(container, savedRule.priority());
			if(savedRule.filters() != null)
				for(String filterToken : savedRule.filters())
					rule.addFilter(ItemFilterCodec.decode(filterToken,
						mc.level.registryAccess()));
			if(rule.getFilters().isEmpty())
				rule.addFilter(BuiltInItemFilter.ALL);
			rule.setConfigured(true);
			destinations.add(rule);
		}
		destinations.sort(
			java.util.Comparator.comparingInt(DestinationRule::getPriority));
		for(int i = 0; i < destinations.size(); i++)
			destinations.get(i).setPriority(i);
		return !destinations.isEmpty();
	}
	
	/** Restoring a profile deliberately leaves the controller paused. */
	public boolean restoreProfile(LootSorterProfile profile)
	{
		if(mc.level == null || profile == null)
			return false;
		boolean hasSources = restoreSources(profile.sources());
		boolean hasDestinations = restoreDestinations(profile.destinations());
		if(hasSources)
			restoreSourceContents(profile.sourceContents());
		transition(LootSorterState.PAUSED);
		runDimension = mc.level.dimension().identifier().toString();
		lastPlayerPosition = mc.player.position();
		return hasSources && hasDestinations;
	}
	
	private boolean isAutomating()
	{
		return switch(state)
		{
			case DISABLED, SELECTING_SOURCES, SELECTING_DESTINATIONS, PAUSED, COMPLETED, ERROR -> false;
			default -> true;
		};
	}
	
	private record PendingAction(ItemStack stack, int inventoryBefore,
		int revisionBefore, boolean withdrawal, long issuedAt)
	{
		static PendingAction withdraw(ItemStack stack, int before, int revision)
		{
			return new PendingAction(stack.copy(), before, revision, true,
				System.currentTimeMillis());
		}
		
		static PendingAction deposit(ItemStack stack, int before, int revision)
		{
			return new PendingAction(stack.copy(), before, revision, false,
				System.currentTimeMillis());
		}
		
		PendingAction delayedBy(long delayMs)
		{
			return new PendingAction(stack, inventoryBefore, revisionBefore,
				withdrawal, issuedAt + delayMs);
		}
	}
	
	private enum SlotTransferStage
	{
		WAITING_FOR_CURSOR,
		WAITING_FOR_TARGET,
		WAITING_FOR_SOURCE,
		WAITING_FOR_SETTLEMENT
	}
	
	private record SlotTransfer(int fromMenuIndex, int targetMenuIndex,
		ItemStack stack, int inventoryBefore, int revisionBefore,
		boolean withdrawal, SlotTransferStage stage, long stageStarted,
		int targetBefore)
	{
		SlotTransfer advance(SlotTransferStage next)
		{
			return new SlotTransfer(fromMenuIndex, targetMenuIndex, stack,
				inventoryBefore, revisionBefore, withdrawal, next,
				System.currentTimeMillis(), targetBefore);
		}
	}
}
