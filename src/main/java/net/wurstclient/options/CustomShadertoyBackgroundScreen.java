/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.options;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.WurstOptionsOtf;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ShadertoyBackgroundManager;
import net.wurstclient.util.WurstColors;

public final class CustomShadertoyBackgroundScreen extends Screen
{
	private final Screen prevScreen;
	private EditBox urlBox;
	private MultiLineEditBox codeBox;
	private Button savePresetButton;
	private String status = "";
	private boolean loading;
	
	public CustomShadertoyBackgroundScreen(Screen prevScreen)
	{
		super(Component.literal("Import Shadertoy Background"));
		this.prevScreen = prevScreen;
	}
	
	@Override
	protected void init()
	{
		WurstOptionsOtf options =
			WurstClient.INSTANCE.getOtfs().wurstOptionsOtf;
		TextFieldSetting urlSetting =
			options.getTitleScreenShadertoyUrlSetting();
		
		int boxWidth = Math.min(760, width - 40);
		int left = width / 2 - boxWidth / 2;
		int urlY = 58;
		
		urlBox = new EditBox(font, left, urlY, boxWidth, 20,
			Component.literal("Shadertoy URL"));
		urlBox.setMaxLength(512);
		urlBox.setValue(urlSetting.getValue());
		urlBox.setHint(
			Component.literal("https://www.shadertoy.com/view/Nfc3RM"));
		urlBox.setResponder(urlSetting::setValue);
		addRenderableWidget(urlBox);
		
		int editorY = urlY + 48;
		int editorHeight = Math.max(120, height - editorY - 144);
		codeBox = MultiLineEditBox.builder().setX(left).setY(editorY)
			.setPlaceholder(Component.literal(
				"Paste raw Shadertoy code here, including mainImage(...)."))
			.build(font, boxWidth, editorHeight,
				Component.literal("Shadertoy code"));
		codeBox.setCharacterLimit(262144);
		codeBox.setValue(ShadertoyBackgroundManager.loadRawShader());
		addRenderableWidget(codeBox);
		
		int buttonY = editorY + editorHeight + 10;
		int gap = 6;
		int buttonWidth = 112;
		int totalWidth = buttonWidth * 3 + gap * 2;
		int buttonX = width / 2 - totalWidth / 2;
		
		addRenderableWidget(
			Button.builder(Component.literal("Load URL"), b -> loadUrl())
				.bounds(buttonX, buttonY, buttonWidth, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Load Paste"), b -> loadPaste())
				.bounds(buttonX + (buttonWidth + gap), buttonY, buttonWidth, 20)
				.build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Use Built-in"), b -> useBuiltIn())
			.bounds(buttonX + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20)
			.build());
		
		int secondRowY = buttonY + 24;
		addRenderableWidget(
			Button.builder(Component.literal("Load Preset"), b -> loadPreset())
				.bounds(buttonX, secondRowY, buttonWidth, 20).build());
		
		savePresetButton = addRenderableWidget(Button
			.builder(Component.literal("Save Preset"), b -> savePreset())
			.bounds(buttonX + (buttonWidth + gap), secondRowY, buttonWidth, 20)
			.build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Back"),
				b -> minecraft.gui.setScreen(prevScreen))
			.bounds(buttonX + (buttonWidth + gap) * 2, secondRowY, buttonWidth,
				20)
			.build());
		
		status = ShadertoyBackgroundManager.hasCustomShader()
			? "Custom shader cache is active." : "Using built-in shader.";
	}
	
	private void loadUrl()
	{
		if(loading)
			return;
		
		String url = urlBox.getValue().trim();
		if(url.isEmpty())
		{
			status = "Paste a Shadertoy URL first.";
			return;
		}
		
		loading = true;
		status = "Downloading and converting shader...";
		Thread worker = new Thread(() -> {
			String result;
			try
			{
				result = ShadertoyBackgroundManager.importFromUrl(url);
			}catch(Exception e)
			{
				result = "Failed: " + e.getMessage();
			}
			
			String finalResult = result;
			minecraft.execute(() -> {
				loading = false;
				status = finalResult;
				if(!finalResult.startsWith("Failed"))
					codeBox
						.setValue(ShadertoyBackgroundManager.loadRawShader());
			});
		}, "Wurst Shadertoy Importer");
		worker.setDaemon(true);
		worker.start();
	}
	
	private void loadPaste()
	{
		if(loading)
			return;
		
		String source = codeBox.getValue();
		if(source.isBlank())
		{
			status = "Paste Shadertoy code first.";
			return;
		}
		
		loading = true;
		status = "Converting pasted shader...";
		Thread worker = new Thread(() -> {
			String result;
			try
			{
				result = ShadertoyBackgroundManager.importFromSource(source);
			}catch(Exception e)
			{
				result = "Failed: " + e.getMessage();
			}
			
			String finalResult = result;
			minecraft.execute(() -> {
				loading = false;
				status = finalResult;
			});
		}, "Wurst Shadertoy Paste Importer");
		worker.setDaemon(true);
		worker.start();
	}
	
	private void useBuiltIn()
	{
		try
		{
			ShadertoyBackgroundManager.clearCustomShader();
			status = "Switched back to built-in shader.";
		}catch(Exception e)
		{
			status = "Failed to clear custom shader: " + e.getMessage();
		}
	}
	
	private void savePreset()
	{
		if(loading)
			return;
		
		String source = codeBox.getValue();
		if(source.isBlank())
		{
			status = "Load or paste a custom shader before saving a preset.";
			return;
		}
		
		minecraft.gui.setScreen(new EnterProfileNameScreen(this, name -> {
			try
			{
				status = ShadertoyBackgroundManager.savePreset(name, source);
			}catch(Exception e)
			{
				status = "Failed: " + e.getMessage();
			}
		}, Component.literal("Save this shader as a preset"),
			ShadertoyBackgroundManager::isValidPresetName));
	}
	
	private void loadPreset()
	{
		if(loading)
			return;
		
		minecraft.gui.setScreen(new ShadertoyPresetScreen(this));
	}
	
	public void reloadFromDisk(String newStatus)
	{
		if(urlBox != null)
			urlBox.setValue(WurstClient.INSTANCE.getOtfs().wurstOptionsOtf
				.getTitleScreenShadertoyUrlSetting().getValue());
		
		if(codeBox != null)
			codeBox.setValue(ShadertoyBackgroundManager.loadRawShader());
		
		status = newStatus;
	}
	
	@Override
	public void tick()
	{
		if(savePresetButton != null)
			savePresetButton.active =
				ShadertoyBackgroundManager.hasCustomShader() && !loading;
	}
	
	@Override
	public void onClose()
	{
		minecraft.gui.setScreen(prevScreen);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		context.fillGradient(0, 0, width, height, 0xDA10131B, 0xE0121B29);
		
		context.centeredText(font, "Import Shadertoy Background", width / 2, 16,
			CommonColors.WHITE);
		context.centeredText(font,
			"Use a URL with an API key, or paste raw single-pass mainImage() code below.",
			width / 2, 30, CommonColors.LIGHT_GRAY);
		
		context.text(font, "Shadertoy URL", urlBox.getX(), urlBox.getY() - 12,
			CommonColors.LIGHT_GRAY);
		context.text(font, "Raw Shadertoy Code", codeBox.getX(),
			codeBox.getY() - 12, CommonColors.LIGHT_GRAY);
		
		for(var renderable : renderables)
			renderable.extractRenderState(context, mouseX, mouseY,
				partialTicks);
		
		int statusColor = status.startsWith("Failed") ? WurstColors.LIGHT_RED
			: CommonColors.LIGHT_GRAY;
		context.centeredText(font, status, width / 2, height - 42, statusColor);
		context.centeredText(font,
			"URL import uses -Dwurst.shadertoyApiKey=<your key> when set; paste import works without a key.",
			width / 2, height - 28, CommonColors.LIGHT_GRAY);
		context.centeredText(font,
			"Multipass/audio/video/cubemap Shadertoys are still unsupported.",
			width / 2, height - 16, CommonColors.LIGHT_GRAY);
	}
}
