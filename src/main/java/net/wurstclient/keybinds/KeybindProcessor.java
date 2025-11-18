/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.keybinds;

import org.lwjgl.glfw.GLFW;
import java.util.Locale;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.command.CmdProcessor;
import net.wurstclient.events.KeyPressListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hack.HackList;
import net.wurstclient.util.ChatUtils;

public final class KeybindProcessor implements KeyPressListener
{
	private final HackList hax;
	private final KeybindList keybinds;
	private final CmdProcessor cmdProcessor;
	
	public KeybindProcessor(HackList hax, KeybindList keybinds,
		CmdProcessor cmdProcessor)
	{
		this.hax = hax;
		this.keybinds = keybinds;
		this.cmdProcessor = cmdProcessor;
	}
	
	@Override
	public void onKeyPress(KeyPressEvent event)
	{
		if(event.getAction() != GLFW.GLFW_PRESS)
			return;
		
		if(InputConstants.isKeyDown(WurstClient.MC.getWindow().getWindow(),
			GLFW.GLFW_KEY_F3))
			return;
		
		Screen screen = WurstClient.MC.screen;
		// Allow processing when no screen is open, when the Click GUI is open,
		// or when Waypoints or ItemHandler screens are open so their keybinds
		// can toggle/close them with the same key.
		if(screen != null && !(screen instanceof ClickGuiScreen)
			&& !(screen instanceof net.wurstclient.clickgui.screens.WaypointsScreen)
			&& !(screen instanceof net.wurstclient.hacks.itemhandler.ItemHandlerScreen))
			return;
			
		// if ClickGuiScreen is open and user typed a printable key, open
		// navigator and pass the initial character
		if(screen instanceof ClickGuiScreen)
		{
			String ch =
				mapPrintableChar(event.getKeyCode(), event.getModifiers());
			if(ch != null)
			{
				// open navigator without prepopulating the search to avoid
				// the first character being entered twice (widget will receive
				// it)
				WurstClient.MC.setScreen(
					new net.wurstclient.navigator.NavigatorMainScreen());
				return;
			}
		}
		
		String keyName = getKeyName(event);
		
		String cmds = keybinds.getCommands(keyName);
		if(cmds == null)
			return;
		
		processCmds(cmds);
	}
	
	private String mapPrintableChar(int keyCode, int modifiers)
	{
		// letters a-z
		if(keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z)
		{
			char c = (char)('a' + (keyCode - GLFW.GLFW_KEY_A));
			return String.valueOf(c);
		}
		// numbers 0-9 (top row)
		if(keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9)
		{
			char c = (char)('0' + (keyCode - GLFW.GLFW_KEY_0));
			return String.valueOf(c);
		}
		if(keyCode == GLFW.GLFW_KEY_SPACE)
			return " ";
		// add basic punctuation if desired
		return null;
	}
	
	private String getKeyName(KeyPressEvent event)
	{
		int keyCode = event.getKeyCode();
		int scanCode = event.getScanCode();
		return InputConstants.getKey(keyCode, scanCode).getName();
	}
	
	private void processCmds(String cmds)
	{
		cmds = cmds.replace(";", "\u00a7").replace("\u00a7\u00a7", ";");
		
		for(String cmd : cmds.split("\u00a7"))
			processCmd(cmd.trim());
	}
	
	private void processCmd(String cmd)
	{
		String trimmed = cmd.trim();
		// Special-case: toggle Waypoints manager when bound to ".waypoints"
		if(trimmed.equalsIgnoreCase(".waypoints"))
		{
			// If Waypoints screen is open, close it; otherwise open manager
			if(net.minecraft.client.Minecraft
				.getInstance().screen instanceof net.wurstclient.clickgui.screens.WaypointsScreen)
			{
				net.minecraft.client.Minecraft.getInstance().setScreen(null);
				return;
			}
			// open via hack utility
			WurstClient.INSTANCE.getHax().waypointsHack.openManager();
			return;
		}
		
		if(cmd.startsWith("."))
			cmdProcessor.process(cmd.substring(1));
		else if(cmd.contains(" "))
		{
			// special-case: open/close ItemHandler GUI when key bound to
			// "itemhandler gui"
			String lower = cmd.toLowerCase(Locale.ROOT).trim();
			if(lower.equals("itemhandler gui"))
			{
				net.minecraft.client.gui.screens.Screen s =
					net.minecraft.client.Minecraft.getInstance().screen;
				if(s instanceof net.wurstclient.hacks.itemhandler.ItemHandlerScreen)
				{
					net.minecraft.client.Minecraft.getInstance()
						.setScreen(null);
					return;
				}
				// Open ItemHandler screen via hack utility when not open
				WurstClient.INSTANCE.getHax().itemHandlerHack.openScreen();
				return;
			}
			cmdProcessor.process(cmd);
		}else
		{
			Hack hack = hax.getHackByName(cmd);
			
			if(hack == null)
			{
				cmdProcessor.process(cmd);
				return;
			}
			
			if(!hack.isEnabled() && hax.tooManyHaxHack.isEnabled()
				&& hax.tooManyHaxHack.isBlocked(hack))
			{
				ChatUtils.error(hack.getName() + " is blocked by TooManyHax.");
				return;
			}
			
			hack.setEnabled(!hack.isEnabled());
		}
	}
}
