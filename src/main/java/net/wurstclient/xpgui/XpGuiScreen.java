/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.xpgui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.lwjgl.glfw.GLFW;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.wurstclient.Feature;
import net.wurstclient.Category;
import net.wurstclient.WurstClient;
import net.wurstclient.altmanager.screens.AltManagerScreen;
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
import net.wurstclient.hacks.ClickGuiHack;
import net.wurstclient.hacks.NavigatorHack;
import net.wurstclient.hacks.TooManyHaxHack;
import net.wurstclient.hacks.XpGuiHack;
import net.wurstclient.options.KeybindManagerScreen;
import net.wurstclient.options.PresetManagerScreen;
import net.wurstclient.options.WurstOptionsScreen;
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
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SpacerSetting;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.ToggleAllPlantTypesSetting;
import net.wurstclient.settings.WaypointsSetting;
import net.wurstclient.util.ChatUtils;

public final class XpGuiScreen extends Screen
{
	private static final int TASKBAR_HEIGHT = 28;
	private static final int TITLE_BAR_HEIGHT = 22;
	private static final int WINDOW_BORDER = 2;
	private static final int WINDOW_INNER_PAD = 6;
	private static final int SETTING_ROW_GAP = 2;
	private static final int START_BUTTON_WIDTH = 86;
	private static final int TASKBAR_RIGHT_WIDTH = 96;
	private static final Identifier XP_DEFAULT_BG_TEX_ID =
		Identifier.fromNamespaceAndPath("wurst", "background.png");
	private static final String XP_WALLPAPER_URL =
		"https://wallpaperaccess.com/full/1536061.jpg";
	private static final String WIN2000_WALLPAPER_URL =
		"https://wallpaperaccess.com/full/2245679.jpg";
	private static final Identifier XP_DESKTOP_TEX_ID =
		Identifier.fromNamespaceAndPath("wurst", "dynamic/xpgui_desktop");
	private static final Identifier XP_DOG_TEX_DYNAMIC_ID =
		Identifier.fromNamespaceAndPath("wurst", "dynamic/xpgui_dog");
	
	private final XpWindowManager windowManager = new XpWindowManager();
	
	private boolean startMenuOpen;
	private boolean allProgramsOpen;
	private String hoveredCategory;
	private int favoritesScroll;
	private int allProgramsModuleScroll;
	
	private boolean searchDialogOpen;
	private boolean searchDialogMinimized;
	private boolean searchDialogFocused;
	private int searchDialogX = Integer.MIN_VALUE;
	private int searchDialogY = Integer.MIN_VALUE;
	private boolean draggingSearchDialog;
	private int draggingSearchOffsetX;
	private int draggingSearchOffsetY;
	private String searchQuery = "";
	private int searchScroll;
	private int searchResultsScroll;
	private boolean searchFieldFocused;
	private boolean searchSelectAll;
	
	private DesktopBackgroundMode desktopBackgroundMode =
		DesktopBackgroundMode.IMAGE;
	private int desktopOpacity = 255;
	private final SliderSetting allProgramsVisibleItems = new SliderSetting(
		"Hack list items", 14, 4, 24, 1, ValueDisplay.INTEGER);
	private String desktopImageUrl = "";
	private boolean win2000Theme;
	private boolean desktopImageRequested;
	private boolean desktopImageFailed;
	private boolean xpguiSettingsLoaded;
	private NativeImage pendingDesktopImage;
	private DynamicTexture desktopTexture;
	private final List<DogGifFrame> dogGifFrames = new ArrayList<>();
	private long dogAnimationStartMs;
	private boolean dogTextureFailed;
	
	private boolean xpguiSettingsOpen;
	private boolean settingsUrlFocused;
	private boolean settingsStartIconFocused;
	private boolean settingsUrlSelectAll;
	private boolean settingsStartIconSelectAll;
	private boolean settingsOpacityDrag;
	private boolean settingsListItemsDrag;
	private UiScrollbarDrag uiScrollbarDrag;
	
	private String startIconBlockName = "crafting_table";
	private Block startIconBlock = Blocks.CRAFTING_TABLE;
	
	private TextSettingEditSession textSettingEditSession;
	
	private XpModuleWindow draggingWindow;
	private int draggingOffsetX;
	private int draggingOffsetY;
	private SliderDrag sliderDrag;
	private SettingScrollDrag settingScrollDrag;
	
	private static final class DogGifFrame
	{
		private final Identifier textureId;
		private final DynamicTexture texture;
		private final int delayMs;
		private final int width;
		private final int height;
		private final int cropX;
		private final int cropY;
		private final int cropW;
		private final int cropH;
		
		private DogGifFrame(Identifier textureId, DynamicTexture texture,
			int delayMs, int width, int height, int cropX, int cropY, int cropW,
			int cropH)
		{
			this.textureId = textureId;
			this.texture = texture;
			this.delayMs = delayMs;
			this.width = width;
			this.height = height;
			this.cropX = cropX;
			this.cropY = cropY;
			this.cropW = cropW;
			this.cropH = cropH;
		}
	}
	
	public XpGuiScreen()
	{
		super(Component.literal("XPGUI"));
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
	
	@Override
	public void removed()
	{
		draggingWindow = null;
		sliderDrag = null;
		uiScrollbarDrag = null;
		if(pendingDesktopImage != null)
		{
			pendingDesktopImage.close();
			pendingDesktopImage = null;
		}
		if(desktopTexture != null)
		{
			desktopTexture.close();
			desktopTexture = null;
		}
		if(!dogGifFrames.isEmpty())
		{
			for(DogGifFrame frame : dogGifFrames)
				frame.texture.close();
			dogGifFrames.clear();
		}
		super.removed();
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(textSettingEditSession != null)
		{
			if(context.key() == GLFW.GLFW_KEY_ESCAPE
				|| context.key() == GLFW.GLFW_KEY_ENTER)
			{
				textSettingEditSession = null;
				return true;
			}
			
			if(context.hasControlDown())
			{
				if(context.key() == GLFW.GLFW_KEY_A)
				{
					textSettingEditSession.selectAll = true;
					return true;
				}
				if(context.key() == GLFW.GLFW_KEY_C)
				{
					if(textSettingEditSession.selectAll)
						minecraft.keyboardHandler.setClipboard(
							textSettingEditSession.setting.getValue());
					return true;
				}
				if(context.key() == GLFW.GLFW_KEY_V)
				{
					String clip = minecraft.keyboardHandler.getClipboard();
					if(textSettingEditSession.selectAll)
						textSettingEditSession.setting.setValue(clip);
					else
						textSettingEditSession.setting.setValue(
							textSettingEditSession.setting.getValue() + clip);
					textSettingEditSession.selectAll = false;
					return true;
				}
			}
			
			if(context.key() == GLFW.GLFW_KEY_BACKSPACE)
			{
				String value = textSettingEditSession.setting.getValue();
				if(textSettingEditSession.selectAll)
				{
					textSettingEditSession.setting.setValue("");
					textSettingEditSession.selectAll = false;
				}else if(!value.isEmpty())
					textSettingEditSession.setting
						.setValue(value.substring(0, value.length() - 1));
				return true;
			}
		}
		
		if(xpguiSettingsOpen)
		{
			if(context.key() == GLFW.GLFW_KEY_ESCAPE)
			{
				xpguiSettingsOpen = false;
				settingsUrlFocused = false;
				settingsStartIconFocused = false;
				return true;
			}
			
			if((settingsUrlFocused || settingsStartIconFocused)
				&& context.hasControlDown())
			{
				if(context.key() == GLFW.GLFW_KEY_A)
				{
					if(settingsUrlFocused)
						settingsUrlSelectAll = true;
					if(settingsStartIconFocused)
						settingsStartIconSelectAll = true;
					return true;
				}
				
				if(context.key() == GLFW.GLFW_KEY_C)
				{
					if(settingsUrlFocused && settingsUrlSelectAll)
						minecraft.keyboardHandler.setClipboard(desktopImageUrl);
					if(settingsStartIconFocused && settingsStartIconSelectAll)
						minecraft.keyboardHandler
							.setClipboard(startIconBlockName);
					return true;
				}
				
				if(context.key() == GLFW.GLFW_KEY_V)
				{
					String clip = minecraft.keyboardHandler.getClipboard();
					if(settingsUrlFocused)
					{
						if(settingsUrlSelectAll)
							desktopImageUrl = clip;
						else
							desktopImageUrl += clip;
						settingsUrlSelectAll = false;
						saveXpguiSettings();
					}
					if(settingsStartIconFocused)
					{
						if(settingsStartIconSelectAll)
							startIconBlockName = clip;
						else
							startIconBlockName += clip;
						settingsStartIconSelectAll = false;
						saveXpguiSettings();
					}
					return true;
				}
			}
			
			if(settingsUrlFocused && context.key() == GLFW.GLFW_KEY_BACKSPACE
				&& (settingsUrlSelectAll || !desktopImageUrl.isEmpty()))
			{
				if(settingsUrlSelectAll)
				{
					desktopImageUrl = "";
					settingsUrlSelectAll = false;
				}else
					desktopImageUrl = desktopImageUrl.substring(0,
						desktopImageUrl.length() - 1);
				saveXpguiSettings();
				return true;
			}
			
			if(settingsStartIconFocused
				&& context.key() == GLFW.GLFW_KEY_BACKSPACE
				&& (settingsStartIconSelectAll
					|| !startIconBlockName.isEmpty()))
			{
				if(settingsStartIconSelectAll)
				{
					startIconBlockName = "";
					settingsStartIconSelectAll = false;
				}else
					startIconBlockName = startIconBlockName.substring(0,
						startIconBlockName.length() - 1);
				saveXpguiSettings();
				return true;
			}
			
			if(settingsUrlFocused && context.key() == GLFW.GLFW_KEY_ENTER)
			{
				downloadDesktopImage(desktopImageUrl);
				saveXpguiSettings();
				return true;
			}
			
			if(settingsStartIconFocused && context.key() == GLFW.GLFW_KEY_ENTER)
			{
				applyStartIconBlockName();
				saveXpguiSettings();
				return true;
			}
		}
		
		if(searchDialogOpen && !searchDialogMinimized)
		{
			if(context.key() == GLFW.GLFW_KEY_ESCAPE)
			{
				searchDialogOpen = false;
				searchDialogMinimized = false;
				searchDialogFocused = false;
				searchFieldFocused = false;
				return true;
			}
			
			if(searchFieldFocused && context.hasControlDown())
			{
				if(context.key() == GLFW.GLFW_KEY_A)
				{
					searchSelectAll = true;
					return true;
				}
				
				if(context.key() == GLFW.GLFW_KEY_C)
				{
					if(searchSelectAll)
						minecraft.keyboardHandler.setClipboard(searchQuery);
					return true;
				}
				
				if(context.key() == GLFW.GLFW_KEY_V)
				{
					String clip = minecraft.keyboardHandler.getClipboard();
					if(searchSelectAll)
						searchQuery = clip;
					else
						searchQuery += clip;
					searchSelectAll = false;
					searchResultsScroll = 0;
					return true;
				}
			}
			
			if(searchFieldFocused && context.key() == GLFW.GLFW_KEY_BACKSPACE
				&& (searchSelectAll || !searchQuery.isEmpty()))
			{
				if(searchSelectAll)
				{
					searchQuery = "";
					searchSelectAll = false;
				}else
					searchQuery =
						searchQuery.substring(0, searchQuery.length() - 1);
				searchResultsScroll = 0;
				return true;
			}
			
			if(searchFieldFocused && context.key() == GLFW.GLFW_KEY_ENTER)
				return true;
		}
		
		if(context.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			if(startMenuOpen)
			{
				startMenuOpen = false;
				allProgramsOpen = false;
				hoveredCategory = null;
				return true;
			}
			
			minecraft.setScreen(null);
			return true;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event)
	{
		if(textSettingEditSession != null)
		{
			char c = (char)event.codepoint();
			if(c >= 32 && c != 127)
			{
				if(textSettingEditSession.selectAll)
					textSettingEditSession.setting
						.setValue(Character.toString(c));
				else
					textSettingEditSession.setting.setValue(
						textSettingEditSession.setting.getValue() + c);
				textSettingEditSession.selectAll = false;
			}
			return true;
		}
		
		if(xpguiSettingsOpen
			&& (settingsUrlFocused || settingsStartIconFocused))
		{
			char c = (char)event.codepoint();
			if(c >= 32 && c != 127)
			{
				if(settingsUrlFocused)
				{
					if(settingsUrlSelectAll)
						desktopImageUrl = Character.toString(c);
					else
						desktopImageUrl += c;
					settingsUrlSelectAll = false;
					saveXpguiSettings();
				}
				
				if(settingsStartIconFocused)
				{
					if(settingsStartIconSelectAll)
						startIconBlockName = Character.toString(c);
					else
						startIconBlockName += c;
					settingsStartIconSelectAll = false;
					saveXpguiSettings();
				}
			}
			return true;
		}
		
		if(searchDialogOpen && searchFieldFocused)
		{
			char c = (char)event.codepoint();
			if(c >= 32 && c != 127)
			{
				if(searchSelectAll)
					searchQuery = Character.toString(c);
				else
					searchQuery += c;
				searchSelectAll = false;
				searchResultsScroll = 0;
			}
			return true;
		}
		
		return super.charTyped(event);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		double mouseX = context.x();
		double mouseY = context.y();
		int button = context.button();
		
		if(handleTaskbarClick(mouseX, mouseY, button))
			return true;
		
		if(startMenuOpen && handleStartMenuClick(mouseX, mouseY, button))
			return true;
		
		if(xpguiSettingsOpen)
		{
			if(handleSettingsDialogClick(mouseX, mouseY, button))
				return true;
			
			if(!isInsideSettingsDialog(mouseX, mouseY))
			{
				xpguiSettingsOpen = false;
				settingsUrlFocused = false;
				settingsStartIconFocused = false;
				return true;
			}
		}
		
		if(searchDialogOpen && !searchDialogMinimized)
		{
			if(handleSearchDialogClick(mouseX, mouseY, button))
				return true;
			
			if(!isInsideSearchDialog(mouseX, mouseY))
			{
				searchFieldFocused = false;
			}
		}
		
		if(startMenuOpen && !isInsideStartMenu(mouseX, mouseY))
		{
			startMenuOpen = false;
			allProgramsOpen = false;
			hoveredCategory = null;
		}
		
		XpModuleWindow focused = windowManager.getFocusedWindow();
		if(focused != null && !focused.isMinimized()
			&& isInsideRect(mouseX, mouseY, focused.getX(), focused.getY(),
				focused.getWidth(), focused.getHeight()))
		{
			searchDialogFocused = false;
			if(handleWindowChromeClick(focused, mouseX, mouseY, button))
				return true;
			if(handleWindowContentClick(focused, mouseX, mouseY, button))
				return true;
			return true;
		}
		
		List<XpModuleWindow> windows = windowManager.getWindows();
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			XpModuleWindow window = windows.get(i);
			if(window == focused || window.isMinimized())
				continue;
			
			if(!isInsideRect(mouseX, mouseY, window.getX(), window.getY(),
				window.getWidth(), window.getHeight()))
				continue;
			
			windowManager.focusWindow(window);
			searchDialogFocused = false;
			if(handleWindowChromeClick(window, mouseX, mouseY, button))
				return true;
			if(handleWindowContentClick(window, mouseX, mouseY, button))
				return true;
			return true;
		}
		
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public boolean mouseDragged(MouseButtonEvent context, double dragX,
		double dragY)
	{
		if(draggingSearchDialog && searchDialogOpen && !searchDialogMinimized)
		{
			SearchDialogRects rects = getSearchDialogRects();
			int maxX = Math.max(0, width - rects.w);
			int maxY = Math.max(0, height - taskbarHeight() - rects.h);
			searchDialogX =
				clamp((int)context.x() + draggingSearchOffsetX, 0, maxX);
			searchDialogY =
				clamp((int)context.y() + draggingSearchOffsetY, 0, maxY);
			return true;
		}
		
		if(draggingWindow != null)
		{
			int maxX = Math.max(0, width - draggingWindow.getWidth());
			int maxY = Math.max(0,
				height - taskbarHeight() - draggingWindow.getHeight());
			draggingWindow
				.setX(clamp((int)context.x() + draggingOffsetX, 0, maxX));
			draggingWindow
				.setY(clamp((int)context.y() + draggingOffsetY, 0, maxY));
			return true;
		}
		
		if(sliderDrag != null)
		{
			updateSliderFromMouse(sliderDrag, context.x());
			return true;
		}
		
		if(settingScrollDrag != null)
		{
			updateWindowScrollFromDrag(settingScrollDrag, context.y());
			return true;
		}
		
		if(uiScrollbarDrag != null)
		{
			updateUiScrollbarFromDrag(uiScrollbarDrag, context.y());
			return true;
		}
		
		if(settingsOpacityDrag)
		{
			updateDesktopOpacityFromMouse(context.x());
			return true;
		}
		
		if(settingsListItemsDrag)
		{
			updateAllProgramsVisibleItemsFromMouse(context.x());
			return true;
		}
		
		return super.mouseDragged(context, dragX, dragY);
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent context)
	{
		draggingSearchDialog = false;
		draggingWindow = null;
		sliderDrag = null;
		settingScrollDrag = null;
		uiScrollbarDrag = null;
		settingsOpacityDrag = false;
		settingsListItemsDrag = false;
		return super.mouseReleased(context);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount)
	{
		if(searchDialogOpen && !searchDialogMinimized
			&& isInsideSearchDialog(mouseX, mouseY))
		{
			SearchDialogRects searchRects = getSearchDialogRects();
			if(isInsideRect(mouseX, mouseY, searchRects.resultsX,
				searchRects.resultsY, searchRects.resultsW,
				searchRects.resultsH))
			{
				int max = Math.max(0,
					getSearchResultRows(getFilteredSearchFeatures()) * 22
						- searchRects.resultsH);
				searchResultsScroll = clamp(
					searchResultsScroll - (int)Math.round(verticalAmount * 20),
					0, max);
				return true;
			}
			
			searchScroll = clamp(
				searchScroll - (int)Math.round(verticalAmount * 18), 0, 120);
			return true;
		}
		
		if(startMenuOpen)
		{
			StartMenuRects rects = getStartMenuRects();
			int favX = rects.x + 6;
			int favTop = rects.y + rects.headerHeight + 8;
			int favBottom = rects.y + rects.height - rects.footerHeight - 32;
			if(isInsideRect(mouseX, mouseY, favX, favTop, rects.leftWidth - 12,
				favBottom - favTop))
			{
				int max = Math.max(0,
					getFavoriteHacks().size() * 23 - (favBottom - favTop));
				favoritesScroll = clamp(
					favoritesScroll - (int)Math.round(verticalAmount * 20), 0,
					max);
				return true;
			}
			
			if(allProgramsOpen)
			{
				AllProgramsRects allPrograms = getAllProgramsRects(rects);
				Map<String, List<Hack>> byCategory = getHacksByCategory();
				
				int moduleY = allPrograms.categoryY;
				if(hoveredCategory != null
					&& byCategory.containsKey(hoveredCategory))
					moduleY = allPrograms.categoryY + 4
						+ new ArrayList<>(byCategory.keySet())
							.indexOf(hoveredCategory) * 20;
				if(hoveredCategory != null && isInsideRect(mouseX, mouseY,
					allPrograms.moduleX, moduleY, allPrograms.moduleW,
					allPrograms.moduleHVisible))
				{
					List<Hack> hacks = getCategoryModules(hoveredCategory);
					int max = Math.max(0,
						hacks.size() * 20 - (allPrograms.moduleHVisible - 8));
					allProgramsModuleScroll = clamp(allProgramsModuleScroll
						- (int)Math.round(verticalAmount * 20), 0, max);
					return true;
				}
			}
		}
		
		List<XpModuleWindow> windows = windowManager.getWindows();
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			XpModuleWindow window = windows.get(i);
			if(window.isMinimized())
				continue;
			
			WindowRects rects = computeWindowRects(window);
			if(!isInsideRect(mouseX, mouseY, rects.contentX, rects.settingsY,
				rects.contentWidth, rects.settingsHeight))
				continue;
			
			List<SettingNode> nodes = collectSettings(window);
			int maxScroll = Math.max(0,
				computeTotalSettingsHeight(nodes) - rects.settingsHeight);
			int delta = (int)Math.round(verticalAmount * 22);
			window.setScrollOffset(
				clamp(window.getScrollOffset() - delta, 0, maxScroll));
			return true;
		}
		
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount,
			verticalAmount);
	}
	
	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY,
		float deltaTicks)
	{
		// Disable blur to preserve XP desktop look.
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		renderDesktop(context);
		
		if(searchDialogOpen && !searchDialogMinimized && !searchDialogFocused)
			renderSearchDialog(context, mouseX, mouseY);
		
		XpModuleWindow focused = windowManager.getFocusedWindow();
		for(XpModuleWindow window : windowManager.getWindows())
			if(!window.isMinimized())
			{
				if(window == focused)
					continue;
				renderWindow(context, window, mouseX, mouseY);
			}
		if(focused != null && !focused.isMinimized())
			renderWindow(context, focused, mouseX, mouseY);
		
		if(searchDialogOpen && !searchDialogMinimized && searchDialogFocused)
			renderSearchDialog(context, mouseX, mouseY);
		
		if(startMenuOpen)
			renderStartMenu(context, mouseX, mouseY);
		
		if(xpguiSettingsOpen)
			renderSettingsDialog(context, mouseX, mouseY);
		
		renderTaskbar(context, mouseX, mouseY);
	}
	
	private void renderDesktop(GuiGraphics context)
	{
		ensureXpguiSettingsLoaded();
		uploadPendingDesktopTexture();
		if(desktopBackgroundMode == DesktopBackgroundMode.NONE)
			return;
		
		if(desktopBackgroundMode == DesktopBackgroundMode.IMAGE)
		{
			ensureDesktopWallpaperRequested();
			Identifier texture = desktopTexture != null ? XP_DESKTOP_TEX_ID
				: XP_DEFAULT_BG_TEX_ID;
			context.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0, 0,
				width, height - taskbarHeight(), width,
				height - taskbarHeight(),
				((desktopOpacity & 0xFF) << 24) | 0xFFFFFF);
			return;
		}
		
		int alpha = desktopOpacity & 0xFF;
		context.fillGradient(0, 0, width, height - taskbarHeight(),
			withAlpha(0xFF0A0A0A, alpha), withAlpha(0xFF2A2A2A, alpha));
		context.fillGradient(0, 0, width, (int)(height * 0.28F),
			withAlpha(0x26000000, alpha), 0x00000000);
	}
	
	private void renderTaskbar(GuiGraphics context, int mouseX, int mouseY)
	{
		int barH = taskbarHeight();
		int y1 = height - barH;
		if(win2000Theme)
		{
			context.fillGradient(0, y1, width, height, 0xFFB9B9B9, 0xFF8D8D8D);
			context.fill(0, y1, width, y1 + 1, XpGuiTheme.TASKBAR_HIGHLIGHT);
			context.fill(0, y1 - 1, width, y1, XpGuiTheme.TASKBAR_BORDER);
			context.fill(0, y1 + 1, width, y1 + 2, 0x22000000);
		}else
		{
			context.fillGradient(0, y1, width, height, 0xFF3B90EC, 0xFF245FD5);
			context.fill(0, y1, width, y1 + 1, 0xFFB8D7FF);
			context.fill(0, y1 + 1, width, y1 + 2, 0x804F99F2);
			context.fill(0, y1 - 1, width, y1, 0xFF154A9C);
		}
		
		int startX = getStartButtonX();
		int startY = getStartButtonY();
		int startH = getStartButtonHeight();
		int startW = getStartButtonWidth();
		boolean hoverStart =
			isInsideRect(mouseX, mouseY, startX, startY, startW, startH);
		int startTop;
		int startBottom;
		if(win2000Theme)
		{
			startTop = hoverStart || startMenuOpen ? 0xFFD1D1D1 : 0xFFC0C0C0;
			startBottom = hoverStart || startMenuOpen ? 0xFF9A9A9A : 0xFF8A8A8A;
		}else
		{
			if(startMenuOpen)
			{
				// XP pressed look: darker overall with subtle bottom lift.
				startTop = 0xFF2C9D37;
				startBottom = 0xFF167A22;
			}else if(hoverStart)
			{
				startTop = 0xFF58D765;
				startBottom = 0xFF209A2C;
			}else
			{
				startTop = 0xFF4DCF59;
				startBottom = 0xFF1A9127;
			}
		}
		int capRadiusY = Math.max(1, (startH - 1) / 2);
		int rightRadiusX =
			win2000Theme ? capRadiusY : Math.max(3, capRadiusY / 3);
		int rightCenterX = startX + startW - rightRadiusX - 1;
		int centerY = startY + capRadiusY;
		for(int i = 0; i < startH; i++)
		{
			int py = startY + i;
			int dy = py - centerY;
			double ny = dy / (double)capRadiusY;
			double inside = Math.max(0.0, 1.0 - ny * ny);
			int rightDx = (int)Math.round(rightRadiusX * Math.sqrt(inside));
			int xRight = Math.min(startX + startW - 1, rightCenterX + rightDx);
			double t = i / (double)Math.max(1, startH - 1);
			int rowColor = blendColor(startTop, startBottom, t);
			if(!win2000Theme)
			{
				if(startMenuOpen)
				{
					rowColor = blendColor(rowColor, 0xFF000000, 0.14);
				}else
				{
					double gloss = Math.max(0.0, 0.50 - t) * 0.18;
					rowColor = blendColor(rowColor, 0xFFFFFFFF, gloss);
				}
			}
			context.fill(startX, py, xRight + 1, py + 1, rowColor);
		}
		int startIconY = startY + (startH - 16) / 2;
		int startIconX = startX + 7;
		context.renderItem(new ItemStack(startIconBlock), startIconX,
			startIconY);
		int startTextX = startX + 32;
		int startTextY = startY + (startH - 8) / 2;
		context.drawString(minecraft.font, "Start", startTextX, startTextY,
			0xFFFFFFFF, false);
		if(!win2000Theme)
		{
			// Redraw the taskbar highlight lines after Start so the seam
			// stays continuous while the Start menu is open/pressed.
			context.fill(0, y1, width, y1 + 1, 0xFFB8D7FF);
			context.fill(0, y1 + 1, width, y1 + 2, 0x804F99F2);
		}
		
		renderTaskbarButtons(context, mouseX, mouseY);
		
		int trayX = width - TASKBAR_RIGHT_WIDTH;
		int trayTop = win2000Theme ? 0xFFCECECE : 0xAA92C5FF;
		int trayBottom = win2000Theme ? 0xFF9D9D9D : 0xAA4F83E7;
		context.fillGradient(trayX, y1 + 2, width - 4, height - 2, trayTop,
			trayBottom);
		context.fill(trayX, y1 + 2, trayX + 1, height - 2, 0x70FFFFFF);
		
		int cogX = trayX + 8;
		int cogY = y1 + (barH - 16) / 2;
		boolean hoverCog =
			isInsideRect(mouseX, mouseY, cogX - 2, cogY - 2, 20, 20);
		XpGuiTheme.drawBevelRect(context, cogX - 2, cogY - 2, cogX + 18,
			cogY + 18, hoverCog ? 0xFF7AA9EB : 0xFF5A8CD9, 0x90FFFFFF,
			0xFF2C57A7);
		context.renderItem(new ItemStack(Items.WRITABLE_BOOK), cogX, cogY);
		
		String time = java.time.LocalTime.now()
			.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"));
		int timeW = minecraft.font.width(time);
		int timeColor = win2000Theme ? 0xFF10243F : 0xFFFFFFFF;
		context.drawString(minecraft.font, time, width - 10 - timeW,
			y1 + (barH - 8) / 2, timeColor, false);
	}
	
	private void renderTaskbarButtons(GuiGraphics context, int mouseX,
		int mouseY)
	{
		List<XpModuleWindow> windows = windowManager.getWindows();
		
		int areaX = getStartButtonX() + getStartButtonWidth() + 8;
		int areaY = height - taskbarHeight() + 4;
		int areaW = width - areaX - TASKBAR_RIGHT_WIDTH - 6;
		if(areaW <= 20)
			return;
		
		boolean showSearchButton = searchDialogOpen;
		int count = windows.size() + (showSearchButton ? 1 : 0);
		if(count <= 0)
			return;
		int buttonW = clamp(areaW / count, 88, 180);
		int maxButtons = Math.max(1, areaW / buttonW);
		int skip = Math.max(0, count - maxButtons);
		
		for(int i = skip; i < windows.size(); i++)
		{
			XpModuleWindow window = windows.get(i);
			int slot = i - skip;
			int x = areaX + slot * buttonW;
			int y = areaY;
			int h = taskbarHeight() - 8;
			boolean focused = !searchDialogFocused
				&& window == windowManager.getFocusedWindow();
			boolean hovered =
				isInsideRect(mouseX, mouseY, x, y, buttonW - 4, h);
			
			int top = focused ? 0xFF2F67C7 : 0xFF4A88E6;
			int bottom = focused ? 0xFF1E4D9A : 0xFF2A5DB7;
			if(win2000Theme)
			{
				top = focused ? 0xFF9A9A9A : 0xFFC7C7C7;
				bottom = focused ? 0xFF7F7F7F : 0xFF9B9B9B;
			}
			if(hovered)
			{
				top = win2000Theme ? 0xFFE2E2E2 : 0xFF81B9FF;
				bottom = win2000Theme ? 0xFFB1B1B1 : 0xFF3A71CC;
			}
			context.fillGradient(x, y, x + buttonW - 4, y + h, top, bottom);
			if(focused)
			{
				context.fill(x, y, x + buttonW - 4, y + 1,
					win2000Theme ? 0xFF666666 : 0xA0143B7A);
				context.fill(x, y + h - 1, x + buttonW - 4, y + h, 0x70FFFFFF);
			}else
			{
				context.fill(x, y, x + buttonW - 4, y + 1, 0x70FFFFFF);
				context.fill(x, y + h - 1, x + buttonW - 4, y + h,
					win2000Theme ? 0xFF777777 : 0xA0184B95);
			}
			
			String title = window.getTitle();
			renderHackStatusIcon(context, x + 4, y + (h - 14) / 2,
				window.getHack());
			String clipped = clipText(title, buttonW - 36);
			context.drawString(minecraft.font, clipped, x + 22, y + (h - 8) / 2,
				win2000Theme ? 0xFF10243F : 0xFFFFFFFF, false);
		}
		
		if(showSearchButton)
		{
			int searchIndex = windows.size();
			if(searchIndex >= skip)
			{
				int slot = searchIndex - skip;
				int x = areaX + slot * buttonW;
				int y = areaY;
				int h = taskbarHeight() - 8;
				boolean focused = searchDialogFocused && !searchDialogMinimized;
				boolean hovered =
					isInsideRect(mouseX, mouseY, x, y, buttonW - 4, h);
				
				int top = focused ? 0xFF2F67C7 : 0xFF4A88E6;
				int bottom = focused ? 0xFF1E4D9A : 0xFF2A5DB7;
				if(win2000Theme)
				{
					top = focused ? 0xFF9A9A9A : 0xFFC7C7C7;
					bottom = focused ? 0xFF7F7F7F : 0xFF9B9B9B;
				}
				if(hovered)
				{
					top = win2000Theme ? 0xFFE2E2E2 : 0xFF81B9FF;
					bottom = win2000Theme ? 0xFFB1B1B1 : 0xFF3A71CC;
				}
				context.fillGradient(x, y, x + buttonW - 4, y + h, top, bottom);
				if(focused)
				{
					context.fill(x, y, x + buttonW - 4, y + 1,
						win2000Theme ? 0xFF666666 : 0xA0143B7A);
					context.fill(x, y + h - 1, x + buttonW - 4, y + h,
						0x70FFFFFF);
				}else
				{
					context.fill(x, y, x + buttonW - 4, y + 1, 0x70FFFFFF);
					context.fill(x, y + h - 1, x + buttonW - 4, y + h,
						win2000Theme ? 0xFF777777 : 0xA0184B95);
				}
				context.renderItem(new ItemStack(Items.COMPASS), x + 4,
					y + (h - 16) / 2);
				context.drawString(minecraft.font,
					clipText("Search", buttonW - 36), x + 22, y + (h - 8) / 2,
					win2000Theme ? 0xFF10243F : 0xFFFFFFFF, false);
			}
		}
	}
	
	private void renderStartMenu(GuiGraphics context, int mouseX, int mouseY)
	{
		StartMenuRects rects = getStartMenuRects();
		context.fill(rects.x + 3, rects.y + 4, rects.x + rects.width + 7,
			rects.y + rects.height + 8, 0x50000000);
		context.fill(rects.x - 1, rects.y - 1, rects.x + rects.width + 1,
			rects.y + rects.height + 1, 0xB0000000);
		XpGuiTheme.drawBevelRect(context, rects.x, rects.y,
			rects.x + rects.width, rects.y + rects.height, 0xFFEFF5FE,
			0xFF8EB7EA, 0xFF0B3E91);
		
		int menuHeaderTop =
			win2000Theme ? 0xFFD6D6D6 : XpGuiTheme.MENU_HEADER_TOP;
		int menuHeaderBottom =
			win2000Theme ? 0xFFA5A5A5 : XpGuiTheme.MENU_HEADER_BOTTOM;
		context.fillGradient(rects.x + 1, rects.y + 1,
			rects.x + rects.width - 1, rects.y + rects.headerHeight,
			menuHeaderTop, menuHeaderBottom);
		context.fill(rects.x + 1, rects.y + 1, rects.x + rects.width - 1,
			rects.y + 2, 0x80FFFFFF);
		
		String playerName = minecraft.getUser().getName();
		drawPlayerHead(context, rects.x + 9, rects.y + 11, 32);
		context.drawString(minecraft.font, playerName, rects.x + 48,
			rects.y + 24, 0xFFFFFFFF, false);
		
		int bodyTop = rects.y + rects.headerHeight;
		int leftColor = win2000Theme ? 0xFFECECEC : XpGuiTheme.MENU_LEFT;
		int rightColor = win2000Theme ? 0xFFE0E0E0 : XpGuiTheme.MENU_RIGHT;
		context.fill(rects.x + 1, bodyTop, rects.x + rects.leftWidth,
			rects.y + rects.height - rects.footerHeight - 1, leftColor);
		context.fill(rects.x + rects.leftWidth, bodyTop,
			rects.x + rects.width - 1,
			rects.y + rects.height - rects.footerHeight - 1, rightColor);
		context.fill(rects.x + rects.leftWidth - 1, bodyTop,
			rects.x + rects.leftWidth,
			rects.y + rects.height - rects.footerHeight - 1, 0xFF8AA8CF);
		
		int footerTop = win2000Theme ? 0xFFC7C7C7 : XpGuiTheme.TASKBAR_TOP;
		int footerBottom =
			win2000Theme ? 0xFF9E9E9E : XpGuiTheme.TASKBAR_BOTTOM;
		context.fillGradient(rects.x + 1,
			rects.y + rects.height - rects.footerHeight,
			rects.x + rects.width - 1, rects.y + rects.height - 1, footerTop,
			footerBottom);
		context.fill(rects.x + 1, rects.y + rects.height - rects.footerHeight,
			rects.x + rects.width - 1,
			rects.y + rects.height - rects.footerHeight + 1, 0x80FFFFFF);
		
		renderStartMenuFooterActions(context, rects, mouseX, mouseY);
		
		renderStartMenuFavorites(context, rects, mouseX, mouseY);
		renderStartMenuRightPanel(context, rects, mouseX, mouseY);
		
		if(allProgramsOpen)
			renderAllProgramsMenus(context, rects, mouseX, mouseY);
	}
	
	private void renderStartMenuFavorites(GuiGraphics context,
		StartMenuRects rects, int mouseX, int mouseY)
	{
		List<Hack> favorites = getFavoriteHacks();
		int x1 = rects.x + 6;
		int contentTop = rects.y + rects.headerHeight + 8;
		int contentBottom = rects.y + rects.height - rects.footerHeight - 32;
		int viewportH = contentBottom - contentTop;
		
		context.enableScissor(x1, contentTop, x1 + rects.leftWidth - 12,
			contentBottom);
		try
		{
			int rowY = contentTop - favoritesScroll;
			if(favorites.isEmpty())
			{
				context.drawString(minecraft.font, "No favorites yet.", x1 + 6,
					rowY + 8, 0xFF4C6892, false);
			}else
				for(Hack hack : favorites)
				{
					int rowH = 22;
					if(rowY + rowH < contentTop)
					{
						rowY += rowH + 1;
						continue;
					}
					if(rowY > contentBottom)
						break;
					
					boolean hovered = isInsideRect(mouseX, mouseY, x1, rowY,
						rects.leftWidth - 12, rowH);
					if(hovered)
						context.fill(x1, rowY, x1 + rects.leftWidth - 12,
							rowY + rowH, 0xFFCCE2FF);
					
					renderHackStatusIcon(context, x1 + 5, rowY + 5, hack);
					context.drawString(minecraft.font, hack.getName(), x1 + 22,
						rowY + 7, 0xFF103B76, false);
					rowY += rowH + 1;
				}
		}finally
		{
			context.disableScissor();
		}
		
		int favMaxScroll = Math.max(0, favorites.size() * 23 - viewportH);
		favoritesScroll = clamp(favoritesScroll, 0, favMaxScroll);
		if(favMaxScroll > 0)
			renderXpScrollbar(context, x1 + rects.leftWidth - 17, contentTop,
				10, viewportH, favoritesScroll, favMaxScroll);
		
		int allProgramsY = rects.y + rects.height - rects.footerHeight - 28;
		boolean hoverAllPrograms = isInsideRect(mouseX, mouseY, x1,
			allProgramsY, rects.leftWidth - 12, 22);
		int allProgramsFill =
			hoverAllPrograms || allProgramsOpen ? 0xFF2E73CC : 0xFFE8F2FF;
		int allProgramsText =
			hoverAllPrograms || allProgramsOpen ? 0xFFFFFFFF : 0xFF0E356A;
		XpGuiTheme.drawBevelRect(context, x1, allProgramsY,
			x1 + rects.leftWidth - 12, allProgramsY + 22, allProgramsFill,
			hoverAllPrograms ? 0xFFAFCCF1 : 0xFFFFFFFF,
			hoverAllPrograms ? 0xFF1F5EA8 : 0xFF90ADD4);
		context.drawString(minecraft.font, "All Programs", x1 + 8,
			allProgramsY + 7, allProgramsText, false);
		context.drawString(minecraft.font, "\u25B6", x1 + rects.leftWidth - 26,
			allProgramsY + 7, allProgramsText, false);
	}
	
	private void renderStartMenuRightPanel(GuiGraphics context,
		StartMenuRects rects, int mouseX, int mouseY)
	{
		String[] entries = {"Wurst Options", "Preset Manager",
			"Keybind Manager", "Alt Manager", "Search"};
		Block[] icons = {Blocks.CRAFTING_TABLE, Blocks.SMITHING_TABLE,
			Blocks.GRINDSTONE, Blocks.END_PORTAL_FRAME, Blocks.CRAFTING_TABLE};
		int x = rects.x + rects.leftWidth + 10;
		int y = rects.y + rects.headerHeight + 10;
		int rowH = 24;
		for(int i = 0; i < entries.length; i++)
		{
			String entry = entries[i];
			boolean hovered = isInsideRect(mouseX, mouseY, x - 4, y - 2,
				rects.width - rects.leftWidth - 18, rowH - 2);
			if(hovered)
				context.fill(x - 4, y - 2,
					x + rects.width - rects.leftWidth - 26, y + rowH - 2,
					0x80C4DAF6);
			ItemStack icon =
				i == 4 ? new ItemStack(Items.COMPASS) : new ItemStack(icons[i]);
			context.renderItem(icon, x, y + 1);
			context.drawString(minecraft.font, entry, x + 22, y + 5, 0xFF163B70,
				false);
			y += rowH;
		}
	}
	
	private void renderStartMenuFooterActions(GuiGraphics context,
		StartMenuRects rects, int mouseX, int mouseY)
	{
		int buttonW = 102;
		int buttonH = 22;
		int buttonX = rects.x + rects.width - buttonW - 12;
		int buttonY = rects.y + rects.height - rects.footerHeight + 6;
		boolean hovered =
			isInsideRect(mouseX, mouseY, buttonX, buttonY, buttonW, buttonH);
		int fillTop = hovered ? 0xFF3F88E6 : 0xFF2D72CF;
		int fillBottom = hovered ? 0xFF225BAF : 0xFF1E4A94;
		if(win2000Theme)
		{
			fillTop = hovered ? 0xFFD9D9D9 : 0xFFC4C4C4;
			fillBottom = hovered ? 0xFFB0B0B0 : 0xFF9A9A9A;
		}
		context.fillGradient(buttonX, buttonY, buttonX + buttonW,
			buttonY + buttonH, fillTop, fillBottom);
		context.fill(buttonX, buttonY, buttonX + buttonW, buttonY + 1,
			0x80FFFFFF);
		context.fill(buttonX, buttonY + buttonH - 1, buttonX + buttonW,
			buttonY + buttonH, 0x7016336C);
		context.renderItem(new ItemStack(Blocks.RED_WOOL), buttonX + 4,
			buttonY + 3);
		context.drawString(minecraft.font, "Disconnect", buttonX + 24,
			buttonY + 7, 0xFFFFFFFF, false);
	}
	
	private void renderAllProgramsMenus(GuiGraphics context,
		StartMenuRects rects, int mouseX, int mouseY)
	{
		Map<String, List<Hack>> byCategory = getHacksByCategory();
		if(byCategory.isEmpty())
			return;
		
		AllProgramsRects ap = getAllProgramsRects(rects);
		context.fill(ap.categoryX - 1, ap.categoryY - 1,
			ap.categoryX + ap.categoryW + 1, ap.categoryY + ap.categoryH + 1,
			0xA0000000);
		XpGuiTheme.drawBevelRect(context, ap.categoryX, ap.categoryY,
			ap.categoryX + ap.categoryW, ap.categoryY + ap.categoryH,
			0xFFF7FBFF, 0xFFBED4F0, 0xFF5C82B8);
		
		String newlyHoveredCategory = null;
		int rowY = ap.categoryY + 4;
		for(String category : byCategory.keySet())
		{
			boolean hovered = isInsideRect(mouseX, mouseY, ap.categoryX + 2,
				rowY, ap.categoryW - 4, 18);
			if(hovered)
				newlyHoveredCategory = category;
			if(hovered || category.equals(hoveredCategory))
				context.fill(ap.categoryX + 2, rowY,
					ap.categoryX + ap.categoryW - 2, rowY + 18, 0xFF2E73CC);
			context.drawString(minecraft.font, category, ap.categoryX + 10,
				rowY + 5, hovered || category.equals(hoveredCategory)
					? 0xFFFFFFFF : 0xFF143A71,
				false);
			context.drawString(minecraft.font, "\u25B6",
				ap.categoryX + ap.categoryW - 14, rowY + 5,
				hovered || category.equals(hoveredCategory) ? 0xFFFFFFFF
					: 0xFF143A71,
				false);
			rowY += 20;
		}
		
		if(newlyHoveredCategory != null)
		{
			hoveredCategory = newlyHoveredCategory;
			allProgramsModuleScroll = 0;
		}
		if(hoveredCategory == null || !byCategory.containsKey(hoveredCategory))
			return;
		
		List<Hack> hacks = getCategoryModules(hoveredCategory);
		int totalModuleH = hacks.size() * 20 + 8;
		int moduleVisibleH = ap.moduleHVisible;
		int moduleY = ap.categoryY + 4
			+ new ArrayList<>(byCategory.keySet()).indexOf(hoveredCategory)
				* 20;
		moduleY =
			clamp(moduleY, 4, height - taskbarHeight() - moduleVisibleH - 2);
		context.fill(ap.moduleX - 1, moduleY - 1, ap.moduleX + ap.moduleW + 1,
			moduleY + moduleVisibleH + 1, 0xA0000000);
		XpGuiTheme.drawBevelRect(context, ap.moduleX, moduleY,
			ap.moduleX + ap.moduleW, moduleY + moduleVisibleH, 0xFFFAFDFF,
			0xFFBED4F0, 0xFF5C82B8);
		
		int moduleMaxScroll = Math.max(0, totalModuleH - moduleVisibleH);
		allProgramsModuleScroll =
			clamp(allProgramsModuleScroll, 0, moduleMaxScroll);
		
		context.enableScissor(ap.moduleX + 1, moduleY + 1,
			ap.moduleX + ap.moduleW - 11, moduleY + moduleVisibleH - 1);
		try
		{
			int listY = moduleY + 4 - allProgramsModuleScroll;
			for(Hack hack : hacks)
			{
				if(listY + 18 < moduleY)
				{
					listY += 20;
					continue;
				}
				if(listY > moduleY + moduleVisibleH)
					break;
				
				boolean hovered = isInsideRect(mouseX, mouseY, ap.moduleX + 2,
					listY, ap.moduleW - 14, 18);
				if(hovered)
					context.fill(ap.moduleX + 2, listY,
						ap.moduleX + ap.moduleW - 12, listY + 18, 0xFF2E73CC);
				renderHackStatusIcon(context, ap.moduleX + 5, listY + 4, hack);
				context.drawString(minecraft.font, hack.getName(),
					ap.moduleX + 22, listY + 5,
					hovered ? 0xFFFFFFFF : 0xFF143A71, false);
				listY += 20;
			}
		}finally
		{
			context.disableScissor();
		}
		
		if(moduleMaxScroll > 0)
			renderXpScrollbar(context, ap.moduleX + ap.moduleW - 12,
				moduleY + 2, 10, moduleVisibleH - 4, allProgramsModuleScroll,
				moduleMaxScroll);
	}
	
	private void renderWindow(GuiGraphics context, XpModuleWindow window,
		int mouseX, int mouseY)
	{
		WindowRects rects = computeWindowRects(window);
		boolean focused =
			!searchDialogFocused && window == windowManager.getFocusedWindow();
		
		XpGuiTheme.drawWindowFrame(context, window.getX(), window.getY(),
			window.getX() + window.getWidth(),
			window.getY() + window.getHeight());
		XpGuiTheme.drawTitleBar(context, window.getX() + 1, window.getY() + 1,
			window.getX() + window.getWidth() - 1,
			window.getY() + TITLE_BAR_HEIGHT - 1, focused);
		
		renderHackStatusIcon(context, window.getX() + 6, window.getY() + 4,
			window.getHack());
		context.drawString(minecraft.font, window.getTitle(),
			window.getX() + 24, window.getY() + 7, 0xFFFFFFFF, false);
		
		renderWindowButtons(context, window, mouseX, mouseY);
		renderWindowToggleRow(context, window, rects, mouseX, mouseY);
		renderWindowSettings(context, window, rects, mouseX, mouseY);
	}
	
	private void renderWindowButtons(GuiGraphics context, XpModuleWindow window,
		int mouseX, int mouseY)
	{
		int buttonSize = 14;
		int btnY = window.getY() + 4;
		int closeX = window.getX() + window.getWidth() - 6 - buttonSize;
		int minX = closeX - 2 - buttonSize;
		
		boolean hoverMin =
			isInsideRect(mouseX, mouseY, minX, btnY, buttonSize, buttonSize);
		boolean hoverClose =
			isInsideRect(mouseX, mouseY, closeX, btnY, buttonSize, buttonSize);
		
		XpGuiTheme.drawBevelRect(context, minX, btnY, minX + buttonSize,
			btnY + buttonSize, hoverMin ? 0xFF8DB8F0 : 0xFF6D9DE6, 0xB0FFFFFF,
			0xFF2A4F98);
		context.fill(minX + 3, btnY + 9, minX + buttonSize - 3, btnY + 10,
			0xFFFFFFFF);
		
		XpGuiTheme.drawBevelRect(context, closeX, btnY, closeX + buttonSize,
			btnY + buttonSize, hoverClose ? 0xFFE56E58 : 0xFFDA4E39, 0xA0FFFFFF,
			0xFF8C2A1E);
		context.drawString(minecraft.font, "x", closeX + 4, btnY + 3,
			0xFFFFFFFF, false);
	}
	
	private void renderWindowToggleRow(GuiGraphics context,
		XpModuleWindow window, WindowRects rects, int mouseX, int mouseY)
	{
		Hack hack = window.getHack();
		int rowX = rects.contentX;
		int rowY = rects.contentY;
		int rowW = rects.contentWidth;
		int rowH = 24;
		boolean hovered = isInsideRect(mouseX, mouseY, rowX, rowY, rowW, rowH);
		
		int fill = hack.isEnabled() ? 0xFFDFF5DF : 0xFFF5E4E4;
		if(hovered)
			fill = hack.isEnabled() ? 0xFFCBEBCB : 0xFFEFD3D3;
		XpGuiTheme.drawBevelRect(context, rowX, rowY, rowX + rowW, rowY + rowH,
			fill, 0xFFFFFFFF, 0xFF8EA5C1);
		
		String state = hack.isEnabled() ? "Enabled" : "Disabled";
		context.drawString(minecraft.font, "State:", rowX + 8, rowY + 8,
			0xFF20426F, false);
		context.drawString(minecraft.font, state + " (click to toggle)",
			rowX + 60, rowY + 8, hack.isEnabled() ? 0xFF187A25 : 0xFF9D1D1D,
			false);
	}
	
	private void renderWindowSettings(GuiGraphics context,
		XpModuleWindow window, WindowRects rects, int mouseX, int mouseY)
	{
		List<SettingNode> nodes = collectSettings(window);
		int totalHeight = computeTotalSettingsHeight(nodes);
		int maxScroll = Math.max(0, totalHeight - rects.settingsHeight);
		window.setScrollOffset(clamp(window.getScrollOffset(), 0, maxScroll));
		boolean hasScrollbar = maxScroll > 0;
		int scrollbarW = hasScrollbar ? 10 : 0;
		int viewW = rects.contentWidth - scrollbarW;
		
		context.fill(rects.contentX, rects.settingsY,
			rects.contentX + rects.contentWidth,
			rects.settingsY + rects.settingsHeight, XpGuiTheme.WINDOW_BODY_ALT);
		
		context.enableScissor(rects.contentX, rects.settingsY,
			rects.contentX + viewW, rects.settingsY + rects.settingsHeight);
		try
		{
			int y = rects.settingsY - window.getScrollOffset();
			for(int i = 0; i < nodes.size(); i++)
			{
				SettingNode node = nodes.get(i);
				int rowH = getRowHeight(node.setting());
				if(y + rowH < rects.settingsY)
				{
					y += rowH + SETTING_ROW_GAP;
					continue;
				}
				if(y > rects.settingsY + rects.settingsHeight)
					break;
				
				renderSettingRow(context, node, rects.contentX, y, viewW,
					mouseX, mouseY, i);
				y += rowH + SETTING_ROW_GAP;
			}
		}finally
		{
			context.disableScissor();
		}
		
		if(hasScrollbar)
			renderXpScrollbar(context, rects.contentX + viewW, rects.settingsY,
				10, rects.settingsHeight, window.getScrollOffset(), maxScroll);
	}
	
	private void renderSettingRow(GuiGraphics context, SettingNode node, int x,
		int y, int width, int mouseX, int mouseY, int rowIndex)
	{
		Setting setting = node.setting();
		int rowH = getRowHeight(setting);
		if(setting instanceof SpacerSetting)
			return;
		
		int rowColor = rowIndex % 2 == 0 ? 0xFFF6FAFF : 0xFFEAF2FD;
		boolean hovered = isInsideRect(mouseX, mouseY, x, y, width, rowH);
		if(hovered)
			rowColor = 0xFFD8E8FC;
		XpGuiTheme.drawBevelRect(context, x, y, x + width, y + rowH, rowColor,
			0xFFFFFFFF, 0xFF9DB6D6);
		
		int labelX = x + 8 + node.depth() * 10;
		String label = setting.getName();
		if(setting instanceof SettingGroup)
		{
			String arrow = node.expanded() ? "\u25BC " : "\u25B6 ";
			label = arrow + label;
		}
		context.drawString(minecraft.font, label, labelX, y + (rowH - 8) / 2,
			0xFF1A3E73, false);
		
		int controlX = x + width / 2;
		int controlW = width / 2 - 8;
		renderSettingControl(context, setting, controlX, y + 3, controlW,
			rowH - 6, hovered);
	}
	
	private void renderSettingControl(GuiGraphics context, Setting setting,
		int x, int y, int width, int height, boolean hovered)
	{
		if(setting instanceof CheckboxSetting checkbox)
		{
			int boxSize = Math.min(height, 14);
			int boxX = x + width - boxSize - 6;
			int boxY = y + (height - boxSize) / 2;
			XpGuiTheme.drawBevelRect(context, boxX, boxY, boxX + boxSize,
				boxY + boxSize, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF5E7FAE);
			if(checkbox.isChecked())
				context.drawString(minecraft.font, "\u2713", boxX + 3, boxY + 2,
					0xFF1D5F12, false);
			return;
		}
		
		if(setting instanceof SliderSetting slider)
		{
			int railX = x + 10;
			int railW = Math.max(30, width - 62);
			int railY = y + height / 2 - 2;
			context.fill(railX, railY, railX + railW, railY + 4, 0xFFC7D8EC);
			context.fill(railX, railY, railX + 1, railY + 4, 0xFFFFFFFF);
			context.fill(railX, railY + 3, railX + railW, railY + 4,
				0xFF5E7FAE);
			
			int knobX = railX + (int)Math.round(slider.getPercentage() * railW);
			XpGuiTheme.drawBevelRect(context, knobX - 4, railY - 4, knobX + 4,
				railY + 8, 0xFFEDF5FF, 0xFFFFFFFF, 0xFF5E7FAE);
			
			String value = slider.getValueString();
			int valueW = minecraft.font.width(value);
			context.drawString(minecraft.font, value, x + width - valueW - 6,
				y + (height - 8) / 2, 0xFF234B7E, false);
			return;
		}
		
		if(setting instanceof TextFieldSetting text)
		{
			XpGuiTheme.drawBevelRect(context, x + 4, y, x + width - 4,
				y + height, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF6E8BB4);
			String shown = clipText(text.getValue(), width - 18);
			context.drawString(minecraft.font, shown, x + 8,
				y + (height - 8) / 2, 0xFF10315F, false);
			if(textSettingEditSession != null
				&& textSettingEditSession.setting == text)
			{
				if(textSettingEditSession.selectAll && !shown.isEmpty())
					context.fill(x + 8, y + 3,
						x + 8 + minecraft.font.width(shown), y + height - 3,
						0x55367CD6);
				context.fill(x + 8 + minecraft.font.width(shown), y + 3,
					x + 9 + minecraft.font.width(shown), y + height - 3,
					0xFF1A3868);
			}
			return;
		}
		
		String label = getSettingValueLabel(setting);
		XpGuiTheme.drawXpButton(context, minecraft.font, label, x + 4, y,
			x + width - 4, y + height, hovered, false);
	}
	
	private boolean handleTaskbarClick(double mouseX, double mouseY, int button)
	{
		int y1 = height - taskbarHeight();
		if(mouseY < y1)
			return false;
		
		int startX = getStartButtonX();
		int startY = getStartButtonY();
		int startH = getStartButtonHeight();
		int startW = getStartButtonWidth();
		if(isInsideRect(mouseX, mouseY, startX, startY, startW, startH))
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			{
				startMenuOpen = !startMenuOpen;
				allProgramsOpen = false;
				hoveredCategory = null;
				searchDialogFocused = false;
			}
			return true;
		}
		
		List<TaskbarButtonRect> buttons = getTaskbarButtonRects();
		for(TaskbarButtonRect btn : buttons)
			if(isInsideRect(mouseX, mouseY, btn.x(), btn.y(), btn.width(),
				btn.height()))
			{
				if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				{
					windowManager.toggleFromTaskbar(btn.window());
					searchDialogFocused = false;
				}
				return true;
			}
		
		if(searchDialogOpen)
		{
			int areaX = getStartButtonX() + getStartButtonWidth() + 8;
			int areaY = height - taskbarHeight() + 4;
			int areaW = width - areaX - TASKBAR_RIGHT_WIDTH - 6;
			int count = windowManager.getWindows().size() + 1;
			if(areaW > 20 && count > 0)
			{
				int buttonW = clamp(areaW / count, 88, 180);
				int maxButtons = Math.max(1, areaW / buttonW);
				int skip = Math.max(0, count - maxButtons);
				int searchIndex = windowManager.getWindows().size();
				if(searchIndex >= skip)
				{
					int slot = searchIndex - skip;
					int bx = areaX + slot * buttonW;
					int by = areaY;
					int bh = taskbarHeight() - 8;
					if(isInsideRect(mouseX, mouseY, bx, by, buttonW - 4, bh))
					{
						if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
						{
							if(!searchDialogMinimized && searchDialogFocused)
							{
								searchDialogMinimized = true;
								searchDialogFocused = false;
								searchFieldFocused = false;
							}else
							{
								searchDialogMinimized = false;
								searchDialogFocused = true;
								searchFieldFocused = true;
							}
						}
						return true;
					}
				}
			}
		}
		
		int trayX = width - TASKBAR_RIGHT_WIDTH;
		int cogX = trayX + 8;
		int cogY = y1 + (taskbarHeight() - 16) / 2;
		if(isInsideRect(mouseX, mouseY, cogX - 2, cogY - 2, 20, 20))
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			{
				xpguiSettingsOpen = !xpguiSettingsOpen;
				settingsUrlFocused = false;
				settingsStartIconFocused = false;
				startMenuOpen = false;
				allProgramsOpen = false;
			}
			return true;
		}
		
		return true;
	}
	
	private boolean handleStartMenuClick(double mouseX, double mouseY,
		int button)
	{
		if(button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return false;
		
		StartMenuRects rects = getStartMenuRects();
		if(!isInsideStartMenu(mouseX, mouseY))
			return false;
		searchDialogFocused = false;
		
		if(tryStartUiScrollbarDrag(mouseX, mouseY, ScrollbarTarget.FAVORITES))
			return true;
		if(allProgramsOpen && tryStartUiScrollbarDrag(mouseX, mouseY,
			ScrollbarTarget.ALLPROGRAMS_MODULES))
			return true;
		
		if(allProgramsOpen)
		{
			Map<String, List<Hack>> byCategory = getHacksByCategory();
			AllProgramsRects ap = getAllProgramsRects(rects);
			int rowY = ap.categoryY + 4;
			int idx = 0;
			String clickedCategory = null;
			for(String category : byCategory.keySet())
			{
				if(isInsideRect(mouseX, mouseY, ap.categoryX + 2,
					rowY + idx * 20, ap.categoryW - 4, 18))
				{
					clickedCategory = category;
					break;
				}
				idx++;
			}
			
			if(clickedCategory != null)
			{
				hoveredCategory = clickedCategory;
				allProgramsModuleScroll = 0;
				return true;
			}
			
			if(hoveredCategory != null)
			{
				List<Hack> hacks = getCategoryModules(hoveredCategory);
				if(!hacks.isEmpty())
				{
					int modY =
						ap.categoryY + 4 + new ArrayList<>(byCategory.keySet())
							.indexOf(hoveredCategory) * 20;
					int visibleH = ap.moduleHVisible;
					modY =
						clamp(modY, 4, height - taskbarHeight() - visibleH - 2);
					int listY = modY + 4 - allProgramsModuleScroll;
					for(int i = 0; i < hacks.size(); i++)
					{
						int iconX = ap.moduleX + 5;
						int iconY = listY + 4;
						if(listY + 18 >= modY && listY <= modY + visibleH
							&& isInsideRect(mouseX, mouseY, iconX, iconY, 14,
								14))
						{
							Hack hack = hacks.get(i);
							hack.setEnabled(!hack.isEnabled());
							return true;
						}
						
						if(listY + 18 >= modY && listY <= modY + visibleH
							&& isInsideRect(mouseX, mouseY, ap.moduleX + 2,
								listY, ap.moduleW - 14, 18))
						{
							openHackWindow(hacks.get(i));
							startMenuOpen = false;
							allProgramsOpen = false;
							hoveredCategory = null;
							return true;
						}
						listY += 20;
					}
				}
			}
		}
		
		int favX = rects.x + 6;
		int favTop = rects.y + rects.headerHeight + 8;
		int favBottom = rects.y + rects.height - rects.footerHeight - 32;
		int allProgramsY = rects.y + rects.height - rects.footerHeight - 28;
		if(isInsideRect(mouseX, mouseY, favX, allProgramsY,
			rects.leftWidth - 12, 22))
		{
			allProgramsOpen = !allProgramsOpen;
			if(allProgramsOpen)
				hoveredCategory = null;
			return true;
		}
		
		int favY = favTop - favoritesScroll;
		List<Hack> favorites = getFavoriteHacks();
		for(Hack hack : favorites)
		{
			if(isInsideRect(mouseX, mouseY, favX + 5, favY + 5, 14, 14))
			{
				hack.setEnabled(!hack.isEnabled());
				return true;
			}
			if(isInsideRect(mouseX, mouseY, favX, favY, rects.leftWidth - 12,
				22))
			{
				openHackWindow(hack);
				startMenuOpen = false;
				allProgramsOpen = false;
				hoveredCategory = null;
				return true;
			}
			favY += 23;
			if(favY > favBottom)
				break;
		}
		
		int rightX = rects.x + rects.leftWidth + 6;
		int rightY = rects.y + rects.headerHeight + 8;
		if(isInsideRect(mouseX, mouseY, rightX, rightY,
			rects.width - rects.leftWidth - 12, 24))
		{
			minecraft.setScreen(new WurstOptionsScreen(this));
			return true;
		}
		rightY += 24;
		if(isInsideRect(mouseX, mouseY, rightX, rightY,
			rects.width - rects.leftWidth - 12, 24))
		{
			minecraft.setScreen(new PresetManagerScreen(this));
			return true;
		}
		rightY += 24;
		if(isInsideRect(mouseX, mouseY, rightX, rightY,
			rects.width - rects.leftWidth - 12, 24))
		{
			minecraft.setScreen(new KeybindManagerScreen(this));
			return true;
		}
		rightY += 24;
		if(isInsideRect(mouseX, mouseY, rightX, rightY,
			rects.width - rects.leftWidth - 12, 24))
		{
			minecraft.setScreen(new AltManagerScreen(this,
				WurstClient.INSTANCE.getAltManager()));
			return true;
		}
		
		rightY += 24;
		if(isInsideRect(mouseX, mouseY, rightX, rightY,
			rects.width - rects.leftWidth - 12, 24))
		{
			openSearchDialog();
			return true;
		}
		
		int disconnectW = 102;
		int disconnectH = 22;
		int disconnectX = rects.x + rects.width - disconnectW - 12;
		int disconnectY = rects.y + rects.height - rects.footerHeight + 6;
		if(isInsideRect(mouseX, mouseY, disconnectX, disconnectY, disconnectW,
			disconnectH))
		{
			startMenuOpen = false;
			allProgramsOpen = false;
			hoveredCategory = null;
			if(minecraft.level != null)
				minecraft.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
			return true;
		}
		
		return true;
	}
	
	private boolean handleWindowChromeClick(XpModuleWindow window,
		double mouseX, double mouseY, int button)
	{
		if(button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return false;
		
		int titleX = window.getX() + 1;
		int titleY = window.getY() + 1;
		int titleW = window.getWidth() - 2;
		
		if(!isInsideRect(mouseX, mouseY, titleX, titleY, titleW,
			TITLE_BAR_HEIGHT - 2))
			return false;
		
		int buttonSize = 14;
		int btnY = window.getY() + 4;
		int closeX = window.getX() + window.getWidth() - 6 - buttonSize;
		int minX = closeX - 2 - buttonSize;
		if(isInsideRect(mouseX, mouseY, closeX, btnY, buttonSize, buttonSize))
		{
			windowManager.closeWindow(window);
			return true;
		}
		
		if(isInsideRect(mouseX, mouseY, minX, btnY, buttonSize, buttonSize))
		{
			window.setMinimized(true);
			return true;
		}
		
		draggingWindow = window;
		draggingOffsetX = window.getX() - (int)mouseX;
		draggingOffsetY = window.getY() - (int)mouseY;
		return true;
	}
	
	private boolean handleWindowContentClick(XpModuleWindow window,
		double mouseX, double mouseY, int button)
	{
		WindowRects rects = computeWindowRects(window);
		List<SettingNode> nodes = collectSettings(window);
		int totalHeight = computeTotalSettingsHeight(nodes);
		int maxScroll = Math.max(0, totalHeight - rects.settingsHeight);
		int viewW = rects.contentWidth - (maxScroll > 0 ? 10 : 0);
		
		if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT && maxScroll > 0)
		{
			if(isInsideRect(mouseX, mouseY, rects.contentX + viewW,
				rects.settingsY, 10, rects.settingsHeight))
			{
				settingScrollDrag = new SettingScrollDrag(window,
					rects.settingsY, rects.settingsHeight, maxScroll);
				updateWindowScrollFromDrag(settingScrollDrag, mouseY);
				return true;
			}
		}
		
		if(isInsideRect(mouseX, mouseY, rects.contentX, rects.contentY,
			rects.contentWidth, 24))
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				window.getHack().setEnabled(!window.getHack().isEnabled());
			return true;
		}
		
		if(!isInsideRect(mouseX, mouseY, rects.contentX, rects.settingsY, viewW,
			rects.settingsHeight))
			return false;
		
		int y = rects.settingsY - window.getScrollOffset();
		for(SettingNode node : nodes)
		{
			int rowH = getRowHeight(node.setting());
			if(node.setting() instanceof SpacerSetting)
			{
				y += rowH + SETTING_ROW_GAP;
				continue;
			}
			
			if(isInsideRect(mouseX, mouseY, rects.contentX, y,
				rects.contentWidth, rowH))
				return activateSetting(window, node, rects, mouseX, y, button);
			
			y += rowH + SETTING_ROW_GAP;
		}
		
		return true;
	}
	
	private boolean activateSetting(XpModuleWindow window, SettingNode node,
		WindowRects rects, double mouseX, int rowY, int button)
	{
		Setting setting = node.setting();
		if(setting instanceof SettingGroup)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				window.toggleGroup(node.groupKey());
			return true;
		}
		
		if(setting instanceof CheckboxSetting checkbox)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
				checkbox.setChecked(checkbox.isCheckedByDefault());
			else if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				checkbox.setChecked(!checkbox.isChecked());
			return true;
		}
		
		if(setting instanceof SliderSetting slider)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
			{
				slider.setValue(slider.getDefaultValue());
				return true;
			}
			
			if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			{
				int controlX = rects.contentX + rects.contentWidth / 2;
				int controlW = rects.contentWidth / 2 - 8;
				int railX = controlX + 10;
				int railW = Math.max(30, controlW - 62);
				sliderDrag = new SliderDrag(window, slider, railX, railW);
				updateSliderFromMouse(sliderDrag, mouseX);
				return true;
			}
		}
		
		if(setting instanceof EnumSetting<?> enumSetting)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
				enumSetting.selectPrev();
			else if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				enumSetting.selectNext();
			return true;
		}
		
		if(setting instanceof StringDropdownSetting dropdown)
		{
			List<String> options = dropdown.getValues();
			if(options.isEmpty())
				return true;
			
			int index = options.indexOf(dropdown.getSelected());
			if(index < 0)
				index = 0;
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
				index = (index - 1 + options.size()) % options.size();
			else if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				index = (index + 1) % options.size();
			dropdown.setSelected(options.get(index));
			return true;
		}
		
		if(setting instanceof ButtonSetting btn)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				btn.runAction();
			return true;
		}
		
		if(setting instanceof TextFieldSetting text)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			{
				if(textSettingEditSession != null
					&& textSettingEditSession.setting == text)
					textSettingEditSession.selectAll = true;
				else
					textSettingEditSession = new TextSettingEditSession(text);
				return true;
			}
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
			{
				text.setValue(text.getDefaultValue());
				return true;
			}
		}
		
		if(setting instanceof ToggleAllPlantTypesSetting toggleAll)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
				toggleAll.resetHarvestingEnabled();
			else if(mouseX < rects.contentX + rects.contentWidth * 0.75)
				toggleAll.toggleHarvestingEnabled();
			else
				toggleAll.toggleReplantingEnabled();
			return true;
		}
		
		if(setting instanceof PlantTypeSetting plantType)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
			{
				plantType.resetHarvestingEnabled();
				plantType.resetReplantingEnabled();
			}else if(mouseX < rects.contentX + rects.contentWidth * 0.75)
				plantType.toggleHarvestingEnabled();
			else
				plantType.toggleReplantingEnabled();
			return true;
		}
		
		if(setting instanceof MobWeaponRuleSetting mobRule)
		{
			if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
			{
				mobRule.resetMob();
				mobRule.resetWeapon();
			}else if(mouseX < rects.contentX + rects.contentWidth * 0.75)
			{
				List<MobWeaponRuleSetting.MobOption> options =
					mobRule.getMobOptions();
				int idx = options.indexOf(mobRule.getSelectedMob());
				idx = (idx + 1) % options.size();
				mobRule.setSelectedMob(options.get(idx));
			}else
			{
				MobWeaponRuleSetting.WeaponCategory[] categories =
					MobWeaponRuleSetting.WeaponCategory.values();
				int idx = mobRule.getSelectedWeapon().ordinal();
				idx = (idx + 1) % categories.length;
				mobRule.setSelectedWeapon(categories[idx]);
			}
			return true;
		}
		
		if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			openSettingEditor(setting);
		
		return true;
	}
	
	private void openSettingEditor(Setting setting)
	{
		if(setting instanceof ColorSetting color)
			minecraft.setScreen(new EditColorScreen(this, color));
		else if(setting instanceof TextFieldSetting textField)
			minecraft.setScreen(new EditTextFieldScreen(this, textField));
		else if(setting instanceof BlockSetting block)
			minecraft.setScreen(new EditBlockScreen(this, block));
		else if(setting instanceof BlockListSetting blockList)
			minecraft.setScreen(new EditBlockListScreen(this, blockList));
		else if(setting instanceof ItemListSetting itemList)
			minecraft.setScreen(new EditItemListScreen(this, itemList));
		else if(setting instanceof EntityTypeListSetting entityList)
			minecraft.setScreen(new EditEntityTypeListScreen(this, entityList));
		else if(setting instanceof BookOffersSetting offers)
			minecraft.setScreen(new EditBookOffersScreen(this, offers));
		else if(setting instanceof FileSetting file)
			minecraft.setScreen(new SelectFileScreen(this, file));
		else if(setting instanceof WaypointsSetting waypoints)
			minecraft
				.setScreen(new WaypointsScreen(this, waypoints.getManager()));
	}
	
	private void updateSliderFromMouse(SliderDrag drag, double mouseX)
	{
		double percentage = (mouseX - drag.sliderX()) / drag.sliderWidth();
		double value = drag.setting().getMinimum()
			+ drag.setting().getRange() * percentage;
		drag.setting().setValue(value);
	}
	
	private void openHackWindow(Hack hack)
	{
		searchDialogFocused = false;
		windowManager.openWindow(hack, width, height, taskbarHeight());
	}
	
	private void openSearchFeature(Feature feature)
	{
		TooManyHaxHack tooManyHax =
			WurstClient.INSTANCE.getHax().tooManyHaxHack;
		if(tooManyHax.isEnabled() && tooManyHax.isBlocked(feature))
		{
			ChatUtils.error(feature.getName() + " is blocked by TooManyHax.");
			return;
		}
		
		if(feature instanceof Hack hack && isLaunchableHack(hack))
		{
			openHackWindow(hack);
			searchDialogOpen = false;
			searchFieldFocused = false;
			return;
		}
		
		if(!feature.getPrimaryAction().isEmpty())
			feature.doPrimaryAction();
		WurstClient.INSTANCE.getNavigator().addPreference(feature.getName());
	}
	
	private void openSearchDialog()
	{
		searchDialogOpen = true;
		searchDialogMinimized = false;
		searchDialogFocused = true;
		searchFieldFocused = true;
		searchSelectAll = false;
		searchScroll = 0;
		searchResultsScroll = 0;
		startMenuOpen = false;
		allProgramsOpen = false;
	}
	
	private boolean handleSearchDialogClick(double mouseX, double mouseY,
		int button)
	{
		SearchDialogRects rects = getSearchDialogRects();
		if(!isInsideRect(mouseX, mouseY, rects.x, rects.y, rects.w, rects.h))
			return false;
		searchDialogFocused = true;
		if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
		{
			int closeX = rects.x + rects.w - 24;
			int closeY = rects.y + 6;
			int minX = closeX - 16;
			int titleX = rects.x + 1;
			int titleY = rects.y + 1;
			int titleW = rects.w - 2;
			int titleH = TITLE_BAR_HEIGHT - 2;
			if(isInsideRect(mouseX, mouseY, minX, closeY, 14, 14))
			{
				searchDialogMinimized = true;
				searchDialogFocused = false;
				searchFieldFocused = false;
				return true;
			}
			
			if(isInsideRect(mouseX, mouseY, closeX, closeY, 14, 14))
			{
				searchDialogOpen = false;
				searchDialogFocused = false;
				searchFieldFocused = false;
				return true;
			}
			
			boolean insideTitle =
				isInsideRect(mouseX, mouseY, titleX, titleY, titleW, titleH);
			boolean insideButtons =
				isInsideRect(mouseX, mouseY, minX, closeY, 14, 14)
					|| isInsideRect(mouseX, mouseY, closeX, closeY, 14, 14);
			if(insideTitle && !insideButtons)
			{
				draggingSearchDialog = true;
				draggingSearchOffsetX = rects.x - (int)mouseX;
				draggingSearchOffsetY = rects.y - (int)mouseY;
				return true;
			}
			
			if(tryStartUiScrollbarDrag(mouseX, mouseY,
				ScrollbarTarget.SEARCH_RESULTS))
				return true;
			
			searchFieldFocused = isInsideRect(mouseX, mouseY, rects.inputX,
				rects.inputY, rects.inputW, 18);
			if(searchFieldFocused)
				searchSelectAll = false;
			
			if(isInsideRect(mouseX, mouseY, rects.resultsX, rects.resultsY,
				rects.resultsW, rects.resultsH))
			{
				List<Feature> filtered = getFilteredSearchFeatures();
				int listX = rects.resultsX + 2;
				int listW = rects.resultsW - 14;
				int colGap = 4;
				int columns = 3;
				int itemW =
					Math.max(70, (listW - colGap * (columns - 1)) / columns);
				int itemH = 20;
				for(int i = 0; i < filtered.size(); i++)
				{
					Feature feature = filtered.get(i);
					int col = i % columns;
					int row = i / columns;
					int x = listX + col * (itemW + colGap);
					int y = rects.resultsY + 2 + row * 22 - searchResultsScroll;
					if(y + itemH < rects.resultsY
						|| y > rects.resultsY + rects.resultsH)
						continue;
					
					if(feature instanceof Hack hack)
					{
						int iconX = x + 4;
						int iconY = y + 3;
						if(isInsideRect(mouseX, mouseY, iconX, iconY, 14, 14))
						{
							hack.setEnabled(!hack.isEnabled());
							return true;
						}
					}
					
					if(isInsideRect(mouseX, mouseY, x, y, itemW, itemH))
					{
						openSearchFeature(feature);
						return true;
					}
				}
				return true;
			}
		}
		return true;
	}
	
	private boolean handleSettingsDialogClick(double mouseX, double mouseY,
		int button)
	{
		SettingsDialogRects rects = getSettingsDialogRects();
		if(button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return isInsideSettingsDialog(mouseX, mouseY);
		
		int closeX = rects.x + rects.w - 22;
		int closeY = rects.y + 6;
		if(isInsideRect(mouseX, mouseY, closeX, closeY, 14, 14))
		{
			xpguiSettingsOpen = false;
			settingsUrlFocused = false;
			settingsStartIconFocused = false;
			return true;
		}
		
		if(isInsideRect(mouseX, mouseY, rects.modeImageX, rects.modeY,
			rects.modeW, 20))
		{
			desktopBackgroundMode = DesktopBackgroundMode.IMAGE;
			ensureDesktopWallpaperRequested();
			saveXpguiSettings();
			return true;
		}
		if(isInsideRect(mouseX, mouseY, rects.modeGradientX, rects.modeY,
			rects.modeW, 20))
		{
			desktopBackgroundMode = DesktopBackgroundMode.GRADIENT;
			saveXpguiSettings();
			return true;
		}
		if(isInsideRect(mouseX, mouseY, rects.modeNoneX, rects.modeY,
			rects.modeW, 20))
		{
			desktopBackgroundMode = DesktopBackgroundMode.NONE;
			saveXpguiSettings();
			return true;
		}
		
		if(isInsideRect(mouseX, mouseY, rects.opacityX, rects.opacityY,
			rects.opacityW, 10))
		{
			settingsOpacityDrag = true;
			updateDesktopOpacityFromMouse(mouseX);
			return true;
		}
		
		if(isInsideRect(mouseX, mouseY, rects.listItemsX, rects.listItemsY,
			rects.listItemsW, 10))
		{
			settingsListItemsDrag = true;
			updateAllProgramsVisibleItemsFromMouse(mouseX);
			return true;
		}
		
		settingsStartIconFocused = isInsideRect(mouseX, mouseY,
			rects.startIconX, rects.startIconY, rects.startIconW, 18);
		if(settingsStartIconFocused)
		{
			settingsUrlFocused = false;
			settingsStartIconSelectAll = false;
			return true;
		}
		
		settingsUrlFocused = isInsideRect(mouseX, mouseY, rects.urlX,
			rects.urlY, rects.urlW, 18);
		if(settingsUrlFocused)
		{
			settingsStartIconFocused = false;
			settingsUrlSelectAll = false;
			return true;
		}
		
		if(isInsideRect(mouseX, mouseY, rects.applyX, rects.applyY,
			rects.applyW, 20))
		{
			desktopBackgroundMode = DesktopBackgroundMode.IMAGE;
			downloadDesktopImage(desktopImageUrl);
			saveXpguiSettings();
			return true;
		}
		
		if(isInsideRect(mouseX, mouseY, rects.defaultX, rects.defaultY,
			rects.defaultW, 20))
		{
			desktopImageUrl = "";
			desktopBackgroundMode = DesktopBackgroundMode.IMAGE;
			desktopImageRequested = false;
			if(desktopTexture != null)
			{
				desktopTexture.close();
				desktopTexture = null;
			}
			desktopImageFailed = false;
			saveXpguiSettings();
			return true;
		}
		
		if(isInsideRect(mouseX, mouseY, rects.startApplyX, rects.startApplyY,
			rects.startApplyW, 20))
		{
			applyStartIconBlockName();
			return true;
		}
		
		if(isInsideRect(mouseX, mouseY, rects.themeXpX, rects.themeY,
			rects.themeW, 20))
		{
			win2000Theme = false;
			desktopBackgroundMode = DesktopBackgroundMode.IMAGE;
			desktopImageUrl = XP_WALLPAPER_URL;
			downloadDesktopImage(XP_WALLPAPER_URL);
			saveXpguiSettings();
			return true;
		}
		
		if(isInsideRect(mouseX, mouseY, rects.theme2kX, rects.themeY,
			rects.themeW, 20))
		{
			win2000Theme = true;
			desktopBackgroundMode = DesktopBackgroundMode.IMAGE;
			desktopImageUrl = WIN2000_WALLPAPER_URL;
			downloadDesktopImage(WIN2000_WALLPAPER_URL);
			saveXpguiSettings();
			return true;
		}
		
		return isInsideSettingsDialog(mouseX, mouseY);
	}
	
	private void renderSearchDialog(GuiGraphics context, int mouseX, int mouseY)
	{
		SearchDialogRects rects = getSearchDialogRects();
		context.fill(rects.x - 2, rects.y - 2, rects.x + rects.w + 2,
			rects.y + rects.h + 2, 0xB0000000);
		XpGuiTheme.drawWindowFrame(context, rects.x, rects.y, rects.x + rects.w,
			rects.y + rects.h);
		XpGuiTheme.drawTitleBar(context, rects.x + 1, rects.y + 1,
			rects.x + rects.w - 1, rects.y + 23, searchDialogFocused);
		context.renderItem(new ItemStack(Items.COMPASS), rects.x + 6,
			rects.y + 4);
		context.drawString(minecraft.font, "Search", rects.x + 26, rects.y + 8,
			0xFFFFFFFF, false);
		int closeX = rects.x + rects.w - 24;
		int closeY = rects.y + 6;
		int minX = closeX - 16;
		boolean hoverMin = isInsideRect(mouseX, mouseY, minX, closeY, 14, 14);
		boolean hoverClose =
			isInsideRect(mouseX, mouseY, closeX, closeY, 14, 14);
		XpGuiTheme.drawBevelRect(context, minX, closeY, minX + 14, closeY + 14,
			hoverMin ? 0xFF8DB8F0 : 0xFF6D9DE6, 0xB0FFFFFF, 0xFF2A4F98);
		context.fill(minX + 3, closeY + 9, minX + 11, closeY + 10, 0xFFFFFFFF);
		XpGuiTheme.drawBevelRect(context, closeX, closeY, closeX + 14,
			closeY + 14, hoverClose ? 0xFFE56E58 : 0xFFDA4E39, 0xA0FFFFFF,
			0xFF8C2A1E);
		context.drawString(minecraft.font, "x", closeX + 4, closeY + 3,
			0xFFFFFFFF, false);
		
		context.fill(rects.leftX, rects.leftY, rects.leftX + rects.leftW,
			rects.leftY + rects.leftH, 0xFF018CA8);
		XpGuiTheme.drawBevelRect(context, rects.inputX - 1, rects.inputY - 1,
			rects.inputX + rects.inputW + 1, rects.inputY + 19, 0xFFFFFFFF,
			0xFFFFFFFF, 0xFF6888B9);
		String query = searchQuery;
		context.drawString(minecraft.font, query, rects.inputX + 4,
			rects.inputY + 5, 0xFF1A3868, false);
		if(searchFieldFocused)
			context.fill(rects.inputX + 4 + minecraft.font.width(query),
				rects.inputY + 4,
				rects.inputX + 5 + minecraft.font.width(query),
				rects.inputY + 15, 0xFF1A3868);
		if(searchFieldFocused && searchSelectAll && !query.isEmpty())
		{
			context.fill(rects.inputX + 4, rects.inputY + 4,
				rects.inputX + 4 + minecraft.font.width(query),
				rects.inputY + 15, 0x55367CD6);
			context.drawString(minecraft.font, query, rects.inputX + 4,
				rects.inputY + 5, 0xFFFFFFFF, false);
		}
		
		context.drawString(minecraft.font, "Search Companion", rects.leftX + 8,
			rects.leftY + 8, 0xFFE8F6FF, false);
		context.drawString(minecraft.font, "Find hacks by name or description.",
			rects.leftX + 8, rects.leftY + 26, 0xFFD8EDFF, false);
		
		int dogX = rects.leftX + 16;
		int dogW = 94;
		int dogH = 94;
		int dogY = rects.leftY + rects.leftH - dogH - 10;
		renderSearchDog(context, dogX, dogY, dogW, dogH);
		
		int bubbleW = Math.min(154, rects.leftW - 58);
		int bubbleH = 58;
		int bubbleX = rects.leftX + rects.leftW - bubbleW - 12;
		int bubbleY = dogY - bubbleH - 12;
		XpGuiTheme.drawBevelRect(context, bubbleX, bubbleY, bubbleX + bubbleW,
			bubbleY + bubbleH, 0xFF9FD3E1, 0xFFCDEAF1, 0xFF4F94A6);
		context.drawString(minecraft.font, "what do you want to", bubbleX + 10,
			bubbleY + 19, 0xFF0F1B1D, false);
		context.drawString(minecraft.font, "search for?", bubbleX + 10,
			bubbleY + 33, 0xFF0F1B1D, false);
		
		context.fill(rects.resultsX, rects.resultsY,
			rects.resultsX + rects.resultsW, rects.resultsY + rects.resultsH,
			0xFFFDFEFF);
		List<Feature> filtered = getFilteredSearchFeatures();
		int max =
			Math.max(0, getSearchResultRows(filtered) * 22 - rects.resultsH);
		searchResultsScroll = clamp(searchResultsScroll, 0, max);
		int listX = rects.resultsX + 2;
		int listW = rects.resultsW - 14;
		int colGap = 4;
		int columns = 3;
		int itemW = Math.max(70, (listW - colGap * (columns - 1)) / columns);
		
		context.enableScissor(rects.resultsX + 1, rects.resultsY + 1,
			rects.resultsX + rects.resultsW - 11,
			rects.resultsY + rects.resultsH - 1);
		try
		{
			for(int i = 0; i < filtered.size(); i++)
			{
				Feature feature = filtered.get(i);
				int col = i % columns;
				int row = i / columns;
				int x = listX + col * (itemW + colGap);
				int y = rects.resultsY + 2 + row * 22 - searchResultsScroll;
				if(y + 20 < rects.resultsY)
					continue;
				if(y > rects.resultsY + rects.resultsH)
					continue;
				
				boolean hovered = isInsideRect(mouseX, mouseY, x, y, itemW, 20);
				if(hovered)
					context.fill(x, y, x + itemW, y + 20, 0xFFCFE1FA);
				
				renderFeatureStatusIcon(context, x + 4, y + 3, feature);
				String name = clipText(feature.getName(), itemW - 28);
				context.drawString(minecraft.font, name, x + 22, y + 7,
					0xFF153A70, false);
			}
		}finally
		{
			context.disableScissor();
		}
		
		if(max > 0)
			renderXpScrollbar(context, rects.resultsX + rects.resultsW - 12,
				rects.resultsY + 1, 10, rects.resultsH - 2, searchResultsScroll,
				max);
	}
	
	private void renderSettingsDialog(GuiGraphics context, int mouseX,
		int mouseY)
	{
		SettingsDialogRects rects = getSettingsDialogRects();
		context.fill(rects.x - 2, rects.y - 2, rects.x + rects.w + 2,
			rects.y + rects.h + 2, 0xC0000000);
		XpGuiTheme.drawWindowFrame(context, rects.x, rects.y, rects.x + rects.w,
			rects.y + rects.h);
		XpGuiTheme.drawTitleBar(context, rects.x + 1, rects.y + 1,
			rects.x + rects.w - 1, rects.y + 23, true);
		context.renderItem(new ItemStack(Items.WRITABLE_BOOK), rects.x + 6,
			rects.y + 4);
		context.drawString(minecraft.font, "XPGUI Settings", rects.x + 26,
			rects.y + 8, 0xFFFFFFFF, false);
		
		int closeX = rects.x + rects.w - 22;
		int closeY = rects.y + 6;
		renderCloseButton(context, closeX, closeY,
			isInsideRect(mouseX, mouseY, closeX, closeY, 14, 14));
		int sep1Y = rects.modeY + 24;
		int sep2Y = rects.startIconY - 22;
		int sep3Y = rects.listItemsY + 20;
		context.fill(rects.x + 10, sep1Y, rects.x + rects.w - 10, sep1Y + 1,
			0x9093ADD6);
		context.fill(rects.x + 10, sep2Y, rects.x + rects.w - 10, sep2Y + 1,
			0x9093ADD6);
		context.fill(rects.x + 10, sep3Y, rects.x + rects.w - 10, sep3Y + 1,
			0x9093ADD6);
		
		context.drawString(minecraft.font, "Background mode:", rects.x + 10,
			rects.y + 36, 0xFF173A6E, false);
		
		XpGuiTheme.drawXpButton(context, minecraft.font, "Image",
			rects.modeImageX, rects.modeY, rects.modeImageX + rects.modeW,
			rects.modeY + 20,
			isInsideRect(mouseX, mouseY, rects.modeImageX, rects.modeY,
				rects.modeW, 20),
			desktopBackgroundMode == DesktopBackgroundMode.IMAGE);
		XpGuiTheme.drawXpButton(context, minecraft.font, "Gradient",
			rects.modeGradientX, rects.modeY, rects.modeGradientX + rects.modeW,
			rects.modeY + 20,
			isInsideRect(mouseX, mouseY, rects.modeGradientX, rects.modeY,
				rects.modeW, 20),
			desktopBackgroundMode == DesktopBackgroundMode.GRADIENT);
		XpGuiTheme.drawXpButton(context, minecraft.font, "None",
			rects.modeNoneX, rects.modeY, rects.modeNoneX + rects.modeW,
			rects.modeY + 20,
			isInsideRect(mouseX, mouseY, rects.modeNoneX, rects.modeY,
				rects.modeW, 20),
			desktopBackgroundMode == DesktopBackgroundMode.NONE);
		
		context.drawString(minecraft.font, "Opacity:", rects.x + 10,
			rects.opacityY - 11, 0xFF173A6E, false);
		context.fill(rects.opacityX, rects.opacityY,
			rects.opacityX + rects.opacityW, rects.opacityY + 10, 0xFFCCE0FB);
		context.fill(rects.opacityX, rects.opacityY, rects.opacityX + 1,
			rects.opacityY + 10, 0xFFFFFFFF);
		context.fill(rects.opacityX, rects.opacityY + 9,
			rects.opacityX + rects.opacityW, rects.opacityY + 10, 0xFF5E7FAE);
		int knobX = rects.opacityX
			+ (int)Math.round((desktopOpacity / 255.0) * rects.opacityW);
		XpGuiTheme.drawBevelRect(context, knobX - 4, rects.opacityY - 4,
			knobX + 4, rects.opacityY + 14, 0xFFEDF5FF, 0xFFFFFFFF, 0xFF5E7FAE);
		String opacityText = Integer.toString(desktopOpacity);
		context.drawString(minecraft.font, opacityText,
			rects.opacityX + rects.opacityW + 8, rects.opacityY + 1, 0xFF244A7D,
			false);
		
		context.drawString(minecraft.font, "Image URL:", rects.x + 10,
			rects.urlY - 11, 0xFF173A6E, false);
		XpGuiTheme.drawBevelRect(context, rects.urlX - 1, rects.urlY - 1,
			rects.urlX + rects.urlW + 1, rects.urlY + 19, 0xFFFFFFFF,
			0xFFFFFFFF, 0xFF6888B9);
		String shownUrl = clipText(desktopImageUrl, rects.urlW - 8);
		context.drawString(minecraft.font, shownUrl, rects.urlX + 4,
			rects.urlY + 5, 0xFF1A3868, false);
		if(settingsUrlFocused && settingsUrlSelectAll && !shownUrl.isEmpty())
		{
			context.fill(rects.urlX + 4, rects.urlY + 4,
				rects.urlX + 4 + minecraft.font.width(shownUrl),
				rects.urlY + 15, 0x55367CD6);
			context.drawString(minecraft.font, shownUrl, rects.urlX + 4,
				rects.urlY + 5, 0xFFFFFFFF, false);
		}
		if(settingsUrlFocused)
			context.fill(rects.urlX + 4 + minecraft.font.width(shownUrl),
				rects.urlY + 4, rects.urlX + 5 + minecraft.font.width(shownUrl),
				rects.urlY + 15, 0xFF1A3868);
		
		XpGuiTheme.drawXpButton(context, minecraft.font, "Apply URL",
			rects.applyX, rects.applyY, rects.applyX + rects.applyW,
			rects.applyY + 20, isInsideRect(mouseX, mouseY, rects.applyX,
				rects.applyY, rects.applyW, 20),
			false);
		XpGuiTheme.drawXpButton(context, minecraft.font, "Use XP Default",
			rects.defaultX, rects.defaultY, rects.defaultX + rects.defaultW,
			rects.defaultY + 20, isInsideRect(mouseX, mouseY, rects.defaultX,
				rects.defaultY, rects.defaultW, 20),
			false);
		
		context.drawString(minecraft.font, "Start icon block:", rects.x + 10,
			rects.startIconY - 15, 0xFF173A6E, false);
		XpGuiTheme.drawBevelRect(context, rects.startIconX - 1,
			rects.startIconY - 1, rects.startIconX + rects.startIconW + 1,
			rects.startIconY + 19, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF6888B9);
		String shownStart = clipText(startIconBlockName, rects.startIconW - 8);
		context.drawString(minecraft.font, shownStart, rects.startIconX + 4,
			rects.startIconY + 5, 0xFF1A3868, false);
		if(settingsStartIconFocused && settingsStartIconSelectAll
			&& !shownStart.isEmpty())
		{
			context.fill(rects.startIconX + 4, rects.startIconY + 4,
				rects.startIconX + 4 + minecraft.font.width(shownStart),
				rects.startIconY + 15, 0x55367CD6);
			context.drawString(minecraft.font, shownStart, rects.startIconX + 4,
				rects.startIconY + 5, 0xFFFFFFFF, false);
		}
		if(settingsStartIconFocused)
			context.fill(
				rects.startIconX + 4 + minecraft.font.width(shownStart),
				rects.startIconY + 4,
				rects.startIconX + 5 + minecraft.font.width(shownStart),
				rects.startIconY + 15, 0xFF1A3868);
		
		XpGuiTheme.drawXpButton(
			context, minecraft.font, "Apply Start Icon", rects.startApplyX,
			rects.startApplyY, rects.startApplyX + rects.startApplyW,
			rects.startApplyY + 20, isInsideRect(mouseX, mouseY,
				rects.startApplyX, rects.startApplyY, rects.startApplyW, 20),
			false);
		
		context.drawString(minecraft.font, "Hack list size:", rects.x + 10,
			rects.listItemsY - 11, 0xFF173A6E, false);
		context.fill(rects.listItemsX, rects.listItemsY,
			rects.listItemsX + rects.listItemsW, rects.listItemsY + 10,
			0xFFCCE0FB);
		context.fill(rects.listItemsX, rects.listItemsY, rects.listItemsX + 1,
			rects.listItemsY + 10, 0xFFFFFFFF);
		context.fill(rects.listItemsX, rects.listItemsY + 9,
			rects.listItemsX + rects.listItemsW, rects.listItemsY + 10,
			0xFF5E7FAE);
		int listKnobX = rects.listItemsX
			+ (int)Math.round(((allProgramsVisibleItems.getValueI() - 4) / 20.0)
				* rects.listItemsW);
		XpGuiTheme.drawBevelRect(context, listKnobX - 4, rects.listItemsY - 4,
			listKnobX + 4, rects.listItemsY + 14, 0xFFEDF5FF, 0xFFFFFFFF,
			0xFF5E7FAE);
		context.drawString(minecraft.font,
			Integer.toString(allProgramsVisibleItems.getValueI()),
			rects.listItemsX + rects.listItemsW + 8, rects.listItemsY + 1,
			0xFF244A7D, false);
		
		context.drawString(minecraft.font, "Theme:", rects.x + 10,
			rects.themeY - 10, 0xFF173A6E, false);
		XpGuiTheme.drawXpButton(context, minecraft.font, "XP", rects.themeXpX,
			rects.themeY, rects.themeXpX + rects.themeW, rects.themeY + 20,
			isInsideRect(mouseX, mouseY, rects.themeXpX, rects.themeY,
				rects.themeW, 20),
			!win2000Theme);
		XpGuiTheme.drawXpButton(context, minecraft.font, "Win2000",
			rects.theme2kX, rects.themeY, rects.theme2kX + rects.themeW,
			rects.themeY + 20, isInsideRect(mouseX, mouseY, rects.theme2kX,
				rects.themeY, rects.themeW, 20),
			win2000Theme);
	}
	
	private void renderCloseButton(GuiGraphics context, int x, int y,
		boolean hovered)
	{
		XpGuiTheme.drawBevelRect(context, x, y, x + 14, y + 14,
			hovered ? 0xFFE56E58 : 0xFFDA4E39, 0xA0FFFFFF, 0xFF8C2A1E);
		context.drawString(minecraft.font, "x", x + 4, y + 3, 0xFFFFFFFF,
			false);
	}
	
	private void renderHackStatusIcon(GuiGraphics context, int x, int y,
		Hack hack)
	{
		int size = 14;
		int fill = hack.isEnabled() ? 0xFF4CD36A : 0xFFB4BEC8;
		int dark = hack.isEnabled() ? 0xFF227B39 : 0xFF6F7C8A;
		XpGuiTheme.drawBevelRect(context, x, y, x + size, y + size, fill,
			0xFFFFFFFF, dark);
		if(hack.isEnabled())
			context.fill(x + 4, y + 4, x + 10, y + 10, 0xAAFFFFFF);
	}
	
	private void renderFeatureStatusIcon(GuiGraphics context, int x, int y,
		Feature feature)
	{
		if(feature instanceof Hack hack)
		{
			renderHackStatusIcon(context, x, y, hack);
			return;
		}
		
		int size = 14;
		XpGuiTheme.drawBevelRect(context, x, y, x + size, y + size, 0xFFD8E0EA,
			0xFFFFFFFF, 0xFF77879A);
	}
	
	private boolean isInsideSearchDialog(double mouseX, double mouseY)
	{
		SearchDialogRects rects = getSearchDialogRects();
		return isInsideRect(mouseX, mouseY, rects.x, rects.y, rects.w, rects.h);
	}
	
	private boolean isInsideSettingsDialog(double mouseX, double mouseY)
	{
		SettingsDialogRects rects = getSettingsDialogRects();
		return isInsideRect(mouseX, mouseY, rects.x, rects.y, rects.w, rects.h);
	}
	
	private SearchDialogRects getSearchDialogRects()
	{
		int w = Math.min(width - 80, Math.max(520, width / 2));
		int h = Math.min(height - taskbarHeight() - 50,
			Math.max(330, (height - taskbarHeight()) / 2));
		if(searchDialogX == Integer.MIN_VALUE
			|| searchDialogY == Integer.MIN_VALUE)
		{
			searchDialogX = (width - w) / 2;
			searchDialogY = Math.max(8, (height - taskbarHeight() - h) / 2);
		}
		int maxX = Math.max(0, width - w);
		int maxY = Math.max(0, height - taskbarHeight() - h);
		int x = clamp(searchDialogX, 0, maxX);
		int y = clamp(searchDialogY, 0, maxY);
		searchDialogX = x;
		searchDialogY = y;
		int leftW = Math.max(210, Math.min(260, (int)(w * 0.38)));
		int leftX = x + 8;
		int leftY = y + 34;
		int leftH = h - 44;
		int inputX = leftX + 10;
		int inputY = leftY + 60;
		int inputW = leftW - 20;
		int resultsX = x + leftW + 16;
		int resultsY = y + 38;
		int resultsW = w - leftW - 24;
		int resultsH = h - 48;
		return new SearchDialogRects(x, y, w, h, leftX, leftY, leftW, leftH,
			inputX, inputY, inputW, resultsX, resultsY, resultsW, resultsH);
	}
	
	private SettingsDialogRects getSettingsDialogRects()
	{
		int w = 392;
		int h = 360;
		int x = width - w - 18;
		int y = Math.max(8, height - taskbarHeight() - h - 10);
		int modeY = y + 48;
		int modeW = 84;
		int modeImageX = x + 10;
		int modeGradientX = modeImageX + modeW + 8;
		int modeNoneX = modeGradientX + modeW + 8;
		int opacityX = x + 10;
		int opacityY = y + 88;
		int opacityW = w - 66;
		int urlX = x + 10;
		int urlY = y + 118;
		int urlW = w - 20;
		int applyX = x + 10;
		int applyY = y + 146;
		int applyW = 100;
		int defaultX = applyX + applyW + 8;
		int defaultY = applyY;
		int defaultW = 128;
		int startIconX = x + 10;
		int startIconY = y + 204;
		int startIconW = w - 20;
		int startApplyX = x + 10;
		int startApplyY = y + 238;
		int startApplyW = 140;
		int listItemsX = x + 10;
		int listItemsY = y + 284;
		int listItemsW = opacityW;
		int themeY = y + 328;
		int themeXpX = x + 10;
		int themeW = 112;
		int theme2kX = themeXpX + themeW + 8;
		return new SettingsDialogRects(x, y, w, h, modeImageX, modeGradientX,
			modeNoneX, modeY, modeW, opacityX, opacityY, opacityW, urlX, urlY,
			urlW, applyX, applyY, applyW, defaultX, defaultY, defaultW,
			startIconX, startIconY, startIconW, startApplyX, startApplyY,
			startApplyW, listItemsX, listItemsY, listItemsW, themeY, themeXpX,
			theme2kX, themeW);
	}
	
	private void updateDesktopOpacityFromMouse(double mouseX)
	{
		SettingsDialogRects rects = getSettingsDialogRects();
		double t = (mouseX - rects.opacityX) / Math.max(1.0, rects.opacityW);
		t = Math.max(0.0, Math.min(1.0, t));
		desktopOpacity = (int)Math.round(t * 255.0);
		saveXpguiSettings();
	}
	
	private void updateAllProgramsVisibleItemsFromMouse(double mouseX)
	{
		SettingsDialogRects rects = getSettingsDialogRects();
		double t =
			(mouseX - rects.listItemsX) / Math.max(1.0, rects.listItemsW);
		t = Math.max(0.0, Math.min(1.0, t));
		int items = 4 + (int)Math.round(t * 20.0);
		allProgramsVisibleItems.setValue(items);
		saveXpguiSettings();
	}
	
	private void applyStartIconBlockName()
	{
		String raw =
			startIconBlockName == null ? "" : startIconBlockName.trim();
		if(raw.isEmpty())
			return;
		
		Identifier id = Identifier.tryParse(raw);
		if(id == null)
			id = Identifier.tryParse("minecraft:" + raw);
		if(id == null || !BuiltInRegistries.BLOCK.containsKey(id))
			return;
		
		startIconBlock = BuiltInRegistries.BLOCK.getValue(id);
		startIconBlockName =
			BuiltInRegistries.BLOCK.getKey(startIconBlock).toString();
		saveXpguiSettings();
	}
	
	private List<Feature> getFilteredSearchFeatures()
	{
		String q = searchQuery.trim().toLowerCase(Locale.ROOT);
		ArrayList<Feature> filtered = new ArrayList<>();
		if(q.isEmpty())
			WurstClient.INSTANCE.getNavigator().copyNavigatorList(filtered);
		else
			WurstClient.INSTANCE.getNavigator().getSearchResults(filtered, q);
		
		return filtered;
	}
	
	private int getSearchResultRows(List<Feature> filtered)
	{
		return (filtered.size() + 2) / 3;
	}
	
	private void drawPlayerHead(GuiGraphics context, int x, int y, int size)
	{
		XpGuiTheme.drawBevelRect(context, x - 2, y - 2, x + size + 2,
			y + size + 2, 0xFFEAF3FF, 0xFFFFFFFF, 0xFF406AA3);
		Identifier texture = DefaultPlayerSkin
			.get(minecraft.getUser().getProfileId()).body().texturePath();
		if(minecraft.getConnection() != null)
		{
			PlayerInfo info = minecraft.getConnection()
				.getPlayerInfo(minecraft.getUser().getProfileId());
			if(info != null)
				texture = info.getSkin().body().texturePath();
		}
		
		context.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 8, 8, size,
			size, 8, 8, 64, 64, 0xFFFFFFFF);
		context.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 40, 8, size,
			size, 8, 8, 64, 64, 0xFFFFFFFF);
	}
	
	private void renderSearchDog(GuiGraphics context, int x, int y, int w,
		int h)
	{
		ensureDogFramesLoaded();
		context.fill(x, y, x + w, y + h, 0xFF018CA8);
		if(!dogGifFrames.isEmpty())
		{
			DogGifFrame frame = getCurrentDogFrame();
			int innerPad = Math.max(2, Math.min(w, h) / 24);
			int drawW = w - innerPad * 2;
			int drawH = h - innerPad * 2;
			int drawX = x + innerPad;
			int drawY = y + innerPad;
			context.blit(RenderPipelines.GUI_TEXTURED, frame.textureId, drawX,
				drawY, frame.cropX, frame.cropY, drawW, drawH, frame.cropW,
				frame.cropH, frame.width, frame.height, 0xFFFFFFFF);
			return;
		}
		
		// Fallback if texture load fails.
		context.fill(x + 10, y + 10, x + w - 10, y + h - 10, 0xFF000000);
		context.drawString(minecraft.font, "!", x + w / 2 - 2, y + h / 2 - 4,
			0xFFFFFFFF, false);
	}
	
	private DogGifFrame getCurrentDogFrame()
	{
		long totalMs = 0;
		for(DogGifFrame frame : dogGifFrames)
			totalMs += Math.max(20, frame.delayMs);
		
		if(totalMs <= 0)
			return dogGifFrames.get(0);
		
		long elapsed =
			(System.currentTimeMillis() - dogAnimationStartMs) % totalMs;
		long cursor = 0;
		for(DogGifFrame frame : dogGifFrames)
		{
			cursor += Math.max(20, frame.delayMs);
			if(elapsed < cursor)
				return frame;
		}
		
		return dogGifFrames.get(dogGifFrames.size() - 1);
	}
	
	private void ensureDogFramesLoaded()
	{
		if(!dogGifFrames.isEmpty() || dogTextureFailed)
			return;
		
		try(InputStream in =
			XpGuiScreen.class.getResourceAsStream("/assets/wurst/dog.gif"))
		{
			if(in == null)
			{
				dogTextureFailed = true;
				return;
			}
			
			byte[] bytes = in.readAllBytes();
			loadGifFrames(bytes);
			if(dogGifFrames.isEmpty())
			{
				NativeImage image = decodeImage(bytes);
				try
				{
					registerDogFrame(0, image, 120);
				}finally
				{
					image.close();
				}
			}
			dogAnimationStartMs = System.currentTimeMillis();
		}catch(Exception ignored)
		{
			dogTextureFailed = true;
		}
	}
	
	private void loadGifFrames(byte[] bytes) throws IOException
	{
		try(ImageInputStream iis =
			ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
		{
			java.util.Iterator<ImageReader> readers =
				ImageIO.getImageReadersByFormatName("gif");
			if(!readers.hasNext())
				return;
			
			ImageReader reader = readers.next();
			try
			{
				reader.setInput(iis, false, false);
				BufferedImage canvas = new BufferedImage(reader.getWidth(0),
					reader.getHeight(0), BufferedImage.TYPE_INT_ARGB);
				BufferedImage frameBase = new BufferedImage(canvas.getWidth(),
					canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
				Graphics2D graphics = canvas.createGraphics();
				graphics.setComposite(AlphaComposite.SrcOver);
				int frameCount = reader.getNumImages(true);
				for(int i = 0; i < frameCount; i++)
				{
					BufferedImage frameImage = reader.read(i);
					if(frameImage == null)
						continue;
					
					GifFrameLayout layout =
						getGifFrameLayout(reader.getImageMetadata(i));
					String disposal =
						getGifDisposalMethod(reader.getImageMetadata(i));
					if("restoreToBackgroundColor".equals(disposal))
					{
						graphics.clearRect(layout.x(), layout.y(), layout.w(),
							layout.h());
					}
					// Some decoders already return full-canvas frames. Only
					// apply GIF offsets when we get a cropped tile frame.
					boolean isTileFrame = layout.w() > 0 && layout.h() > 0
						&& frameImage.getWidth() == layout.w()
						&& frameImage.getHeight() == layout.h()
						&& (layout.w() != canvas.getWidth()
							|| layout.h() != canvas.getHeight());
					if(isTileFrame)
						graphics.drawImage(frameImage, layout.x(), layout.y(),
							null);
					else
						graphics.drawImage(frameImage, 0, 0, null);
					int delayMs =
						getGifFrameDelayMs(reader.getImageMetadata(i));
					frameBase = deepCopyBufferedImage(canvas);
					NativeImage nativeFrame = bufferedImageToNative(frameBase);
					try
					{
						registerDogFrame(i, nativeFrame, delayMs);
					}finally
					{
						nativeFrame.close();
					}
				}
				graphics.dispose();
			}finally
			{
				reader.dispose();
			}
		}
	}
	
	private GifFrameLayout getGifFrameLayout(IIOMetadata metadata)
	{
		try
		{
			Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
			for(Node node = root.getFirstChild(); node != null; node =
				node.getNextSibling())
			{
				if(!"ImageDescriptor".equals(node.getNodeName()))
					continue;
				
				NamedNodeMap attrs = node.getAttributes();
				if(attrs == null)
					break;
				
				int x = Integer.parseInt(
					attrs.getNamedItem("imageLeftPosition").getNodeValue());
				int y = Integer.parseInt(
					attrs.getNamedItem("imageTopPosition").getNodeValue());
				int w = Integer
					.parseInt(attrs.getNamedItem("imageWidth").getNodeValue());
				int h = Integer
					.parseInt(attrs.getNamedItem("imageHeight").getNodeValue());
				return new GifFrameLayout(x, y, w, h);
			}
		}catch(Exception ignored)
		{
			// Use defaults below.
		}
		return new GifFrameLayout(0, 0, 0, 0);
	}
	
	private String getGifDisposalMethod(IIOMetadata metadata)
	{
		try
		{
			Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
			for(Node node = root.getFirstChild(); node != null; node =
				node.getNextSibling())
			{
				if(!"GraphicControlExtension".equals(node.getNodeName()))
					continue;
				
				NamedNodeMap attrs = node.getAttributes();
				if(attrs == null)
					break;
				
				Node disposal = attrs.getNamedItem("disposalMethod");
				if(disposal != null)
					return disposal.getNodeValue();
			}
		}catch(Exception ignored)
		{
			// Use default below.
		}
		return "doNotDispose";
	}
	
	private int getGifFrameDelayMs(IIOMetadata metadata)
	{
		try
		{
			Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
			for(Node node = root.getFirstChild(); node != null; node =
				node.getNextSibling())
			{
				if(!"GraphicControlExtension".equals(node.getNodeName()))
					continue;
				
				NamedNodeMap attrs = node.getAttributes();
				if(attrs == null)
					continue;
				
				Node delay = attrs.getNamedItem("delayTime");
				if(delay == null)
					continue;
				
				int hundredths = Integer.parseInt(delay.getNodeValue());
				return Math.max(20, hundredths * 10);
			}
		}catch(Exception ignored)
		{
			// Use default below.
		}
		return 100;
	}
	
	private NativeImage bufferedImageToNative(BufferedImage buffered)
		throws IOException
	{
		try(ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			ImageIO.write(buffered, "png", out);
			try(InputStream in = new ByteArrayInputStream(out.toByteArray()))
			{
				NativeImage image = NativeImage.read(in);
				if(image == null)
					throw new IOException("Failed to decode GIF frame.");
				return image;
			}
		}
	}
	
	private void registerDogFrame(int frameIndex, NativeImage image,
		int delayMs)
	{
		int width = image.getWidth();
		int height = image.getHeight();
		int[] bounds = getOpaqueBounds(image);
		Identifier frameId = frameIndex == 0 ? XP_DOG_TEX_DYNAMIC_ID
			: Identifier.fromNamespaceAndPath("wurst",
				"dynamic/xpgui_dog_" + frameIndex);
		DynamicTexture texture =
			new DynamicTexture("xpgui_dog_" + frameIndex, width, height, false);
		NativeImage pixels = texture.getPixels();
		if(pixels == null)
			return;
		for(int px = 0; px < width; px++)
			for(int py = 0; py < height; py++)
				pixels.setPixel(px, py, image.getPixel(px, py));
		texture.upload();
		minecraft.getTextureManager().register(frameId, texture);
		dogGifFrames
			.add(new DogGifFrame(frameId, texture, Math.max(20, delayMs), width,
				height, bounds[0], bounds[1], bounds[2], bounds[3]));
	}
	
	private int[] getOpaqueBounds(NativeImage image)
	{
		int minX = image.getWidth();
		int minY = image.getHeight();
		int maxX = -1;
		int maxY = -1;
		for(int y = 0; y < image.getHeight(); y++)
			for(int x = 0; x < image.getWidth(); x++)
			{
				int alpha = (image.getPixel(x, y) >>> 24) & 0xFF;
				if(alpha == 0)
					continue;
				if(x < minX)
					minX = x;
				if(y < minY)
					minY = y;
				if(x > maxX)
					maxX = x;
				if(y > maxY)
					maxY = y;
			}
		
		if(maxX < minX || maxY < minY)
			return new int[]{0, 0, image.getWidth(), image.getHeight()};
		
		return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
	}
	
	private void ensureDesktopWallpaperRequested()
	{
		if(desktopImageRequested || desktopTexture != null
			|| desktopImageFailed)
			return;
		desktopImageRequested = true;
		downloadDesktopImage(desktopImageUrl);
	}
	
	private void downloadDesktopImage(String url)
	{
		if(url == null || url.isBlank())
			return;
		
		desktopImageUrl = url.trim();
		desktopImageRequested = true;
		desktopImageFailed = false;
		CompletableFuture.runAsync(() -> {
			try
			{
				byte[] bytes = readUrlBytes(desktopImageUrl);
				NativeImage image = decodeImage(bytes);
				
				synchronized(this)
				{
					if(pendingDesktopImage != null)
						pendingDesktopImage.close();
					pendingDesktopImage = image;
				}
				
				try
				{
					Path desktopPath =
						getXpguiDataDir().resolve("desktop_background.jpg");
					Files.write(desktopPath, bytes);
				}catch(Exception ignored)
				{
					// If saving fails, keep in-memory texture anyway.
				}
			}catch(Exception ignored)
			{
				desktopImageFailed = true;
			}
		});
	}
	
	private byte[] readUrlBytes(String url) throws IOException
	{
		try
		{
			HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.ALWAYS).build();
			HttpRequest request =
				HttpRequest.newBuilder().uri(java.net.URI.create(url))
					.header("User-Agent",
						"Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
					.timeout(java.time.Duration.ofSeconds(30)).GET().build();
			HttpResponse<byte[]> response =
				client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if(response.statusCode() >= 200 && response.statusCode() < 300)
				return response.body();
		}catch(Exception ignored)
		{
			// Fallback below.
		}
		
		var conn = java.net.URI.create(url).toURL().openConnection();
		conn.setConnectTimeout(15_000);
		conn.setReadTimeout(30_000);
		conn.setRequestProperty("User-Agent",
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
		try(InputStream in = conn.getInputStream())
		{
			return in.readAllBytes();
		}
	}
	
	private NativeImage decodeImage(byte[] bytes) throws IOException
	{
		try(InputStream in = new ByteArrayInputStream(bytes))
		{
			NativeImage image = NativeImage.read(in);
			if(image != null)
				return image;
		}catch(Exception ignored)
		{
			// Fallback via ImageIO for formats NativeImage cannot decode.
		}
		
		BufferedImage buffered;
		try(InputStream in = new ByteArrayInputStream(bytes))
		{
			buffered = ImageIO.read(in);
		}
		if(buffered == null)
			throw new IOException("Unsupported image format.");
		
		try(ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			ImageIO.write(buffered, "png", out);
			try(InputStream in = new ByteArrayInputStream(out.toByteArray()))
			{
				NativeImage image = NativeImage.read(in);
				if(image == null)
					throw new IOException("Failed to decode image.");
				return image;
			}
		}
	}
	
	private void uploadPendingDesktopTexture()
	{
		NativeImage image;
		synchronized(this)
		{
			image = pendingDesktopImage;
			pendingDesktopImage = null;
		}
		
		if(image == null)
			return;
		
		if(desktopTexture != null)
			desktopTexture.close();
		
		int w = image.getWidth();
		int h = image.getHeight();
		desktopTexture = new DynamicTexture("xpgui_desktop", w, h, false);
		NativeImage pixels = desktopTexture.getPixels();
		if(pixels == null)
		{
			image.close();
			desktopImageFailed = true;
			return;
		}
		
		for(int px = 0; px < w; px++)
			for(int py = 0; py < h; py++)
				pixels.setPixel(px, py, image.getPixel(px, py));
			
		image.close();
		desktopTexture.upload();
		minecraft.getTextureManager().register(XP_DESKTOP_TEX_ID,
			desktopTexture);
	}
	
	private Path getXpguiDataDir() throws IOException
	{
		Path dir =
			minecraft.gameDirectory.toPath().resolve("wurst").resolve("xpgui");
		Files.createDirectories(dir);
		return dir;
	}
	
	private void ensureXpguiSettingsLoaded()
	{
		if(xpguiSettingsLoaded)
			return;
		
		xpguiSettingsLoaded = true;
		try
		{
			Path file = getXpguiDataDir().resolve("settings.properties");
			if(!Files.exists(file))
			{
				applyStartIconBlockName();
				return;
			}
			
			Properties props = new Properties();
			try(InputStream in = Files.newInputStream(file))
			{
				props.load(in);
			}
			
			String mode = props.getProperty("desktopBackgroundMode", "IMAGE");
			try
			{
				desktopBackgroundMode = DesktopBackgroundMode.valueOf(mode);
			}catch(Exception ignored)
			{
				desktopBackgroundMode = DesktopBackgroundMode.IMAGE;
			}
			
			win2000Theme = Boolean
				.parseBoolean(props.getProperty("win2000Theme", "false"));
			desktopOpacity = clamp(
				Integer.parseInt(props.getProperty("desktopOpacity", "255")), 0,
				255);
			int visibleItems =
				clamp(
					Integer.parseInt(
						props.getProperty("allProgramsVisibleItems", "14")),
					4, 24);
			allProgramsVisibleItems.setValue(visibleItems);
			desktopImageUrl = props.getProperty("desktopImageUrl", "").trim();
			startIconBlockName =
				props.getProperty("startIconBlockName", startIconBlockName);
			applyStartIconBlockName();
		}catch(Exception ignored)
		{
			applyStartIconBlockName();
		}
	}
	
	private BufferedImage deepCopyBufferedImage(BufferedImage source)
	{
		BufferedImage copy = new BufferedImage(source.getWidth(),
			source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = copy.createGraphics();
		try
		{
			graphics.setComposite(AlphaComposite.Src);
			graphics.drawImage(source, 0, 0, null);
		}finally
		{
			graphics.dispose();
		}
		return copy;
	}
	
	private void saveXpguiSettings()
	{
		if(minecraft == null)
			return;
		
		try
		{
			Properties props = new Properties();
			props.setProperty("desktopBackgroundMode",
				desktopBackgroundMode.name());
			props.setProperty("desktopOpacity",
				Integer.toString(desktopOpacity));
			props.setProperty("desktopImageUrl",
				desktopImageUrl == null ? "" : desktopImageUrl);
			props.setProperty("win2000Theme", Boolean.toString(win2000Theme));
			props.setProperty("startIconBlockName",
				startIconBlockName == null ? "" : startIconBlockName);
			props.setProperty("allProgramsVisibleItems",
				Integer.toString(allProgramsVisibleItems.getValueI()));
			
			Path file = getXpguiDataDir().resolve("settings.properties");
			try(var out = Files.newOutputStream(file))
			{
				props.store(out, "Wurst XPGUI settings");
			}
		}catch(Exception ignored)
		{
			// Persistence is best-effort.
		}
	}
	
	private void renderXpScrollbar(GuiGraphics context, int x, int y, int w,
		int h, int scroll, int maxScroll)
	{
		XpGuiTheme.drawBevelRect(context, x, y, x + w, y + h, 0xFFE3ECFA,
			0xFFFFFFFF, 0xFF6C8EBE);
		if(maxScroll <= 0)
			return;
		
		int thumbH = getScrollbarThumbHeight(h, maxScroll);
		int thumbY =
			y + (int)Math.round((h - thumbH) * (scroll / (double)maxScroll));
		XpGuiTheme.drawBevelRect(context, x + 1, thumbY, x + w - 1,
			thumbY + thumbH, 0xFFCFE1FA, 0xFFFFFFFF, 0xFF5F7FB0);
	}
	
	private void updateWindowScrollFromDrag(SettingScrollDrag drag,
		double mouseY)
	{
		int thumbH = Math.max(16, (int)Math.round((drag.barH * 1.0)
			* (drag.barH * 1.0 / (drag.barH + drag.maxScroll))));
		int top = drag.barY;
		int bottom = drag.barY + drag.barH - thumbH;
		int thumbTop = clamp((int)mouseY - thumbH / 2, top, bottom);
		double ratio = (thumbTop - top) / (double)Math.max(1, bottom - top);
		drag.window.setScrollOffset((int)Math.round(ratio * drag.maxScroll));
	}
	
	private boolean tryStartUiScrollbarDrag(double mouseX, double mouseY,
		ScrollbarTarget target)
	{
		ScrollbarInfo info = getScrollbarInfo(target);
		if(info == null || info.maxScroll <= 0)
			return false;
		
		if(!isInsideRect(mouseX, mouseY, info.barX, info.barY, info.barW,
			info.barH))
			return false;
		
		uiScrollbarDrag = new UiScrollbarDrag(target, info.barX, info.barY,
			info.barW, info.barH, info.maxScroll);
		updateUiScrollbarFromDrag(uiScrollbarDrag, mouseY);
		return true;
	}
	
	private void updateUiScrollbarFromDrag(UiScrollbarDrag drag, double mouseY)
	{
		int thumbH = getScrollbarThumbHeight(drag.barH, drag.maxScroll);
		int top = drag.barY;
		int bottom = drag.barY + drag.barH - thumbH;
		int thumbTop = clamp((int)mouseY - thumbH / 2, top, bottom);
		double ratio = (thumbTop - top) / (double)Math.max(1, bottom - top);
		int scroll = (int)Math.round(ratio * drag.maxScroll);
		
		switch(drag.target)
		{
			case FAVORITES -> favoritesScroll = scroll;
			case SEARCH_RESULTS -> searchResultsScroll = scroll;
			case ALLPROGRAMS_MODULES -> allProgramsModuleScroll = scroll;
		}
	}
	
	private ScrollbarInfo getScrollbarInfo(ScrollbarTarget target)
	{
		if(target == ScrollbarTarget.SEARCH_RESULTS)
		{
			SearchDialogRects rects = getSearchDialogRects();
			List<Feature> filtered = getFilteredSearchFeatures();
			int max = Math.max(0,
				getSearchResultRows(filtered) * 22 - rects.resultsH);
			return new ScrollbarInfo(rects.resultsX + rects.resultsW - 12,
				rects.resultsY + 1, 10, rects.resultsH - 2, max);
		}
		
		StartMenuRects rects = getStartMenuRects();
		if(target == ScrollbarTarget.FAVORITES)
		{
			int x1 = rects.x + 6;
			int contentTop = rects.y + rects.headerHeight + 8;
			int contentBottom =
				rects.y + rects.height - rects.footerHeight - 32;
			int viewportH = contentBottom - contentTop;
			int max = Math.max(0, getFavoriteHacks().size() * 23 - viewportH);
			return new ScrollbarInfo(x1 + rects.leftWidth - 17, contentTop, 10,
				viewportH, max);
		}
		
		if(target == ScrollbarTarget.ALLPROGRAMS_MODULES && allProgramsOpen
			&& hoveredCategory != null)
		{
			Map<String, List<Hack>> byCategory = getHacksByCategory();
			AllProgramsRects ap = getAllProgramsRects(rects);
			List<Hack> hacks = getCategoryModules(hoveredCategory);
			int index =
				new ArrayList<>(byCategory.keySet()).indexOf(hoveredCategory);
			if(index < 0)
				return null;
			int totalModuleH = hacks.size() * 20 + 8;
			int moduleVisibleH =
				Math.max(120, allProgramsVisibleItems.getValueI() * 20 + 8);
			int moduleY = ap.categoryY + 4 + index * 20;
			moduleY = clamp(moduleY, 4,
				height - taskbarHeight() - moduleVisibleH - 2);
			int max = Math.max(0, totalModuleH - moduleVisibleH);
			return new ScrollbarInfo(ap.moduleX + ap.moduleW - 12, moduleY + 2,
				10, moduleVisibleH - 4, max);
		}
		
		return null;
	}
	
	private int getScrollbarThumbHeight(int h, int maxScroll)
	{
		if(maxScroll <= 0)
			return h;
		return Math.max(16,
			(int)Math.round((h * 1.0) * (h * 1.0 / (h + maxScroll))));
	}
	
	private List<SettingNode> collectSettings(XpModuleWindow window)
	{
		ArrayList<SettingNode> nodes = new ArrayList<>();
		for(Setting setting : window.getHack().getSettings().values())
			appendSetting(window, nodes, setting, 0,
				window.getHack().getName().toLowerCase(Locale.ROOT));
		return nodes;
	}
	
	private void appendSetting(XpModuleWindow window, List<SettingNode> nodes,
		Setting setting, int depth, String keyPrefix)
	{
		if(!setting.isVisibleInGui() && depth == 0)
			return;
		
		if(setting instanceof SettingGroup group)
		{
			String key = keyPrefix + "/" + group.getName();
			boolean expanded = window.isGroupExpanded(key);
			nodes.add(new SettingNode(group, depth, key, expanded));
			if(expanded)
				for(Setting child : group.getChildren())
					appendSetting(window, nodes, child, depth + 1, key);
			return;
		}
		
		nodes.add(new SettingNode(setting, depth, "", false));
	}
	
	private int computeTotalSettingsHeight(List<SettingNode> nodes)
	{
		int total = 0;
		for(SettingNode node : nodes)
			total += getRowHeight(node.setting()) + SETTING_ROW_GAP;
		return Math.max(0, total - SETTING_ROW_GAP);
	}
	
	private int getRowHeight(Setting setting)
	{
		if(setting instanceof SpacerSetting)
			return 8;
		if(setting instanceof SliderSetting)
			return 24;
		return 20;
	}
	
	private String getSettingValueLabel(Setting setting)
	{
		if(setting instanceof EnumSetting<?> enumSetting)
			return String.valueOf(enumSetting.getSelected());
		if(setting instanceof StringDropdownSetting dropdown)
			return dropdown.getSelected().isBlank() ? "Default"
				: dropdown.getSelected();
		if(setting instanceof ButtonSetting)
			return "Run";
		if(setting instanceof ColorSetting color)
			return String.format("#%06X", color.getColorI() & 0xFFFFFF);
		if(setting instanceof TextFieldSetting text)
			return clipText(text.getValue(), 18);
		if(setting instanceof BlockSetting block)
			return clipText(block.getShortBlockName(), 18);
		if(setting instanceof BlockListSetting)
			return "Edit list...";
		if(setting instanceof ItemListSetting)
			return "Edit list...";
		if(setting instanceof EntityTypeListSetting)
			return "Edit list...";
		if(setting instanceof BookOffersSetting)
			return "Edit offers...";
		if(setting instanceof FileSetting file)
			return clipText(file.getSelectedFileName(), 18);
		if(setting instanceof WaypointsSetting)
			return "Open manager...";
		if(setting instanceof ToggleAllPlantTypesSetting toggleAll)
			return "Harvest: " + stateText(toggleAll.isHarvestingEnabled())
				+ " / Replant: " + stateText(toggleAll.isReplantingEnabled());
		if(setting instanceof PlantTypeSetting plantType)
			return "Harvest: " + yesNo(plantType.isHarvestingEnabled())
				+ " / Replant: " + yesNo(plantType.isReplantingEnabled());
		if(setting instanceof MobWeaponRuleSetting mobRule)
			return mobRule.getSelectedMob().displayName() + " / "
				+ mobRule.getSelectedWeapon().toString();
		return "Edit...";
	}
	
	private String stateText(Boolean value)
	{
		if(value == null)
			return "Mixed";
		return value ? "On" : "Off";
	}
	
	private String yesNo(boolean value)
	{
		return value ? "On" : "Off";
	}
	
	private List<Hack> getFavoriteHacks()
	{
		ArrayList<Hack> favorites = new ArrayList<>();
		for(Hack hack : WurstClient.INSTANCE.getHax().getAllHax())
		{
			if(!isLaunchableHack(hack) || !hack.isFavorite())
				continue;
			favorites.add(hack);
		}
		
		favorites.sort(
			Comparator.comparing(Hack::getName, String.CASE_INSENSITIVE_ORDER));
		return favorites;
	}
	
	private Map<String, List<Hack>> getHacksByCategory()
	{
		Map<String, List<Hack>> map = new HashMap<>();
		for(Category category : Category.values())
		{
			if(category == Category.FAVORITES)
				continue;
			map.put(category.getName(), new ArrayList<>());
		}
		
		for(Hack hack : WurstClient.INSTANCE.getHax().getAllHax())
		{
			if(!isLaunchableHack(hack) || hack.getCategory() == null)
				continue;
			List<Hack> list = map.get(hack.getCategory().getName());
			if(list != null)
				list.add(hack);
		}
		
		for(List<Hack> list : map.values())
			list.sort(Comparator.comparing(Hack::getName,
				String.CASE_INSENSITIVE_ORDER));
		
		map.entrySet().removeIf(e -> e.getValue().isEmpty());
		
		Map<String, List<Hack>> ordered = new java.util.LinkedHashMap<>();
		for(Category category : Category.values())
		{
			if(map.containsKey(category.getName()))
				ordered.put(category.getName(), map.get(category.getName()));
		}
		return ordered;
	}
	
	private List<Hack> getCategoryModules(String categoryName)
	{
		Map<String, List<Hack>> byCategory = getHacksByCategory();
		List<Hack> hacks = byCategory.get(categoryName);
		return hacks == null ? List.of() : hacks;
	}
	
	private AllProgramsRects getAllProgramsRects(StartMenuRects rects)
	{
		int allProgramsY = rects.y + rects.height - rects.footerHeight - 28;
		int categoryX = rects.x + rects.leftWidth - 1;
		int categoryW = 170;
		int categoryH = Math.max(48, getHacksByCategory().size() * 20 + 8);
		int categoryY = allProgramsY + 22 - categoryH;
		categoryY =
			clamp(categoryY, 4, height - taskbarHeight() - categoryH - 2);
		int moduleX = categoryX + categoryW - 2;
		int moduleW = 190;
		int moduleHVisible =
			Math.max(120, allProgramsVisibleItems.getValueI() * 20 + 8);
		return new AllProgramsRects(categoryX, categoryY, categoryW, categoryH,
			moduleX, moduleW, moduleHVisible);
	}
	
	private boolean isLaunchableHack(Hack hack)
	{
		if(hack == null)
			return false;
		if(hack instanceof ClickGuiHack || hack instanceof AltGuiHack
			|| hack instanceof NavigatorHack || hack instanceof XpGuiHack)
			return false;
		if(hack == WurstClient.INSTANCE.getHax().globalToggleHack)
			return false;
		
		return !(WurstClient.INSTANCE.getHax().tooManyHaxHack.isEnabled()
			&& WurstClient.INSTANCE.getHax().tooManyHaxHack.isBlocked(hack)
			&& !hack.isEnabled());
	}
	
	private boolean isInsideStartMenu(double mouseX, double mouseY)
	{
		StartMenuRects rects = getStartMenuRects();
		if(isInsideRect(mouseX, mouseY, rects.x, rects.y, rects.width,
			rects.height))
			return true;
		
		if(!allProgramsOpen)
			return false;
		
		AllProgramsRects ap = getAllProgramsRects(rects);
		if(isInsideRect(mouseX, mouseY, ap.categoryX, ap.categoryY,
			ap.categoryW, ap.categoryH))
			return true;
		
		if(hoveredCategory != null)
		{
			Map<String, List<Hack>> byCategory = getHacksByCategory();
			int index =
				new ArrayList<>(byCategory.keySet()).indexOf(hoveredCategory);
			if(index >= 0)
			{
				int modY = ap.categoryY + 4 + index * 20;
				modY = clamp(modY, 4,
					height - taskbarHeight() - ap.moduleHVisible - 2);
				if(isInsideRect(mouseX, mouseY, ap.moduleX, modY, ap.moduleW,
					ap.moduleHVisible))
					return true;
			}
		}
		
		return false;
	}
	
	private StartMenuRects getStartMenuRects()
	{
		int menuWidth = Math.min(440, Math.max(360, width - 40));
		int menuHeight =
			Math.min(430, Math.max(320, height - taskbarHeight() - 18));
		int x = 2;
		int y = height - taskbarHeight() - menuHeight;
		int header = 56;
		int footer = 34;
		int leftWidth = (int)(menuWidth * 0.54F);
		return new StartMenuRects(x, y, menuWidth, menuHeight, header, footer,
			leftWidth);
	}
	
	private int getStartButtonX()
	{
		return win2000Theme ? 4 : 0;
	}
	
	private int getStartButtonY()
	{
		int y1 = height - taskbarHeight();
		return win2000Theme ? y1 + 3 : y1;
	}
	
	private int getStartButtonWidth()
	{
		return win2000Theme ? START_BUTTON_WIDTH : START_BUTTON_WIDTH - 14;
	}
	
	private int getStartButtonHeight()
	{
		return win2000Theme ? taskbarHeight() - 6 : taskbarHeight();
	}
	
	private List<TaskbarButtonRect> getTaskbarButtonRects()
	{
		ArrayList<TaskbarButtonRect> result = new ArrayList<>();
		List<XpModuleWindow> windows = windowManager.getWindows();
		if(windows.isEmpty())
			return result;
		
		int areaX = getStartButtonX() + getStartButtonWidth() + 8;
		int areaY = height - taskbarHeight() + 4;
		int areaW = width - areaX - TASKBAR_RIGHT_WIDTH - 6;
		if(areaW <= 20)
			return result;
		
		int count = windows.size();
		int buttonW = clamp(areaW / count, 88, 180);
		int maxButtons = Math.max(1, areaW / buttonW);
		int skip = Math.max(0, count - maxButtons);
		
		for(int i = skip; i < count; i++)
		{
			int slot = i - skip;
			int x = areaX + slot * buttonW;
			result.add(new TaskbarButtonRect(windows.get(i), x, areaY,
				buttonW - 4, taskbarHeight() - 8));
		}
		
		return result;
	}
	
	private WindowRects computeWindowRects(XpModuleWindow window)
	{
		int contentX = window.getX() + WINDOW_BORDER + WINDOW_INNER_PAD;
		int contentY = window.getY() + TITLE_BAR_HEIGHT + WINDOW_INNER_PAD;
		int contentWidth =
			window.getWidth() - WINDOW_BORDER * 2 - WINDOW_INNER_PAD * 2;
		int settingsY = contentY + 28;
		int settingsHeight =
			window.getHeight() - TITLE_BAR_HEIGHT - WINDOW_INNER_PAD * 2 - 30;
		return new WindowRects(contentX, contentY, contentWidth, settingsY,
			settingsHeight);
	}
	
	private String clipText(String text, int maxWidth)
	{
		if(text == null)
			return "";
		
		if(minecraft.font.width(text) <= maxWidth)
			return text;
		
		String dots = "...";
		int dotsW = minecraft.font.width(dots);
		StringBuilder builder = new StringBuilder();
		for(char c : text.toCharArray())
		{
			if(minecraft.font.width(builder.toString() + c) + dotsW > maxWidth)
				break;
			builder.append(c);
		}
		return builder + dots;
	}
	
	private boolean isInsideRect(double mouseX, double mouseY, int x, int y,
		int width, int height)
	{
		return mouseX >= x && mouseY >= y && mouseX < x + width
			&& mouseY < y + height;
	}
	
	private int withAlpha(int color, int alpha)
	{
		return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
	}
	
	private int blendColor(int a, int b, double t)
	{
		t = Math.max(0.0, Math.min(1.0, t));
		int aa = (a >>> 24) & 0xFF;
		int ar = (a >>> 16) & 0xFF;
		int ag = (a >>> 8) & 0xFF;
		int ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF;
		int br = (b >>> 16) & 0xFF;
		int bg = (b >>> 8) & 0xFF;
		int bb = b & 0xFF;
		int ca = (int)Math.round(aa + (ba - aa) * t);
		int cr = (int)Math.round(ar + (br - ar) * t);
		int cg = (int)Math.round(ag + (bg - ag) * t);
		int cb = (int)Math.round(ab + (bb - ab) * t);
		return ((ca & 0xFF) << 24) | ((cr & 0xFF) << 16) | ((cg & 0xFF) << 8)
			| (cb & 0xFF);
	}
	
	private int clamp(int value, int min, int max)
	{
		if(value < min)
			return min;
		if(value > max)
			return max;
		return value;
	}
	
	private int taskbarHeight()
	{
		return win2000Theme ? 24 : TASKBAR_HEIGHT;
	}
	
	public boolean isKeyboardInputCaptured()
	{
		return searchFieldFocused || settingsUrlFocused
			|| settingsStartIconFocused || textSettingEditSession != null;
	}
	
	private record WindowRects(int contentX, int contentY, int contentWidth,
		int settingsY, int settingsHeight)
	{}
	
	private record StartMenuRects(int x, int y, int width, int height,
		int headerHeight, int footerHeight, int leftWidth)
	{}
	
	private record SettingNode(Setting setting, int depth, String groupKey,
		boolean expanded)
	{}
	
	private record SliderDrag(XpModuleWindow window, SliderSetting setting,
		int sliderX, int sliderWidth)
	{}
	
	private record TaskbarButtonRect(XpModuleWindow window, int x, int y,
		int width, int height)
	{}
	
	private record SettingScrollDrag(XpModuleWindow window, int barY, int barH,
		int maxScroll)
	{}
	
	private record SearchDialogRects(int x, int y, int w, int h, int leftX,
		int leftY, int leftW, int leftH, int inputX, int inputY, int inputW,
		int resultsX, int resultsY, int resultsW, int resultsH)
	{}
	
	private record SettingsDialogRects(int x, int y, int w, int h,
		int modeImageX, int modeGradientX, int modeNoneX, int modeY, int modeW,
		int opacityX, int opacityY, int opacityW, int urlX, int urlY, int urlW,
		int applyX, int applyY, int applyW, int defaultX, int defaultY,
		int defaultW, int startIconX, int startIconY, int startIconW,
		int startApplyX, int startApplyY, int startApplyW, int listItemsX,
		int listItemsY, int listItemsW, int themeY, int themeXpX, int theme2kX,
		int themeW)
	{}
	
	private record AllProgramsRects(int categoryX, int categoryY, int categoryW,
		int categoryH, int moduleX, int moduleW, int moduleHVisible)
	{}
	
	private record ScrollbarInfo(int barX, int barY, int barW, int barH,
		int maxScroll)
	{}
	
	private record GifFrameLayout(int x, int y, int w, int h)
	{}
	
	private record UiScrollbarDrag(ScrollbarTarget target, int barX, int barY,
		int barW, int barH, int maxScroll)
	{}
	
	private static final class TextSettingEditSession
	{
		private final TextFieldSetting setting;
		private boolean selectAll;
		
		private TextSettingEditSession(TextFieldSetting setting)
		{
			this.setting = setting;
		}
	}
	
	private enum DesktopBackgroundMode
	{
		IMAGE,
		GRADIENT,
		NONE
	}
	
	private enum ScrollbarTarget
	{
		FAVORITES,
		ALLPROGRAMS_MODULES,
		SEARCH_RESULTS
	}
}
