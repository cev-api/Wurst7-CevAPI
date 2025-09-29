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

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.WurstColors;

public final class EditBlockListScreen extends Screen
{
	private final Screen prevScreen;
	private final BlockListSetting blockList;
	
	private ListGui listGui;
	private TextFieldWidget blockNameField;
	private ButtonWidget addKeywordButton;
	private ButtonWidget addButton;
	private ButtonWidget removeButton;
	private ButtonWidget doneButton;
	
	private Block blockToAdd;
	private java.util.List<net.minecraft.block.Block> fuzzyMatches =
		java.util.Collections.emptyList();
	
	public EditBlockListScreen(Screen prevScreen, BlockListSetting blockList)
	{
		super(Text.literal(""));
		this.prevScreen = prevScreen;
		this.blockList = blockList;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(client, this, blockList.getBlockNames());
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
		
		blockNameField = new TextFieldWidget(client.textRenderer, rowStart,
			rowY, fieldWidth, 20, Text.literal(""));
		addSelectableChild(blockNameField);
		blockNameField.setMaxLength(256);
		
		int keywordX = rowStart + fieldWidth + gap;
		int addX = keywordX + keywordWidth + gap;
		int removeX = addX + addWidth + gap;
		
		addDrawableChild(addKeywordButton =
			ButtonWidget.builder(Text.literal("Add Keyword"), b -> {
				String raw = blockNameField.getText();
				if(raw != null)
					raw = raw.trim();
				if(raw != null && !raw.isEmpty())
					blockList.addRawName(raw);
				client.setScreen(EditBlockListScreen.this);
			}).dimensions(keywordX, rowY, keywordWidth, 20).build());
		
		addDrawableChild(
			addButton = ButtonWidget.builder(Text.literal("Add"), b -> {
				if(blockToAdd != null)
				{
					blockList.add(blockToAdd);
				}else if(fuzzyMatches != null && !fuzzyMatches.isEmpty())
				{
					for(net.minecraft.block.Block bk : fuzzyMatches)
						blockList.add(bk);
				}else
				{
					String raw = blockNameField.getText();
					if(raw != null)
						raw = raw.trim();
					if(raw != null && !raw.isEmpty())
						blockList.addRawName(raw);
				}
				client.setScreen(EditBlockListScreen.this);
			}).dimensions(addX, rowY, addWidth, 20).build());
		
		addDrawableChild(removeButton =
			ButtonWidget.builder(Text.literal("Remove Selected"), b -> {
				blockList
					.remove(blockList.indexOf(listGui.getSelectedBlockName()));
				client.setScreen(EditBlockListScreen.this);
			}).dimensions(removeX, rowY, removeWidth, 20).build());
		
		addDrawableChild(ButtonWidget.builder(Text.literal("Reset to Defaults"),
			b -> client.setScreen(new ConfirmScreen(b2 -> {
				if(b2)
					blockList.resetToDefaults();
				client.setScreen(EditBlockListScreen.this);
			}, Text.literal("Reset to Defaults"),
				Text.literal("Are you sure?"))))
			.dimensions(width - 328, 8, 150, 20).build());
		
		addDrawableChild(ButtonWidget.builder(Text.literal("Clear List"), b -> {
			blockList.clear();
			client.setScreen(EditBlockListScreen.this);
		}).dimensions(width - 168, 8, 150, 20).build());
		
		addDrawableChild(doneButton = ButtonWidget
			.builder(Text.literal("Done"), b -> client.setScreen(prevScreen))
			.dimensions(width / 2 - 100, height - 28, 200, 20).build());
	}
	
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int mouseButton)
	{
		blockNameField.mouseClicked(mouseX, mouseY, mouseButton);
		return super.mouseClicked(mouseX, mouseY, mouseButton);
	}
	
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int int_3)
	{
		switch(keyCode)
		{
			case GLFW.GLFW_KEY_ENTER:
			if(addButton.active)
				addButton.onPress();
			break;
			
			case GLFW.GLFW_KEY_DELETE:
			if(!blockNameField.isFocused())
				removeButton.onPress();
			break;
			
			case GLFW.GLFW_KEY_ESCAPE:
			doneButton.onPress();
			break;
			
			default:
			break;
		}
		
		return super.keyPressed(keyCode, scanCode, int_3);
	}
	
	@Override
	public void tick()
	{
		String nameOrId = blockNameField.getText();
		String trimmed = nameOrId == null ? "" : nameOrId.trim();
		boolean hasInput = !trimmed.isEmpty();
		blockToAdd =
			net.wurstclient.util.BlockUtils.getBlockFromNameOrID(nameOrId);
		// Build fuzzy matches if no exact block found
		if(blockToAdd == null)
		{
			String q = trimmed.toLowerCase(java.util.Locale.ROOT);
			if(q.isEmpty())
			{
				fuzzyMatches = java.util.Collections.emptyList();
			}else
			{
				java.util.ArrayList<net.minecraft.block.Block> list =
					new java.util.ArrayList<>();
				for(net.minecraft.util.Identifier id : net.minecraft.registry.Registries.BLOCK
					.getIds())
				{
					String s = id.toString().toLowerCase(java.util.Locale.ROOT);
					if(s.contains(q))
						list.add(
							net.minecraft.registry.Registries.BLOCK.get(id));
				}
				// Deduplicate and sort by identifier
				java.util.LinkedHashMap<String, net.minecraft.block.Block> map =
					new java.util.LinkedHashMap<>();
				for(net.minecraft.block.Block b : list)
					map.put(net.wurstclient.util.BlockUtils.getName(b), b);
				fuzzyMatches = new java.util.ArrayList<>(map.values());
				fuzzyMatches.sort(java.util.Comparator
					.comparing(net.wurstclient.util.BlockUtils::getName));
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
			blockList.getName() + " (" + blockList.size() + ")", width / 2, 12,
			Colors.WHITE);
		
		matrixStack.pushMatrix();
		
		blockNameField.render(context, mouseX, mouseY, partialTicks);
		
		for(Drawable drawable : drawables)
			drawable.render(context, mouseX, mouseY, partialTicks);
			
		// Draw placeholder + decorative left icon frame using ABSOLUTE
		// coordinates
		// derived from the actual TextFieldWidget position/size (no matrix
		// translate).
		context.state.goUpLayer();
		
		int x0 = blockNameField.getX();
		int y0 = blockNameField.getY();
		int y1 = y0 + blockNameField.getHeight();
		
		if(blockNameField.getText().isEmpty() && !blockNameField.isFocused())
			context.drawTextWithShadow(client.textRenderer, "block name or ID",
				x0 + 6, y0 + 6, Colors.GRAY);
		
		int border =
			blockNameField.isFocused() ? Colors.WHITE : Colors.LIGHT_GRAY;
		int black = Colors.BLACK;
		int iconBoxLeft = x0 - 20;
		
		// Left decoration for the item icon, anchored to the field.
		context.fill(iconBoxLeft, y0, x0, y1, border);
		context.fill(iconBoxLeft + 1, y0 + 1, x0 - 1, y1 - 1, black);
		
		RenderUtils.drawItem(context,
			blockToAdd == null ? ItemStack.EMPTY : new ItemStack(blockToAdd),
			iconBoxLeft + 2, y0 + 2, false);
		
		context.state.goDownLayer();
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
		extends AlwaysSelectedEntryListWidget.Entry<EditBlockListScreen.Entry>
	{
		private final String blockName;
		
		public Entry(String blockName)
		{
			this.blockName = Objects.requireNonNull(blockName);
		}
		
		@Override
		public Text getNarration()
		{
			Block block = BlockUtils.getBlockFromName(blockName);
			ItemStack stack = new ItemStack(block);
			
			return Text.translatable("narrator.select",
				"Block " + getDisplayName(stack) + ", " + blockName + ", "
					+ getIdText(block));
		}
		
		@Override
		public void render(DrawContext context, int index, int y, int x,
			int entryWidth, int entryHeight, int mouseX, int mouseY,
			boolean hovered, float tickDelta)
		{
			Block block = BlockUtils.getBlockFromName(blockName);
			ItemStack stack = new ItemStack(block);
			TextRenderer tr = client.textRenderer;
			
			RenderUtils.drawItem(context, stack, x + 1, y + 1, true);
			context.drawText(tr, getDisplayName(stack), x + 28, y,
				WurstColors.VERY_LIGHT_GRAY, false);
			context.drawText(tr, blockName, x + 28, y + 9, Colors.LIGHT_GRAY,
				false);
			context.drawText(tr, getIdText(block), x + 28, y + 18,
				Colors.LIGHT_GRAY, false);
		}
		
		private String getDisplayName(ItemStack stack)
		{
			return stack.isEmpty() ? "\u00a7ounknown block\u00a7r"
				: stack.getName().getString();
		}
		
		private String getIdText(Block block)
		{
			return "ID: " + Block.getRawIdFromState(block.getDefaultState());
		}
	}
	
	private final class ListGui
		extends AlwaysSelectedEntryListWidget<EditBlockListScreen.Entry>
	{
		public ListGui(MinecraftClient minecraft, EditBlockListScreen screen,
			List<String> list)
		{
			super(minecraft, screen.width, screen.height - 96, 36, 30, 0);
			
			list.stream().map(EditBlockListScreen.Entry::new)
				.forEach(this::addEntry);
		}
		
		public String getSelectedBlockName()
		{
			EditBlockListScreen.Entry selected = getSelectedOrNull();
			return selected != null ? selected.blockName : null;
		}
	}
}
