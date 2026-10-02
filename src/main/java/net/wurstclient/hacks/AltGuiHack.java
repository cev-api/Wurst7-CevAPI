/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;

import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.Category;
import net.wurstclient.altgui.AltGuiFontManager;
import net.wurstclient.altgui.AltGuiScreen;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SpacerSetting;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.text.WText;

@DontSaveState
@DontBlock
@SearchTags({"alt gui", "altgui", "alternate gui", "meteor gui", "click gui",
	"clickgui", "hack menu"})
public final class AltGuiHack extends Hack
{
	private final ColorSetting bgColor =
		new ColorSetting("Background", new Color(0x0D0F13));
	private final ColorSetting panelColor =
		new ColorSetting("Panel", new Color(0x151A22));
	private final ColorSetting panelLightColor =
		new ColorSetting("Panel light", new Color(0x1B212C));
	private final ColorSetting textColor =
		new ColorSetting("Text", new Color(0xE9EDF5));
	private final ColorSetting mutedTextColor =
		new ColorSetting("Muted text", new Color(0x97A2B5));
	private final ColorSetting accentColor =
		new ColorSetting("Accent", new Color(0x8100DD));
	private final ColorSetting enabledColor =
		new ColorSetting("Enabled", new Color(0xEA00FF));
	private final ColorSetting disabledColor =
		new ColorSetting("Disabled", new Color(0x5F6C82));
	private final ColorSetting sectionBorderColor =
		new ColorSetting("Section border", new Color(0x242D3C));
	private final ColorSetting hackRowColor =
		new ColorSetting("Hack row background", new Color(0x1B212C));
	private final ColorSetting enabledHackRowColor =
		new ColorSetting("Enabled hack row background", new Color(0x8D238E));
	private final ColorSetting alternateHackRowColor =
		new ColorSetting("Alternate hack row background", new Color(0x202733));
	private final ColorSetting hackRowHighlightColor =
		new ColorSetting("Hack row highlight color", new Color(0x992EFE));
	private final ColorSetting settingRowColor =
		new ColorSetting("Setting row background", new Color(0x1B212C));
	private final ColorSetting alternateSettingRowColor = new ColorSetting(
		"Alternate setting row background", new Color(0x242B36));
	private final ColorSetting settingRowHighlightColor =
		new ColorSetting("Setting row highlight", new Color(0x9E2EFE));
	private final ColorSetting settingValueBackgroundColor =
		new ColorSetting("Setting value background", new Color(0x151A22));
	private final ColorSetting descriptionColor =
		new ColorSetting("Alt text", new Color(0x97A2B5));
	
	private final SliderSetting uiOpacity = new SliderSetting("UI opacity",
		0.84, 0.25, 1, 0.01, ValueDisplay.PERCENTAGE);
	private final SliderSetting backgroundOpacity = new SliderSetting(
		"Background opacity", 0.4, 0, 1, 0.01, ValueDisplay.PERCENTAGE);
	private final SliderSetting tooltipOpacity = new SliderSetting(
		"Tooltip opacity", 1.0, 0.1, 1, 0.01, ValueDisplay.PERCENTAGE);
	private final SliderSetting settingsWidth = new SliderSetting(
		"Settings width", 0.44, 0.05, 1.0, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting settingsHeight = new SliderSetting(
		"Settings height", 0.7, 0.05, 1.0, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting widthPercent = new SliderSetting("Window width",
		0.87, 0.5, 1, 0.01, ValueDisplay.PERCENTAGE);
	private final SliderSetting heightPercent = new SliderSetting(
		"Window height", 0.75, 0.5, 1, 0.01, ValueDisplay.PERCENTAGE);
	private final SliderSetting categoryHeight = new SliderSetting(
		"Category height", 24, 8, 32, 1, ValueDisplay.INTEGER);
	private final SliderSetting categoryWidth = new SliderSetting(
		"Category width", 105, 40, 240, 1, ValueDisplay.INTEGER);
	private final SliderSetting rowHeight =
		new SliderSetting("Row height", 13, 8, 30, 1, ValueDisplay.INTEGER);
	private final SliderSetting fontScale = new SliderSetting("Font scale", 1.0,
		0.3, 1.8, 0.05, ValueDisplay.DECIMAL);
	private final EnumSetting<FontSmoothing> fontSmoothing = new EnumSetting<>(
		"Font smoothing", FontSmoothing.values(), FontSmoothing.OFF);
	private final StringDropdownSetting fontFamily =
		new StringDropdownSetting("Font family",
			WText.literal("TTF/OTF file from the Wurst fonts folder."));
	private final ButtonSetting reloadFonts =
		new ButtonSetting("Reload fonts", this::refreshFontOptions);
	private final ButtonSetting openFontsFolder =
		new ButtonSetting("Open fonts folder",
			() -> AltGuiFontManager.getInstance().openFontsFolder());
	private final SettingGroup fontGroup = new SettingGroup("Font",
		WText.literal("Font scale, family and smoothing for AltGUI."), true,
		true).addChildren(fontScale, fontSmoothing, fontFamily, reloadFonts,
			openFontsFolder);
	private final CheckboxSetting typeBadges =
		new CheckboxSetting("Type badges", false);
	private final CheckboxSetting favoriteStars =
		new CheckboxSetting("Favorite stars", true);
	private final CheckboxSetting fillColorValues = new CheckboxSetting(
		"Fill color values",
		"Show color settings as filled value boxes instead of colored outlines.",
		true);
	private final CheckboxSetting settingRowDividers = new CheckboxSetting(
		"Setting row dividers",
		"Show horizontal divider lines between expanded setting rows.", false);
	private final CheckboxSetting settingRowStriping =
		new CheckboxSetting("Setting row striping",
			"Alternate the background color of settings rows.", true);
	private final CheckboxSetting switchSettingToggles =
		new CheckboxSetting("Switch-style setting toggles",
			"Use switches instead of the original ON/OFF buttons for settings.",
			false);
	private final CheckboxSetting switchHackToggles =
		new CheckboxSetting("Switch-style hack toggles",
			"Use switches instead of ON/OFF buttons in the hack list.", false);
	private final SliderSetting settingHighlightThickness = new SliderSetting(
		"Setting highlight thickness", 0.8, 0, 4, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting settingDividerThickness = new SliderSetting(
		"Setting divider thickness", 0.1, 0, 4, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting hackRowBorderThickness = new SliderSetting(
		"Hack row border thickness", 0.4, 0, 4, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting hackHighlightThickness = new SliderSetting(
		"Hack highlight thickness", 1, 0, 4, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting categoryRowBorderThickness = new SliderSetting(
		"Category border thickness", 0.9, 0, 4, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting sectionBorderThickness = new SliderSetting(
		"Section border thickness", 1, 0, 4, 0.1, ValueDisplay.DECIMAL);
	private final CheckboxSetting hackRowStriping =
		new CheckboxSetting("Hack row striping",
			"Alternate the background color of hack rows.", true);
	private final CheckboxSetting hackRowBorders = new CheckboxSetting(
		"Hack row borders", "Draw a border around each hack row.", true);
	private final CheckboxSetting categoryRowBorders =
		new CheckboxSetting("Category row borders",
			"Draw a border around each category row.", true);
	private final CheckboxSetting hackRowHighlight =
		new CheckboxSetting("Hack row highlight",
			"Highlight the hovered or selected hack row.", true);
	private final CheckboxSetting sectionBorders = new CheckboxSetting(
		"Section borders", "Show borders around the AltGUI panes.", true);
	private final CheckboxSetting hackExpandIcons = new CheckboxSetting(
		"Hack expand icons",
		"Show triangle icons before hacks to indicate collapsed/expanded settings.",
		true);
	private final CheckboxSetting autoSizeTopTabs = new CheckboxSetting(
		"Auto-size top tabs",
		"Automatically size category tabs in top-tab mode to fit their text.",
		true);
	private final CheckboxSetting searchOnlyWhileTyping =
		new CheckboxSetting("Show search while typing",
			"Hide the search bar until you start typing a query.", true);
	private final CheckboxSetting searchSettings = new CheckboxSetting(
		"Search settings/toggles",
		"Include setting names and values in AltGUI search results.", true);
	private final CheckboxSetting keepHackSettingsOpen = new CheckboxSetting(
		"Keep hack settings open",
		"Keep expanded hack settings open when AltGUI is closed and reopened.",
		true);
	private final EnumSetting<OpenBehavior> openBehavior = new EnumSetting<>(
		"On open", OpenBehavior.values(), OpenBehavior.LAST_POSITION);
	private final EnumSetting<CategoryLayout> categoryLayout =
		new EnumSetting<>("Category layout", CategoryLayout.values(),
			CategoryLayout.SIDEBAR);
	private final EnumSetting<HackClickMode> hackClickMode = new EnumSetting<>(
		"Hack click mode", HackClickMode.values(), HackClickMode.OPEN_SETTINGS);
	private final SettingGroup colorsGroup = new SettingGroup("Colors",
		WText.literal("Backgrounds, highlights, text and borders."), false,
		false).addChildren(bgColor, panelColor, panelLightColor, textColor,
			mutedTextColor, descriptionColor, accentColor, enabledColor,
			disabledColor, sectionBorderColor, hackRowColor,
			enabledHackRowColor, alternateHackRowColor, hackRowHighlightColor,
			settingRowColor, alternateSettingRowColor, settingRowHighlightColor,
			settingValueBackgroundColor);
	private final SettingGroup layoutGroup = new SettingGroup("Layout & font",
		WText.literal("Window size, columns, spacing and typography."), false,
		false).addChildren(uiOpacity, backgroundOpacity, tooltipOpacity,
			settingsWidth, settingsHeight, widthPercent, heightPercent,
			categoryHeight, categoryWidth, rowHeight, categoryLayout,
			fontGroup);
	private final SettingGroup hackListGroup = new SettingGroup("Hack list",
		WText.literal("Hack row appearance and click behavior."), false, false)
			.addChildren(hackRowHighlight, hackRowStriping, hackRowBorders,
				hackRowBorderThickness, hackHighlightThickness,
				categoryRowBorders, categoryRowBorderThickness,
				switchHackToggles, favoriteStars, hackExpandIcons,
				hackClickMode);
	private final SettingGroup settingsListGroup =
		new SettingGroup("Settings list",
			WText.literal("Setting row backgrounds, values and highlights."),
			false, false).addChildren(typeBadges, fillColorValues,
				settingRowDividers, settingDividerThickness, settingRowStriping,
				settingHighlightThickness, switchSettingToggles, sectionBorders,
				sectionBorderThickness);
	private final SettingGroup searchGroup =
		new SettingGroup("Search & startup",
			WText.literal("Search behavior and what AltGUI restores on open."),
			false, false).addChildren(searchOnlyWhileTyping, searchSettings,
				keepHackSettingsOpen, openBehavior);
	
	private float cachedMinScale = Float.NaN;
	private int cachedMinSmoothing = -1;
	private String cachedMinFamily = "";
	private int cachedMinRowHeight = 14;
	
	public AltGuiHack()
	{
		super("AltGUI");
		addGroupedSettings(colorsGroup);
		addSetting(new SpacerSetting(8));
		addGroupedSettings(layoutGroup);
		addSetting(new SpacerSetting(8));
		addGroupedSettings(hackListGroup);
		addSetting(new SpacerSetting(8));
		addGroupedSettings(settingsListGroup);
		addSetting(new SpacerSetting(8));
		addGroupedSettings(searchGroup);
		refreshFontOptions();
	}
	
	private void addGroupedSettings(SettingGroup group)
	{
		addSetting(group);
		for(Setting child : group.getChildren())
		{
			if(child instanceof SettingGroup nested)
				addGroupedSettings(nested);
			else
				addSetting(child);
		}
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.gui == null)
		{
			setEnabled(false);
			return;
		}
		
		normalizeLegacyScaleSettings();
		enforceNoClipLayout();
		
		if(!(MC.gui.screen() instanceof AltGuiScreen))
			MC.gui.setScreen(new AltGuiScreen(MC.gui.screen()));
		
		setEnabled(false);
	}
	
	private void normalizeLegacyScaleSettings()
	{
		double w = widthPercent.getValue();
		double h = heightPercent.getValue();
		if(w > 2)
			widthPercent.setValue(w / 100D);
		if(h > 2)
			heightPercent.setValue(h / 100D);
	}
	
	public int getBackgroundColor()
	{
		return bgColor.getColorI();
	}
	
	public int getPanelColor()
	{
		return panelColor.getColorI();
	}
	
	public int getPanelLightColor()
	{
		return panelLightColor.getColorI();
	}
	
	public int getTextColor()
	{
		return textColor.getColorI();
	}
	
	public int getMutedTextColor()
	{
		return mutedTextColor.getColorI();
	}
	
	public int getAccentColor()
	{
		return accentColor.getColorI();
	}
	
	public int getEnabledColor()
	{
		return enabledColor.getColorI();
	}
	
	public int getDisabledColor()
	{
		return disabledColor.getColorI();
	}
	
	public float getUiOpacity()
	{
		return uiOpacity.getValueF();
	}
	
	public float getBackgroundOpacity()
	{
		return backgroundOpacity.getValueF();
	}
	
	public float getTooltipOpacity()
	{
		return tooltipOpacity.getValueF();
	}
	
	public float getSettingsWidth()
	{
		return (float)settingsWidth.getValue();
	}
	
	public float getSettingsHeight()
	{
		return (float)settingsHeight.getValue();
	}
	
	public double getWidthPercent()
	{
		return widthPercent.getValue();
	}
	
	public double getHeightPercent()
	{
		return heightPercent.getValue();
	}
	
	public int getCategoryWidth()
	{
		return Math.max(categoryWidth.getValueI(), getMinimumCategoryWidth());
	}
	
	public int getCategoryHeight()
	{
		return Math.max(categoryHeight.getValueI(), getMinimumCategoryHeight());
	}
	
	public int getRowHeight()
	{
		return Math.max(rowHeight.getValueI(), getMinimumRowHeight());
	}
	
	public float getFontScale()
	{
		return (float)fontScale.getValue();
	}
	
	public ColorSetting getBgColorSetting()
	{
		return bgColor;
	}
	
	public ColorSetting getPanelColorSetting()
	{
		return panelColor;
	}
	
	public ColorSetting getPanelLightColorSetting()
	{
		return panelLightColor;
	}
	
	public ColorSetting getTextColorSetting()
	{
		return textColor;
	}
	
	public ColorSetting getMutedTextColorSetting()
	{
		return mutedTextColor;
	}
	
	public ColorSetting getAccentColorSetting()
	{
		return accentColor;
	}
	
	public ColorSetting getEnabledColorSetting()
	{
		return enabledColor;
	}
	
	public ColorSetting getDisabledColorSetting()
	{
		return disabledColor;
	}
	
	public ColorSetting getSectionBorderColorSetting()
	{
		return sectionBorderColor;
	}
	
	public int getSectionBorderColor()
	{
		return sectionBorderColor.getColorI();
	}
	
	public ColorSetting getHackRowColorSetting()
	{
		return hackRowColor;
	}
	
	public ColorSetting getEnabledHackRowColorSetting()
	{
		return enabledHackRowColor;
	}
	
	public ColorSetting getAlternateHackRowColorSetting()
	{
		return alternateHackRowColor;
	}
	
	public ColorSetting getHackRowHighlightColorSetting()
	{
		return hackRowHighlightColor;
	}
	
	public ColorSetting getSettingRowColorSetting()
	{
		return settingRowColor;
	}
	
	public ColorSetting getAlternateSettingRowColorSetting()
	{
		return alternateSettingRowColor;
	}
	
	public ColorSetting getSettingRowHighlightColorSetting()
	{
		return settingRowHighlightColor;
	}
	
	public ColorSetting getSettingValueBackgroundColorSetting()
	{
		return settingValueBackgroundColor;
	}
	
	public int getHackRowColor()
	{
		return hackRowColor.getColorI();
	}
	
	public int getEnabledHackRowColor()
	{
		return enabledHackRowColor.getColorI();
	}
	
	public int getAlternateHackRowColor()
	{
		return alternateHackRowColor.getColorI();
	}
	
	public int getHackRowHighlightColor()
	{
		return hackRowHighlightColor.getColorI();
	}
	
	public int getSettingRowColor()
	{
		return settingRowColor.getColorI();
	}
	
	public int getAlternateSettingRowColor()
	{
		return alternateSettingRowColor.getColorI();
	}
	
	public int getSettingRowHighlightColor()
	{
		return settingRowHighlightColor.getColorI();
	}
	
	public int getSettingValueBackgroundColor()
	{
		return settingValueBackgroundColor.getColorI();
	}
	
	public ColorSetting getDescriptionColorSetting()
	{
		return descriptionColor;
	}
	
	public int getDescriptionColor()
	{
		return descriptionColor.getColorI();
	}
	
	public float getSettingHighlightThickness()
	{
		return (float)settingHighlightThickness.getValue();
	}
	
	public float getSettingDividerThickness()
	{
		return (float)settingDividerThickness.getValue();
	}
	
	public float getHackRowBorderThickness()
	{
		return (float)hackRowBorderThickness.getValue();
	}
	
	public float getHackHighlightThickness()
	{
		return (float)hackHighlightThickness.getValue();
	}
	
	public SliderSetting getHackHighlightThicknessSetting()
	{
		return hackHighlightThickness;
	}
	
	public float getCategoryRowBorderThickness()
	{
		return (float)categoryRowBorderThickness.getValue();
	}
	
	public float getSectionBorderThickness()
	{
		return (float)sectionBorderThickness.getValue();
	}
	
	public SliderSetting getHackRowBorderThicknessSetting()
	{
		return hackRowBorderThickness;
	}
	
	public SliderSetting getCategoryRowBorderThicknessSetting()
	{
		return categoryRowBorderThickness;
	}
	
	public SliderSetting getSectionBorderThicknessSetting()
	{
		return sectionBorderThickness;
	}
	
	public boolean isHackRowHighlightEnabled()
	{
		return hackRowHighlight.isChecked();
	}
	
	public boolean isHackRowStripingEnabled()
	{
		return hackRowStriping.isChecked();
	}
	
	public boolean isHackRowBordersEnabled()
	{
		return hackRowBorders.isChecked();
	}
	
	public boolean isCategoryRowBordersEnabled()
	{
		return categoryRowBorders.isChecked();
	}
	
	public SliderSetting getUiOpacitySetting()
	{
		return uiOpacity;
	}
	
	public SliderSetting getBackgroundOpacitySetting()
	{
		return backgroundOpacity;
	}
	
	public SliderSetting getTooltipOpacitySetting()
	{
		return tooltipOpacity;
	}
	
	public SliderSetting getSettingsWidthSetting()
	{
		return settingsWidth;
	}
	
	public SliderSetting getSettingsHeightSetting()
	{
		return settingsHeight;
	}
	
	public SliderSetting getWidthPercentSetting()
	{
		return widthPercent;
	}
	
	public SliderSetting getHeightPercentSetting()
	{
		return heightPercent;
	}
	
	public SliderSetting getCategoryWidthSetting()
	{
		return categoryWidth;
	}
	
	public SliderSetting getCategoryHeightSetting()
	{
		return categoryHeight;
	}
	
	public SliderSetting getRowHeightSetting()
	{
		return rowHeight;
	}
	
	public SliderSetting getFontScaleSetting()
	{
		return fontScale;
	}
	
	public StringDropdownSetting getFontFamilySetting()
	{
		return fontFamily;
	}
	
	public String getSelectedFontFamily()
	{
		String selected = fontFamily.getSelected();
		return selected == null || selected.isBlank() ? "Minecraft" : selected;
	}
	
	public int getFontSmoothingFactor()
	{
		return fontSmoothing.getSelected().factor();
	}
	
	public void refreshFontOptions()
	{
		AltGuiFontManager manager = AltGuiFontManager.getInstance();
		manager.setSmoothingFactor(getFontSmoothingFactor());
		manager.reloadFonts();
		fontFamily.setOptions(manager.getFontOptions());
		if(fontFamily.getSelected().isBlank())
			fontFamily.setSelected("Minecraft");
	}
	
	public CheckboxSetting getTypeBadgesSetting()
	{
		return typeBadges;
	}
	
	public CheckboxSetting getFavoriteStarsSetting()
	{
		return favoriteStars;
	}
	
	public CheckboxSetting getFillColorValuesSetting()
	{
		return fillColorValues;
	}
	
	public CheckboxSetting getSettingRowDividersSetting()
	{
		return settingRowDividers;
	}
	
	public SliderSetting getSettingDividerThicknessSetting()
	{
		return settingDividerThickness;
	}
	
	public CheckboxSetting getSettingRowStripingSetting()
	{
		return settingRowStriping;
	}
	
	public CheckboxSetting getSectionBordersSetting()
	{
		return sectionBorders;
	}
	
	public CheckboxSetting getHackExpandIconsSetting()
	{
		return hackExpandIcons;
	}
	
	public CheckboxSetting getAutoSizeTopTabsSetting()
	{
		return autoSizeTopTabs;
	}
	
	public CheckboxSetting getSearchOnlyWhileTypingSetting()
	{
		return searchOnlyWhileTyping;
	}
	
	public EnumSetting<OpenBehavior> getOpenBehaviorSetting()
	{
		return openBehavior;
	}
	
	public EnumSetting<CategoryLayout> getCategoryLayoutSetting()
	{
		return categoryLayout;
	}
	
	public boolean isTypeBadgesEnabled()
	{
		return typeBadges.isChecked();
	}
	
	public boolean isFavoriteStarsEnabled()
	{
		return favoriteStars.isChecked();
	}
	
	public boolean isFillColorValuesEnabled()
	{
		return fillColorValues.isChecked();
	}
	
	public boolean isSettingRowDividersEnabled()
	{
		return settingRowDividers.isChecked();
	}
	
	public boolean isSettingRowStripingEnabled()
	{
		return settingRowStriping.isChecked();
	}
	
	public boolean isSwitchSettingTogglesEnabled()
	{
		return switchSettingToggles.isChecked();
	}
	
	public boolean isSwitchHackTogglesEnabled()
	{
		return switchHackToggles.isChecked();
	}
	
	public boolean isSectionBordersEnabled()
	{
		return sectionBorders.isChecked();
	}
	
	public boolean isHackExpandIconsEnabled()
	{
		return hackExpandIcons.isChecked();
	}
	
	public boolean isAutoSizeTopTabsEnabled()
	{
		return autoSizeTopTabs.isChecked();
	}
	
	public boolean isSearchOnlyWhileTypingEnabled()
	{
		return searchOnlyWhileTyping.isChecked();
	}
	
	public boolean isSearchSettingsEnabled()
	{
		return searchSettings.isChecked();
	}
	
	public boolean isKeepHackSettingsOpenEnabled()
	{
		return keepHackSettingsOpen.isChecked();
	}
	
	public OpenBehavior getOpenBehavior()
	{
		return openBehavior.getSelected();
	}
	
	public CategoryLayout getCategoryLayout()
	{
		return categoryLayout.getSelected();
	}
	
	public EnumSetting<HackClickMode> getHackClickModeSetting()
	{
		return hackClickMode;
	}
	
	public HackClickMode getHackClickMode()
	{
		return hackClickMode.getSelected();
	}
	
	public int getMinimumRowHeight()
	{
		float scale = getFontScale();
		int smoothing = getFontSmoothingFactor();
		String family = getSelectedFontFamily();
		if(scale == cachedMinScale && smoothing == cachedMinSmoothing
			&& family.equals(cachedMinFamily))
			return cachedMinRowHeight;
		
		AltGuiFontManager manager = AltGuiFontManager.getInstance();
		manager.setSmoothingFactor(smoothing);
		manager.setActiveFont(family);
		int lineHeight =
			MC != null && MC.font != null ? manager.getLineHeight(MC.font) : 9;
		int scaledTextHeight = (int)Math.ceil(lineHeight * scale);
		cachedMinScale = scale;
		cachedMinSmoothing = smoothing;
		cachedMinFamily = family;
		cachedMinRowHeight = Math.max(10, scaledTextHeight + 4);
		return cachedMinRowHeight;
	}
	
	public int getMinimumCategoryHeight()
	{
		AltGuiFontManager manager = AltGuiFontManager.getInstance();
		manager.setSmoothingFactor(getFontSmoothingFactor());
		manager.setActiveFont(getSelectedFontFamily());
		int lineHeight =
			MC != null && MC.font != null ? manager.getLineHeight(MC.font) : 9;
		int scaledTextHeight = (int)Math.ceil(lineHeight * getFontScale());
		return Math.max(10, scaledTextHeight + 4);
	}
	
	public int getMinimumCategoryWidth()
	{
		AltGuiFontManager manager = AltGuiFontManager.getInstance();
		manager.setSmoothingFactor(getFontSmoothingFactor());
		manager.setActiveFont(getSelectedFontFamily());
		int maxTextWidth = manager.getTextWidth(MC.font, "Client Settings");
		maxTextWidth =
			Math.max(maxTextWidth, manager.getTextWidth(MC.font, "Enabled"));
		for(Category category : Category.values())
			maxTextWidth = Math.max(maxTextWidth,
				manager.getTextWidth(MC.font, category.getName()));
		return Math.max(40, (int)Math.ceil(maxTextWidth * getFontScale()) + 20);
	}
	
	public void enforceNoClipLayout()
	{
		int minRowHeight = getMinimumRowHeight();
		if(rowHeight.getValueI() < minRowHeight)
			rowHeight.setValue(minRowHeight);
		int minCategoryHeight = getMinimumCategoryHeight();
		if(categoryHeight.getValueI() < minCategoryHeight)
			categoryHeight.setValue(minCategoryHeight);
		int minCategoryWidth = getMinimumCategoryWidth();
		if(categoryWidth.getValueI() < minCategoryWidth)
			categoryWidth.setValue(minCategoryWidth);
	}
	
	public void resetStyle()
	{
		bgColor.setColor(bgColor.getDefaultColor());
		panelColor.setColor(panelColor.getDefaultColor());
		panelLightColor.setColor(panelLightColor.getDefaultColor());
		textColor.setColor(textColor.getDefaultColor());
		mutedTextColor.setColor(mutedTextColor.getDefaultColor());
		accentColor.setColor(accentColor.getDefaultColor());
		enabledColor.setColor(enabledColor.getDefaultColor());
		disabledColor.setColor(disabledColor.getDefaultColor());
		sectionBorderColor.setColor(sectionBorderColor.getDefaultColor());
		hackRowColor.setColor(hackRowColor.getDefaultColor());
		enabledHackRowColor.setColor(enabledHackRowColor.getDefaultColor());
		alternateHackRowColor.setColor(alternateHackRowColor.getDefaultColor());
		hackRowHighlightColor.setColor(hackRowHighlightColor.getDefaultColor());
		settingRowColor.setColor(settingRowColor.getDefaultColor());
		alternateSettingRowColor
			.setColor(alternateSettingRowColor.getDefaultColor());
		settingRowHighlightColor
			.setColor(settingRowHighlightColor.getDefaultColor());
		settingValueBackgroundColor
			.setColor(settingValueBackgroundColor.getDefaultColor());
		descriptionColor.setColor(descriptionColor.getDefaultColor());
		uiOpacity.setValue(uiOpacity.getDefaultValue());
		backgroundOpacity.setValue(backgroundOpacity.getDefaultValue());
		tooltipOpacity.setValue(tooltipOpacity.getDefaultValue());
		settingsWidth.setValue(settingsWidth.getDefaultValue());
		settingsHeight.setValue(settingsHeight.getDefaultValue());
		widthPercent.setValue(widthPercent.getDefaultValue());
		heightPercent.setValue(heightPercent.getDefaultValue());
		categoryHeight.setValue(categoryHeight.getDefaultValue());
		categoryWidth.setValue(categoryWidth.getDefaultValue());
		rowHeight.setValue(rowHeight.getDefaultValue());
		fontScale.setValue(fontScale.getDefaultValue());
		fontSmoothing.setSelected(fontSmoothing.getDefaultSelected());
		fontFamily.resetToDefault();
		AltGuiFontManager.getInstance()
			.setSmoothingFactor(getFontSmoothingFactor());
		AltGuiFontManager.getInstance().setActiveFont("Minecraft");
		typeBadges.setChecked(typeBadges.isCheckedByDefault());
		favoriteStars.setChecked(favoriteStars.isCheckedByDefault());
		fillColorValues.setChecked(fillColorValues.isCheckedByDefault());
		settingRowDividers.setChecked(settingRowDividers.isCheckedByDefault());
		settingDividerThickness
			.setValue(settingDividerThickness.getDefaultValue());
		hackRowBorderThickness
			.setValue(hackRowBorderThickness.getDefaultValue());
		hackHighlightThickness
			.setValue(hackHighlightThickness.getDefaultValue());
		categoryRowBorderThickness
			.setValue(categoryRowBorderThickness.getDefaultValue());
		sectionBorderThickness
			.setValue(sectionBorderThickness.getDefaultValue());
		settingRowStriping.setChecked(settingRowStriping.isCheckedByDefault());
		switchSettingToggles
			.setChecked(switchSettingToggles.isCheckedByDefault());
		switchHackToggles.setChecked(switchHackToggles.isCheckedByDefault());
		settingHighlightThickness
			.setValue(settingHighlightThickness.getDefaultValue());
		hackRowHighlight.setChecked(hackRowHighlight.isCheckedByDefault());
		hackRowStriping.setChecked(hackRowStriping.isCheckedByDefault());
		hackRowBorders.setChecked(hackRowBorders.isCheckedByDefault());
		categoryRowBorders.setChecked(categoryRowBorders.isCheckedByDefault());
		sectionBorders.setChecked(sectionBorders.isCheckedByDefault());
		hackExpandIcons.setChecked(hackExpandIcons.isCheckedByDefault());
		autoSizeTopTabs.setChecked(autoSizeTopTabs.isCheckedByDefault());
		searchOnlyWhileTyping
			.setChecked(searchOnlyWhileTyping.isCheckedByDefault());
		categoryLayout.setSelected(categoryLayout.getDefaultSelected());
		hackClickMode.setSelected(hackClickMode.getDefaultSelected());
	}
	
	public enum FontSmoothing
	{
		OFF("Off", 1),
		X2("2x", 2),
		X3("3x", 3);
		
		private final String label;
		private final int factor;
		
		FontSmoothing(String label, int factor)
		{
			this.label = label;
			this.factor = factor;
		}
		
		public int factor()
		{
			return factor;
		}
		
		@Override
		public String toString()
		{
			return label;
		}
	}
	
	public enum OpenBehavior
	{
		FAVORITES("Favorites"),
		LAST_POSITION("Last position"),
		ENABLED("Enabled");
		
		private final String label;
		
		OpenBehavior(String label)
		{
			this.label = label;
		}
		
		@Override
		public String toString()
		{
			return label;
		}
	}
	
	public enum CategoryLayout
	{
		SIDEBAR("Sidebar"),
		TOP_TABS("Top tabs");
		
		private final String label;
		
		CategoryLayout(String label)
		{
			this.label = label;
		}
		
		@Override
		public String toString()
		{
			return label;
		}
	}
	
	public enum HackClickMode
	{
		TOGGLE("Toggle hacks"),
		OPEN_SETTINGS("Click opens settings");
		
		private final String label;
		
		HackClickMode(String label)
		{
			this.label = label;
		}
		
		@Override
		public String toString()
		{
			return label;
		}
	}
}
