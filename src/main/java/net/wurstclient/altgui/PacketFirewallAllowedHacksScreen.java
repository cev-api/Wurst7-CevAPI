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
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.other_features.PacketFirewallOtf;

/** The AltGUI counterpart to PacketFirewall's ClickGUI hack selector. */
public final class PacketFirewallAllowedHacksScreen extends Screen
{
	private final Screen prevScreen;
	private final PacketFirewallOtf packetFirewall;
	
	private int scroll;
	private int maxScroll;
	
	public PacketFirewallAllowedHacksScreen(Screen prevScreen,
		PacketFirewallOtf packetFirewall)
	{
		super(Component.literal("PacketFirewall Allowed Hacks"));
		this.prevScreen = prevScreen;
		this.packetFirewall = packetFirewall;
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			minecraft.gui.setScreen(prevScreen);
			return true;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount)
	{
		scroll -= (int)(verticalAmount * 18);
		clampScroll();
		return true;
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		if(context.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return super.mouseClicked(context, doubleClick);
		
		double mouseX = context.x();
		double mouseY = context.y();
		int x1 = width / 2 - 180;
		int x2 = width / 2 + 180;
		int y1 = 34;
		int y2 = height - 20;
		if(mouseX < x1 || mouseX > x2 || mouseY < y1 || mouseY > y2)
			return super.mouseClicked(context, doubleClick);
		
		int contentY = y1 + 2 - scroll;
		for(Hack hack : getSortedHacks())
		{
			if(mouseY >= contentY && mouseY <= contentY + 14)
			{
				packetFirewall.toggleAllowedHack(hack);
				return true;
			}
			contentY += 14;
		}
		
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		context.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);
		context.centeredText(minecraft.font, "PacketFirewall - Allowed Hacks",
			width / 2, 12, 0xFFFFFFFF);
		context.centeredText(minecraft.font,
			"Left-click to allow/disallow. [default] entries start allowed. ESC to return.",
			width / 2, 22, 0xFFAAAAAA);
		
		int x1 = width / 2 - 180;
		int x2 = width / 2 + 180;
		int y1 = 34;
		int y2 = height - 20;
		context.fill(x1, y1, x2, y2, 0x80202020);
		context.fill(x1, y1, x2, y1 + 1, 0xFF4A4A4A);
		context.fill(x1, y2 - 1, x2, y2, 0xFF4A4A4A);
		context.fill(x1, y1, x1 + 1, y2, 0xFF4A4A4A);
		context.fill(x2 - 1, y1, x2, y2, 0xFF4A4A4A);
		
		List<Hack> hacks = getSortedHacks();
		int contentY = y1 + 2 - scroll;
		maxScroll = Math.max(0, hacks.size() * 14 + 2 - (y2 - y1 - 2));
		clampScroll();
		
		context.enableScissor(x1 + 1, y1 + 1, x2 - 1, y2 - 1);
		for(Hack hack : hacks)
		{
			int top = contentY;
			int bottom = top + 14;
			if(bottom >= y1 && top <= y2)
			{
				boolean allowed = packetFirewall.isAllowedHack(hack);
				boolean defaultAllowed =
					packetFirewall.isAllowedByDefault(hack);
				boolean hover = mouseX >= x1 + 2 && mouseX <= x2 - 2
					&& mouseY >= top && mouseY <= bottom;
				int bg = allowed ? 0x9022AA22 : 0x90303030;
				if(hover)
					bg = allowed ? 0xB022CC22 : 0xB0505050;
				context.fill(x1 + 2, top, x2 - 2, bottom, bg);
				String suffix = defaultAllowed ? " [default]" : "";
				String state = allowed ? "[X] " : "[ ] ";
				context.text(minecraft.font, state + hack.getName() + suffix,
					x1 + 6, top + 3, 0xFFFFFFFF, false);
			}
			contentY += 14;
		}
		context.disableScissor();
	}
	
	private List<Hack> getSortedHacks()
	{
		ArrayList<Hack> hacks =
			new ArrayList<>(WurstClient.INSTANCE.getHax().getAllHax());
		hacks.sort(Comparator.comparing(h -> h.getName().toLowerCase()));
		return hacks;
	}
	
	private void clampScroll()
	{
		if(scroll < 0)
			scroll = 0;
		if(scroll > maxScroll)
			scroll = maxScroll;
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
}
