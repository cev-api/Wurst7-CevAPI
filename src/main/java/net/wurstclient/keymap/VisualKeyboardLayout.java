/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.keymap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.wurstclient.keymap.VisualKey.VisualKeyCategory;

public final class VisualKeyboardLayout
{
	public static final int CANVAS_WIDTH = 1460;
	public static final int CANVAS_HEIGHT = 620;
	
	private final List<VisualKey> keys;
	
	private VisualKeyboardLayout(List<VisualKey> keys)
	{
		this.keys = List.copyOf(keys);
	}
	
	public List<VisualKey> keys()
	{
		return Collections.unmodifiableList(keys);
	}
	
	public static VisualKeyboardLayout createFullKeyboard()
	{
		ArrayList<VisualKey> keys = new ArrayList<>();
		
		addMacroRow(keys, 124F, 20F);
		addFunctionRow(keys, 24F, 66F);
		addSystemButtons(keys, 860F, 66F);
		addMainKeyboard(keys, 24F, 120F);
		addNavigationCluster(keys, 860F, 120F);
		addArrowCluster(keys, 893F, 274F);
		addNumpad(keys, 1108F, 120F);
		addMouseButtons(keys, 836F, 388F);
		
		return new VisualKeyboardLayout(keys);
	}
	
	public static void addMacroRow(List<VisualKey> keys, float x, float y)
	{
		float keyH = 28F;
		float gap = 6F;
		float wideGap = 43F;
		
		for(int i = GLFW.GLFW_KEY_F13; i <= GLFW.GLFW_KEY_F16; i++)
		{
			addKey(keys, "F" + (i - GLFW.GLFW_KEY_F13 + 13), i, x, y, 48F, keyH,
				VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
		x += wideGap - gap;
		for(int i = GLFW.GLFW_KEY_F17; i <= GLFW.GLFW_KEY_F20; i++)
		{
			addKey(keys, "F" + (i - GLFW.GLFW_KEY_F13 + 13), i, x, y, 48F, keyH,
				VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
		x += wideGap - gap;
		for(int i = GLFW.GLFW_KEY_F21; i <= GLFW.GLFW_KEY_F24; i++)
		{
			addKey(keys, "F" + (i - GLFW.GLFW_KEY_F13 + 13), i, x, y, 48F, keyH,
				VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
	}
	
	public static void addFunctionRow(List<VisualKey> keys, float x, float y)
	{
		float keyH = 42F;
		float gap = 6F;
		float wideGapA = 42F;
		float wideGapB = 43F;
		float wideGapC = 43F;
		
		addKey(keys, "Esc", GLFW.GLFW_KEY_ESCAPE, x, y, 58F, keyH,
			VisualKeyCategory.FUNCTION);
		x += 58F + wideGapA;
		
		for(int i = GLFW.GLFW_KEY_F1; i <= GLFW.GLFW_KEY_F4; i++)
		{
			addKey(keys, "F" + (i - GLFW.GLFW_KEY_F1 + 1), i, x, y, 48F, keyH,
				VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
		x += wideGapB - gap;
		for(int i = GLFW.GLFW_KEY_F5; i <= GLFW.GLFW_KEY_F8; i++)
		{
			addKey(keys, "F" + (i - GLFW.GLFW_KEY_F1 + 1), i, x, y, 48F, keyH,
				VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
		x += wideGapC - gap;
		for(int i = GLFW.GLFW_KEY_F9; i <= GLFW.GLFW_KEY_F12; i++)
		{
			addKey(keys, "F" + (i - GLFW.GLFW_KEY_F1 + 1), i, x, y, 48F, keyH,
				VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
	}
	
	public static void addSystemButtons(List<VisualKey> keys, float x, float y)
	{
		float keyH = 42F;
		float gap = 6F;
		
		addKey(keys, "PrtSc", GLFW.GLFW_KEY_PRINT_SCREEN, x, y, 70F, keyH,
			VisualKeyCategory.FUNCTION);
		x += 70F + gap;
		addKey(keys, "ScrLk", GLFW.GLFW_KEY_SCROLL_LOCK, x, y, 70F, keyH,
			VisualKeyCategory.FUNCTION);
		x += 70F + gap;
		addKey(keys, "Pause", GLFW.GLFW_KEY_PAUSE, x, y, 70F, keyH,
			VisualKeyCategory.FUNCTION);
	}
	
	public static void addMainKeyboard(List<VisualKey> keys, float x, float y)
	{
		float keyH = 42F;
		float gap = 6F;
		
		addKey(keys, "`", GLFW.GLFW_KEY_GRAVE_ACCENT, x, y, 42F, keyH,
			VisualKeyCategory.MAIN);
		x += 42F + gap;
		
		addKey(keys, "1", GLFW.GLFW_KEY_1, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "2", GLFW.GLFW_KEY_2, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "3", GLFW.GLFW_KEY_3, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "4", GLFW.GLFW_KEY_4, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "5", GLFW.GLFW_KEY_5, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "6", GLFW.GLFW_KEY_6, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "7", GLFW.GLFW_KEY_7, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "8", GLFW.GLFW_KEY_8, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "9", GLFW.GLFW_KEY_9, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "0", GLFW.GLFW_KEY_0, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		
		addKey(keys, "-", GLFW.GLFW_KEY_MINUS, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "=", GLFW.GLFW_KEY_EQUAL, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "Backspace", GLFW.GLFW_KEY_BACKSPACE, x, y, 96F, keyH,
			VisualKeyCategory.MAIN);
		
		y += 48F;
		x = 24F;
		addKey(keys, "Tab", GLFW.GLFW_KEY_TAB, x, y, 68F, keyH,
			VisualKeyCategory.MAIN);
		x += 68F + gap;
		int[] qRow = {GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_E,
			GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_U,
			GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_P};
		String[] qLabels = {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"};
		for(int key : qRow)
		{
			addKey(keys, qLabels[0], key, x, y, 48F, keyH,
				VisualKeyCategory.MAIN);
			qLabels = shiftLeft(qLabels);
			x += 48F + gap;
		}
		addKey(keys, "[", GLFW.GLFW_KEY_LEFT_BRACKET, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "]", GLFW.GLFW_KEY_RIGHT_BRACKET, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "\\", GLFW.GLFW_KEY_BACKSLASH, x, y, 94F, keyH,
			VisualKeyCategory.MAIN);
		
		y += 48F;
		x = 24F;
		addKey(keys, "Caps", GLFW.GLFW_KEY_CAPS_LOCK, x, y, 82F, keyH,
			VisualKeyCategory.MAIN);
		x += 82F + gap;
		int[] aRow = {GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D,
			GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_J,
			GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_L};
		String[] aLabels = {"A", "S", "D", "F", "G", "H", "J", "K", "L"};
		for(int key : aRow)
		{
			addKey(keys, aLabels[0], key, x, y, 48F, keyH,
				VisualKeyCategory.MAIN);
			aLabels = shiftLeft(aLabels);
			x += 48F + gap;
		}
		addKey(keys, ";", GLFW.GLFW_KEY_SEMICOLON, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "'", GLFW.GLFW_KEY_APOSTROPHE, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "Enter", GLFW.GLFW_KEY_ENTER, x, y, 134F, keyH,
			VisualKeyCategory.MAIN);
		
		y += 48F;
		x = 24F;
		addKey(keys, "Left Shift", GLFW.GLFW_KEY_LEFT_SHIFT, x, y, 110F, keyH,
			VisualKeyCategory.MAIN);
		x += 110F + gap;
		int[] zRow = {GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_C,
			GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_M};
		String[] zLabels = {"Z", "X", "C", "V", "B", "N", "M"};
		for(int key : zRow)
		{
			addKey(keys, zLabels[0], key, x, y, 48F, keyH,
				VisualKeyCategory.MAIN);
			zLabels = shiftLeft(zLabels);
			x += 48F + gap;
		}
		addKey(keys, ",", GLFW.GLFW_KEY_COMMA, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, ".", GLFW.GLFW_KEY_PERIOD, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "/", GLFW.GLFW_KEY_SLASH, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "Right Shift", GLFW.GLFW_KEY_RIGHT_SHIFT, x, y, 160F, keyH,
			VisualKeyCategory.MAIN);
		
		y += 48F;
		x = 24F;
		addKey(keys, "Left Ctrl", GLFW.GLFW_KEY_LEFT_CONTROL, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Left Win", GLFW.GLFW_KEY_LEFT_SUPER, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Left Alt", GLFW.GLFW_KEY_LEFT_ALT, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Space", GLFW.GLFW_KEY_SPACE, x, y, 332F, keyH,
			VisualKeyCategory.MAIN);
		x += 332F + gap;
		addKey(keys, "Right Alt", GLFW.GLFW_KEY_RIGHT_ALT, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Right Win", GLFW.GLFW_KEY_RIGHT_SUPER, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Menu", GLFW.GLFW_KEY_MENU, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Right Ctrl", GLFW.GLFW_KEY_RIGHT_CONTROL, x, y, 58F, keyH,
			VisualKeyCategory.MAIN);
	}
	
	public static void addNavigationCluster(List<VisualKey> keys, float x,
		float y)
	{
		float keyH = 42F;
		float gap = 6F;
		
		addKey(keys, "Ins", GLFW.GLFW_KEY_INSERT, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
		x += 70F + gap;
		addKey(keys, "Home", GLFW.GLFW_KEY_HOME, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
		x += 70F + gap;
		addKey(keys, "PgUp", GLFW.GLFW_KEY_PAGE_UP, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
		
		y += 48F;
		x -= (70F + gap) * 2F;
		addKey(keys, "Del", GLFW.GLFW_KEY_DELETE, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
		x += 70F + gap;
		addKey(keys, "End", GLFW.GLFW_KEY_END, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
		x += 70F + gap;
		addKey(keys, "PgDn", GLFW.GLFW_KEY_PAGE_DOWN, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
	}
	
	public static void addArrowCluster(List<VisualKey> keys, float x, float y)
	{
		float keyH = 42F;
		float keyW = 48F;
		float gap = 6F;
		
		addKey(keys, "Up", GLFW.GLFW_KEY_UP, x + keyW + gap, y, keyW, keyH,
			VisualKeyCategory.ARROW);
		addKey(keys, "Left", GLFW.GLFW_KEY_LEFT, x, y + keyH + gap, keyW, keyH,
			VisualKeyCategory.ARROW);
		addKey(keys, "Down", GLFW.GLFW_KEY_DOWN, x + keyW + gap, y + keyH + gap,
			keyW, keyH, VisualKeyCategory.ARROW);
		addKey(keys, "Right", GLFW.GLFW_KEY_RIGHT, x + (keyW + gap) * 2F,
			y + keyH + gap, keyW, keyH, VisualKeyCategory.ARROW);
	}
	
	public static void addNumpad(List<VisualKey> keys, float x, float y)
	{
		float keyH = 42F;
		float keyW = 48F;
		float gap = 6F;
		
		addKey(keys, "Num", GLFW.GLFW_KEY_NUM_LOCK, x, y, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "/", GLFW.GLFW_KEY_KP_DIVIDE, x + keyW + gap, y, keyW,
			keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "*", GLFW.GLFW_KEY_KP_MULTIPLY, x + (keyW + gap) * 2F, y,
			keyW, keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "-", GLFW.GLFW_KEY_KP_SUBTRACT, x + (keyW + gap) * 3F, y,
			keyW, keyH, VisualKeyCategory.NUMPAD);
		
		float rowY = y + keyH + gap;
		addKey(keys, "7", GLFW.GLFW_KEY_KP_7, x, rowY, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "8", GLFW.GLFW_KEY_KP_8, x + keyW + gap, rowY, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "9", GLFW.GLFW_KEY_KP_9, x + (keyW + gap) * 2F, rowY, keyW,
			keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "+", GLFW.GLFW_KEY_KP_ADD, x + (keyW + gap) * 3F, rowY,
			keyW, keyH * 2F + gap, VisualKeyCategory.NUMPAD);
		
		rowY += keyH + gap;
		addKey(keys, "4", GLFW.GLFW_KEY_KP_4, x, rowY, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "5", GLFW.GLFW_KEY_KP_5, x + keyW + gap, rowY, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "6", GLFW.GLFW_KEY_KP_6, x + (keyW + gap) * 2F, rowY, keyW,
			keyH, VisualKeyCategory.NUMPAD);
		
		rowY += keyH + gap;
		addKey(keys, "1", GLFW.GLFW_KEY_KP_1, x, rowY, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "2", GLFW.GLFW_KEY_KP_2, x + keyW + gap, rowY, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "3", GLFW.GLFW_KEY_KP_3, x + (keyW + gap) * 2F, rowY, keyW,
			keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "Enter", GLFW.GLFW_KEY_KP_ENTER, x + (keyW + gap) * 3F,
			rowY, keyW, keyH * 2F + gap, VisualKeyCategory.NUMPAD);
		
		rowY += keyH + gap;
		addKey(keys, "0", GLFW.GLFW_KEY_KP_0, x, rowY, keyW * 2F + gap, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, ".", GLFW.GLFW_KEY_KP_DECIMAL, x + (keyW + gap) * 2F, rowY,
			keyW, keyH, VisualKeyCategory.NUMPAD);
	}
	
	public static void addMouseButtons(List<VisualKey> keys, float x, float y)
	{
		float keyH = 28F;
		float keyW = 50F;
		float gap = 6F;
		
		addMouseKey(keys, "M1", GLFW.GLFW_MOUSE_BUTTON_1, x, y, keyW, keyH);
		addMouseKey(keys, "M2", GLFW.GLFW_MOUSE_BUTTON_2, x + keyW + gap, y,
			keyW, keyH);
		addMouseKey(keys, "M3", GLFW.GLFW_MOUSE_BUTTON_3, x + (keyW + gap) * 2F,
			y, keyW, keyH);
		addMouseKey(keys, "M4", GLFW.GLFW_MOUSE_BUTTON_4, x + (keyW + gap) * 3F,
			y, keyW, keyH);
		addMouseKey(keys, "M5", GLFW.GLFW_MOUSE_BUTTON_5, x + (keyW + gap) * 4F,
			y, keyW, keyH);
	}
	
	private static void addKey(List<VisualKey> keys, String label, int glfwKey,
		float x, float y, float width, float height, VisualKeyCategory category)
	{
		keys.add(new VisualKey(label,
			InputConstants.Type.KEYSYM.getOrCreate(glfwKey), x, y, width,
			height, category));
	}
	
	private static void addMouseKey(List<VisualKey> keys, String label,
		int mouseButton, float x, float y, float width, float height)
	{
		keys.add(new VisualKey(label,
			InputConstants.Type.MOUSE.getOrCreate(mouseButton), x, y, width,
			height, VisualKeyCategory.MOUSE));
	}
	
	private static String[] shiftLeft(String[] values)
	{
		if(values.length == 0)
			return values;
		
		String[] shifted = new String[values.length];
		for(int i = 1; i < values.length; i++)
			shifted[i - 1] = values[i];
		shifted[values.length - 1] = values[0];
		return shifted;
	}
	
}
