/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.EntityHitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.MouseButtonPressListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.villageroll.VillagerRollNormalizedRoll;
import net.wurstclient.hacks.villageroll.VillagerRollObservation;
import net.wurstclient.hacks.villageroll.VillagerRollPredictor;
import net.wurstclient.hacks.villageroll.VillagerRollRoll;
import net.wurstclient.hacks.villageroll.VillagerRollSynchronizer;
import net.wurstclient.hacks.villageroll.VillagerRollTrade;
import net.wurstclient.hacks.villageroll.VillagerRollTradeKind;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;

@SearchTags({"villager roll", "villageroll", "librarian roll", "librarian seed",
	"villager seed"})
public final class VillagerRollHack extends Hack
	implements UpdateListener, MouseButtonPressListener, GUIRenderListener
{
	private static final int MAX_OBSERVATIONS = 64;
	private static final int MAX_SCREEN_STATES = 256;
	private static final int MAX_NEXT_BOOK_LINES = 200;
	
	private final CheckboxSetting showHud = new CheckboxSetting("Show HUD",
		"Shows the current librarian roll and target in the in-game HUD.",
		true);
	private final CheckboxSetting chatWarnings = new CheckboxSetting(
		"Chat warnings",
		"Warns in chat when the selected target is close or has been found.",
		true);
	private final CheckboxSetting middleClickInfo =
		new CheckboxSetting("Middle-click info",
			"Shows the prediction for a librarian when middle-clicked.", false);
	private final SliderSetting warningDistance = new SliderSetting(
		"Warning distance", "Rolls before target warnings are emitted.", 5, 1,
		20, 1, ValueDisplay.INTEGER);
	private final SliderSetting searchHorizon = new SliderSetting(
		"Search horizon", "Maximum number of future rolls to search.", 1000,
		100, 1_000_000, 100, ValueDisplay.INTEGER);
	
	private final ExecutorService executor =
		Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "Wurst-VillagerRoll");
			thread.setDaemon(true);
			return thread;
		});
	private final AtomicLong synchronizationGeneration = new AtomicLong();
	private final AtomicLong targetSearchGeneration = new AtomicLong();
	
	private final ArrayList<VillagerRollNormalizedRoll> observations =
		new ArrayList<>();
	private final ArrayDeque<ScreenObservationKey> screenStateOrder =
		new ArrayDeque<>();
	private final Set<ScreenObservationKey> seenScreenStates = new HashSet<>();
	
	private Object lastLevel;
	private Long lastEffectiveSeed;
	private Long manualSeed;
	private Long sequenceSeed;
	private Long currentRoll;
	private long stateGeneration;
	private VillagerRollSynchronizer.Result lastSyncResult;
	private int lastSyncHorizon;
	private SequenceStatus sequenceStatus = SequenceStatus.UNKNOWN;
	private boolean gapRecoveryAttempted;
	
	private String targetEnchantmentId;
	private Integer targetLevel;
	private TargetHit targetHit;
	private long lastWarningTargetRoll = Long.MIN_VALUE;
	private long lastWarningOffset = -1;
	
	private Future<?> synchronizationTask;
	private Future<?> targetSearchTask;
	
	public VillagerRollHack()
	{
		super("VillagerRoll");
		setCategory(Category.OTHER);
		addSetting(showHud);
		addSetting(chatWarnings);
		addSetting(middleClickInfo);
		addSetting(warningDistance);
		addSetting(searchHorizon);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(MouseButtonPressListener.class, this);
		EVENTS.add(GUIRenderListener.class, this);
		message("Enabled. Type .villageroll help for setup instructions.");
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(MouseButtonPressListener.class, this);
		EVENTS.remove(GUIRenderListener.class, this);
		cancelSynchronization();
		cancelTargetSearch();
		clearSequenceState();
		lastLevel = null;
		lastEffectiveSeed = null;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || MC.player == null)
		{
			if(lastLevel != null)
			{
				clearSequenceState();
				lastLevel = null;
				lastEffectiveSeed = null;
			}
			return;
		}
		
		Long effectiveSeed = getEffectiveSeed();
		if(lastLevel != MC.level)
		{
			if(lastLevel != null)
				clearSequenceState();
			lastLevel = MC.level;
			lastEffectiveSeed = effectiveSeed;
		}
		if(lastEffectiveSeed != null && effectiveSeed != null
			&& !lastEffectiveSeed.equals(effectiveSeed))
		{
			clearSequenceState();
			lastEffectiveSeed = effectiveSeed;
		}
		
		observeOpenMerchant();
	}
	
	@Override
	public void onMouseButtonPress(
		MouseButtonPressListener.MouseButtonPressEvent event)
	{
		if(!middleClickInfo.isChecked()
			|| event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE
			|| event.getAction() != GLFW.GLFW_PRESS
			|| !(MC.hitResult instanceof EntityHitResult hit)
			|| !(hit.getEntity() instanceof Villager villager)
			|| !isNoviceLibrarian(villager))
			return;
		
		message("Librarian prediction");
		if(currentRoll == null || sequenceSeed == null)
		{
			message(
				"Not synchronized. Observations: " + observations.size() + ".");
			printNextStep();
			return;
		}
		
		VillagerRollRoll roll =
			VillagerRollPredictor.predictRoll(sequenceSeed, currentRoll);
		message("Current roll: " + currentRoll);
		message("Current: " + formatTrade(roll.first()) + " / "
			+ formatTrade(roll.second()));
		VillagerRollPredictor.BookHit next =
			findNextTargetSynchronously(currentRoll, 1000);
		if(next != null)
		{
			message("Next " + formatBook(next.trade()) + ": +"
				+ next.rollsAhead() + " rerolls");
			message(next.trade().emeraldPrice() + " emeralds");
		}
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(!showHud.isChecked() || currentRoll == null || sequenceSeed == null)
			return;
		
		String line1 = "VillagerRoll";
		String line2 = targetEnchantmentId == null ? "Roll " + currentRoll
			: formatTarget();
		String line3 = targetHit == null ? sequenceStatus.displayName
			: (targetHit.rollsAhead() == 0 ? "TARGET HIT"
				: "+" + targetHit.rollsAhead() + " rolls");
		int width = Math.max(MC.font.width(line1),
			Math.max(MC.font.width(line2), MC.font.width(line3)));
		int x = context.guiWidth() - width - 8;
		int y = 8;
		context.fill(x - 3, y - 2, x + width + 3, y + 30, 0x90000000);
		context.text(MC.font, line1, x, y, 0xFFFFFFFF, false);
		context.text(MC.font, line2, x, y + 10, 0xFFFFFFFF, false);
		context.text(MC.font, line3, x, y + 20, 0xFF55FF55, false);
	}
	
	@Override
	public String getRenderName()
	{
		if(targetHit != null && currentRoll != null)
			return getName() + " [+" + targetHit.rollsAhead() + "]";
		return getName();
	}
	
	public void printStatus()
	{
		message("Seed: "
			+ (getEffectiveSeed() == null ? "unknown" : getEffectiveSeed()));
		message("Sequence: " + VillagerRollPredictor.LIBRARIAN_SEQUENCE_ID);
		message("Status: " + sequenceStatus.displayName);
		message("Counter: global across novice librarians in this world.");
		if(currentRoll != null)
			message("Current roll: " + currentRoll);
		message("Observations: " + observations.size());
		message("Target: "
			+ (targetEnchantmentId == null ? "none" : formatTarget()));
		if(sequenceStatus == SequenceStatus.AMBIGUOUS && lastSyncResult != null)
			message("Candidate positions: " + (lastSyncResult.moreCandidates()
				? "50+" : lastSyncResult.candidateRolls().size()));
		if(sequenceStatus == SequenceStatus.SYNCHRONIZING)
			message("Search horizon: " + lastSyncHorizon);
		if(targetHit != null)
			message("Next target: +" + targetHit.rollsAhead() + ", "
				+ targetHit.trade().emeraldPrice() + " emeralds");
		printNextStep();
	}
	
	public void printHelp()
	{
		message("Workflow for an existing world:");
		message(
			"Set the seed, then open newly generated novice librarian trade screens.");
		message(
			"To reroll: wait for unemployed, replace the lectern, wait for librarian, then open trades.");
		message(
			"Collect 2-3 different new menus; breaking/placing a lectern alone does not count.");
		message(
			"Reopening unchanged offers is ignored. All novice librarians share one world-wide counter.");
		message(
			"Trade Rebalance must be disabled for these predictions to match vanilla.");
		message(
			"For a truly fresh sequence, use .villageroll fresh before the first trade is generated.");
		message(
			"After synchronization, search with .villageroll mending or set .villageroll target mending.");
		printNextStep();
	}
	
	public void resetFromCommand()
	{
		clearSequenceState();
		message("Sequence state reset.");
		printNextStep();
	}
	
	public void setFreshFromCommand()
	{
		Long seed = getEffectiveSeed();
		if(seed == null)
		{
			message("World seed unknown. Use .villageroll seed <seed>");
			return;
		}
		
		clearSequenceState();
		sequenceSeed = seed;
		currentRoll = 0L;
		sequenceStatus = SequenceStatus.FRESH;
		lastEffectiveSeed = seed;
		message("Fresh sequence assumption set at roll 0.");
		message(
			"Next: open the first novice librarian trade screen. If it differs, the hack will resynchronize.");
		queueTargetSearch();
	}
	
	public void setManualSeed(String input)
	{
		Long parsed = VillagerRollPredictor.tryParseSeed(input);
		if(parsed == null)
		{
			message(
				"Invalid numeric seed. Use a signed 64-bit value or a text seed.");
			return;
		}
		manualSeed = parsed;
		clearSequenceState();
		lastEffectiveSeed = parsed;
		message("Manual seed set to " + parsed + ".");
		printNextStep();
	}
	
	public void clearManualSeed()
	{
		manualSeed = null;
		clearSequenceState();
		lastEffectiveSeed = getEffectiveSeed();
		message("Manual seed cleared.");
		printNextStep();
	}
	
	public void resynchronize(int horizon)
	{
		if(observations.isEmpty())
		{
			message("No librarian observations yet.");
			printNextStep();
			return;
		}
		startSynchronization(horizon);
	}
	
	public void setTarget(String input, Integer requestedLevel)
	{
		VillagerRollPredictor.EnchantmentInfo enchantment =
			VillagerRollPredictor.findEnchantment(input);
		if(enchantment == null)
		{
			message("Unknown librarian enchantment: " + input);
			return;
		}
		if(requestedLevel != null
			&& (requestedLevel < 1 || requestedLevel > enchantment.maxLevel()))
		{
			message("Level must be between 1 and " + enchantment.maxLevel()
				+ " for " + shortId(enchantment.id()) + ".");
			return;
		}
		
		targetEnchantmentId = enchantment.id();
		targetLevel = requestedLevel;
		resetTargetWarnings();
		message("Target set to " + formatTarget() + ".");
		queueTargetSearch();
	}
	
	public void clearTarget()
	{
		targetEnchantmentId = null;
		targetLevel = null;
		cancelTargetSearch();
		resetTargetWarnings();
		message("Target cleared.");
	}
	
	public void search(String input, Integer requestedLevel, Integer maxPrice)
	{
		VillagerRollPredictor.EnchantmentInfo enchantment =
			VillagerRollPredictor.findEnchantment(input);
		if(enchantment == null)
		{
			message("Unknown librarian enchantment: " + input);
			return;
		}
		if(requestedLevel != null
			&& (requestedLevel < 1 || requestedLevel > enchantment.maxLevel()))
		{
			message("Level must be between 1 and " + enchantment.maxLevel()
				+ " for " + shortId(enchantment.id()) + ".");
			return;
		}
		if(maxPrice != null && maxPrice < 1)
		{
			message("Maximum price must be positive.");
			return;
		}
		Long seed = sequenceSeed;
		Long start = currentRoll;
		if(seed == null || start == null)
		{
			message("Sequence position is unknown.");
			printNextStep();
			return;
		}
		
		int horizon = searchHorizon.getValueI();
		long generation = targetSearchGeneration.incrementAndGet();
		cancelFuture(targetSearchTask);
		BooleanSupplier cancelled =
			() -> generation != targetSearchGeneration.get();
		targetSearchTask = executor.submit(() -> {
			List<VillagerRollPredictor.BookHit> hits =
				VillagerRollPredictor.findBooks(seed, start, horizon,
					enchantment.id(), requestedLevel, maxPrice, 4, cancelled);
			MC.execute(() -> {
				if(generation != targetSearchGeneration.get())
					return;
				printSearchResult(enchantment.id(), requestedLevel, hits);
			});
		});
	}
	
	public void printUpcoming(int horizon)
	{
		if(horizon < 0 || horizon > 100_000)
		{
			message("Horizon must be between 0 and 100000 rolls.");
			return;
		}
		if(sequenceSeed == null || currentRoll == null)
		{
			message("Sequence position is unknown.");
			printNextStep();
			return;
		}
		
		Long seed = sequenceSeed;
		Long firstRoll = currentRoll;
		long generation = targetSearchGeneration.incrementAndGet();
		cancelFuture(targetSearchTask);
		targetSearchTask = executor.submit(() -> {
			List<VillagerRollRoll> rolls = VillagerRollPredictor
				.predictRolls(seed, firstRoll, horizon + 1);
			MC.execute(() -> {
				if(generation != targetSearchGeneration.get())
					return;
				message("Upcoming books:");
				int lines = 0;
				for(int i = 0; i < rolls.size()
					&& lines < MAX_NEXT_BOOK_LINES; i++)
				{
					VillagerRollRoll roll = rolls.get(i);
					if(roll.first()
						.kind() == VillagerRollTradeKind.ENCHANTED_BOOK)
					{
						printUpcomingBook(i, 1, roll.first());
						lines++;
					}
					if(roll.second()
						.kind() == VillagerRollTradeKind.ENCHANTED_BOOK
						&& lines < MAX_NEXT_BOOK_LINES)
					{
						printUpcomingBook(i, 2, roll.second());
						lines++;
					}
				}
				if(lines == 0)
					message("No enchanted books found in that horizon.");
			});
		});
	}
	
	private void observeOpenMerchant()
	{
		if(!(MC.gui.screen() instanceof MerchantScreen)
			|| !(MC.player.containerMenu instanceof MerchantMenu menu)
			|| menu.getOffers().size() < 2 || menu.getTraderLevel() > 1)
			return;
		
		Villager villager = findMerchantVillager();
		if(villager == null)
			return;
		
		MerchantOffers offers = menu.getOffers();
		VillagerRollTrade first = normalizeOffer(offers.get(0));
		VillagerRollTrade second = normalizeOffer(offers.get(1));
		if(first == null || second == null || first.kind() == second.kind())
			return;
		
		VillagerRollObservation observation = new VillagerRollObservation(
			villager.getUUID(), new VillagerRollRoll(first, second));
		ScreenObservationKey key = new ScreenObservationKey(
			observation.villagerId(), observation.roll());
		if(!seenScreenStates.add(key))
			return;
		screenStateOrder.addLast(key);
		if(screenStateOrder.size() > MAX_SCREEN_STATES)
			seenScreenStates.remove(screenStateOrder.removeFirst());
		handleObservation(observation);
	}
	
	private Villager findMerchantVillager()
	{
		if(MC.hitResult instanceof EntityHitResult hit
			&& hit.getEntity() instanceof Villager villager
			&& isNoviceLibrarian(villager))
			return villager;
		
		Villager nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		boolean tie = false;
		for(var entity : MC.level.entitiesForRendering())
		{
			if(!(entity instanceof Villager villager)
				|| !isNoviceLibrarian(villager))
				continue;
			double distance = MC.player.distanceToSqr(villager);
			if(distance > 36)
				continue;
			if(distance < nearestDistance - 1.0E-4)
			{
				nearest = villager;
				nearestDistance = distance;
				tie = false;
			}else if(Math.abs(distance - nearestDistance) < 1.0E-4)
				tie = true;
		}
		return tie ? null : nearest;
	}
	
	private VillagerRollTrade normalizeOffer(MerchantOffer offer)
	{
		if(offer.getBaseCostA().is(Items.PAPER)
			&& offer.getResult().is(Items.EMERALD))
			return new VillagerRollTrade(VillagerRollTradeKind.PAPER, null, 0,
				offer.getCostA().getCount());
		if(offer.getResult().is(Items.BOOKSHELF))
			return new VillagerRollTrade(VillagerRollTradeKind.BOOKSHELF, null,
				0, offer.getCostA().getCount());
		if(!offer.getResult().is(Items.ENCHANTED_BOOK))
			return null;
		
		ItemEnchantments enchantments =
			EnchantmentHelper.getEnchantmentsForCrafting(offer.getResult());
		if(enchantments.size() != 1)
			return null;
		for(var entry : enchantments.entrySet())
		{
			String id = entry.getKey().unwrapKey()
				.map(key -> key.identifier().toString()).orElse(null);
			if(id == null)
				return null;
			return VillagerRollTrade.enchantedBook(id, entry.getIntValue(),
				offer.getCostA().getCount());
		}
		return null;
	}
	
	private boolean isNoviceLibrarian(Villager villager)
	{
		return villager.getVillagerData().level() == 1
			&& villager.getVillagerData().profession().unwrapKey()
				.orElse(null) == VillagerProfession.LIBRARIAN;
	}
	
	private void handleObservation(VillagerRollObservation observation)
	{
		VillagerRollNormalizedRoll normalized = observation.normalized();
		if(sequenceStatus == SequenceStatus.FRESH && currentRoll != null
			&& sequenceSeed != null)
		{
			VillagerRollNormalizedRoll expected = VillagerRollPredictor
				.predictRoll(sequenceSeed, currentRoll).normalize();
			if(expected.equals(normalized))
			{
				appendObservation(normalized);
				sequenceStatus = SequenceStatus.SYNCHRONIZED;
				message("Fresh sequence confirmed at roll 0.");
				printNextStep();
				queueTargetSearch();
				return;
			}
			message("Fresh assumption did not match. Resynchronizing...");
			sequenceStatus = SequenceStatus.UNKNOWN;
			appendObservation(normalized);
			startSynchronization(
				VillagerRollSynchronizer.INITIAL_SEARCH_HORIZON);
			printNextStep();
			return;
		}
		
		if((sequenceStatus == SequenceStatus.SYNCHRONIZED
			|| sequenceStatus == SequenceStatus.VERIFYING)
			&& currentRoll != null && sequenceSeed != null)
		{
			long expectedRoll = currentRoll + 1;
			VillagerRollNormalizedRoll expected = VillagerRollPredictor
				.predictRoll(sequenceSeed, expectedRoll).normalize();
			if(expected.equals(normalized))
			{
				appendObservation(normalized);
				currentRoll = expectedRoll;
				sequenceStatus = SequenceStatus.SYNCHRONIZED;
				message("Synchronized at roll " + currentRoll + ".");
				queueTargetSearch();
				return;
			}
			
			message("Prediction mismatch. Resynchronizing...");
			sequenceStatus = SequenceStatus.UNKNOWN;
			currentRoll = null;
			sequenceSeed = getEffectiveSeed();
			keepOnlyLatestObservation(normalized);
			gapRecoveryAttempted = false;
			startSynchronization(
				VillagerRollSynchronizer.INITIAL_SEARCH_HORIZON);
			printNextStep();
			return;
		}
		
		boolean wasSynchronizing =
			sequenceStatus == SequenceStatus.SYNCHRONIZING;
		appendObservation(normalized);
		startSynchronization(VillagerRollSynchronizer.INITIAL_SEARCH_HORIZON);
		if(!wasSynchronizing)
		{
			message("Observation recorded. Synchronizing in the background.");
			printNextStep();
		}
	}
	
	private void appendObservation(VillagerRollNormalizedRoll observation)
	{
		observations.add(observation);
		if(observations.size() > MAX_OBSERVATIONS)
			observations.remove(0);
		stateGeneration++;
	}
	
	private void keepOnlyLatestObservation(
		VillagerRollNormalizedRoll observation)
	{
		observations.clear();
		appendObservation(observation);
	}
	
	private void startSynchronization(int horizon)
	{
		Long seed = getEffectiveSeed();
		if(seed == null)
		{
			message("World seed unknown. Use .villageroll seed <seed>");
			return;
		}
		if(horizon < VillagerRollSynchronizer.INITIAL_SEARCH_HORIZON
			|| horizon > VillagerRollSynchronizer.MAXIMUM_SEARCH_HORIZON)
		{
			message(
				"Synchronization horizon must be 1000, 100000, or 1000000.");
			return;
		}
		
		sequenceSeed = seed;
		currentRoll = null;
		sequenceStatus = SequenceStatus.SYNCHRONIZING;
		lastSyncHorizon = horizon;
		lastSyncResult = null;
		long generation = synchronizationGeneration.incrementAndGet();
		long capturedStateGeneration = stateGeneration;
		List<VillagerRollNormalizedRoll> snapshot = List.copyOf(observations);
		cancelFuture(synchronizationTask);
		BooleanSupplier cancelled =
			() -> generation != synchronizationGeneration.get();
		synchronizationTask = executor.submit(() -> {
			VillagerRollSynchronizer.Result result = VillagerRollSynchronizer
				.synchronize(seed, snapshot, 0, horizon, cancelled);
			MC.execute(() -> acceptSynchronization(generation,
				capturedStateGeneration, seed, snapshot, horizon, result));
		});
	}
	
	private void acceptSynchronization(long generation,
		long capturedStateGeneration, long seed,
		List<VillagerRollNormalizedRoll> snapshot, int horizon,
		VillagerRollSynchronizer.Result result)
	{
		if(generation != synchronizationGeneration.get()
			|| capturedStateGeneration != stateGeneration
			|| !snapshot.equals(observations)
			|| !Long.valueOf(seed).equals(getEffectiveSeed()))
			return;
		lastSyncResult = result;
		if(result.status() == VillagerRollSynchronizer.Status.CANCELLED)
			return;
		if(result.status() == VillagerRollSynchronizer.Status.NO_MATCH)
		{
			if(horizon == VillagerRollSynchronizer.INITIAL_SEARCH_HORIZON)
			{
				message(
					"No match in 1000 rolls; extending synchronization search to 100000.");
				startSynchronization(
					VillagerRollSynchronizer.EXTENDED_SEARCH_HORIZON);
			}else
			{
				if(!gapRecoveryAttempted && observations.size() > 1)
				{
					gapRecoveryAttempted = true;
					VillagerRollNormalizedRoll latest =
						observations.get(observations.size() - 1);
					keepOnlyLatestObservation(latest);
					sequenceStatus = SequenceStatus.UNKNOWN;
					currentRoll = null;
					message(
						"No match found. Another player may have advanced the global counter; restarting from the newest menu.");
					message(
						"Open 1-2 more newly generated novice librarian menus so the skipped rolls can be bypassed.");
					startSynchronization(horizon);
					return;
				}
				sequenceStatus = SequenceStatus.UNKNOWN;
				message("No matching sequence position found in " + horizon
					+ " rolls.");
				printNextStep();
			}
			return;
		}
		if(result.status() == VillagerRollSynchronizer.Status.AMBIGUOUS)
		{
			sequenceStatus = SequenceStatus.AMBIGUOUS;
			currentRoll = null;
			message("Sequence position is ambiguous. Candidates: "
				+ (result.moreCandidates() ? "50+"
					: result.candidateRolls().size()));
			message(
				"Next: open 1-2 more newly generated novice librarian menus; same or different villagers are okay.");
			return;
		}
		
		long firstMatchedRoll = result.uniqueFirstRoll();
		sequenceSeed = seed;
		currentRoll = firstMatchedRoll + snapshot.size() - 1L;
		sequenceStatus = SequenceStatus.VERIFYING;
		gapRecoveryAttempted = false;
		message(
			"Possible sequence position found at roll " + currentRoll + ".");
		message("Verifying next librarian...");
		queueTargetSearch();
	}
	
	private void queueTargetSearch()
	{
		if(targetEnchantmentId == null || sequenceSeed == null
			|| currentRoll == null)
		{
			cancelTargetSearch();
			return;
		}
		
		long generation = targetSearchGeneration.incrementAndGet();
		long capturedStateGeneration = stateGeneration;
		long seed = sequenceSeed;
		long firstRoll = currentRoll;
		int horizon = searchHorizon.getValueI();
		String enchantmentId = targetEnchantmentId;
		Integer level = targetLevel;
		cancelFuture(targetSearchTask);
		BooleanSupplier cancelled =
			() -> generation != targetSearchGeneration.get();
		targetSearchTask = executor.submit(() -> {
			List<VillagerRollPredictor.BookHit> hits =
				VillagerRollPredictor.findBooks(seed, firstRoll, horizon,
					enchantmentId, level, null, 1, cancelled);
			MC.execute(() -> {
				if(generation != targetSearchGeneration.get()
					|| capturedStateGeneration != stateGeneration)
					return;
				if(hits.isEmpty())
					targetHit = null;
				else
				{
					VillagerRollPredictor.BookHit hit = hits.get(0);
					targetHit = new TargetHit(hit.rollsAhead(),
						hit.absoluteRoll(), hit.slot(), hit.trade());
				}
				emitTargetWarning();
			});
		});
	}
	
	private VillagerRollPredictor.BookHit findNextTargetSynchronously(
		long firstRoll, int horizon)
	{
		if(targetEnchantmentId == null || sequenceSeed == null)
			return null;
		List<VillagerRollPredictor.BookHit> hits =
			VillagerRollPredictor.findBooks(sequenceSeed, firstRoll, horizon,
				targetEnchantmentId, targetLevel, null, 1, () -> false);
		return hits.isEmpty() ? null : hits.get(0);
	}
	
	private void emitTargetWarning()
	{
		if(targetHit == null || !chatWarnings.isChecked())
			return;
		long offset = targetHit.rollsAhead();
		if(targetHit.absoluteRoll() == lastWarningTargetRoll
			&& offset == lastWarningOffset)
			return;
		if(offset != 0 && offset != 1 && offset != 2 && offset != 5)
			return;
		if(offset > warningDistance.getValueI())
			return;
		lastWarningTargetRoll = targetHit.absoluteRoll();
		lastWarningOffset = offset;
		if(offset == 0)
		{
			message("TARGET HIT: " + formatBook(targetHit.trade()) + " - "
				+ targetHit.trade().emeraldPrice() + " emeralds");
			message("Do not break the lectern.");
			if(MC.level != null && MC.player != null)
				MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
					MC.player.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP,
					SoundSource.PLAYERS, 0.8F, 1.35F, false);
		}else if(offset == 1)
			message("NEXT ROLL: " + formatBook(targetHit.trade()).toUpperCase()
				+ " - " + targetHit.trade().emeraldPrice() + " EMERALDS");
		else
			message(
				formatBook(targetHit.trade()) + " in " + offset + " rolls.");
	}
	
	private void printSearchResult(String enchantmentId, Integer level,
		List<VillagerRollPredictor.BookHit> hits)
	{
		if(hits.isEmpty())
		{
			message("No " + shortId(enchantmentId) + " found in "
				+ searchHorizon.getValueI() + " rolls.");
			return;
		}
		VillagerRollPredictor.BookHit first = hits.get(0);
		message(formatBook(first.trade()));
		message("Next: +" + first.rollsAhead() + " rerolls");
		message("Price: " + first.trade().emeraldPrice() + " emeralds");
		message("Absolute roll: " + first.absoluteRoll());
		for(int i = 1; i < hits.size(); i++)
		{
			VillagerRollPredictor.BookHit hit = hits.get(i);
			message("Further: +" + hit.rollsAhead() + " "
				+ hit.trade().emeraldPrice() + " emeralds ("
				+ formatBook(hit.trade()) + ")");
		}
	}
	
	private void printUpcomingBook(int offset, int slot,
		VillagerRollTrade trade)
	{
		message("+" + offset + " slot " + slot + " " + formatBook(trade) + " - "
			+ trade.emeraldPrice() + "e");
	}
	
	private String formatTarget()
	{
		return targetEnchantmentId == null ? "none"
			: formatBookName(shortId(targetEnchantmentId), targetLevel);
	}
	
	private static String formatTrade(VillagerRollTrade trade)
	{
		return switch(trade.kind())
		{
			case PAPER -> "Paper";
			case BOOKSHELF -> "Bookshelf";
			case ENCHANTED_BOOK -> formatBook(trade);
		};
	}
	
	private static String formatBook(VillagerRollTrade trade)
	{
		return formatBookName(shortId(trade.enchantmentId()), trade.level());
	}
	
	private static String formatBookName(String id, Integer level)
	{
		StringBuilder result = new StringBuilder();
		for(String word : id.replace('_', ' ').split(" "))
		{
			if(word.isEmpty())
				continue;
			if(result.length() > 0)
				result.append(' ');
			result.append(Character.toUpperCase(word.charAt(0)))
				.append(word.substring(1));
		}
		if(level != null)
			result.append(' ').append(toRoman(level));
		return result.toString();
	}
	
	private static String shortId(String id)
	{
		int separator = id.lastIndexOf(':');
		return separator < 0 ? id : id.substring(separator + 1);
	}
	
	private static String toRoman(int level)
	{
		return switch(level)
		{
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			default -> Integer.toString(level);
		};
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
	
	private void resetTargetWarnings()
	{
		targetHit = null;
		lastWarningTargetRoll = Long.MIN_VALUE;
		lastWarningOffset = -1;
	}
	
	private void clearSequenceState()
	{
		cancelSynchronization();
		cancelTargetSearch();
		observations.clear();
		screenStateOrder.clear();
		seenScreenStates.clear();
		sequenceSeed = null;
		currentRoll = null;
		lastSyncResult = null;
		lastSyncHorizon = 0;
		sequenceStatus = SequenceStatus.UNKNOWN;
		gapRecoveryAttempted = false;
		stateGeneration++;
		resetTargetWarnings();
	}
	
	private void cancelSynchronization()
	{
		synchronizationGeneration.incrementAndGet();
		cancelFuture(synchronizationTask);
		synchronizationTask = null;
	}
	
	private void cancelTargetSearch()
	{
		targetSearchGeneration.incrementAndGet();
		cancelFuture(targetSearchTask);
		targetSearchTask = null;
	}
	
	private static void cancelFuture(Future<?> future)
	{
		if(future != null)
			future.cancel(true);
	}
	
	private void message(String text)
	{
		ChatUtils.message("[VillagerRoll] " + text);
	}
	
	private void printNextStep()
	{
		if(getEffectiveSeed() == null)
		{
			message(
				"Next: set the world seed with .villageroll seed <seed>. Singleplayer seeds are detected automatically.");
			return;
		}
		
		switch(sequenceStatus)
		{
			case UNKNOWN:
			if(observations.isEmpty())
				message(
					"Next: open a newly generated novice librarian trade screen. For an existing world, do not use .villageroll fresh.");
			else
				message(
					"Next: verify the seed and Trade Rebalance setting, then open 1-2 more newly generated menus.");
			break;
			case FRESH:
			message(
				"Next: open the novice librarian trade screen once; fresh mode assumes this is roll 0.");
			break;
			case SYNCHRONIZING:
			message(
				"Next: keep opening newly generated novice librarian menus; the search is running in the background.");
			break;
			case VERIFYING:
			message(
				"Next: open one more newly generated novice librarian menu to verify the possible position.");
			break;
			case SYNCHRONIZED:
			message(
				"Next: use .villageroll <enchantment> or .villageroll target <enchantment>. Each new novice menu advances the shared counter.");
			break;
			case AMBIGUOUS:
			message(
				"Next: open 1-2 more newly generated novice librarian menus; do not count lectern placement alone or reopen unchanged offers.");
			break;
		}
	}
	
	private record ScreenObservationKey(UUID villagerId, VillagerRollRoll roll)
	{}
	
	private record TargetHit(long rollsAhead, long absoluteRoll, int slot,
		VillagerRollTrade trade)
	{}
	
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
