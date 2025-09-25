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
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Identifier;
import net.wurstclient.settings.EntityTypeListSetting;

public final class EditEntityTypeListScreen extends Screen
{
	private final Screen prevScreen;
	private final EntityTypeListSetting typeList;
	
	private ListGui listGui;
	private TextFieldWidget typeNameField;
	private ButtonWidget addButton;
	private ButtonWidget removeButton;
	private ButtonWidget doneButton;
	
	private net.minecraft.entity.EntityType<?> typeToAdd;
	private java.util.List<net.minecraft.entity.EntityType<?>> fuzzyMatches =
		java.util.Collections.emptyList();
	
	public EditEntityTypeListScreen(Screen prevScreen,
		EntityTypeListSetting typeList)
	{
		super(Text.literal(""));
		this.prevScreen = prevScreen;
		this.typeList = typeList;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(client, this, typeList.getTypeNames());
		addSelectableChild(listGui);
		
		typeNameField = new TextFieldWidget(client.textRenderer,
			width / 2 - 152, height - 56, 140, 20, Text.literal(""));
		addSelectableChild(typeNameField);
		typeNameField.setMaxLength(256);
		
		addDrawableChild(
			addButton = ButtonWidget.builder(Text.literal("Add"), b -> {
				if(typeToAdd != null)
				{
					typeList.add(typeToAdd);
				}else if(fuzzyMatches != null && !fuzzyMatches.isEmpty())
				{
					for(net.minecraft.entity.EntityType<?> et : fuzzyMatches)
						typeList.add(et);
				}
				client.setScreen(EditEntityTypeListScreen.this);
			}).dimensions(width / 2 - 2, height - 56, 80, 20).build());
		
		addDrawableChild(removeButton =
			ButtonWidget.builder(Text.literal("Remove Selected"), b -> {
				String selected = listGui.getSelectedTypeName();
				typeList.remove(typeList.getTypeNames().indexOf(selected));
				client.setScreen(EditEntityTypeListScreen.this);
			}).dimensions(width / 2 + 82, height - 56, 120, 20).build());
		
		addDrawableChild(ButtonWidget.builder(Text.literal("Reset to Defaults"),
			b -> client.setScreen(new ConfirmScreen(b2 -> {
				if(b2)
					typeList.resetToDefaults();
				client.setScreen(EditEntityTypeListScreen.this);
			}, Text.literal("Reset to Defaults"),
				Text.literal("Are you sure?"))))
			.dimensions(width - 328, 8, 150, 20).build());
		
		addDrawableChild(ButtonWidget.builder(Text.literal("Clear List"), b -> {
			typeList.clear();
			client.setScreen(EditEntityTypeListScreen.this);
		}).dimensions(width - 168, 8, 150, 20).build());
		
		addDrawableChild(doneButton = ButtonWidget
			.builder(Text.literal("Done"), b -> client.setScreen(prevScreen))
			.dimensions(width / 2 - 100, height - 28, 200, 20).build());
	}
	
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int mouseButton)
	{
		typeNameField.mouseClicked(mouseX, mouseY, mouseButton);
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
			if(!typeNameField.isFocused())
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
		String nameOrId = typeNameField.getText().toLowerCase();
		try
		{
			Identifier id = Identifier.of(nameOrId);
			typeToAdd =
				net.minecraft.registry.Registries.ENTITY_TYPE.containsId(id)
					? net.minecraft.registry.Registries.ENTITY_TYPE.get(id)
					: null;
		}catch(IllegalArgumentException e)
		{
			typeToAdd = null;
		}
		if(typeToAdd == null)
		{
			String q = nameOrId == null ? ""
				: nameOrId.trim().toLowerCase(java.util.Locale.ROOT);
			if(q.isEmpty())
			{
				fuzzyMatches = java.util.Collections.emptyList();
			}else
			{
				java.util.ArrayList<net.minecraft.entity.EntityType<?>> list =
					new java.util.ArrayList<>();
				for(Identifier id : Registries.ENTITY_TYPE.getIds())
				{
					String s = id.toString().toLowerCase(java.util.Locale.ROOT);
					if(s.contains(q))
						list.add(Registries.ENTITY_TYPE.get(id));
				}
				java.util.LinkedHashMap<String, net.minecraft.entity.EntityType<?>> map =
					new java.util.LinkedHashMap<>();
				for(net.minecraft.entity.EntityType<?> et : list)
					map.put(Registries.ENTITY_TYPE.getId(et).toString(), et);
				fuzzyMatches = new java.util.ArrayList<>(map.values());
				fuzzyMatches.sort(java.util.Comparator.comparing(
					t -> Registries.ENTITY_TYPE.getId(t).toString()));
			}
			addButton.active = !fuzzyMatches.isEmpty();
			addButton.setMessage(Text.literal(fuzzyMatches.isEmpty() ? "Add"
				: ("Add Matches (" + fuzzyMatches.size() + ")")));
		}else
		{
			fuzzyMatches = java.util.Collections.emptyList();
			addButton.active = true;
			addButton.setMessage(Text.literal("Add"));
		}
		
		removeButton.active = listGui.getSelectedOrNull() != null;
	}
	
	@Override
	public void render(DrawContext context, int mouseX, int mouseY,
		float partialTicks)
	{
		Matrix3x2fStack matrixStack = context.getMatrices();
		
		listGui.render(context, mouseX, mouseY, partialTicks);
		
		context.drawCenteredTextWithShadow(client.textRenderer,
			typeList.getName() + " (" + typeList.getTypeNames().size() + ")",
			width / 2, 12, Colors.WHITE);
		
		matrixStack.pushMatrix();
		
		typeNameField.render(context, mouseX, mouseY, partialTicks);
		
		for(Drawable drawable : drawables)
			drawable.render(context, mouseX, mouseY, partialTicks);
			
		// Draw placeholder + decorative left icon frame using ABSOLUTE
		// coordinates
		// derived from the actual TextFieldWidget position/size (no matrix
		// translate).
		context.state.goUpLayer();
		
		int x0 = typeNameField.getX();
		int y0 = typeNameField.getY();
		int x1 = x0 + typeNameField.getWidth();
		int y1 = y0 + typeNameField.getHeight();
		
		if(typeNameField.getText().isEmpty() && !typeNameField.isFocused())
			context.drawTextWithShadow(client.textRenderer, "entity type id",
				x0 + 6, y0 + 6, Colors.GRAY);
		
		int border =
			typeNameField.isFocused() ? Colors.WHITE : Colors.LIGHT_GRAY;
		int black = Colors.BLACK;
		
		context.fill(x0 - 16, y0, x0, y1, border);
		context.fill(x0 - 15, y0 + 1, x0 - 1, y1 - 1, black);
		
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
	
	private final class Entry extends
		AlwaysSelectedEntryListWidget.Entry<EditEntityTypeListScreen.Entry>
	{
		private final String typeName;
		
		public Entry(String typeName)
		{
			this.typeName = Objects.requireNonNull(typeName);
		}
		
		@Override
		public net.minecraft.text.Text getNarration()
		{
			return net.minecraft.text.Text.translatable("narrator.select",
				"Entity " + typeName);
		}
		
		@Override
		public void render(DrawContext context, int index, int y, int x,
			int entryWidth, int entryHeight, int mouseX, int mouseY,
			boolean hovered, float tickDelta)
		{
			TextRenderer tr = client.textRenderer;
			String display = Registries.ENTITY_TYPE.get(Identifier.of(typeName))
				.getName().getString();
			context.drawText(tr, display, x + 8, y,
				net.wurstclient.util.WurstColors.VERY_LIGHT_GRAY, false);
			context.drawText(tr, typeName, x + 8, y + 10, Colors.LIGHT_GRAY,
				false);
		}
	}
	
	private final class ListGui
		extends AlwaysSelectedEntryListWidget<EditEntityTypeListScreen.Entry>
	{
		public ListGui(MinecraftClient minecraft,
			EditEntityTypeListScreen screen, List<String> list)
		{
			super(minecraft, screen.width, screen.height - 96, 36, 30, 0);
			list.stream().map(EditEntityTypeListScreen.Entry::new)
				.forEach(this::addEntry);
		}
		
		public String getSelectedTypeName()
		{
			EditEntityTypeListScreen.Entry selected = getSelectedOrNull();
			return selected != null ? selected.typeName : null;
		}
	}
}
