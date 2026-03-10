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
import net.wurstclient.altgui.AltGuiFontManager;
import net.wurstclient.altgui.AltGuiScreen;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SliderSetting;
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
		new ColorSetting("Accent", new Color(0x2E9AFE));
	private final ColorSetting enabledColor =
		new ColorSetting("Enabled", new Color(0x23D18B));
	private final ColorSetting disabledColor =
		new ColorSetting("Disabled", new Color(0x5F6C82));
	
	private final SliderSetting uiOpacity = new SliderSetting("UI opacity",
		0.82, 0.25, 1, 0.01, ValueDisplay.PERCENTAGE);
	private final SliderSetting tooltipOpacity = new SliderSetting(
		"Tooltip opacity", 0.86, 0.1, 1, 0.01, ValueDisplay.PERCENTAGE);
	private final SliderSetting margin =
		new SliderSetting("Window margin", 18, 4, 80, 1, ValueDisplay.INTEGER);
	private final SliderSetting widthPercent = new SliderSetting("Window width",
		0.96, 0.5, 1, 0.01, ValueDisplay.PERCENTAGE);
	private final SliderSetting heightPercent = new SliderSetting(
		"Window height", 0.92, 0.5, 1, 0.01, ValueDisplay.PERCENTAGE);
	private final SliderSetting categoryWidth = new SliderSetting(
		"Category width", 110, 70, 240, 1, ValueDisplay.INTEGER);
	private final SliderSetting rowHeight =
		new SliderSetting("Row height", 18, 14, 30, 1, ValueDisplay.INTEGER);
	private final SliderSetting fontScale = new SliderSetting("Font scale", 1.0,
		0.3, 1.8, 0.05, ValueDisplay.DECIMAL);
	private final EnumSetting<FontSmoothing> fontSmoothing = new EnumSetting<>(
		"Font smoothing", FontSmoothing.values(), FontSmoothing.X2);
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
	private final EnumSetting<OpenBehavior> openBehavior = new EnumSetting<>(
		"On open", OpenBehavior.values(), OpenBehavior.FAVORITES);
	
	private float cachedMinScale = Float.NaN;
	private int cachedMinSmoothing = -1;
	private String cachedMinFamily = "";
	private int cachedMinRowHeight = 14;
	
	public AltGuiHack()
	{
		super("AltGUI");
		addSetting(bgColor);
		addSetting(panelColor);
		addSetting(panelLightColor);
		addSetting(textColor);
		addSetting(mutedTextColor);
		addSetting(accentColor);
		addSetting(enabledColor);
		addSetting(disabledColor);
		addSetting(uiOpacity);
		addSetting(tooltipOpacity);
		addSetting(margin);
		addSetting(widthPercent);
		addSetting(heightPercent);
		addSetting(categoryWidth);
		addSetting(rowHeight);
		addSetting(fontScale);
		addSetting(fontSmoothing);
		addSetting(fontFamily);
		addSetting(reloadFonts);
		addSetting(openFontsFolder);
		addSetting(fontGroup);
		addSetting(typeBadges);
		addSetting(favoriteStars);
		addSetting(openBehavior);
		refreshFontOptions();
	}
	
	@Override
	protected void onEnable()
	{
		normalizeLegacyScaleSettings();
		enforceNoClipLayout();
		
		if(!(MC.screen instanceof AltGuiScreen))
			MC.setScreen(new AltGuiScreen(MC.screen));
		
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
	
	public float getTooltipOpacity()
	{
		return tooltipOpacity.getValueF();
	}
	
	public int getMargin()
	{
		return margin.getValueI();
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
		return categoryWidth.getValueI();
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
	
	public SliderSetting getUiOpacitySetting()
	{
		return uiOpacity;
	}
	
	public SliderSetting getTooltipOpacitySetting()
	{
		return tooltipOpacity;
	}
	
	public SliderSetting getMarginSetting()
	{
		return margin;
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
	
	public EnumSetting<OpenBehavior> getOpenBehaviorSetting()
	{
		return openBehavior;
	}
	
	public boolean isTypeBadgesEnabled()
	{
		return typeBadges.isChecked();
	}
	
	public boolean isFavoriteStarsEnabled()
	{
		return favoriteStars.isChecked();
	}
	
	public OpenBehavior getOpenBehavior()
	{
		return openBehavior.getSelected();
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
		cachedMinRowHeight = Math.max(14, scaledTextHeight + 4);
		return cachedMinRowHeight;
	}
	
	public void enforceNoClipLayout()
	{
		int minRowHeight = getMinimumRowHeight();
		if(rowHeight.getValueI() < minRowHeight)
			rowHeight.setValue(minRowHeight);
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
		uiOpacity.setValue(uiOpacity.getDefaultValue());
		tooltipOpacity.setValue(tooltipOpacity.getDefaultValue());
		margin.setValue(margin.getDefaultValue());
		widthPercent.setValue(widthPercent.getDefaultValue());
		heightPercent.setValue(heightPercent.getDefaultValue());
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
}
