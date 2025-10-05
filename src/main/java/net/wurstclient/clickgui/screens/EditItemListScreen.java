/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.List;
import java.util.Objects;

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Identifier;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.util.ItemUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.WurstColors;

public final class EditItemListScreen extends Screen
{
	private final Screen prevScreen;
	private final ItemListSetting itemList;
	
	private ListGui listGui;
	private TextFieldWidget itemNameField;
	private ButtonWidget addKeywordButton;
	private ButtonWidget addButton;
	private ButtonWidget removeButton;
	private ButtonWidget doneButton;
	
	private Item itemToAdd;
	private java.util.List<net.minecraft.item.Item> fuzzyMatches =
		java.util.Collections.emptyList();
	
	public EditItemListScreen(Screen prevScreen, ItemListSetting itemList)
	{
		super(Text.literal(""));
		this.prevScreen = prevScreen;
		this.itemList = itemList;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(client, this, itemList.getItemNames());
		addSelectableChild(listGui);
		
		int rowY = height - 56;
		int gap = 8;
		int fieldWidth = 160;
		int keywordWidth = 110;
		int addWidth = 80;
		int removeWidth = 120;
		int totalWidth =
			fieldWidth + keywordWidth + addWidth + removeWidth + gap * 3;
		int rowStart = width / 2 - totalWidth / 2;
		
		itemNameField = new TextFieldWidget(client.textRenderer, rowStart, rowY,
			fieldWidth, 20, Text.literal(""));
		addSelectableChild(itemNameField);
		itemNameField.setMaxLength(256);
		
		int keywordX = rowStart + fieldWidth + gap;
		int addX = keywordX + keywordWidth + gap;
		int removeX = addX + addWidth + gap;
		
		addDrawableChild(addKeywordButton =
			ButtonWidget.builder(Text.literal("Add Keyword"), b -> {
				String raw = itemNameField.getText();
				if(raw != null)
					raw = raw.trim();
				if(raw != null && !raw.isEmpty())
					itemList.addRawName(raw);
				client.setScreen(EditItemListScreen.this);
			}).dimensions(keywordX, rowY, keywordWidth, 20).build());
		
		addDrawableChild(
			addButton = ButtonWidget.builder(Text.literal("Add"), b -> {
				if(itemToAdd != null)
				{
					itemList.add(itemToAdd);
				}else if(fuzzyMatches != null && !fuzzyMatches.isEmpty())
				{
					for(net.minecraft.item.Item it : fuzzyMatches)
						itemList.add(it);
				}else
				{
					String raw = itemNameField.getText();
					if(raw != null)
						raw = raw.trim();
					if(raw != null && !raw.isEmpty())
						itemList.addRawName(raw);
				}
				client.setScreen(EditItemListScreen.this);
			}).dimensions(addX, rowY, addWidth, 20).build());
		
		addDrawableChild(removeButton =
			ButtonWidget.builder(Text.literal("Remove Selected"), b -> {
				itemList.remove(itemList.getItemNames()
					.indexOf(listGui.getSelectedBlockName()));
				client.setScreen(EditItemListScreen.this);
			}).dimensions(removeX, rowY, removeWidth, 20).build());
		
		addDrawableChild(ButtonWidget.builder(Text.literal("Reset to Defaults"),
			b -> client.setScreen(new ConfirmScreen(b2 -> {
				if(b2)
					itemList.resetToDefaults();
				client.setScreen(EditItemListScreen.this);
			}, Text.literal("Reset to Defaults"),
				Text.literal("Are you sure?"))))
			.dimensions(width - 328, 8, 150, 20).build());
		
		addDrawableChild(ButtonWidget.builder(Text.literal("Clear List"), b -> {
			itemList.clear();
			client.setScreen(EditItemListScreen.this);
		}).dimensions(width - 168, 8, 150, 20).build());
		
		addDrawableChild(doneButton = ButtonWidget
			.builder(Text.literal("Done"), b -> client.setScreen(prevScreen))
			.dimensions(width / 2 - 100, height - 28, 200, 20).build());
	}
	
	@Override
	public boolean mouseClicked(Click context, boolean doubleClick)
	{
		itemNameField.mouseClicked(context, doubleClick);
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public boolean keyPressed(KeyInput context)
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
		String nameOrId = itemNameField.getText();
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
				java.util.ArrayList<net.minecraft.item.Item> list =
					new java.util.ArrayList<>();
				for(net.minecraft.util.Identifier id : net.minecraft.registry.Registries.ITEM
					.getIds())
				{
					String s = id.toString().toLowerCase(java.util.Locale.ROOT);
					if(s.contains(q))
						list.add(
							net.minecraft.registry.Registries.ITEM.get(id));
				}
				// Deduplicate and sort by identifier
				java.util.LinkedHashMap<String, net.minecraft.item.Item> map =
					new java.util.LinkedHashMap<>();
				for(net.minecraft.item.Item it : list)
					map.put(net.minecraft.registry.Registries.ITEM.getId(it)
						.toString(), it);
				fuzzyMatches = new java.util.ArrayList<>(map.values());
				fuzzyMatches.sort(java.util.Comparator
					.comparing(it -> net.minecraft.registry.Registries.ITEM
						.getId(it).toString()));
			}
			addButton.active = !fuzzyMatches.isEmpty() || hasInput;
			addButton.setMessage(Text.literal(fuzzyMatches.isEmpty() ? "Add"
				: ("Add Matches (" + fuzzyMatches.size() + ")")));
		}else
		{
			fuzzyMatches = java.util.Collections.emptyList();
			addButton.active = true;
			addButton.setMessage(Text.literal("Add"));
		}
		
		addKeywordButton.active = hasInput;
		removeButton.active = listGui.getSelectedOrNull() != null;
	}
	
	@Override
	public void render(DrawContext context, int mouseX, int mouseY,
		float partialTicks)
	{
		Matrix3x2fStack matrixStack = context.getMatrices();
		
		listGui.render(context, mouseX, mouseY, partialTicks);
		
		context.drawCenteredTextWithShadow(client.textRenderer,
			itemList.getName() + " (" + itemList.getItemNames().size() + ")",
			width / 2, 12, Colors.WHITE);
		
		matrixStack.pushMatrix();
		
		itemNameField.render(context, mouseX, mouseY, partialTicks);
		
		for(Drawable drawable : drawables)
			drawable.render(context, mouseX, mouseY, partialTicks);
		
		// Draw placeholder + decorative left icon frame anchored to the field
		context.state.goUpLayer();
		
		int x0 = itemNameField.getX();
		int y0 = itemNameField.getY();
		int y1 = y0 + itemNameField.getHeight();
		
		if(itemNameField.getText().isEmpty() && !itemNameField.isFocused())
			context.drawTextWithShadow(client.textRenderer, "item name or ID",
				x0 + 6, y0 + 6, Colors.GRAY);
		
		int border =
			itemNameField.isFocused() ? Colors.WHITE : Colors.LIGHT_GRAY;
		int black = Colors.BLACK;
		int iconBoxLeft = x0 - 20;
		
		context.fill(iconBoxLeft, y0, x0, y1, border);
		context.fill(iconBoxLeft + 1, y0 + 1, x0 - 1, y1 - 1, black);
		
		RenderUtils.drawItem(context,
			itemToAdd == null ? ItemStack.EMPTY : new ItemStack(itemToAdd),
			iconBoxLeft + 2, y0 + 2, false);
		
		matrixStack.popMatrix();
	}
	
	@Override
	public boolean shouldPause()
	{
		return false;
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
	
	private final class Entry
		extends AlwaysSelectedEntryListWidget.Entry<EditItemListScreen.Entry>
	{
		private final String itemName;
		
		public Entry(String itemName)
		{
			this.itemName = Objects.requireNonNull(itemName);
		}
		
		@Override
		public Text getNarration()
		{
			Item item = Registries.ITEM.get(Identifier.of(itemName));
			ItemStack stack = new ItemStack(item);
			
			return Text.translatable("narrator.select",
				"Item " + getDisplayName(stack) + ", " + itemName + ", "
					+ getIdText(item));
		}
		
		@Override
		public void render(DrawContext context, int mouseX, int mouseY,
			boolean hovered, float tickDelta)
		{
			int x = getContentX();
			int y = getContentY();
			
			Item item = Registries.ITEM.get(Identifier.of(itemName));
			ItemStack stack = new ItemStack(item);
			TextRenderer tr = client.textRenderer;
			
			RenderUtils.drawItem(context, stack, x + 1, y + 1, true);
			context.drawText(tr, getDisplayName(stack), x + 28, y,
				WurstColors.VERY_LIGHT_GRAY, false);
			context.drawText(tr, itemName, x + 28, y + 9, Colors.LIGHT_GRAY,
				false);
			context.drawText(tr, getIdText(item), x + 28, y + 18,
				Colors.LIGHT_GRAY, false);
		}
		
		private String getDisplayName(ItemStack stack)
		{
			return stack.isEmpty() ? "\u00a7ounknown item\u00a7r"
				: stack.getName().getString();
		}
		
		private String getIdText(Item item)
		{
			return "ID: " + Registries.ITEM.getRawId(item);
		}
	}
	
	private final class ListGui
		extends AlwaysSelectedEntryListWidget<EditItemListScreen.Entry>
	{
		public ListGui(MinecraftClient minecraft, EditItemListScreen screen,
			List<String> list)
		{
			super(minecraft, screen.width, screen.height - 96, 36, 30);
			
			list.stream().map(EditItemListScreen.Entry::new)
				.forEach(this::addEntry);
		}
		
		public String getSelectedBlockName()
		{
			EditItemListScreen.Entry selected = getSelectedOrNull();
			return selected != null ? selected.itemName : null;
		}
	}
}
