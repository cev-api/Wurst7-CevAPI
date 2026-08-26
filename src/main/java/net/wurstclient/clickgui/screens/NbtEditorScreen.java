/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.hacks.ophack.NBTEditorHack;

/** Native, large-text SNBT editor with Wurst preset integration. */
public final class NBTEditorScreen extends Screen
{
	private final Screen previous;
	private static final Pattern ERROR_POSITION =
		Pattern.compile("(?:position|at)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern RESOURCE_ID =
		Pattern.compile("minecraft:[a-z0-9_./-]+");
	private final NBTEditorHack hack;
	private MultiLineEditBox editor;
	private EditBox presetName;
	private Button applyButton;
	private int validationY;
	private String validationStatus = "Validating...";
	private String operationStatus = "";
	private String lastValidatedText = "\u0000";
	private String pendingValidationText = "";
	private int validationCooldown;
	
	public NBTEditorScreen(Screen previous, NBTEditorHack hack)
	{
		super(Component.literal("NBT Editor"));
		this.previous = previous;
		this.hack = hack;
	}
	
	@Override
	public void init()
	{
		int editorWidth = Math.min(1050, Math.max(420, width - 260));
		int x = (width - editorWidth) / 2;
		int y = 38;
		int buttonY = height - 61;
		int presetY = height - 34;
		// Keep the status strip immediately above the controls. This makes the
		// raw editor consume every usable pixel instead of leaving a dead gap.
		int editorHeight = Math.max(130, buttonY - y - 34);
		editor = MultiLineEditBox.builder().setX(x).setY(y)
			.setPlaceholder(Component.literal("Paste item SNBT here"))
			.build(font, editorWidth, editorHeight, Component.literal("NBT"));
		// MultiLineEditBox supports clipboard edits and scroll/selection. This
		// must remain unlimited so full container items are not truncated.
		editor.setCharacterLimit(Integer.MAX_VALUE);
		editor.setValue(hack.getEditorText());
		addRenderableWidget(editor);
		setFocused(editor);
		
		int editorBottom = y + editorHeight;
		validationY = editorBottom + 10;
		int buttonWidth = 118;
		int gap = 6;
		int total = buttonWidth * 4 + gap * 3;
		int start = (width - total) / 2;
		addRenderableWidget(button("Read held", start, buttonY, b -> {
			setText(hack.readHeldItem());
			operationStatus = hack.getLastEditorMessage();
		}));
		addRenderableWidget(
			button("New item", start + buttonWidth + gap, buttonY, b -> {
				setText(hack.newItem());
				operationStatus = hack.getLastEditorMessage();
			}));
		applyButton = button("Apply / Give", start + (buttonWidth + gap) * 2,
			buttonY, b -> apply());
		addRenderableWidget(applyButton);
		addRenderableWidget(button("Cancel", start + (buttonWidth + gap) * 3,
			buttonY, b -> close()));
		
		int presetTotal = 220 + 5 + 118 + 5 + 118;
		int presetStart = (width - presetTotal) / 2;
		presetName = new EditBox(font, presetStart, presetY, 220, 20,
			Component.literal("Preset name"));
		presetName.setMaxLength(64);
		addRenderableWidget(presetName);
		addRenderableWidget(button("Save preset", presetStart + 225, presetY,
			b -> savePreset()));
		addRenderableWidget(button("Manage presets", presetStart + 348, presetY,
			b -> minecraft.gui.setScreen(new NBTPresetListScreen(this, hack))));
		requestValidation();
	}
	
	private void setText(String text)
	{
		editor.setValue(text == null ? "" : text);
		requestValidation();
	}
	
	private void requestValidation()
	{
		pendingValidationText = editor == null ? "" : editor.getValue();
		validationCooldown = 8;
	}
	
	@Override
	public void tick()
	{
		super.tick();
		if(editor == null)
			return;
		String text = editor.getValue();
		if(!text.equals(pendingValidationText))
		{
			operationStatus = "";
			pendingValidationText = text;
			validationCooldown = 8;
		}
		if(validationCooldown > 0)
		{
			validationCooldown--;
			return;
		}
		if(text.equals(lastValidatedText))
			return;
		lastValidatedText = text;
		String error = hack.validate(text);
		validationStatus =
			error == null ? "Ready to apply" : errorWithLocation(text, error);
		applyButton.active = error == null;
	}
	
	private Button button(String label, int x, int y, Button.OnPress action)
	{
		return Button.builder(Component.literal(label), action)
			.bounds(x, y, 118, 20).build();
	}
	
	private String errorWithLocation(String text, String error)
	{
		String message = error == null ? "Invalid item data." : error;
		Matcher matcher = ERROR_POSITION.matcher(message);
		int position = -1;
		if(matcher.find())
			try
			{
				position = Math.clamp(Integer.parseInt(matcher.group(1)), 0,
					text.length());
			}catch(NumberFormatException ignored)
			{}
		else
		{
			// Registry errors omit offsets, so find the rejected id in the
			// input.
			Matcher id = RESOURCE_ID.matcher(message);
			if(id.find())
				position = text.indexOf(id.group());
		}
		if(position < 0)
			return shortStatus("Invalid: " + message);
		try
		{
			int line = 1;
			int column = 1;
			for(int i = 0; i < position; i++)
				if(text.charAt(i) == '\n')
				{
					line++;
					column = 1;
				}else
					column++;
			return shortStatus("Invalid at line " + line + ", column " + column
				+ ": " + message);
		}catch(RuntimeException ignored)
		{
			return shortStatus("Invalid: " + message);
		}
	}
	
	private String shortStatus(String message)
	{
		if(message == null)
			return "";
		String singleLine = message.replace('\n', ' ').replace('\r', ' ')
			.replaceAll("\\s+", " ").trim();
		return font.plainSubstrByWidth(singleLine, Math.max(80, width - 40));
	}
	
	private void savePreset()
	{
		if(hack.savePreset(presetName.getValue().trim(), editor.getValue()))
		{
			presetName.setValue("");
			operationStatus = "Preset saved.";
		}
	}
	
	public void loadPresetText(String value)
	{
		hack.setEditorText(value);
		setText(value);
		operationStatus = hack.getLastEditorMessage();
	}
	
	private void apply()
	{
		if(hack.apply(editor.getValue()))
			close();
		else
			operationStatus = hack.getLastEditorMessage();
	}
	
	private void close()
	{
		hack.setEnabled(false);
		minecraft.gui.setScreen(previous);
	}
	
	@Override
	public boolean keyPressed(KeyEvent event)
	{
		if(event.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			close();
			return true;
		}
		if(event.key() == GLFW.GLFW_KEY_ENTER && event.hasControlDown())
		{
			apply();
			return true;
		}
		return super.keyPressed(event);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		context.centeredText(font, "NBT Editor", width / 2, 14,
			CommonColors.WHITE);
		context.centeredText(font,
			"Large SNBT editor. Ctrl+V pastes, mouse wheel scrolls, Ctrl+Enter applies.",
			width / 2, 26, 0xAAAAAA);
		super.extractRenderState(context, mouseX, mouseY, partialTicks);
		int statusWidth = Math.min(1050, Math.max(420, width - 260));
		int statusLeft = (width - statusWidth) / 2;
		context.fill(statusLeft, validationY - 3, statusLeft + statusWidth,
			validationY + 21, 0xDD121820);
		context.fill(statusLeft, validationY - 3, statusLeft + statusWidth,
			validationY - 2, 0xFF4B5563);
		int color =
			validationStatus.startsWith("Ready") ? 0xFF86EFAC : 0xFFFF8A8A;
		context.centeredText(font, validationStatus, width / 2, validationY,
			color);
		if(!operationStatus.isEmpty())
			context.centeredText(font, shortStatus(operationStatus), width / 2,
				validationY + 11, 0xFFE2E8F0);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
}
