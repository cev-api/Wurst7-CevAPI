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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.CommonColors;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.settings.EntityTypeListSetting;

public final class EditEntityTypeListScreen extends Screen
{
	private final Screen prevScreen;
	private final EntityTypeListSetting typeList;
	
	private ListGui listGui;
	private EditBox typeNameField;
	private Button addKeywordButton;
	private Button addButton;
	private Button removeButton;
	private Button doneButton;
	
	private net.minecraft.world.entity.EntityType<?> typeToAdd;
	private java.util.List<net.minecraft.world.entity.EntityType<?>> fuzzyMatches =
		java.util.Collections.emptyList();
	
	public EditEntityTypeListScreen(Screen prevScreen,
		EntityTypeListSetting typeList)
	{
		super(Component.literal(""));
		this.prevScreen = prevScreen;
		this.typeList = typeList;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(minecraft, this, typeList.getTypeNames());
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
		
		typeNameField = new EditBox(minecraft.font, rowStart, rowY, fieldWidth,
			20, Component.literal(""));
		addWidget(typeNameField);
		typeNameField.setMaxLength(256);
		
		int keywordX = rowStart + fieldWidth + gap;
		int addX = keywordX + keywordWidth + gap;
		int removeX = addX + addWidth + gap;
		
		addRenderableWidget(addKeywordButton =
			Button.builder(Component.literal("Add Keyword"), b -> {
				String raw = typeNameField.getValue();
				if(raw != null)
					raw = raw.trim();
				if(raw == null || raw.isEmpty())
					return;
				
				var prevState = listGui.captureState();
				List<String> before = new ArrayList<>(typeList.getTypeNames());
				typeList.addRawName(raw);
				List<String> added = new ArrayList<>(typeList.getTypeNames());
				added.removeAll(before);
				
				refreshList(prevState, added, prevState.scrollAmount());
			}).bounds(keywordX, rowY, keywordWidth, 20).build());
		
		addRenderableWidget(
			addButton = Button.builder(Component.literal("Add"), b -> {
				var prevState = listGui.captureState();
				List<String> before = new ArrayList<>(typeList.getTypeNames());
				
				if(typeToAdd != null)
				{
					typeList.add(typeToAdd);
				}else if(fuzzyMatches != null && !fuzzyMatches.isEmpty())
				{
					for(net.minecraft.world.entity.EntityType<?> et : fuzzyMatches)
						typeList.add(et);
				}else
				{
					String raw = typeNameField.getValue();
					if(raw != null)
						raw = raw.trim();
					if(raw != null && !raw.isEmpty())
						typeList.addRawName(raw);
				}
				
				List<String> added = new ArrayList<>(typeList.getTypeNames());
				added.removeAll(before);
				
				refreshList(prevState, added, prevState.scrollAmount());
			}).bounds(addX, rowY, addWidth, 20).build());
		
		addRenderableWidget(removeButton =
			Button.builder(Component.literal("Remove Selected"), b -> {
				List<String> selected = listGui.getSelectedTypeNames();
				if(selected.isEmpty())
					return;
				
				var prevState = listGui.captureState();
				for(String key : selected)
				{
					int index = typeList.getTypeNames().indexOf(key);
					if(index >= 0)
						typeList.remove(index);
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
						typeList.resetToDefaults();
					minecraft.setScreen(EditEntityTypeListScreen.this);
				}, Component.literal("Reset to Defaults"),
					Component.literal("Are you sure?"))))
				.bounds(width - 328, 8, 150, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Clear List"), b -> {
				typeList.clear();
				minecraft.setScreen(EditEntityTypeListScreen.this);
			}).bounds(width - 168, 8, 150, 20).build());
		
		addRenderableWidget(doneButton = Button
			.builder(Component.literal("Done"),
				b -> minecraft.setScreen(prevScreen))
			.bounds(width / 2 - 100, height - 28, 200, 20).build());
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		typeNameField.mouseClicked(context, doubleClick);
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public boolean keyPressed(KeyEvent keyInput)
	{
		int keyCode = keyInput.key();
		switch(keyCode)
		{
			case GLFW.GLFW_KEY_ENTER:
			if(addButton.active)
				addButton.onPress(keyInput);
			break;
			
			case GLFW.GLFW_KEY_DELETE:
			if(!typeNameField.isFocused())
				removeButton.onPress(keyInput);
			break;
			
			case GLFW.GLFW_KEY_ESCAPE:
			doneButton.onPress(keyInput);
			break;
			
			default:
			break;
		}
		
		return super.keyPressed(keyInput);
	}
	
	@Override
	public void tick()
	{
		String rawInput = typeNameField.getValue();
		String nameOrId = rawInput == null ? "" : rawInput.toLowerCase();
		String trimmed = rawInput == null ? "" : rawInput.trim();
		boolean hasInput = !trimmed.isEmpty();
		try
		{
			ResourceLocation id = ResourceLocation.parse(nameOrId);
			typeToAdd =
				net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
					.containsKey(id)
						? net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
							.getValue(id)
						: null;
		}catch(IllegalArgumentException e)
		{
			typeToAdd = null;
		}
		if(typeToAdd == null)
		{
			String q = trimmed.isEmpty() ? ""
				: trimmed.toLowerCase(java.util.Locale.ROOT);
			if(q.isEmpty())
			{
				fuzzyMatches = java.util.Collections.emptyList();
			}else
			{
				java.util.ArrayList<net.minecraft.world.entity.EntityType<?>> list =
					new java.util.ArrayList<>();
				for(ResourceLocation id : BuiltInRegistries.ENTITY_TYPE
					.keySet())
				{
					String s = id.toString().toLowerCase(java.util.Locale.ROOT);
					if(s.contains(q))
						list.add(BuiltInRegistries.ENTITY_TYPE.getValue(id));
				}
				java.util.LinkedHashMap<String, net.minecraft.world.entity.EntityType<?>> map =
					new java.util.LinkedHashMap<>();
				for(net.minecraft.world.entity.EntityType<?> et : list)
					map.put(BuiltInRegistries.ENTITY_TYPE.getKey(et).toString(),
						et);
				fuzzyMatches = new java.util.ArrayList<>(map.values());
				fuzzyMatches.sort(java.util.Comparator.comparing(
					t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
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
			typeList.getName() + " (" + typeList.getTypeNames().size() + ")",
			width / 2, 12, CommonColors.WHITE);
		
		matrixStack.pushMatrix();
		
		typeNameField.render(context, mouseX, mouseY, partialTicks);
		
		for(Renderable drawable : renderables)
			drawable.render(context, mouseX, mouseY, partialTicks);
			
		// Draw placeholder + decorative left icon frame using ABSOLUTE
		// coordinates
		// derived from the actual TextFieldWidget position/size (no matrix
		// translate).
		context.guiRenderState.up();
		
		int x0 = typeNameField.getX();
		int y0 = typeNameField.getY();
		int y1 = y0 + typeNameField.getHeight();
		
		if(typeNameField.getValue().isEmpty() && !typeNameField.isFocused())
			context.drawString(minecraft.font, "entity type id", x0 + 6, y0 + 6,
				CommonColors.GRAY);
		
		int border = typeNameField.isFocused() ? CommonColors.WHITE
			: CommonColors.LIGHT_GRAY;
		int black = CommonColors.BLACK;
		
		context.fill(x0 - 16, y0, x0, y1, border);
		context.fill(x0 - 15, y0 + 1, x0 - 1, y1 - 1, black);
		
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
		listGui.reloadPreservingState(typeList.getTypeNames(), previousState,
			preferredKeys, scrollAmount);
		updateButtons();
	}
	
	private final class Entry
		extends MultiSelectEntryListWidget.Entry<EditEntityTypeListScreen.Entry>
	{
		private final String typeName;
		
		public Entry(ListGui parent, String typeName)
		{
			super(parent);
			this.typeName = Objects.requireNonNull(typeName);
		}
		
		@Override
		public net.minecraft.network.chat.Component getNarration()
		{
			return net.minecraft.network.chat.Component
				.translatable("narrator.select", "Entity " + typeName);
		}
		
		@Override
		public void renderContent(GuiGraphics context, int mouseX, int mouseY,
			boolean hovered, float tickDelta)
		{
			Font tr = minecraft.font;
			
			String display;
			net.minecraft.resources.ResourceLocation id =
				net.minecraft.resources.ResourceLocation.tryParse(typeName);
			if(id != null
				&& net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
					.containsKey(id))
				display =
					net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
						.getValue(id).getDescription().getString();
			else
				display = "\u00a7okeyword\u00a7r";
			int x = getContentX();
			int y = getContentY();
			context.drawString(tr, display, x + 8, y,
				net.wurstclient.util.WurstColors.VERY_LIGHT_GRAY, false);
			context.drawString(tr, typeName, x + 8, y + 10,
				CommonColors.LIGHT_GRAY, false);
		}
		
		@Override
		public String selectionKey()
		{
			return typeName;
		}
	}
	
	private final class ListGui
		extends MultiSelectEntryListWidget<EditEntityTypeListScreen.Entry>
	{
		public ListGui(Minecraft minecraft, EditEntityTypeListScreen screen,
			List<String> list)
		{
			super(minecraft, screen.width, screen.height - 96, 36, 30);
			reload(list);
			ensureSelection();
		}
		
		public void reload(List<String> list)
		{
			clearEntries();
			list.stream()
				.map(name -> new EditEntityTypeListScreen.Entry(this, name))
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
		
		public List<String> getSelectedTypeNames()
		{
			return getSelectedKeys();
		}
		
		@Override
		protected String getSelectionKey(EditEntityTypeListScreen.Entry entry)
		{
			return entry.typeName;
		}
	}
}
