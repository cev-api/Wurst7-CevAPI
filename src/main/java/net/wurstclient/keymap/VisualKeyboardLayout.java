/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.keymap;

import org.lwjgl.sdl.SDLScancode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
		
		for(int i = InputConstants.KEY_F13; i <= InputConstants.KEY_F16; i++)
		{
			addKey(keys, "F" + (i - InputConstants.KEY_F13 + 13), i, x, y, 48F,
				keyH, VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
		x += wideGap - gap;
		for(int i = InputConstants.KEY_F17; i <= InputConstants.KEY_F20; i++)
		{
			addKey(keys, "F" + (i - InputConstants.KEY_F13 + 13), i, x, y, 48F,
				keyH, VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
		x += wideGap - gap;
		for(int i = InputConstants.KEY_F21; i <= InputConstants.KEY_F24; i++)
		{
			addKey(keys, "F" + (i - InputConstants.KEY_F13 + 13), i, x, y, 48F,
				keyH, VisualKeyCategory.FUNCTION);
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
		
		addKey(keys, "Esc", InputConstants.KEY_ESCAPE, x, y, 58F, keyH,
			VisualKeyCategory.FUNCTION);
		x += 58F + wideGapA;
		
		for(int i = InputConstants.KEY_F1; i <= InputConstants.KEY_F4; i++)
		{
			addKey(keys, "F" + (i - InputConstants.KEY_F1 + 1), i, x, y, 48F,
				keyH, VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
		x += wideGapB - gap;
		for(int i = InputConstants.KEY_F5; i <= InputConstants.KEY_F8; i++)
		{
			addKey(keys, "F" + (i - InputConstants.KEY_F1 + 1), i, x, y, 48F,
				keyH, VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
		x += wideGapC - gap;
		for(int i = InputConstants.KEY_F9; i <= InputConstants.KEY_F12; i++)
		{
			addKey(keys, "F" + (i - InputConstants.KEY_F1 + 1), i, x, y, 48F,
				keyH, VisualKeyCategory.FUNCTION);
			x += 48F + gap;
		}
	}
	
	public static void addSystemButtons(List<VisualKey> keys, float x, float y)
	{
		float keyH = 42F;
		float gap = 6F;
		
		addKey(keys, "PrtSc", InputConstants.KEY_PRINTSCREEN, x, y, 70F, keyH,
			VisualKeyCategory.FUNCTION);
		x += 70F + gap;
		addKey(keys, "ScrLk", InputConstants.KEY_SCROLLLOCK, x, y, 70F, keyH,
			VisualKeyCategory.FUNCTION);
		x += 70F + gap;
		addKey(keys, "Pause", InputConstants.KEY_PAUSE, x, y, 70F, keyH,
			VisualKeyCategory.FUNCTION);
	}
	
	public static void addMainKeyboard(List<VisualKey> keys, float x, float y)
	{
		float keyH = 42F;
		float gap = 6F;
		
		addKey(keys, "`", InputConstants.KEY_GRAVE, x, y, 42F, keyH,
			VisualKeyCategory.MAIN);
		x += 42F + gap;
		
		addKey(keys, "1", InputConstants.KEY_1, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "2", InputConstants.KEY_2, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "3", InputConstants.KEY_3, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "4", InputConstants.KEY_4, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "5", InputConstants.KEY_5, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "6", InputConstants.KEY_6, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "7", InputConstants.KEY_7, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "8", InputConstants.KEY_8, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "9", InputConstants.KEY_9, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "0", InputConstants.KEY_0, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		
		addKey(keys, "-", InputConstants.KEY_MINUS, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "=", InputConstants.KEY_EQUALS, x, y, 50F, keyH,
			VisualKeyCategory.MAIN);
		x += 50F + gap;
		addKey(keys, "Backspace", InputConstants.KEY_BACKSPACE, x, y, 96F, keyH,
			VisualKeyCategory.MAIN);
		
		y += 48F;
		x = 24F;
		addKey(keys, "Tab", InputConstants.KEY_TAB, x, y, 68F, keyH,
			VisualKeyCategory.MAIN);
		x += 68F + gap;
		int[] qRow = {InputConstants.KEY_Q, InputConstants.KEY_W,
			InputConstants.KEY_E, InputConstants.KEY_R, InputConstants.KEY_T,
			InputConstants.KEY_Y, InputConstants.KEY_U, InputConstants.KEY_I,
			InputConstants.KEY_O, InputConstants.KEY_P};
		String[] qLabels = {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"};
		for(int key : qRow)
		{
			addKey(keys, qLabels[0], key, x, y, 48F, keyH,
				VisualKeyCategory.MAIN);
			qLabels = shiftLeft(qLabels);
			x += 48F + gap;
		}
		addKey(keys, "[", InputConstants.KEY_LBRACKET, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "]", InputConstants.KEY_RBRACKET, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "\\", InputConstants.KEY_BACKSLASH, x, y, 94F, keyH,
			VisualKeyCategory.MAIN);
		
		y += 48F;
		x = 24F;
		addKey(keys, "Caps", InputConstants.KEY_CAPSLOCK, x, y, 82F, keyH,
			VisualKeyCategory.MAIN);
		x += 82F + gap;
		int[] aRow = {InputConstants.KEY_A, InputConstants.KEY_S,
			InputConstants.KEY_D, InputConstants.KEY_F, InputConstants.KEY_G,
			InputConstants.KEY_H, InputConstants.KEY_J, InputConstants.KEY_K,
			InputConstants.KEY_L};
		String[] aLabels = {"A", "S", "D", "F", "G", "H", "J", "K", "L"};
		for(int key : aRow)
		{
			addKey(keys, aLabels[0], key, x, y, 48F, keyH,
				VisualKeyCategory.MAIN);
			aLabels = shiftLeft(aLabels);
			x += 48F + gap;
		}
		addKey(keys, ";", InputConstants.KEY_SEMICOLON, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "'", InputConstants.KEY_APOSTROPHE, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "Enter", InputConstants.KEY_RETURN, x, y, 134F, keyH,
			VisualKeyCategory.MAIN);
		
		y += 48F;
		x = 24F;
		addKey(keys, "Left Shift", InputConstants.KEY_LSHIFT, x, y, 110F, keyH,
			VisualKeyCategory.MAIN);
		x += 110F + gap;
		int[] zRow = {InputConstants.KEY_Z, InputConstants.KEY_X,
			InputConstants.KEY_C, InputConstants.KEY_V, InputConstants.KEY_B,
			InputConstants.KEY_N, InputConstants.KEY_M};
		String[] zLabels = {"Z", "X", "C", "V", "B", "N", "M"};
		for(int key : zRow)
		{
			addKey(keys, zLabels[0], key, x, y, 48F, keyH,
				VisualKeyCategory.MAIN);
			zLabels = shiftLeft(zLabels);
			x += 48F + gap;
		}
		addKey(keys, ",", InputConstants.KEY_COMMA, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, ".", InputConstants.KEY_PERIOD, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "/", InputConstants.KEY_SLASH, x, y, 48F, keyH,
			VisualKeyCategory.MAIN);
		x += 48F + gap;
		addKey(keys, "Right Shift", InputConstants.KEY_RSHIFT, x, y, 160F, keyH,
			VisualKeyCategory.MAIN);
		
		y += 48F;
		x = 24F;
		addKey(keys, "Left Ctrl", InputConstants.KEY_LCONTROL, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Left Win", InputConstants.KEY_LGUI, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Left Alt", InputConstants.KEY_LALT, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Space", InputConstants.KEY_SPACE, x, y, 332F, keyH,
			VisualKeyCategory.MAIN);
		x += 332F + gap;
		addKey(keys, "Right Alt", InputConstants.KEY_RALT, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Right Win", InputConstants.KEY_RGUI, x, y, 64F, keyH,
			VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Menu", SDLScancode.SDL_SCANCODE_APPLICATION, x, y, 64F,
			keyH, VisualKeyCategory.MAIN);
		x += 64F + gap;
		addKey(keys, "Right Ctrl", InputConstants.KEY_RCONTROL, x, y, 58F, keyH,
			VisualKeyCategory.MAIN);
	}
	
	public static void addNavigationCluster(List<VisualKey> keys, float x,
		float y)
	{
		float keyH = 42F;
		float gap = 6F;
		
		addKey(keys, "Ins", InputConstants.KEY_INSERT, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
		x += 70F + gap;
		addKey(keys, "Home", InputConstants.KEY_HOME, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
		x += 70F + gap;
		addKey(keys, "PgUp", InputConstants.KEY_PAGEUP, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
		
		y += 48F;
		x -= (70F + gap) * 2F;
		addKey(keys, "Del", InputConstants.KEY_DELETE, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
		x += 70F + gap;
		addKey(keys, "End", InputConstants.KEY_END, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
		x += 70F + gap;
		addKey(keys, "PgDn", InputConstants.KEY_PAGEDOWN, x, y, 70F, keyH,
			VisualKeyCategory.NAVIGATION);
	}
	
	public static void addArrowCluster(List<VisualKey> keys, float x, float y)
	{
		float keyH = 42F;
		float keyW = 48F;
		float gap = 6F;
		
		addKey(keys, "Up", InputConstants.KEY_UP, x + keyW + gap, y, keyW, keyH,
			VisualKeyCategory.ARROW);
		addKey(keys, "Left", InputConstants.KEY_LEFT, x, y + keyH + gap, keyW,
			keyH, VisualKeyCategory.ARROW);
		addKey(keys, "Down", InputConstants.KEY_DOWN, x + keyW + gap,
			y + keyH + gap, keyW, keyH, VisualKeyCategory.ARROW);
		addKey(keys, "Right", InputConstants.KEY_RIGHT, x + (keyW + gap) * 2F,
			y + keyH + gap, keyW, keyH, VisualKeyCategory.ARROW);
	}
	
	public static void addNumpad(List<VisualKey> keys, float x, float y)
	{
		float keyH = 42F;
		float keyW = 48F;
		float gap = 6F;
		
		addKey(keys, "Num", InputConstants.KEY_NUMLOCK, x, y, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "/", SDLScancode.SDL_SCANCODE_KP_DIVIDE, x + keyW + gap, y,
			keyW, keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "*", SDLScancode.SDL_SCANCODE_KP_MULTIPLY,
			x + (keyW + gap) * 2F, y, keyW, keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "-", SDLScancode.SDL_SCANCODE_KP_MINUS,
			x + (keyW + gap) * 3F, y, keyW, keyH, VisualKeyCategory.NUMPAD);
		
		float rowY = y + keyH + gap;
		addKey(keys, "7", InputConstants.KEY_NUMPAD7, x, rowY, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "8", InputConstants.KEY_NUMPAD8, x + keyW + gap, rowY,
			keyW, keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "9", InputConstants.KEY_NUMPAD9, x + (keyW + gap) * 2F,
			rowY, keyW, keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "+", SDLScancode.SDL_SCANCODE_KP_PLUS,
			x + (keyW + gap) * 3F, rowY, keyW, keyH * 2F + gap,
			VisualKeyCategory.NUMPAD);
		
		rowY += keyH + gap;
		addKey(keys, "4", InputConstants.KEY_NUMPAD4, x, rowY, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "5", InputConstants.KEY_NUMPAD5, x + keyW + gap, rowY,
			keyW, keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "6", InputConstants.KEY_NUMPAD6, x + (keyW + gap) * 2F,
			rowY, keyW, keyH, VisualKeyCategory.NUMPAD);
		
		rowY += keyH + gap;
		addKey(keys, "1", InputConstants.KEY_NUMPAD1, x, rowY, keyW, keyH,
			VisualKeyCategory.NUMPAD);
		addKey(keys, "2", InputConstants.KEY_NUMPAD2, x + keyW + gap, rowY,
			keyW, keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "3", InputConstants.KEY_NUMPAD3, x + (keyW + gap) * 2F,
			rowY, keyW, keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, "Enter", InputConstants.KEY_NUMPADENTER,
			x + (keyW + gap) * 3F, rowY, keyW, keyH * 2F + gap,
			VisualKeyCategory.NUMPAD);
		
		rowY += keyH + gap;
		addKey(keys, "0", InputConstants.KEY_NUMPAD0, x, rowY, keyW * 2F + gap,
			keyH, VisualKeyCategory.NUMPAD);
		addKey(keys, ".", InputConstants.KEY_NUMPADCOMMA, x + (keyW + gap) * 2F,
			rowY, keyW, keyH, VisualKeyCategory.NUMPAD);
	}
	
	public static void addMouseButtons(List<VisualKey> keys, float x, float y)
	{
		float keyH = 28F;
		float keyW = 50F;
		float gap = 6F;
		
		addMouseKey(keys, "M1", InputConstants.MOUSE_BUTTON_LEFT, x, y, keyW,
			keyH);
		addMouseKey(keys, "M2", InputConstants.MOUSE_BUTTON_RIGHT,
			x + keyW + gap, y, keyW, keyH);
		addMouseKey(keys, "M3", InputConstants.MOUSE_BUTTON_MIDDLE,
			x + (keyW + gap) * 2F, y, keyW, keyH);
		addMouseKey(keys, "M4", InputConstants.MOUSE_BUTTON_4,
			x + (keyW + gap) * 3F, y, keyW, keyH);
		addMouseKey(keys, "M5", InputConstants.MOUSE_BUTTON_5,
			x + (keyW + gap) * 4F, y, keyW, keyH);
	}
	
	private static void addKey(List<VisualKey> keys, String label, int glfwKey,
		float x, float y, float width, float height, VisualKeyCategory category)
	{
		keys.add(new VisualKey(label,
			InputConstants.Type.KEYBOARD.getOrCreate(glfwKey), x, y, width,
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
