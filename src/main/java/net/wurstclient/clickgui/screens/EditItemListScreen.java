/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
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

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.CommonColors;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.util.ItemUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.WurstColors;

public final class EditItemListScreen extends Screen
{
	private final Screen prevScreen;
	private final ItemListSetting itemList;
	
	private ListGui listGui;
	private EditBox itemNameField;
	private Button addKeywordButton;
	private Button addButton;
	private Button removeButton;
	private Button doneButton;
	
	private Item itemToAdd;
	private java.util.List<net.minecraft.world.item.Item> fuzzyMatches =
		java.util.Collections.emptyList();
	
	public EditItemListScreen(Screen prevScreen, ItemListSetting itemList)
	{
		super(Component.literal(""));
		this.prevScreen = prevScreen;
		this.itemList = itemList;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(minecraft, this, itemList.getItemNames());
		addWidget(listGui);
		
		int rowY = height - 56;
		int gap = 8;
		int fieldWidth = 160;
		int keywordWidth = 110;
		int addWidth = 80;
		int removeWidth = 120;
		int totalWidth =
			fieldWidth + keywordWidth + addWidth + removeWidth + gap * 3;
		int rowStart = width / 2 - totalWidth / 2;
		
		itemNameField = new EditBox(minecraft.font, rowStart, rowY, fieldWidth,
			20, Component.literal(""));
		addWidget(itemNameField);
		itemNameField.setMaxLength(256);
		
		int keywordX = rowStart + fieldWidth + gap;
		int addX = keywordX + keywordWidth + gap;
		int removeX = addX + addWidth + gap;
		
		addRenderableWidget(addKeywordButton =
			Button.builder(Component.literal("Add Keyword"), b -> {
				String raw = itemNameField.getValue();
				if(raw != null)
					raw = raw.trim();
				if(raw == null || raw.isEmpty())
					return;
				
				var prevState = listGui.captureState();
				List<String> before = new ArrayList<>(itemList.getItemNames());
				itemList.addRawName(raw);
				List<String> added = new ArrayList<>(itemList.getItemNames());
				added.removeAll(before);
				
				refreshList(prevState, added, prevState.scrollAmount());
			}).bounds(keywordX, rowY, keywordWidth, 20).build());
		
		addRenderableWidget(
			addButton = Button.builder(Component.literal("Add"), b -> {
				var prevState = listGui.captureState();
				List<String> before = new ArrayList<>(itemList.getItemNames());
				
				if(itemToAdd != null)
				{
					itemList.add(itemToAdd);
				}else if(fuzzyMatches != null && !fuzzyMatches.isEmpty())
				{
					for(net.minecraft.world.item.Item it : fuzzyMatches)
						itemList.add(it);
				}else
				{
					String raw = itemNameField.getValue();
					if(raw != null)
						raw = raw.trim();
					if(raw != null && !raw.isEmpty())
						itemList.addRawName(raw);
				}
				
				List<String> added = new ArrayList<>(itemList.getItemNames());
				added.removeAll(before);
				
				refreshList(prevState, added, prevState.scrollAmount());
			}).bounds(addX, rowY, addWidth, 20).build());
		
		addRenderableWidget(removeButton =
			Button.builder(Component.literal("Remove Selected"), b -> {
				List<String> selected = listGui.getSelectedItemNames();
				if(selected.isEmpty())
					return;
				
				var prevState = listGui.captureState();
				for(String key : selected)
				{
					int index = itemList.getItemNames().indexOf(key);
					if(index >= 0)
						itemList.remove(index);
				}
				
				refreshList(prevState, Collections.emptyList(),
					prevState.scrollAmount());
			}).bounds(removeX, rowY, removeWidth, 20).build());
		
		listGui.setSelectionListener(this::updateButtons);
		updateButtons();
		
		addRenderableWidget(
			Button.builder(Component.literal("Reset to Defaults"),
				b -> minecraft.setScreen(new ConfirmScreen(b2 -> {
					if(b2)
						itemList.resetToDefaults();
					minecraft.setScreen(EditItemListScreen.this);
				}, Component.literal("Reset to Defaults"),
					Component.literal("Are you sure?"))))
				.bounds(width - 328, 8, 150, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Clear List"), b -> {
				itemList.clear();
				minecraft.setScreen(EditItemListScreen.this);
			}).bounds(width - 168, 8, 150, 20).build());
		
		addRenderableWidget(doneButton = Button
			.builder(Component.literal("Done"),
				b -> minecraft.setScreen(prevScreen))
			.bounds(width / 2 - 100, height - 28, 200, 20).build());
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		itemNameField.mouseClicked(context, doubleClick);
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
			if(!itemNameField.isFocused())
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
		String nameOrId = itemNameField.getValue();
		String trimmed = nameOrId == null ? "" : nameOrId.trim();
		boolean hasInput = !trimmed.isEmpty();
		itemToAdd = ItemUtils.getItemFromNameOrID(nameOrId);
		// Build fuzzy matches if no exact item found
		if(itemToAdd == null)
		{
			String q = trimmed.toLowerCase(java.util.Locale.ROOT);
			if(q.isEmpty())
			{
				fuzzyMatches = java.util.Collections.emptyList();
			}else
			{
				java.util.ArrayList<net.minecraft.world.item.Item> list =
					new java.util.ArrayList<>();
				for(net.minecraft.resources.ResourceLocation id : net.minecraft.core.registries.BuiltInRegistries.ITEM
					.keySet())
				{
					String s = id.toString().toLowerCase(java.util.Locale.ROOT);
					if(s.contains(q))
						list.add(
							net.minecraft.core.registries.BuiltInRegistries.ITEM
								.getValue(id));
				}
				// Deduplicate and sort by identifier
				java.util.LinkedHashMap<String, net.minecraft.world.item.Item> map =
					new java.util.LinkedHashMap<>();
				for(net.minecraft.world.item.Item it : list)
					map.put(net.minecraft.core.registries.BuiltInRegistries.ITEM
						.getKey(it).toString(), it);
				fuzzyMatches = new java.util.ArrayList<>(map.values());
				fuzzyMatches.sort(java.util.Comparator.comparing(
					it -> net.minecraft.core.registries.BuiltInRegistries.ITEM
						.getKey(it).toString()));
			}
			addButton.active = !fuzzyMatches.isEmpty() || hasInput;
			addButton.setMessage(Component.literal(fuzzyMatches.isEmpty()
				? "Add" : ("Add Matches (" + fuzzyMatches.size() + ")")));
		}else
		{
			fuzzyMatches = java.util.Collections.emptyList();
			addButton.active = true;
			addButton.setMessage(Component.literal("Add"));
		}
		
		addKeywordButton.active = hasInput;
		updateButtons();
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		Matrix3x2fStack matrixStack = context.pose();
		
		listGui.render(context, mouseX, mouseY, partialTicks);
		
		context.drawCenteredString(minecraft.font,
			itemList.getName() + " (" + itemList.getItemNames().size() + ")",
			width / 2, 12, CommonColors.WHITE);
		
		matrixStack.pushMatrix();
		
		itemNameField.render(context, mouseX, mouseY, partialTicks);
		
		for(Renderable drawable : renderables)
			drawable.render(context, mouseX, mouseY, partialTicks);
		
		// Draw placeholder + decorative left icon frame anchored to the field
		context.guiRenderState.up();
		
		int x0 = itemNameField.getX();
		int y0 = itemNameField.getY();
		int y1 = y0 + itemNameField.getHeight();
		
		if(itemNameField.getValue().isEmpty() && !itemNameField.isFocused())
			context.drawString(minecraft.font, "item name or ID", x0 + 6,
				y0 + 6, CommonColors.GRAY);
		
		int border = itemNameField.isFocused() ? CommonColors.WHITE
			: CommonColors.LIGHT_GRAY;
		int black = CommonColors.BLACK;
		int iconBoxLeft = x0 - 20;
		
		context.fill(iconBoxLeft, y0, x0, y1, border);
		context.fill(iconBoxLeft + 1, y0 + 1, x0 - 1, y1 - 1, black);
		
		RenderUtils.drawItem(context,
			itemToAdd == null ? ItemStack.EMPTY : new ItemStack(itemToAdd),
			iconBoxLeft + 2, y0 + 2, false);
		
		matrixStack.popMatrix();
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
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
		listGui.reloadPreservingState(itemList.getItemNames(), previousState,
			preferredKeys, scrollAmount);
		updateButtons();
	}
	
	private final class Entry
		extends MultiSelectEntryListWidget.Entry<EditItemListScreen.Entry>
	{
		private final String itemName;
		
		public Entry(ListGui parent, String itemName)
		{
			super(parent);
			this.itemName = Objects.requireNonNull(itemName);
		}
		
		@Override
		public Component getNarration()
		{
			Item item =
				BuiltInRegistries.ITEM.getValue(Identifier.parse(itemName));
			ItemStack stack = new ItemStack(item);
			
			return Component.translatable("narrator.select",
				"Item " + getDisplayName(stack) + ", " + itemName + ", "
					+ getIdText(item));
		}
		
		@Override
		public void renderContent(GuiGraphics context, int mouseX, int mouseY,
			boolean hovered, float tickDelta)
		{
			int x = getContentX();
			int y = getContentY();
			
			Item item =
				BuiltInRegistries.ITEM.getValue(Identifier.parse(itemName));
			ItemStack stack = new ItemStack(item);
			Font tr = minecraft.font;
			
			RenderUtils.drawItem(context, stack, x + 1, y + 1, true);
			context.drawString(tr, getDisplayName(stack), x + 28, y,
				WurstColors.VERY_LIGHT_GRAY, false);
			context.drawString(tr, itemName, x + 28, y + 9,
				CommonColors.LIGHT_GRAY, false);
			context.drawString(tr, getIdText(item), x + 28, y + 18,
				CommonColors.LIGHT_GRAY, false);
		}
		
		private String getDisplayName(ItemStack stack)
		{
			return stack.isEmpty() ? "\u00a7ounknown item\u00a7r"
				: stack.getHoverName().getString();
		}
		
		private String getIdText(Item item)
		{
			return "ID: " + BuiltInRegistries.ITEM.getId(item);
		}
		
		@Override
		public String selectionKey()
		{
			return itemName;
		}
	}
	
	private final class ListGui
		extends MultiSelectEntryListWidget<EditItemListScreen.Entry>
	{
		public ListGui(Minecraft minecraft, EditItemListScreen screen,
			List<String> list)
		{
			super(minecraft, screen.width, screen.height - 96, 36, 30);
			reload(list);
			ensureSelection();
		}
		
		public void reload(List<String> list)
		{
			clearEntries();
			list.stream().map(name -> new EditItemListScreen.Entry(this, name))
				.forEach(this::addEntry);
		}
		
		public void reloadPreservingState(List<String> list,
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
		
		public List<String> getSelectedItemNames()
		{
			return getSelectedKeys();
		}
		
		@Override
		protected String getSelectionKey(EditItemListScreen.Entry entry)
		{
			return entry.itemName;
		}
	}
}
