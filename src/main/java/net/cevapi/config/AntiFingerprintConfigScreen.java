/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.cevapi.config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
	
	private EditBox thresholdField;
	private EditBox windowField;
	private EditBox whitelistField;
	private int contentTop;
	
	public AntiFingerprintConfigScreen(Screen parent)
	{
		super(Component.literal("Anti-Fingerprint"));
		this.parent = parent;
	}
	
	@Override
	protected void init()
	{
		int centerX = width / 2;
		contentTop = Math.max(MIN_TOP_MARGIN, (height - CONTENT_HEIGHT) / 2);
		int y = contentTop;
		int doneButtonY = Math.min(height - 28, y + CONTENT_HEIGHT + 24);
		
		addRenderableWidget(
			Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(centerX - 100, doneButtonY, 200, 20).build());
		
		addRenderableWidget(CycleButton.<AntiFingerprintConfig.Policy> builder(
			policy -> Component.literal(policy.toString()))
			.withValues(AntiFingerprintConfig.Policy.values())
			.withInitialValue(config.getPolicy()).create(centerX - 100, y, 200,
				20, Component.literal("Policy"), (button, value) -> config
					.getPolicySetting().setSelected(value)));
		y += 26;
		
		addRenderableWidget(
			CycleButton.<AntiFingerprintConfig.ToastVerbosity> builder(
				level -> Component.literal(level.toString()))
				.withValues(AntiFingerprintConfig.ToastVerbosity.values())
				.withInitialValue(config.getToastVerbosity())
				.create(centerX - 100, y, 200, 20,
					Component.literal("Toast verbosity"),
					(button, value) -> config.getToastVerbositySetting()
						.setSelected(value)));
		y += 26;
		
		addRenderableWidget(CycleButton.onOffBuilder()
			.withInitialValue(config.isAuditLogEnabled()).create(centerX - 100,
				y, 200, 20, Component.literal("Audit logging"), (button,
					value) -> config.getAuditLogSetting().setChecked(value)));
		y += 34;
		
		addRenderableWidget(CycleButton.onOffBuilder()
			.withInitialValue(config.shouldClearCache()).create(centerX - 100,
				y, 200, 20, Component.literal("Clear cache before download"),
				(button, value) -> config.getPurgeCacheSetting()
					.setChecked(value)));
		y += 34;
		
		addRenderableWidget(CycleButton.onOffBuilder()
			.withInitialValue(config.shouldIsolateCache()).create(centerX - 100,
				y, 200, 20, Component.literal("Isolate cached packs"),
				(button, value) -> config.getIsolateCacheSetting()
					.setChecked(value)));
		y += 34;
		
		addRenderableWidget(CycleButton.onOffBuilder()
			.withInitialValue(config.shouldExtractSandbox())
			.create(centerX - 100, y, 200, 20,
				Component.literal("Extract sandbox copy"),
				(button, value) -> config.getExtractSandboxSetting()
					.setChecked(value)));
		y += 34;
		
		// Extra breathing room before first text field so labels never clip
		y += FIRST_FIELD_TOP_SPACER;
		
		// Text fields
		thresholdField = new EditBox(font, centerX - 100, y, 200, 20,
			Component.literal("Threshold"));
		thresholdField
			.setValue(Integer.toString(config.getFingerprintThreshold()));
		thresholdField.setResponder(this::onThresholdChanged);
		addRenderableWidget(thresholdField);
		y += FIELD_SPACING;
		
		windowField = new EditBox(font, centerX - 100, y, 200, 20,
			Component.literal("Window"));
		windowField.setValue(Long.toString(config.getFingerprintWindowMs()));
		windowField.setResponder(this::onWindowChanged);
		addRenderableWidget(windowField);
		y += FIELD_SPACING;
		
		whitelistField = new EditBox(font, centerX - 100, y, 200, 20,
			Component.literal("Whitelist"));
		whitelistField.setMaxLength(256);
		whitelistField.setValue(config.getWhitelistRaw());
		whitelistField.setResponder(config::setWhitelistRaw);
		addRenderableWidget(whitelistField);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta)
	{
		super.render(context, mouseX, mouseY, delta);
		
		// Title
		context.drawCenteredString(font, title, width / 2,
			Math.max(20, contentTop - 26), LABEL_COLOR);
		
		// Labels above text fields (with extra offset for breathing room)
		if(thresholdField != null)
			context.drawString(font, "Fingerprint threshold (packs)",
				thresholdField.getX(), thresholdField.getY() - LABEL_Y_OFFSET,
				LABEL_COLOR);
		
		if(windowField != null)
			context.drawString(font, "Detection window (ms)",
				windowField.getX(), windowField.getY() - LABEL_Y_OFFSET,
				LABEL_COLOR);
		
		if(whitelistField != null)
		{
			context.drawString(font, "Whitelisted hosts (comma separated)",
				whitelistField.getX(), whitelistField.getY() - LABEL_Y_OFFSET,
				LABEL_COLOR);
			
			if(whitelistField.getValue().isBlank()
				&& !whitelistField.isFocused())
				context.drawString(font, "example.com, static.server",
					whitelistField.getX() + 4, whitelistField.getY() + 6,
					PLACEHOLDER_COLOR);
		}
	}
	
	@Override
	public void onClose()
	{
		minecraft.setScreen(parent);
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
