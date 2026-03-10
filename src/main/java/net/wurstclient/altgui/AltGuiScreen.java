/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altgui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.wurstclient.Category;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.screens.EditBlockListScreen;
import net.wurstclient.clickgui.screens.EditBlockScreen;
import net.wurstclient.clickgui.screens.EditBookOffersScreen;
import net.wurstclient.clickgui.screens.EditColorScreen;
import net.wurstclient.clickgui.screens.EditEntityTypeListScreen;
import net.wurstclient.clickgui.screens.EditItemListScreen;
import net.wurstclient.clickgui.screens.EditTextFieldScreen;
import net.wurstclient.clickgui.screens.SelectFileScreen;
import net.wurstclient.clickgui.screens.WaypointsScreen;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.AltGuiHack;
import net.wurstclient.hacks.ClientChatOverlayHack;
import net.wurstclient.hacks.ClickGuiHack;
import net.wurstclient.hacks.NavigatorHack;
import net.wurstclient.hacks.TooManyHaxHack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.BlockSetting;
import net.wurstclient.settings.BookOffersSetting;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EntityTypeListSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.FileSetting;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.settings.MobWeaponRuleSetting;
import net.wurstclient.settings.PlantTypeSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SpacerSetting;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.ToggleAllPlantTypesSetting;
import net.wurstclient.settings.WaypointsSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.ColorUtils;

public final class AltGuiScreen extends Screen
{
	private static final int SEARCH_HEIGHT = 14;
	private static final int MAX_RECENTLY_DISABLED = 64;
	private static final ArrayList<String> RECENTLY_DISABLED_HACKS =
		new ArrayList<>();
	private static Category LAST_SELECTED_CATEGORY = Category.FAVORITES;
	private static boolean LAST_ENABLED_CATEGORY_SELECTED;
	private static boolean LAST_STYLE_CATEGORY_SELECTED;
	private static int LAST_MODULE_SCROLL;
	private static final HashSet<String> LAST_EXPANDED_FEATURES =
		new HashSet<>();
	
	private final Screen prevScreen;
	
	private EditBox searchBox;
	private String searchText = "";
	private Category selectedCategory = Category.FAVORITES;
	private boolean enabledCategorySelected;
	private boolean styleCategorySelected;
	
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int categoryW;
	private int moduleX;
	private int moduleY;
	private int moduleW;
	private int moduleH;
	
	private int moduleScroll;
	private int maxModuleScroll;
	
	private int uiMenuX;
	private int uiMenuY;
	private int uiMenuW;
	private int uiMenuH;
	
	private final HashSet<String> expandedFeatures = new HashSet<>();
	private final HashSet<SettingGroup> expandedGroups = new HashSet<>();
	private String hoverTooltip = "";
	private int pendingScrollRestore = -1;
	
	private SliderDrag draggingSlider;
	private final AltGuiFontManager fontManager =
		AltGuiFontManager.getInstance();
	private String appliedFontFamily = "";
	private int appliedFontSmoothing = -1;
	
	public AltGuiScreen(Screen prevScreen)
	{
		super(Component.literal("Alt GUI"));
		this.prevScreen = prevScreen;
	}
	
	@Override
	protected void init()
	{
		applyOpenBehavior();
		rebuildLayout();
		
		Font font = minecraft.font;
		searchBox = new EditBox(font, moduleX + 10, panelY + 10, moduleW - 20,
			SEARCH_HEIGHT, Component.literal("Search"));
		searchBox.setBordered(false);
		searchBox.setMaxLength(128);
		searchBox.setTextColor(cfg().getTextColor());
		searchBox.setValue(searchText);
		addRenderableWidget(searchBox);
		setFocused(searchBox);
		searchBox.setFocused(true);
	}
	
	private void applyOpenBehavior()
	{
		switch(cfg().getOpenBehavior())
		{
			case ENABLED ->
			{
				selectedCategory = Category.FAVORITES;
				enabledCategorySelected = true;
				styleCategorySelected = false;
				moduleScroll = 0;
				pendingScrollRestore = -1;
				expandedFeatures.clear();
			}
			case LAST_POSITION ->
			{
				selectedCategory = LAST_SELECTED_CATEGORY;
				enabledCategorySelected = LAST_ENABLED_CATEGORY_SELECTED;
				styleCategorySelected = LAST_STYLE_CATEGORY_SELECTED;
				moduleScroll = 0;
				pendingScrollRestore = Math.max(0, LAST_MODULE_SCROLL);
				expandedFeatures.clear();
				expandedFeatures.addAll(LAST_EXPANDED_FEATURES);
			}
			case FAVORITES ->
			{
				selectedCategory = Category.FAVORITES;
				enabledCategorySelected = false;
				styleCategorySelected = false;
				moduleScroll = 0;
				pendingScrollRestore = -1;
				expandedFeatures.clear();
			}
		}
	}
	
	private AltGuiHack cfg()
	{
		return WurstClient.INSTANCE.getHax().altGuiHack;
	}
	
	private void rebuildLayout()
	{
		int margin = cfg().getMargin();
		int targetW = (int)(width * cfg().getWidthPercent());
		int targetH = (int)(height * cfg().getHeightPercent());
		panelW = Math.max(320, Math.min(width - margin * 2, targetW));
		panelH = Math.max(220, Math.min(height - margin * 2, targetH));
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		categoryW = Math.min(cfg().getCategoryWidth(), panelW - 150);
		
		moduleX = panelX + categoryW + 8;
		moduleY = panelY + SEARCH_HEIGHT + 22;
		moduleW = panelW - categoryW - 16;
		moduleH = panelH - SEARCH_HEIGHT - 28;
		
		uiMenuX = moduleX + 8;
		uiMenuY = moduleY;
		uiMenuW = moduleW - 16;
		uiMenuH = moduleH;
	}
	
	@Override
	public void tick()
	{
		if(searchBox != null)
			searchText = searchBox.getValue();
	}
	
	private void applyLiveLayout()
	{
		cfg().enforceNoClipLayout();
		rebuildLayout();
		if(searchBox != null)
		{
			searchBox.setX(moduleX + 10);
			searchBox.setY(panelY + 10);
			searchBox.setWidth(moduleW - 20);
		}
		clampScroll();
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			minecraft.setScreen(prevScreen);
			return true;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		double mouseX = context.x();
		double mouseY = context.y();
		int button = context.button();
		
		if(super.mouseClicked(context, doubleClick))
			return true;
		
		if(handleCategoryClick(mouseX, mouseY))
			return true;
		
		if(handleModuleClick(mouseX, mouseY, button))
			return true;
		
		return false;
	}
	
	@Override
	public boolean mouseDragged(MouseButtonEvent context, double dragX,
		double dragY)
	{
		if(draggingSlider == null)
			return super.mouseDragged(context, dragX, dragY);
		
		setSliderValueFromMouse(draggingSlider, context.x());
		if(isStyleSlider(draggingSlider.slider()))
			applyLiveLayout();
		return true;
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent context)
	{
		draggingSlider = null;
		return super.mouseReleased(context);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount)
	{
		if(mouseX < moduleX || mouseX > moduleX + moduleW || mouseY < moduleY
			|| mouseY > moduleY + moduleH)
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount,
				verticalAmount);
		
		moduleScroll -= (int)(verticalAmount * cfg().getRowHeight());
		clampScroll();
		return true;
	}
	
	@Override
	public void resize(int width, int height)
	{
		super.resize(width, height);
		applyLiveLayout();
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		hoverTooltip = "";
		syncFontManager();
		int bg =
			withAlpha(cfg().getBackgroundColor(), cfg().getUiOpacity() * 0.86F);
		int panel = withAlpha(cfg().getPanelColor(), cfg().getUiOpacity());
		
		context.fill(0, 0, width, height, bg);
		context.fill(panelX, panelY, panelX + panelW, panelY + panelH, panel);
		context.fill(panelX + categoryW, panelY, panelX + categoryW + 1,
			panelY + panelH, withAlpha(cfg().getPanelLightColor(), 0.8F));
		
		renderHeader(context);
		renderCategories(context, mouseX, mouseY);
		renderModules(context, mouseX, mouseY);
		
		if(searchBox != null)
		{
			searchBox.setTextColor(cfg().getTextColor());
			searchBox.render(context, mouseX, mouseY, partialTicks);
		}
		
		renderTooltip(context);
	}
	
	private void renderHeader(GuiGraphics context)
	{
		Font font = minecraft.font;
		int accent = cfg().getAccentColor();
		int muted = cfg().getMutedTextColor();
		int panelLight =
			withAlpha(cfg().getPanelLightColor(), cfg().getUiOpacity());
		
		drawStringScaled(context, font, "Wurst7 CevAPI", panelX + 12,
			panelY + 10, accent, true);
		drawStringScaled(context, font, "Alt GUI", panelX + 12, panelY + 22,
			muted, false);
		
		context.fill(moduleX + 8, panelY + 8, moduleX + moduleW - 8,
			panelY + 10 + SEARCH_HEIGHT + 4, panelLight);
	}
	
	private void renderTooltip(GuiGraphics context)
	{
		if(hoverTooltip == null || hoverTooltip.isBlank())
			return;
		
		Font font = minecraft.font;
		String text = trimToWidth(font, hoverTooltip, panelW - 16);
		int boxY1 = panelY + panelH - 16;
		int boxY2 = panelY + panelH - 2;
		context.fill(panelX, boxY1, panelX + panelW, boxY2,
			withAlpha(cfg().getPanelLightColor(), cfg().getTooltipOpacity()));
		drawStringScaled(context, font, text, panelX + 8, boxY1 + 3,
			cfg().getMutedTextColor(), false);
	}
	
	private void renderCategories(GuiGraphics context, int mouseX, int mouseY)
	{
		Font font = minecraft.font;
		int y = panelY + SEARCH_HEIGHT + 30;
		int accentFill = withAlpha(cfg().getAccentColor(), 0.35F);
		int accentHover = withAlpha(cfg().getAccentColor(), 0.2F);
		
		int enabledRowH = 16;
		boolean enabledHovered = isInside(mouseX, mouseY, panelX + 6, y,
			panelX + categoryW - 8, y + enabledRowH);
		if(enabledCategorySelected || enabledHovered)
			context.fill(panelX + 6, y, panelX + categoryW - 8, y + enabledRowH,
				enabledCategorySelected ? accentFill : accentHover);
		
		drawStringScaled(context, font, "Enabled", panelX + 12, y + 4,
			enabledCategorySelected ? cfg().getTextColor()
				: cfg().getMutedTextColor(),
			false);
		y += enabledRowH + 4;
		
		for(Category category : Category.values())
		{
			int rowH = 16;
			boolean hovered = isInside(mouseX, mouseY, panelX + 6, y,
				panelX + categoryW - 8, y + rowH);
			boolean selected = !styleCategorySelected
				&& !enabledCategorySelected && selectedCategory == category;
			
			if(selected || hovered)
				context.fill(panelX + 6, y, panelX + categoryW - 8, y + rowH,
					selected ? accentFill : accentHover);
			
			drawStringScaled(context, font, category.getName(), panelX + 12,
				y + 4,
				selected ? cfg().getTextColor() : cfg().getMutedTextColor(),
				false);
			y += rowH + 4;
		}
		
		int styleRowH = 16;
		boolean styleHovered = isInside(mouseX, mouseY, panelX + 6, y,
			panelX + categoryW - 8, y + styleRowH);
		if(styleCategorySelected || styleHovered)
			context.fill(panelX + 6, y, panelX + categoryW - 8, y + styleRowH,
				styleCategorySelected ? accentFill : accentHover);
		
		drawStringScaled(context, font, "Client Settings", panelX + 12, y + 4,
			styleCategorySelected ? cfg().getTextColor()
				: cfg().getMutedTextColor(),
			false);
	}
	
	private void renderModules(GuiGraphics context, int mouseX, int mouseY)
	{
		Font font = minecraft.font;
		int contentY = moduleY - moduleScroll;
		int viewTop = moduleY;
		int viewBottom = moduleY + moduleH;
		int rowH = cfg().getRowHeight();
		int clipX1 = moduleX + 8;
		int clipY1 = moduleY;
		int clipX2 = moduleX + moduleW - 8;
		int clipY2 = moduleY + moduleH;
		int contentBottomX = moduleX + moduleW - 12;
		int totalContentHeight = 0;
		int bottomPadding = 6;
		context.enableScissor(clipX1, clipY1, clipX2, clipY2);
		
		List<DisplayEntry> entries = getDisplayedEntries();
		
		for(DisplayEntry entry : entries)
		{
			Feature feature = entry.feature();
			int rowTop = contentY;
			int rowBottom = rowTop + rowH;
			boolean visible = rowBottom >= viewTop && rowTop <= viewBottom;
			if(visible)
			{
				boolean hovered = isInside(mouseX, mouseY, moduleX + 8, rowTop,
					contentBottomX, rowBottom);
				if(hovered)
					hoverTooltip = feature.getDescription();
				int bgColor = feature.isEnabled()
					? withAlpha(cfg().getEnabledColor(), 0.28F)
					: hovered ? withAlpha(cfg().getAccentColor(), 0.2F)
						: withAlpha(cfg().getPanelLightColor(), 0.55F);
				context.fill(moduleX + 8, rowTop, contentBottomX, rowBottom,
					bgColor);
				int nameX = moduleX + 14;
				int rowTextY = centeredTextY(font, rowTop, rowBottom);
				if(feature instanceof Hack hack
					&& cfg().isFavoriteStarsEnabled() && hack.isFavorite())
				{
					drawStringScaled(context, font, "★", nameX, rowTextY,
						cfg().getAccentColor(), false);
					nameX += Math.max(12, scaledFontWidth(font, "★") + 4);
				}
				if(entry.recentDisabled())
				{
					String recentTag = "RECENT";
					drawStringScaled(context, font, recentTag, nameX, rowTextY,
						cfg().getMutedTextColor(), false);
					nameX += scaledFontWidth(font, recentTag) + 6;
				}
				
				drawStringScaled(context, font, feature.getName(), nameX,
					rowTextY, cfg().getTextColor(), false);
				
				if(featureHasStatePill(feature))
				{
					String stateLabel = feature.isEnabled() ? "ON" : "OFF";
					int pillW = 44;
					int toggleX2 = contentBottomX - 6;
					int toggleX1 = toggleX2 - pillW;
					int pillPad = getPillPadding(font, rowBottom - rowTop);
					int pillY1 = rowTop + pillPad;
					int pillY2 = rowBottom - pillPad;
					context.fill(toggleX1, pillY1, toggleX2, pillY2,
						feature.isEnabled()
							? withAlpha(cfg().getEnabledColor(), 0.9F)
							: withAlpha(cfg().getDisabledColor(), 0.92F));
					int pillTextY = centeredTextY(font, pillY1, pillY2);
					drawCenteredStringScaled(context, font, stateLabel,
						(toggleX1 + toggleX2) / 2, pillTextY,
						cfg().getTextColor());
				}
			}
			
			contentY += rowH;
			totalContentHeight += rowH;
			
			if(!expandedFeatures.contains(feature.getName()))
				continue;
			
			List<SettingRow> settingRows = getSettingRows(feature);
			for(SettingRow row : settingRows)
			{
				int h = row.height();
				int sy1 = contentY;
				int sy2 = sy1 + h;
				if(sy2 >= viewTop && sy1 <= viewBottom)
					renderSettingRow(context, font, mouseX, mouseY, row, sy1,
						sy2);
				contentY += h;
				totalContentHeight += h;
			}
		}
		context.disableScissor();
		
		totalContentHeight += bottomPadding;
		maxModuleScroll = Math.max(0, totalContentHeight - moduleH + 2);
		if(pendingScrollRestore >= 0)
		{
			moduleScroll = pendingScrollRestore;
			pendingScrollRestore = -1;
		}
		clampScroll();
		
		if(maxModuleScroll > 0)
			renderScrollbar(context);
	}
	
	private void renderScrollbar(GuiGraphics context)
	{
		int x1 = moduleX + moduleW - 11;
		int x2 = x1 + 3;
		context.fill(x1, moduleY, x2, moduleY + moduleH,
			withAlpha(cfg().getPanelLightColor(), 0.65F));
		
		double progress =
			maxModuleScroll == 0 ? 0 : moduleScroll / (double)maxModuleScroll;
		int knobH = Math.max(20,
			(int)(moduleH * (moduleH / (double)(moduleH + maxModuleScroll))));
		int knobY = moduleY + (int)((moduleH - knobH) * progress);
		context.fill(x1, knobY, x2, knobY + knobH, cfg().getAccentColor());
	}
	
	private void renderSettingRow(GuiGraphics context, Font font, int mouseX,
		int mouseY, SettingRow row, int y1, int y2)
	{
		if(row.setting() instanceof SpacerSetting)
			return;
		
		int depth = row.depth();
		int baseX1 = moduleX + 20;
		int rowX1 = baseX1 + depth * 10;
		int rowX2 = moduleX + moduleW - 12;
		boolean hovered = isInside(mouseX, mouseY, rowX1, y1, rowX2, y2);
		if(hovered && row.setting().getDescription() != null
			&& !row.setting().getDescription().isBlank())
			hoverTooltip = row.setting().getDescription();
		boolean oddStripe = ((y1 / Math.max(1, row.height())) & 1) == 0;
		int rowColor = hovered ? withAlpha(cfg().getAccentColor(), 0.28F)
			: oddStripe ? withAlpha(cfg().getPanelLightColor(), 0.78F)
				: withAlpha(cfg().getPanelLightColor(), 0.62F);
		context.fill(rowX1, y1, rowX2, y2, rowColor);
		context.fill(rowX1, y1, rowX2, y1 + 1,
			withAlpha(cfg().getTextColor(), 0.12F));
		context.fill(rowX1, y2 - 1, rowX2, y2,
			withAlpha(cfg().getTextColor(), 0.06F));
		
		for(int i = 0; i < depth; i++)
		{
			int guideX = baseX1 + i * 10 + 4;
			context.fill(guideX, y1, guideX + 1, y2,
				withAlpha(cfg().getMutedTextColor(), 0.28F));
		}
		
		int markerColor = getSettingMarkerColor(row.setting());
		context.fill(rowX1, y1, rowX1 + 3, y2, markerColor);
		
		int tagX2 = rowX1 + 6;
		if(cfg().isTypeBadgesEnabled())
		{
			String typeTag = getSettingTypeTag(row.setting());
			int tagX1 = rowX1 + 6;
			int tagW = Math.max(22, scaledFontWidth(font, typeTag) + 8);
			tagX2 = tagX1 + tagW;
			context.fill(tagX1, y1 + 2, tagX2, y2 - 2,
				withAlpha(cfg().getPanelColor(), 0.92F));
			int tagTextY = centeredTextY(font, y1 + 2, y2 - 2);
			drawCenteredStringScaled(context, font, typeTag,
				(tagX1 + tagX2) / 2, tagTextY,
				withAlpha(cfg().getMutedTextColor(), 0.95F));
		}
		
		String nameText = getSettingName(row.setting());
		String valueText = getSettingValue(row.setting());
		
		int valuePad = 6;
		int fixedValueColW = Math.min(180, Math.max(120, (rowX2 - rowX1) / 4));
		int valueBoxW = valueText.isEmpty() ? 0 : fixedValueColW;
		int valueX2 = rowX2 - 6;
		int valueX1 = valueX2 - valueBoxW;
		int nameMaxW =
			valueText.isEmpty() ? rowX2 - tagX2 - 12 : valueX1 - tagX2 - 10;
		
		nameText = trimToWidth(font, nameText, Math.max(30, nameMaxW));
		int settingTextY = centeredTextY(font, y1, y2);
		drawStringScaled(context, font, nameText, tagX2 + 6, settingTextY,
			cfg().getTextColor(), false);
		
		if(!valueText.isEmpty())
		{
			valueText = trimToWidth(font, valueText, valueBoxW - valuePad * 2);
			int valuePadY = getPillPadding(font, y2 - y1);
			int valueY1 = y1 + valuePadY;
			int valueY2 = y2 - valuePadY;
			if(valueY2 <= valueY1)
			{
				valueY1 = y1 + 1;
				valueY2 = y2 - 1;
			}
			if(row.setting() instanceof SliderSetting slider)
			{
				context.fill(valueX1, valueY1, valueX2, valueY2,
					withAlpha(cfg().getPanelLightColor(), 0.9F));
				int innerX1 = valueX1 + 2;
				int innerX2 = valueX2 - 2;
				int innerY1 = valueY1 + 1;
				int innerY2 = valueY2 - 1;
				if(innerY2 <= innerY1)
				{
					innerY1 = valueY1;
					innerY2 = valueY2;
				}
				int fill = (int)((innerX2 - innerX1) * slider.getPercentage());
				context.fill(innerX1, innerY1, innerX1 + fill, innerY2,
					withAlpha(cfg().getAccentColor(), 0.92F));
			}else
			{
				context.fill(valueX1, valueY1, valueX2, valueY2,
					getValueBadgeColor(row.setting(), valueText));
			}
			int valueTextY = centeredTextY(font, valueY1, valueY2);
			drawCenteredStringScaled(context, font, valueText,
				(valueX1 + valueX2) / 2, valueTextY, cfg().getTextColor());
			
			if(row.setting() instanceof ColorSetting color)
			{
				drawRectBorder(context, valueX1, valueY1, valueX2, valueY2,
					color.getColorI());
			}
		}
	}
	
	private void renderSliderPreview(GuiGraphics context, SliderSetting slider,
		int y1, int y2, int fillColor, int bx1, int bx2)
	{
		int by1 = y1 + 6;
		int by2 = y2 - 6;
		context.fill(bx1, by1, bx2, by2,
			withAlpha(cfg().getPanelLightColor(), 0.8F));
		
		int fill = (int)((bx2 - bx1) * slider.getPercentage());
		context.fill(bx1, by1, bx1 + fill, by2, fillColor);
	}
	
	private void renderUiSettingsPanel(GuiGraphics context, int mouseX,
		int mouseY)
	{
		Font font = minecraft.font;
		context.fill(uiMenuX, uiMenuY, uiMenuX + uiMenuW, uiMenuY + uiMenuH,
			withAlpha(cfg().getPanelColor(), 0.95F));
		context.fill(uiMenuX, uiMenuY, uiMenuX + uiMenuW, uiMenuY + 18,
			withAlpha(cfg().getPanelLightColor(), 0.95F));
		drawStringScaled(context, font, "AltGUI Style", uiMenuX + 8,
			uiMenuY + 5, cfg().getTextColor(), false);
		
		int y = uiMenuY + 24;
		y = renderUiSlider(context, font, "UI opacity",
			cfg().getUiOpacitySetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Window margin",
			cfg().getMarginSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Window width",
			cfg().getWidthPercentSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Window height",
			cfg().getHeightPercentSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Category width",
			cfg().getCategoryWidthSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Row height",
			cfg().getRowHeightSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Font scale",
			cfg().getFontScaleSetting(), y, mouseX, mouseY);
		y = renderUiToggle(context, font, "Type badges",
			cfg().getTypeBadgesSetting(), y, mouseX, mouseY);
		y += 4;
		
		y = renderUiColor(context, font, "Background",
			cfg().getBgColorSetting(), y, mouseX, mouseY);
		y = renderUiColor(context, font, "Panel", cfg().getPanelColorSetting(),
			y, mouseX, mouseY);
		y = renderUiColor(context, font, "Panel light",
			cfg().getPanelLightColorSetting(), y, mouseX, mouseY);
		y = renderUiColor(context, font, "Text", cfg().getTextColorSetting(), y,
			mouseX, mouseY);
		y = renderUiColor(context, font, "Muted",
			cfg().getMutedTextColorSetting(), y, mouseX, mouseY);
		y = renderUiColor(context, font, "Accent",
			cfg().getAccentColorSetting(), y, mouseX, mouseY);
		y = renderUiColor(context, font, "Enabled",
			cfg().getEnabledColorSetting(), y, mouseX, mouseY);
		y = renderUiColor(context, font, "Disabled",
			cfg().getDisabledColorSetting(), y, mouseX, mouseY);
		
		int btnY1 = uiMenuY + uiMenuH - 18;
		int btnY2 = uiMenuY + uiMenuH - 4;
		int btnX1 = uiMenuX + uiMenuW - 64;
		int btnX2 = uiMenuX + uiMenuW - 8;
		context.fill(btnX1, btnY1, btnX2, btnY2,
			withAlpha(cfg().getAccentColor(), 0.4F));
		drawCenteredStringScaled(context, font, "Reset", (btnX1 + btnX2) / 2,
			btnY1 + 3, cfg().getTextColor());
		
	}
	
	private int renderUiSlider(GuiGraphics context, Font font, String label,
		SliderSetting slider, int y, int mouseX, int mouseY)
	{
		int rowH = 14;
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		int trackX1 = uiMenuX + 144;
		int trackX2 = uiMenuX + uiMenuW - 12;
		boolean hovered = isInside(mouseX, mouseY, x1, y, x2, y + rowH);
		context.fill(x1, y, x2, y + rowH,
			hovered ? withAlpha(cfg().getAccentColor(), 0.14F)
				: withAlpha(cfg().getPanelLightColor(), 0.58F));
		drawStringScaled(context, font, label + ": " + slider.getValueString(),
			x1 + 4, y + 3, cfg().getMutedTextColor(), false);
		context.fill(trackX1, y + 4, trackX2, y + 10,
			withAlpha(cfg().getPanelLightColor(), 0.9F));
		int fill = (int)((trackX2 - trackX1) * slider.getPercentage());
		context.fill(trackX1, y + 4, trackX1 + fill, y + 10,
			cfg().getAccentColor());
		return y + rowH + 2;
	}
	
	private int renderUiToggle(GuiGraphics context, Font font, String label,
		CheckboxSetting setting, int y, int mouseX, int mouseY)
	{
		int rowH = 14;
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		boolean hovered = isInside(mouseX, mouseY, x1, y, x2, y + rowH);
		context.fill(x1, y, x2, y + rowH,
			hovered ? withAlpha(cfg().getAccentColor(), 0.14F)
				: withAlpha(cfg().getPanelLightColor(), 0.58F));
		drawStringScaled(context, font, label, x1 + 4, y + 3,
			cfg().getMutedTextColor(), false);
		
		String state = setting.isChecked() ? "ON" : "OFF";
		int pillW = 36;
		int pillX2 = x2 - 4;
		int pillX1 = pillX2 - pillW;
		context.fill(pillX1, y + 2, pillX2, y + rowH - 2,
			setting.isChecked() ? withAlpha(cfg().getEnabledColor(), 0.86F)
				: withAlpha(cfg().getDisabledColor(), 0.9F));
		drawCenteredStringScaled(context, font, state, (pillX1 + pillX2) / 2,
			y + 3, cfg().getTextColor());
		return y + rowH + 2;
	}
	
	private int renderUiColor(GuiGraphics context, Font font, String label,
		ColorSetting color, int y, int mouseX, int mouseY)
	{
		int rowH = 13;
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		boolean hovered = isInside(mouseX, mouseY, x1, y, x2, y + rowH);
		context.fill(x1, y, x2, y + rowH,
			hovered ? withAlpha(cfg().getAccentColor(), 0.14F)
				: withAlpha(cfg().getPanelLightColor(), 0.58F));
		drawStringScaled(context, font, label, x1 + 4, y + 2,
			cfg().getMutedTextColor(), false);
		context.fill(x2 - 44, y + 2, x2 - 30, y + rowH - 2, color.getColorI());
		drawStringScaled(context, font, ColorUtils.toHex(color.getColor()),
			x2 - 26, y + 2, cfg().getTextColor(), false);
		return y + rowH + 1;
	}
	
	private boolean handleUiSettingsClick(double mouseX, double mouseY,
		int button)
	{
		if(!isInside(mouseX, mouseY, uiMenuX, uiMenuY, uiMenuX + uiMenuW,
			uiMenuY + uiMenuH))
			return false;
		
		int y = uiMenuY + 24;
		y = handleUiSliderClick(cfg().getUiOpacitySetting(), mouseX, mouseY, y,
			button);
		y = handleUiSliderClick(cfg().getMarginSetting(), mouseX, mouseY, y,
			button);
		y = handleUiSliderClick(cfg().getWidthPercentSetting(), mouseX, mouseY,
			y, button);
		y = handleUiSliderClick(cfg().getHeightPercentSetting(), mouseX, mouseY,
			y, button);
		y = handleUiSliderClick(cfg().getCategoryWidthSetting(), mouseX, mouseY,
			y, button);
		y = handleUiSliderClick(cfg().getRowHeightSetting(), mouseX, mouseY, y,
			button);
		y = handleUiSliderClick(cfg().getFontScaleSetting(), mouseX, mouseY, y,
			button);
		y = handleUiToggleClick(cfg().getTypeBadgesSetting(), mouseX, mouseY,
			y);
		y += 4;
		
		y = handleUiColorClick(cfg().getBgColorSetting(), mouseX, mouseY, y);
		y = handleUiColorClick(cfg().getPanelColorSetting(), mouseX, mouseY, y);
		y = handleUiColorClick(cfg().getPanelLightColorSetting(), mouseX,
			mouseY, y);
		y = handleUiColorClick(cfg().getTextColorSetting(), mouseX, mouseY, y);
		y = handleUiColorClick(cfg().getMutedTextColorSetting(), mouseX, mouseY,
			y);
		y = handleUiColorClick(cfg().getAccentColorSetting(), mouseX, mouseY,
			y);
		y = handleUiColorClick(cfg().getEnabledColorSetting(), mouseX, mouseY,
			y);
		y = handleUiColorClick(cfg().getDisabledColorSetting(), mouseX, mouseY,
			y);
		
		int btnY1 = uiMenuY + uiMenuH - 18;
		int btnY2 = uiMenuY + uiMenuH - 4;
		int btnX1 = uiMenuX + uiMenuW - 64;
		int btnX2 = uiMenuX + uiMenuW - 8;
		if(isInside(mouseX, mouseY, btnX1, btnY1, btnX2, btnY2))
		{
			cfg().resetStyle();
			applyLiveLayout();
			return true;
		}
		
		return true;
	}
	
	private int handleUiSliderClick(SliderSetting slider, double mouseX,
		double mouseY, int y, int button)
	{
		int rowH = 14;
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		int trackX1 = uiMenuX + 144;
		int trackX2 = uiMenuX + uiMenuW - 12;
		if(isInside(mouseX, mouseY, x1, y, x2, y + rowH))
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
			{
				slider.setValue(slider.getValue() - slider.getIncrement());
			}else
			{
				draggingSlider = new SliderDrag(slider, trackX1, trackX2);
				setSliderValueFromMouse(draggingSlider, mouseX);
			}
			applyLiveLayout();
		}
		return y + rowH + 2;
	}
	
	private int handleUiColorClick(ColorSetting setting, double mouseX,
		double mouseY, int y)
	{
		int rowH = 13;
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		if(isInside(mouseX, mouseY, x1, y, x2, y + rowH))
			minecraft.setScreen(new EditColorScreen(this, setting));
		return y + rowH + 1;
	}
	
	private int handleUiToggleClick(CheckboxSetting setting, double mouseX,
		double mouseY, int y)
	{
		int rowH = 14;
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		if(isInside(mouseX, mouseY, x1, y, x2, y + rowH))
			setting.setChecked(!setting.isChecked());
		return y + rowH + 2;
	}
	
	private boolean handleCategoryClick(double mouseX, double mouseY)
	{
		int y = panelY + SEARCH_HEIGHT + 30;
		if(isInside(mouseX, mouseY, panelX + 6, y, panelX + categoryW - 8,
			y + 16))
		{
			enabledCategorySelected = true;
			styleCategorySelected = false;
			moduleScroll = 0;
			return true;
		}
		y += 20;
		
		for(Category category : Category.values())
		{
			if(isInside(mouseX, mouseY, panelX + 6, y, panelX + categoryW - 8,
				y + 16))
			{
				selectedCategory = category;
				enabledCategorySelected = false;
				styleCategorySelected = false;
				moduleScroll = 0;
				return true;
			}
			y += 20;
		}
		
		if(isInside(mouseX, mouseY, panelX + 6, y, panelX + categoryW - 8,
			y + 16))
		{
			enabledCategorySelected = false;
			styleCategorySelected = true;
			expandedFeatures
				.add(WurstClient.INSTANCE.getHax().clickGuiHack.getName());
			expandedFeatures
				.add(WurstClient.INSTANCE.getHax().altGuiHack.getName());
			moduleScroll = 0;
			return true;
		}
		
		return false;
	}
	
	private boolean handleModuleClick(double mouseX, double mouseY, int button)
	{
		if(mouseX < moduleX + 8 || mouseX > moduleX + moduleW - 8
			|| mouseY < moduleY || mouseY > moduleY + moduleH)
			return false;
		
		int contentY = moduleY - moduleScroll;
		int rowH = cfg().getRowHeight();
		List<DisplayEntry> entries = getDisplayedEntries();
		
		for(DisplayEntry entry : entries)
		{
			Feature feature = entry.feature();
			int rowTop = contentY;
			int rowBottom = rowTop + rowH;
			if(isInside(mouseX, mouseY, moduleX + 8, rowTop,
				moduleX + moduleW - 8, rowBottom))
			{
				if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
					toggleExpandedFeature(feature);
				else if(button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
					&& feature instanceof Hack hack)
					hack.setFavorite(!hack.isFavorite());
				else if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				{
					TooManyHaxHack tooManyHax =
						WurstClient.INSTANCE.getHax().tooManyHaxHack;
					if(feature != tooManyHax && tooManyHax.isEnabled()
						&& tooManyHax.isBlocked(feature))
					{
						ChatUtils.error(
							feature.getName() + " is blocked by TooManyHax.");
						return true;
					}
					
					if(isMenuOnlyFeature(feature))
					{
						toggleExpandedFeature(feature);
						return true;
					}
					
					feature.doPrimaryAction();
					if(feature instanceof Hack hack)
					{
						if(hack.isEnabled())
							removeRecentlyDisabled(hack.getName());
						else
							addRecentlyDisabled(hack.getName());
					}
				}
				return true;
			}
			contentY += rowH;
			
			if(!expandedFeatures.contains(feature.getName()))
				continue;
			
			for(SettingRow row : getSettingRows(feature))
			{
				int y1 = contentY;
				int y2 = y1 + row.height();
				if(isInside(mouseX, mouseY, moduleX + 20, y1,
					moduleX + moduleW - 12, y2))
					return handleSettingClick(row, mouseX, mouseY, button, y1,
						y2);
				contentY += row.height();
			}
		}
		
		return false;
	}
	
	private boolean handleSettingClick(SettingRow row, double mouseX,
		double mouseY, int button, int y1, int y2)
	{
		Feature owner = row.owner();
		Setting setting = row.setting();
		if(setting instanceof SpacerSetting)
			return false;
		
		if(setting instanceof CheckboxSetting checkbox)
		{
			checkbox.setChecked(!checkbox.isChecked());
			return true;
		}
		
		if(setting instanceof SliderSetting slider)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
			{
				slider.setValue(slider.getDefaultValue());
				if(owner instanceof ClientChatOverlayHack chatHack
					&& slider == chatHack.getChatFontScaleSetting())
					chatHack.resetVanillaChatScale();
				if(isStyleSlider(slider))
					applyLiveLayout();
				return true;
			}
			
			int rowX1 = moduleX + 20 + row.depth() * 10;
			int rowX2 = moduleX + moduleW - 12;
			int fixedValueColW =
				Math.min(180, Math.max(120, (rowX2 - rowX1) / 4));
			int valueX2 = rowX2 - 6;
			int valueX1 = valueX2 - fixedValueColW;
			int x1 = valueX1 + 2;
			int x2 = valueX2 - 2;
			draggingSlider = new SliderDrag(slider, x1, x2);
			setSliderValueFromMouse(draggingSlider, mouseX);
			if(isStyleSlider(slider))
				applyLiveLayout();
			return true;
		}
		
		if(setting instanceof EnumSetting<?> enumSetting)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
				enumSetting.selectPrev();
			else
				enumSetting.selectNext();
			return true;
		}
		
		if(setting instanceof StringDropdownSetting dropdown)
		{
			List<String> values = dropdown.getValues();
			if(values.isEmpty())
				return true;
			int i = values.indexOf(dropdown.getSelected());
			if(i < 0)
				i = 0;
			int delta = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1;
			for(int attempts = 0; attempts < values.size(); attempts++)
			{
				i += delta;
				if(i < 0)
					i = values.size() - 1;
				if(i >= values.size())
					i = 0;
				String candidate = values.get(i);
				if(owner instanceof AltGuiHack
					&& dropdown == cfg().getFontFamilySetting()
					&& (candidate == null || candidate.isBlank()))
					continue;
				dropdown.setSelected(candidate);
				break;
			}
			return true;
		}
		
		if(setting instanceof ButtonSetting buttonSetting)
		{
			buttonSetting.runAction();
			return true;
		}
		
		if(owner instanceof TooManyHaxHack tooManyHax
			&& "Blocked hacks".equals(setting.getName()))
		{
			minecraft.setScreen(new TooManyHaxEditorScreen(this, tooManyHax));
			return true;
		}
		
		if(setting instanceof SettingGroup group)
		{
			toggleGroup(group);
			return true;
		}
		
		if(setting instanceof PlantTypeSetting plantType)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
				plantType.toggleReplantingEnabled();
			else
				plantType.toggleHarvestingEnabled();
			return true;
		}
		
		if(setting instanceof ToggleAllPlantTypesSetting allPlantTypes)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
				allPlantTypes.toggleReplantingEnabled();
			else
				allPlantTypes.toggleHarvestingEnabled();
			return true;
		}
		
		if(setting instanceof MobWeaponRuleSetting mobRule)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
				cycleMobRuleMob(mobRule, -1);
			else
				cycleMobRuleWeapon(mobRule, 1);
			return true;
		}
		
		if(setting instanceof ColorSetting color)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
			{
				color.setColor(color.getDefaultColor());
				if(isStyleColor(color))
					applyLiveLayout();
				return true;
			}
			
			minecraft.setScreen(new EditColorScreen(this, color));
			return true;
		}
		
		if(setting instanceof TextFieldSetting textField)
		{
			minecraft.setScreen(new EditTextFieldScreen(this, textField));
			return true;
		}
		
		if(setting instanceof BlockSetting block)
		{
			minecraft.setScreen(new EditBlockScreen(this, block));
			return true;
		}
		
		if(setting instanceof BlockListSetting blockList)
		{
			minecraft.setScreen(new EditBlockListScreen(this, blockList));
			return true;
		}
		
		if(setting instanceof ItemListSetting itemList)
		{
			minecraft.setScreen(new EditItemListScreen(this, itemList));
			return true;
		}
		
		if(setting instanceof EntityTypeListSetting entityList)
		{
			minecraft.setScreen(new EditEntityTypeListScreen(this, entityList));
			return true;
		}
		
		if(setting instanceof BookOffersSetting bookOffers)
		{
			minecraft.setScreen(new EditBookOffersScreen(this, bookOffers));
			return true;
		}
		
		if(setting instanceof FileSetting file)
		{
			minecraft.setScreen(new SelectFileScreen(this, file));
			return true;
		}
		
		if(setting instanceof WaypointsSetting waypoints)
		{
			minecraft
				.setScreen(new WaypointsScreen(this, waypoints.getManager()));
			return true;
		}
		
		return true;
	}
	
	private void cycleMobRuleWeapon(MobWeaponRuleSetting setting, int delta)
	{
		MobWeaponRuleSetting.WeaponCategory[] values =
			MobWeaponRuleSetting.WeaponCategory.values();
		int i = setting.getSelectedWeapon().ordinal() + delta;
		if(i < 0)
			i = values.length - 1;
		if(i >= values.length)
			i = 0;
		setting.setSelectedWeapon(values[i]);
	}
	
	private void cycleMobRuleMob(MobWeaponRuleSetting setting, int delta)
	{
		List<MobWeaponRuleSetting.MobOption> values = setting.getMobOptions();
		int i = values.indexOf(setting.getSelectedMob());
		if(i < 0)
			i = 0;
		i += delta;
		if(i < 0)
			i = values.size() - 1;
		if(i >= values.size())
			i = 0;
		setting.setSelectedMob(values.get(i));
	}
	
	private void setSliderValueFromMouse(SliderDrag drag, double mouseX)
	{
		double pct = (mouseX - drag.x1()) / (drag.x2() - drag.x1());
		pct = Math.max(0, Math.min(1, pct));
		SliderSetting slider = drag.slider();
		double value = slider.getMinimum()
			+ (slider.getMaximum() - slider.getMinimum()) * pct;
		slider.setValue(value);
	}
	
	private void toggleExpandedFeature(Feature feature)
	{
		String key = feature.getName();
		if(expandedFeatures.contains(key))
			expandedFeatures.remove(key);
		else
			expandedFeatures.add(key);
	}
	
	private void toggleGroup(SettingGroup group)
	{
		if(expandedGroups.contains(group))
			expandedGroups.remove(group);
		else
			expandedGroups.add(group);
	}
	
	private List<Feature> getFilteredFeatures()
	{
		String query = searchText == null ? ""
			: searchText.toLowerCase(Locale.ROOT).trim();
		boolean globalSearch = !query.isEmpty();
		ArrayList<Feature> features = new ArrayList<>();
		
		for(Hack hack : WurstClient.INSTANCE.getHax().getAllHax())
		{
			if(hack instanceof ClickGuiHack || hack instanceof NavigatorHack
				|| hack instanceof AltGuiHack)
				continue;
			if(hack == WurstClient.INSTANCE.getHax().globalToggleHack)
				continue;
			if(isHiddenByTooManyHax(hack))
				continue;
			
			if(!globalSearch)
			{
				if(styleCategorySelected)
					continue;
				if(enabledCategorySelected)
				{
					if(!hack.isEnabled())
						continue;
				}else if(!matchesCategory(hack, selectedCategory))
					continue;
			}
			
			if(globalSearch && !matchesSearch(hack, query))
				continue;
			
			features.add(hack);
		}
		
		if(!styleCategorySelected && !enabledCategorySelected)
		{
			Feature performanceOverlay =
				WurstClient.INSTANCE.getOtfs().performanceOverlayOtf;
			boolean include =
				globalSearch ? matchesSearch(performanceOverlay, query)
					: selectedCategory == Category.OTHER;
			if(include && !isHiddenByTooManyHax(performanceOverlay))
				features.add(performanceOverlay);
		}
		
		features.sort(Comparator.comparing(Feature::getName,
			String.CASE_INSENSITIVE_ORDER));
		return features;
	}
	
	private List<DisplayEntry> getDisplayedEntries()
	{
		ArrayList<DisplayEntry> entries = new ArrayList<>();
		if(!styleCategorySelected && !enabledCategorySelected)
		{
			for(Feature feature : getFilteredFeatures())
				entries.add(new DisplayEntry(feature, false));
			return entries;
		}
		
		if(enabledCategorySelected)
			return getEnabledCategoryEntries();
		
		String query = searchText == null ? ""
			: searchText.toLowerCase(Locale.ROOT).trim();
		ArrayList<Feature> features =
			new ArrayList<>(getClientSettingsFeatures());
		if(!query.isEmpty())
			features.removeIf(feature -> !matchesSearch(feature, query));
		for(Feature feature : features)
			entries.add(new DisplayEntry(feature, false));
		return entries;
	}
	
	private List<DisplayEntry> getEnabledCategoryEntries()
	{
		String query = searchText == null ? ""
			: searchText.toLowerCase(Locale.ROOT).trim();
		ArrayList<DisplayEntry> entries = new ArrayList<>();
		HashSet<String> added = new HashSet<>();
		
		for(Hack hack : WurstClient.INSTANCE.getHax().getAllHax())
		{
			if(hack instanceof ClickGuiHack || hack instanceof NavigatorHack
				|| hack instanceof AltGuiHack)
				continue;
			if(isHiddenByTooManyHax(hack))
				continue;
			
			if(!hack.isEnabled())
				continue;
			if(!query.isEmpty() && !matchesSearch(hack, query))
				continue;
			
			entries.add(new DisplayEntry(hack, false));
			added.add(hack.getName());
		}
		
		entries.sort(Comparator.comparing(entry -> entry.feature().getName(),
			String.CASE_INSENSITIVE_ORDER));
		
		ArrayList<String> staleNames = new ArrayList<>();
		for(String name : RECENTLY_DISABLED_HACKS)
		{
			Hack hack = findHackByName(name);
			if(hack == null)
			{
				staleNames.add(name);
				continue;
			}
			
			if(hack.isEnabled() || added.contains(name))
			{
				staleNames.add(name);
				continue;
			}
			if(isHiddenByTooManyHax(hack))
				continue;
			if(!query.isEmpty() && !matchesSearch(hack, query))
				continue;
			
			entries.add(new DisplayEntry(hack, true));
		}
		
		for(String staleName : staleNames)
			RECENTLY_DISABLED_HACKS.remove(staleName);
		
		return entries;
	}
	
	private List<Feature> getClientSettingsFeatures()
	{
		ArrayList<Feature> features = new ArrayList<>();
		features.add(WurstClient.INSTANCE.getOtfs().wurstLogoOtf);
		features.add(WurstClient.INSTANCE.getOtfs().hackListOtf);
		features.add(WurstClient.INSTANCE.getOtfs().keybindManagerOtf);
		features.add(WurstClient.INSTANCE.getOtfs().presetManagerOtf);
		features.add(WurstClient.INSTANCE.getOtfs().wurstOptionsOtf);
		features.add(WurstClient.INSTANCE.getHax().globalToggleHack);
		features.add(WurstClient.INSTANCE.getHax().clickGuiHack);
		features.add(WurstClient.INSTANCE.getHax().altGuiHack);
		return features;
	}
	
	private boolean matchesCategory(Hack hack, Category category)
	{
		if(category == Category.FAVORITES)
			return hack.isFavorite();
		
		Category hackCategory = hack.getCategory();
		if(hackCategory == null)
			return category == Category.OTHER;
		
		return hackCategory == category;
	}
	
	private boolean matchesSearch(Feature feature, String query)
	{
		if(feature.getName().toLowerCase(Locale.ROOT).contains(query))
			return true;
		
		if(feature.getDescription().toLowerCase(Locale.ROOT).contains(query))
			return true;
		
		return feature.getSearchTags().toLowerCase(Locale.ROOT).contains(query);
	}
	
	private List<SettingRow> getSettingRows(Feature feature)
	{
		ArrayList<SettingRow> rows = new ArrayList<>();
		for(Setting setting : feature.getSettings().values())
			appendSettingRows(rows, feature, setting, 0, false);
		return rows;
	}
	
	private void appendSettingRows(List<SettingRow> rows, Feature owner,
		Setting setting, int depth, boolean insideGroup)
	{
		if(!insideGroup && !(setting instanceof SettingGroup)
			&& !setting.isVisibleInGui())
			return;
		
		int h = setting instanceof SpacerSetting
			? Math.max(4, cfg().getRowHeight() / 2) : cfg().getRowHeight();
		rows.add(new SettingRow(owner, setting, depth, h));
		
		if(setting instanceof SettingGroup group)
		{
			if(!expandedGroups.contains(group))
				return;
			
			for(Setting child : group.getChildren())
				appendSettingRows(rows, owner, child, depth + 1, true);
		}
	}
	
	private String getSettingName(Setting setting)
	{
		if(setting instanceof SettingGroup group)
			return (expandedGroups.contains(group) ? "[-] " : "[+] ")
				+ setting.getName();
		
		if(setting instanceof SpacerSetting)
			return "";
		
		return setting.getName();
	}
	
	private String getSettingValue(Setting setting)
	{
		if(setting instanceof CheckboxSetting checkbox)
			return checkbox.isChecked() ? "ON" : "OFF";
		
		if(setting instanceof SliderSetting slider)
			return normalizeDisplayValue(slider.getValueString());
		
		if(setting instanceof EnumSetting<?> enumSetting)
			return normalizeDisplayValue("" + enumSetting.getSelected());
		
		if(setting instanceof StringDropdownSetting dropdown)
			return normalizeDisplayValue(dropdown.getSelected());
		
		if(setting instanceof TextFieldSetting textField)
			return normalizeDisplayValue(textField.getValue());
		
		if(setting instanceof ColorSetting color)
			return ColorUtils.toHex(color.getColor());
		
		if(setting instanceof ButtonSetting)
			return "Run";
		
		if(setting instanceof PlantTypeSetting plant)
			return "H:" + (plant.isHarvestingEnabled() ? "ON" : "OFF") + " R:"
				+ (plant.isReplantingEnabled() ? "ON" : "OFF");
		
		if(setting instanceof ToggleAllPlantTypesSetting all)
			return "H:" + triState(all.isHarvestingEnabled()) + " R:"
				+ triState(all.isReplantingEnabled());
		
		if(setting instanceof MobWeaponRuleSetting mobRule)
			return mobRule.getSelectedMob() + " -> "
				+ mobRule.getSelectedWeapon();
		
		if(setting instanceof SettingGroup group)
			return expandedGroups.contains(group) ? "Expanded" : "Collapsed";
		
		if(setting instanceof SpacerSetting)
			return "";
		
		return "Edit";
	}
	
	private int getSettingMarkerColor(Setting setting)
	{
		if(setting instanceof CheckboxSetting)
			return withAlpha(cfg().getEnabledColor(), 0.95F);
		if(setting instanceof SliderSetting)
			return withAlpha(cfg().getAccentColor(), 0.95F);
		if(setting instanceof ButtonSetting)
			return withAlpha(cfg().getAccentColor(), 0.9F);
		if(setting instanceof EnumSetting<?>
			|| setting instanceof StringDropdownSetting)
			return withAlpha(cfg().getAccentColor(), 0.85F);
		if(setting instanceof ColorSetting)
			return withAlpha(cfg().getTextColor(), 0.9F);
		if(setting instanceof SettingGroup)
			return withAlpha(cfg().getMutedTextColor(), 0.95F);
		return withAlpha(cfg().getMutedTextColor(), 0.75F);
	}
	
	private String getSettingTypeTag(Setting setting)
	{
		if(setting instanceof CheckboxSetting)
			return "BOOL";
		if(setting instanceof SliderSetting)
			return "SLD";
		if(setting instanceof ColorSetting)
			return "CLR";
		if(setting instanceof ButtonSetting)
			return "BTN";
		if(setting instanceof EnumSetting<?>
			|| setting instanceof StringDropdownSetting)
			return "MODE";
		if(setting instanceof SettingGroup)
			return "GRP";
		if(setting instanceof TextFieldSetting)
			return "TXT";
		return "EDIT";
	}
	
	private int getValueBadgeColor(Setting setting, String valueText)
	{
		if(setting instanceof CheckboxSetting)
		{
			boolean on = "ON".equalsIgnoreCase(valueText)
				|| "true".equalsIgnoreCase(valueText);
			return on ? withAlpha(cfg().getEnabledColor(), 0.82F)
				: withAlpha(cfg().getDisabledColor(), 0.86F);
		}
		
		if(setting instanceof SettingGroup group)
			return expandedGroups.contains(group)
				? withAlpha(cfg().getAccentColor(), 0.58F)
				: withAlpha(cfg().getDisabledColor(), 0.86F);
		
		if(setting instanceof SliderSetting)
			return withAlpha(cfg().getAccentColor(), 0.62F);
		
		if(setting instanceof ButtonSetting)
			return withAlpha(cfg().getAccentColor(), 0.56F);
		
		return withAlpha(cfg().getPanelColor(), 0.9F);
	}
	
	private String trimToWidth(Font font, String text, int maxWidth)
	{
		if(text == null)
			return "";
		int unscaledMaxWidth = Math.max(1,
			(int)(maxWidth / Math.max(0.01F, cfg().getFontScale())));
		if(textWidthBase(font, text) <= unscaledMaxWidth)
			return text;
		
		String ellipsis = "...";
		int ellipsisW = textWidthBase(font, ellipsis);
		int lo = 0;
		int hi = text.length();
		while(lo < hi)
		{
			int mid = (lo + hi + 1) >>> 1;
			int w = fontManager.getTextWidthPrefix(font, text, mid) + ellipsisW;
			if(w <= unscaledMaxWidth)
				lo = mid;
			else
				hi = mid - 1;
		}
		
		if(lo <= 0)
			return ellipsis;
		
		return text.substring(0, lo) + ellipsis;
	}
	
	private String triState(Boolean value)
	{
		if(value == null)
			return "MIX";
		return value ? "ON" : "OFF";
	}
	
	private String normalizeDisplayValue(String value)
	{
		if(value == null)
			return "";
		
		String trimmed = value.trim();
		if(trimmed.equalsIgnoreCase("off"))
			return "OFF";
		if(trimmed.equalsIgnoreCase("on"))
			return "ON";
		
		return value;
	}
	
	private boolean isStyleSlider(SliderSetting slider)
	{
		return slider == cfg().getUiOpacitySetting()
			|| slider == cfg().getTooltipOpacitySetting()
			|| slider == cfg().getMarginSetting()
			|| slider == cfg().getWidthPercentSetting()
			|| slider == cfg().getHeightPercentSetting()
			|| slider == cfg().getCategoryWidthSetting()
			|| slider == cfg().getRowHeightSetting()
			|| slider == cfg().getFontScaleSetting();
	}
	
	private Hack findHackByName(String name)
	{
		for(Hack hack : WurstClient.INSTANCE.getHax().getAllHax())
			if(hack.getName().equals(name))
				return hack;
			
		return null;
	}
	
	private void addRecentlyDisabled(String hackName)
	{
		RECENTLY_DISABLED_HACKS.remove(hackName);
		RECENTLY_DISABLED_HACKS.add(0, hackName);
		if(RECENTLY_DISABLED_HACKS.size() > MAX_RECENTLY_DISABLED)
			RECENTLY_DISABLED_HACKS.remove(MAX_RECENTLY_DISABLED);
	}
	
	private void removeRecentlyDisabled(String hackName)
	{
		RECENTLY_DISABLED_HACKS.remove(hackName);
	}
	
	private boolean isStyleColor(ColorSetting color)
	{
		return color == cfg().getBgColorSetting()
			|| color == cfg().getPanelColorSetting()
			|| color == cfg().getPanelLightColorSetting()
			|| color == cfg().getTextColorSetting()
			|| color == cfg().getMutedTextColorSetting()
			|| color == cfg().getAccentColorSetting()
			|| color == cfg().getEnabledColorSetting()
			|| color == cfg().getDisabledColorSetting();
	}
	
	private boolean featureHasStatePill(Feature feature)
	{
		if(isMenuOnlyFeature(feature))
			return false;
		
		if(feature instanceof ClickGuiHack || feature instanceof AltGuiHack)
			return false;
		
		String action = feature.getPrimaryAction();
		if(action == null || action.isBlank())
			return false;
		
		String a = action.toLowerCase(Locale.ROOT);
		if(a.contains("open") || a.contains("options") || a.contains("preset")
			|| a.contains("keybind"))
			return false;
		
		return a.contains("enable") || a.contains("disable")
			|| a.contains("show") || a.contains("hide") || a.contains("allow")
			|| a.contains("block") || feature.isEnabled();
	}
	
	private boolean isMenuOnlyFeature(Feature feature)
	{
		String name = feature.getName();
		return name != null && (name.equalsIgnoreCase("GlobalSettings")
			|| name.equalsIgnoreCase("GlobalToggle")
			|| name.equalsIgnoreCase("HackList")
			|| name.equalsIgnoreCase("WurstOptions")
			|| name.equalsIgnoreCase("WurstLogo"));
	}
	
	private boolean isHiddenByTooManyHax(Feature feature)
	{
		TooManyHaxHack tooManyHax =
			WurstClient.INSTANCE.getHax().tooManyHaxHack;
		return tooManyHax.isEnabled() && tooManyHax.isBlocked(feature);
	}
	
	private void clampScroll()
	{
		if(moduleScroll < 0)
			moduleScroll = 0;
		if(moduleScroll > maxModuleScroll)
			moduleScroll = maxModuleScroll;
	}
	
	private boolean isInside(double mouseX, double mouseY, int x1, int y1,
		int x2, int y2)
	{
		return mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2;
	}
	
	private static int withAlpha(int color, float alpha)
	{
		int a = Math.max(0, Math.min(255, (int)(alpha * 255)));
		return (color & 0x00FFFFFF) | (a << 24);
	}
	
	private void drawRectBorder(GuiGraphics context, int x1, int y1, int x2,
		int y2, int color)
	{
		context.fill(x1, y1, x2, y1 + 1, color);
		context.fill(x1, y2 - 1, x2, y2, color);
		context.fill(x1, y1, x1 + 1, y2, color);
		context.fill(x2 - 1, y1, x2, y2, color);
	}
	
	private int scaledFontWidth(Font font, String text)
	{
		return (int)(textWidthBase(font, text) * cfg().getFontScale());
	}
	
	private int scaledFontHeight(Font font)
	{
		return (int)(lineHeightBase(font) * cfg().getFontScale());
	}
	
	private int centeredTextY(Font font, int top, int bottom)
	{
		int h = Math.max(1, scaledFontHeight(font));
		return top + Math.max(0, ((bottom - top) - h) / 2);
	}
	
	private int getPillPadding(Font font, int rowHeight)
	{
		int textH = Math.max(1, scaledFontHeight(font));
		int maxPad = Math.max(1, (rowHeight - textH - 2) / 2);
		return Math.min(4, maxPad);
	}
	
	private void drawStringScaled(GuiGraphics context, Font font, String text,
		int x, int y, int color, boolean shadow)
	{
		float scale = Math.max(0.01F, cfg().getFontScale());
		fontManager.drawString(context, font, text, x, y, color, shadow, scale);
	}
	
	private int textWidthBase(Font font, String text)
	{
		return fontManager.getTextWidth(font, text);
	}
	
	private int lineHeightBase(Font font)
	{
		return fontManager.getLineHeight(font);
	}
	
	private void syncFontManager()
	{
		int smoothing = cfg().getFontSmoothingFactor();
		if(appliedFontSmoothing != smoothing)
		{
			fontManager.setSmoothingFactor(smoothing);
			appliedFontSmoothing = smoothing;
			appliedFontFamily = "";
		}
		
		String family = cfg().getSelectedFontFamily();
		if(!family.equals(appliedFontFamily))
		{
			fontManager.setActiveFont(family);
			appliedFontFamily = family;
		}
	}
	
	private void drawCenteredStringScaled(GuiGraphics context, Font font,
		String text, int centerX, int y, int color)
	{
		int x = centerX - scaledFontWidth(font, text) / 2;
		drawStringScaled(context, font, text, x, y, color, false);
	}
	
	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY,
		float deltaTicks)
	{
		// custom background above
	}
	
	@Override
	public void removed()
	{
		LAST_SELECTED_CATEGORY = selectedCategory;
		LAST_ENABLED_CATEGORY_SELECTED = enabledCategorySelected;
		LAST_STYLE_CATEGORY_SELECTED = styleCategorySelected;
		LAST_MODULE_SCROLL = Math.max(0, moduleScroll);
		LAST_EXPANDED_FEATURES.clear();
		LAST_EXPANDED_FEATURES.addAll(expandedFeatures);
		super.removed();
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	private record DisplayEntry(Feature feature, boolean recentDisabled)
	{}
	
	private record SettingRow(Feature owner, Setting setting, int depth,
		int height)
	{}
	
	private record SliderDrag(SliderSetting slider, int x1, int x2)
	{}
}
