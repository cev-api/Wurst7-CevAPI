/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatInputListener.ChatInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.seedmapper.SeedMapperData;
import net.wurstclient.seedmapper.VendorSeedMapperLoader;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SpacerSetting;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.text.WText;

@DontSaveState
@SearchTags({"SeedMapper", "seed mapper", "seedmap", "Seed Mapper Helper"})
public final class SeedMapperHelperHack extends Hack
	implements ChatInputListener, UpdateListener
{
	private static final Predicate<String> SEED_VALIDATOR =
		value -> value == null || value.isEmpty() || value.matches("-?\\d+");
	private static final Predicate<String> AMOUNT_VALIDATOR =
		value -> value == null || value.isEmpty() || value.matches("\\d+");
	private static final Predicate<String> ENCHANT_LEVEL_VALIDATOR = value -> {
		if(value == null || value.isEmpty())
			return true;
		if(value.equals("*"))
			return true;
		return value.matches("\\d+");
	};
	private static final Predicate<String> COORDINATE_VALIDATOR = value -> {
		if(value == null || value.isEmpty())
			return true;
		return value.matches("^[~^]?$")
			|| value.matches("^[~^]?-?\\d+(?:\\.\\d+)?$")
			|| value.matches("-?\\d+(?:\\.\\d+)?");
	};
	private static final Predicate<String> ROTATION_VALIDATOR =
		value -> value == null || value.isEmpty()
			|| value.matches("-?\\d+(?:\\.\\d+)?");
	private static final Pattern CSV_SPLIT = Pattern.compile("\\s*,\\s*");
	private static final Pattern SEED_RESPONSE_PATTERN = Pattern.compile(
		"(?i)seed(?:\\s+is)?(?:\\s+currently)?(?:\\s+set)?(?:\\s+to)?\\s*(-?\\d+)");
	
	private final SeedMapperData data = VendorSeedMapperLoader.getData();
	
	private final ButtonSetting statusButton =
		new ButtonSetting("Check SeedMapper status", this::reportStatus);
	private final ButtonSetting seedMapButton =
		new ButtonSetting("Open Seed Map UI", this::openSeedMapUi);
	private final ButtonSetting minimapButton =
		new ButtonSetting("Open SeedMap minimap", this::openSeedMapMinimap);
	private final ButtonSetting enableMinimapButton = new ButtonSetting(
		"Show SeedMap minimap", () -> setMinimapEnabled(true));
	private final ButtonSetting disableMinimapButton = new ButtonSetting(
		"Hide SeedMap minimap", () -> setMinimapEnabled(false));
	private final ButtonSetting clearOverlaysButton =
		new ButtonSetting("Clear overlays", this::clearOverlays);
	private final ButtonSetting checkSeedButton =
		new ButtonSetting("Check current seed",
			() -> runSimpleCommand("sm:checkseed", "check SeedMapper seed"));
	private final ButtonSetting stopTaskButton = new ButtonSetting(
		"Stop locator threads", () -> runSimpleCommand("sm:stoptask",
			"cancel SeedMapper locator tasks"));
	private final CheckboxSetting showCommandFeedbackSetting =
		new CheckboxSetting("Show command feedback", true);
	private final TextFieldSetting seedCheckInputSetting =
		new TextFieldSetting("Seed set", "", SEED_VALIDATOR);
	private final ButtonSetting applySeedInputButton =
		new ButtonSetting("Set seed", this::applySeedInput);
	private final TextFieldSetting savedSeedValueSetting =
		new TextFieldSetting("Saved seed value", "", SEED_VALIDATOR);
	private final ButtonSetting addSavedSeedButton =
		new ButtonSetting("Add saved seed", this::addSavedSeed);
	private final TextFieldSetting seedResolutionOrderSetting =
		new TextFieldSetting("Seed resolution order",
			"COMMAND_SOURCE,SEED_CONFIG,SAVED_SEEDS_CONFIG");
	private final ButtonSetting applySeedResolutionOrderButton =
		new ButtonSetting("Apply resolution order",
			this::applySeedResolutionOrder);
	private final CheckboxSetting oreAirCheckSetting =
		new CheckboxSetting("Ore air check enabled", false);
	private final ButtonSetting applyOreAirCheckButton =
		new ButtonSetting("Apply OreAirCheck", this::applyOreAirCheck);
	private final CheckboxSetting clearSeedMapCachesSetting =
		new CheckboxSetting("Clear SeedMap caches on close", false);
	private final ButtonSetting applyClearSeedMapCachesButton =
		new ButtonSetting("Apply ClearSeedMapCachesOnClose",
			this::applyClearSeedMapCaches);
	private final SliderSetting seedMapThreadsSetting = new SliderSetting(
		"Seed map threads", 4, 1, 32, 1, ValueDisplay.INTEGER);
	private final ButtonSetting applySeedMapThreadsButton =
		new ButtonSetting("Apply SeedMapThreads", this::applySeedMapThreads);
	private final SliderSetting minimapOffsetXSetting = new SliderSetting(
		"Minimap X offset", 4, 0, 512, 1, ValueDisplay.INTEGER);
	private final ButtonSetting applyMinimapOffsetXButton = new ButtonSetting(
		"Apply SeedMapMinimapOffsetX", this::applyMinimapOffsetX);
	private final SliderSetting minimapOffsetYSetting = new SliderSetting(
		"Minimap Y offset", 4, 0, 512, 1, ValueDisplay.INTEGER);
	private final ButtonSetting applyMinimapOffsetYButton = new ButtonSetting(
		"Apply SeedMapMinimapOffsetY", this::applyMinimapOffsetY);
	private final SliderSetting minimapWidthSetting = new SliderSetting(
		"Minimap width", 205, 64, 512, 1, ValueDisplay.INTEGER);
	private final ButtonSetting applyMinimapWidthButton =
		new ButtonSetting("Apply SeedMapMinimapWidth", this::applyMinimapWidth);
	private final SliderSetting minimapHeightSetting = new SliderSetting(
		"Minimap height", 205, 64, 512, 1, ValueDisplay.INTEGER);
	private final ButtonSetting applyMinimapHeightButton = new ButtonSetting(
		"Apply SeedMapMinimapHeight", this::applyMinimapHeight);
	private final CheckboxSetting minimapRotateSetting =
		new CheckboxSetting("Rotate minimap with player", true);
	private final ButtonSetting applyMinimapRotateButton =
		new ButtonSetting("Apply SeedMapMinimapRotateWithPlayer",
			this::applyMinimapRotateWithPlayer);
	private final SliderSetting minimapPixelsPerBiomeSetting =
		new SliderSetting("Minimap pixels per biome", 4.0, 0.05, 150.0, 0.05,
			ValueDisplay.DECIMAL);
	private final ButtonSetting applyMinimapPixelsPerBiomeButton =
		new ButtonSetting("Apply SeedMapMinimapPixelsPerBiome",
			this::applyMinimapPixelsPerBiome);
	private final SliderSetting minimapIconScaleSetting = new SliderSetting(
		"Minimap icon scale", 1.0, 0.25, 4.0, 0.05, ValueDisplay.DECIMAL);
	private final ButtonSetting applyMinimapIconScaleButton = new ButtonSetting(
		"Apply SeedMapMinimapIconScale", this::applyMinimapIconScale);
	private final SliderSetting minimapOpacitySetting = new SliderSetting(
		"Minimap opacity", 1.0, 0.05, 1.0, 0.01, ValueDisplay.DECIMAL);
	private final ButtonSetting applyMinimapOpacityButton = new ButtonSetting(
		"Apply SeedMapMinimapOpacity", this::applyMinimapOpacity);
	private final SliderSetting pixelsPerBiomeSetting = new SliderSetting(
		"Pixels per biome", 6.0, 1.0, 32.0, 0.1, ValueDisplay.DECIMAL);
	private final ButtonSetting applyPixelsPerBiomeButton =
		new ButtonSetting("Apply PixelsPerBiome", this::applyPixelsPerBiome);
	private final TextFieldSetting toggledFeaturesSetting =
		new TextFieldSetting("Toggled features", "");
	private final ButtonSetting applyToggledFeaturesButton =
		new ButtonSetting("Apply ToggledFeatures", this::applyToggledFeatures);
	private final CheckboxSetting devModeSetting =
		new CheckboxSetting("Dev mode enabled", false);
	private final ButtonSetting applyDevModeButton =
		new ButtonSetting("Apply DevMode", this::applyDevMode);
	private final SliderSetting espTimeoutMinutesSetting = new SliderSetting(
		"ESP timeout minutes", 10, 1, 180, 1, ValueDisplay.INTEGER);
	private final ButtonSetting applyEspTimeoutMinutesButton =
		new ButtonSetting("Apply EspTimeoutMinutes",
			this::applyEspTimeoutMinutes);
	private final List<EspProfile> espProfiles = new ArrayList<>();
	private boolean connectionPreviouslyNull = true;
	
	private final EnumSetting<HighlightMode> highlightModeSetting =
		new EnumSetting<>("Highlight type", HighlightMode.values(),
			HighlightMode.BLOCK);
	private final StringDropdownSetting highlightBlockSetting =
		new StringDropdownSetting("Highlight block",
			WText.literal("Used by /sm:highlight block."));
	private final SliderSetting highlightChunksSetting = new SliderSetting(
		"Highlight chunk range", 4, 0, 20, 1, ValueDisplay.INTEGER);
	private final ButtonSetting runHighlightButton =
		new ButtonSetting("Run highlight", this::runConfiguredHighlight);
	private final ButtonSetting clearHighlightButton =
		new ButtonSetting("Clear highlight", this::clearHighlight);
	
	private final StringDropdownSetting locateBiomeSetting =
		new StringDropdownSetting("Biome target",
			WText.literal("Biome keys for /sm:locate biome."));
	private final ButtonSetting runLocateBiomeButton =
		new ButtonSetting("Locate biome", this::runLocateBiome);
	
	private final StringDropdownSetting locateStructureSetting =
		new StringDropdownSetting("Structure target",
			WText.literal("Structure key for /sm:locate feature."));
	private final TextFieldSetting structurePieceFiltersField =
		new TextFieldSetting("Piece filters", "");
	private final TextFieldSetting structureVariantFiltersField =
		new TextFieldSetting("Variant filters", "");
	private final StringDropdownSetting structurePiecePicker =
		new StringDropdownSetting("Piece presets",
			WText.literal("Pieces applicable to fortress & end_city."));
	private final StringDropdownSetting structureVariantPicker =
		new StringDropdownSetting("Variant presets", WText
			.literal("Variants for structures plus biome/rotation/mirrored."));
	private final EnumSetting<VariantDataMode> variantDataSetting =
		new EnumSetting<>("Variant data", VariantDataMode.values(),
			VariantDataMode.AUTO);
	private final ButtonSetting addPieceFilterButton =
		new ButtonSetting("Add piece filter", this::addStructurePiece);
	private final ButtonSetting clearPieceFiltersButton = new ButtonSetting(
		"Clear piece filters", () -> structurePieceFiltersField.setValue(""));
	private final ButtonSetting addVariantFilterButton =
		new ButtonSetting("Add variant filter", this::addStructureVariant);
	private final ButtonSetting clearVariantFiltersButton =
		new ButtonSetting("Clear variant filters",
			() -> structureVariantFiltersField.setValue(""));
	private final ButtonSetting runLocateStructureButton =
		new ButtonSetting("Locate structure", this::runLocateStructure);
	
	private final TextFieldSetting locateLootAmountSetting =
		new TextFieldSetting("Loot amount", "1", AMOUNT_VALIDATOR);
	private final StringDropdownSetting locateLootItemSetting =
		new StringDropdownSetting("Loot item predicate",
			WText.literal("Keys from ItemAndEnchantmentsPredicateArgument."));
	private final EnumSetting<LootClauseType> lootClauseTypeSetting =
		new EnumSetting<>("Enchant clause", LootClauseType.values(),
			LootClauseType.NONE);
	private final StringDropdownSetting lootEnchantPicker =
		new StringDropdownSetting("Enchant key",
			WText.literal("Keys from ENCHANTMENTS."));
	private final TextFieldSetting lootEnchantLevelSetting =
		new TextFieldSetting("Enchant level", "*", ENCHANT_LEVEL_VALIDATOR);
	private final ButtonSetting runLocateLootButton =
		new ButtonSetting("Locate loot", this::runLocateLoot);
	
	private final StringDropdownSetting locateOreVeinSetting =
		new StringDropdownSetting("Ore vein target",
			WText.literal("Choices for /sm:locate orevein."));
	private final ButtonSetting runLocateOreVeinButton =
		new ButtonSetting("Locate ore vein", this::runLocateOreVein);
	private final ButtonSetting runLocateSlimeChunkButton = new ButtonSetting(
		"Locate slime chunk",
		() -> runLocateSimple("sm:locate slimechunk", "locate a slime chunk"));
	private final ButtonSetting runLocateSpawnButton =
		new ButtonSetting("Locate spawn",
			() -> runLocateSimple("sm:locate spawn", "locate spawn chunks"));
	private final ButtonSetting runLocateCanyonButton =
		new ButtonSetting("Locate canyon",
			() -> runLocateSimple("sm:locate canyon", "locate canyon carvers"));
	
	private final TextFieldSetting sourceSeedSetting =
		new TextFieldSetting("Source seed override", "", SEED_VALIDATOR);
	private final StringDropdownSetting sourceDimensionSetting =
		new StringDropdownSetting("Source dimension",
			WText.literal("Optional /sm:source in <dimension>."));
	private final StringDropdownSetting sourceVersionPresetSetting =
		new StringDropdownSetting("Source version presets",
			WText.literal("Available /sm:source versioned keys."));
	private final TextFieldSetting sourceVersionSetting =
		new TextFieldSetting("Source version override", "");
	private final TextFieldSetting sourceSelectorSetting =
		new TextFieldSetting("Source selector (as ...)", "");
	private final TextFieldSetting sourcePosXSetting =
		new TextFieldSetting("Source pos X", "", COORDINATE_VALIDATOR);
	private final TextFieldSetting sourcePosYSetting =
		new TextFieldSetting("Source pos Y", "", COORDINATE_VALIDATOR);
	private final TextFieldSetting sourcePosZSetting =
		new TextFieldSetting("Source pos Z", "", COORDINATE_VALIDATOR);
	private final TextFieldSetting sourceYawSetting =
		new TextFieldSetting("Rotate yaw", "", ROTATION_VALIDATOR);
	private final TextFieldSetting sourcePitchSetting =
		new TextFieldSetting("Rotate pitch", "", ROTATION_VALIDATOR);
	private final TextFieldSetting sourceCommandSetting =
		new TextFieldSetting("Command for sm:source run", "");
	private final ButtonSetting applyVersionPresetButton = new ButtonSetting(
		"Use selected version preset", this::applyVersionPreset);
	private final ButtonSetting runSourceButton =
		new ButtonSetting("Run source", this::runSourceCommand);
	private final ButtonSetting resetSourceFormButton =
		new ButtonSetting("Reset source builder", this::resetSourceForm);
	
	public SeedMapperHelperHack()
	{
		super("SeedMapperHelper");
		setCategory(Category.OTHER);
		
		addPossibleKeybind(".say /sm:clear", "SeedMapper: Clear overlays");
		addPossibleKeybind(".say /sm:stoptask",
			"SeedMapper: Stop locator tasks");
		addPossibleKeybind(".say /sm:seedmap", "SeedMapper: Open Seed Map UI");
		addPossibleKeybind(".say /sm:minimap on",
			"SeedMapper: Show minimap overlay");
		addPossibleKeybind(".say /sm:minimap off",
			"SeedMapper: Hide minimap overlay");
		addPossibleKeybind(".say /sm:seedcheck",
			"SeedMapper: Check current seed");
		addPossibleKeybind(".seedmapperhelper map",
			"SeedMapper: Open Seed Map UI");
		addPossibleKeybind(".seedmapperhelper highlight",
			"SeedMapper: Run highlight");
		addPossibleKeybind(".seedmapperhelper clearhighlight",
			"SeedMapper: Clear highlight");
		
		highlightBlockSetting.setOptions(data.getHighlightBlocks());
		autoSelectFirst(highlightBlockSetting, data.getHighlightBlocks());
		locateBiomeSetting.setOptions(data.getBiomeKeys());
		autoSelectFirst(locateBiomeSetting, data.getBiomeKeys());
		
		List<String> structures = data.getStructureKeys();
		locateStructureSetting.setOptions(structures);
		autoSelectFirst(locateStructureSetting, structures);
		
		structurePiecePicker.setOptions(flattenOptionUnion(data, true));
		structureVariantPicker.setOptions(flattenOptionUnion(data, false));
		autoSelectFirst(structureVariantPicker, data.getVariantKeyUnion());
		
		locateLootItemSetting.setOptions(data.getLootItems());
		autoSelectFirst(locateLootItemSetting, data.getLootItems());
		lootEnchantPicker.setOptions(data.getLootEnchantments());
		autoSelectFirst(lootEnchantPicker, data.getLootEnchantments());
		
		locateOreVeinSetting.setOptions(data.getOreVeinTargets());
		autoSelectFirst(locateOreVeinSetting, data.getOreVeinTargets());
		
		sourceDimensionSetting.setOptions(data.getDimensionShortcuts());
		sourceVersionPresetSetting.setOptions(data.getVersionShortcuts());
		
		addSetting(statusButton);
		addSetting(seedMapButton);
		addSetting(minimapButton);
		addSetting(enableMinimapButton);
		addSetting(disableMinimapButton);
		addSetting(clearOverlaysButton);
		addSetting(checkSeedButton);
		addSetting(seedCheckInputSetting);
		addSetting(applySeedInputButton);
		addSetting(stopTaskButton);
		addSetting(showCommandFeedbackSetting);
		addSetting(savedSeedValueSetting);
		addSetting(addSavedSeedButton);
		addSetting(seedResolutionOrderSetting);
		addSetting(applySeedResolutionOrderButton);
		addSetting(oreAirCheckSetting);
		addSetting(applyOreAirCheckButton);
		addSetting(clearSeedMapCachesSetting);
		addSetting(applyClearSeedMapCachesButton);
		addSetting(seedMapThreadsSetting);
		addSetting(applySeedMapThreadsButton);
		addSetting(minimapOffsetXSetting);
		addSetting(applyMinimapOffsetXButton);
		addSetting(minimapOffsetYSetting);
		addSetting(applyMinimapOffsetYButton);
		addSetting(minimapWidthSetting);
		addSetting(applyMinimapWidthButton);
		addSetting(minimapHeightSetting);
		addSetting(applyMinimapHeightButton);
		addSetting(minimapRotateSetting);
		addSetting(applyMinimapRotateButton);
		addSetting(minimapPixelsPerBiomeSetting);
		addSetting(applyMinimapPixelsPerBiomeButton);
		addSetting(minimapIconScaleSetting);
		addSetting(applyMinimapIconScaleButton);
		addSetting(minimapOpacitySetting);
		addSetting(applyMinimapOpacityButton);
		addSetting(pixelsPerBiomeSetting);
		addSetting(applyPixelsPerBiomeButton);
		addSetting(toggledFeaturesSetting);
		addSetting(applyToggledFeaturesButton);
		addSetting(devModeSetting);
		addSetting(applyDevModeButton);
		addSetting(espTimeoutMinutesSetting);
		addSetting(applyEspTimeoutMinutesButton);
		addSection("SeedMapper commands", "General-purpose SeedMapper actions.",
			statusButton, seedMapButton, minimapButton, enableMinimapButton,
			disableMinimapButton, clearOverlaysButton, checkSeedButton,
			seedCheckInputSetting, applySeedInputButton, stopTaskButton,
			showCommandFeedbackSetting);
		addSection("SeedMapper config", "Convenience controls for /sm:config.",
			savedSeedValueSetting, addSavedSeedButton,
			seedResolutionOrderSetting, applySeedResolutionOrderButton,
			oreAirCheckSetting, applyOreAirCheckButton,
			clearSeedMapCachesSetting, applyClearSeedMapCachesButton,
			seedMapThreadsSetting, applySeedMapThreadsButton,
			pixelsPerBiomeSetting, applyPixelsPerBiomeButton,
			toggledFeaturesSetting, applyToggledFeaturesButton, devModeSetting,
			applyDevModeButton, espTimeoutMinutesSetting,
			applyEspTimeoutMinutesButton);
		addSection("SeedMap minimap", "Configure the in-game minimap overlay.",
			minimapOffsetXSetting, applyMinimapOffsetXButton,
			minimapOffsetYSetting, applyMinimapOffsetYButton,
			minimapWidthSetting, applyMinimapWidthButton, minimapHeightSetting,
			applyMinimapHeightButton, minimapRotateSetting,
			applyMinimapRotateButton, minimapPixelsPerBiomeSetting,
			applyMinimapPixelsPerBiomeButton, minimapIconScaleSetting,
			applyMinimapIconScaleButton, minimapOpacitySetting,
			applyMinimapOpacityButton);
		
		addSetting(highlightModeSetting);
		addSetting(highlightBlockSetting);
		addSetting(highlightChunksSetting);
		addSetting(runHighlightButton);
		addSetting(clearHighlightButton);
		addSection("Highlighting", "Configure /sm:highlight.",
			highlightModeSetting, highlightBlockSetting, highlightChunksSetting,
			runHighlightButton, clearHighlightButton);
		
		addSetting(locateBiomeSetting);
		addSetting(runLocateBiomeButton);
		addSection("Biome locator", "Locate specific biome keys.",
			locateBiomeSetting, runLocateBiomeButton);
		
		addSetting(locateStructureSetting);
		addSetting(structurePieceFiltersField);
		addSetting(structurePiecePicker);
		addSetting(addPieceFilterButton);
		addSetting(clearPieceFiltersButton);
		addSetting(structureVariantFiltersField);
		addSetting(structureVariantPicker);
		addSetting(addVariantFilterButton);
		addSetting(clearVariantFiltersButton);
		addSetting(variantDataSetting);
		addSetting(runLocateStructureButton);
		addSection("Structure locator", "Compose /sm:locate feature commands.",
			locateStructureSetting, structurePieceFiltersField,
			structurePiecePicker, addPieceFilterButton, clearPieceFiltersButton,
			structureVariantFiltersField, structureVariantPicker,
			addVariantFilterButton, clearVariantFiltersButton,
			variantDataSetting, runLocateStructureButton);
		
		addSetting(locateLootAmountSetting);
		addSetting(locateLootItemSetting);
		addSetting(lootClauseTypeSetting);
		addSetting(lootEnchantPicker);
		addSetting(lootEnchantLevelSetting);
		addSetting(runLocateLootButton);
		addSection("Loot locator", "Search loot tables and enchantments.",
			locateLootAmountSetting, locateLootItemSetting,
			lootClauseTypeSetting, lootEnchantPicker, lootEnchantLevelSetting,
			runLocateLootButton);
		
		addSetting(locateOreVeinSetting);
		addSetting(runLocateOreVeinButton);
		addSetting(runLocateSlimeChunkButton);
		addSetting(runLocateSpawnButton);
		addSetting(runLocateCanyonButton);
		addSection("Other locators", "Extra /sm:locate helpers.",
			locateOreVeinSetting, runLocateOreVeinButton,
			runLocateSlimeChunkButton, runLocateSpawnButton,
			runLocateCanyonButton);
		
		addSetting(sourceSeedSetting);
		addSetting(sourceDimensionSetting);
		addSetting(sourceVersionPresetSetting);
		addSetting(sourceVersionSetting);
		addSetting(sourceSelectorSetting);
		addSetting(sourcePosXSetting);
		addSetting(sourcePosYSetting);
		addSetting(sourcePosZSetting);
		addSetting(sourceYawSetting);
		addSetting(sourcePitchSetting);
		addSetting(sourceCommandSetting);
		addSetting(applyVersionPresetButton);
		addSetting(runSourceButton);
		addSetting(resetSourceFormButton);
		addSection("Source builder", "Build /sm:source commands.",
			sourceSeedSetting, sourceDimensionSetting,
			sourceVersionPresetSetting, sourceVersionSetting,
			sourceSelectorSetting, sourcePosXSetting, sourcePosYSetting,
			sourcePosZSetting, sourceYawSetting, sourcePitchSetting,
			sourceCommandSetting, applyVersionPresetButton, runSourceButton,
			resetSourceFormButton);
		
		initEspProfiles();
		
		EVENTS.add(ChatInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onEnable()
	{
		reportStatus();
		setEnabled(false);
	}
	
	private void reportStatus()
	{
		boolean available = VendorSeedMapperLoader.isSeedMapperPresent();
		Optional<String> version = VendorSeedMapperLoader.getDetectedVersion();
		if(available)
		{
			ChatUtils.message("SeedMapper detected (version "
				+ version.orElse("unknown") + ").");
		}else
			ChatUtils.warning(
				"SeedMapper isn't installed or failed to load. Commands will be blocked.");
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		if(event.getComponent() == null)
			return;
		
		String message = event.getComponent().getString();
		if(message == null || message.isEmpty())
			return;
		
		String trimmed = message.trim();
		if(trimmed.isEmpty())
			return;
		
		handleSeedCheckFeedback(trimmed);
		
		for(EspProfile profile : espProfiles)
			if(profile.handleChatFeedback(trimmed))
			{
				event.cancel();
				break;
			}
	}
	
	@Override
	public void onUpdate()
	{
		boolean connected = MC.getConnection() != null;
		if(connected && connectionPreviouslyNull)
		{
			connectionPreviouslyNull = false;
			if(VendorSeedMapperLoader.isSeedMapperPresent())
				requestAllEspStates(true);
			
		}else if(!connected)
			connectionPreviouslyNull = true;
	}
	
	public void openSeedMapUi()
	{
		runSimpleCommand("sm:seedmap", "open the Seed Mapper UI");
	}
	
	public void openSeedMapMinimap()
	{
		runSimpleCommand("sm:minimap", "open the SeedMap minimap");
	}
	
	private void setMinimapEnabled(boolean enabled)
	{
		runSimpleCommand("sm:minimap " + (enabled ? "on" : "off"),
			(enabled ? "enable" : "disable") + " the SeedMap minimap");
	}
	
	public void clearOverlays()
	{
		runSimpleCommand("sm:clear", "clear SeedMapper overlays");
	}
	
	public void runConfiguredHighlight()
	{
		runHighlightCommand();
	}
	
	public void clearHighlight()
	{
		sendSeedMapperCommand("sm:highlight clear", "clear highlights", false,
			showCommandFeedbackSetting.isChecked());
	}
	
	private void runHighlightCommand()
	{
		HighlightMode mode = highlightModeSetting.getSelected();
		int chunks =
			Math.min(mode.maxChunks, highlightChunksSetting.getValueI());
		
		String command;
		switch(mode)
		{
			case BLOCK ->
			{
				String block = highlightBlockSetting.getSelected();
				if(block.isEmpty())
				{
					ChatUtils.error("Select a block to highlight.");
					return;
				}
				command = "sm:highlight block " + block + " " + chunks;
			}
			
			case ORE_VEIN ->
			{
				command = "sm:highlight orevein " + chunks;
			}
			
			default ->
			{
				return;
			}
		}
		
		runSimpleCommand(command, "run sm:highlight");
	}
	
	private void runLocateBiome()
	{
		String biome = locateBiomeSetting.getSelected();
		if(biome.isEmpty())
		{
			ChatUtils.error("Select a biome to locate.");
			return;
		}
		
		runSimpleCommand("sm:locate biome " + biome, "locate biome " + biome);
	}
	
	private void addStructurePiece()
	{
		String structure = locateStructureSetting.getSelected();
		if(structure.isEmpty())
		{
			ChatUtils.error("Select a structure before adding pieces.");
			return;
		}
		
		if(!data.supportsPieceFilters(structure))
		{
			ChatUtils.warning(
				"The selected structure does not support piece filters.");
			return;
		}
		
		String piece = structurePiecePicker.getSelected();
		if(piece.isEmpty())
		{
			ChatUtils.error("Pick a piece to add.");
			return;
		}
		
		List<String> validPieces = data.getStructurePieces(structure);
		if(!validPieces.contains(piece))
		{
			ChatUtils
				.error("\"" + piece + "\" isn't valid for " + structure + ".");
			return;
		}
		
		LinkedHashSet<String> values =
			parseCsv(structurePieceFiltersField.getValue());
		if(values.add(piece))
			structurePieceFiltersField.setValue(joinCsv(values));
	}
	
	private void addStructureVariant()
	{
		String structure = locateStructureSetting.getSelected();
		if(structure.isEmpty())
		{
			ChatUtils.error("Select a structure before adding variants.");
			return;
		}
		
		String variant = structureVariantPicker.getSelected();
		if(variant.isEmpty())
		{
			ChatUtils.error("Pick a variant to add.");
			return;
		}
		
		boolean isGeneric = data.getGenericVariantKeys().contains(variant);
		boolean supported = data.supportsVariantFilters(structure) || isGeneric;
		if(!supported)
		{
			ChatUtils
				.warning("Variants are not supported for " + structure + ".");
			return;
		}
		
		if(!isGeneric
			&& !data.getStructureVariants(structure).contains(variant))
		{
			ChatUtils.error(
				"\"" + variant + "\" isn't valid for " + structure + ".");
			return;
		}
		
		LinkedHashSet<String> values =
			parseCsv(structureVariantFiltersField.getValue());
		if(values.add(variant))
			structureVariantFiltersField.setValue(joinCsv(values));
	}
	
	private void runLocateStructure()
	{
		String structure = locateStructureSetting.getSelected();
		if(structure.isEmpty())
		{
			ChatUtils.error("Select a structure to locate.");
			return;
		}
		
		StringBuilder builder =
			new StringBuilder("sm:locate feature ").append(structure);
		
		String filter = buildBracketFilters(structurePieceFiltersField,
			structureVariantFiltersField);
		if(!filter.isEmpty())
			builder.append(filter);
		
		switch(variantDataSetting.getSelected())
		{
			case FORCE_TRUE -> builder.append(" variantdata true");
			case FORCE_FALSE -> builder.append(" variantdata false");
			default ->
				{
				}
		}
		
		runSimpleCommand(builder.toString(), "locate feature " + structure);
	}
	
	private void runLocateLoot()
	{
		String item = locateLootItemSetting.getSelected();
		if(item.isEmpty())
		{
			ChatUtils.error("Select an item predicate.");
			return;
		}
		
		int amount = parsePositiveInt(locateLootAmountSetting.getValue(), 1);
		if(amount <= 0)
		{
			ChatUtils.error("Loot amount must be greater than zero.");
			return;
		}
		
		StringBuilder predicate = new StringBuilder(item);
		LootClauseType clause = lootClauseTypeSetting.getSelected();
		if(clause != LootClauseType.NONE)
		{
			String enchant = lootEnchantPicker.getSelected();
			if(enchant.isEmpty())
			{
				ChatUtils.error("Select an enchantment to use.");
				return;
			}
			String level = lootEnchantLevelSetting.getValue().trim();
			if(level.isEmpty())
				level = "*";
			if(!ENCHANT_LEVEL_VALIDATOR.test(level))
			{
				ChatUtils.error("Invalid enchantment level. Use digits or *.");
				return;
			}
			predicate.append(" ").append(clause.keyword).append(" ")
				.append(enchant).append(" ").append(level);
		}
		
		String command = "sm:locate loot " + amount + " " + predicate;
		runSimpleCommand(command, "locate loot " + item);
	}
	
	private void runLocateOreVein()
	{
		String target = locateOreVeinSetting.getSelected();
		if(target.isEmpty())
		{
			ChatUtils.error("Select an ore vein type.");
			return;
		}
		
		runSimpleCommand("sm:locate orevein " + target,
			"locate " + target + " vein");
	}
	
	private void runLocateSimple(String command, String label)
	{
		runSimpleCommand(command, label);
	}
	
	private void applySeedInput()
	{
		String seed = seedCheckInputSetting.getValue().trim();
		if(seed.isEmpty())
		{
			ChatUtils.error("Enter a seed to set.");
			return;
		}
		if(!SEED_VALIDATOR.test(seed))
		{
			ChatUtils.error("Invalid seed value.");
			return;
		}
		
		runSimpleCommand("sm:config Seed set " + seed, "set seed to " + seed);
	}
	
	private void addSavedSeed()
	{
		String seed = savedSeedValueSetting.getValue().trim();
		if(seed.isEmpty())
		{
			ChatUtils.error("Enter a seed to save.");
			return;
		}
		if(!SEED_VALIDATOR.test(seed))
		{
			ChatUtils.error("Invalid seed value.");
			return;
		}
		
		runSimpleCommand("sm:config SavedSeeds add " + seed,
			"add saved seed " + seed);
	}
	
	private void applySeedResolutionOrder()
	{
		String order = seedResolutionOrderSetting.getValue().trim();
		if(order.isEmpty())
		{
			ChatUtils.error("Enter a comma-separated resolution order.");
			return;
		}
		
		String normalized = order.replace(" ", "");
		runSimpleCommand("sm:config SeedResolutionOrder set " + normalized,
			"set SeedResolutionOrder");
	}
	
	private void applyOreAirCheck()
	{
		runSimpleCommand(
			"sm:config OreAirCheck set " + oreAirCheckSetting.isChecked(),
			"set OreAirCheck");
	}
	
	private void applyClearSeedMapCaches()
	{
		runSimpleCommand(
			"sm:config ClearSeedMapCachesOnClose set "
				+ clearSeedMapCachesSetting.isChecked(),
			"set ClearSeedMapCachesOnClose");
	}
	
	private void applySeedMapThreads()
	{
		int threads = Math.max(1, seedMapThreadsSetting.getValueI());
		runSimpleCommand("sm:config SeedMapThreads set " + threads,
			"set SeedMapThreads");
	}
	
	private void applyMinimapOffsetX()
	{
		int offset = minimapOffsetXSetting.getValueI();
		runSimpleCommand("sm:config SeedMapMinimapOffsetX set " + offset,
			"set SeedMapMinimapOffsetX");
	}
	
	private void applyMinimapOffsetY()
	{
		int offset = minimapOffsetYSetting.getValueI();
		runSimpleCommand("sm:config SeedMapMinimapOffsetY set " + offset,
			"set SeedMapMinimapOffsetY");
	}
	
	private void applyMinimapWidth()
	{
		int width = minimapWidthSetting.getValueI();
		runSimpleCommand("sm:config SeedMapMinimapWidth set " + width,
			"set SeedMapMinimapWidth");
	}
	
	private void applyMinimapHeight()
	{
		int height = minimapHeightSetting.getValueI();
		runSimpleCommand("sm:config SeedMapMinimapHeight set " + height,
			"set SeedMapMinimapHeight");
	}
	
	private void applyMinimapRotateWithPlayer()
	{
		runSimpleCommand(
			"sm:config SeedMapMinimapRotateWithPlayer set "
				+ minimapRotateSetting.isChecked(),
			"set SeedMapMinimapRotateWithPlayer");
	}
	
	private void applyMinimapPixelsPerBiome()
	{
		double pixels = minimapPixelsPerBiomeSetting.getValue();
		runSimpleCommand("sm:config SeedMapMinimapPixelsPerBiome set "
			+ formatDouble(pixels), "set SeedMapMinimapPixelsPerBiome");
	}
	
	private void applyMinimapIconScale()
	{
		double scale = minimapIconScaleSetting.getValue();
		runSimpleCommand(
			"sm:config SeedMapMinimapIconScale set " + formatDouble(scale),
			"set SeedMapMinimapIconScale");
	}
	
	private void applyMinimapOpacity()
	{
		double opacity = minimapOpacitySetting.getValue();
		runSimpleCommand(
			"sm:config SeedMapMinimapOpacity set " + formatDouble(opacity),
			"set SeedMapMinimapOpacity");
	}
	
	private void applyPixelsPerBiome()
	{
		double pixels = pixelsPerBiomeSetting.getValue();
		runSimpleCommand("sm:config PixelsPerBiome set " + formatDouble(pixels),
			"set PixelsPerBiome");
	}
	
	private void applyToggledFeatures()
	{
		String features = toggledFeaturesSetting.getValue().trim();
		if(features.isEmpty())
		{
			ChatUtils.error("Enter at least one feature name.");
			return;
		}
		String normalized = features.replace(" ", "");
		runSimpleCommand("sm:config ToggledFeatures set " + normalized,
			"set ToggledFeatures");
	}
	
	private void applyDevMode()
	{
		runSimpleCommand("sm:config DevMode set " + devModeSetting.isChecked(),
			"set DevMode");
	}
	
	private void applyEspTimeoutMinutes()
	{
		int minutes = Math.max(1, espTimeoutMinutesSetting.getValueI());
		runSimpleCommand("sm:config EspTimeoutMinutes set " + minutes,
			"set EspTimeoutMinutes");
	}
	
	private void handleSeedCheckFeedback(String message)
	{
		if(message == null || message.isEmpty())
			return;
		
		String lower = message.toLowerCase(Locale.ROOT);
		if(!lower.contains("seed"))
			return;
		boolean related = lower.contains("seedmapper")
			|| lower.contains("sm:seedcheck") || lower.startsWith("seed ")
			|| lower.startsWith("seed:") || lower.startsWith("current seed");
		if(!related)
			return;
		
		Matcher matcher = SEED_RESPONSE_PATTERN.matcher(message);
		if(matcher.find())
		{
			seedCheckInputSetting.setValue(matcher.group(1));
			return;
		}
		
		if(lower.contains("no seed") || lower.contains("not set"))
			seedCheckInputSetting.setValue("");
	}
	
	private void applyVersionPreset()
	{
		String preset = sourceVersionPresetSetting.getSelected();
		if(preset.isEmpty())
		{
			ChatUtils.error("Pick a version preset first.");
			return;
		}
		sourceVersionSetting.setValue(preset);
	}
	
	private void resetSourceForm()
	{
		sourceSeedSetting.setValue("");
		sourceDimensionSetting.setSelected("");
		sourceVersionPresetSetting.setSelected("");
		sourceVersionSetting.setValue("");
		sourceSelectorSetting.setValue("");
		sourcePosXSetting.setValue("");
		sourcePosYSetting.setValue("");
		sourcePosZSetting.setValue("");
		sourceYawSetting.setValue("");
		sourcePitchSetting.setValue("");
		sourceCommandSetting.setValue("");
	}
	
	private void runSourceCommand()
	{
		StringBuilder builder = new StringBuilder("sm:source");
		
		String seed = sourceSeedSetting.getValue().trim();
		if(!seed.isEmpty())
		{
			if(!SEED_VALIDATOR.test(seed))
			{
				ChatUtils.error("Invalid seed value.");
				return;
			}
			builder.append(" seeded ").append(seed);
		}
		
		String version = sourceVersionSetting.getValue().trim();
		if(version.isEmpty())
			version = sourceVersionPresetSetting.getSelected();
		if(version != null && !version.isEmpty())
			builder.append(" versioned ").append(version);
		
		String dimension = sourceDimensionSetting.getSelected();
		if(!dimension.isEmpty())
			builder.append(" in ").append(dimension);
		
		String selector = sourceSelectorSetting.getValue().trim();
		if(!selector.isEmpty())
			builder.append(" as ").append(selector);
		
		String positioned = buildCoordinateSegment(sourcePosXSetting,
			sourcePosYSetting, sourcePosZSetting);
		if(positioned == null)
			return;
		if(!positioned.isEmpty())
			builder.append(" positioned ").append(positioned);
		
		String rotated =
			buildRotationSegment(sourceYawSetting, sourcePitchSetting);
		if(rotated == null)
			return;
		if(!rotated.isEmpty())
			builder.append(" rotated ").append(rotated);
		
		String run = sourceCommandSetting.getValue().trim();
		if(!run.isEmpty())
			builder.append(" run ").append(run);
		
		runSimpleCommand(builder.toString(), "run sm:source");
	}
	
	private String buildCoordinateSegment(TextFieldSetting x,
		TextFieldSetting y, TextFieldSetting z)
	{
		String sx = x.getValue().trim();
		String sy = y.getValue().trim();
		String sz = z.getValue().trim();
		
		boolean empty = sx.isEmpty() && sy.isEmpty() && sz.isEmpty();
		if(empty)
			return "";
		
		if(sx.isEmpty() || sy.isEmpty() || sz.isEmpty())
		{
			ChatUtils
				.error("Fill out all coordinate fields or leave them blank.");
			return null;
		}
		if(!(COORDINATE_VALIDATOR.test(sx) && COORDINATE_VALIDATOR.test(sy)
			&& COORDINATE_VALIDATOR.test(sz)))
		{
			ChatUtils.error(
				"Coordinates can contain numbers with optional '~' or '^'.");
			return null;
		}
		return sx + " " + sy + " " + sz;
	}
	
	private String buildRotationSegment(TextFieldSetting yawSetting,
		TextFieldSetting pitchSetting)
	{
		String yaw = yawSetting.getValue().trim();
		String pitch = pitchSetting.getValue().trim();
		boolean empty = yaw.isEmpty() && pitch.isEmpty();
		if(empty)
			return "";
		if(yaw.isEmpty() || pitch.isEmpty())
		{
			ChatUtils.error(
				"Provide both yaw and pitch for rotation or leave them blank.");
			return null;
		}
		if(!(ROTATION_VALIDATOR.test(yaw) && ROTATION_VALIDATOR.test(pitch)))
		{
			ChatUtils.error("Yaw and pitch must be numeric.");
			return null;
		}
		return yaw + " " + pitch;
	}
	
	private void runSimpleCommand(String command, String label)
	{
		sendSeedMapperCommand(command, label, false,
			showCommandFeedbackSetting.isChecked());
	}
	
	private boolean sendSeedMapperCommand(String command, String label,
		boolean silentErrors, boolean showFeedback)
	{
		if(!VendorSeedMapperLoader.isSeedMapperPresent())
		{
			if(!silentErrors)
			{
				if(label == null || label.isEmpty())
					ChatUtils.error(
						"SeedMapper mod not detected. Cannot perform this action.");
				else
					ChatUtils.error(
						"SeedMapper mod not detected. Cannot " + label + ".");
			}
			return false;
		}
		if(MC.getConnection() == null)
		{
			if(!silentErrors)
				ChatUtils
					.error("Join a world before sending SeedMapper commands.");
			return false;
		}
		
		MC.getConnection().sendCommand(command);
		if(showFeedback)
			ChatUtils.message("SeedMapperHelper sent: /" + command);
		return true;
	}
	
	private int parsePositiveInt(String value, int fallback)
	{
		if(value == null || value.isEmpty())
			return fallback;
		try
		{
			return Integer.parseInt(value);
		}catch(NumberFormatException e)
		{
			return fallback;
		}
	}
	
	private String buildBracketFilters(TextFieldSetting piecesField,
		TextFieldSetting variantsField)
	{
		LinkedHashSet<String> filters = new LinkedHashSet<>();
		filters.addAll(parseCsv(piecesField.getValue()));
		filters.addAll(parseCsv(variantsField.getValue()));
		if(filters.isEmpty())
			return "";
		return "[" + String.join(",", filters) + "]";
	}
	
	private LinkedHashSet<String> parseCsv(String text)
	{
		LinkedHashSet<String> values = new LinkedHashSet<>();
		if(text == null || text.isBlank())
			return values;
		for(String token : CSV_SPLIT.split(text))
		{
			String trimmed = token.trim();
			if(!trimmed.isEmpty())
				values.add(trimmed);
		}
		return values;
	}
	
	private String joinCsv(Set<String> values)
	{
		return values.stream().collect(Collectors.joining(","));
	}
	
	private List<String> flattenOptionUnion(SeedMapperData seedData,
		boolean pieces)
	{
		List<String> options = new ArrayList<>();
		if(pieces)
			for(String structure : seedData.getStructureKeys())
				options.addAll(seedData.getStructurePieces(structure));
		else
			options.addAll(seedData.getVariantKeyUnion());
		
		return options.stream().distinct().sorted().toList();
	}
	
	private void autoSelectFirst(StringDropdownSetting setting,
		List<String> options)
	{
		if(options == null || options.isEmpty())
			return;
		setting.setSelected(options.get(0));
	}
	
	private void initEspProfiles()
	{
		for(EspBucket bucket : EspBucket.values())
		{
			EspProfile profile = new EspProfile(bucket);
			espProfiles.add(profile);
			profile.registerSettings();
		}
	}
	
	private void requestAllEspStates(boolean silent)
	{
		for(EspProfile profile : espProfiles)
			profile.requestRefresh(silent);
	}
	
	private void addSection(String title, String description,
		Setting... settings)
	{
		if(settings == null || settings.length == 0)
			return;
		
		WText desc = description == null || description.isEmpty()
			? WText.empty() : WText.literal(description);
		SettingGroup group = new SettingGroup(title, desc, false, true);
		group.addChildren(settings);
		addSetting(group);
		addSpacer();
	}
	
	private void addSpacer()
	{
		addSetting(new SpacerSetting());
	}
	
	private final class EspProfile
	{
		private final EspBucket bucket;
		private final ColorSetting outlineColorSetting;
		private final SliderSetting outlineAlphaSetting;
		private final CheckboxSetting useCommandColorSetting;
		private final CheckboxSetting fillEnabledSetting;
		private final ColorSetting fillColorSetting;
		private final SliderSetting fillAlphaSetting;
		private final CheckboxSetting rainbowSetting;
		private final SliderSetting rainbowSpeedSetting;
		private final ButtonSetting applyButton;
		private final ButtonSetting refreshButton;
		private final ButtonSetting resetButton;
		private boolean suppressNextResponse;
		private int suppressedLinesRemaining;
		private static final int SILENT_REFRESH_LINES = 12;
		
		private EspProfile(EspBucket bucket)
		{
			this.bucket = bucket;
			String prefix = bucket.label + " ";
			outlineColorSetting = new ColorSetting(prefix + "outline color",
				WText.literal("Outline color used by " + bucket.label + "."),
				Color.WHITE);
			outlineAlphaSetting = new SliderSetting(prefix + "outline alpha",
				0.8, 0.0, 1.0, 0.01, ValueDisplay.DECIMAL);
			useCommandColorSetting =
				new CheckboxSetting(prefix + "use command color", false);
			fillEnabledSetting =
				new CheckboxSetting(prefix + "fill enabled", true);
			fillColorSetting = new ColorSetting(prefix + "fill color",
				WText.literal("Fill color used by " + bucket.label + "."),
				new Color(0x55FFD4));
			fillAlphaSetting = new SliderSetting(prefix + "fill alpha", 0.3,
				0.0, 1.0, 0.01, ValueDisplay.DECIMAL);
			rainbowSetting =
				new CheckboxSetting(prefix + "rainbow mode", false);
			rainbowSpeedSetting = new SliderSetting(prefix + "rainbow speed",
				0.5, 0.05, 5.0, 0.05, ValueDisplay.DECIMAL);
			applyButton =
				new ButtonSetting(prefix + "apply", this::applyToServer);
			refreshButton = new ButtonSetting(prefix + "refresh",
				() -> requestRefresh(false));
			resetButton =
				new ButtonSetting(prefix + "reset", this::resetOnServer);
		}
		
		private void registerSettings()
		{
			addSetting(outlineColorSetting);
			addSetting(outlineAlphaSetting);
			addSetting(useCommandColorSetting);
			addSetting(fillEnabledSetting);
			addSetting(fillColorSetting);
			addSetting(fillAlphaSetting);
			addSetting(rainbowSetting);
			addSetting(rainbowSpeedSetting);
			addSetting(applyButton);
			addSetting(refreshButton);
			addSetting(resetButton);
			
			addSection(bucket.label, "Configure " + bucket.label + ".",
				outlineColorSetting, outlineAlphaSetting,
				useCommandColorSetting, fillEnabledSetting, fillColorSetting,
				fillAlphaSetting, rainbowSetting, rainbowSpeedSetting,
				applyButton, refreshButton, resetButton);
		}
		
		private void applyToServer()
		{
			String command = buildSetCommand();
			sendSeedMapperCommand(command, "configure " + bucket.label, false,
				showCommandFeedbackSetting.isChecked());
		}
		
		private void resetOnServer()
		{
			sendSeedMapperCommand("sm:config " + bucket.literal + " reset",
				"reset " + bucket.label, false,
				showCommandFeedbackSetting.isChecked());
		}
		
		private void requestRefresh(boolean silentErrors)
		{
			suppressNextResponse = silentErrors;
			suppressedLinesRemaining = silentErrors ? SILENT_REFRESH_LINES : 0;
			sendSeedMapperCommand("sm:config " + bucket.literal + " get",
				silentErrors ? null : "refresh " + bucket.label, silentErrors,
				false);
		}
		
		private String buildSetCommand()
		{
			StringBuilder builder = new StringBuilder("sm:config ")
				.append(bucket.literal).append(" set ");
			appendProperty(builder, "outlineColor",
				toHexColor(outlineColorSetting.getColor()));
			appendProperty(builder, "outlineAlpha",
				formatDouble(outlineAlphaSetting.getValue()));
			appendProperty(builder, "useCommandColor",
				String.valueOf(useCommandColorSetting.isChecked()));
			appendProperty(builder, "fillEnabled",
				String.valueOf(fillEnabledSetting.isChecked()));
			appendProperty(builder, "fillColor",
				toHexColor(fillColorSetting.getColor()));
			appendProperty(builder, "fillAlpha",
				formatDouble(fillAlphaSetting.getValue()));
			appendProperty(builder, "rainbow",
				String.valueOf(rainbowSetting.isChecked()));
			appendProperty(builder, "rainbowSpeed",
				formatDouble(rainbowSpeedSetting.getValue()));
			return builder.toString().trim();
		}
		
		private void appendProperty(StringBuilder builder, String property,
			String value)
		{
			builder.append(property).append(' ').append(value).append(' ');
		}
		
		private boolean handleChatFeedback(String message)
		{
			String json = extractJsonPayload(message, bucket.literal);
			boolean consumed = false;
			
			if(json != null)
			{
				try
				{
					JsonObject root =
						JsonParser.parseString(json).getAsJsonObject();
					loadFromJson(root);
				}catch(Exception ignored)
				{}
				consumed = true;
				suppressNextResponse = false;
				suppressedLinesRemaining = 0;
				
			}else if(suppressNextResponse && suppressedLinesRemaining > 0)
			{
				String lower = message.toLowerCase(Locale.ROOT);
				if(lower.contains(bucket.literal.toLowerCase(Locale.ROOT)))
				{
					consumed = true;
					suppressedLinesRemaining--;
					if(suppressedLinesRemaining <= 0)
						suppressNextResponse = false;
				}
			}
			
			return consumed;
		}
		
		private void loadFromJson(JsonObject json)
		{
			updateColor(outlineColorSetting, json.get("outlineColor"));
			updateSlider(outlineAlphaSetting, json.get("outlineAlpha"));
			updateCheckbox(useCommandColorSetting, json.get("useCommandColor"));
			updateCheckbox(fillEnabledSetting, json.get("fillEnabled"));
			updateColor(fillColorSetting, json.get("fillColor"));
			updateSlider(fillAlphaSetting, json.get("fillAlpha"));
			updateCheckbox(rainbowSetting, json.get("rainbow"));
			updateSlider(rainbowSpeedSetting, json.get("rainbowSpeed"));
		}
		
		private void updateColor(ColorSetting setting, JsonElement element)
		{
			Color parsed = parseColor(element);
			if(parsed != null)
				setting.setColor(parsed);
		}
		
		private void updateSlider(SliderSetting setting, JsonElement element)
		{
			if(element == null)
				return;
			try
			{
				setting.setValue(element.getAsDouble());
			}catch(Exception ignored)
			{}
		}
		
		private void updateCheckbox(CheckboxSetting setting,
			JsonElement element)
		{
			if(element == null)
				return;
			try
			{
				setting.setChecked(element.getAsBoolean());
			}catch(Exception ignored)
			{}
		}
	}
	
	private enum EspBucket
	{
		BLOCK("Block highlight ESP", "blockhighlightesp"),
		ORE_VEIN("Ore vein ESP", "oreveinesp"),
		TERRAIN("Terrain ESP", "terrainesp"),
		CANYON("Canyon ESP", "canyonesp"),
		CAVE("Cave ESP", "caveesp");
		
		private final String label;
		private final String literal;
		
		EspBucket(String label, String literal)
		{
			this.label = label;
			this.literal = literal;
		}
	}
	
	private String extractJsonPayload(String message, String literal)
	{
		if(message == null || literal == null || literal.isEmpty())
			return null;
		
		String sanitized = message.trim();
		String literalPattern = Pattern.quote(literal);
		String[] connectors = new String[]{"is currently set to",
			"has been set to", "has been reset to"};
		
		for(String connector : connectors)
		{
			Pattern pattern = Pattern.compile("(?i)" + literalPattern
				+ "\\s*(?:[:>\\-]\\s*)?" + Pattern.quote(connector)
				+ "\\s*(\\{.*?\\})(?:\\.|\\s|$)");
			java.util.regex.Matcher matcher = pattern.matcher(sanitized);
			if(!matcher.find())
				continue;
			
			String payload = matcher.group(1).trim();
			if(!payload.isEmpty())
				return payload;
		}
		
		return null;
	}
	
	private static String toHexColor(Color color)
	{
		return String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(),
			color.getGreen(), color.getBlue());
	}
	
	private static String formatDouble(double value)
	{
		String formatted = String.format(Locale.ROOT, "%.3f", value);
		if(formatted.indexOf('.') >= 0)
		{
			while(formatted.endsWith("0"))
				formatted = formatted.substring(0, formatted.length() - 1);
			if(formatted.endsWith("."))
				formatted = formatted.substring(0, formatted.length() - 1);
		}
		return formatted.isEmpty() ? "0" : formatted;
	}
	
	private static Color parseColor(JsonElement element)
	{
		if(element == null || !element.isJsonPrimitive())
			return null;
		return parseColor(element.getAsString());
	}
	
	private static Color parseColor(String value)
	{
		if(value == null)
			return null;
		
		String normalized = value.trim();
		if(normalized.isEmpty())
			return null;
		if(normalized.startsWith("#"))
			normalized = normalized.substring(1);
		else if(normalized.startsWith("0x") || normalized.startsWith("0X"))
			normalized = normalized.substring(2);
		
		if(normalized.length() == 8)
			normalized = normalized.substring(2);
		if(normalized.length() != 6)
			return null;
		
		try
		{
			int rgb = Integer.parseInt(normalized, 16);
			return new Color(rgb);
		}catch(NumberFormatException e)
		{
			return null;
		}
	}
	
	private enum HighlightMode
	{
		BLOCK(20),
		ORE_VEIN(20);
		
		private final int maxChunks;
		
		HighlightMode(int maxChunks)
		{
			this.maxChunks = maxChunks;
		}
	}
	
	private enum VariantDataMode
	{
		AUTO,
		FORCE_TRUE,
		FORCE_FALSE;
	}
	
	private enum LootClauseType
	{
		NONE(""),
		WITH("with"),
		WITHOUT("without");
		
		private final String keyword;
		
		private LootClauseType(String keyword)
		{
			this.keyword = keyword;
		}
	}
	
}
