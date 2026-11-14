/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.wurstclient.clickgui.ClickGui;

public final class ClickGuiScreen extends Screen
{
	private final ClickGui gui;
	
	public ClickGuiScreen(ClickGui gui)
	{
		super(Component.literal(""));
		this.gui = gui;
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent extractor, boolean doubleClick)
	{
		return gui.handleMouseClick(extractor);
	}
	
	@Override
	public void mouseMoved(double mouseX, double mouseY)
	{
		gui.handleScaledMouseMove(mouseX, mouseY);
		super.mouseMoved(mouseX, mouseY);
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent extractor)
	{
		gui.handleMouseRelease(extractor.x(), extractor.y(),
			extractor.button());
		return super.mouseReleased(extractor);
	}
	
	@Override
	public boolean mouseDragged(MouseButtonEvent extractor, double deltaX,
		double deltaY)
	{
		if(gui.handleMouseDrag(extractor))
			return true;
		
		return super.mouseDragged(extractor, deltaX, deltaY);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount)
	{
		gui.handleMouseScroll(mouseX, mouseY, verticalAmount);
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount,
			verticalAmount);
	}
	
	@Override
	public boolean keyPressed(KeyEvent extractor)
	{
		if(gui.handleKeyPressed(extractor))
			return true;
		
		return super.keyPressed(extractor);
	}
	
	@Override
	public boolean charTyped(CharacterEvent event)
	{
		if(gui.handleCharTyped(event))
			return true;
		
		return super.charTyped(event);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX,
		int mouseY, float partialTicks)
	{
		for(Renderable drawable : renderables)
			drawable.extractRenderState(extractor, mouseX, mouseY,
				partialTicks);
		
		gui.extractRenderState(extractor, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX,
		int mouseY, float deltaTicks)
	{
		// Don't blur
	}
	
	@Override
	public void removed()
	{
		// Persist scroll offsets and window states before rebuilding the UI.
		gui.persistWindowLayout();
		gui.clearKeyboardInput();
		gui.init();
		super.removed();
	}
}
