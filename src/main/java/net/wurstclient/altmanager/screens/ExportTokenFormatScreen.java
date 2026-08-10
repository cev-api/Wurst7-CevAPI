/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager.screens;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

public final class ExportTokenFormatScreen extends Screen
{
	private final Screen prevScreen;
	private final Consumer<Format> onSelect;
	
	public ExportTokenFormatScreen(Screen prevScreen, Consumer<Format> onSelect)
	{
		super(Component.literal("Export Token Accounts"));
		this.prevScreen = prevScreen;
		this.onSelect = onSelect;
	}
	
	@Override
	protected void init()
	{
		int x = width / 2 - 100;
		int y = height / 2 - 4;
		
		addRenderableWidget(Button
			.builder(Component.literal("Refresh Tokens"),
				b -> select(Format.REFRESH_TOKENS))
			.bounds(x, y, 200, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Access Tokens (Temporary)"),
				b -> select(Format.ACCESS_TOKENS))
			.bounds(x, y + 24, 200, 20).build());
		addRenderableWidget(
			Button.builder(Component.literal("Cancel"), b -> onClose())
				.bounds(x, y + 48, 200, 20).build());
	}
	
	private void select(Format format)
	{
		minecraft.setScreen(prevScreen);
		onSelect.accept(format);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX,
		int mouseY, float partialTicks)
	{
		context.drawCenteredString(font, "Export Token Accounts", width / 2,
			height / 2 - 54, CommonColors.WHITE);
		context.drawCenteredString(font,
			"Refresh tokens retain the Microsoft client ID that issued them.",
			width / 2, height / 2 - 34, CommonColors.LIGHT_GRAY);
		context.drawCenteredString(font,
			"Access tokens work without a client ID but expire quickly.",
			width / 2, height / 2 - 22, CommonColors.LIGHT_GRAY);
		
		for(Renderable drawable : renderables)
			drawable.render(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public void onClose()
	{
		minecraft.setScreen(prevScreen);
	}
	
	public enum Format
	{
		REFRESH_TOKENS("refresh-tokens"),
		ACCESS_TOKENS("access-tokens");
		
		private final String fileChooserArgument;
		
		private Format(String fileChooserArgument)
		{
			this.fileChooserArgument = fileChooserArgument;
		}
		
		public String getFileChooserArgument()
		{
			return fileChooserArgument;
		}
	}
}
