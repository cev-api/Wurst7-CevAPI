/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import org.lwjgl.glfw.GLFW;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.clickgui.screens.LootSorterDestinationScreen;
import net.wurstclient.clickgui.screens.LootSorterDestinationChoiceScreen;
import net.wurstclient.clickgui.screens.LootSorterLayoutChoiceScreen;
import net.wurstclient.clickgui.screens.LootSorterSourceScanChoiceScreen;
import net.wurstclient.clickgui.screens.ChestSearchScreen;
import net.wurstclient.events.KeyPressListener;
import net.wurstclient.events.MouseButtonPressListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.lootsorter.BuiltInItemFilter;
import net.wurstclient.hacks.lootsorter.CustomItemFilterPreset;
import net.wurstclient.hacks.lootsorter.ItemFilter;
import net.wurstclient.hacks.lootsorter.LootSorterController;
import net.wurstclient.hacks.lootsorter.LootSorterState;
import net.wurstclient.hacks.lootsorter.DestinationRule;
import net.wurstclient.hacks.lootsorter.LootSorterProfile;
import net.wurstclient.hacks.lootsorter.LootSorterProfileStore;
import net.wurstclient.hacks.lootsorter.LootSorterSelectionPresetStore;
import net.wurstclient.hacks.lootsorter.LootSorterSelectionPresetStore.DestinationPreset;
import net.wurstclient.hacks.lootsorter.LootSorterSelectionPresetStore.SourcePreset;
import net.wurstclient.hacks.lootsorter.SourceContentsSnapshot;
import net.wurstclient.hacks.lootsorter.LootSorterSourceChestManager;
import net.wurstclient.hacks.lootsorter.CustomPresetStore;
import net.wurstclient.hacks.lootsorter.ItemFilterModifiers;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;

/** Wurst feature adapter; run mechanics live in the lootsorter package. */
@SearchTags({"loot sorter", "container sorter", "chest sorter"})
public final class LootSorterHack extends Hack
	implements UpdateListener, RightClickListener, KeyPressListener,
	MouseButtonPressListener, RenderListener
{
	private enum RuleState
	{
		ANY("Any"),
		ONLY("Only"),
		EXCLUDE("Exclude");
		
		private final String name;
		
		RuleState(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
		
		Boolean asBoolean()
		{
			return switch(this)
			{
				case ANY -> null;
				case ONLY -> Boolean.TRUE;
				case EXCLUDE -> Boolean.FALSE;
			};
		}
	}
	
	private enum PresetCapture
	{
		NONE,
		SOURCE,
		DESTINATION
	}
	
	private final CheckboxSetting renderSelections =
		new CheckboxSetting("Render selections", true);
	private final CheckboxSetting renderLabels = new CheckboxSetting(
		"Render labels",
		"Show destination filter labels within the configured range.", false);
	private final SliderSetting labelRange = new SliderSetting("Label range",
		12, 4, 64, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting navigationTimeout =
		new SliderSetting("Navigation timeout", 30, 5, 180, 1,
			ValueDisplay.INTEGER.withSuffix("s"));
	private final SliderSetting interactionTimeout =
		new SliderSetting("Interaction timeout", 6, 2, 30, 1,
			ValueDisplay.INTEGER.withSuffix("s"));
	private final SliderSetting actionDelay = new SliderSetting(
		"Transfer action delay",
		"Minimum wait after each confirmed transfer. 0 sends at most one normal quick-move per client tick.",
		0, 0, 1000, 10, ValueDisplay.INTEGER.withSuffix("ms"));
	private final SliderSetting maximumFailures = new SliderSetting(
		"Maximum failures", 3, 1, 10, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting automaticallyCloseScreens =
		new CheckboxSetting("Automatically close screens", true);
	private final CheckboxSetting keepRunningWhenUnfocused =
		new CheckboxSetting("Keep running while unfocused",
			"Temporarily disables Minecraft's Pause on Lost Focus while LootSorter is enabled.",
			true);
	private final CheckboxSetting stopOnManualInventoryInput =
		new CheckboxSetting("Pause on manual inventory input",
			"Pause and rescan after a manual container click instead of disabling LootSorter.",
			false);
	private final CheckboxSetting chatNotifications =
		new CheckboxSetting("Chat notifications", true);
	private final CheckboxSetting fullCompletionSummary =
		new CheckboxSetting("Full completion summary", true);
	private final CheckboxSetting saveLayoutsPerServer = new CheckboxSetting(
		"Save layouts per server",
		"Require a profile to be loaded only on the server where it was saved.",
		true);
	private final CheckboxSetting debugLogging = new CheckboxSetting(
		"Debug logging",
		"Log LootSorter state, route and inventory confirmation details to chat.",
		false);
	private final EnumSetting<BuiltInItemFilter> destinationFilter =
		new EnumSetting<>("Destination filter", BuiltInItemFilter.values(),
			BuiltInItemFilter.ALL);
	private final ItemListSetting customPresetItems = new ItemListSetting(
		"Custom preset items",
		"Click to use Wurst's searchable item selector for a reusable exact-item preset.");
	private final ItemListSetting customPresetExcludedItems =
		new ItemListSetting("Custom preset excluded items",
			"Click to use Wurst's searchable item selector for items the preset must exclude.");
	private final CheckboxSetting useCustomPreset =
		new CheckboxSetting("Use custom preset", false);
	private final TextFieldSetting customPresetName =
		new TextFieldSetting("Custom preset name", "Default custom preset");
	private final TextFieldSetting customPresetItemTags =
		new TextFieldSetting("Custom preset item tags",
			"Comma-separated item tags, e.g. minecraft:swords.", "");
	private final ButtonSetting saveCustomPreset =
		new ButtonSetting("Save custom preset", this::saveCustomPreset);
	private final ButtonSetting loadCustomPreset =
		new ButtonSetting("Load custom preset", this::loadCustomPreset);
	private final EnumSetting<RuleState> customEnchanted = new EnumSetting<>(
		"Custom preset enchantments", RuleState.values(), RuleState.ANY);
	private final EnumSetting<RuleState> customDamaged = new EnumSetting<>(
		"Custom preset damage", RuleState.values(), RuleState.ANY);
	private final EnumSetting<RuleState> customNamed = new EnumSetting<>(
		"Custom preset names", RuleState.values(), RuleState.ANY);
	private final SliderSetting customMinimumDurability =
		new SliderSetting("Custom minimum durability", 0, 0, 100, 1,
			ValueDisplay.INTEGER.withSuffix("% (0 = any)"));
	private final SliderSetting customMinimumEnchantment = new SliderSetting(
		"Custom minimum enchantment", 0, 0, 5, 1, ValueDisplay.INTEGER);
	private final TextFieldSetting customRequiredEnchantment =
		new TextFieldSetting("Custom required enchantment",
			"Optional enchantment ID, e.g. minecraft:sharpness.", "");
	private final EnumSetting<RuleState> customTreasure = new EnumSetting<>(
		"Custom treasure enchantments", RuleState.values(), RuleState.ANY);
	private final EnumSetting<RuleState> customCurses = new EnumSetting<>(
		"Custom curse enchantments", RuleState.values(), RuleState.ANY);
	private final TextFieldSetting profileName =
		new TextFieldSetting("Profile name", "Default");
	private final ButtonSetting saveProfile =
		new ButtonSetting("Save layout profile", this::saveCurrentProfile);
	private final ButtonSetting loadProfile =
		new ButtonSetting("Load layout profile", this::loadNamedProfile);
	private final ButtonSetting showSourceChests = new ButtonSetting(
		"Show source chests", () -> showSourceChestSearch(null));
	private LootSorterController controller;
	private LootSorterProfile loadedProfile;
	private LootSorterProfile retainedLayout;
	private CustomItemFilterPreset activeCustomPreset;
	private boolean changedPauseOnLostFocus;
	private boolean pauseOnLostFocusBeforeRun;
	private PresetCapture presetCapture = PresetCapture.NONE;
	private String presetCaptureName;
	private String activeSourcePresetName;
	private boolean discardRetainedLayoutOnDisable;
	
	public LootSorterHack()
	{
		super("LootSorter");
		setCategory(Category.ITEMS);
		addSetting(renderSelections);
		addSetting(renderLabels);
		addSetting(labelRange);
		addSetting(destinationFilter);
		addSetting(useCustomPreset);
		addSetting(customPresetItems);
		addSetting(customPresetExcludedItems);
		addSetting(customPresetName);
		addSetting(customPresetItemTags);
		addSetting(saveCustomPreset);
		addSetting(loadCustomPreset);
		addSetting(customEnchanted);
		addSetting(customDamaged);
		addSetting(customNamed);
		addSetting(customMinimumDurability);
		addSetting(customMinimumEnchantment);
		addSetting(customRequiredEnchantment);
		addSetting(customTreasure);
		addSetting(customCurses);
		addSetting(profileName);
		addSetting(saveProfile);
		addSetting(loadProfile);
		addSetting(showSourceChests);
		addSetting(navigationTimeout);
		addSetting(interactionTimeout);
		addSetting(actionDelay);
		addSetting(maximumFailures);
		addSetting(automaticallyCloseScreens);
		addSetting(keepRunningWhenUnfocused);
		addSetting(stopOnManualInventoryInput);
		addSetting(chatNotifications);
		addSetting(fullCompletionSummary);
		addSetting(saveLayoutsPerServer);
		addSetting(debugLogging);
	}
	
	@Override
	protected void onEnable()
	{
		updateUnfocusedOperation();
		controller = new LootSorterController(MC, this::getSelectedFilter,
			this::openDestinationEditor, this::persistSourcePresetContents);
		if(presetCapture == PresetCapture.SOURCE)
			controller.begin();
		else if(presetCapture == PresetCapture.DESTINATION)
			controller.beginDestinationSelection();
		else if(loadedProfile != null)
		{
			if(!controller.restoreProfile(loadedProfile))
			{
				ChatUtils.error(
					"LootSorter: saved layout no longer contains valid containers.");
				setEnabled(false);
				return;
			}
			loadedProfile = null;
			openLayoutChoice("Saved layout is ready.");
		}else if(retainedLayout != null
			&& controller.restoreProfile(retainedLayout))
			openLayoutChoice("Previous layout is ready.");
		else
		{
			activeSourcePresetName = null;
			controller.begin();
		}
		if(controller.getState() == LootSorterState.DISABLED)
		{
			setEnabled(false);
			return;
		}
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RightClickListener.class, this);
		EVENTS.add(KeyPressListener.class, this);
		EVENTS.add(MouseButtonPressListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	private void openLayoutChoice(String message)
	{
		MC.gui.setScreen(new LootSorterLayoutChoiceScreen(MC.gui.screen(),
			message, this::openRestoredSourceScanChoice, controller::begin));
	}
	
	private void openRestoredSourceScanChoice()
	{
		if(controller == null)
			return;
		if(!controller.hasSavedSourceContents())
		{
			openRestoredDestinationChoice(true);
			return;
		}
		openSourceScanChoice("Use saved source contents?",
			() -> openRestoredDestinationChoice(false),
			() -> openRestoredDestinationChoice(true));
	}
	
	private void openRestoredDestinationChoice(boolean recheckSources)
	{
		if(controller == null)
			return;
		MC.gui.setScreen(new LootSorterDestinationChoiceScreen(MC.gui.screen(),
			"Use the saved destinations?",
			() -> controller.startSorting(recheckSources),
			controller::beginDestinationReplacement));
	}
	
	private void confirmDestinationSelection()
	{
		if(controller == null || !controller.hasConfiguredDestinations())
		{
			if(controller != null)
				controller.startSorting(true);
			return;
		}
		if(controller.hasSavedSourceContents())
		{
			openSourceScanChoice("Use saved source contents?",
				() -> controller.startSorting(false),
				() -> controller.startSorting(true));
			return;
		}
		controller.startSorting(true);
	}
	
	private void openSourceScanChoice(String message, Runnable useSaved,
		Runnable rescan)
	{
		MC.gui.setScreen(new LootSorterSourceScanChoiceScreen(MC.gui.screen(),
			message, useSaved, rescan));
	}
	
	@Override
	protected void onDisable()
	{
		restorePauseOnLostFocus();
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RightClickListener.class, this);
		EVENTS.remove(KeyPressListener.class, this);
		EVENTS.remove(MouseButtonPressListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		if(controller != null)
		{
			if(!discardRetainedLayoutOnDisable && MC.level != null)
				retainedLayout = controller.createProfile("session", "*",
					MC.level.dimension().identifier().toString(),
					dimensionTypeIdentifier());
			controller.stop(null);
		}
		controller = null;
		if(!discardRetainedLayoutOnDisable)
		{
			presetCapture = PresetCapture.NONE;
			presetCaptureName = null;
		}
		discardRetainedLayoutOnDisable = false;
	}
	
	private ItemFilter getSelectedFilter()
	{
		if(!useCustomPreset.isChecked())
			return destinationFilter.getSelected();
		return getCustomPreset();
	}
	
	private void openDestinationEditor(DestinationRule rule)
	{
		boolean savingPreset = presetCapture == PresetCapture.DESTINATION;
		MC.gui.setScreen(new LootSorterDestinationScreen(MC.gui.screen(), rule,
			customPresetItems, this::createDraftCustomPreset,
			this::saveCustomPreset, () -> controller.removeDestination(rule),
			savingPreset ? this::finishPresetCapture
				: this::confirmDestinationSelection,
			savingPreset ? "Confirm & save destination preset"
				: "Confirm & start sorting"));
	}
	
	/** Starts named source setup without beginning a sorting run. */
	public boolean beginSourcePresetSetup(String name)
	{
		return beginPresetSetup(PresetCapture.SOURCE, name);
	}
	
	/**
	 * Saves the sources already selected in the current setup when possible.
	 * This lets a user press Enter to finish source selection, then use
	 * {@code .lootsorter set source <name>} without throwing their selection
	 * away. The store replaces an identically named preset deliberately.
	 */
	public boolean saveOrBeginSourcePreset(String name)
	{
		String presetName = normalizePresetName(name);
		if(presetName == null)
		{
			ChatUtils
				.error("LootSorter: preset names must be 1-64 characters.");
			return false;
		}
		if(controller != null && (controller
			.getState() == LootSorterState.SELECTING_SOURCES
			|| controller.getState() == LootSorterState.SELECTING_DESTINATIONS))
			return saveCurrentSourcePreset(presetName);
		return beginSourcePresetSetup(presetName);
	}
	
	/** Starts named destination setup without beginning a sorting run. */
	public boolean beginDestinationPresetSetup(String name)
	{
		return beginPresetSetup(PresetCapture.DESTINATION, name);
	}
	
	private boolean beginPresetSetup(PresetCapture capture, String name)
	{
		String presetName = normalizePresetName(name);
		if(presetName == null)
		{
			ChatUtils
				.error("LootSorter: preset names must be 1-64 characters.");
			return false;
		}
		if(MC.player == null || MC.level == null)
		{
			ChatUtils
				.error("LootSorter: join a world before creating a preset.");
			return false;
		}
		if(isEnabled())
		{
			ChatUtils.error("LootSorter: disable the current selection or run "
				+ "before starting a separate preset setup.");
			return false;
		}
		retainedLayout = null;
		loadedProfile = null;
		activeSourcePresetName = null;
		presetCapture = capture;
		presetCaptureName = presetName;
		setEnabled(true);
		return isEnabled();
	}
	
	/** Loads a source preset during the normal source-selection phase. */
	public boolean loadSourcePresetForRun(String name)
	{
		if(!requireSelectionPhase(LootSorterState.SELECTING_SOURCES, "source"))
			return false;
		SourcePreset preset =
			new LootSorterSelectionPresetStore().findSource(name).orElse(null);
		if(preset == null)
		{
			ChatUtils.error("LootSorter: no source preset named " + name + ".");
			return false;
		}
		if(!belongsToCurrentWorld(preset.serverIdentifier(),
			preset.dimensionKey(), preset.dimensionType()))
			return false;
		if(!controller.restoreSources(preset.sources())
			|| !controller.confirmLoadedSources())
		{
			ChatUtils.error("LootSorter: source preset " + preset.name()
				+ " no longer contains valid containers.");
			return false;
		}
		activeSourcePresetName = preset.name();
		boolean restoredContents =
			controller.restoreSourceContents(preset.sourceContents());
		ChatUtils.message("LootSorter: loaded source preset " + preset.name()
			+ (restoredContents ? " with saved contents" : "")
			+ ". Now select or load destinations.");
		return true;
	}
	
	/**
	 * Loads destination filters during the normal destination-selection phase.
	 */
	public boolean loadDestinationPresetForRun(String name)
	{
		if(!requireSelectionPhase(LootSorterState.SELECTING_DESTINATIONS,
			"destination"))
			return false;
		DestinationPreset preset = new LootSorterSelectionPresetStore()
			.findDestination(name).orElse(null);
		if(preset == null)
		{
			ChatUtils
				.error("LootSorter: no destination preset named " + name + ".");
			return false;
		}
		if(!belongsToCurrentWorld(preset.serverIdentifier(),
			preset.dimensionKey(), preset.dimensionType()))
			return false;
		if(!controller.restoreDestinations(preset.destinations()))
		{
			ChatUtils.error("LootSorter: destination preset " + preset.name()
				+ " no longer contains valid containers.");
			return false;
		}
		ChatUtils.message("LootSorter: loaded destination preset "
			+ preset.name() + ". Press Enter to start sorting.");
		return true;
	}
	
	/**
	 * Saves the current full scan back to the source preset that supplied it.
	 */
	private void persistSourcePresetContents(
		List<SourceContentsSnapshot> sourceContents)
	{
		if(activeSourcePresetName == null || sourceContents == null
			|| sourceContents.isEmpty())
			return;
		try
		{
			LootSorterSelectionPresetStore store =
				new LootSorterSelectionPresetStore();
			SourcePreset preset =
				store.findSource(activeSourcePresetName).orElse(null);
			if(preset == null)
				return;
			store.saveSource(new SourcePreset(preset.name(),
				preset.serverIdentifier(), preset.dimensionKey(),
				preset.dimensionType(), preset.sources(), sourceContents));
		}catch(IOException ignored)
		{
			// A run must not stop because an optional cache write failed.
		}
	}
	
	/**
	 * Opens a read-only ChestSearch view containing only LootSorter sources.
	 * Without a name, it uses the current selection or retained layout.
	 */
	public boolean showSourceChestSearch(String presetName)
	{
		if(MC.player == null || MC.level == null)
		{
			ChatUtils.error(
				"LootSorter: join a world before opening source search.");
			return false;
		}
		List<LootSorterProfile.ContainerPos> sources;
		List<SourceContentsSnapshot> contents;
		String server;
		String dimension;
		String title = "LootSorter source chests";
		if(presetName != null && !presetName.isBlank())
		{
			SourcePreset preset = new LootSorterSelectionPresetStore()
				.findSource(presetName).orElse(null);
			if(preset == null)
			{
				ChatUtils.error(
					"LootSorter: no source preset named " + presetName + ".");
				return false;
			}
			sources = preset.sources();
			contents = preset.sourceContents();
			server = preset.serverIdentifier();
			dimension = preset.dimensionKey();
			title += ": " + preset.name();
		}else if(controller != null && !controller.exportSources().isEmpty())
		{
			sources = controller.exportSources();
			contents = controller.exportSourceContents();
			server = serverIdentifier();
			dimension = MC.level.dimension().identifier().toString();
		}else if(retainedLayout != null && !retainedLayout.sources().isEmpty())
		{
			sources = retainedLayout.sources();
			contents = retainedLayout.sourceContents();
			server = retainedLayout.serverIdentifier();
			dimension = retainedLayout.dimensionKey();
		}else
		{
			ChatUtils.error("LootSorter: no current source layout to show.");
			return false;
		}
		if(sources.isEmpty())
		{
			ChatUtils.error("LootSorter: that source layout is empty.");
			return false;
		}
		ChestSearchScreen screen = new ChestSearchScreen(MC.gui.screen(),
			new LootSorterSourceChestManager(sources, contents, server,
				dimension, MC.level.registryAccess()),
			false, title, true, true);
		// Commands are not guaranteed to be called from the render thread.
		// Queue the screen change so .lootsort show always opens the actual
		// ChestSearch UI instead of being lost while a chat command is handled.
		MC.execute(() -> MC.gui.setScreen(screen));
		int scanned = contents == null ? 0 : contents.size();
		ChatUtils.message("LootSorter: opened source chest search for "
			+ sources.size() + " source container"
			+ (sources.size() == 1 ? "" : "s") + " (" + scanned
			+ " saved content record" + (scanned == 1 ? "" : "s") + ").");
		return true;
	}
	
	public List<String> getSourcePresetNames()
	{
		return new LootSorterSelectionPresetStore().load().sources().stream()
			.map(SourcePreset::name).sorted(String::compareToIgnoreCase)
			.toList();
	}
	
	public List<String> getDestinationPresetNames()
	{
		return new LootSorterSelectionPresetStore().load().destinations()
			.stream().map(DestinationPreset::name)
			.sorted(String::compareToIgnoreCase).toList();
	}
	
	public boolean deleteSourcePreset(String name)
	{
		return deletePreset(name, true);
	}
	
	public boolean deleteDestinationPreset(String name)
	{
		return deletePreset(name, false);
	}
	
	private boolean deletePreset(String name, boolean source)
	{
		try
		{
			LootSorterSelectionPresetStore store =
				new LootSorterSelectionPresetStore();
			boolean deleted = source ? store.deleteSource(name)
				: store.deleteDestination(name);
			if(!deleted)
				ChatUtils.error(
					"LootSorter: no " + (source ? "source" : "destination")
						+ " preset named " + name + ".");
			return deleted;
		}catch(IOException e)
		{
			ChatUtils.error("LootSorter: could not delete the preset.");
			return false;
		}
	}
	
	private boolean requireSelectionPhase(LootSorterState expected, String kind)
	{
		if(controller != null && controller.getState() == expected)
			return true;
		ChatUtils.error("LootSorter: start LootSorter and reach the " + kind
			+ " selection step before loading that preset.");
		return false;
	}
	
	private boolean belongsToCurrentWorld(String presetServer,
		String presetDimension, String presetDimensionType)
	{
		if(MC.level == null)
			return false;
		if(!serverIdentifier().equals(presetServer))
		{
			ChatUtils.error(
				"LootSorter: that preset belongs to a different " + "server.");
			return false;
		}
		if(!MC.level.dimension().identifier().toString().equals(presetDimension)
			|| !dimensionTypeIdentifier().equals(presetDimensionType))
		{
			ChatUtils.error("LootSorter: that preset belongs to a different "
				+ "dimension.");
			return false;
		}
		return true;
	}
	
	private String normalizePresetName(String name)
	{
		if(name == null)
			return null;
		String trimmed = name.trim();
		return trimmed.isEmpty() || trimmed.length() > 64 ? null : trimmed;
	}
	
	private boolean saveCurrentSourcePreset(String presetName)
	{
		if(controller == null || MC.level == null
			|| controller.exportSources().isEmpty())
		{
			ChatUtils.error(
				"LootSorter: select at least one source before saving the preset.");
			return false;
		}
		try
		{
			List<SourceContentsSnapshot> contents =
				controller.hasCompleteSourceContents()
					? controller.exportSourceContents() : List.of();
			new LootSorterSelectionPresetStore()
				.saveSource(new SourcePreset(presetName, serverIdentifier(),
					MC.level.dimension().identifier().toString(),
					dimensionTypeIdentifier(), controller.exportSources(),
					contents));
			activeSourcePresetName = presetName;
			ChatUtils.message("LootSorter: saved source preset " + presetName
				+ " (existing preset overwritten if present).");
			return true;
		}catch(IOException e)
		{
			ChatUtils.error("LootSorter: could not save the preset.");
			return false;
		}
	}
	
	private void finishPresetCapture()
	{
		if(controller == null || presetCapture == PresetCapture.NONE
			|| presetCaptureName == null || MC.level == null)
			return;
		try
		{
			LootSorterSelectionPresetStore store =
				new LootSorterSelectionPresetStore();
			if(presetCapture == PresetCapture.SOURCE)
			{
				if(!saveCurrentSourcePreset(presetCaptureName))
					return;
			}else
			{
				if(controller
					.getState() != LootSorterState.SELECTING_DESTINATIONS
					|| controller.exportDestinations().isEmpty())
				{
					ChatUtils
						.error("LootSorter: configure at least one destination "
							+ "before saving the preset.");
					return;
				}
				store.saveDestination(
					new DestinationPreset(presetCaptureName, serverIdentifier(),
						MC.level.dimension().identifier().toString(),
						dimensionTypeIdentifier(),
						controller.exportDestinations()));
				ChatUtils.message("LootSorter: saved destination preset "
					+ presetCaptureName + ".");
			}
		}catch(IOException e)
		{
			ChatUtils.error("LootSorter: could not save the preset.");
			return;
		}
		presetCapture = PresetCapture.NONE;
		presetCaptureName = null;
		discardRetainedLayoutOnDisable = true;
		setEnabled(false);
	}
	
	private ItemFilter getCustomPreset()
	{
		if(activeCustomPreset != null)
			return activeCustomPreset;
		return createDraftCustomPreset("Custom preset");
	}
	
	/** The destination editor must always reflect the item list just edited. */
	private ItemFilter createDraftCustomPreset()
	{
		return createDraftCustomPreset("Custom item list");
	}
	
	private ItemFilter saveCustomPreset(String name)
	{
		if(name == null || name.isBlank())
		{
			ChatUtils
				.error("LootSorter: enter a name for the custom item list.");
			return null;
		}
		customPresetName.setValue(name.trim());
		try
		{
			CustomPresetStore store = new CustomPresetStore();
			List<CustomItemFilterPreset> presets =
				new ArrayList<>(store.load());
			presets.removeIf(preset -> preset.getName()
				.equalsIgnoreCase(customPresetName.getValue()));
			activeCustomPreset =
				createDraftCustomPreset(customPresetName.getValue());
			presets.add(activeCustomPreset);
			store.save(presets);
			ChatUtils.message("LootSorter: saved custom preset "
				+ customPresetName.getValue() + ".");
			return activeCustomPreset;
		}catch(Exception e)
		{
			ChatUtils.error("LootSorter: could not save custom preset.");
			return null;
		}
	}
	
	private void saveCustomPreset()
	{
		saveCustomPreset(customPresetName.getValue());
	}
	
	private CustomItemFilterPreset createDraftCustomPreset(String name)
	{
		Integer durability = customMinimumDurability.getValueI();
		Integer enchantment = customMinimumEnchantment.getValueI();
		return new CustomItemFilterPreset(name,
			new LinkedHashSet<>(customPresetItems.getItemNames()),
			new LinkedHashSet<>(customPresetExcludedItems.getItemNames()),
			parseTags(),
			new ItemFilterModifiers(customEnchanted.getSelected().asBoolean(),
				customDamaged.getSelected().asBoolean(),
				durability == 0 ? null : durability,
				enchantment == 0 ? null : enchantment,
				customNamed.getSelected().asBoolean(),
				customRequiredEnchantment.getValue().isBlank() ? null
					: customRequiredEnchantment.getValue().trim(),
				null, customTreasure.getSelected().asBoolean(),
				customCurses.getSelected().asBoolean()));
	}
	
	private LinkedHashSet<String> parseTags()
	{
		LinkedHashSet<String> tags = new LinkedHashSet<>();
		for(String tag : customPresetItemTags.getValue().split(","))
			if(!tag.isBlank())
				tags.add(tag.trim());
		return tags;
	}
	
	private void loadCustomPreset()
	{
		activeCustomPreset = new CustomPresetStore().load().stream()
			.filter(preset -> preset.getName()
				.equalsIgnoreCase(customPresetName.getValue()))
			.findFirst().orElse(null);
		if(activeCustomPreset == null)
		{
			ChatUtils.error("LootSorter: no custom preset named "
				+ customPresetName.getValue() + ".");
			return;
		}
		useCustomPreset.setChecked(true);
		ChatUtils.message("LootSorter: loaded custom preset "
			+ activeCustomPreset.getName() + ".");
	}
	
	private void saveCurrentProfile()
	{
		if(controller == null || MC.level == null)
		{
			ChatUtils.error("LootSorter: select a layout before saving it.");
			return;
		}
		try
		{
			LootSorterProfileStore store = new LootSorterProfileStore();
			List<LootSorterProfile> profiles = store.load();
			profiles.removeIf(profile -> profile.name()
				.equalsIgnoreCase(profileName.getValue()));
			profiles.add(controller.createProfile(profileName.getValue(),
				saveLayoutsPerServer.isChecked() ? serverIdentifier() : "*",
				MC.level.dimension().identifier().toString(),
				dimensionTypeIdentifier()));
			store.save(profiles);
			ChatUtils.message(
				"LootSorter: saved profile " + profileName.getValue() + ".");
		}catch(Exception e)
		{
			ChatUtils.error("LootSorter: could not save layout profile.");
		}
	}
	
	private void loadNamedProfile()
	{
		LootSorterProfileStore store = new LootSorterProfileStore();
		loadedProfile = store.load().stream().filter(
			profile -> profile.name().equalsIgnoreCase(profileName.getValue()))
			.findFirst().orElse(null);
		if(loadedProfile == null)
		{
			ChatUtils.error(
				"LootSorter: no profile named " + profileName.getValue() + ".");
			return;
		}
		if(!loadedProfile.serverIdentifier().equals("*")
			&& !loadedProfile.serverIdentifier().equals(serverIdentifier()))
		{
			ChatUtils
				.error("LootSorter: profile belongs to a different server.");
			loadedProfile = null;
			return;
		}
		if(MC.level != null && !loadedProfile.dimensionKey()
			.equals(MC.level.dimension().identifier().toString()))
		{
			ChatUtils
				.error("LootSorter: profile belongs to a different dimension.");
			loadedProfile = null;
			return;
		}
		if(MC.level != null
			&& !loadedProfile.dimensionType().equals(dimensionTypeIdentifier()))
		{
			ChatUtils.error(
				"LootSorter: profile belongs to a different dimension type.");
			loadedProfile = null;
			return;
		}
		if(isEnabled())
			setEnabled(false);
		setEnabled(true);
	}
	
	private String serverIdentifier()
	{
		if(MC.hasSingleplayerServer())
			return "singleplayer";
		return MC.getCurrentServer() == null || MC.getCurrentServer().ip == null
			? "unknown" : MC.getCurrentServer().ip.trim().toLowerCase();
	}
	
	private String dimensionTypeIdentifier()
	{
		return MC.level.dimensionTypeRegistration().unwrapKey()
			.map(key -> key.identifier().toString())
			.orElseGet(() -> MC.level.dimensionType().toString());
	}
	
	@Override
	public void onUpdate()
	{
		updateUnfocusedOperation();
		if(controller != null)
		{
			controller.tick(navigationTimeout.getValueI() * 1000L,
				interactionTimeout.getValueI() * 1000L, actionDelay.getValueI(),
				maximumFailures.getValueI(),
				automaticallyCloseScreens.isChecked(),
				chatNotifications.isChecked(),
				fullCompletionSummary.isChecked(), debugLogging.isChecked());
			if(controller.getState() == LootSorterState.COMPLETED
				|| controller.getState() == LootSorterState.ERROR)
				setEnabled(false);
		}
	}
	
	private void updateUnfocusedOperation()
	{
		if(MC.options == null)
			return;
		if(keepRunningWhenUnfocused.isChecked())
		{
			if(!changedPauseOnLostFocus)
			{
				pauseOnLostFocusBeforeRun = MC.options.pauseOnLostFocus;
				changedPauseOnLostFocus = true;
			}
			MC.options.pauseOnLostFocus = false;
		}else
			restorePauseOnLostFocus();
	}
	
	private void restorePauseOnLostFocus()
	{
		if(changedPauseOnLostFocus && MC.options != null)
			MC.options.pauseOnLostFocus = pauseOnLostFocusBeforeRun;
		changedPauseOnLostFocus = false;
	}
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		if(controller != null)
		{
			if(controller.getState() == LootSorterState.SELECTING_SOURCES)
				activeSourcePresetName = null;
			controller.onRightClick(event);
		}
	}
	
	@Override
	public void onKeyPress(KeyPressEvent event)
	{
		// Do not interpret the keys used to type a chat command or edit a GUI
		// field as sorter hotkeys (for example, Right Shift for capitals).
		if(MC.gui.screen() != null)
			return;
		if(presetCapture != PresetCapture.NONE
			&& event.getAction() == GLFW.GLFW_PRESS
			&& event.getKeyCode() == GLFW.GLFW_KEY_ENTER
			&& !(MC.gui.screen() instanceof LootSorterDestinationScreen))
		{
			finishPresetCapture();
			return;
		}
		if(controller != null && event.getAction() == GLFW.GLFW_PRESS
			&& event.getKeyCode() == GLFW.GLFW_KEY_ENTER
			&& controller.getState() == LootSorterState.SELECTING_DESTINATIONS
			&& !(MC.gui.screen() instanceof LootSorterDestinationScreen))
		{
			confirmDestinationSelection();
			return;
		}
		if(controller != null)
			controller.onKeyPress(event);
	}
	
	@Override
	public void onMouseButtonPress(MouseButtonPressEvent event)
	{
		if(stopOnManualInventoryInput.isChecked() && controller != null)
			controller.onManualInventoryInput();
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(renderSelections.isChecked() && controller != null)
			controller.render(matrices, renderLabels.isChecked(),
				labelRange.getValue());
	}
}
