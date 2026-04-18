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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
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
import net.wurstclient.hacks.XpGuiHack;
import net.wurstclient.keybinds.Keybind;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.other_feature.OtherFeature;
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
	private static final int MAX_RECENTLY_DISABLED = 64;
	private static final ArrayList<String> RECENTLY_DISABLED_HACKS =
		new ArrayList<>();
	private static String LAST_SELECTED_CATEGORY = Category.FAVORITES.getName();
	private static boolean LAST_ENABLED_CATEGORY_SELECTED;
	private static boolean LAST_STYLE_CATEGORY_SELECTED;
	private static int LAST_MODULE_SCROLL;
	private static final HashSet<String> LAST_EXPANDED_FEATURES =
		new HashSet<>();
	private static final Set<String> HIDDEN_OTHER_FEATURES =
		Set.of("CleanUp", "LastServer", "Reconnect", "ServerFinder",
			"WikiDataExport", "WurstCapes");
	private static final Set<String> MOVE_TO_CLIENT_SETTINGS =
		Set.of("DisableWurst", "CommandPrefix", "Changelog",
			"ConnectionLogOverlay", "NoTelemetry", "NoChatReports",
			"ForceAllowChats", "VanillaSpoof", "Translations");
	private static final Set<String> KEYBINDS_HIDDEN_FOR =
		Set.of("WurstOptions", "Translations", "WurstLogo", "CommandPrefix",
			"ConnectionLogOverlay", "DisableWurst", "ForceAllowChats",
			"HideModMenu", "NoTelemetry");
	
	private final Screen prevScreen;
	
	private EditBox searchBox;
	private String searchText = "";
	private String selectedCategory = Category.FAVORITES.getName();
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
	private int categoryScroll;
	private int maxCategoryScroll;
	
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
		int searchHeight = getSearchHeight();
		searchBox = new EditBox(font, getSearchBoxX(), getSearchBoxY(),
			getSearchBoxWidth(), searchHeight, Component.literal("Search"));
		searchBox.setBordered(false);
		searchBox.setMaxLength(128);
		searchBox.setTextColor(cfg().getTextColor());
		searchBox.setValue(searchText);
		addRenderableWidget(searchBox);
		if(cfg().isSearchOnlyWhileTypingEnabled())
		{
			setFocused(null);
			searchBox.setFocused(false);
			searchBox.setVisible(false);
		}else
		{
			setFocused(searchBox);
			searchBox.setFocused(true);
			searchBox.setVisible(true);
		}
	}
	
	private void applyOpenBehavior()
	{
		switch(cfg().getOpenBehavior())
		{
			case ENABLED ->
			{
				selectedCategory = Category.FAVORITES.getName();
				enabledCategorySelected = true;
				styleCategorySelected = false;
				moduleScroll = 0;
				categoryScroll = 0;
				pendingScrollRestore = -1;
				expandedFeatures.clear();
				if(cfg().isKeepHackSettingsOpenEnabled())
					expandedFeatures.addAll(LAST_EXPANDED_FEATURES);
			}
			case LAST_POSITION ->
			{
				selectedCategory = LAST_SELECTED_CATEGORY;
				enabledCategorySelected = LAST_ENABLED_CATEGORY_SELECTED;
				styleCategorySelected = LAST_STYLE_CATEGORY_SELECTED;
				moduleScroll = 0;
				categoryScroll = 0;
				pendingScrollRestore = Math.max(0, LAST_MODULE_SCROLL);
				expandedFeatures.clear();
				if(cfg().isKeepHackSettingsOpenEnabled())
					expandedFeatures.addAll(LAST_EXPANDED_FEATURES);
			}
			case FAVORITES ->
			{
				selectedCategory = Category.FAVORITES.getName();
				enabledCategorySelected = false;
				styleCategorySelected = false;
				moduleScroll = 0;
				categoryScroll = 0;
				pendingScrollRestore = -1;
				expandedFeatures.clear();
				if(cfg().isKeepHackSettingsOpenEnabled())
					expandedFeatures.addAll(LAST_EXPANDED_FEATURES);
			}
		}
	}
	
	private AltGuiHack cfg()
	{
		return WurstClient.INSTANCE.getHax().altGuiHack;
	}
	
	private int getCategoryRowHeight()
	{
		return cfg().getCategoryHeight();
	}
	
	private int getCategoryRowGap()
	{
		return Math.max(2, Math.round(getCategoryRowHeight() * 0.25F));
	}
	
	private boolean isTopTabsLayout()
	{
		return cfg().getCategoryLayout() == AltGuiHack.CategoryLayout.TOP_TABS;
	}
	
	private int getCategoryAreaX1()
	{
		if(isTopTabsLayout())
			return panelX + 8;
		
		return panelX + 6;
	}
	
	private int getCategoryAreaX2()
	{
		if(isTopTabsLayout())
			return panelX + panelW - 8;
		
		return panelX + categoryW - 8;
	}
	
	private int getCategoryAreaY1()
	{
		if(isTopTabsLayout())
			return panelY + 34;
		
		return panelY + getSearchHeight() + 30;
	}
	
	private int getCategoryAreaY2()
	{
		if(isTopTabsLayout())
			return getCategoryAreaY1() + getCategoryRowHeight();
		
		return panelY + panelH - 20;
	}
	
	private int getTopTabGap()
	{
		return Math.max(2, Math.round(getCategoryRowGap() * 0.75F));
	}
	
	private int getSearchBoxX()
	{
		if(isTopTabsLayout())
			return getCategoryAreaX2() - getSearchBoxWidth() - 4;
		
		return moduleX + 14;
	}
	
	private int getSearchBoxY()
	{
		if(isTopTabsLayout())
			return panelY + 10;
		
		return panelY + 12;
	}
	
	private int getSearchBoxWidth()
	{
		if(isTopTabsLayout())
		{
			int desired = Math.max(160, Math.min(320, panelW / 3));
			int maxW = Math.max(120, getCategoryAreaX2() - (panelX + 210));
			return Math.min(desired, maxW);
		}
		
		return moduleW - 24;
	}
	
	private boolean shouldRenderSearchBox()
	{
		if(searchBox == null)
			return false;
		
		if(!cfg().isSearchOnlyWhileTypingEnabled())
			return true;
		
		String value = searchBox.getValue();
		return searchBox.isFocused() || (value != null && !value.isBlank());
	}
	
	private int getSearchHeight()
	{
		return 12;
	}
	
	private float getRightSettingsWidthScale()
	{
		return Math.max(0.05F, cfg().getSettingsWidth());
	}
	
	private float getRightSettingsHeightScale()
	{
		return Math.max(0.05F, cfg().getSettingsHeight());
	}
	
	private int scaleRightSettingWidth(int value)
	{
		return Math.max(1, Math.round(value * getRightSettingsWidthScale()));
	}
	
	private int scaleRightSettingHeight(int value)
	{
		return Math.max(1, Math.round(value * getRightSettingsHeightScale()));
	}
	
	private int getMinimumReadableUiRowHeight()
	{
		Font font = minecraft != null ? minecraft.font : null;
		int textH = font == null ? 9 : Math.max(1, scaledFontHeight(font));
		return Math.max(10, textH + 2);
	}
	
	private int getSettingsValueColumnWidth(int rowX1, int rowX2)
	{
		int min = scaleRightSettingWidth(120);
		int max = scaleRightSettingWidth(180);
		return Math.min(max, Math.max(min, (rowX2 - rowX1) / 4));
	}
	
	private void rebuildLayout()
	{
		int targetW = (int)(width * cfg().getWidthPercent());
		int targetH = (int)(height * cfg().getHeightPercent());
		int searchHeight = getSearchHeight();
		int edgePadding = 8;
		panelW = Math.max(320, Math.min(width - edgePadding * 2, targetW));
		panelH = Math.max(220, Math.min(height - edgePadding * 2, targetH));
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		categoryW = Math.min(cfg().getCategoryWidth(), panelW - 150);
		if(isTopTabsLayout())
		{
			moduleX = panelX + 6;
			moduleW = panelW - 12;
			moduleY = getCategoryAreaY2() + getCategoryRowGap() + 8;
			int moduleBottomY = panelY + panelH - 6;
			moduleH = Math.max(40, moduleBottomY - moduleY);
		}else
		{
			moduleX = panelX + categoryW + 8;
			moduleY = panelY + searchHeight + 22;
			moduleW = panelW - categoryW - 16;
			moduleH = panelH - searchHeight - 28;
		}
		
		uiMenuX = moduleX + 8;
		uiMenuY = moduleY;
		uiMenuW = moduleW - 16;
		uiMenuH = moduleH;
	}
	
	@Override
	public void tick()
	{
		if(searchBox != null)
		{
			searchText = searchBox.getValue();
			searchBox.setVisible(shouldRenderSearchBox());
		}
	}
	
	private void applyLiveLayout()
	{
		cfg().enforceNoClipLayout();
		rebuildLayout();
		if(searchBox != null)
		{
			searchBox.setX(getSearchBoxX());
			searchBox.setY(getSearchBoxY());
			searchBox.setHeight(getSearchHeight());
			searchBox.setWidth(getSearchBoxWidth());
			searchBox.setVisible(shouldRenderSearchBox());
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
	public boolean charTyped(CharacterEvent event)
	{
		if(searchBox != null && cfg().isSearchOnlyWhileTypingEnabled()
			&& !searchBox.isFocused())
		{
			setFocused(searchBox);
			searchBox.setFocused(true);
			searchBox.setVisible(true);
		}
		
		if(searchBox != null && searchBox.charTyped(event))
			return true;
		
		return super.charTyped(event);
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
		if(mouseX >= moduleX && mouseX <= moduleX + moduleW && mouseY >= moduleY
			&& mouseY <= moduleY + moduleH)
		{
			moduleScroll -= (int)(verticalAmount * cfg().getRowHeight());
			clampScroll();
			return true;
		}
		
		if(isInsideCategoryArea(mouseX, mouseY))
		{
			if(!isTopTabsLayout())
			{
				categoryScroll -=
					(int)(verticalAmount * getCategoryRowHeight());
				clampScroll();
				return true;
			}
		}
		
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount,
			verticalAmount);
	}
	
	@Override
	public void resize(int width, int height)
	{
		super.resize(width, height);
		applyLiveLayout();
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		hoverTooltip = "";
		syncFontManager();
		int bg =
			withAlpha(cfg().getBackgroundColor(), cfg().getBackgroundOpacity());
		int panel = withAlpha(cfg().getPanelColor(), cfg().getUiOpacity());
		
		context.fill(0, 0, width, height, bg);
		context.fill(panelX, panelY, panelX + panelW, panelY + panelH, panel);
		if(!isTopTabsLayout())
			context.fill(panelX + categoryW, panelY, panelX + categoryW + 1,
				panelY + panelH, withAlpha(cfg().getPanelLightColor(), 0.8F));
		
		renderHeader(context);
		renderCategories(context, mouseX, mouseY);
		renderModules(context, mouseX, mouseY);
		
		if(shouldRenderSearchBox())
		{
			renderSearchBoxText(context);
		}
		
		renderTooltip(context);
	}
	
	private void renderSearchBoxText(GuiGraphicsExtractor context)
	{
		if(searchBox == null)
			return;
		
		Font font = minecraft.font;
		int x1 = searchBox.getX();
		int y1 = searchBox.getY();
		int x2 = x1 + searchBox.getWidth();
		int y2 = y1 + searchBox.getHeight();
		int padX =
			Math.max(6, Math.round(4F * Math.max(0.25F, cfg().getFontScale())));
		int textY = centeredTextY(font, y1, y2);
		int maxTextW = Math.max(1, (x2 - x1) - padX * 2);
		
		String value = searchBox.getValue();
		boolean focused = searchBox.isFocused();
		boolean empty = value == null || value.isBlank();
		String baseText =
			empty && !focused ? "search" : (value == null ? "" : value);
		String drawText = trimToWidth(font, baseText, maxTextW);
		int textColor = empty && !focused ? cfg().getMutedTextColor()
			: cfg().getTextColor();
		
		drawStringScaled(context, font, drawText, x1 + padX, textY, textColor,
			false);
		
		if(focused && (minecraft.gui.getGuiTicks() / 6) % 2 == 0)
		{
			int caretX = x1 + padX + scaledFontWidth(font, drawText);
			int caretTop = textY;
			int caretBottom = textY + Math.max(1, scaledFontHeight(font));
			context.fill(caretX, caretTop, caretX + 1, caretBottom,
				withAlpha(cfg().getTextColor(), cfg().getUiOpacity()));
		}
	}
	
	private void renderHeader(GuiGraphicsExtractor context)
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
		
		if(shouldRenderSearchBox())
		{
			int sx1 = getSearchBoxX() - 4;
			int sx2 = getSearchBoxX() + getSearchBoxWidth() + 4;
			if(isTopTabsLayout())
			{
				sx1 = Math.max(getCategoryAreaX1(), sx1);
				sx2 = Math.min(getCategoryAreaX2(), sx2);
			}
			int sy1 = getSearchBoxY() - 2;
			int sy2 = getSearchBoxY() + getSearchHeight() + 2;
			context.fill(sx1, sy1, sx2, sy2, panelLight);
		}
	}
	
	private void renderTooltip(GuiGraphicsExtractor context)
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
	
	private void renderCategories(GuiGraphicsExtractor context, int mouseX,
		int mouseY)
	{
		if(isTopTabsLayout())
		{
			renderTopTabCategories(context, mouseX, mouseY);
			return;
		}
		
		renderSidebarCategories(context, mouseX, mouseY);
	}
	
	private void renderSidebarCategories(GuiGraphicsExtractor context,
		int mouseX, int mouseY)
	{
		Font font = minecraft.font;
		int rowH = getCategoryRowHeight();
		int rowGap = getCategoryRowGap();
		int areaX1 = getCategoryAreaX1();
		int areaX2 = getCategoryAreaX2();
		int areaY1 = getCategoryAreaY1();
		int areaY2 = getCategoryAreaY2();
		int y = areaY1 - categoryScroll;
		int accentFill = withAlpha(cfg().getAccentColor(), 0.35F);
		int accentHover = withAlpha(cfg().getAccentColor(), 0.2F);
		List<String> categories = getDisplayCategories();
		
		int rows = 2 + categories.size();
		int totalHeight = rows * rowH + (rows - 1) * rowGap;
		int viewportHeight = Math.max(0, areaY2 - areaY1);
		maxCategoryScroll = Math.max(0, totalHeight - viewportHeight);
		clampScroll();
		y = areaY1 - categoryScroll;
		
		context.enableScissor(areaX1, areaY1, areaX2, areaY2);
		
		int enabledRowH = rowH;
		boolean enabledHovered =
			isInside(mouseX, mouseY, areaX1, y, areaX2, y + enabledRowH);
		if(enabledCategorySelected || enabledHovered)
			context.fill(areaX1, y, areaX2, y + enabledRowH,
				enabledCategorySelected ? accentFill : accentHover);
		
		int enabledTextY = centeredTextY(font, y, y + enabledRowH) + 1;
		drawStringScaled(context, font, "Enabled", panelX + 12, enabledTextY,
			enabledCategorySelected ? cfg().getTextColor()
				: cfg().getMutedTextColor(),
			false);
		y += enabledRowH + rowGap;
		
		for(String categoryName : categories)
		{
			boolean hovered =
				isInside(mouseX, mouseY, areaX1, y, areaX2, y + rowH);
			boolean selected =
				!styleCategorySelected && !enabledCategorySelected
					&& selectedCategory.equalsIgnoreCase(categoryName);
			
			if(selected || hovered)
				context.fill(areaX1, y, areaX2, y + rowH,
					selected ? accentFill : accentHover);
			
			int catTextY = centeredTextY(font, y, y + rowH) + 1;
			drawStringScaled(context, font, categoryName, panelX + 12, catTextY,
				selected ? cfg().getTextColor() : cfg().getMutedTextColor(),
				false);
			y += rowH + rowGap;
		}
		
		int styleRowH = rowH;
		boolean styleHovered =
			isInside(mouseX, mouseY, areaX1, y, areaX2, y + styleRowH);
		if(styleCategorySelected || styleHovered)
			context.fill(areaX1, y, areaX2, y + styleRowH,
				styleCategorySelected ? accentFill : accentHover);
		
		int styleTextY = centeredTextY(font, y, y + styleRowH) + 1;
		drawStringScaled(context, font, "Client Settings", panelX + 12,
			styleTextY, styleCategorySelected ? cfg().getTextColor()
				: cfg().getMutedTextColor(),
			false);
		context.disableScissor();
		
		if(maxCategoryScroll > 0)
			renderCategoryScrollbar(context, areaX1, areaY1, areaX2, areaY2);
	}
	
	private void renderTopTabCategories(GuiGraphicsExtractor context,
		int mouseX, int mouseY)
	{
		Font font = minecraft.font;
		int areaY1 = getCategoryAreaY1();
		int areaY2 = getCategoryAreaY2();
		int accentFill = withAlpha(cfg().getAccentColor(), 0.35F);
		int accentHover = withAlpha(cfg().getAccentColor(), 0.2F);
		int areaX1 = getCategoryAreaX1();
		int areaX2 = getCategoryAreaX2();
		List<TopCategoryTab> tabs = getTopCategoryTabs(font);
		
		maxCategoryScroll = 0;
		categoryScroll = 0;
		
		context.fill(areaX1, areaY1, areaX2, areaY2,
			withAlpha(cfg().getPanelLightColor(), 0.58F));
		context.fill(areaX1, areaY2, areaX2, areaY2 + 1,
			withAlpha(cfg().getPanelLightColor(), 0.85F));
		
		for(TopCategoryTab tab : tabs)
		{
			boolean selected = switch(tab.kind())
			{
				case ENABLED -> enabledCategorySelected;
				case STYLE -> styleCategorySelected;
				case CATEGORY -> !styleCategorySelected
					&& !enabledCategorySelected
					&& selectedCategory.equalsIgnoreCase(tab.categoryName());
			};
			drawTopCategoryTab(context, font, tab.label(), selected,
				isInside(mouseX, mouseY, tab.x1(), areaY1, tab.x2(), areaY2),
				tab.x1(), areaY1, tab.x2(), areaY2, accentFill, accentHover);
		}
	}
	
	private List<TopCategoryTab> getTopCategoryTabs(Font font)
	{
		int areaX1 = getCategoryAreaX1();
		int areaX2 = getCategoryAreaX2();
		int areaW = Math.max(1, areaX2 - areaX1);
		int gap = getTopTabGap();
		
		ArrayList<String> labels = new ArrayList<>();
		labels.add("Enabled");
		List<String> categories = getDisplayCategories();
		labels.addAll(categories);
		labels.add("Client Settings");
		int tabCount = labels.size();
		if(tabCount == 0)
			return List.of();
		
		int[] widths = new int[tabCount];
		int availableW = Math.max(1, areaW - Math.max(0, tabCount - 1) * gap);
		
		if(cfg().isAutoSizeTopTabsEnabled())
		{
			int minW = 24;
			int sum = 0;
			for(int i = 0; i < tabCount; i++)
			{
				widths[i] =
					Math.max(minW, scaledFontWidth(font, labels.get(i)) + 14);
				sum += widths[i];
			}
			
			if(sum > availableW)
			{
				double scale = availableW / (double)sum;
				sum = 0;
				for(int i = 0; i < tabCount; i++)
				{
					widths[i] =
						Math.max(minW, (int)Math.floor(widths[i] * scale));
					sum += widths[i];
				}
				
				for(int i = 0; sum > availableW && i < tabCount * 6; i++)
				{
					int idx = i % tabCount;
					if(widths[idx] > minW)
					{
						widths[idx]--;
						sum--;
					}
				}
			}
		}else
		{
			int tabW = Math.max(1, availableW / tabCount);
			for(int i = 0; i < tabCount; i++)
				widths[i] = tabW;
			int remainder = Math.max(0, availableW - tabW * tabCount);
			for(int i = 0; i < remainder; i++)
				widths[i % tabCount]++;
		}
		
		int usedW = Math.max(0, (tabCount - 1) * gap);
		for(int w : widths)
			usedW += w;
		int x = areaX1 + Math.max(0, (areaW - usedW) / 2);
		
		ArrayList<TopCategoryTab> tabs = new ArrayList<>(tabCount);
		int labelIndex = 0;
		int x1 = x;
		int x2 = Math.min(areaX2, x1 + widths[labelIndex]);
		tabs.add(new TopCategoryTab(TopTabKind.ENABLED, labels.get(labelIndex),
			null, x1, x2));
		x = x2 + gap;
		labelIndex++;
		
		for(String categoryName : categories)
		{
			x1 = x;
			x2 = Math.min(areaX2, x1 + widths[labelIndex]);
			tabs.add(new TopCategoryTab(TopTabKind.CATEGORY,
				labels.get(labelIndex), categoryName, x1, x2));
			x = x2 + gap;
			labelIndex++;
		}
		
		x1 = x;
		x2 = Math.min(areaX2, x1 + widths[labelIndex]);
		tabs.add(new TopCategoryTab(TopTabKind.STYLE, labels.get(labelIndex),
			null, x1, x2));
		return tabs;
	}
	
	private void drawTopCategoryTab(GuiGraphicsExtractor context, Font font,
		String label, boolean selected, boolean hovered, int x1, int y1, int x2,
		int y2, int accentFill, int accentHover)
	{
		if(x2 <= x1)
			return;
		
		if(selected || hovered)
			context.fill(x1, y1, x2, y2, selected ? accentFill : accentHover);
		
		String text = trimToWidth(font, label, Math.max(1, x2 - x1 - 6));
		drawCenteredStringScaledInBox(context, font, text, x1, y1, x2, y2,
			selected ? cfg().getTextColor() : cfg().getMutedTextColor());
		if(selected)
			context.fill(x1, y2 - 1, x2, y2,
				withAlpha(cfg().getAccentColor(), 0.95F));
	}
	
	private void renderCategoryScrollbar(GuiGraphicsExtractor context,
		int areaX1, int areaY1, int areaX2, int areaY2)
	{
		int trackX2 = areaX2 - 1;
		int trackX1 = trackX2 - 2;
		int trackY1 = areaY1;
		int trackY2 = areaY2;
		
		int trackColor = withAlpha(cfg().getPanelLightColor(), 0.7F);
		context.fill(trackX1, trackY1, trackX2, trackY2, trackColor);
		
		double trackH = Math.max(1, trackY2 - trackY1);
		double knobRatio = trackH / (trackH + Math.max(1, maxCategoryScroll));
		int knobH = Math.max(10, (int)Math.round(trackH * knobRatio));
		int maxKnobY = (int)(trackH - knobH);
		double scrollRatio = maxCategoryScroll == 0 ? 0
			: categoryScroll / (double)maxCategoryScroll;
		int knobOffset = (int)Math.round(maxKnobY * scrollRatio);
		
		int knobY1 = trackY1 + knobOffset;
		int knobY2 = Math.min(trackY2, knobY1 + knobH);
		int knobColor = withAlpha(cfg().getAccentColor(), 0.95F);
		context.fill(trackX1, knobY1, trackX2, knobY2, knobColor);
	}
	
	private void renderModules(GuiGraphicsExtractor context, int mouseX,
		int mouseY)
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
		int entryIndex = 0;
		
		for(DisplayEntry entry : entries)
		{
			Feature feature = entry.feature();
			boolean expanded = expandedFeatures.contains(feature.getName());
			List<SettingRow> settingRows = null;
			boolean hasExpandableRows = false;
			if(expanded || cfg().isHackExpandIconsEnabled())
			{
				settingRows = getSettingRows(feature);
				hasExpandableRows = !settingRows.isEmpty();
			}
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
				if(!feature.isEnabled() && !hovered && !expanded)
					bgColor = withAlpha(cfg().getPanelLightColor(), 0.55F);
				if(expanded && !hovered)
					bgColor = withAlpha(cfg().getAccentColor(),
						feature.isEnabled() ? 0.33F : 0.24F);
				context.fill(moduleX + 8, rowTop, contentBottomX, rowBottom,
					bgColor);
				if(expanded)
				{
					context.fill(moduleX + 8, rowTop, moduleX + 10, rowBottom,
						withAlpha(cfg().getAccentColor(), 0.96F));
					context.fill(moduleX + 8, rowTop, contentBottomX,
						rowTop + 1, withAlpha(cfg().getAccentColor(), 0.62F));
					context.fill(moduleX + 8, rowBottom - 1, contentBottomX,
						rowBottom, withAlpha(cfg().getAccentColor(), 0.45F));
				}
				int nameX = moduleX + 14;
				int rowTextY = centeredTextY(font, rowTop, rowBottom);
				if(cfg().isHackExpandIconsEnabled() && hasExpandableRows)
				{
					String icon = expanded ? "▼" : "▶";
					drawStringScaled(context, font, icon, nameX, rowTextY,
						cfg().getMutedTextColor(), false);
					nameX += Math.max(11, scaledFontWidth(font, icon) + 4);
				}
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
					drawCenteredStringScaledInBox(context, font, stateLabel,
						toggleX1, pillY1, toggleX2, pillY2,
						cfg().getTextColor());
				}
			}
			
			contentY += rowH;
			totalContentHeight += rowH;
			
			if(!expanded)
				continue;
			
			if(settingRows == null)
				settingRows = getSettingRows(feature);
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
			
			entryIndex++;
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
	
	private void renderScrollbar(GuiGraphicsExtractor context)
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
	
	private void renderSettingRow(GuiGraphicsExtractor context, Font font,
		int mouseX, int mouseY, SettingRow row, int y1, int y2)
	{
		if(row.isKeybindRow())
		{
			int rowX1 = moduleX + 20 + row.depth() * scaleRightSettingWidth(10);
			int rowX2 = moduleX + moduleW - 12;
			boolean hovered = isInside(mouseX, mouseY, rowX1, y1, rowX2, y2);
			
			context.fill(rowX1, y1, rowX2, y2,
				hovered ? withAlpha(cfg().getAccentColor(), 0.28F)
					: withAlpha(cfg().getPanelLightColor(), 0.72F));
			context.fill(rowX1, y1, rowX1 + scaleRightSettingWidth(3), y2,
				withAlpha(cfg().getAccentColor(), 0.95F));
			
			String label = "Keybinds";
			int count = getExistingKeybindsForFeature(row.owner(),
				getPossibleKeybindsForFeature(row.owner())).size();
			String value = count == 0 ? "Open" : count + " bound";
			
			int valueW = getSettingsValueColumnWidth(rowX1, rowX2);
			int valueX2 = rowX2 - scaleRightSettingWidth(6);
			int valueX1 = valueX2 - valueW;
			int valuePad = getPillPadding(font, y2 - y1);
			int valueY1 = y1 + valuePad;
			int valueY2 = y2 - valuePad;
			
			drawStringScaled(context, font, label,
				rowX1 + scaleRightSettingWidth(10), centeredTextY(font, y1, y2),
				cfg().getTextColor(), false);
			
			context.fill(valueX1, valueY1, valueX2, valueY2,
				withAlpha(cfg().getPanelColor(), 0.92F));
			drawMarqueeStringScaledInBox(context, font, value, valueX1, valueY1,
				valueX2, valueY2, cfg().getTextColor(),
				scaleRightSettingWidth(6));
			return;
		}
		
		if(row.setting() instanceof SpacerSetting)
			return;
		
		int depth = row.depth();
		int baseX1 = moduleX + 20;
		int rowX1 = baseX1 + depth * scaleRightSettingWidth(10);
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
		if(cfg().isSettingRowDividersEnabled())
		{
			context.fill(rowX1, y1, rowX2, y1 + 1,
				withAlpha(cfg().getTextColor(), 0.12F));
			context.fill(rowX1, y2 - 1, rowX2, y2,
				withAlpha(cfg().getTextColor(), 0.06F));
		}
		
		for(int i = 0; i < depth; i++)
		{
			int guideX = baseX1 + i * scaleRightSettingWidth(10)
				+ scaleRightSettingWidth(4);
			context.fill(guideX, y1, guideX + 1, y2,
				withAlpha(cfg().getMutedTextColor(), 0.28F));
		}
		
		int markerColor = getSettingMarkerColor(row.setting());
		context.fill(rowX1, y1, rowX1 + scaleRightSettingWidth(3), y2,
			markerColor);
		
		int tagX2 = rowX1 + scaleRightSettingWidth(6);
		if(cfg().isTypeBadgesEnabled())
		{
			String typeTag = getSettingTypeTag(row.setting());
			int tagX1 = rowX1 + scaleRightSettingWidth(6);
			int tagW = Math.max(scaleRightSettingWidth(22),
				scaledFontWidth(font, typeTag) + scaleRightSettingWidth(8));
			tagX2 = tagX1 + tagW;
			context.fill(tagX1, y1 + scaleRightSettingHeight(2), tagX2,
				y2 - scaleRightSettingHeight(2),
				withAlpha(cfg().getPanelColor(), 0.92F));
			int tagTextY = centeredTextY(font, y1 + scaleRightSettingHeight(2),
				y2 - scaleRightSettingHeight(2));
			drawCenteredStringScaled(context, font, typeTag,
				(tagX1 + tagX2) / 2, tagTextY,
				withAlpha(cfg().getMutedTextColor(), 0.95F));
		}
		
		String nameText = getSettingName(row.setting());
		String valueText = getSettingValue(row.setting());
		
		int valuePad = scaleRightSettingWidth(6);
		int fixedValueColW = getSettingsValueColumnWidth(rowX1, rowX2);
		int valueBoxW = valueText.isEmpty() ? 0 : fixedValueColW;
		int valueX2 = rowX2 - scaleRightSettingWidth(6);
		int valueX1 = valueX2 - valueBoxW;
		int nameMaxW =
			valueText.isEmpty() ? rowX2 - tagX2 - scaleRightSettingWidth(12)
				: valueX1 - tagX2 - scaleRightSettingWidth(10);
		
		nameText = trimToWidth(font, nameText, Math.max(30, nameMaxW));
		int settingTextY = centeredTextY(font, y1, y2);
		drawStringScaled(context, font, nameText,
			tagX2 + scaleRightSettingWidth(6), settingTextY,
			cfg().getTextColor(), false);
		
		if(!valueText.isEmpty())
		{
			int valuePadY = getPillPadding(font, y2 - y1);
			int valueY1 = y1 + valuePadY;
			int valueY2 = y2 - valuePadY;
			if(valueY2 <= valueY1)
			{
				valueY1 = y1 + 1;
				valueY2 = y2 - 1;
			}
			int valueTextColor = cfg().getTextColor();
			if(row.setting() instanceof ColorSetting color
				&& cfg().isFillColorValuesEnabled())
			{
				context.fill(valueX1, valueY1, valueX2, valueY2,
					color.getColorI());
				valueTextColor = getReadableTextColor(color.getColorI());
			}else if(row.setting() instanceof SliderSetting slider)
			{
				context.fill(valueX1, valueY1, valueX2, valueY2,
					withAlpha(cfg().getPanelLightColor(), 0.9F));
				int innerX1 = valueX1 + scaleRightSettingWidth(2);
				int innerX2 = valueX2 - scaleRightSettingWidth(2);
				int innerY1 = valueY1 + scaleRightSettingHeight(1);
				int innerY2 = valueY2 - scaleRightSettingHeight(1);
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
			drawMarqueeStringScaledInBox(context, font, valueText, valueX1,
				valueY1, valueX2, valueY2, valueTextColor, valuePad);
			
			if(row.setting() instanceof ColorSetting color
				&& !cfg().isFillColorValuesEnabled())
			{
				drawRectBorder(context, valueX1, valueY1, valueX2, valueY2,
					color.getColorI());
			}
		}
	}
	
	private void renderSliderPreview(GuiGraphicsExtractor context,
		SliderSetting slider, int y1, int y2, int fillColor, int bx1, int bx2)
	{
		int by1 = y1 + 6;
		int by2 = y2 - 6;
		context.fill(bx1, by1, bx2, by2,
			withAlpha(cfg().getPanelLightColor(), 0.8F));
		
		int fill = (int)((bx2 - bx1) * slider.getPercentage());
		context.fill(bx1, by1, bx1 + fill, by2, fillColor);
	}
	
	private void renderUiSettingsPanel(GuiGraphicsExtractor context, int mouseX,
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
		y = renderUiSlider(context, font, "Background opacity",
			cfg().getBackgroundOpacitySetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Settings width",
			cfg().getSettingsWidthSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Settings height",
			cfg().getSettingsHeightSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Window width",
			cfg().getWidthPercentSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Window height",
			cfg().getHeightPercentSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Category height",
			cfg().getCategoryHeightSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Category width",
			cfg().getCategoryWidthSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Row height",
			cfg().getRowHeightSetting(), y, mouseX, mouseY);
		y = renderUiSlider(context, font, "Font scale",
			cfg().getFontScaleSetting(), y, mouseX, mouseY);
		y = renderUiEnum(context, font, "Category layout",
			cfg().getCategoryLayoutSetting(), y, mouseX, mouseY);
		y = renderUiToggle(context, font, "Type badges",
			cfg().getTypeBadgesSetting(), y, mouseX, mouseY);
		y = renderUiToggle(context, font, "Fill color values",
			cfg().getFillColorValuesSetting(), y, mouseX, mouseY);
		y = renderUiToggle(context, font, "Setting row dividers",
			cfg().getSettingRowDividersSetting(), y, mouseX, mouseY);
		y = renderUiToggle(context, font, "Hack expand icons",
			cfg().getHackExpandIconsSetting(), y, mouseX, mouseY);
		y = renderUiToggle(context, font, "Auto-size top tabs",
			cfg().getAutoSizeTopTabsSetting(), y, mouseX, mouseY);
		y = renderUiToggle(context, font, "Search while typing",
			cfg().getSearchOnlyWhileTypingSetting(), y, mouseX, mouseY);
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
	
	private int renderUiSlider(GuiGraphicsExtractor context, Font font,
		String label, SliderSetting slider, int y, int mouseX, int mouseY)
	{
		int rowH = Math.max(getMinimumReadableUiRowHeight(),
			scaleRightSettingHeight(14));
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		int trackX1 = uiMenuX + scaleRightSettingWidth(144);
		int trackX2 = uiMenuX + uiMenuW - 12;
		boolean hovered = isInside(mouseX, mouseY, x1, y, x2, y + rowH);
		context.fill(x1, y, x2, y + rowH,
			hovered ? withAlpha(cfg().getAccentColor(), 0.14F)
				: withAlpha(cfg().getPanelLightColor(), 0.58F));
		drawStringScaled(context, font, label + ": " + slider.getValueString(),
			x1 + scaleRightSettingWidth(4), y + scaleRightSettingHeight(3),
			cfg().getMutedTextColor(), false);
		context.fill(trackX1, y + scaleRightSettingHeight(4), trackX2,
			y + scaleRightSettingHeight(10),
			withAlpha(cfg().getPanelLightColor(), 0.9F));
		int fill = (int)((trackX2 - trackX1) * slider.getPercentage());
		context.fill(trackX1, y + scaleRightSettingHeight(4), trackX1 + fill,
			y + scaleRightSettingHeight(10), cfg().getAccentColor());
		return y + rowH + scaleRightSettingHeight(2);
	}
	
	private int renderUiToggle(GuiGraphicsExtractor context, Font font,
		String label, CheckboxSetting setting, int y, int mouseX, int mouseY)
	{
		int rowH = Math.max(getMinimumReadableUiRowHeight(),
			scaleRightSettingHeight(14));
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		boolean hovered = isInside(mouseX, mouseY, x1, y, x2, y + rowH);
		context.fill(x1, y, x2, y + rowH,
			hovered ? withAlpha(cfg().getAccentColor(), 0.14F)
				: withAlpha(cfg().getPanelLightColor(), 0.58F));
		drawStringScaled(context, font, label, x1 + scaleRightSettingWidth(4),
			y + scaleRightSettingHeight(3), cfg().getMutedTextColor(), false);
		
		String state = setting.isChecked() ? "ON" : "OFF";
		int pillW = scaleRightSettingWidth(36);
		int pillX2 = x2 - scaleRightSettingWidth(4);
		int pillX1 = pillX2 - pillW;
		context.fill(pillX1, y + scaleRightSettingHeight(2), pillX2,
			y + rowH - scaleRightSettingHeight(2),
			setting.isChecked() ? withAlpha(cfg().getEnabledColor(), 0.86F)
				: withAlpha(cfg().getDisabledColor(), 0.9F));
		drawCenteredStringScaled(context, font, state, (pillX1 + pillX2) / 2,
			y + scaleRightSettingHeight(3), cfg().getTextColor());
		return y + rowH + scaleRightSettingHeight(2);
	}
	
	private int renderUiEnum(GuiGraphicsExtractor context, Font font,
		String label, EnumSetting<?> setting, int y, int mouseX, int mouseY)
	{
		int rowH = Math.max(getMinimumReadableUiRowHeight(),
			scaleRightSettingHeight(14));
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		boolean hovered = isInside(mouseX, mouseY, x1, y, x2, y + rowH);
		context.fill(x1, y, x2, y + rowH,
			hovered ? withAlpha(cfg().getAccentColor(), 0.14F)
				: withAlpha(cfg().getPanelLightColor(), 0.58F));
		drawStringScaled(context, font, label, x1 + scaleRightSettingWidth(4),
			y + scaleRightSettingHeight(3), cfg().getMutedTextColor(), false);
		
		String value = normalizeDisplayValue("" + setting.getSelected());
		int pillW = scaleRightSettingWidth(96);
		int pillX2 = x2 - scaleRightSettingWidth(4);
		int pillX1 = pillX2 - pillW;
		int pillY1 = y + scaleRightSettingHeight(2);
		int pillY2 = y + rowH - scaleRightSettingHeight(2);
		context.fill(pillX1, pillY1, pillX2, pillY2,
			withAlpha(cfg().getPanelColor(), 0.92F));
		drawMarqueeStringScaledInBox(context, font, value, pillX1, pillY1,
			pillX2, pillY2, cfg().getTextColor(), scaleRightSettingWidth(3));
		return y + rowH + scaleRightSettingHeight(2);
	}
	
	private int renderUiColor(GuiGraphicsExtractor context, Font font,
		String label, ColorSetting color, int y, int mouseX, int mouseY)
	{
		int rowH = Math.max(getMinimumReadableUiRowHeight(),
			scaleRightSettingHeight(13));
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		boolean hovered = isInside(mouseX, mouseY, x1, y, x2, y + rowH);
		context.fill(x1, y, x2, y + rowH,
			hovered ? withAlpha(cfg().getAccentColor(), 0.14F)
				: withAlpha(cfg().getPanelLightColor(), 0.58F));
		drawStringScaled(context, font, label, x1 + scaleRightSettingWidth(4),
			y + scaleRightSettingHeight(2), cfg().getMutedTextColor(), false);
		context.fill(x2 - scaleRightSettingWidth(44),
			y + scaleRightSettingHeight(2), x2 - scaleRightSettingWidth(30),
			y + rowH - scaleRightSettingHeight(2), color.getColorI());
		drawStringScaled(context, font, ColorUtils.toHex(color.getColor()),
			x2 - scaleRightSettingWidth(26), y + scaleRightSettingHeight(2),
			cfg().getTextColor(), false);
		return y + rowH + scaleRightSettingHeight(1);
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
		y = handleUiSliderClick(cfg().getBackgroundOpacitySetting(), mouseX,
			mouseY, y, button);
		y = handleUiSliderClick(cfg().getSettingsWidthSetting(), mouseX, mouseY,
			y, button);
		y = handleUiSliderClick(cfg().getSettingsHeightSetting(), mouseX,
			mouseY, y, button);
		y = handleUiSliderClick(cfg().getWidthPercentSetting(), mouseX, mouseY,
			y, button);
		y = handleUiSliderClick(cfg().getHeightPercentSetting(), mouseX, mouseY,
			y, button);
		y = handleUiSliderClick(cfg().getCategoryHeightSetting(), mouseX,
			mouseY, y, button);
		y = handleUiSliderClick(cfg().getCategoryWidthSetting(), mouseX, mouseY,
			y, button);
		y = handleUiSliderClick(cfg().getRowHeightSetting(), mouseX, mouseY, y,
			button);
		y = handleUiSliderClick(cfg().getFontScaleSetting(), mouseX, mouseY, y,
			button);
		y = handleUiEnumClick(cfg().getCategoryLayoutSetting(), mouseX, mouseY,
			y, button);
		y = handleUiToggleClick(cfg().getTypeBadgesSetting(), mouseX, mouseY,
			y);
		y = handleUiToggleClick(cfg().getFillColorValuesSetting(), mouseX,
			mouseY, y);
		y = handleUiToggleClick(cfg().getSettingRowDividersSetting(), mouseX,
			mouseY, y);
		y = handleUiToggleClick(cfg().getHackExpandIconsSetting(), mouseX,
			mouseY, y);
		y = handleUiToggleClick(cfg().getAutoSizeTopTabsSetting(), mouseX,
			mouseY, y);
		y = handleUiToggleClick(cfg().getSearchOnlyWhileTypingSetting(), mouseX,
			mouseY, y);
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
		int rowH = Math.max(getMinimumReadableUiRowHeight(),
			scaleRightSettingHeight(14));
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		int trackX1 = uiMenuX + scaleRightSettingWidth(144);
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
		return y + rowH + scaleRightSettingHeight(2);
	}
	
	private int handleUiColorClick(ColorSetting setting, double mouseX,
		double mouseY, int y)
	{
		int rowH = Math.max(getMinimumReadableUiRowHeight(),
			scaleRightSettingHeight(13));
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		if(isInside(mouseX, mouseY, x1, y, x2, y + rowH))
			minecraft.setScreen(new EditColorScreen(this, setting));
		return y + rowH + scaleRightSettingHeight(1);
	}
	
	private int handleUiToggleClick(CheckboxSetting setting, double mouseX,
		double mouseY, int y)
	{
		int rowH = Math.max(getMinimumReadableUiRowHeight(),
			scaleRightSettingHeight(14));
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		if(isInside(mouseX, mouseY, x1, y, x2, y + rowH))
		{
			setting.setChecked(!setting.isChecked());
			if(setting == cfg().getSearchOnlyWhileTypingSetting()
				&& searchBox != null)
			{
				if(setting.isChecked())
				{
					if(searchBox.getValue().isBlank())
					{
						setFocused(null);
						searchBox.setFocused(false);
						searchBox.setVisible(false);
					}
				}else
				{
					searchBox.setVisible(true);
				}
			}
			if(setting == cfg().getAutoSizeTopTabsSetting())
				applyLiveLayout();
		}
		return y + rowH + scaleRightSettingHeight(2);
	}
	
	private int handleUiEnumClick(EnumSetting<?> setting, double mouseX,
		double mouseY, int y, int button)
	{
		int rowH = Math.max(getMinimumReadableUiRowHeight(),
			scaleRightSettingHeight(14));
		int x1 = uiMenuX + 8;
		int x2 = uiMenuX + uiMenuW - 8;
		if(isInside(mouseX, mouseY, x1, y, x2, y + rowH))
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
				setting.selectPrev();
			else
				setting.selectNext();
			applyLiveLayout();
		}
		return y + rowH + scaleRightSettingHeight(2);
	}
	
	private boolean handleCategoryClick(double mouseX, double mouseY)
	{
		if(isTopTabsLayout())
			return handleTopTabCategoryClick(mouseX, mouseY);
		
		int rowH = getCategoryRowHeight();
		int rowGap = getCategoryRowGap();
		int areaX1 = getCategoryAreaX1();
		int areaX2 = getCategoryAreaX2();
		int areaY1 = getCategoryAreaY1();
		int y = areaY1 - categoryScroll;
		
		if(!isInsideCategoryArea(mouseX, mouseY))
			return false;
		
		if(isInside(mouseX, mouseY, areaX1, y, areaX2, y + rowH))
		{
			clearSearch();
			enabledCategorySelected = true;
			styleCategorySelected = false;
			moduleScroll = 0;
			return true;
		}
		y += rowH + rowGap;
		
		for(String categoryName : getDisplayCategories())
		{
			if(isInside(mouseX, mouseY, areaX1, y, areaX2, y + rowH))
			{
				clearSearch();
				selectedCategory = categoryName;
				enabledCategorySelected = false;
				styleCategorySelected = false;
				moduleScroll = 0;
				return true;
			}
			y += rowH + rowGap;
		}
		
		if(isInside(mouseX, mouseY, areaX1, y, areaX2, y + rowH))
		{
			clearSearch();
			enabledCategorySelected = false;
			styleCategorySelected = true;
			moduleScroll = 0;
			return true;
		}
		
		return false;
	}
	
	private boolean handleTopTabCategoryClick(double mouseX, double mouseY)
	{
		if(!isInsideCategoryArea(mouseX, mouseY))
			return false;
		
		int areaY1 = getCategoryAreaY1();
		int areaY2 = getCategoryAreaY2();
		for(TopCategoryTab tab : getTopCategoryTabs(minecraft.font))
		{
			if(!isInside(mouseX, mouseY, tab.x1(), areaY1, tab.x2(), areaY2))
				continue;
			
			clearSearch();
			switch(tab.kind())
			{
				case ENABLED ->
				{
					enabledCategorySelected = true;
					styleCategorySelected = false;
				}
				case CATEGORY ->
				{
					selectedCategory = tab.categoryName();
					enabledCategorySelected = false;
					styleCategorySelected = false;
				}
				case STYLE ->
				{
					enabledCategorySelected = false;
					styleCategorySelected = true;
				}
			}
			moduleScroll = 0;
			return true;
		}
		
		return false;
	}
	
	private void clearSearch()
	{
		searchText = "";
		if(searchBox != null)
		{
			searchBox.setValue("");
			if(cfg().isSearchOnlyWhileTypingEnabled())
			{
				setFocused(null);
				searchBox.setFocused(false);
				searchBox.setVisible(false);
			}
		}
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
		if(row.isKeybindRow())
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				minecraft.setScreen(new AltGuiKeybindScreen(this, owner));
			return true;
		}
		
		Setting setting = row.setting();
		if(setting instanceof SpacerSetting)
			return false;
		
		if(setting instanceof CheckboxSetting checkbox)
		{
			checkbox.setChecked(!checkbox.isChecked());
			if(checkbox == cfg().getSearchOnlyWhileTypingSetting()
				&& searchBox != null)
			{
				if(checkbox.isChecked())
				{
					if(searchBox.getValue().isBlank())
					{
						setFocused(null);
						searchBox.setFocused(false);
						searchBox.setVisible(false);
					}
				}else
				{
					searchBox.setVisible(true);
				}
			}
			if(checkbox == cfg().getAutoSizeTopTabsSetting())
				applyLiveLayout();
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
			
			int rowX1 = moduleX + 20 + row.depth() * scaleRightSettingWidth(10);
			int rowX2 = moduleX + moduleW - 12;
			int fixedValueColW = getSettingsValueColumnWidth(rowX1, rowX2);
			int valueX2 = rowX2 - scaleRightSettingWidth(6);
			int valueX1 = valueX2 - fixedValueColW;
			int x1 = valueX1 + scaleRightSettingWidth(2);
			int x2 = valueX2 - scaleRightSettingWidth(2);
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
			if(enumSetting == cfg().getCategoryLayoutSetting())
				applyLiveLayout();
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
	
	private Set<PossibleKeybind> getPossibleKeybindsForFeature(Feature feature)
	{
		LinkedHashSet<PossibleKeybind> possible =
			new LinkedHashSet<>(feature.getPossibleKeybinds());
		
		if(feature instanceof Hack)
			possible.add(new PossibleKeybind(feature.getName(),
				"Toggle " + feature.getName()));
		
		return possible;
	}
	
	private boolean featureSupportsKeybinds(Feature feature)
	{
		if(feature == null)
			return false;
		
		if(KEYBINDS_HIDDEN_FOR.stream()
			.anyMatch(name -> name.equalsIgnoreCase(feature.getName())))
			return false;
		
		return !getPossibleKeybindsForFeature(feature).isEmpty();
	}
	
	private TreeMap<String, PossibleKeybind> getExistingKeybindsForFeature(
		Feature feature, Set<PossibleKeybind> possibleKeybinds)
	{
		TreeMap<String, PossibleKeybind> existing = new TreeMap<>();
		TreeMap<String, String> possibleByCommand =
			new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for(PossibleKeybind pkb : possibleKeybinds)
			possibleByCommand.put(pkb.getCommand(), pkb.getDescription());
		
		for(Keybind keybind : WurstClient.INSTANCE.getKeybinds()
			.getAllKeybinds())
		{
			String commands = keybind.getCommands().replace(";", "\u00a7")
				.replace("\u00a7\u00a7", ";");
			for(String commandRaw : commands.split("\u00a7"))
			{
				String command = commandRaw.trim();
				String description = possibleByCommand.get(command);
				if(description != null)
				{
					existing.put(keybind.getKey(),
						new PossibleKeybind(command, description));
					continue;
				}
				
				if(feature instanceof Hack
					&& command.equalsIgnoreCase(feature.getName()))
					existing.put(keybind.getKey(), new PossibleKeybind(command,
						"Toggle " + feature.getName()));
			}
		}
		
		return existing;
	}
	
	private List<Feature> getFilteredFeatures()
	{
		String query = searchText == null ? ""
			: searchText.toLowerCase(Locale.ROOT).trim();
		boolean globalSearch = !query.isEmpty();
		ArrayList<Feature> features = new ArrayList<>();
		Feature performanceOverlay =
			WurstClient.INSTANCE.getOtfs().performanceOverlayOtf;
		List<Feature> clientSettingsFeatures = getClientSettingsFeatures();
		
		for(Hack hack : WurstClient.INSTANCE.getHax().getAllHax())
		{
			if(hack instanceof ClickGuiHack || hack instanceof NavigatorHack
				|| hack instanceof AltGuiHack || hack instanceof XpGuiHack)
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
		
		for(OtherFeature otf : WurstClient.INSTANCE.getOtfs().getAllOtfs())
		{
			if(otf == null || otf == performanceOverlay)
				continue;
			
			if(HIDDEN_OTHER_FEATURES.contains(otf.getName()))
				continue;
			
			if(MOVE_TO_CLIENT_SETTINGS.stream()
				.anyMatch(name -> name.equalsIgnoreCase(otf.getName())))
				continue;
			
			if(clientSettingsFeatures.contains(otf))
				continue;
			
			if(isHiddenByTooManyHax(otf))
				continue;
			
			if(!globalSearch)
			{
				if(styleCategorySelected || enabledCategorySelected)
					continue;
				if(!selectedCategory.equalsIgnoreCase(Category.OTHER.getName()))
					continue;
			}
			
			if(globalSearch && !matchesSearch(otf, query))
				continue;
			
			features.add(otf);
		}
		
		if(!styleCategorySelected && !enabledCategorySelected)
		{
			boolean include = globalSearch
				? matchesSearch(performanceOverlay, query)
				: selectedCategory.equalsIgnoreCase(Category.OTHER.getName());
			if(include && !isHiddenByTooManyHax(performanceOverlay))
				features.add(performanceOverlay);
		}
		
		if(globalSearch)
			features.sort(buildSearchComparator(query));
		else
			features.sort(Comparator.comparing(Feature::getName,
				String.CASE_INSENSITIVE_ORDER));
		return features;
	}
	
	private List<DisplayEntry> getDisplayedEntries()
	{
		String query = searchText == null ? ""
			: searchText.toLowerCase(Locale.ROOT).trim();
		if(!query.isEmpty())
			return getUniversalSearchEntries(query);
		
		ArrayList<DisplayEntry> entries = new ArrayList<>();
		if(!styleCategorySelected && !enabledCategorySelected)
		{
			for(Feature feature : getFilteredFeatures())
				entries.add(new DisplayEntry(feature, false));
			return entries;
		}
		
		if(enabledCategorySelected)
			return getEnabledCategoryEntries();
		
		ArrayList<Feature> features =
			new ArrayList<>(getClientSettingsFeatures());
		for(Feature feature : features)
			entries.add(new DisplayEntry(feature, false));
		return entries;
	}
	
	private List<DisplayEntry> getUniversalSearchEntries(String query)
	{
		ArrayList<DisplayEntry> entries = new ArrayList<>();
		HashSet<String> added = new HashSet<>();
		
		for(Feature feature : getFilteredFeatures())
		{
			String key = feature.getName().toLowerCase(Locale.ROOT);
			if(!added.add(key))
				continue;
			entries.add(new DisplayEntry(feature, false));
		}
		
		for(Feature feature : getClientSettingsFeatures())
		{
			if(!matchesSearch(feature, query))
				continue;
			
			String key = feature.getName().toLowerCase(Locale.ROOT);
			if(!added.add(key))
				continue;
			entries.add(new DisplayEntry(feature, false));
		}
		
		Feature performanceOverlay =
			WurstClient.INSTANCE.getOtfs().performanceOverlayOtf;
		if(!isHiddenByTooManyHax(performanceOverlay)
			&& matchesSearch(performanceOverlay, query))
		{
			String key = performanceOverlay.getName().toLowerCase(Locale.ROOT);
			if(added.add(key))
				entries.add(new DisplayEntry(performanceOverlay, false));
		}
		
		Comparator<Feature> comparator = buildSearchComparator(query);
		entries.sort(Comparator
			.comparing((DisplayEntry entry) -> entry.feature(), comparator));
		return entries;
	}
	
	private Comparator<Feature> buildSearchComparator(String query)
	{
		Comparator<String> indexComparator = (a, b) -> {
			int index1 = indexOfQuery(a, query);
			int index2 = indexOfQuery(b, query);
			if(index1 == index2)
				return 0;
			if(index1 == -1)
				return 1;
			if(index2 == -1)
				return -1;
			return index1 - index2;
		};
		
		return Comparator.comparing(Feature::getName, indexComparator)
			.thenComparing(Feature::getSearchTags, indexComparator)
			.thenComparing(Feature::getDescription, indexComparator)
			.thenComparing(Feature::getName, String.CASE_INSENSITIVE_ORDER);
	}
	
	private int indexOfQuery(String text, String query)
	{
		if(text == null || query == null)
			return -1;
		return text.toLowerCase(Locale.ROOT).indexOf(query);
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
				|| hack instanceof AltGuiHack || hack instanceof XpGuiHack)
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
		features.add(WurstClient.INSTANCE.getHax().navigatorHack);
		features.add(WurstClient.INSTANCE.getHax().clickGuiHack);
		features.add(WurstClient.INSTANCE.getHax().altGuiHack);
		features.add(WurstClient.INSTANCE.getHax().xpGuiHack);
		return features;
	}
	
	private boolean matchesCategory(Hack hack, String categoryName)
	{
		if(categoryName.equalsIgnoreCase(Category.FAVORITES.getName()))
			return hack.isFavorite();
		
		String hackCategory = hack.getCategoryName();
		if(hackCategory == null || hackCategory.isBlank())
			hackCategory = Category.OTHER.getName();
		
		return hackCategory.equalsIgnoreCase(categoryName);
	}
	
	private List<String> getDisplayCategories()
	{
		ArrayList<String> categories = new ArrayList<>();
		for(Category category : Category.values())
			categories.add(category.getName());
		
		for(Hack hack : WurstClient.INSTANCE.getHax().getAllHax())
		{
			String categoryName = hack.getCategoryName();
			if(categoryName == null || categoryName.isBlank())
				continue;
			
			if(!containsIgnoreCase(categories, categoryName))
				categories.add(categoryName);
		}
		
		return categories;
	}
	
	private boolean containsIgnoreCase(List<String> values, String needle)
	{
		for(String value : values)
			if(value.equalsIgnoreCase(needle))
				return true;
		return false;
	}
	
	private boolean matchesSearch(Feature feature, String query)
	{
		if(feature.getName().toLowerCase(Locale.ROOT).contains(query))
			return true;
		
		if(feature.getDescription().toLowerCase(Locale.ROOT).contains(query))
			return true;
		
		if(feature.getSearchTags().toLowerCase(Locale.ROOT).contains(query))
			return true;
		
		if(!cfg().isSearchSettingsEnabled())
			return false;
		
		return matchesSettingsSearch(feature, query);
	}
	
	private boolean matchesSettingsSearch(Feature feature, String query)
	{
		for(Setting setting : feature.getSettings().values())
			if(matchesSettingSearch(setting, query))
				return true;
			
		return false;
	}
	
	private boolean matchesSettingSearch(Setting setting, String query)
	{
		if(!(setting instanceof SettingGroup) && !setting.isVisibleInGui())
			return false;
		
		if(setting.getName().toLowerCase(Locale.ROOT).contains(query))
			return true;
		
		if(setting instanceof SettingGroup group)
			for(Setting child : group.getChildren())
				if(matchesSettingSearch(child, query))
					return true;
				
		return false;
	}
	
	private List<SettingRow> getSettingRows(Feature feature)
	{
		ArrayList<SettingRow> rows = new ArrayList<>();
		for(Setting setting : feature.getSettings().values())
			appendSettingRows(rows, feature, setting, 0, false);
		
		if(featureSupportsKeybinds(feature))
		{
			int h = Math.max(cfg().getMinimumRowHeight(), Math
				.round(cfg().getRowHeight() * getRightSettingsHeightScale()));
			rows.add(new SettingRow(feature, null, 0, h, true));
		}
		
		return rows;
	}
	
	private void appendSettingRows(List<SettingRow> rows, Feature owner,
		Setting setting, int depth, boolean insideGroup)
	{
		if(!insideGroup && !(setting instanceof SettingGroup)
			&& !setting.isVisibleInGui())
			return;
		
		int h =
			setting instanceof SpacerSetting
				? Math.max(4,
					Math.round(cfg().getRowHeight()
						* getRightSettingsHeightScale() / 2F))
				: Math.max(cfg().getMinimumRowHeight(), Math.round(
					cfg().getRowHeight() * getRightSettingsHeightScale()));
		rows.add(new SettingRow(owner, setting, depth, h, false));
		
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
		
		return trimmed;
	}
	
	private boolean isStyleSlider(SliderSetting slider)
	{
		return slider == cfg().getUiOpacitySetting()
			|| slider == cfg().getBackgroundOpacitySetting()
			|| slider == cfg().getTooltipOpacitySetting()
			|| slider == cfg().getSettingsWidthSetting()
			|| slider == cfg().getSettingsHeightSetting()
			|| slider == cfg().getWidthPercentSetting()
			|| slider == cfg().getHeightPercentSetting()
			|| slider == cfg().getCategoryHeightSetting()
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
		
		if(feature instanceof ClickGuiHack || feature instanceof AltGuiHack
			|| feature instanceof XpGuiHack)
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
			|| name.equalsIgnoreCase("WurstLogo")
			|| name.equalsIgnoreCase("ClickGUI")
			|| name.equalsIgnoreCase("AltGUI")
			|| name.equalsIgnoreCase("XPGUI"));
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
		
		if(categoryScroll < 0)
			categoryScroll = 0;
		if(categoryScroll > maxCategoryScroll)
			categoryScroll = maxCategoryScroll;
	}
	
	private boolean isInsideCategoryArea(double mouseX, double mouseY)
	{
		int x1 = getCategoryAreaX1();
		int x2 = getCategoryAreaX2();
		int y1 = getCategoryAreaY1();
		int y2 = getCategoryAreaY2();
		return isInside(mouseX, mouseY, x1, y1, x2, y2);
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
	
	private static int getReadableTextColor(int bgColor)
	{
		int r = (bgColor >> 16) & 0xFF;
		int g = (bgColor >> 8) & 0xFF;
		int b = bgColor & 0xFF;
		double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
		return luminance > 0.55 ? 0xFF000000 : 0xFFFFFFFF;
	}
	
	private void drawRectBorder(GuiGraphicsExtractor context, int x1, int y1,
		int x2, int y2, int color)
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
	
	private void drawStringScaled(GuiGraphicsExtractor context, Font font,
		String text, int x, int y, int color, boolean shadow)
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
	
	private void drawCenteredStringScaled(GuiGraphicsExtractor context,
		Font font, String text, int centerX, int y, int color)
	{
		int x = centerX - scaledFontWidth(font, text) / 2;
		drawStringScaled(context, font, text, x, y, color, false);
	}
	
	private void drawCenteredStringScaledInBox(GuiGraphicsExtractor context,
		Font font, String text, int x1, int y1, int x2, int y2, int color)
	{
		float textW = scaledFontWidth(font, text);
		float textH = Math.max(1, scaledFontHeight(font));
		float centerX = (x1 + x2) * 0.5F;
		float centerY = (y1 + y2) * 0.5F;
		int x = Math.round(centerX - textW * 0.5F);
		int y = Math.round(centerY - textH * 0.5F);
		drawStringScaled(context, font, text, x, y, color, false);
	}
	
	private void drawMarqueeStringScaledInBox(GuiGraphicsExtractor context,
		Font font, String text, int x1, int y1, int x2, int y2, int color,
		int padX)
	{
		if(text == null || text.isEmpty())
			return;
		
		int innerX1 = x1 + Math.max(0, padX);
		int innerX2 = x2 - Math.max(0, padX);
		if(innerX2 <= innerX1)
			return;
		
		int innerW = innerX2 - innerX1;
		int textW = Math.max(1, scaledFontWidth(font, text));
		int textH = Math.max(1, scaledFontHeight(font));
		int y = Math.round((y1 + y2) * 0.5F - textH * 0.5F);
		
		if(textW <= innerW)
		{
			int x = Math.round((x1 + x2) * 0.5F - textW * 0.5F);
			drawStringScaled(context, font, text, x, y, color, false);
			return;
		}
		
		int overflow = textW - innerW;
		int ticks = minecraft != null && minecraft.gui != null
			? minecraft.gui.getGuiTicks()
			: (int)(System.currentTimeMillis() / 50L);
		float cycle = 220F;
		float phase = (ticks % (int)cycle) / cycle;
		float pingPong = phase <= 0.5F ? phase * 2F : (1F - phase) * 2F;
		int x = innerX1 - Math.round(overflow * pingPong);
		
		context.enableScissor(innerX1, y1, innerX2, y2);
		drawStringScaled(context, font, text, x, y, color, false);
		context.disableScissor();
	}
	
	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float deltaTicks)
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
		int height, boolean isKeybindRow)
	{}
	
	private record SliderDrag(SliderSetting slider, int x1, int x2)
	{}
	
	private enum TopTabKind
	{
		ENABLED,
		CATEGORY,
		STYLE
	}
	
	private record TopCategoryTab(TopTabKind kind, String label,
		String categoryName, int x1, int x2)
	{}
}
