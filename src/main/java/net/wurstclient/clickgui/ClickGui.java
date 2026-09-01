/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.Category;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.config.BuildConfig;
import net.wurstclient.clickgui.components.FeatureButton;
import net.wurstclient.clickgui.modern.ModernFeatureButton;
import net.wurstclient.clickgui.modern.ModernSettingComponent;
import net.wurstclient.clickgui.modern.ModernWindow;
import net.wurstclient.hacks.ClickGuiHack;
import net.wurstclient.hacks.TooManyHaxHack;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.json.JsonUtils;

public final class ClickGui
{
	private static final int MODERN_TITLE_CONTROL_SIZE = 13;
	private static final int MODERN_TITLE_CONTROL_SLOT = 14;
	private static final int MODERN_TITLE_CONTROL_RIGHT_PADDING = 8;
	private static final WurstClient WURST = WurstClient.INSTANCE;
	private static final Minecraft MC = WurstClient.MC;
	private static final Set<String> MOVE_TO_CLIENT_SETTINGS =
		Set.of("DisableWurst", "CommandPrefix", "Changelog",
			"ConnectionLogOverlay", "NoTelemetry", "NoChatReports",
			"ForceAllowChats", "VanillaSpoof", "Translations");
	
	private final ArrayList<Window> windows = new ArrayList<>();
	private final ArrayList<Popup> popups = new ArrayList<>();
	private final Path windowsFile;
	// Phase 1 Modern ClickGUI: never write Modern layout into Classic
	// windows.json.
	private final Path modernWindowsFile;
	private final Map<String, Window> modernCategoryWindows =
		new LinkedHashMap<>();
	private boolean modernStyle;
	// Phase 5: transient global search; never persisted as layout or hack
	// state.
	private String modernSearchQuery = "";
	private boolean modernSearchActive;
	private final LinkedHashMap<String, CategorySnapshot> modernSearchSnapshot =
		new LinkedHashMap<>();
	private int modernSearchBarY;
	private int modernSearchWindowCap;
	private final Map<String, String> modernSelectedSections = new HashMap<>();
	private final ArrayList<Feature> modernFeatures = new ArrayList<>();
	private int modernScreenWidth = -1;
	private int modernScreenHeight = -1;
	
	private float[] bgColor = new float[3];
	private float[] topBarColor = new float[3];
	private float[] hackHeaderColor = new float[3];
	private float[] acColor = new float[3];
	private float[] enabledHackColor = new float[3];
	private float[] dropdownButtonColor = new float[3];
	private float[] pinButtonColor = new float[3];
	private int txtColor;
	private float opacity;
	private float topBarOpacity;
	private float hackHeaderOpacity;
	private float ttOpacity;
	private int maxHeight;
	private int maxSettingsHeight;
	private boolean isolateWindows;
	
	private String tooltip = "";
	
	private boolean leftMouseButtonPressed;
	private boolean pinnedClickActive;
	private KeyboardInput keyboardInput;
	private boolean refreshPending;
	private boolean initializing;
	private int modernRowHeight = -1;
	private final Map<String, Integer> rememberedScroll = new HashMap<>();
	
	public ClickGui(Path windowsFile)
	{
		this.windowsFile = windowsFile;
		modernWindowsFile = windowsFile.resolveSibling("modern-windows.json");
	}
	
	/**
	 * Find a window by its title, or null if not present.
	 */
	public Window findWindowByTitle(String title)
	{
		for(Window w : windows)
			if(w.getTitle().equals(title))
				return w;
		return null;
	}
	
	/**
	 * Bring a window to the front (top of render order).
	 */
	public void bringWindowToFront(Window w)
	{
		if(windows.remove(w))
			windows.add(w);
	}
	
	public void init()
	{
		if(initializing)
			return;
		
		initializing = true;
		try
		{
			initImpl();
		}finally
		{
			initializing = false;
		}
	}
	
	private void initImpl()
	{
		modernStyle = WURST.getHax().clickGuiHack.isModernStyle();
		if(modernStyle)
		{
			initModern();
			return;
		}
		LinkedHashMap<String, WindowState> reopenSettingsWindows =
			captureOpenSettingsWindows();
		
		// Clear existing windows/popups so repeated init() calls rebuild
		// the UI instead of duplicating entries.
		windows.clear();
		popups.clear();
		updateColors();
		
		LinkedHashMap<String, Window> windowMap = new LinkedHashMap<>();
		for(Category category : Category.values())
			windowMap.put(category.getName(), new Window(category.getName()));
		
		ArrayList<Feature> features = new ArrayList<>();
		features.addAll(WURST.getHax().getAllHax());
		features.addAll(WURST.getCmds().getAllCmds());
		features.addAll(WURST.getOtfs().getAllOtfs());
		
		TooManyHaxHack tooManyHax = WURST.getHax().tooManyHaxHack;
		for(Feature f : features)
		{
			if(f == WURST.getHax().globalToggleHack)
				continue;
			if(f == WURST.getCmds().autoBuildCmd)
				continue;
			if(f == WURST.getCmds().lootSorterCmd
				|| f == WURST.getCmds().lootSortCmd)
				continue;
			
			if(MOVE_TO_CLIENT_SETTINGS.stream()
				.anyMatch(name -> name.equalsIgnoreCase(f.getName())))
				continue;
				
			// When TooManyHax is enabled, hide hacks that it disabled from
			// the ClickGUI to avoid cluttering the UI. The Navigator should
			// keep showing all features, so we only apply this filter here.
			if(f instanceof net.wurstclient.hack.Hack
				&& tooManyHax.shouldHideEverywhere(f))
			{
				continue;
			}
			
			String categoryName = f.getCategoryName();
			if(categoryName == null || categoryName.isBlank())
				continue;
			
			Window window = windowMap.get(categoryName);
			if(window == null)
			{
				window = new Window(categoryName);
				windowMap.put(categoryName, window);
			}
			window.add(new FeatureButton(f));
		}
		
		for(Window window : windowMap.values())
			sortWindowByFeatureName(window);
		// add favorites window entries (show favorites in the Favorites
		// category). Respect TooManyHax hiding behaviour here as well so
		// favorite hacks disabled by TooManyHax don't appear in ClickGUI.
		for(Feature f : features)
		{
			if(!(f instanceof net.wurstclient.hack.Hack
				&& ((net.wurstclient.hack.Hack)f).isFavorite()))
				continue;
			
			if(f instanceof net.wurstclient.hack.Hack
				&& tooManyHax.shouldHideEverywhere(f))
			{
				continue;
			}
			
			windowMap.get(net.wurstclient.Category.FAVORITES.getName())
				.add(new FeatureButton(f));
		}
		// ensure favourites window is sorted alphabetically
		Window favWindow =
			windowMap.get(net.wurstclient.Category.FAVORITES.getName());
		if(favWindow != null)
			sortFavoritesWindow(favWindow);
		
		windowMap.values().stream().filter(window -> window.countChildren() > 0)
			.forEach(windows::add);
		
		Window uiSettings = new Window("Client Settings");
		if(BuildConfig.includesOtherFeature("wurstLogoOtf"))
			uiSettings.add(new FeatureButton(WURST.getOtfs().wurstLogoOtf));
		if(BuildConfig.includesOtherFeature("hackListOtf"))
			uiSettings.add(new FeatureButton(WURST.getOtfs().hackListOtf));
		if(BuildConfig.includesOtherFeature("keybindManagerOtf"))
			uiSettings
				.add(new FeatureButton(WURST.getOtfs().keybindManagerOtf));
		if(BuildConfig.includesOtherFeature("presetManagerOtf"))
			uiSettings.add(new FeatureButton(WURST.getOtfs().presetManagerOtf));
		if(BuildConfig.includesOtherFeature("wurstOptionsOtf"))
			uiSettings.add(new FeatureButton(WURST.getOtfs().wurstOptionsOtf));
		if(BuildConfig.includesHack("globalToggleHack"))
			uiSettings.add(new FeatureButton(WURST.getHax().globalToggleHack));
		if(BuildConfig.includesHack("clickGuiHack"))
		{
			ClickGuiHack clickGuiHack = WURST.getHax().clickGuiHack;
			// Keep ClickGUI settings behind a dedicated settings entry in
			// Classic, matching Modern and avoiding an oversized inline
			// settings
			// list in Client Settings.
			uiSettings.add(new FeatureButton(clickGuiHack));
		}
		// These features are intentionally hidden from their normal categories,
		// but their settings still belong in the Classic Client Settings
		// window.
		for(Feature feature : features)
		{
			if(!MOVE_TO_CLIENT_SETTINGS.stream()
				.anyMatch(name -> name.equalsIgnoreCase(feature.getName())))
				continue;
			feature.getSettings().values().stream().peek(Setting::update)
				.filter(Setting::isVisibleInGui).map(Setting::getComponent)
				.filter(Objects::nonNull).forEach(uiSettings::add);
		}
		if(uiSettings.countChildren() > 0)
			windows.add(uiSettings);
			
		// Removed dedicated Chest Tools window so Chest Search isn't shown in
		// its own category/window.
		
		for(Window window : windows)
			window.setMinimized(true);
		
		if(BuildConfig.includesHack("radarHack"))
			windows.add(WurstClient.INSTANCE.getHax().radarHack.getWindow());
		
		int x = 5;
		int y = 5;
		int scaledWidth = MC.getWindow().getGuiScaledWidth();
		for(Window window : windows)
		{
			window.pack();
			// Ensure Chest Tools is not minimized so it’s visible by default
			if(window.getTitle().equals("Chest Tools"))
				window.setMinimized(false);
			if(x + window.getWidth() + 5 > scaledWidth)
			{
				x = 5;
				y += 18;
			}
			window.setX(x);
			window.setY(y);
			x += window.getWidth() + 5;
		}
		
		JsonObject json;
		try(BufferedReader reader = Files.newBufferedReader(windowsFile))
		{
			json = JsonParser.parseReader(reader).getAsJsonObject();
			
		}catch(NoSuchFileException e)
		{
			saveWindows();
			return;
			
		}catch(Exception e)
		{
			System.out.println("Failed to load " + windowsFile.getFileName());
			e.printStackTrace();
			
			saveWindows();
			return;
		}
		
		for(Window window : windows)
		{
			JsonElement jsonWindow = json.get(window.getTitle());
			if(jsonWindow == null || !jsonWindow.isJsonObject())
				continue;
			
			JsonElement jsonX = jsonWindow.getAsJsonObject().get("x");
			if(jsonX.isJsonPrimitive() && jsonX.getAsJsonPrimitive().isNumber())
				window.setX(jsonX.getAsInt());
			
			JsonElement jsonY = jsonWindow.getAsJsonObject().get("y");
			if(jsonY.isJsonPrimitive() && jsonY.getAsJsonPrimitive().isNumber())
				window.setY(jsonY.getAsInt());
			
			JsonElement jsonMinimized =
				jsonWindow.getAsJsonObject().get("minimized");
			if(jsonMinimized.isJsonPrimitive()
				&& jsonMinimized.getAsJsonPrimitive().isBoolean())
				window.setMinimized(jsonMinimized.getAsBoolean());
			
			JsonElement jsonPinned = jsonWindow.getAsJsonObject().get("pinned");
			if(jsonPinned.isJsonPrimitive()
				&& jsonPinned.getAsJsonPrimitive().isBoolean())
				window.setPinned(jsonPinned.getAsBoolean());
			
			JsonElement scrollElement =
				jsonWindow.getAsJsonObject().get("scrollOffset");
			applySavedScroll(window, scrollElement);
		}
		
		// Recreate any pinned settings windows and setting-group popout
		// windows.
		for(java.util.Map.Entry<String, JsonElement> e : json.entrySet())
		{
			String title = e.getKey();
			boolean exists = false;
			for(Window w : windows)
				if(w.getTitle().equals(title))
				{
					exists = true;
					break;
				}
			if(exists)
				continue;
			
			final String groupSeparator = " Settings > ";
			int groupSplit = title.indexOf(groupSeparator);
			if(groupSplit >= 0)
			{
				String featName = title.substring(0, groupSplit);
				String groupName =
					title.substring(groupSplit + groupSeparator.length());
				Feature matched = null;
				for(Feature f : features)
					if(f.getName().equals(featName))
					{
						matched = f;
						break;
					}
				if(matched == null)
					continue;
				
				SettingGroup group = null;
				for(Setting setting : matched.getSettings().values())
					if(setting instanceof SettingGroup g
						&& g.getName().equals(groupName))
					{
						group = g;
						break;
					}
				if(group == null)
					continue;
				
				try
				{
					Window popout = new Window(title);
					for(Setting s : group.getChildren())
					{
						Component c = s.getComponent();
						if(c != null)
							popout.add(c);
					}
					popout.pack();
					popout.setPinnable(true);
					popout.setClosable(true);
					
					JsonObject jw = e.getValue().getAsJsonObject();
					JsonElement jx = jw.get("x");
					if(jx != null && jx.isJsonPrimitive()
						&& jx.getAsJsonPrimitive().isNumber())
						popout.setX(jx.getAsInt());
					JsonElement jy = jw.get("y");
					if(jy != null && jy.isJsonPrimitive()
						&& jy.getAsJsonPrimitive().isNumber())
						popout.setY(jy.getAsInt());
					JsonElement jm = jw.get("minimized");
					if(jm != null && jm.isJsonPrimitive()
						&& jm.getAsJsonPrimitive().isBoolean())
						popout.setMinimized(jm.getAsBoolean());
					JsonElement jp = jw.get("pinned");
					if(jp != null && jp.isJsonPrimitive()
						&& jp.getAsJsonPrimitive().isBoolean())
						popout.setPinned(jp.getAsBoolean());
					JsonElement scrollElement = jw.get("scrollOffset");
					applySavedScroll(popout, scrollElement);
					windows.add(popout);
				}catch(Throwable ignored)
				{
					// Best-effort.
				}
				continue;
			}
			
			final String suffix = " Settings";
			if(!title.endsWith(suffix))
				continue;
			String featName =
				title.substring(0, title.length() - suffix.length());
			Feature matched = null;
			for(Feature f : features)
				if(f.getName().equals(featName))
				{
					matched = f;
					break;
				}
			if(matched == null)
				continue;
			
			try
			{
				SettingsWindow sw =
					new SettingsWindow(matched, windows.get(0), 0);
				JsonObject jw = e.getValue().getAsJsonObject();
				JsonElement jx = jw.get("x");
				if(jx != null && jx.isJsonPrimitive()
					&& jx.getAsJsonPrimitive().isNumber())
					sw.setX(jx.getAsInt());
				JsonElement jy = jw.get("y");
				if(jy != null && jy.isJsonPrimitive()
					&& jy.getAsJsonPrimitive().isNumber())
					sw.setY(jy.getAsInt());
				JsonElement jm = jw.get("minimized");
				if(jm != null && jm.isJsonPrimitive()
					&& jm.getAsJsonPrimitive().isBoolean())
					sw.setMinimized(jm.getAsBoolean());
				JsonElement jp = jw.get("pinned");
				if(jp != null && jp.isJsonPrimitive()
					&& jp.getAsJsonPrimitive().isBoolean())
					sw.setPinned(jp.getAsBoolean());
				JsonElement scrollElement = jw.get("scrollOffset");
				applySavedScroll(sw, scrollElement);
				windows.add(sw);
			}catch(Throwable ignored)
			{
				// Best-effort.
			}
		}
		
		// Reopen settings windows that were open when the GUI was closed.
		reopenTransientSettingsWindows(reopenSettingsWindows, features);
		
	}
	
	/**
	 * Saves the current window layout (positions, minimized state, scroll
	 * offsets, pinned state) so it can be restored next time ClickGUI opens.
	 */
	private void initModern()
	{
		modernSearchQuery = "";
		modernSearchActive = false;
		modernSearchWindowCap = 0;
		modernSearchSnapshot.clear();
		modernRowHeight = WURST.getHax().clickGuiHack.getRowHeight();
		LinkedHashMap<String, WindowState> reopenSettings =
			captureOpenModernSettingsWindows();
		Map<String, String> reopenSections =
			new HashMap<>(modernSelectedSections);
		windows.clear();
		popups.clear();
		modernCategoryWindows.clear();
		modernSelectedSections.clear();
		modernFeatures.clear();
		updateColors();
		
		for(Category category : Category.values())
		{
			Window window = new ModernWindow(category.getName());
			window.setClosable(true);
			modernCategoryWindows.put(category.getName(), window);
			windows.add(window);
		}
		
		Window clientSettings = new ModernWindow("Client Settings");
		clientSettings.setClosable(true);
		if(BuildConfig.includesOtherFeature("wurstLogoOtf"))
			clientSettings
				.add(new ModernFeatureButton(WURST.getOtfs().wurstLogoOtf));
		if(BuildConfig.includesOtherFeature("hackListOtf"))
			clientSettings
				.add(new ModernFeatureButton(WURST.getOtfs().hackListOtf));
		if(BuildConfig.includesOtherFeature("keybindManagerOtf"))
			clientSettings.add(
				new ModernFeatureButton(WURST.getOtfs().keybindManagerOtf));
		if(BuildConfig.includesOtherFeature("presetManagerOtf"))
			clientSettings
				.add(new ModernFeatureButton(WURST.getOtfs().presetManagerOtf));
		if(BuildConfig.includesOtherFeature("wurstOptionsOtf"))
			clientSettings
				.add(new ModernFeatureButton(WURST.getOtfs().wurstOptionsOtf));
		if(BuildConfig.includesHack("globalToggleHack"))
			clientSettings
				.add(new ModernFeatureButton(WURST.getHax().globalToggleHack));
		if(BuildConfig.includesHack("clickGuiHack"))
			clientSettings
				.add(new ModernFeatureButton(WURST.getHax().clickGuiHack));
		
		ArrayList<Feature> features = new ArrayList<>();
		features.addAll(WURST.getHax().getAllHax());
		features.addAll(WURST.getCmds().getAllCmds());
		features.addAll(WURST.getOtfs().getAllOtfs());
		modernFeatures.addAll(features);
		for(Feature feature : features)
			if(MOVE_TO_CLIENT_SETTINGS.stream()
				.anyMatch(name -> name.equalsIgnoreCase(feature.getName())))
				for(Setting setting : feature.getSettings().values())
					addModernSetting(clientSettings, setting);
		modernCategoryWindows.put(clientSettings.getTitle(), clientSettings);
		windows.add(clientSettings);
		
		TooManyHaxHack tooManyHax = WURST.getHax().tooManyHaxHack;
		for(Feature feature : features)
		{
			if(feature == WURST.getHax().clickGuiHack
				|| !isFeatureVisibleInClickGui(feature, tooManyHax))
				continue;
			Window window =
				modernCategoryWindows.get(feature.getCategoryName());
			if(window != null)
				window.add(new ModernFeatureButton(feature));
		}
		
		Window favorites =
			modernCategoryWindows.get(Category.FAVORITES.getName());
		for(Feature feature : features)
			if(feature instanceof net.wurstclient.hack.Hack hack
				&& hack.isFavorite()
				&& !tooManyHax.shouldHideEverywhere(feature))
				favorites.add(new ModernFeatureButton(feature));
			
		for(Window window : modernCategoryWindows.values())
			window.pack();
		if(!loadModernWindowLayout())
		{
			layoutModernOverview();
			saveWindows();
		}
		reopenModernSettingsWindows(reopenSettings, features, reopenSections);
	}
	
	private LinkedHashMap<String, WindowState> captureOpenModernSettingsWindows()
	{
		LinkedHashMap<String, WindowState> states = new LinkedHashMap<>();
		for(Window window : windows)
			if(window instanceof net.wurstclient.clickgui.modern.ModernSettingsWindow
				&& !window.isClosing())
			{
				states.put(window.getTitle(), new WindowState(window));
				modernSelectedSections.put(window.getTitle(),
					((net.wurstclient.clickgui.modern.ModernSettingsWindow)window)
						.getSelectedSection());
			}
		return states;
	}
	
	private void reopenModernSettingsWindows(
		LinkedHashMap<String, WindowState> states, ArrayList<Feature> features,
		Map<String, String> sections)
	{
		for(WindowState state : states.values())
		{
			if(findWindowByTitle(state.title) != null
				|| !state.title.endsWith(" Settings"))
				continue;
			String displayName = state.title.substring(0,
				state.title.length() - " Settings".length());
			for(Feature feature : features)
				if(feature.getDisplayName().equals(displayName))
				{
					Window parent = modernCategoryWindows.getOrDefault(
						feature.getCategoryName(),
						modernCategoryWindows.get("Client Settings"));
					if(parent == null)
						break;
					Window window =
						new net.wurstclient.clickgui.modern.ModernSettingsWindow(
							feature, parent, 0);
					String selectedSection = sections.get(state.title);
					if(selectedSection != null)
						((net.wurstclient.clickgui.modern.ModernSettingsWindow)window)
							.selectSection(selectedSection);
					state.apply(window);
					addWindow(window);
					break;
				}
		}
	}
	
	private void addModernSetting(Window window, Setting setting)
	{
		if(setting == null || !setting.isVisibleInGui())
			return;
		Component component = ModernSettingComponent.supports(setting)
			? new ModernSettingComponent(setting) : setting.getComponent();
		if(component != null)
		{
			component.setHeight(getModernComponentHeight(component));
			window.add(component);
		}
	}
	
	public int getModernComponentHeight(Component component)
	{
		int rowHeight = getModernRowHeight();
		if(component instanceof ModernSettingComponent settingComponent
			&& settingComponent.isSlider())
			return Math.min(25, rowHeight + 4);
		boolean compact = component instanceof ModernSettingComponent
			|| component instanceof net.wurstclient.clickgui.components.ColorComponent
			|| component instanceof net.wurstclient.clickgui.components.ComboBoxComponent<?>
			|| component instanceof net.wurstclient.clickgui.components.StringDropdownComponent;
		return compact ? rowHeight : Math.max(rowHeight,
			Math.max(component.getHeight(), component.getDefaultHeight()));
	}
	
	private boolean isFeatureVisibleInClickGui(Feature feature,
		TooManyHaxHack tooManyHax)
	{
		if(feature == WURST.getHax().globalToggleHack
			|| feature == WURST.getCmds().autoBuildCmd
			|| feature == WURST.getCmds().lootSorterCmd
			|| feature == WURST.getCmds().lootSortCmd)
			return false;
		
		if(MOVE_TO_CLIENT_SETTINGS.stream()
			.anyMatch(name -> name.equalsIgnoreCase(feature.getName())))
			return false;
		
		if(feature instanceof net.wurstclient.hack.Hack
			&& tooManyHax.shouldHideEverywhere(feature))
			return false;
		
		String categoryName = feature.getCategoryName();
		return categoryName != null && !categoryName.isBlank();
	}
	
	private boolean loadModernWindowLayout()
	{
		try(BufferedReader reader = Files.newBufferedReader(modernWindowsFile))
		{
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			if(!json.has("layoutVersion"))
				return false;
			JsonElement sectionsElement = json.get("selectedSections");
			if(sectionsElement != null && sectionsElement.isJsonObject())
				for(Map.Entry<String, JsonElement> entry : sectionsElement
					.getAsJsonObject().entrySet())
					if(entry.getValue().isJsonPrimitive())
						modernSelectedSections.put(entry.getKey(),
							entry.getValue().getAsString());
			for(Window window : modernCategoryWindows.values())
			{
				JsonElement element = json.get(window.getTitle());
				if(element == null || !element.isJsonObject())
					continue;
				
				JsonObject state = element.getAsJsonObject();
				JsonElement x = state.get("x");
				JsonElement y = state.get("y");
				JsonElement minimized = state.get("minimized");
				JsonElement pinned = state.get("pinned");
				JsonElement width = state.get("width");
				if(x != null && x.isJsonPrimitive()
					&& x.getAsJsonPrimitive().isNumber())
					window.setX(x.getAsInt());
				if(y != null && y.isJsonPrimitive()
					&& y.getAsJsonPrimitive().isNumber())
					window.setY(y.getAsInt());
				window.setMinimized(false);
				if(minimized != null && minimized.isJsonPrimitive()
					&& minimized.getAsJsonPrimitive().isBoolean()
					&& minimized.getAsBoolean())
					windows.remove(window);
				if(pinned != null && pinned.isJsonPrimitive()
					&& pinned.getAsJsonPrimitive().isBoolean())
					window.setPinned(pinned.getAsBoolean());
			}
			return true;
		}catch(NoSuchFileException e)
		{
			return false;
		}catch(Exception e)
		{
			System.out
				.println("Failed to load " + modernWindowsFile.getFileName());
			e.printStackTrace();
			return false;
		}
	}
	
	private void layoutModernOverview()
	{
		int x = 5;
		int y = 34;
		int rowHeight = 0;
		int scaledWidth = MC.getWindow().getGuiScaledWidth();
		
		for(Window window : modernCategoryWindows.values())
		{
			window.pack();
			if(x + window.getWidth() + 5 > scaledWidth && x > 5)
			{
				x = 5;
				y += rowHeight + 5;
				rowHeight = 0;
			}
			window.setX(x);
			window.setY(y);
			x += window.getWidth() + 5;
			rowHeight = Math.max(rowHeight, window.getHeight());
		}
	}
	
	private Path getActiveWindowsFile()
	{
		return modernStyle ? modernWindowsFile : windowsFile;
	}
	
	private int getModernNavigationStartX()
	{
		int width = MC.font.width("Client Settings") + 10;
		for(Category category : Category.values())
			width += MC.font.width(category.getName()) + 12;
		return Math.max(4, (MC.getWindow().getGuiScaledWidth() - width) / 2);
	}
	
	private boolean handleModernNavigationClick(int mouseX, int mouseY,
		int mouseButton)
	{
		if(!modernStyle || mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT
			|| mouseY < 4 || mouseY >= 25)
			return false;
		
		int x = getModernNavigationStartX();
		for(Category category : Category.values())
		{
			int width = MC.font.width(category.getName()) + 10;
			if(mouseX >= x && mouseX < x + width)
			{
				Window window = modernCategoryWindows.get(category.getName());
				if(window != null)
				{
					// A visible category tab toggles its canvas window closed.
					// Reopening
					// the same tab restores the same object and persisted
					// position.
					if(windows.contains(window) && !window.isMinimized())
					{
						window.close();
						windows.remove(window);
					}else
					{
						window.reopen();
						if(!windows.contains(window))
							windows.add(window);
						bringWindowToFront(window);
					}
					saveWindows();
				}
				return true;
			}
			x += width + 2;
		}
		int clientWidth = MC.font.width("Client Settings") + 10;
		if(mouseX >= x && mouseX < x + clientWidth)
		{
			Window window = modernCategoryWindows.get("Client Settings");
			if(window != null)
			{
				if(windows.contains(window) && !window.isMinimized())
				{
					window.close();
					windows.remove(window);
				}else
				{
					window.reopen();
					if(!windows.contains(window))
						windows.add(window);
					bringWindowToFront(window);
				}
				saveWindows();
			}
			return true;
		}
		
		return false;
	}
	
	private void renderModernNavigation(GuiGraphicsExtractor context,
		int mouseX, int mouseY)
	{
		int screenWidth = MC.getWindow().getGuiScaledWidth();
		context.fill(0, 0, screenWidth, 30,
			RenderUtils.toIntColor(topBarColor, topBarOpacity));
		RenderUtils.drawLine2D(context, 0, 29, screenWidth, 29,
			RenderUtils.toIntColor(acColor, topBarOpacity));
		int x = getModernNavigationStartX();
		for(Category category : Category.values())
		{
			Window window = modernCategoryWindows.get(category.getName());
			boolean minimized = window != null && window.isMinimized();
			boolean open =
				window != null && windows.contains(window) && !minimized;
			boolean focused = open && !windows.isEmpty()
				&& windows.get(windows.size() - 1) == window;
			x = renderModernNavigationButton(context, category.getName(), x,
				mouseX, mouseY, open, minimized, focused) + 2;
		}
		Window clientSettings = modernCategoryWindows.get("Client Settings");
		boolean clientMinimized =
			clientSettings != null && clientSettings.isMinimized();
		boolean clientOpen = clientSettings != null
			&& windows.contains(clientSettings) && !clientMinimized;
		boolean clientFocused = clientOpen && !windows.isEmpty()
			&& windows.get(windows.size() - 1) == clientSettings;
		renderModernNavigationButton(context, "Client Settings", x, mouseX,
			mouseY, clientOpen, clientMinimized, clientFocused);
	}
	
	private int renderModernNavigationButton(GuiGraphicsExtractor context,
		String label, int x, int mouseX, int mouseY, boolean open,
		boolean minimized, boolean focused)
	{
		int width = MC.font.width(label) + 10;
		boolean hovering =
			mouseX >= x && mouseX < x + width && mouseY >= 4 && mouseY < 25;
		if(focused)
			context.fill(x, 4, x + width, 25, RenderUtils.toIntColor(acColor,
				Math.min(1F, topBarOpacity + 0.18F)));
		else if(open)
			context.fill(x, 4, x + width, 25,
				RenderUtils.toIntColor(acColor, topBarOpacity * 0.62F));
		else
			context.fill(x, 4, x + width, 25,
				RenderUtils.toIntColor(new float[]{bgColor[0] * 0.62F,
					bgColor[1] * 0.62F, bgColor[2] * 0.62F},
					topBarOpacity * 0.9F));
		if(hovering && !focused)
			RenderUtils.drawBorder2D(context, x, 4, x + width, 25,
				RenderUtils.toIntColor(acColor, topBarOpacity * 0.45F));
		if(open || focused)
			context.fill(x + 1, 25, x + width - 1, 27, RenderUtils.toIntColor(
				enabledHackColor, Math.min(1F, topBarOpacity * 0.85F)));
		context.text(MC.font, label, x + 5, 10, txtColor, false);
		return x + width;
	}
	
	public void persistWindowLayout()
	{
		rememberScrollOffsets();
		saveWindows();
	}
	
	public void requestRefresh()
	{
		if(initializing)
			return;
		
		refreshPending = true;
	}
	
	private LinkedHashMap<String, WindowState> captureOpenSettingsWindows()
	{
		LinkedHashMap<String, WindowState> reopenSettingsWindows =
			new LinkedHashMap<>();
		
		for(Window window : windows)
		{
			if(!(window instanceof SettingsWindow))
				continue;
			if(window.isClosing())
				continue;
			
			reopenSettingsWindows.put(window.getTitle(),
				new WindowState(window));
		}
		
		return reopenSettingsWindows;
	}
	
	private void reopenTransientSettingsWindows(
		LinkedHashMap<String, WindowState> reopenSettingsWindows,
		ArrayList<Feature> features)
	{
		if(reopenSettingsWindows.isEmpty())
			return;
		
		for(WindowState state : reopenSettingsWindows.values())
		{
			if(findWindowByTitle(state.title) != null)
				continue;
			
			final String suffix = " Settings";
			if(!state.title.endsWith(suffix))
				continue;
			
			String featName = state.title.substring(0,
				state.title.length() - suffix.length());
			Feature matched = null;
			for(Feature f : features)
				if(f.getName().equals(featName))
				{
					matched = f;
					break;
				}
			
			if(matched == null)
				continue;
			
			try
			{
				SettingsWindow sw =
					new SettingsWindow(matched, windows.get(0), 0);
				state.apply(sw);
				rememberedScroll.put(sw.getTitle(), sw.getScrollOffset());
				windows.add(sw);
			}catch(Throwable ignored)
			{
				// Best-effort: ignore any failure recreating windows
			}
		}
	}
	
	private void rememberScrollOffsets()
	{
		for(Window window : windows)
			rememberedScroll.put(window.getTitle(), window.getScrollOffset());
	}
	
	private int getConfiguredWindowMaxHeight(Window window)
	{
		boolean settingsWindow = window instanceof SettingsWindow
			|| window instanceof net.wurstclient.clickgui.modern.ModernSettingsWindow;
		return settingsWindow ? maxSettingsHeight : maxHeight;
	}
	
	private void applySavedScroll(Window window, JsonElement scrollElement)
	{
		int scroll = rememberedScroll.getOrDefault(window.getTitle(),
			window.getScrollOffset());
		
		if(scrollElement != null && scrollElement.isJsonPrimitive()
			&& scrollElement.getAsJsonPrimitive().isNumber())
		{
			scroll = scrollElement.getAsInt();
		}
		
		// Ensure validation uses the same height constraints as rendering,
		// otherwise the clamp below would force scroll=0 when max height
		// limits are applied only later during render.
		window.setMaxHeight(getConfiguredWindowMaxHeight(window));
		window.validate();
		scroll = Math.min(scroll, 0);
		scroll = Math.max(scroll, -window.getInnerHeight() + window.getHeight()
			- getHeaderHeight(window));
		window.setScrollOffset(scroll);
		rememberedScroll.put(window.getTitle(), scroll);
	}
	
	private static final class WindowState
	{
		final String title;
		final int x;
		final int y;
		final boolean minimized;
		final boolean pinned;
		final int scrollOffset;
		
		WindowState(Window window)
		{
			title = window.getTitle();
			x = window.getActualX();
			y = window.getActualY();
			minimized = window.isMinimized();
			pinned = window.isPinned();
			scrollOffset = window.getScrollOffset();
		}
		
		void apply(Window window)
		{
			window.setX(x);
			window.setY(y);
			window.setMinimized(minimized);
			window.setPinned(pinned);
			
			window.validate();
			int scroll = scrollOffset;
			scroll = Math.min(scroll, 0);
			scroll = Math.max(scroll,
				-window.getInnerHeight() + window.getHeight()
					- (window instanceof ModernWindow
						? WURST.getHax().clickGuiHack.getHeaderHeight() : 13));
			window.setScrollOffset(scroll);
		}
	}
	
	private void saveWindows()
	{
		if(modernSearchActive)
			return;
		
		JsonObject json = new JsonObject();
		if(modernStyle)
		{
			json.addProperty("layoutVersion", 1);
			JsonObject selectedSections = new JsonObject();
			for(Map.Entry<String, String> entry : modernSelectedSections
				.entrySet())
				selectedSections.addProperty(entry.getKey(), entry.getValue());
			json.add("selectedSections", selectedSections);
		}
		
		ArrayList<Window> windowsToSave = new ArrayList<>(windows);
		// Closed category windows are deliberately removed from the render
		// list.
		// Keep them in the Modern layout file so reopening after a GUI reload
		// restores the exact position, width and scroll state.
		if(modernStyle)
			for(Window window : modernCategoryWindows.values())
				if(!windowsToSave.contains(window))
					windowsToSave.add(window);
				
		for(Window window : windowsToSave)
		{
			// Persist pinned/position/minimized state for non-closable windows
			// as before. Also persist closable windows only when they're pinned
			// so user-constructed settings/popups that they pinned survive UI
			// reloads. This fixes lost pin state for per-feature settings
			// windows.
			if(window.isClosable() && !window.isPinned()
				&& !(modernStyle && window instanceof ModernWindow))
				continue;
			
			JsonObject jsonWindow = new JsonObject();
			jsonWindow.addProperty("x", window.getActualX());
			jsonWindow.addProperty("y", window.getActualY());
			jsonWindow.addProperty("minimized",
				modernStyle && window instanceof ModernWindow
					&& !windows.contains(window) || window.isMinimized());
			jsonWindow.addProperty("pinned", window.isPinned());
			if(modernStyle && window instanceof ModernWindow)
				jsonWindow.addProperty("width", window.getWidth());
			int savedScroll = rememberedScroll.getOrDefault(window.getTitle(),
				window.getScrollOffset());
			jsonWindow.addProperty("scrollOffset", savedScroll);
			json.add(window.getTitle(), jsonWindow);
		}
		
		Path stateFile = getActiveWindowsFile();
		try
		{
			Path parent = stateFile.getParent();
			if(parent != null)
				Files.createDirectories(parent);
			try(BufferedWriter writer = Files.newBufferedWriter(stateFile))
			{
				JsonUtils.PRETTY_GSON.toJson(json, writer);
			}
		}catch(IOException e)
		{
			System.out.println("Failed to save " + stateFile.getFileName());
			e.printStackTrace();
		}
	}
	
	public boolean handleMouseClick(MouseButtonEvent context)
	{
		int mouseX = (int)context.x();
		int mouseY = (int)context.y();
		int mouseButton = context.button();
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			leftMouseButtonPressed = true;
		
		if(handleModernNavigationClick(mouseX, mouseY, mouseButton))
		{
			closeActivePopups();
			return true;
		}
		
		boolean popupClicked =
			handlePopupMouseClick(mouseX, mouseY, mouseButton);
		boolean handled = popupClicked;
		
		if(!popupClicked)
		{
			boolean closedPopups = closeActivePopups();
			if(!closedPopups || modernStyle)
				handled = handleWindowMouseClick(mouseX, mouseY, mouseButton,
					context);
			else
				handled = closedPopups;
		}
		
		closeInvalidPopups();
		windows.removeIf(Window::isClosing);
		return handled;
	}
	
	public boolean handlePinnedMouseClick(MouseButtonEvent context)
	{
		int mouseX = (int)context.x();
		int mouseY = (int)context.y();
		int mouseButton = context.button();
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			leftMouseButtonPressed = true;
		
		if(handleModernNavigationClick(mouseX, mouseY, mouseButton))
		{
			closeActivePopups();
			return true;
		}
		
		boolean popupClicked =
			handlePinnedPopupMouseClick(mouseX, mouseY, mouseButton);
		boolean windowClicked = false;
		if(!popupClicked)
		{
			boolean closedPopups = closePinnedPopups();
			if(!closedPopups || modernStyle)
				windowClicked = handlePinnedWindowMouseClick(mouseX, mouseY,
					mouseButton, context);
		}
		
		for(Popup popup : popups)
		{
			Window parent = popup.getOwner().getParent();
			if(parent != null && parent.isClosing())
				popup.close();
		}
		
		windows.removeIf(Window::isClosing);
		popups.removeIf(Popup::isClosing);
		
		pinnedClickActive = popupClicked || windowClicked;
		return pinnedClickActive;
	}
	
	private boolean closeActivePopups()
	{
		if(popups.isEmpty())
			return false;
		
		for(Popup popup : popups)
			popup.close();
		
		return true;
	}
	
	private boolean closePinnedPopups()
	{
		boolean closedAny = false;
		for(Popup popup : popups)
		{
			Window parent = popup.getOwner().getParent();
			if(parent != null && parent.isPinned())
			{
				popup.close();
				closedAny = true;
			}
		}
		
		return closedAny;
	}
	
	public void handleMouseRelease(double mouseX, double mouseY,
		int mouseButton)
	{
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
		{
			leftMouseButtonPressed = false;
			boolean save = false;
			for(Window window : windows)
			{
				for(int i = 0; i < window.countChildren(); i++)
					window.getChild(i).handleMouseRelease(mouseButton);
				
				if(window.isDragging())
				{
					window.stopDragging();
					save = true;
				}
				if(window.isDraggingScrollbar())
				{
					window.stopDraggingScrollbar();
					save = true;
				}
				if(window.isResizing())
				{
					window.stopResizing();
					save = true;
				}
			}
			if(save)
				saveWindows();
		}
	}
	
	/**
	 * Updates an active window drag from the screen's mouse-drag event. The
	 * render loop still updates these states as a fallback for pinned overlays,
	 * but handling the event here makes dragging independent of render timing.
	 */
	public boolean handleMouseDrag(MouseButtonEvent context)
	{
		return updateActiveWindowDrag((int)context.x(), (int)context.y(),
			false);
	}
	
	public boolean handlePinnedMouseDrag(MouseButtonEvent context)
	{
		return updateActiveWindowDrag((int)context.x(), (int)context.y(), true);
	}
	
	/**
	 * Fallback for screens that do not forward mouseDragged events.
	 * MouseHandler
	 * supplies raw display coordinates, so convert them to GUI-scaled
	 * coordinates before updating the active drag.
	 */
	public boolean handleScaledMouseMove(double mouseX, double mouseY)
	{
		boolean pinnedOnly = !(MC.gui
			.screen() instanceof net.wurstclient.clickgui.screens.ClickGuiScreen);
		return updateActiveWindowDrag((int)mouseX, (int)mouseY, pinnedOnly);
	}
	
	public boolean handleMouseMove(double rawMouseX, double rawMouseY)
	{
		int screenWidth = MC.getWindow().getScreenWidth();
		int screenHeight = MC.getWindow().getScreenHeight();
		if(screenWidth <= 0 || screenHeight <= 0)
			return false;
		
		int mouseX =
			(int)(rawMouseX * MC.getWindow().getGuiScaledWidth() / screenWidth);
		int mouseY = (int)(rawMouseY * MC.getWindow().getGuiScaledHeight()
			/ screenHeight);
		boolean pinnedOnly = !(MC.gui
			.screen() instanceof net.wurstclient.clickgui.screens.ClickGuiScreen);
		return updateActiveWindowDrag(mouseX, mouseY, pinnedOnly);
	}
	
	private boolean updateActiveWindowDrag(int mouseX, int mouseY,
		boolean pinnedOnly)
	{
		boolean handled = false;
		for(Window window : windows)
		{
			if(window.isInvisible() || pinnedOnly && !window.isPinned())
				continue;
			
			if(window.isDragging())
			{
				window.dragTo(mouseX, mouseY);
				handled = true;
			}
			if(window.isDraggingScrollbar())
			{
				window.dragScrollbarTo(mouseY);
				rememberedScroll.put(window.getTitle(),
					window.getScrollOffset());
				handled = true;
			}
			if(window.isResizing())
			{
				window.resizeTo(mouseX);
				handled = true;
			}
			
			if(!window.isMinimized() && !window.isDragging()
				&& !window.isDraggingScrollbar() && !window.isResizing())
			{
				window.validate();
				int cMouseX = mouseX - window.getX();
				int cMouseY = mouseY - window.getY() - getHeaderHeight(window);
				if(window.isScrollingEnabled())
					cMouseY -= window.getScrollOffset();
				if(handleComponentMouseDrag(window, cMouseX, cMouseY))
					handled = true;
			}
		}
		
		if(handled)
			leftMouseButtonPressed = true;
		return handled;
	}
	
	public boolean handlePinnedMouseRelease(double mouseX, double mouseY,
		int mouseButton)
	{
		handleMouseRelease(mouseX, mouseY, mouseButton);
		if(pinnedClickActive)
		{
			pinnedClickActive = false;
			return true;
		}
		
		return false;
	}
	
	public void handleMouseScroll(double mouseX, double mouseY, double delta)
	{
		if(delta == 0)
			return;
		
		if(handlePopupMouseScroll(mouseX, mouseY, delta))
			return;
		
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			Window window = windows.get(i);
			
			if(window.isMinimized() || window.isInvisible())
				continue;
			
			if(mouseX < window.getX()
				|| mouseY < window.getY() + getHeaderHeight(window))
				continue;
			if(mouseX >= window.getX() + window.getWidth()
				|| mouseY >= window.getY() + window.getHeight())
				continue;
			
			if(handleWindowComponentMouseScroll(window, mouseX, mouseY, delta))
			{
				closeInvalidPopups();
				break;
			}
			
			int dWheel = (int)delta * 4;
			if(dWheel == 0)
				return;
			
			if(!window.isScrollingEnabled())
				continue;
			
			int scroll = window.getScrollOffset() + dWheel;
			scroll = Math.min(scroll, 0);
			scroll = Math.max(scroll, -window.getInnerHeight()
				+ window.getHeight() - getHeaderHeight(window));
			window.setScrollOffset(scroll);
			rememberedScroll.put(window.getTitle(), scroll);
			closeInvalidPopups();
			break;
		}
	}
	
	public boolean handlePinnedMouseScroll(double mouseX, double mouseY,
		double delta)
	{
		if(delta == 0)
			return false;
		
		if(handlePinnedPopupMouseScroll(mouseX, mouseY, delta))
			return true;
		
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			Window window = windows.get(i);
			if(!window.isPinned() || window.isInvisible())
				continue;
			
			if(window.isMinimized())
				continue;
			
			if(mouseX < window.getX()
				|| mouseY < window.getY() + getHeaderHeight(window))
				continue;
			if(mouseX >= window.getX() + window.getWidth()
				|| mouseY >= window.getY() + window.getHeight())
				continue;
			
			if(handleWindowComponentMouseScroll(window, mouseX, mouseY, delta))
			{
				closeInvalidPopups();
				return true;
			}
			
			int dWheel = (int)delta * 4;
			if(dWheel == 0)
				return false;
			
			if(!window.isScrollingEnabled())
				continue;
			
			int scroll = window.getScrollOffset() + dWheel;
			scroll = Math.min(scroll, 0);
			scroll = Math.max(scroll, -window.getInnerHeight()
				+ window.getHeight() - getHeaderHeight(window));
			window.setScrollOffset(scroll);
			rememberedScroll.put(window.getTitle(), scroll);
			closeInvalidPopups();
			return true;
		}
		
		return false;
	}
	
	public boolean handleNavigatorPopupClick(double mouseX, double mouseY,
		int mouseButton)
	{
		boolean popupClicked =
			handlePopupMouseClick(mouseX, mouseY, mouseButton);
		
		if(popupClicked)
			closeInvalidPopups();
		
		return popupClicked;
	}
	
	public boolean handleNavigatorMouseScroll(double mouseX, double mouseY,
		double delta)
	{
		boolean popupScrolled = handlePopupMouseScroll(mouseX, mouseY, delta);
		if(popupScrolled)
			closeInvalidPopups();
		
		return popupScrolled;
	}
	
	public boolean handleKeyPressed(KeyEvent context)
	{
		if(modernStyle && keyboardInput == null)
		{
			if(context.key() == GLFW.GLFW_KEY_ESCAPE
				&& !modernSearchQuery.isEmpty())
			{
				modernSearchQuery = "";
				updateModernSearchResults();
				return true;
			}
			if(context.key() == GLFW.GLFW_KEY_BACKSPACE
				&& !modernSearchQuery.isEmpty())
			{
				modernSearchQuery = modernSearchQuery.substring(0,
					modernSearchQuery.offsetByCodePoints(0, modernSearchQuery
						.codePointCount(0, modernSearchQuery.length()) - 1));
				updateModernSearchResults();
				return true;
			}
		}
		if(keyboardInput != null && keyboardInput.onKeyPressed(context))
			return true;
		
		if(context.key() == GLFW.GLFW_KEY_ESCAPE && keyboardInput != null)
		{
			clearKeyboardInput();
			return true;
		}
		
		return false;
	}
	
	public boolean handleCharTyped(CharacterEvent event)
	{
		if(modernStyle && keyboardInput == null
			&& !Character.isISOControl(event.codepoint()))
		{
			modernSearchQuery +=
				new String(Character.toChars(event.codepoint()));
			updateModernSearchResults();
			return true;
		}
		return keyboardInput != null && keyboardInput.onCharTyped(event);
	}
	
	public void requestKeyboardInput(KeyboardInput handler)
	{
		if(handler == null || keyboardInput == handler)
			return;
		
		if(keyboardInput != null)
			clearKeyboardInput();
		
		keyboardInput = handler;
	}
	
	public void releaseKeyboardInput(KeyboardInput handler)
	{
		if(handler != null && keyboardInput == handler)
			keyboardInput = null;
	}
	
	public void clearKeyboardInput()
	{
		if(keyboardInput == null)
			return;
		
		KeyboardInput handler = keyboardInput;
		keyboardInput = null;
		handler.onKeyboardFocusLost();
	}
	
	public boolean isKeyboardInputCaptured()
	{
		return keyboardInput != null;
	}
	
	public void handleNavigatorMouseClick(double cMouseX, double cMouseY,
		int mouseButton, Window window, MouseButtonEvent context)
	{
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			leftMouseButtonPressed = true;
		
		handleComponentMouseClick(window, cMouseX, cMouseY, mouseButton,
			context);
		
		closeInvalidPopups();
	}
	
	public boolean handleNavigatorComponentMouseScroll(double cMouseX,
		double cMouseY, double delta, Window window)
	{
		if(delta == 0)
			return false;
		
		for(int i = window.countChildren() - 1; i >= 0; i--)
		{
			Component c = window.getChild(i);
			
			if(cMouseX < c.getX() || cMouseY < c.getY())
				continue;
			if(cMouseX >= c.getX() + c.getWidth()
				|| cMouseY >= c.getY() + c.getHeight())
				continue;
			
			if(c.handleMouseScroll(cMouseX, cMouseY, delta))
			{
				closeInvalidPopups();
				return true;
			}
			
			break;
		}
		
		return false;
	}
	
	public void closePopupsOutsideArea(Window window, int x1, int y1, int x2,
		int y2)
	{
		for(Popup popup : popups)
		{
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			if(parent == null || parent.isClosing())
			{
				popup.close();
				continue;
			}
			
			if(parent == window
				&& !isComponentVisibleWithinBounds(owner, x1, y1, x2, y2))
				popup.close();
		}
		
		popups.removeIf(Popup::isClosing);
	}
	
	public boolean handlePopupMouseClick(double mouseX, double mouseY,
		int mouseButton)
	{
		closeInvalidPopups();
		
		for(int i = popups.size() - 1; i >= 0; i--)
		{
			Popup popup = popups.get(i);
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			if(parent == null)
				continue;
			
			int x0 = parent.getX() + owner.getX();
			int y0 = parent.getY() + getHeaderHeight(parent)
				+ parent.getScrollOffset() + owner.getY();
			
			int x1 = x0 + popup.getX();
			int y1 = y0 + popup.getY();
			int x2 = x1 + popup.getWidth();
			int y2 = y1 + popup.getHeight();
			
			if(mouseX < x1 || mouseY < y1)
				continue;
			if(mouseX >= x2 || mouseY >= y2)
				continue;
			
			int cMouseX = (int)(mouseX - x0);
			int cMouseY = (int)(mouseY - y0);
			popup.handleMouseClick(cMouseX, cMouseY, mouseButton);
			
			// remove by object to avoid index-based removal issues if the
			// list was modified concurrently
			popups.remove(popup);
			popups.add(popup);
			closeInvalidPopups();
			return true;
		}
		
		return false;
	}
	
	private boolean handlePinnedPopupMouseClick(double mouseX, double mouseY,
		int mouseButton)
	{
		for(int i = popups.size() - 1; i >= 0; i--)
		{
			Popup popup = popups.get(i);
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			if(parent == null || !parent.isPinned())
				continue;
			
			int x0 = parent.getX() + owner.getX();
			int y0 = parent.getY() + getHeaderHeight(parent)
				+ parent.getScrollOffset() + owner.getY();
			
			int x1 = x0 + popup.getX();
			int y1 = y0 + popup.getY();
			int x2 = x1 + popup.getWidth();
			int y2 = y1 + popup.getHeight();
			
			if(mouseX < x1 || mouseY < y1)
				continue;
			if(mouseX >= x2 || mouseY >= y2)
				continue;
			
			int cMouseX = (int)(mouseX - x0);
			int cMouseY = (int)(mouseY - y0);
			popup.handleMouseClick(cMouseX, cMouseY, mouseButton);
			
			popups.remove(popup);
			popups.add(popup);
			closeInvalidPopups();
			return true;
		}
		
		return false;
	}
	
	private boolean handlePopupMouseScroll(double mouseX, double mouseY,
		double delta)
	{
		for(int i = popups.size() - 1; i >= 0; i--)
		{
			Popup popup = popups.get(i);
			if(popup.getWidth() <= 0 || popup.getHeight() <= 0)
				continue;
			
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			if(parent == null)
				continue;
			
			int x0 = parent.getX() + owner.getX();
			int y0 = parent.getY() + getHeaderHeight(parent)
				+ parent.getScrollOffset() + owner.getY();
			
			int x1 = x0 + popup.getX();
			int y1 = y0 + popup.getY();
			int x2 = x1 + popup.getWidth();
			int y2 = y1 + popup.getHeight();
			
			if(mouseX < x1 || mouseY < y1)
				continue;
			if(mouseX >= x2 || mouseY >= y2)
				continue;
			
			int cMouseX = (int)(mouseX - x0);
			int cMouseY = (int)(mouseY - y0);
			if(popup.handleMouseScroll(cMouseX, cMouseY, delta))
			{
				closeInvalidPopups();
				return true;
			}
		}
		
		return false;
	}
	
	private boolean handlePinnedPopupMouseScroll(double mouseX, double mouseY,
		double delta)
	{
		for(int i = popups.size() - 1; i >= 0; i--)
		{
			Popup popup = popups.get(i);
			if(popup.getWidth() <= 0 || popup.getHeight() <= 0)
				continue;
			
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			if(parent == null || !parent.isPinned())
				continue;
			
			int x0 = parent.getX() + owner.getX();
			int y0 = parent.getY() + getHeaderHeight(parent)
				+ parent.getScrollOffset() + owner.getY();
			
			int x1 = x0 + popup.getX();
			int y1 = y0 + popup.getY();
			int x2 = x1 + popup.getWidth();
			int y2 = y1 + popup.getHeight();
			
			if(mouseX < x1 || mouseY < y1)
				continue;
			if(mouseX >= x2 || mouseY >= y2)
				continue;
			
			int cMouseX = (int)(mouseX - x0);
			int cMouseY = (int)(mouseY - y0);
			if(popup.handleMouseScroll(cMouseX, cMouseY, delta))
			{
				closeInvalidPopups();
				return true;
			}
		}
		
		return false;
	}
	
	private void closeInvalidPopups()
	{
		for(Popup popup : popups)
		{
			Window parent = popup.getOwner().getParent();
			if(parent == null || parent.isClosing()
				|| !isPopupOwnerVisible(popup))
				popup.close();
		}
		
		popups.removeIf(Popup::isClosing);
	}
	
	private boolean isPopupOwnerVisible(Popup popup)
	{
		Component owner = popup.getOwner();
		Window parent = owner.getParent();
		if(parent == null || parent.isInvisible() || parent.isMinimized())
			return false;
		
		int x1 = parent.getX();
		int y1 = parent.getY() + getHeaderHeight(parent);
		int x2 = x1 + parent.getWidth();
		int y2 = parent.getY() + parent.getHeight();
		return isComponentVisibleWithinBounds(owner, x1, y1, x2, y2);
	}
	
	private boolean isComponentVisibleWithinBounds(Component c, int x1, int y1,
		int x2, int y2)
	{
		Window parent = c.getParent();
		int cx1 = parent.getX() + c.getX();
		int cy1 = parent.getY() + getHeaderHeight(parent)
			+ parent.getScrollOffset() + c.getY();
		int cx2 = cx1 + c.getWidth();
		int cy2 = cy1 + c.getHeight();
		return cx2 > x1 && cx1 < x2 && cy2 > y1 && cy1 < y2;
	}
	
	private int getHeaderHeight(Window window)
	{
		return window instanceof ModernWindow ? getModernHeaderHeight() : 13;
	}
	
	private boolean handleWindowMouseClick(int mouseX, int mouseY,
		int mouseButton, MouseButtonEvent context)
	{
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			Window window = windows.get(i);
			int windowCountBefore = windows.size();
			if(window.isInvisible())
				continue;
			
			int x1 = window.getX();
			int y1 = window.getY();
			int x2 = x1 + window.getWidth();
			int y2 = y1 + window.getHeight();
			int y3 = y1 + getHeaderHeight(window);
			
			if(mouseX < x1 || mouseY < y1)
				continue;
			if(mouseX >= x2 || mouseY >= y2)
				continue;
			
			if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT
				&& window.isResizable() && !window.isMinimized()
				&& mouseX >= x2 - 7 && mouseY >= y2 - 7)
				window.startResizing(mouseX);
			else if(mouseY < y3)
				handleTitleBarMouseClick(window, mouseX, mouseY, mouseButton);
			else if(!window.isMinimized())
			{
				window.validate();
				
				int cMouseX = mouseX - x1;
				int cMouseY = mouseY - y3;
				
				if(window.isScrollingEnabled()
					&& isOverScrollbar(window, mouseX, mouseY))
					handleScrollbarMouseClick(window, mouseX, mouseY,
						mouseButton);
				else
				{
					if(window.isScrollingEnabled())
						cMouseY -= window.getScrollOffset();
					
					handleComponentMouseClick(window, cMouseX, cMouseY,
						mouseButton, context);
				}
				
			}else
				continue;
				
			// remove by object to avoid index-based removal issues if the
			// windows list was modified concurrently
			if(!windows.contains(window))
				break;
			if(windows.size() != windowCountBefore)
				break;
			
			windows.remove(window);
			windows.add(window);
			return true;
		}
		return false;
	}
	
	private boolean handlePinnedWindowMouseClick(int mouseX, int mouseY,
		int mouseButton, MouseButtonEvent context)
	{
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			Window window = windows.get(i);
			int windowCountBefore = windows.size();
			if(window.isInvisible() || !window.isPinned())
				continue;
			
			int x1 = window.getX();
			int y1 = window.getY();
			int x2 = x1 + window.getWidth();
			int y2 = y1 + window.getHeight();
			int y3 = y1 + getHeaderHeight(window);
			
			if(mouseX < x1 || mouseY < y1)
				continue;
			if(mouseX >= x2 || mouseY >= y2)
				continue;
			
			if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT
				&& window.isResizable() && !window.isMinimized()
				&& mouseX >= x2 - 7 && mouseY >= y2 - 7)
				window.startResizing(mouseX);
			else if(mouseY < y3)
				handleTitleBarMouseClick(window, mouseX, mouseY, mouseButton);
			else if(!window.isMinimized())
			{
				window.validate();
				
				int cMouseX = mouseX - x1;
				int cMouseY = mouseY - y3;
				
				if(window.isScrollingEnabled()
					&& isOverScrollbar(window, mouseX, mouseY))
					handleScrollbarMouseClick(window, mouseX, mouseY,
						mouseButton);
				else
				{
					if(window.isScrollingEnabled())
						cMouseY -= window.getScrollOffset();
					
					handleComponentMouseClick(window, cMouseX, cMouseY,
						mouseButton, context);
				}
				
			}else
				continue;
			
			if(!windows.contains(window))
				break;
			if(windows.size() != windowCountBefore)
				break;
			
			windows.remove(window);
			windows.add(window);
			return true;
		}
		
		return false;
	}
	
	private void handleTitleBarMouseClick(Window window, int mouseX, int mouseY,
		int mouseButton)
	{
		if(mouseButton != 0)
			return;
		boolean modernWindow = window instanceof ModernWindow;
		int controlSize = modernWindow ? MODERN_TITLE_CONTROL_SIZE : 9;
		int controlSlot = modernWindow ? MODERN_TITLE_CONTROL_SLOT : 11;
		int controlY =
			window.getY()
				+ (modernWindow
					? Math
						.max(1,
							Math.round(
								(getModernHeaderHeight() - controlSize) / 2F))
					: 2);
		if(mouseY < controlY || mouseY >= controlY + controlSize)
		{
			window.startDragging(mouseX, mouseY);
			return;
		}
		
		int x3 = window.getX() + window.getWidth()
			- (modernWindow ? MODERN_TITLE_CONTROL_RIGHT_PADDING : 0);
		if(window.isClosable())
		{
			x3 -= controlSlot;
			if(mouseX >= x3 && mouseX < x3 + controlSize)
			{
				window.close();
				if(modernStyle && modernCategoryWindows.containsValue(window))
				{
					windows.remove(window);
					saveWindows();
				}
				return;
			}
		}
		if(window.isPinnable())
		{
			x3 -= controlSlot;
			if(mouseX >= x3 && mouseX < x3 + controlSize)
			{
				window.setPinned(!window.isPinned());
				saveWindows();
				return;
			}
		}
		if(window.isMinimizable())
		{
			x3 -= controlSlot;
			if(mouseX >= x3 && mouseX < x3 + controlSize)
			{
				window.setMinimized(!window.isMinimized());
				saveWindows();
				return;
			}
		}
		window.startDragging(mouseX, mouseY);
	}
	
	private boolean isOverScrollbar(Window window, int mouseX, int mouseY)
	{
		int x1 = window.getX() + window.getWidth() - 8;
		int x2 = window.getX() + window.getWidth();
		int y1 = window.getY() + getHeaderHeight(window)
			+ window.getScrollbarTrackTop();
		int y2 = window.getY() + getHeaderHeight(window)
			+ window.getScrollbarTrackBottom();
		return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
	}
	
	private void handleScrollbarMouseClick(Window window, int mouseX,
		int mouseY, int mouseButton)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		
		if(!isOverScrollbar(window, mouseX, mouseY))
			return;
		int localMouseY = mouseY - window.getY() - getHeaderHeight(window);
		int scrollbarY = window.getScrollbarThumbY();
		int scrollbarHeight = window.getScrollbarThumbHeight();
		if(localMouseY < scrollbarY
			|| localMouseY >= scrollbarY + scrollbarHeight)
			window.centerScrollbarOn(mouseY);
		window.startDraggingScrollbar(mouseY);
	}
	
	private void handleComponentMouseClick(Window window, double mouseX,
		double mouseY, int mouseButton, MouseButtonEvent context)
	{
		for(int i2 = window.countChildren() - 1; i2 >= 0; i2--)
		{
			Component c = window.getChild(i2);
			
			if(mouseX < c.getX() || mouseY < c.getY())
				continue;
			if(mouseX >= c.getX() + c.getWidth()
				|| mouseY >= c.getY() + c.getHeight())
				continue;
			
			if(keyboardInput != null && keyboardInput != c)
				clearKeyboardInput();
			
			c.handleMouseClick(mouseX, mouseY, mouseButton, context);
			break;
		}
	}
	
	private boolean handleComponentMouseDrag(Window window, double mouseX,
		double mouseY)
	{
		for(int i = window.countChildren() - 1; i >= 0; i--)
		{
			Component c = window.getChild(i);
			if(c.handleMouseDrag(mouseX, mouseY))
				return true;
		}
		return false;
	}
	
	private boolean handleWindowComponentMouseScroll(Window window,
		double mouseX, double mouseY, double delta)
	{
		window.validate();
		
		int cMouseX = (int)(mouseX - window.getX());
		int cMouseY = (int)(mouseY - window.getY() - getHeaderHeight(window));
		if(window.isScrollingEnabled())
		{
			cMouseY -= window.getScrollOffset();
		}
		
		return handleNavigatorComponentMouseScroll(cMouseX, cMouseY, delta,
			window);
	}
	
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		// Apply Classic/Modern selection immediately instead of waiting for the
		// screen to close and reopen.
		if(refreshPending
			|| modernStyle != WURST.getHax().clickGuiHack.isModernStyle()
			|| modernStyle && modernRowHeight != WURST.getHax().clickGuiHack
				.getRowHeight())
		{
			refreshPending = false;
			init();
		}
		
		updateColors();
		adaptModernLayoutToScreen();
		
		Matrix3x2fStack matrixStack = context.pose();
		matrixStack.pushMatrix();
		
		tooltip = "";
		if(modernStyle)
		{
			renderModernNavigation(context, mouseX, mouseY);
			renderModernSearchOverlay(context);
		}
		
		ArrayList<Window> visibleWindows = new ArrayList<>();
		for(Window window : windows)
		{
			if(window.isInvisible())
				continue;
			
			// dragging
			if(window.isDragging())
				if(isLeftMouseButtonPressed())
					window.dragTo(mouseX, mouseY);
				else
				{
					window.stopDragging();
					saveWindows();
				}
				
			// Modern windows support horizontal resize from the lower-right
			// grip.
			if(window.isResizing())
				if(isLeftMouseButtonPressed())
					window.resizeTo(mouseX);
				else
				{
					window.stopResizing();
					saveWindows();
				}
			
			// scrollbar dragging
			if(window.isDraggingScrollbar())
				if(isLeftMouseButtonPressed())
					window.dragScrollbarTo(mouseY);
				else
					window.stopDraggingScrollbar();
				
			visibleWindows.add(window);
		}
		
		if(isolateWindows && !visibleWindows.isEmpty())
			renderWindowsWithIsolation(context, visibleWindows, mouseX, mouseY,
				partialTicks);
		else
			for(Window window : visibleWindows)
			{
				context.guiRenderState.up();
				renderWindow(context, window, mouseX, mouseY, partialTicks);
			}
		
		renderPopups(context, mouseX, mouseY);
		renderTooltip(context, mouseX, mouseY);
		
		matrixStack.popMatrix();
	}
	
	public void renderPopups(GuiGraphicsExtractor context, int mouseX,
		int mouseY)
	{
		closeInvalidPopups();
		
		Matrix3x2fStack matrixStack = context.pose();
		for(Popup popup : popups)
		{
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			if(parent == null)
				continue;
			
			int x1 = parent.getX() + owner.getX();
			int y1 = parent.getY() + getHeaderHeight(parent)
				+ parent.getScrollOffset() + owner.getY();
			
			matrixStack.pushMatrix();
			matrixStack.translate(x1, y1);
			context.guiRenderState.up();
			
			int cMouseX = mouseX - x1;
			int cMouseY = mouseY - y1;
			popup.render(context, cMouseX, cMouseY);
			
			matrixStack.popMatrix();
		}
	}
	
	private void renderPinnedPopups(GuiGraphicsExtractor context, int mouseX,
		int mouseY)
	{
		Matrix3x2fStack matrixStack = context.pose();
		for(Popup popup : popups)
		{
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			if(parent == null || !parent.isPinned())
				continue;
			
			int x1 = parent.getX() + owner.getX();
			int y1 = parent.getY() + getHeaderHeight(parent)
				+ parent.getScrollOffset() + owner.getY();
			
			matrixStack.pushMatrix();
			matrixStack.translate(x1, y1);
			context.guiRenderState.up();
			
			int cMouseX = mouseX - x1;
			int cMouseY = mouseY - y1;
			popup.render(context, cMouseX, cMouseY);
			
			matrixStack.popMatrix();
		}
	}
	
	public void renderTooltip(GuiGraphicsExtractor context, int mouseX,
		int mouseY)
	{
		if(tooltip.isEmpty())
			return;
		
		String[] lines = tooltip.split("\n");
		Font tr = MC.font;
		
		int tw = 0;
		int th = lines.length * tr.lineHeight;
		for(String line : lines)
		{
			int lw = tr.width(line);
			if(lw > tw)
				tw = lw;
		}
		int sw = MC.gui.screen().width;
		int sh = MC.gui.screen().height;
		
		int xt1 = mouseX + tw + 11 <= sw ? mouseX + 8 : mouseX - tw - 8;
		int xt2 = xt1 + tw + 3;
		int yt1 = mouseY + th - 2 <= sh ? mouseY - 4 : mouseY - th - 4;
		int yt2 = yt1 + th + 2;
		
		context.guiRenderState.up();
		
		// background
		float[] tooltipBg =
			{bgColor[0] * 0.42F, bgColor[1] * 0.42F, bgColor[2] * 0.42F};
		context.fill(xt1, yt1, xt2, yt2,
			RenderUtils.toIntColor(tooltipBg, ttOpacity));
		
		// outline
		RenderUtils.drawBorder2D(context, xt1, yt1, xt2, yt2,
			RenderUtils.toIntColor(acColor, 0.5F));
		
		// text
		context.guiRenderState.up();
		for(int i = 0; i < lines.length; i++)
			context.text(tr, lines[i], xt1 + 2, yt1 + 2 + i * tr.lineHeight,
				txtColor, false);
	}
	
	public void renderPinnedWindows(GuiGraphicsExtractor context,
		float partialTicks)
	{
		// Apply Classic/Modern selection immediately instead of waiting for the
		// screen to close and reopen.
		if(refreshPending
			|| modernStyle != WURST.getHax().clickGuiHack.isModernStyle()
			|| modernStyle && modernRowHeight != WURST.getHax().clickGuiHack
				.getRowHeight())
		{
			refreshPending = false;
			init();
		}
		
		ArrayList<Window> pinnedWindows = new ArrayList<>();
		for(Window window : windows)
		{
			if(window.isPinned() && !window.isInvisible())
				pinnedWindows.add(window);
		}
		
		if(pinnedWindows.isEmpty())
			return;
		
		int mouseX =
			(int)(MC.mouseHandler.xpos() * MC.getWindow().getGuiScaledWidth()
				/ MC.getWindow().getScreenWidth());
		int mouseY =
			(int)(MC.mouseHandler.ypos() * MC.getWindow().getGuiScaledHeight()
				/ MC.getWindow().getScreenHeight());
		
		for(Window window : pinnedWindows)
		{
			if(window.isDragging())
				if(isLeftMouseButtonPressed())
					window.dragTo(mouseX, mouseY);
				else
				{
					window.stopDragging();
					saveWindows();
				}
			
			if(window.isDraggingScrollbar())
				if(isLeftMouseButtonPressed())
					window.dragScrollbarTo(mouseY);
				else
					window.stopDraggingScrollbar();
		}
		
		tooltip = "";
		if(isolateWindows)
			renderWindowsWithIsolation(context, pinnedWindows, mouseX, mouseY,
				partialTicks);
		else
			for(Window window : pinnedWindows)
			{
				context.guiRenderState.up();
				renderWindow(context, window, mouseX, mouseY, partialTicks);
			}
		
		renderPinnedPopups(context, mouseX, mouseY);
	}
	
	public void updateColors()
	{
		ClickGuiHack clickGui = WURST.getHax().clickGuiHack;
		
		opacity = clickGui.getOpacity();
		ttOpacity = clickGui.getTooltipOpacity();
		bgColor = clickGui.getBackgroundColor();
		txtColor = clickGui.getTextColor();
		topBarColor = clickGui.getTopBarColor();
		topBarOpacity = clickGui.getTopBarOpacity();
		hackHeaderColor = clickGui.getHackHeaderColor();
		hackHeaderOpacity = clickGui.getHackHeaderOpacity();
		enabledHackColor = clickGui.getEnabledHackColor();
		dropdownButtonColor = clickGui.getDropdownButtonColor();
		pinButtonColor = clickGui.getPinButtonColor();
		isolateWindows = clickGui.isWindowIsolationEnabled();
		maxHeight = clickGui.getMaxHeight();
		maxSettingsHeight = clickGui.getMaxSettingsHeight();
		
		if(WurstClient.INSTANCE.getHax().rainbowUiHack.isEnabled())
			acColor = RenderUtils.getRainbowColor();
		else
			acColor = clickGui.getAccentColor();
	}
	
	private void renderModernWindow(GuiGraphicsExtractor context, Window window,
		int mouseX, int mouseY, float partialTicks)
	{
		int x1 = window.getX();
		int y1 = window.getY();
		int x2 = x1 + window.getWidth();
		int headerBottom = y1 + getModernHeaderHeight();
		boolean minimized = window.isMinimized();
		if(!minimized)
		{
			int availableHeight = MC.getWindow().getGuiScaledHeight() - 54;
			int configuredHeight = getConfiguredWindowMaxHeight(window);
			int cappedHeight = configuredHeight <= 0 ? availableHeight
				: Math.min(configuredHeight, availableHeight);
			if(modernSearchActive && modernSearchWindowCap > 0
				&& modernCategoryWindows.containsValue(window))
				cappedHeight = Math.min(cappedHeight, modernSearchWindowCap);
			window.setMaxHeight(Math.max(80, cappedHeight));
			window.validate();
		}
		int y2 = minimized ? headerBottom : y1 + window.getHeight();
		boolean focused =
			!windows.isEmpty() && windows.get(windows.size() - 1) == window;
		int border =
			RenderUtils.toIntColor(acColor, focused ? opacity : opacity * 0.6F);
		int modernBackground = RenderUtils.toIntColor(bgColor, opacity);
		int modernHeader = RenderUtils.toIntColor(hackHeaderColor,
			opacity * hackHeaderOpacity);
		context.fill(x1, y1, x2, y2, modernBackground);
		context.fill(x1, y1, x2, headerBottom, modernHeader);
		RenderUtils.drawBorder2D(context, x1, y1, x2, y2, border);
		RenderUtils.drawLine2D(context, x1 + 1, headerBottom, x2 - 1,
			headerBottom, RenderUtils.toIntColor(acColor, opacity * 0.6F));
		int titleY = y1 + Math.max(0,
			Math.round((getModernHeaderHeight() - MC.font.lineHeight) / 2F));
		context.text(MC.font, window.getTitle(), x1 + 8, titleY, txtColor,
			false);
		int controlX =
			x2 - MODERN_TITLE_CONTROL_RIGHT_PADDING - MODERN_TITLE_CONTROL_SIZE;
		int controlY = y1 + Math.max(1, Math
			.round((getModernHeaderHeight() - MODERN_TITLE_CONTROL_SIZE) / 2F));
		if(window.isClosable())
		{
			context.fill(controlX, controlY,
				controlX + MODERN_TITLE_CONTROL_SIZE,
				controlY + MODERN_TITLE_CONTROL_SIZE,
				RenderUtils.toIntColor(dropdownButtonColor, opacity));
			drawModernClose(context, controlX, controlY, txtColor);
			controlX -= MODERN_TITLE_CONTROL_SLOT;
		}
		if(window.isPinnable())
		{
			context.fill(controlX, controlY,
				controlX + MODERN_TITLE_CONTROL_SIZE,
				controlY + MODERN_TITLE_CONTROL_SIZE,
				RenderUtils.toIntColor(dropdownButtonColor, opacity));
			drawModernPin(context, controlX, controlY, window.isPinned(),
				mouseX >= controlX
					&& mouseX < controlX + MODERN_TITLE_CONTROL_SIZE
					&& mouseY >= controlY
					&& mouseY < controlY + MODERN_TITLE_CONTROL_SIZE);
			controlX -= MODERN_TITLE_CONTROL_SLOT;
		}
		if(window.isMinimizable())
		{
			context.fill(controlX, controlY,
				controlX + MODERN_TITLE_CONTROL_SIZE,
				controlY + MODERN_TITLE_CONTROL_SIZE,
				RenderUtils.toIntColor(dropdownButtonColor, opacity));
			drawModernMinimize(context, controlX, controlY, txtColor,
				minimized);
		}
		if(minimized)
			return;
		int bodyBottom = y2 - 2;
		context.enableScissor(x1 + 2, headerBottom + 1, x2 - 2, bodyBottom);
		Matrix3x2fStack matrixStack = context.pose();
		matrixStack.pushMatrix();
		matrixStack.translate(x1, headerBottom + window.getScrollOffset());
		int childMouseX = mouseX - x1;
		int childMouseY = mouseY - headerBottom - window.getScrollOffset();
		int settingsBorder =
			RenderUtils.toIntColor(getModernHackRowBorderColor(),
				opacity * getModernHackRowBorderOpacity());
		for(int i = 0; i < window.countChildren(); i++)
		{
			Component child = window.getChild(i);
			child.extractRenderState(context, childMouseX, childMouseY,
				partialTicks);
			RenderUtils.drawBorder2D(context, child.getX(), child.getY(),
				child.getX() + child.getWidth(),
				child.getY() + child.getHeight(), settingsBorder);
		}
		matrixStack.popMatrix();
		context.disableScissor();
		if(window.isScrollingEnabled())
		{
			int trackTop = headerBottom + window.getScrollbarTrackTop();
			int trackBottom = headerBottom + window.getScrollbarTrackBottom();
			int thumbHeight = window.getScrollbarThumbHeight();
			int thumbY = headerBottom + window.getScrollbarThumbY();
			context.fill(x2 - 7, trackTop, x2 - 3, trackBottom,
				RenderUtils.toIntColor(dropdownButtonColor, opacity));
			context.fill(x2 - 7, thumbY, x2 - 3, thumbY + thumbHeight,
				RenderUtils.toIntColor(acColor, opacity));
		}
	}
	
	private void drawModernPin(GuiGraphicsExtractor context, int x, int y,
		boolean pinned, boolean hovering)
	{
		int color = pinned ? (hovering ? 0xFFFF5555 : 0xFFFF2222)
			: (hovering ? 0xFFFFFFFF : 0xFFD9D9D9);
		int outline = 0xB0101010;
		
		// A compact push-pin silhouette; the red fill is the pinned state.
		RenderUtils.fill2D(context, x + 3, y + 1, x + 10, y + 4, color);
		RenderUtils.fill2D(context, x + 5, y + 4, x + 8, y + 9, color);
		RenderUtils.fillTriangle2D(context,
			new float[][]{{x + 3, y + 9}, {x + 10, y + 9}, {x + 6.5F, y + 12}},
			color);
		RenderUtils.drawBorder2D(context, x + 3, y + 1, x + 10, y + 4, outline);
		RenderUtils.drawBorder2D(context, x + 5, y + 4, x + 8, y + 9, outline);
	}
	
	private void drawModernClose(GuiGraphicsExtractor context, int x, int y,
		int color)
	{
		drawCenteredTitleGlyph(context, "×", x, y, color);
	}
	
	private void drawModernMinimize(GuiGraphicsExtractor context, int x, int y,
		int color, boolean minimized)
	{
		drawCenteredTitleGlyph(context, minimized ? "+" : "-", x, y, color);
	}
	
	private void drawCenteredTitleGlyph(GuiGraphicsExtractor context,
		String glyph, int x, int y, int color)
	{
		int glyphX = Math
			.round(x + (MODERN_TITLE_CONTROL_SIZE - MC.font.width(glyph)) / 2F);
		int glyphY = Math
			.round(y + (MODERN_TITLE_CONTROL_SIZE - MC.font.lineHeight) / 2F);
		context.text(MC.font, glyph, glyphX, glyphY, color, false);
	}
	
	private void renderWindow(GuiGraphicsExtractor context, Window window,
		int mouseX, int mouseY, float partialTicks)
	{
		if(window instanceof ModernWindow)
		{
			renderModernWindow(context, window, mouseX, mouseY, partialTicks);
			return;
		}
		int x1 = window.getX();
		int y1 = window.getY();
		int x2 = x1 + window.getWidth();
		int y2 = y1 + window.getHeight();
		int y3 = y1 + getHeaderHeight(window);
		
		boolean modernWindow = window instanceof ModernWindow;
		if(modernWindow)
		{
			renderModernWindow(context, window, mouseX, mouseY, partialTicks);
			return;
		}
		int windowBgColor = RenderUtils.toIntColor(bgColor, opacity);
		int outlineColor = RenderUtils.toIntColor(acColor, 0.5F);
		
		Matrix3x2fStack matrixStack = context.pose();
		
		if(window.isMinimized())
			y2 = y3;
		
		if(mouseX >= x1 && mouseY >= y1 && mouseX < x2 && mouseY < y2)
			tooltip = "";
		
		if(!window.isMinimized())
		{
			window.setMaxHeight(window instanceof SettingsWindow
				? maxSettingsHeight : maxHeight);
			window.validate();
			
			// scrollbar
			if(window.isScrollingEnabled())
			{
				int xs1 = x2 - 3;
				int xs2 = xs1 + 2;
				int xs3 = x2;
				
				int ys1 = y3;
				int ys2 = y2;
				int ys3 = ys1 + window.getScrollbarThumbY();
				int ys4 = ys3 + window.getScrollbarThumbHeight();
				
				// window background
				context.fill(xs2, ys1, xs3, ys2, windowBgColor);
				context.fill(xs1, ys1, xs2, ys3, windowBgColor);
				context.fill(xs1, ys4, xs2, ys2, windowBgColor);
				
				boolean hovering = mouseX >= xs1 && mouseY >= ys3
					&& mouseX < xs2 && mouseY < ys4;
				
				// scrollbar
				int scrollbarColor = RenderUtils.toIntColor(acColor,
					hovering ? opacity * 1.5F : opacity);
				context.fill(xs1, ys3, xs2, ys4, scrollbarColor);
				
				// outline
				RenderUtils.drawBorder2D(context, xs1, ys3, xs2, ys4,
					outlineColor);
			}
			
			int x3 = x1 + 2;
			int x4 = window.isScrollingEnabled() ? x2 - 3 : x2;
			int x5 = x4 - 2;
			int y4 = y3 + window.getScrollOffset();
			
			// window background
			// left & right
			context.fill(x1, y3, x3, y2, windowBgColor);
			context.fill(x5, y3, x4, y2, windowBgColor);
			
			context.enableScissor(x1, y3, x2, y2);
			
			matrixStack.pushMatrix();
			matrixStack.translate(x1, y4);
			
			// window background
			// between children
			int xc1 = 2;
			int xc2 = x5 - x1;
			for(int i = 0; i < window.countChildren(); i++)
			{
				int yc1 = window.getChild(i).getY();
				int yc2 = yc1 - 2;
				context.fill(xc1, yc2, xc2, yc1, windowBgColor);
			}
			
			// window background
			// bottom
			int yc1;
			if(window.countChildren() == 0)
				yc1 = 0;
			else
			{
				Component lastChild =
					window.getChild(window.countChildren() - 1);
				yc1 = lastChild.getY() + lastChild.getHeight();
			}
			int yc2 = yc1 + 2;
			context.fill(xc1, yc2, xc2, yc1, windowBgColor);
			
			// render children
			int cMouseX = mouseX - x1;
			int cMouseY = mouseY - y4;
			for(int i = 0; i < window.countChildren(); i++)
				window.getChild(i).extractRenderState(context, cMouseX, cMouseY,
					partialTicks);
			
			matrixStack.popMatrix();
			context.disableScissor();
		}
		
		// window outline
		RenderUtils.drawBorder2D(context, x1, y1, x2, y2, outlineColor);
		if(window.isResizable() && !window.isMinimized())
		{
			RenderUtils.drawLine2D(context, x2 - 6, y2 - 2, x2 - 2, y2 - 6,
				RenderUtils.toIntColor(acColor, opacity));
			RenderUtils.drawLine2D(context, x2 - 4, y2 - 2, x2 - 2, y2 - 4,
				RenderUtils.toIntColor(acColor, opacity));
		}
		
		// title bar separator line
		if(!window.isMinimized())
			RenderUtils.drawLine2D(context, x1, y3, x2, y3, outlineColor);
		
		// title bar buttons
		int x3 = x2;
		int y4 = y1 + (modernWindow ? 4 : 2);
		int y5 = y4 + (modernWindow ? 15 : 9);
		boolean hoveringY = mouseY >= y4 && mouseY < y5;
		if(window.isClosable())
		{
			x3 -= modernWindow ? 16 : 11;
			int x4 = x3 + (modernWindow ? 15 : 9);
			boolean hovering = hoveringY && mouseX >= x3 && mouseX < x4;
			if(modernWindow)
				renderModernTitleButton(context, x3, y4, x4, y5, hovering,
					hovering ? "✖" : "✕");
			else
			{
				renderTitleBarButton(context, x3, y4, x4, y5, hovering);
				ClickGuiIcons.drawCross(context, x3, y4, x4, y5, hovering);
			}
		}
		
		if(window.isPinnable())
		{
			x3 -= modernWindow ? 16 : 11;
			int x4 = x3 + (modernWindow ? 15 : 9);
			boolean hovering = hoveringY && mouseX >= x3 && mouseX < x4;
			if(modernWindow)
				renderModernTitleButton(context, x3, y4, x4, y5, hovering,
					window.isPinned() ? "◆" : "◇");
			else
			{
				renderTitleBarButton(context, x3, y4, x4, y5, hovering);
				ClickGuiIcons.drawPin(context, x3, y4, x4, y5, hovering,
					window.isPinned());
			}
		}
		
		if(window.isMinimizable())
		{
			x3 -= modernWindow ? 16 : 11;
			int x4 = x3 + (modernWindow ? 15 : 9);
			boolean hovering = hoveringY && mouseX >= x3 && mouseX < x4;
			if(modernWindow)
				renderModernTitleButton(context, x3, y4, x4, y5, hovering, "−");
			else
			{
				renderTitleBarButton(context, x3, y4, x4, y5, hovering);
				ClickGuiIcons.drawMinimizeArrow(context, x3, y4, x4, y5,
					hovering, window.isMinimized());
			}
		}
		
		// title bar background
		// above & below buttons
		int titleBgColor = modernWindow ? 0xED27242D
			: RenderUtils.toIntColor(acColor, opacity);
		context.fill(x3, y1, x2, y4, titleBgColor);
		context.fill(x3, y5, x2, y3, titleBgColor);
		
		// title bar background
		// behind title
		context.fill(x1, y1, x3, y3, titleBgColor);
		
		// window title
		Font tr = MC.font;
		String title = tr.substrByWidth(
			net.minecraft.network.chat.Component.literal(window.getTitle()),
			x3 - x1).getString();
		context.guiRenderState.up();
		context.text(tr, title, x1 + 2, y1 + 3, txtColor, false);
	}
	
	private void renderModernTitleButton(GuiGraphicsExtractor context, int x1,
		int y1, int x2, int y2, boolean hovering, String icon)
	{
		context.fill(x1, y1, x2, y2,
			hovering ? RenderUtils.toIntColor(acColor, opacity * 0.7F)
				: RenderUtils.toIntColor(bgColor, opacity));
		context.text(MC.font, icon, x1 + (x2 - x1 - MC.font.width(icon)) / 2,
			y1 + (y2 - y1 - MC.font.lineHeight) / 2, txtColor, false);
	}
	
	private void renderTitleBarButton(GuiGraphicsExtractor context, int x1,
		int y1, int x2, int y2, boolean hovering)
	{
		int x3 = x2 + 2;
		
		// button background
		int buttonBgColor = RenderUtils.toIntColor(bgColor,
			hovering ? opacity * 1.5F : opacity);
		context.fill(x1, y1, x2, y2, buttonBgColor);
		
		// background between buttons
		int windowBgColor = RenderUtils.toIntColor(acColor, opacity);
		context.fill(x2, y1, x3, y2, windowBgColor);
		
		// button outline
		int outlineColor = RenderUtils.toIntColor(acColor, 0.5F);
		RenderUtils.drawBorder2D(context, x1, y1, x2, y2, outlineColor);
	}
	
	private void adaptModernLayoutToScreen()
	{
		if(!modernStyle)
			return;
		int width = MC.getWindow().getGuiScaledWidth();
		int height = MC.getWindow().getGuiScaledHeight();
		if(width == modernScreenWidth && height == modernScreenHeight)
			return;
		modernScreenWidth = width;
		modernScreenHeight = height;
		for(Window window : windows)
		{
			int maxX = Math.max(0, width - Math.min(width, window.getWidth()));
			int maxY = Math.max(34, height - 13);
			window.setX(Math.max(0, Math.min(maxX, window.getActualX())));
			window.setY(Math.max(34, Math.min(maxY, window.getActualY())));
			window.setMaxHeight(Math.max(80, height - 45));
		}
	}
	
	private static final class CategorySnapshot
	{
		private final int x;
		private final int y;
		private final int width;
		private final boolean minimized;
		private final boolean open;
		private final ArrayList<Component> children = new ArrayList<>();
		
		private CategorySnapshot(Window window, boolean open)
		{
			x = window.getActualX();
			y = window.getActualY();
			width = window.getWidth();
			minimized = window.isMinimized();
			this.open = open;
			for(int i = 0; i < window.countChildren(); i++)
				children.add(window.getChild(i));
		}
	}
	
	private void updateModernSearchResults()
	{
		if(!modernStyle)
			return;
		
		if(modernSearchQuery.isBlank())
		{
			exitModernSearch();
			return;
		}
		
		if(!modernSearchActive)
			enterModernSearch();
		
		ArrayList<Window> matching = new ArrayList<>();
		for(Window window : modernCategoryWindows.values())
		{
			CategorySnapshot snapshot =
				modernSearchSnapshot.get(window.getTitle());
			if(snapshot == null)
				continue;
			
			window.clearChildren();
			for(Component child : snapshot.children)
				if(child instanceof ModernFeatureButton button
					&& ModernFeatureButton.matches(button.getFeature(),
						modernSearchQuery))
					window.add(child);
				
			boolean hasMatches = window.countChildren() > 0;
			window.setInvisible(!hasMatches);
			if(!hasMatches)
				continue;
			
			window.setMinimized(false);
			if(!windows.contains(window))
				windows.add(window);
			matching.add(window);
		}
		
		layoutModernSearchResults(matching);
	}
	
	private void enterModernSearch()
	{
		modernSearchSnapshot.clear();
		for(Window window : modernCategoryWindows.values())
			modernSearchSnapshot.put(window.getTitle(),
				new CategorySnapshot(window, windows.contains(window)));
		modernSearchActive = true;
	}
	
	private void exitModernSearch()
	{
		if(!modernSearchActive)
			return;
		
		modernSearchActive = false;
		modernSearchWindowCap = 0;
		for(Window window : modernCategoryWindows.values())
		{
			CategorySnapshot snapshot =
				modernSearchSnapshot.get(window.getTitle());
			if(snapshot == null)
				continue;
			
			window.clearChildren();
			for(Component child : snapshot.children)
				window.add(child);
			window.pack();
			window.setWidth(snapshot.width);
			window.setX(snapshot.x);
			window.setY(snapshot.y);
			window.setMinimized(snapshot.minimized);
			window.setInvisible(false);
			if(snapshot.open)
			{
				window.reopen();
				if(!windows.contains(window))
					windows.add(window);
			}else
				windows.remove(window);
		}
		modernSearchSnapshot.clear();
	}
	
	private void layoutModernSearchResults(ArrayList<Window> matching)
	{
		int gap = 5;
		int screenWidth = MC.getWindow().getGuiScaledWidth();
		int screenHeight = MC.getWindow().getGuiScaledHeight();
		int topLimit = 54;
		modernSearchBarY = 34;
		modernSearchWindowCap = 0;
		if(matching.isEmpty())
			return;
		
		int naturalCap = Math.max(80, screenHeight - topLimit - gap);
		for(Window window : matching)
			packModernSearchWindow(window, naturalCap);
		
		ArrayList<ArrayList<Window>> rows = new ArrayList<>();
		ArrayList<Window> row = new ArrayList<>();
		int rowWidth = 0;
		for(Window window : matching)
		{
			int width = window.getWidth() + gap;
			if(!row.isEmpty() && rowWidth + width > screenWidth - gap)
			{
				rows.add(row);
				row = new ArrayList<>();
				rowWidth = 0;
			}
			row.add(window);
			rowWidth += width;
		}
		if(!row.isEmpty())
			rows.add(row);
		
		int available = screenHeight - topLimit - gap;
		if(measureModernSearchBlock(rows, gap) > available)
		{
			int perRow = (available - (rows.size() - 1) * gap) / rows.size();
			modernSearchWindowCap = Math.max(60, perRow);
			for(Window window : matching)
				packModernSearchWindow(window, modernSearchWindowCap);
		}
		
		int blockHeight = measureModernSearchBlock(rows, gap);
		int top = Math.max(topLimit, (screenHeight - blockHeight) / 2);
		modernSearchBarY = top - 22;
		int y = top;
		for(ArrayList<Window> currentRow : rows)
		{
			int totalWidth = -gap;
			int tallest = 0;
			for(Window window : currentRow)
			{
				totalWidth += window.getWidth() + gap;
				tallest = Math.max(tallest, window.getHeight());
			}
			int x = Math.max(gap, (screenWidth - totalWidth) / 2);
			for(Window window : currentRow)
			{
				window.setX(x);
				window.setY(y);
				bringWindowToFront(window);
				x += window.getWidth() + gap;
			}
			y += tallest + gap;
		}
	}
	
	private void packModernSearchWindow(Window window, int maxHeight)
	{
		window.setMaxHeight(maxHeight);
		window.pack();
		window.validate();
	}
	
	private int measureModernSearchBlock(ArrayList<ArrayList<Window>> rows,
		int gap)
	{
		int blockHeight = -gap;
		for(ArrayList<Window> row : rows)
		{
			int tallest = 0;
			for(Window window : row)
				tallest = Math.max(tallest, window.getHeight());
			blockHeight += tallest + gap;
		}
		return blockHeight;
	}
	
	public String getModernSearchQuery()
	{
		return modernSearchQuery;
	}
	
	private void renderModernSearchOverlay(GuiGraphicsExtractor context)
	{
		if(modernSearchQuery.isEmpty())
			return;
		
		int matches = 0;
		for(Window window : modernCategoryWindows.values())
			if(!window.isInvisible())
				matches += window.countChildren();
			
		String label = "Search: " + modernSearchQuery + "_  (" + matches + ")";
		int width = MC.font.width(label) + 14;
		int screenWidth = MC.getWindow().getGuiScaledWidth();
		int x = (screenWidth - width) / 2;
		int y = modernSearchBarY;
		context.fill(x, y, x + width, y + 16,
			RenderUtils.toIntColor(bgColor, opacity));
		RenderUtils.drawBorder2D(context, x, y, x + width, y + 16,
			RenderUtils.toIntColor(acColor, opacity));
		int textY = Math.round(y + (16 - MC.font.lineHeight) / 2F);
		context.text(MC.font, label, x + 7, textY, txtColor, false);
	}
	
	public float[] getModernHackRowBorderColor()
	{
		return WURST.getHax().clickGuiHack.getHackRowBorderColor();
	}
	
	public float getModernHackRowBorderOpacity()
	{
		return WURST.getHax().clickGuiHack.getHackRowBorderOpacity();
	}
	
	public boolean isModernEnabledRowHighlight()
	{
		return WURST.getHax().clickGuiHack.isHighlightEnabledRows();
	}
	
	public float[] getBgColor()
	{
		return bgColor;
	}
	
	public float[] getAcColor()
	{
		return acColor;
	}
	
	public float[] getEnabledHackColor()
	{
		return enabledHackColor;
	}
	
	public int getTxtColor()
	{
		return txtColor;
	}
	
	public float[] getDropdownButtonColor()
	{
		return dropdownButtonColor;
	}
	
	public float[] getPinButtonColor()
	{
		return pinButtonColor;
	}
	
	public boolean isWindowIsolationEnabled()
	{
		return isolateWindows;
	}
	
	private void renderWindowsWithIsolation(GuiGraphicsExtractor context,
		List<Window> windowsToRender, int mouseX, int mouseY,
		float partialTicks)
	{
		List<List<Rect>> occlusionMasks = buildOcclusionMasks(windowsToRender);
		
		for(int i = 0; i < windowsToRender.size(); i++)
		{
			Window window = windowsToRender.get(i);
			List<Rect> visibleAreas =
				computeVisibleAreas(window, occlusionMasks.get(i));
			
			if(visibleAreas.isEmpty())
				continue;
			
			for(Rect rect : visibleAreas)
			{
				context.enableScissor(rect.x1, rect.y1, rect.x2, rect.y2);
				context.guiRenderState.up();
				renderWindow(context, window, mouseX, mouseY, partialTicks);
				context.disableScissor();
			}
		}
	}
	
	private List<List<Rect>> buildOcclusionMasks(List<Window> windowsToRender)
	{
		ArrayList<List<Rect>> masks = new ArrayList<>(windowsToRender.size());
		for(int i = 0; i < windowsToRender.size(); i++)
			masks.add(new ArrayList<>());
		
		ArrayList<Rect> accumulated = new ArrayList<>();
		for(int i = windowsToRender.size() - 1; i >= 0; i--)
		{
			ArrayList<Rect> copy = new ArrayList<>(accumulated.size());
			for(Rect rect : accumulated)
				copy.add(rect.copy());
			masks.set(i, copy);
			
			accumulated.add(Rect.fromWindow(windowsToRender.get(i)));
		}
		
		return masks;
	}
	
	private List<Rect> computeVisibleAreas(Window window, List<Rect> occluders)
	{
		ArrayList<Rect> visible = new ArrayList<>();
		visible.add(Rect.fromWindow(window));
		
		for(Rect occluder : occluders)
			visible = subtractRectangles(visible, occluder);
		
		return visible;
	}
	
	private ArrayList<Rect> subtractRectangles(List<Rect> source, Rect occluder)
	{
		ArrayList<Rect> result = new ArrayList<>();
		for(Rect rect : source)
			result.addAll(rect.subtract(occluder));
		return result;
	}
	
	public int getModernHeaderHeight()
	{
		return WURST.getHax().clickGuiHack.getHeaderHeight();
	}
	
	public int getModernRowHeight()
	{
		return WURST.getHax().clickGuiHack.getRowHeight();
	}
	
	public float getOpacity()
	{
		return opacity;
	}
	
	public float getTooltipOpacity()
	{
		return ttOpacity;
	}
	
	public void setTooltip(String tooltip)
	{
		this.tooltip = Objects.requireNonNull(tooltip);
	}
	
	private static final class Rect
	{
		final int x1;
		final int y1;
		final int x2;
		final int y2;
		
		Rect(int x1, int y1, int x2, int y2)
		{
			this.x1 = x1;
			this.y1 = y1;
			this.x2 = x2;
			this.y2 = y2;
		}
		
		static Rect fromWindow(Window window)
		{
			int x1 = window.getX();
			int y1 = window.getY();
			int width = window.getWidth();
			int height = window.isMinimized() ? 13 : window.getHeight();
			return new Rect(x1, y1, x1 + width, y1 + height);
		}
		
		Rect copy()
		{
			return new Rect(x1, y1, x2, y2);
		}
		
		List<Rect> subtract(Rect other)
		{
			ArrayList<Rect> pieces = new ArrayList<>();
			if(!intersects(other))
			{
				pieces.add(this);
				return pieces;
			}
			
			int ox1 = Math.max(x1, other.x1);
			int oy1 = Math.max(y1, other.y1);
			int ox2 = Math.min(x2, other.x2);
			int oy2 = Math.min(y2, other.y2);
			
			if(oy1 > y1)
				pieces.add(new Rect(x1, y1, x2, oy1));
			if(oy2 < y2)
				pieces.add(new Rect(x1, oy2, x2, y2));
			
			if(oy1 < oy2)
			{
				if(ox1 > x1)
					pieces.add(new Rect(x1, oy1, ox1, oy2));
				if(ox2 < x2)
					pieces.add(new Rect(ox2, oy1, x2, oy2));
			}
			
			return pieces;
		}
		
		private boolean intersects(Rect other)
		{
			return x1 < other.x2 && x2 > other.x1 && y1 < other.y2
				&& y2 > other.y1;
		}
	}
	
	public void addWindow(Window window)
	{
		windows.add(window);
	}
	
	public void addPopup(Popup popup)
	{
		popups.add(popup);
	}
	
	public boolean hasPopup(Popup popup)
	{
		return popups.contains(popup);
	}
	
	/**
	 * Add a feature to the Favorites window if not already present.
	 */
	public void addFavoriteFeature(Feature feature)
	{
		if(modernStyle)
		{
			requestRefresh();
			return;
		}
		String favTitle = net.wurstclient.Category.FAVORITES.getName();
		for(Window window : windows)
		{
			if(window.getTitle().equals(favTitle))
			{
				// check existing
				for(int i = 0; i < window.countChildren(); i++)
				{
					net.wurstclient.clickgui.Component c = window.getChild(i);
					if(c instanceof net.wurstclient.clickgui.components.FeatureButton)
					{
						net.wurstclient.clickgui.components.FeatureButton fb =
							(net.wurstclient.clickgui.components.FeatureButton)c;
						if(fb.getFeature().getName().equals(feature.getName()))
							return;
					}
				}
				window
					.add(new net.wurstclient.clickgui.components.FeatureButton(
						feature));
				sortFavoritesWindow(window);
				return;
			}
		}
	}
	
	public void removeFavoriteFeature(Feature feature)
	{
		if(modernStyle)
		{
			requestRefresh();
			return;
		}
		String favTitle = net.wurstclient.Category.FAVORITES.getName();
		for(Window window : windows)
		{
			if(!window.getTitle().equals(favTitle))
				continue;
			for(int i = window.countChildren() - 1; i >= 0; i--)
			{
				net.wurstclient.clickgui.Component c = window.getChild(i);
				if(c instanceof net.wurstclient.clickgui.components.FeatureButton)
				{
					net.wurstclient.clickgui.components.FeatureButton fb =
						(net.wurstclient.clickgui.components.FeatureButton)c;
					if(fb.getFeature().getName().equals(feature.getName()))
					{
						window.remove(i);
						window.pack();
						return;
					}
				}
			}
		}
	}
	
	public boolean isLeftMouseButtonPressed()
	{
		return leftMouseButtonPressed || MC.mouseHandler.isLeftPressed();
	}
	
	/**
	 * Sort the given favourites window's children alphabetically by feature
	 * name.
	 */
	private void sortFavoritesWindow(Window window)
	{
		if(window == null)
			return;
		// collect children
		ArrayList<net.wurstclient.clickgui.Component> all = new ArrayList<>();
		for(int i = 0; i < window.countChildren(); i++)
			all.add(window.getChild(i));
		// sort by feature name when possible
		all.sort((c1, c2) -> {
			String n1 = c1 instanceof FeatureButton
				? getFeatureSortName(((FeatureButton)c1).getFeature())
				: c1.getClass().getName();
			String n2 = c2 instanceof FeatureButton
				? getFeatureSortName(((FeatureButton)c2).getFeature())
				: c2.getClass().getName();
			return n1.compareToIgnoreCase(n2);
		});
		// remove all children and re-add in sorted order
		for(int i = window.countChildren() - 1; i >= 0; i--)
			window.remove(i);
		for(net.wurstclient.clickgui.Component c : all)
			window.add(c);
		window.pack();
	}
	
	/**
	 * Sort a window's children alphabetically by feature name when possible.
	 * Falls back to component class name for non-feature children.
	 */
	private void sortWindowByFeatureName(Window window)
	{
		if(window == null)
			return;
		
		ArrayList<net.wurstclient.clickgui.Component> all = new ArrayList<>();
		for(int i = 0; i < window.countChildren(); i++)
			all.add(window.getChild(i));
		
		all.sort((c1, c2) -> {
			String n1 = c1 instanceof FeatureButton
				? getFeatureSortName(((FeatureButton)c1).getFeature())
				: c1.getClass().getName();
			String n2 = c2 instanceof FeatureButton
				? getFeatureSortName(((FeatureButton)c2).getFeature())
				: c2.getClass().getName();
			return n1.compareToIgnoreCase(n2);
		});
		
		for(int i = window.countChildren() - 1; i >= 0; i--)
			window.remove(i);
		for(net.wurstclient.clickgui.Component c : all)
			window.add(c);
		
		window.pack();
	}
	
	private static String getFeatureSortName(Feature feature)
	{
		String name = feature.getName();
		if(name != null && name.startsWith("."))
			return name.substring(1);
		
		return name;
	}
}
