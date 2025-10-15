/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.cevapi.config;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class AntiFingerprintConfigScreen extends Screen
{
	private static final int CONTENT_HEIGHT = 304;
	private static final int MIN_TOP_MARGIN = 48;
	private static final int LABEL_COLOR = 0xFFFFFFFF;
	private static final int PLACEHOLDER_COLOR = 0xFFAAAAAA;
	
	// Spacing tweaks
	private static final int LABEL_Y_OFFSET = 14; // label sits 14px above its
													// field
	private static final int FIELD_SPACING = 46; // vertical distance between
													// text fields
	private static final int FIRST_FIELD_TOP_SPACER = 8; // extra gap before the
															// first field
	
	private final Screen parent;
	private final AntiFingerprintConfig config = AntiFingerprintConfig.INSTANCE;
	
	private TextFieldWidget thresholdField;
	private TextFieldWidget windowField;
	private TextFieldWidget whitelistField;
	private int contentTop;
	
	public AntiFingerprintConfigScreen(Screen parent)
	{
		super(Text.literal("Anti-Fingerprint"));
		this.parent = parent;
	}
	
	@Override
	protected void init()
	{
		int centerX = width / 2;
		contentTop = Math.max(MIN_TOP_MARGIN, (height - CONTENT_HEIGHT) / 2);
		int y = contentTop;
		int doneButtonY = Math.min(height - 28, y + CONTENT_HEIGHT + 24);
		
		addDrawableChild(
			ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(centerX - 100, doneButtonY, 200, 20).build());
		
		addDrawableChild(
			CyclingButtonWidget.<AntiFingerprintConfig.Policy> builder(
				policy -> Text.literal(policy.toString()))
				.values(AntiFingerprintConfig.Policy.values())
				.initially(config.getPolicy()).build(centerX - 100, y, 200, 20,
					Text.literal("Policy"), (button, value) -> config
						.getPolicySetting().setSelected(value)));
		y += 26;
		
		addDrawableChild(
			CyclingButtonWidget.<AntiFingerprintConfig.ToastVerbosity> builder(
				level -> Text.literal(level.toString()))
				.values(AntiFingerprintConfig.ToastVerbosity.values())
				.initially(config.getToastVerbosity()).build(centerX - 100, y,
					200, 20, Text.literal("Toast verbosity"),
					(button, value) -> config.getToastVerbositySetting()
						.setSelected(value)));
		y += 26;
		
		addDrawableChild(CyclingButtonWidget.onOffBuilder()
			.initially(config.isAuditLogEnabled()).build(centerX - 100, y, 200,
				20, Text.literal("Audit logging"), (button, value) -> config
					.getAuditLogSetting().setChecked(value)));
		y += 34;
		
		addDrawableChild(CyclingButtonWidget.onOffBuilder()
			.initially(config.shouldClearCache()).build(centerX - 100, y, 200,
				20, Text.literal("Clear cache before download"), (button,
					value) -> config.getPurgeCacheSetting().setChecked(value)));
		y += 34;
		
		addDrawableChild(CyclingButtonWidget.onOffBuilder()
			.initially(config.shouldIsolateCache()).build(centerX - 100, y, 200,
				20, Text.literal("Isolate cached packs"),
				(button, value) -> config.getIsolateCacheSetting()
					.setChecked(value)));
		y += 34;
		
		addDrawableChild(CyclingButtonWidget.onOffBuilder()
			.initially(config.shouldExtractSandbox()).build(centerX - 100, y,
				200, 20, Text.literal("Extract sandbox copy"),
				(button, value) -> config.getExtractSandboxSetting()
					.setChecked(value)));
		y += 34;
		
		// Extra breathing room before first text field so labels never clip
		y += FIRST_FIELD_TOP_SPACER;
		
		// Text fields
		thresholdField = new TextFieldWidget(textRenderer, centerX - 100, y,
			200, 20, Text.literal("Threshold"));
		thresholdField
			.setText(Integer.toString(config.getFingerprintThreshold()));
		thresholdField.setChangedListener(this::onThresholdChanged);
		addDrawableChild(thresholdField);
		y += FIELD_SPACING;
		
		windowField = new TextFieldWidget(textRenderer, centerX - 100, y, 200,
			20, Text.literal("Window"));
		windowField.setText(Long.toString(config.getFingerprintWindowMs()));
		windowField.setChangedListener(this::onWindowChanged);
		addDrawableChild(windowField);
		y += FIELD_SPACING;
		
		whitelistField = new TextFieldWidget(textRenderer, centerX - 100, y,
			200, 20, Text.literal("Whitelist"));
		whitelistField.setMaxLength(256);
		whitelistField.setText(config.getWhitelistRaw());
		whitelistField.setChangedListener(config::setWhitelistRaw);
		addDrawableChild(whitelistField);
	}
	
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta)
	{
		super.render(context, mouseX, mouseY, delta);
		
		// Title
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2,
			Math.max(20, contentTop - 26), LABEL_COLOR);
		
		// Labels above text fields (with extra offset for breathing room)
		if(thresholdField != null)
			context.drawTextWithShadow(textRenderer,
				"Fingerprint threshold (packs)", thresholdField.getX(),
				thresholdField.getY() - LABEL_Y_OFFSET, LABEL_COLOR);
		
		if(windowField != null)
			context.drawTextWithShadow(textRenderer, "Detection window (ms)",
				windowField.getX(), windowField.getY() - LABEL_Y_OFFSET,
				LABEL_COLOR);
		
		if(whitelistField != null)
		{
			context.drawTextWithShadow(textRenderer,
				"Whitelisted hosts (comma separated)", whitelistField.getX(),
				whitelistField.getY() - LABEL_Y_OFFSET, LABEL_COLOR);
			
			if(whitelistField.getText().isBlank()
				&& !whitelistField.isFocused())
				context.drawTextWithShadow(textRenderer,
					"example.com, static.server", whitelistField.getX() + 4,
					whitelistField.getY() + 6, PLACEHOLDER_COLOR);
		}
	}
	
	@Override
	public void close()
	{
		client.setScreen(parent);
	}
	
	private void onThresholdChanged(String text)
	{
		if(text == null || text.isBlank())
			return;
		
		try
		{
			int value = Integer.parseInt(text);
			int min = config.getThresholdMin();
			int max = config.getThresholdMax();
			value = Math.max(min, Math.min(max, value));
			config.getFingerprintThresholdSetting().setValue(value);
		}catch(NumberFormatException ignored)
		{}
	}
	
	private void onWindowChanged(String text)
	{
		if(text == null || text.isBlank())
			return;
		
		try
		{
			int value = Integer.parseInt(text);
			int min = config.getWindowMin();
			int max = config.getWindowMax();
			value = Math.max(min, Math.min(max, value));
			config.getFingerprintWindowSetting().setValue(value);
		}catch(NumberFormatException ignored)
		{}
	}
}
