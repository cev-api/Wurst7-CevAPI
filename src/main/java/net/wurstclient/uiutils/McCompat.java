/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;

public final class McCompat
{
	private McCompat()
	{}
	
	public static Screen getScreen(Minecraft mc)
	{
		if(mc == null)
			return null;
		
		Object gui = mc.gui;
		if(gui != null)
		{
			try
			{
				Method method = gui.getClass().getMethod("screen");
				Object result = method.invoke(gui);
				if(result instanceof Screen screen)
					return screen;
			}catch(ReflectiveOperationException ignored)
			{}
		}
		
		try
		{
			Field field = mc.getClass().getField("screen");
			Object result = field.get(mc);
			if(result instanceof Screen screen)
				return screen;
		}catch(ReflectiveOperationException ignored)
		{}
		
		return null;
	}
	
	public static void setScreen(Minecraft mc, Screen screen)
	{
		if(mc == null)
			return;
		
		try
		{
			Method method = mc.getClass().getMethod("setScreen", Screen.class);
			method.invoke(mc, screen);
			return;
		}catch(ReflectiveOperationException ignored)
		{}
		
		try
		{
			Method method =
				mc.getClass().getMethod("setScreenAndShow", Screen.class);
			method.invoke(mc, screen);
			return;
		}catch(ReflectiveOperationException ignored)
		{}
		
		Object gui = mc.gui;
		if(gui == null)
			return;
		
		try
		{
			Method method = gui.getClass().getMethod("setScreen", Screen.class);
			method.invoke(gui, screen);
		}catch(ReflectiveOperationException ignored)
		{}
	}
	
	public static void addRecentChat(Minecraft mc, String message)
	{
		if(mc == null || message == null)
			return;
		
		ChatComponent chat = getChatComponent(mc);
		if(chat != null)
			chat.addRecentChat(message);
	}
	
	/** Returns the shared sent-message history used by vanilla chat. */
	public static List<String> recentChat(Minecraft mc)
	{
		ChatComponent chat = mc == null ? null : getChatComponent(mc);
		return chat == null ? List.of() : List.copyOf(chat.getRecentChat());
	}
	
	/** -1 for Up, +1 for Down, or zero for other keys. */
	public static int chatHistoryDirection(KeyEvent event)
	{
		if(event == null)
			return 0;
		boolean sdlInput = usesSdlInput();
		if(event.key() == (sdlInput ? 82 : 265))
			return -1;
		if(event.key() == (sdlInput ? 81 : 264))
			return 1;
		return 0;
	}
	
	public static boolean isLeftMouseButton(int button)
	{
		return button == (usesSdlInput() ? 1 : 0);
	}
	
	public static boolean isConfirmationKey(KeyEvent event)
	{
		if(event == null)
			return false;
		return usesSdlInput() ? event.key() == 40 || event.key() == 88
			: event.key() == 257 || event.key() == 335;
	}
	
	private static boolean usesSdlInput()
	{
		String version = SharedConstants.getCurrentVersion().id();
		String[] parts = version.split("\\.");
		try
		{
			int major = Integer.parseInt(parts[0]);
			int minor = parts.length > 1
				? Integer.parseInt(parts[1].replaceAll("[^0-9].*$", "")) : 0;
			return major > 26 || major == 26 && minor >= 3;
		}catch(NumberFormatException e)
		{
			return version.startsWith("26.3");
		}
	}
	
	private static ChatComponent getChatComponent(Minecraft mc)
	{
		Object gui = mc.gui;
		if(gui == null)
			return null;
		
		try
		{
			Method method = gui.getClass().getMethod("getChat");
			Object result = method.invoke(gui);
			if(result instanceof ChatComponent chat)
				return chat;
		}catch(ReflectiveOperationException ignored)
		{}
		
		try
		{
			Field hudField = gui.getClass().getField("hud");
			Object hud = hudField.get(gui);
			if(hud == null)
				return null;
			Method method = hud.getClass().getMethod("getChat");
			Object result = method.invoke(hud);
			if(result instanceof ChatComponent chat)
				return chat;
		}catch(ReflectiveOperationException ignored)
		{}
		
		return null;
	}
}
