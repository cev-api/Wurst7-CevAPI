/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager.screens;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.CommonColors;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.wurstclient.WurstClient;
import net.wurstclient.altmanager.AltRenderer;
import net.wurstclient.altmanager.NameGenerator;
import net.wurstclient.altmanager.SkinStealer;

public abstract class AltEditorScreen extends Screen
{
	private final Path skinFolder =
		WurstClient.INSTANCE.getWurstFolder().resolve("skins");
	
	protected final Screen prevScreen;
	
	private EditBox nameOrEmailBox;
	private EditBox passwordBox;
	
	private Button doneButton;
	private Button cancelButton;
	private Button randomNameButton;
	private Button stealSkinButton;
	private Button openSkinFolderButton;
	
	protected String message = "";
	private int errorTimer;
	
	public AltEditorScreen(Screen prevScreen, Component title)
	{
		super(title);
		this.prevScreen = prevScreen;
	}
	
	@Override
	public final void init()
	{
		nameOrEmailBox = new EditBox(font, width / 2 - 100,
			getNameOrEmailBoxY(), 200, 20, Component.literal(""));
		nameOrEmailBox.setMaxLength(4096);
		nameOrEmailBox.setFocused(true);
		nameOrEmailBox.setValue(getDefaultNameOrEmail());
		addWidget(nameOrEmailBox);
		
		passwordBox = new EditBox(font, width / 2 - 100, getPasswordBoxY(), 200,
			20, Component.literal(""));
		passwordBox.setValue(getDefaultPassword());
		passwordBox.addFormatter((text, startIndex) -> FormattedCharSequence
			.forward("*".repeat(text.length()), Style.EMPTY));
		passwordBox.setMaxLength(4096);
		addWidget(passwordBox);
		
		addRenderableWidget(doneButton = Button
			.builder(Component.literal(getDoneButtonText()),
				b -> pressDoneButton())
			.bounds(width / 2 - 100, getDoneButtonY(), 200, 20).build());
		
		addRenderableWidget(cancelButton =
			Button.builder(Component.literal("Cancel"), b -> onClose())
				.bounds(width / 2 - 100, getCancelButtonY(), 200, 20).build());
		
		addRenderableWidget(
			randomNameButton =
				Button
					.builder(Component.literal("Random Name"),
						b -> nameOrEmailBox
							.setValue(NameGenerator.generateName()))
					.bounds(width / 2 - 100, getRandomNameButtonY(), 200, 20)
					.build());
		
		addRenderableWidget(stealSkinButton = Button
			.builder(Component.literal("Steal Skin"),
				b -> message = stealSkin(getNameOrEmail()))
			.bounds(width - (width / 2 - 100) / 2 - 64, getBottomButtonsY(),
				128, 20)
			.build());
		
		addRenderableWidget(openSkinFolderButton = Button
			.builder(Component.literal("Open Skin Folder"),
				b -> openSkinFolder())
			.bounds((width / 2 - 100) / 2 - 64, getBottomButtonsY(), 128, 20)
			.build());
		
		addExtraWidgets();
		
		setFocused(nameOrEmailBox);
	}
	
	private void openSkinFolder()
	{
		createSkinFolder();
		Util.getPlatform().openFile(skinFolder.toFile());
	}
	
	private void createSkinFolder()
	{
		try
		{
			Files.createDirectories(skinFolder);
			
		}catch(IOException e)
		{
			e.printStackTrace();
			message = "\u00a74\u00a7lSkin folder could not be created.";
		}
	}
	
	@Override
	public final void tick()
	{
		String nameOrEmail = nameOrEmailBox.getValue().trim();
		doneButton.active =
			isDoneButtonActive(nameOrEmail, passwordBox.getValue().trim());
		doneButton.setMessage(Component.literal(getDoneButtonText()));
		onTick();
		applyDynamicLayout();
		
		randomNameButton.visible = shouldShowRandomNameButton();
		randomNameButton.active = randomNameButton.visible;
		
		boolean alex = nameOrEmail.equalsIgnoreCase("Alexander01998");
		stealSkinButton.visible = shouldShowStealSkinButton();
		stealSkinButton.active = stealSkinButton.visible && !alex;
		
		openSkinFolderButton.visible = shouldShowOpenSkinFolderButton();
		openSkinFolderButton.active = openSkinFolderButton.visible;
	}
	
	private void applyDynamicLayout()
	{
		nameOrEmailBox.setY(getNameOrEmailBoxY());
		passwordBox.setY(getPasswordBoxY());
		doneButton.setY(getDoneButtonY());
		randomNameButton.setY(getRandomNameButtonY());
		cancelButton.setY(getCancelButtonY());
		
		int bottomY = getBottomButtonsY();
		stealSkinButton.setY(bottomY);
		openSkinFolderButton.setY(bottomY);
	}
	
	protected boolean isDoneButtonActive(String nameOrEmail, String password)
	{
		boolean alex = nameOrEmail.equalsIgnoreCase("Alexander01998");
		return !nameOrEmail.isEmpty() && !(alex && password.isEmpty());
	}
	
	protected void onTick()
	{
		
	}
	
	protected void addExtraWidgets()
	{
		
	}
	
	protected boolean shouldShowRandomNameButton()
	{
		return true;
	}
	
	protected boolean shouldShowStealSkinButton()
	{
		return true;
	}
	
	protected boolean shouldShowOpenSkinFolderButton()
	{
		return true;
	}
	
	protected boolean shouldRenderSkinPreview()
	{
		return true;
	}
	
	protected String getSkinPreviewName()
	{
		return getNameOrEmail();
	}
	
	protected int getNameOrEmailBoxY()
	{
		return 60;
	}
	
	protected int getPasswordBoxY()
	{
		return 100;
	}
	
	protected int getDoneButtonY()
	{
		return height / 4 + 72 + 12;
	}
	
	protected int getRandomNameButtonY()
	{
		return height / 4 + 96 + 12;
	}
	
	protected int getCancelButtonY()
	{
		return height / 4 + 120 + 12;
	}
	
	protected int getBottomButtonsY()
	{
		return height - 32;
	}
	
	protected String getTopInfoLabel()
	{
		return "";
	}
	
	/**
	 * @return the user-entered name or email. Cannot be empty when pressing the
	 *         done button. Cannot be null.
	 */
	protected final String getNameOrEmail()
	{
		return nameOrEmailBox.getValue();
	}
	
	protected final void setNameOrEmail(String value)
	{
		nameOrEmailBox.setValue(value);
	}
	
	/**
	 * @return the user-entered password. Can be empty. Cannot be null.
	 */
	protected final String getPassword()
	{
		return passwordBox.getValue();
	}
	
	protected final void setPassword(String value)
	{
		passwordBox.setValue(value);
	}
	
	protected String getDefaultNameOrEmail()
	{
		return minecraft.getUser().getName();
	}
	
	protected String getDefaultPassword()
	{
		return "";
	}
	
	protected String getNameOrEmailLabelLine1()
	{
		return "Name (for cracked alts), or";
	}
	
	protected String getNameOrEmailLabelLine2()
	{
		return "E-Mail (for premium alts)";
	}
	
	protected String getPasswordLabel()
	{
		return "Password (for premium alts)";
	}
	
	protected String getAccountTypeLabel()
	{
		return getPassword().isEmpty() ? "cracked" : "premium";
	}
	
	protected abstract String getDoneButtonText();
	
	protected abstract void pressDoneButton();
	
	protected final void doErrorEffect()
	{
		errorTimer = 8;
	}
	
	private final String stealSkin(String name)
	{
		createSkinFolder();
		Path path = skinFolder.resolve(name + ".png");
		
		try
		{
			URL url = SkinStealer.getSkinUrl(name);
			
			try(InputStream in = url.openStream())
			{
				Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
			}
			
			return "\u00a7a\u00a7lSaved skin as " + name + ".png";
			
		}catch(IOException e)
		{
			e.printStackTrace();
			return "\u00a74\u00a7lSkin could not be saved.";
			
		}catch(NullPointerException e)
		{
			e.printStackTrace();
			return "\u00a74\u00a7lPlayer does not exist.";
		}
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ENTER)
			doneButton.onPress(context);
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		nameOrEmailBox.mouseClicked(context, doubleClick);
		passwordBox.mouseClicked(context, doubleClick);
		
		if(nameOrEmailBox.isFocused() || passwordBox.isFocused())
			message = "";
		
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_4)
		{
			onClose();
			return true;
		}
		
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		if(shouldRenderSkinPreview())
		{
			String previewName = getSkinPreviewName();
			
			AltRenderer.drawAltBack(context, previewName,
				(width / 2 - 100) / 2 - 64, height / 2 - 128, 128, 256);
			AltRenderer.drawAltBody(context, previewName,
				width - (width / 2 - 100) / 2 - 64, height / 2 - 128, 128, 256);
		}
		
		String topInfo = getTopInfoLabel();
		if(!topInfo.isEmpty())
			context.drawString(font, topInfo, width / 2 - 100,
				getNameOrEmailBoxY() - 58, CommonColors.LIGHT_GRAY);
		
		// text
		context.drawString(font, getNameOrEmailLabelLine1(), width / 2 - 100,
			getNameOrEmailBoxY() - 23, CommonColors.LIGHT_GRAY);
		context.drawString(font, getNameOrEmailLabelLine2(), width / 2 - 100,
			getNameOrEmailBoxY() - 13, CommonColors.LIGHT_GRAY);
		context.drawString(font, getPasswordLabel(), width / 2 - 100,
			getPasswordBoxY() - 13, CommonColors.LIGHT_GRAY);
		context.drawString(font, "Account type: " + getAccountTypeLabel(),
			width / 2 - 100, getPasswordBoxY() + 27, CommonColors.LIGHT_GRAY);
		
		String[] lines = message.split("\n");
		for(int i = 0; i < lines.length; i++)
			context.drawCenteredString(font, lines[i], width / 2,
				getPasswordBoxY() + 42 + 10 * i, CommonColors.WHITE);
		
		// text boxes
		nameOrEmailBox.render(context, mouseX, mouseY, partialTicks);
		passwordBox.render(context, mouseX, mouseY, partialTicks);
		
		// red flash for errors
		if(errorTimer > 0)
		{
			int alpha = (int)(Math.min(1, errorTimer / 16F) * 255);
			int color = 0xFF0000 | alpha << 24;
			context.fill(0, 0, width, height, color);
			errorTimer--;
		}
		
		for(Renderable drawable : renderables)
			drawable.render(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public final void onClose()
	{
		minecraft.setScreen(prevScreen);
	}
}
