/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.Category;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.components.FeatureButton;
import net.wurstclient.hacks.ClickGuiHack;
import net.wurstclient.hacks.TooManyHaxHack;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.json.JsonUtils;

public final class ClickGui
{
	private static final WurstClient WURST = WurstClient.INSTANCE;
	private static final Minecraft MC = WurstClient.MC;
	
	private final ArrayList<Window> windows = new ArrayList<>();
	private final ArrayList<Popup> popups = new ArrayList<>();
	private final Path windowsFile;
	
	private float[] bgColor = new float[3];
	private float[] acColor = new float[3];
	private float[] enabledHackColor = new float[3];
	private float[] dropdownButtonColor = new float[3];
	private float[] pinButtonColor = new float[3];
	private int txtColor;
	private float opacity;
	private float ttOpacity;
	private int maxHeight;
	private int maxSettingsHeight;
	private boolean isolateWindows;
	
	private String tooltip = "";
	
	private boolean leftMouseButtonPressed;
	private KeyboardInput keyboardInput;
	
	public ClickGui(Path windowsFile)
	{
		this.windowsFile = windowsFile;
	}
	
	public void init()
	{
		// Clear existing windows/popups so repeated init() calls rebuild
		// the UI instead of duplicating entries.
		windows.clear();
		popups.clear();
		updateColors();
		
		LinkedHashMap<Category, Window> windowMap = new LinkedHashMap<>();
		for(Category category : Category.values())
			windowMap.put(category, new Window(category.getName()));
		
		ArrayList<Feature> features = new ArrayList<>();
		features.addAll(WURST.getHax().getAllHax());
		features.addAll(WURST.getCmds().getAllCmds());
		features.addAll(WURST.getOtfs().getAllOtfs());
		
		TooManyHaxHack tooManyHax = WURST.getHax().tooManyHaxHack;
		for(Feature f : features)
		{
			// When TooManyHax is enabled, hide hacks that it disabled from
			// the ClickGUI to avoid cluttering the UI. The Navigator should
			// keep showing all features, so we only apply this filter here.
			if(f instanceof net.wurstclient.hack.Hack && tooManyHax.isEnabled()
				&& tooManyHax.isBlocked(f)
				&& !((net.wurstclient.hack.Hack)f).isEnabled())
			{
				continue;
			}
			
			if(f.getCategory() != null)
				windowMap.get(f.getCategory()).add(new FeatureButton(f));
		}
		// add favorites window entries (show favorites in the Favorites
		// category). Respect TooManyHax hiding behaviour here as well so
		// favorite hacks disabled by TooManyHax don't appear in ClickGUI.
		for(Feature f : features)
		{
			if(!(f instanceof net.wurstclient.hack.Hack
				&& ((net.wurstclient.hack.Hack)f).isFavorite()))
				continue;
			
			if(f instanceof net.wurstclient.hack.Hack && tooManyHax.isEnabled()
				&& tooManyHax.isBlocked(f)
				&& !((net.wurstclient.hack.Hack)f).isEnabled())
			{
				continue;
			}
			
			windowMap.get(net.wurstclient.Category.FAVORITES)
				.add(new FeatureButton(f));
		}
		// ensure favourites window is sorted alphabetically
		Window favWindow = windowMap.get(net.wurstclient.Category.FAVORITES);
		if(favWindow != null)
			sortFavoritesWindow(favWindow);
		
		windows.addAll(windowMap.values());
		
		Window uiSettings = new Window("UI Settings");
		uiSettings.add(new FeatureButton(WURST.getOtfs().wurstLogoOtf));
		uiSettings.add(new FeatureButton(WURST.getOtfs().hackListOtf));
		uiSettings.add(new FeatureButton(WURST.getOtfs().keybindManagerOtf));
		ClickGuiHack clickGuiHack = WURST.getHax().clickGuiHack;
		uiSettings.add(clickGuiHack.getIsolateWindowsSetting().getComponent());
		Stream<Setting> settings =
			clickGuiHack.getSettings().values().stream().filter(
				setting -> setting != clickGuiHack.getIsolateWindowsSetting());
		settings.map(Setting::getComponent).forEach(c -> uiSettings.add(c));
		// Removed secondary Chest Search button from UI Settings so Chest
		// Search
		// only appears in the ITEMS category via its hack.
		windows.add(uiSettings);
		
		// Removed dedicated Chest Tools window so Chest Search isn't shown in
		// its own category/window.
		
		for(Window window : windows)
			window.setMinimized(true);
		
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
		}
		
		saveWindows();
	}
	
	private void saveWindows()
	{
		JsonObject json = new JsonObject();
		
		for(Window window : windows)
		{
			if(window.isClosable())
				continue;
			
			JsonObject jsonWindow = new JsonObject();
			jsonWindow.addProperty("x", window.getActualX());
			jsonWindow.addProperty("y", window.getActualY());
			jsonWindow.addProperty("minimized", window.isMinimized());
			jsonWindow.addProperty("pinned", window.isPinned());
			json.add(window.getTitle(), jsonWindow);
		}
		
		try(BufferedWriter writer = Files.newBufferedWriter(windowsFile))
		{
			JsonUtils.PRETTY_GSON.toJson(json, writer);
			
		}catch(IOException e)
		{
			System.out.println("Failed to save " + windowsFile.getFileName());
			e.printStackTrace();
		}
	}
	
	public void handleMouseClick(MouseButtonEvent context)
	{
		int mouseX = (int)context.x();
		int mouseY = (int)context.y();
		int mouseButton = context.button();
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			leftMouseButtonPressed = true;
		
		boolean popupClicked =
			handlePopupMouseClick(mouseX, mouseY, mouseButton);
		
		if(!popupClicked)
		{
			boolean closedPopups = closeActivePopups();
			if(!closedPopups)
				handleWindowMouseClick(mouseX, mouseY, mouseButton, context);
		}
		
		for(Popup popup : popups)
			if(popup.getOwner().getParent().isClosing())
				popup.close();
			
		windows.removeIf(Window::isClosing);
		popups.removeIf(Popup::isClosing);
	}
	
	private boolean closeActivePopups()
	{
		if(popups.isEmpty())
			return false;
		
		for(Popup popup : popups)
			popup.close();
		
		return true;
	}
	
	public void handleMouseRelease(double mouseX, double mouseY,
		int mouseButton)
	{
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			leftMouseButtonPressed = false;
	}
	
	public void handleMouseScroll(double mouseX, double mouseY, double delta)
	{
		if(delta == 0)
			return;
		
		if(handlePopupMouseScroll(mouseX, mouseY, delta))
			return;
		
		int dWheel = (int)delta * 4;
		if(dWheel == 0)
			return;
		
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			Window window = windows.get(i);
			
			if(!window.isScrollingEnabled() || window.isMinimized()
				|| window.isInvisible())
				continue;
			
			if(mouseX < window.getX() || mouseY < window.getY() + 13)
				continue;
			if(mouseX >= window.getX() + window.getWidth()
				|| mouseY >= window.getY() + window.getHeight())
				continue;
			
			int scroll = window.getScrollOffset() + dWheel;
			scroll = Math.min(scroll, 0);
			scroll = Math.max(scroll,
				-window.getInnerHeight() + window.getHeight() - 13);
			window.setScrollOffset(scroll);
			break;
		}
	}
	
	public boolean handleNavigatorPopupClick(double mouseX, double mouseY,
		int mouseButton)
	{
		boolean popupClicked =
			handlePopupMouseClick(mouseX, mouseY, mouseButton);
		
		if(popupClicked)
		{
			for(Popup popup : popups)
				if(popup.getOwner().getParent().isClosing())
					popup.close();
				
			popups.removeIf(Popup::isClosing);
		}
		
		return popupClicked;
	}
	
	public boolean handleNavigatorMouseScroll(double mouseX, double mouseY,
		double delta)
	{
		boolean popupScrolled = handlePopupMouseScroll(mouseX, mouseY, delta);
		if(popupScrolled)
		{
			for(Popup popup : popups)
				if(popup.getOwner().getParent().isClosing())
					popup.close();
				
			popups.removeIf(Popup::isClosing);
		}
		
		return popupScrolled;
	}
	
	public boolean handleKeyPressed(KeyEvent context)
	{
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
		
		for(Popup popup : popups)
			if(popup.getOwner().getParent().isClosing())
				popup.close();
			
		popups.removeIf(Popup::isClosing);
	}
	
	private boolean handlePopupMouseClick(double mouseX, double mouseY,
		int mouseButton)
	{
		for(int i = popups.size() - 1; i >= 0; i--)
		{
			Popup popup = popups.get(i);
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			
			int x0 = parent.getX() + owner.getX();
			int y0 =
				parent.getY() + 13 + parent.getScrollOffset() + owner.getY();
			
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
			
			int x0 = parent.getX() + owner.getX();
			int y0 =
				parent.getY() + 13 + parent.getScrollOffset() + owner.getY();
			
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
				return true;
		}
		
		return false;
	}
	
	private void handleWindowMouseClick(int mouseX, int mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			Window window = windows.get(i);
			if(window.isInvisible())
				continue;
			
			int x1 = window.getX();
			int y1 = window.getY();
			int x2 = x1 + window.getWidth();
			int y2 = y1 + window.getHeight();
			int y3 = y1 + 13;
			
			if(mouseX < x1 || mouseY < y1)
				continue;
			if(mouseX >= x2 || mouseY >= y2)
				continue;
			
			if(mouseY < y3)
				handleTitleBarMouseClick(window, mouseX, mouseY, mouseButton);
			else if(!window.isMinimized())
			{
				window.validate();
				
				int cMouseX = mouseX - x1;
				int cMouseY = mouseY - y3;
				
				if(window.isScrollingEnabled() && mouseX >= x2 - 3)
					handleScrollbarMouseClick(window, cMouseX, cMouseY,
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
			
			windows.remove(window);
			windows.add(window);
			break;
		}
	}
	
	private void handleTitleBarMouseClick(Window window, int mouseX, int mouseY,
		int mouseButton)
	{
		if(mouseButton != 0)
			return;
		
		if(mouseY < window.getY() + 2 || mouseY >= window.getY() + 11)
		{
			window.startDragging(mouseX, mouseY);
			return;
		}
		
		int x3 = window.getX() + window.getWidth();
		
		if(window.isClosable())
		{
			x3 -= 11;
			if(mouseX >= x3 && mouseX < x3 + 9)
			{
				window.close();
				return;
			}
		}
		
		if(window.isPinnable())
		{
			x3 -= 11;
			if(mouseX >= x3 && mouseX < x3 + 9)
			{
				window.setPinned(!window.isPinned());
				saveWindows();
				return;
			}
		}
		
		if(window.isMinimizable())
		{
			x3 -= 11;
			if(mouseX >= x3 && mouseX < x3 + 9)
			{
				window.setMinimized(!window.isMinimized());
				saveWindows();
				return;
			}
		}
		
		window.startDragging(mouseX, mouseY);
	}
	
	private void handleScrollbarMouseClick(Window window, int mouseX,
		int mouseY, int mouseButton)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		
		if(mouseX >= window.getWidth() - 1)
			return;
		
		double outerHeight = window.getHeight() - 13;
		double innerHeight = window.getInnerHeight();
		double maxScrollbarHeight = outerHeight - 2;
		int scrollbarY =
			(int)(outerHeight * (-window.getScrollOffset() / innerHeight) + 1);
		int scrollbarHeight =
			(int)(maxScrollbarHeight * outerHeight / innerHeight);
		
		if(mouseY < scrollbarY || mouseY >= scrollbarY + scrollbarHeight)
			return;
		
		window.startDraggingScrollbar(window.getY() + 13 + mouseY);
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
	
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		updateColors();
		
		Matrix3x2fStack matrixStack = context.pose();
		matrixStack.pushMatrix();
		
		tooltip = "";
		ArrayList<Window> visibleWindows = new ArrayList<>();
		for(Window window : windows)
		{
			if(window.isInvisible())
				continue;
			
			// dragging
			if(window.isDragging())
				if(leftMouseButtonPressed)
					window.dragTo(mouseX, mouseY);
				else
				{
					window.stopDragging();
					saveWindows();
				}
			
			// scrollbar dragging
			if(window.isDraggingScrollbar())
				if(leftMouseButtonPressed)
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
	
	public void renderPopups(GuiGraphics context, int mouseX, int mouseY)
	{
		Matrix3x2fStack matrixStack = context.pose();
		for(Popup popup : popups)
		{
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			
			int x1 = parent.getX() + owner.getX();
			int y1 =
				parent.getY() + 13 + parent.getScrollOffset() + owner.getY();
			
			matrixStack.pushMatrix();
			matrixStack.translate(x1, y1);
			context.guiRenderState.up();
			
			int cMouseX = mouseX - x1;
			int cMouseY = mouseY - y1;
			popup.render(context, cMouseX, cMouseY);
			
			matrixStack.popMatrix();
		}
	}
	
	public void renderTooltip(GuiGraphics context, int mouseX, int mouseY)
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
		int sw = MC.screen.width;
		int sh = MC.screen.height;
		
		int xt1 = mouseX + tw + 11 <= sw ? mouseX + 8 : mouseX - tw - 8;
		int xt2 = xt1 + tw + 3;
		int yt1 = mouseY + th - 2 <= sh ? mouseY - 4 : mouseY - th - 4;
		int yt2 = yt1 + th + 2;
		
		context.guiRenderState.up();
		
		// background
		context.fill(xt1, yt1, xt2, yt2,
			RenderUtils.toIntColor(bgColor, ttOpacity));
		
		// outline
		RenderUtils.drawBorder2D(context, xt1, yt1, xt2, yt2,
			RenderUtils.toIntColor(acColor, 0.5F));
		
		// text
		context.guiRenderState.up();
		for(int i = 0; i < lines.length; i++)
			context.drawString(tr, lines[i], xt1 + 2,
				yt1 + 2 + i * tr.lineHeight, txtColor, false);
	}
	
	public void renderPinnedWindows(GuiGraphics context, float partialTicks)
	{
		ArrayList<Window> pinnedWindows = new ArrayList<>();
		for(Window window : windows)
		{
			if(window.isPinned() && !window.isInvisible())
				pinnedWindows.add(window);
		}
		
		if(pinnedWindows.isEmpty())
			return;
		
		if(isolateWindows)
			renderWindowsWithIsolation(context, pinnedWindows,
				Integer.MIN_VALUE, Integer.MIN_VALUE, partialTicks);
		else
			for(Window window : pinnedWindows)
			{
				context.guiRenderState.up();
				renderWindow(context, window, Integer.MIN_VALUE,
					Integer.MIN_VALUE, partialTicks);
			}
	}
	
	public void updateColors()
	{
		ClickGuiHack clickGui = WURST.getHax().clickGuiHack;
		
		opacity = clickGui.getOpacity();
		ttOpacity = clickGui.getTooltipOpacity();
		bgColor = clickGui.getBackgroundColor();
		txtColor = clickGui.getTextColor();
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
	
	private void renderWindow(GuiGraphics context, Window window, int mouseX,
		int mouseY, float partialTicks)
	{
		int x1 = window.getX();
		int y1 = window.getY();
		int x2 = x1 + window.getWidth();
		int y2 = y1 + window.getHeight();
		int y3 = y1 + 13;
		
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
				
				double outerHeight = y2 - y3;
				double innerHeight = window.getInnerHeight();
				double maxScrollbarHeight = outerHeight - 2;
				double scrollbarY =
					outerHeight * (-window.getScrollOffset() / innerHeight) + 1;
				double scrollbarHeight =
					maxScrollbarHeight * outerHeight / innerHeight;
				
				int ys1 = y3;
				int ys2 = y2;
				int ys3 = ys1 + (int)scrollbarY;
				int ys4 = ys3 + (int)scrollbarHeight;
				
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
				window.getChild(i).render(context, cMouseX, cMouseY,
					partialTicks);
			
			matrixStack.popMatrix();
			context.disableScissor();
		}
		
		// window outline
		RenderUtils.drawBorder2D(context, x1, y1, x2, y2, outlineColor);
		
		// title bar separator line
		if(!window.isMinimized())
			RenderUtils.drawLine2D(context, x1, y3, x2, y3, outlineColor);
		
		// title bar buttons
		int x3 = x2;
		int y4 = y1 + 2;
		int y5 = y3 - 2;
		boolean hoveringY = mouseY >= y4 && mouseY < y5;
		if(window.isClosable())
		{
			x3 -= 11;
			int x4 = x3 + 9;
			boolean hovering = hoveringY && mouseX >= x3 && mouseX < x4;
			renderTitleBarButton(context, x3, y4, x4, y5, hovering);
			ClickGuiIcons.drawCross(context, x3, y4, x4, y5, hovering);
		}
		
		if(window.isPinnable())
		{
			x3 -= 11;
			int x4 = x3 + 9;
			boolean hovering = hoveringY && mouseX >= x3 && mouseX < x4;
			renderTitleBarButton(context, x3, y4, x4, y5, hovering);
			ClickGuiIcons.drawPin(context, x3, y4, x4, y5, hovering,
				window.isPinned());
		}
		
		if(window.isMinimizable())
		{
			x3 -= 11;
			int x4 = x3 + 9;
			boolean hovering = hoveringY && mouseX >= x3 && mouseX < x4;
			renderTitleBarButton(context, x3, y4, x4, y5, hovering);
			ClickGuiIcons.drawMinimizeArrow(context, x3, y4, x4, y5, hovering,
				window.isMinimized());
		}
		
		// title bar background
		// above & below buttons
		int titleBgColor = RenderUtils.toIntColor(acColor, opacity);
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
		context.drawString(tr, title, x1 + 2, y1 + 3, txtColor, false);
	}
	
	private void renderTitleBarButton(GuiGraphics context, int x1, int y1,
		int x2, int y2, boolean hovering)
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
	
	private void renderWindowsWithIsolation(GuiGraphics context,
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
	
	/**
	 * Add a feature to the Favorites window if not already present.
	 */
	public void addFavoriteFeature(Feature feature)
	{
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
		return leftMouseButtonPressed;
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
				? ((FeatureButton)c1).getFeature().getName()
				: c1.getClass().getName();
			String n2 = c2 instanceof FeatureButton
				? ((FeatureButton)c2).getFeature().getName()
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
}
