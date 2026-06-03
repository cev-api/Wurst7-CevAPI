/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.settings.FriendListSetting;

public final class EditFriendListScreen extends Screen
{
	private final Screen prevScreen;
	private final FriendListSetting friendList;
	
	private ListGui listGui;
	private EditBox friendNameField;
	private Button addButton;
	private Button removeButton;
	private Button doneButton;
	
	public EditFriendListScreen(Screen prevScreen, FriendListSetting friendList)
	{
		super(Component.literal(""));
		this.prevScreen = prevScreen;
		this.friendList = friendList;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(minecraft, this, friendList.getFriendNames());
		addWidget(listGui);
		
		int rowY = height - 56;
		int gap = 8;
		int fieldWidth = 180;
		int addWidth = 80;
		int removeWidth = 140;
		int totalWidth = fieldWidth + addWidth + removeWidth + gap * 2;
		int rowStart = width / 2 - totalWidth / 2;
		
		friendNameField = new EditBox(minecraft.font, rowStart, rowY,
			fieldWidth, 20, Component.literal(""));
		friendNameField.setMaxLength(64);
		addRenderableWidget(friendNameField);
		
		int addX = rowStart + fieldWidth + gap;
		int removeX = addX + addWidth + gap;
		
		addRenderableWidget(
			addButton = Button.builder(Component.literal("Add"), b -> {
				String raw = friendNameField.getValue();
				if(raw == null)
					return;
				
				String trimmed = raw.trim();
				if(trimmed.isEmpty())
					return;
				
				var prevState = listGui.captureState();
				friendList.addName(trimmed);
				friendNameField.setValue("");
				refreshList(prevState, List.of(trimmed),
					prevState.scrollAmount());
			}).bounds(addX, rowY, addWidth, 20).build());
		
		addRenderableWidget(removeButton =
			Button.builder(Component.literal("Remove Selected"), b -> {
				List<String> selected = listGui.getSelectedFriendNames();
				if(selected.isEmpty())
					return;
				
				var prevState = listGui.captureState();
				friendList.removeNames(selected);
				refreshList(prevState, Collections.emptyList(),
					prevState.scrollAmount());
			}).bounds(removeX, rowY, removeWidth, 20).build());
		
		listGui.setSelectionListener(this::updateButtons);
		updateButtons();
		
		addRenderableWidget(Button.builder(Component.literal("Clear List"),
			b -> minecraft.setScreen(new ConfirmScreen(confirm -> {
				if(confirm)
				{
					friendList.clear();
					refreshList(null, Collections.emptyList(), 0);
				}
				minecraft.setScreen(EditFriendListScreen.this);
			}, Component.literal("Clear Friends"),
				Component.literal("Remove every friend?"))))
			.bounds(width / 2 - 75, 8, 150, 20).build());
		
		addRenderableWidget(doneButton = Button
			.builder(Component.literal("Done"),
				b -> minecraft.setScreen(prevScreen))
			.bounds(width / 2 - 100, height - 28, 200, 20).build());
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		listGui.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		context.centeredText(
			minecraft.font, friendList.getName() + " ("
				+ friendList.getFriendNames().size() + ")",
			width / 2, 12, CommonColors.WHITE);
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		friendNameField.mouseClicked(context, doubleClick);
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		switch(context.key())
		{
			case GLFW.GLFW_KEY_ENTER:
			if(addButton.active)
				addButton.onPress(context);
			break;
			
			case GLFW.GLFW_KEY_DELETE:
			if(!friendNameField.isFocused())
				removeButton.onPress(context);
			break;
			
			case GLFW.GLFW_KEY_ESCAPE:
			doneButton.onPress(context);
			break;
			
			default:
			break;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public void tick()
	{
		String value = friendNameField.getValue();
		addButton.active = value != null && !value.trim().isEmpty();
	}
	
	private void updateButtons()
	{
		if(removeButton != null)
			removeButton.active = listGui.hasSelection();
	}
	
	private void refreshList(
		MultiSelectEntryListWidget.SelectionState previousState,
		Collection<String> preferredKeys, double scrollAmount)
	{
		listGui.reloadPreservingState(friendList.getFriendNames(),
			previousState, preferredKeys, scrollAmount);
		updateButtons();
	}
	
	private final class Entry
		extends MultiSelectEntryListWidget.Entry<EditFriendListScreen.Entry>
	{
		private final String friendName;
		
		private Entry(ListGui parent, String friendName)
		{
			super(parent);
			this.friendName = Objects.requireNonNull(friendName);
		}
		
		@Override
		public Component getNarration()
		{
			return Component.translatable("narrator.select",
				"Friend " + friendName);
		}
		
		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX,
			int mouseY, boolean hovered, float tickDelta)
		{
			int x = getContentX();
			int y = getContentY();
			
			context.text(minecraft.font, friendName, x + 4, y + 4,
				CommonColors.WHITE, false);
			context.text(minecraft.font, "Click or shift-click to select",
				x + 4, y + 16, CommonColors.LIGHT_GRAY, false);
		}
		
		@Override
		public String selectionKey()
		{
			return friendName;
		}
	}
	
	private final class ListGui
		extends MultiSelectEntryListWidget<EditFriendListScreen.Entry>
	{
		private ListGui(Minecraft minecraft, EditFriendListScreen screen,
			List<String> list)
		{
			super(minecraft, screen.width, screen.height - 96, 36, 30);
			reload(list);
			ensureSelection();
		}
		
		private void reload(List<String> list)
		{
			clearEntries();
			list.stream()
				.map(name -> new EditFriendListScreen.Entry(this, name))
				.forEach(this::addEntry);
		}
		
		private void reloadPreservingState(List<String> list,
			SelectionState previousState, Collection<String> preferredKeys,
			double scrollAmount)
		{
			reload(list);
			
			if(preferredKeys != null && !preferredKeys.isEmpty())
			{
				setSelection(preferredKeys, scrollAmount);
				return;
			}
			
			if(previousState != null)
			{
				restoreState(new SelectionState(
					new ArrayList<>(previousState.selectedKeys()),
					previousState.anchorKey(), scrollAmount,
					previousState.anchorIndex()));
				return;
			}
			
			ensureSelection();
		}
		
		private List<String> getSelectedFriendNames()
		{
			return getSelectedKeys();
		}
		
		@Override
		protected String getSelectionKey(EditFriendListScreen.Entry entry)
		{
			return entry.friendName;
		}
	}
}
