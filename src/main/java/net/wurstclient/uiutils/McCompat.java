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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.ChatComponent;

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
