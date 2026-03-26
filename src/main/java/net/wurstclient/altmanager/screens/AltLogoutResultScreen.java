/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager.screens;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class AltLogoutResultScreen extends Screen
{
	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	
	private final Screen nextScreen;
	private final boolean restored;
	private final String accountName;
	
	public AltLogoutResultScreen(Screen nextScreen, boolean restored,
		String accountName)
	{
		super(Component.literal("Logout"));
		this.nextScreen = nextScreen;
		this.restored = restored;
		this.accountName = accountName == null || accountName.isBlank()
			? "Unknown" : accountName;
	}
	
	@Override
	protected void init()
	{
		addRenderableWidget(Button
			.builder(Component.literal("Continue"),
				b -> minecraft.setScreen(nextScreen))
			.bounds(width / 2 - 100, height / 2 + 48, 200, 20).build());
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		if(restored)
		{
			context.centeredText(font, "Logged out of alt session", width / 2,
				height / 2 - 34, GREEN);
			context.centeredText(font, "Current account: " + accountName,
				width / 2, height / 2 - 20, GREEN);
		}else
		{
			context.centeredText(font, "Logout verification failed", width / 2,
				height / 2 - 34, RED);
			context.centeredText(font, "Current account: " + accountName,
				width / 2, height / 2 - 20, RED);
		}
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ENTER
			|| context.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			onClose();
			return true;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_4)
		{
			onClose();
			return true;
		}
		
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public void onClose()
	{
		minecraft.setScreen(nextScreen);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
}
