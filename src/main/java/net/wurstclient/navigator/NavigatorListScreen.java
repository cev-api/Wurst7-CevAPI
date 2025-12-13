/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.navigator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Mth;
import net.wurstclient.options.EnterProfileNameScreen;
import org.lwjgl.glfw.GLFW;

public final class NavigatorListScreen extends Screen
{
	private static final int ROW_HEIGHT = 20;
	private static final int CONNECT_WIDTH = 70;
	private static final int COMMAND_WIDTH = 80;
	private static final int BUTTON_HEIGHT = 16;
	private static final int DARK_GRAY = 0xFF555555;
	
	private final List<String> entries;
	private final Consumer<String> connectCallback;
	private final BiConsumer<String, String> commandCallback;
	private final Screen returnScreen;
	private final Runnable onCancel;
	
	private Button cancelButton;
	private double scrollOffset;
	private int listLeft;
	private int listRight;
	private int listTop;
	private int listBottom;
	
	public NavigatorListScreen(Component title, List<String> entries,
		Consumer<String> connectCallback,
		BiConsumer<String, String> commandCallback, Screen returnScreen,
		Runnable onCancel)
	{
		super(title);
		this.entries = new ArrayList<>(entries != null ? entries : List.of());
		this.connectCallback = connectCallback;
		this.commandCallback = commandCallback;
		this.returnScreen = returnScreen;
		this.onCancel = onCancel;
	}
	
	@Override
	public void init()
	{
		listLeft = width / 2 - 150;
		listRight = width / 2 + 150;
		listTop = 35;
		listBottom = height - 60;
		scrollOffset = 0;
		
		cancelButton = addRenderableWidget(Button
			.builder(Component.literal("Cancel"), button -> cancelSelection())
			.bounds(width / 2 - 50, height - 30, 100, 20).build());
	}
	
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers)
	{
		if(keyCode == GLFW.GLFW_KEY_ESCAPE)
		{
			cancelSelection();
			return true;
		}
		
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount)
	{
		int maxScroll =
			Math.max(0, entries.size() * ROW_HEIGHT - (listBottom - listTop));
		scrollOffset = Mth.clamp(scrollOffset - verticalAmount * ROW_HEIGHT / 2,
			0, maxScroll);
		return true;
	}
	
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
		{
			if(mouseX >= listLeft && mouseX <= listRight && mouseY >= listTop
				&& mouseY <= listBottom)
			{
				double relativeY = mouseY - listTop + scrollOffset;
				int index = (int)(relativeY / ROW_HEIGHT);
				if(index >= 0 && index < entries.size())
				{
					int rowY =
						(int)(listTop + index * ROW_HEIGHT - scrollOffset);
					int connectX1 = listRight - CONNECT_WIDTH - 6;
					int connectY1 = rowY + 2;
					int connectX2 = connectX1 + CONNECT_WIDTH;
					int connectY2 = connectY1 + BUTTON_HEIGHT;
					int commandX1 = connectX1 - COMMAND_WIDTH - 4;
					int commandY1 = connectY1;
					int commandX2 = commandX1 + COMMAND_WIDTH;
					int commandY2 = connectY2;
					if(mouseX >= connectX1 && mouseX < connectX2
						&& mouseY >= connectY1 && mouseY < connectY2)
					{
						selectEntry(entries.get(index));
						return true;
					}
					if(mouseX >= commandX1 && mouseX < commandX2
						&& mouseY >= commandY1 && mouseY < commandY2)
					{
						promptCommand(entries.get(index));
						return true;
					}
				}
			}
		}
		
		return super.mouseClicked(mouseX, mouseY, button);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		context.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);
		context.drawCenteredString(minecraft.font, title.getString(), width / 2,
			15, CommonColors.WHITE);
		
		renderList(context, mouseX, mouseY);
		
		for(Renderable renderable : renderables)
			renderable.render(context, mouseX, mouseY, partialTicks);
	}
	
	private void renderList(GuiGraphics context, int mouseX, int mouseY)
	{
		context.fill(listLeft - 2, listTop - 2, listRight + 2, listBottom + 2,
			0xFF000000);
		context.fill(listLeft, listTop, listRight, listBottom, 0xFF000000);
		
		int startIndex =
			Math.max(0, (int)Math.floor(scrollOffset / ROW_HEIGHT));
		int maxVisible =
			(int)Math.ceil((listBottom - listTop) / (double)ROW_HEIGHT) + 1;
		
		for(int i = startIndex; i < entries.size()
			&& i < startIndex + maxVisible; i++)
		{
			int rowY = (int)(listTop + i * ROW_HEIGHT - scrollOffset);
			if(rowY + ROW_HEIGHT < listTop || rowY > listBottom)
				continue;
			
			String name = entries.get(i);
			context.drawString(minecraft.font, name, listLeft + 6, rowY + 4,
				CommonColors.WHITE);
			
			int connectX1 = listRight - CONNECT_WIDTH - 6;
			int connectY1 = rowY + 2;
			int connectX2 = connectX1 + CONNECT_WIDTH;
			int connectY2 = connectY1 + BUTTON_HEIGHT;
			boolean connectHover = mouseX >= connectX1 && mouseY >= connectY1
				&& mouseX < connectX2 && mouseY < connectY2;
			int connectColor =
				connectHover ? CommonColors.LIGHT_GRAY : DARK_GRAY;
			context.fill(connectX1, connectY1, connectX2, connectY2,
				connectColor);
			context.drawCenteredString(minecraft.font, "Connect",
				connectX1 + CONNECT_WIDTH / 2, connectY1 + 4,
				CommonColors.WHITE);
			
			int commandX1 = connectX1 - COMMAND_WIDTH - 4;
			int commandY1 = connectY1;
			int commandX2 = commandX1 + COMMAND_WIDTH;
			int commandY2 = connectY2;
			boolean commandHover = mouseX >= commandX1 && mouseY >= commandY1
				&& mouseX < commandX2 && mouseY < commandY2;
			int commandColor =
				commandHover ? CommonColors.LIGHT_GRAY : DARK_GRAY;
			context.fill(commandX1, commandY1, commandX2, commandY2,
				commandColor);
			context.drawCenteredString(minecraft.font, "Command",
				commandX1 + COMMAND_WIDTH / 2, commandY1 + 4,
				CommonColors.WHITE);
		}
	}
	
	private void selectEntry(String entry)
	{
		if(entry == null || entry.isEmpty())
			return;
		
		if(connectCallback != null)
			connectCallback.accept(entry);
		
		close();
	}
	
	private void promptCommand(String entry)
	{
		if(commandCallback == null || entry == null || entry.isEmpty())
			return;
		
		minecraft.setScreen(new EnterProfileNameScreen(this, input -> {
			if(input == null)
				return;
			
			String trimmed = input.trim();
			if(trimmed.isEmpty())
				return;
			
			commandCallback.accept(entry, trimmed);
		}, Component.literal("Enter reconnect command"),
			value -> value != null && !value.trim().isEmpty()));
	}
	
	private void cancelSelection()
	{
		if(onCancel != null)
			onCancel.run();
		close();
	}
	
	private void close()
	{
		minecraft.setScreen(returnScreen);
	}
}
