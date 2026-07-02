/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.keymap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.CommonColors;
import net.wurstclient.keybinds.Keybind;
import net.wurstclient.util.ChatUtils;

public final class KeyboardBindsScreen extends Screen
{
	private static final long PRESS_HIGHLIGHT_MS = 260L;
	private static final int PANEL_TOP = 48;
	private static final int PANEL_BOTTOM = 44;
	
	private final Screen prevScreen;
	private final VisualKeyboardLayout layout;
	private final WurstBindBridge bindBridge;
	private final KeyOccupancyScanner occupancyScanner;
	
	private Map<InputConstants.Key, List<String>> mcOccupancy = Map.of();
	private final Map<InputConstants.Key, Long> pressedKeys =
		new LinkedHashMap<>();
	private VisualKey hoveredKey;
	
	public KeyboardBindsScreen(Screen prevScreen)
	{
		super(Component.literal(""));
		this.prevScreen = prevScreen;
		this.layout = VisualKeyboardLayout.createFullKeyboard();
		this.bindBridge = new WurstBindBridge();
		this.occupancyScanner = new KeyOccupancyScanner();
	}
	
	@Override
	public void init()
	{
		addRenderableWidget(Button
			.builder(Component.literal("Back"),
				b -> minecraft.gui.setScreen(prevScreen))
			.bounds(width / 2 - 50, height - 28, 100, 20).build());
		refreshOccupancy();
	}
	
	@Override
	public void tick()
	{
		refreshOccupancy();
		prunePressedKeys();
	}
	
	private void refreshOccupancy()
	{
		mcOccupancy = occupancyScanner.scan(minecraft);
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		flashKey(InputConstants.Type.KEYSYM.getOrCreate(context.key()));
		
		if(context.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			minecraft.gui.setScreen(prevScreen);
			return true;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_4)
		{
			minecraft.gui.setScreen(prevScreen);
			return true;
		}
		
		VisualKey key = getKeyAtMouse(context.x(), context.y());
		if(key == null)
			return super.mouseClicked(context, doubleClick);
		
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT)
		{
			String command = bindBridge.getCommandForKey(key.key());
			minecraft.gui
				.setScreen(new KeyCommandEditScreen(this, key, command));
			return true;
		}
		
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
		{
			if(bindBridge.getCommandForKey(key.key()) != null)
				bindBridge.clearCommandForKey(key.key());
			return true;
		}
		
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE)
		{
			minecraft.keyboardHandler.setClipboard(key.key().getName());
			ChatUtils.message("Copied key: " + key.key().getName());
			return true;
		}
		
		return super.mouseClicked(context, doubleClick);
	}
	
	private VisualKey getKeyAtMouse(double mouseX, double mouseY)
	{
		double virtualMouseX = screenToVirtualX(mouseX);
		double virtualMouseY = screenToVirtualY(mouseY);
		
		for(VisualKey key : layout.keys())
		{
			if(key.contains(virtualMouseX, virtualMouseY))
				return key;
		}
		
		return null;
	}
	
	private double getScale()
	{
		double availableW = Math.max(1, width - 32);
		double availableH = Math.max(1, height - PANEL_TOP - PANEL_BOTTOM);
		double scale = Math.min(availableW / getLayoutWidth(),
			availableH / getLayoutHeight());
		return Math.max(0.25D, scale);
	}
	
	private double getOriginX()
	{
		double scale = getScale();
		double keyboardW = getLayoutWidth() * scale;
		return (width - keyboardW) / 2.0 - getLayoutMinX() * scale;
	}
	
	private double getOriginY()
	{
		double scale = getScale();
		double keyboardH = getLayoutHeight() * scale;
		double availableH = Math.max(1, height - PANEL_TOP - PANEL_BOTTOM);
		return PANEL_TOP + (availableH - keyboardH) / 2.0
			- getLayoutMinY() * scale;
	}
	
	private double screenToVirtualX(double screenX)
	{
		return (screenX - getOriginX()) / getScale();
	}
	
	private double screenToVirtualY(double screenY)
	{
		return (screenY - getOriginY()) / getScale();
	}
	
	private int toScreenX(float virtualX)
	{
		return (int)Math.round(getOriginX() + virtualX * getScale());
	}
	
	private int toScreenY(float virtualY)
	{
		return (int)Math.round(getOriginY() + virtualY * getScale());
	}
	
	private int toScreenW(float virtualW)
	{
		return Math.max(1, (int)Math.round(virtualW * getScale()));
	}
	
	private int toScreenH(float virtualH)
	{
		return Math.max(1, (int)Math.round(virtualH * getScale()));
	}
	
	@Override
	public boolean charTyped(CharacterEvent event)
	{
		boolean handled = super.charTyped(event);
		return handled;
	}
	
	private void flashKey(InputConstants.Key key)
	{
		if(key == null)
			return;
		
		pressedKeys.put(key, System.currentTimeMillis());
	}
	
	private void prunePressedKeys()
	{
		long now = System.currentTimeMillis();
		pressedKeys.entrySet()
			.removeIf(entry -> now - entry.getValue() > PRESS_HIGHLIGHT_MS);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		Font font = minecraft.font;
		hoveredKey = getKeyAtMouse(mouseX, mouseY);
		
		context.fillGradient(0, 0, width, height, 0xFF0C1016, 0xFF171C28);
		
		int titleY = 16;
		context.centeredText(font, "Keyboard Binds / Keymap", width / 2, titleY,
			CommonColors.WHITE);
		context.centeredText(font,
			buildHeaderLine("Click a key to edit its ", "Wurst",
				" command, right click to clear.", 0xFFFFA94D),
			width / 2, titleY + 12, CommonColors.LIGHT_GRAY);
		context.centeredText(font,
			buildHeaderLine("Minecraft keybinds are shown on the keyboard in ",
				"yellow", ".", 0xFFFFE18A),
			width / 2, titleY + 22, CommonColors.LIGHT_GRAY);
		context.centeredText(font,
			buildHeaderLine("Conflicting keys are shown in ", "red",
				". Wurst commands typically take priority in conflicts.",
				0xFFFF5B5B),
			width / 2, titleY + 32, CommonColors.LIGHT_GRAY);
		
		drawKeyboard(context, font, mouseX, mouseY);
		drawMouseLabel(context, font);
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		if(hoveredKey != null)
			context.setComponentTooltipForNextFrame(font,
				buildTooltip(hoveredKey), mouseX, mouseY);
	}
	
	private void drawKeyboard(GuiGraphicsExtractor context, Font font,
		int mouseX, int mouseY)
	{
		context.fill(toScreenX((float)getLayoutMinX() - 12F),
			toScreenY((float)getLayoutMinY() - 10F),
			toScreenX((float)getLayoutMaxX() + 12F),
			toScreenY((float)getLayoutMaxY() + 10F), 0x46101014);
		
		for(VisualKey key : layout.keys())
			drawKey(context, font, key, mouseX, mouseY);
	}
	
	private double getLayoutMinX()
	{
		double min = Double.POSITIVE_INFINITY;
		for(VisualKey key : layout.keys())
			min = Math.min(min, key.x());
		return Double.isFinite(min) ? min : 0D;
	}
	
	private double getLayoutMaxX()
	{
		double max = Double.NEGATIVE_INFINITY;
		for(VisualKey key : layout.keys())
			max = Math.max(max, key.x() + key.width());
		return Double.isFinite(max) ? max : VisualKeyboardLayout.CANVAS_WIDTH;
	}
	
	private double getLayoutMinY()
	{
		double min = Double.POSITIVE_INFINITY;
		for(VisualKey key : layout.keys())
			min = Math.min(min, key.y());
		return Double.isFinite(min) ? min : 0D;
	}
	
	private double getLayoutMaxY()
	{
		double max = Double.NEGATIVE_INFINITY;
		for(VisualKey key : layout.keys())
			max = Math.max(max, key.y() + key.height());
		return Double.isFinite(max) ? max : VisualKeyboardLayout.CANVAS_HEIGHT;
	}
	
	private double getLayoutWidth()
	{
		return Math.max(1D, getLayoutMaxX() - getLayoutMinX());
	}
	
	private double getLayoutHeight()
	{
		return Math.max(1D, getLayoutMaxY() - getLayoutMinY());
	}
	
	private void drawMouseLabel(GuiGraphicsExtractor context, Font font)
	{
		float minX = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		for(VisualKey key : layout.keys())
		{
			if(key.category() != VisualKey.VisualKeyCategory.MOUSE)
				continue;
			
			minX = Math.min(minX, key.x());
			maxX = Math.max(maxX, key.x() + key.width());
		}
		
		if(!Float.isFinite(minX) || !Float.isFinite(maxX))
			return;
		
		int x1 = toScreenX(minX);
		int x2 = toScreenX(maxX);
		float minY = Float.POSITIVE_INFINITY;
		for(VisualKey key : layout.keys())
		{
			if(key.category() != VisualKey.VisualKeyCategory.MOUSE)
				continue;
			
			minY = Math.min(minY, key.y());
		}
		
		if(!Float.isFinite(minY))
			return;
		
		float maxY = Float.NEGATIVE_INFINITY;
		for(VisualKey key : layout.keys())
		{
			if(key.category() != VisualKey.VisualKeyCategory.MOUSE)
				continue;
			
			maxY = Math.max(maxY, key.y() + key.height());
		}
		
		if(!Float.isFinite(maxY))
			return;
		
		int y = toScreenY(maxY + 6F);
		context.centeredText(font, "Mouse", (x1 + x2) / 2, y,
			CommonColors.LIGHT_GRAY);
	}
	
	private void drawKey(GuiGraphicsExtractor context, Font font, VisualKey key,
		int mouseX, int mouseY)
	{
		int x = toScreenX(key.x());
		int y = toScreenY(key.y());
		int w = toScreenW(key.width());
		int h = toScreenH(key.height());
		
		boolean hovered = hoveredKey == key;
		String command = bindBridge.getCommandForKey(key.key());
		boolean hasWurst = command != null;
		List<String> occupied = mcOccupancy.getOrDefault(key.key(), List.of());
		boolean conflict = hasWurst && !occupied.isEmpty();
		boolean occupiedOnly = !hasWurst && !occupied.isEmpty();
		long pressAge = getPressAgeMs(key.key());
		float pressPulse = getPressPulse(pressAge);
		
		int baseColor = getCategoryColor(key.category());
		int fillColor = hovered ? brighten(baseColor, 0.16F) : baseColor;
		if(hasWurst)
			fillColor = mix(fillColor, 0xFF8A4E17, 0.24F);
		if(occupiedOnly)
			fillColor = mix(fillColor, 0xFF7A6518, 0.26F);
		if(conflict)
			fillColor = mix(fillColor, 0xFF742121, 0.26F);
		if(pressPulse > 0F)
			fillColor = mix(fillColor, 0xFF71FF52, pressPulse * 0.72F);
		
		context.fill(x, y, x + w, y + h, 0xFF0B0D10);
		context.fill(x + 1, y + 1, x + w - 1, y + h - 1, fillColor);
		
		int borderColor = conflict ? 0xFFFF5B5B
			: pressPulse > 0F ? 0xFF8CFF72 : hasWurst ? 0xFFFFA94D
				: occupiedOnly ? 0xFFFFD24D : hovered ? 0xFFB7D3FF : 0xFF757E8B;
		drawBorder(context, x, y, x + w, y + h, borderColor);
		if(conflict)
			drawBorder(context, x + 1, y + 1, x + w - 1, y + h - 1, 0x80FF0000);
		if(pressPulse > 0F)
			drawBorder(context, x + 1, y + 1, x + w - 1, y + h - 1, 0xAA71FF52);
		
		context.guiRenderState.up();
		int padX = Math.max(2, (int)Math.round(w * 0.08));
		int keyTopY = y + 3;
		int keyMidY = y + h / 2 - font.lineHeight / 2;
		int keyBotY = y + h - font.lineHeight - 3;
		
		drawCenteredFitText(context, font, key.label(), x + padX, keyTopY,
			x + w - padX, keyTopY + font.lineHeight, 0xFFF4F6F9);
		
		if(command != null && h >= 32)
		{
			drawCenteredFitText(context, font, shortenCommandLabel(command),
				x + padX, keyMidY, x + w - padX, keyMidY + font.lineHeight,
				0xFFFFB86B);
		}else if(!occupied.isEmpty() && h >= 32)
		{
			drawCenteredFitText(context, font,
				shortenControlLabel(getOccupiedLabel(occupied)), x + padX,
				keyMidY, x + w - padX, keyMidY + font.lineHeight, 0xFFFFE18A);
		}
		
		if(conflict && !occupied.isEmpty() && h >= 34)
		{
			drawCenteredFitText(context, font,
				shortenControlLabel(getOccupiedLabel(occupied)), x + padX,
				keyBotY, x + w - padX, keyBotY + font.lineHeight, 0xFFFFE18A);
		}
		
		if(conflict)
			context.text(font, "!", x + w - 8, y + 2, 0xFFFFB3B3, false);
	}
	
	private void drawCenteredFitText(GuiGraphicsExtractor context, Font font,
		String text, int x1, int y1, int x2, int y2, int color)
	{
		if(text == null || text.isBlank() || x2 <= x1)
			return;
		
		String fitted = fitText(font, text, x2 - x1 - 2);
		int textW = font.width(fitted);
		int textX = x1 + Math.max(0, (x2 - x1 - textW) / 2);
		int textY = y1 + Math.max(0, (y2 - y1 - font.lineHeight) / 2);
		context.enableScissor(x1, y1, x2, y2);
		context.text(font, fitted, textX, textY, color, false);
		context.disableScissor();
	}
	
	private String fitText(Font font, String text, int maxWidth)
	{
		if(text == null)
			return "";
		
		if(maxWidth <= 0 || font.width(text) <= maxWidth)
			return text;
		
		String ellipsis = "...";
		int targetWidth = Math.max(0, maxWidth - font.width(ellipsis));
		StringBuilder builder = new StringBuilder(text);
		while(builder.length() > 0
			&& font.width(builder.toString()) > targetWidth)
			builder.setLength(builder.length() - 1);
		return builder + ellipsis;
	}
	
	private List<Component> buildTooltip(VisualKey key)
	{
		ArrayList<Component> lines = new ArrayList<>();
		String displayKey = Keybind.getDisplayKey(key.key().getName());
		lines.add(Component.literal("Physical key: " + displayKey));
		
		String command = bindBridge.getCommandForKey(key.key());
		if(command == null || command.isBlank())
		{
			lines.add(Component.literal("Wurst command: (none)")
				.withStyle(style -> style.withColor(0xFFB8B8B8)));
		}else
		{
			MutableComponent line = Component.literal("Wurst command: ")
				.withStyle(style -> style.withColor(0xFFE0E0E0));
			line.append(Component.literal(command)
				.withStyle(style -> style.withColor(0xFFFFA94D)));
			lines.add(line);
		}
		
		List<String> occupied = mcOccupancy.getOrDefault(key.key(), List.of());
		if(occupied.isEmpty())
			lines.add(Component.literal("Minecraft / mod controls: (none)"));
		else
		{
			lines.add(Component.literal("Minecraft / mod controls:"));
			for(String control : occupied)
			{
				MutableComponent line = Component.literal("- ")
					.withStyle(style -> style.withColor(0xFFE0E0E0));
				line.append(Component.literal(control)
					.withStyle(style -> style.withColor(0xFFFFE18A)));
				lines.add(line);
			}
		}
		
		if(command != null && !occupied.isEmpty())
			lines.add(Component.literal(
				"Status: conflict - Wurst and Minecraft/mods both use this key."));
		else if(command != null)
			lines.add(Component.literal("Status: Wurst bound."));
		else if(!occupied.isEmpty())
			lines.add(Component.literal(
				"Status: occupied by Minecraft or another mod/plugin."));
		else
			lines.add(Component.literal("Status: free."));
		return lines;
	}
	
	private static String shortenCommandLabel(String command)
	{
		if(command == null)
			return "";
		
		String first = command.split(";", 2)[0].trim();
		if(first.isEmpty())
			return "";
		
		String[] parts = first.split("\\s+");
		if(parts.length >= 2 && parts[0].startsWith("."))
			return parts[1];
		
		return parts[0];
	}
	
	private static String shortenControlLabel(String label)
	{
		if(label == null)
			return "";
		
		String trimmed = label.trim();
		if(trimmed.length() <= 16)
			return trimmed;
		
		return trimmed.substring(0, 13) + "...";
	}
	
	private static String getOccupiedLabel(List<String> occupied)
	{
		if(occupied.isEmpty())
			return "";
		
		return occupied.size() == 1 ? occupied.get(0)
			: occupied.get(0) + " +" + (occupied.size() - 1);
	}
	
	private static Component buildHeaderLine(String prefix, String emphasis,
		String suffix, int color)
	{
		MutableComponent line = Component.literal(prefix);
		line.append(Component.literal(emphasis)
			.withStyle(style -> style.withColor(color)));
		line.append(Component.literal(suffix));
		return line;
	}
	
	private long getPressAgeMs(InputConstants.Key key)
	{
		Long pressedAt = pressedKeys.get(key);
		if(pressedAt == null)
			return Long.MAX_VALUE;
		
		return Math.max(0L, System.currentTimeMillis() - pressedAt);
	}
	
	private static float getPressPulse(long ageMs)
	{
		if(ageMs < 0L || ageMs > PRESS_HIGHLIGHT_MS)
			return 0F;
		
		float progress = ageMs / (float)PRESS_HIGHLIGHT_MS;
		float pulse = 1F - progress;
		return pulse * pulse;
	}
	
	private static int getCategoryColor(VisualKey.VisualKeyCategory category)
	{
		Objects.requireNonNull(category);
		return 0xFF2F3440;
	}
	
	private static int brighten(int color, float amount)
	{
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		r = Math.min(255, (int)(r + 255 * amount));
		g = Math.min(255, (int)(g + 255 * amount));
		b = Math.min(255, (int)(b + 255 * amount));
		return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
	}
	
	private static int mix(int color, int overlay, float alpha)
	{
		alpha = Math.max(0F, Math.min(1F, alpha));
		int a = (color >>> 24) & 0xFF;
		int r = blend((color >> 16) & 0xFF, (overlay >> 16) & 0xFF, alpha);
		int g = blend((color >> 8) & 0xFF, (overlay >> 8) & 0xFF, alpha);
		int b = blend(color & 0xFF, overlay & 0xFF, alpha);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
	
	private static int blend(int base, int overlay, float alpha)
	{
		return Math.min(255, Math.round(base * (1F - alpha) + overlay * alpha));
	}
	
	private static void drawBorder(GuiGraphicsExtractor context, int x1, int y1,
		int x2, int y2, int color)
	{
		context.horizontalLine(x1, x2 - 1, y1, color);
		context.horizontalLine(x1, x2 - 1, y2 - 1, color);
		context.verticalLine(x1, y1 + 1, y2 - 1, color);
		context.verticalLine(x2 - 1, y1 + 1, y2 - 1, color);
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
}
