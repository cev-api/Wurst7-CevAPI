/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager.screens;

import java.net.URI;
import java.util.regex.Pattern;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Util;
import net.wurstclient.altmanager.AltManager;
import net.wurstclient.altmanager.AltRenderer;
import net.wurstclient.altmanager.LoginException;
import net.wurstclient.altmanager.MicrosoftLoginManager;
import net.wurstclient.altmanager.MinecraftProfile;
import net.wurstclient.altmanager.MinecraftServicesApi;
import net.wurstclient.altmanager.TokenAlt;

public final class EditTokenAltScreen extends Screen
{
	private static final Pattern NAME_PATTERN =
		Pattern.compile("^[A-Za-z0-9_]{3,16}$");
	
	private final Screen prevScreen;
	private final AltManager altManager;
	private final TokenAlt tokenAlt;
	
	private EditBox nameBox;
	private EditBox skinUrlBox;
	private Button renameButton;
	private Button skinModelButton;
	private Button applySkinButton;
	
	private volatile boolean busy;
	private volatile String message = "";
	private volatile boolean nameChangeProbeInProgress;
	private volatile Boolean nameChangeAllowed;
	private volatile String nameChangeStatus =
		"Checking name-change availability...";
	private int errorTimer;
	private MinecraftServicesApi.SkinVariant skinVariant =
		MinecraftServicesApi.SkinVariant.CLASSIC;
	private String previewName;
	private int skinRefreshBurstsRemaining;
	private long nextSkinRefreshTime;
	
	public EditTokenAltScreen(Screen prevScreen, AltManager altManager,
		TokenAlt tokenAlt)
	{
		super(Component.literal("Edit Token Alt"));
		this.prevScreen = prevScreen;
		this.altManager = altManager;
		this.tokenAlt = tokenAlt;
		previewName = tokenAlt.getName();
	}
	
	@Override
	protected void init()
	{
		nameBox = new EditBox(font, width / 2 - 100, 78, 200, 20,
			Component.literal(""));
		nameBox.setMaxLength(16);
		nameBox.setValue(tokenAlt.getName());
		addWidget(nameBox);
		
		skinUrlBox = new EditBox(font, width / 2 - 100, 126, 200, 20,
			Component.literal(""));
		skinUrlBox.setMaxLength(2048);
		addWidget(skinUrlBox);
		
		addRenderableWidget(renameButton =
			Button.builder(Component.literal("Change Name"), b -> startRename())
				.bounds(width / 2 - 100, 152, 200, 20).build());
		
		addRenderableWidget(skinModelButton = Button
			.builder(Component.literal(getSkinModelButtonText()),
				b -> skinVariant = skinVariant.next())
			.bounds(width / 2 - 100, 176, 98, 20).build());
		
		addRenderableWidget(applySkinButton = Button
			.builder(Component.literal("Apply Skin URL"),
				b -> startSkinChange())
			.bounds(width / 2 + 2, 176, 98, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(width / 2 - 100, 200, 200, 20).build());
		
		scheduleSkinRefreshBurst(previewName, 5);
		startNameChangeAvailabilityProbe();
		setFocused(nameBox);
	}
	
	@Override
	public void tick()
	{
		boolean renameBlocked = Boolean.FALSE.equals(nameChangeAllowed);
		boolean renameUnknown = nameChangeAllowed == null;
		boolean canRename = !busy && !nameChangeProbeInProgress
			&& !renameBlocked && !nameBox.getValue().trim().isEmpty();
		boolean canChangeSkin =
			!busy && !skinUrlBox.getValue().trim().isEmpty();
		
		nameBox.setEditable(!busy);
		skinUrlBox.setEditable(!busy);
		renameButton.active = canRename;
		renameButton
			.setMessage(Component.literal(getRenameButtonText(renameUnknown)));
		applySkinButton.active = canChangeSkin;
		skinModelButton.active = !busy;
		skinModelButton.setMessage(
			Component.literal(busy ? "Model: -" : getSkinModelButtonText()));
		
		if(skinRefreshBurstsRemaining > 0 && !previewName.isBlank()
			&& Util.getMillis() >= nextSkinRefreshTime)
		{
			AltRenderer.refreshSkin(previewName);
			skinRefreshBurstsRemaining--;
			nextSkinRefreshTime = Util.getMillis() + 900L;
		}
	}
	
	private void startRename()
	{
		if(busy)
			return;
		
		String newName = nameBox.getValue().trim();
		if(newName.isEmpty())
		{
			setErrorMessage("Name cannot be empty.");
			return;
		}
		
		if(!NAME_PATTERN.matcher(newName).matches())
		{
			setErrorMessage(
				"Invalid name format. Use 3-16 letters, numbers, or underscores.");
			return;
		}
		
		setBusyMessage(
			"\u00a7e\u00a7lChecking token and name-change limits...");
		
		Thread thread =
			new Thread(() -> runRename(newName), "Wurst Token Alt Rename");
		thread.setDaemon(true);
		thread.start();
	}
	
	private void runRename(String newName)
	{
		try
		{
			MinecraftProfile profile =
				MicrosoftLoginManager.authenticateTokenAltWithoutSession(
					tokenAlt.getToken(), tokenAlt.getRefreshToken());
			String accessToken = profile.getAccessToken();
			
			String precheckWarning = "";
			try
			{
				MinecraftServicesApi.NameChangeInfo info =
					MinecraftServicesApi.getNameChangeInfo(accessToken);
				
				if(!info.allowed())
				{
					String warning =
						"Name change is currently blocked by Mojang for this account.";
					if(!info.changedAt().isBlank())
						warning += " Last changed: " + info.changedAt();
					
					setWarningMessage(warning);
					return;
				}
				
			}catch(MinecraftServicesApi.ApiException e)
			{
				if(e.getStatusCode() != 404)
					precheckWarning =
						"Could not read name-change limit: " + e.getMessage();
			}
			
			MinecraftServicesApi.ProfileData updated =
				MinecraftServicesApi.changeName(accessToken, newName);
			String resolvedName =
				updated.name().isBlank() ? newName : updated.name();
			
			String warningCopy = precheckWarning;
			minecraft.execute(() -> {
				altManager.updateTokenAltName(tokenAlt, resolvedName);
				previewName = resolvedName;
				nameBox.setValue(resolvedName);
				scheduleSkinRefreshBurst(resolvedName, 3);
				
				message =
					"\u00a7a\u00a7lName changed successfully.\n\u00a7aCurrent profile: "
						+ resolvedName;
				if(!warningCopy.isBlank())
					message += "\n\u00a76\u00a7lWarning:\u00a76 " + warningCopy;
				
				startNameChangeAvailabilityProbe();
				busy = false;
			});
			
		}catch(LoginException e)
		{
			setErrorMessage(
				"Token verification failed: " + cleanMessage(e.getMessage()));
			
		}catch(MinecraftServicesApi.ApiException e)
		{
			setErrorMessage(cleanMessage(e.getMessage()));
		}
	}
	
	private void startSkinChange()
	{
		if(busy)
			return;
		
		String skinUrl = skinUrlBox.getValue().trim();
		if(skinUrl.isEmpty())
		{
			setErrorMessage("Skin URL cannot be empty.");
			return;
		}
		
		if(!isValidHttpUrl(skinUrl))
		{
			setErrorMessage("Skin URL must start with http:// or https://");
			return;
		}
		
		setBusyMessage("\u00a7e\u00a7lApplying skin URL...");
		MinecraftServicesApi.SkinVariant selectedVariant = skinVariant;
		
		Thread thread =
			new Thread(() -> runSkinChange(skinUrl, selectedVariant),
				"Wurst Token Alt Skin");
		thread.setDaemon(true);
		thread.start();
	}
	
	private void runSkinChange(String skinUrl,
		MinecraftServicesApi.SkinVariant variant)
	{
		try
		{
			MinecraftProfile profile =
				MicrosoftLoginManager.authenticateTokenAltWithoutSession(
					tokenAlt.getToken(), tokenAlt.getRefreshToken());
			String accessToken = profile.getAccessToken();
			
			MinecraftServicesApi.SkinChangeResult result = MinecraftServicesApi
				.changeSkinFromUrl(accessToken, skinUrl, variant);
			
			MinecraftServicesApi.ProfileData updated = result.profile();
			String resolvedName =
				updated.name().isBlank() ? profile.getName() : updated.name();
			String activeUrl = updated.activeSkinUrl();
			String activeVariant = updated.activeSkinVariant();
			
			minecraft.execute(() -> {
				if(!resolvedName.isBlank())
				{
					altManager.updateTokenAltName(tokenAlt, resolvedName);
					previewName = resolvedName;
					nameBox.setValue(resolvedName);
				}
				
				scheduleSkinRefreshBurst(previewName, 8);
				
				message = "\u00a7a\u00a7lSkin changed successfully.";
				
				if(!activeUrl.isBlank())
				{
					message += "\n\u00a7aActive skin URL: " + activeUrl;
					if(!activeVariant.isBlank())
						message += "\n\u00a7aModel: " + activeVariant;
					
					if(!activeUrl.equalsIgnoreCase(result.requestedUrl()))
						message +=
							"\n\u00a76Note: Mojang may re-host the skin URL internally.";
				}else
					message +=
						"\n\u00a76Warning: API accepted the request, but did not return an active skin URL.";
				
				busy = false;
			});
			
		}catch(LoginException e)
		{
			setErrorMessage(
				"Token verification failed: " + cleanMessage(e.getMessage()));
			
		}catch(MinecraftServicesApi.ApiException e)
		{
			setErrorMessage(cleanMessage(e.getMessage()));
		}
	}
	
	private boolean isValidHttpUrl(String skinUrl)
	{
		try
		{
			URI uri = URI.create(skinUrl);
			String scheme = uri.getScheme();
			return "http".equalsIgnoreCase(scheme)
				|| "https".equalsIgnoreCase(scheme);
			
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
	
	private void setBusyMessage(String busyMessage)
	{
		minecraft.execute(() -> {
			busy = true;
			message = busyMessage;
		});
	}
	
	private void setWarningMessage(String warningMessage)
	{
		minecraft.execute(() -> {
			busy = false;
			message = "\u00a76\u00a7lWarning:\u00a76 " + warningMessage;
		});
	}
	
	private void setErrorMessage(String errorMessage)
	{
		minecraft.execute(() -> {
			busy = false;
			message = "\u00a74\u00a7lError:\u00a7c " + errorMessage;
			errorTimer = 8;
		});
	}
	
	private String cleanMessage(String raw)
	{
		if(raw == null || raw.isBlank())
			return "Unknown error.";
		
		return raw.replace('\n', ' ').replace('\r', ' ').trim();
	}
	
	private String getSkinModelButtonText()
	{
		return "Model: " + skinVariant.getLabel();
	}
	
	private String getRenameButtonText(boolean renameUnknown)
	{
		if(nameChangeProbeInProgress)
			return "Change Name (Checking...)";
		
		if(Boolean.FALSE.equals(nameChangeAllowed))
			return "Change Name (Unavailable)";
		
		if(renameUnknown)
			return "Change Name";
		
		return "Change Name";
	}
	
	private void startNameChangeAvailabilityProbe()
	{
		if(nameChangeProbeInProgress)
			return;
		
		nameChangeProbeInProgress = true;
		nameChangeAllowed = null;
		nameChangeStatus = "Checking name-change availability...";
		
		Thread thread = new Thread(() -> {
			try
			{
				MinecraftProfile profile =
					MicrosoftLoginManager.authenticateTokenAltWithoutSession(
						tokenAlt.getToken(), tokenAlt.getRefreshToken());
				
				MinecraftServicesApi.NameChangeInfo info = MinecraftServicesApi
					.getNameChangeInfo(profile.getAccessToken());
				
				minecraft.execute(() -> {
					nameChangeProbeInProgress = false;
					nameChangeAllowed = info.allowed();
					
					if(info.allowed())
						nameChangeStatus =
							"\u00a7a\u00a7lName change available.";
					else
					{
						nameChangeStatus =
							"\u00a76\u00a7lName change unavailable for this account.";
						if(!info.changedAt().isBlank())
							nameChangeStatus +=
								" Last changed: " + info.changedAt();
					}
				});
				
			}catch(LoginException e)
			{
				minecraft.execute(() -> {
					nameChangeProbeInProgress = false;
					nameChangeAllowed = false;
					nameChangeStatus =
						"\u00a74\u00a7lCan't verify account token.";
				});
				
			}catch(MinecraftServicesApi.ApiException e)
			{
				minecraft.execute(() -> {
					nameChangeProbeInProgress = false;
					nameChangeAllowed = null;
					nameChangeStatus = "\u00a76Name-change check unavailable: "
						+ cleanMessage(e.getMessage());
				});
			}
		}, "Wurst Name Change Probe");
		
		thread.setDaemon(true);
		thread.start();
	}
	
	private void scheduleSkinRefreshBurst(String name, int attempts)
	{
		if(name == null || name.isBlank() || attempts <= 0)
			return;
		
		AltRenderer.refreshSkin(name);
		skinRefreshBurstsRemaining = attempts - 1;
		nextSkinRefreshTime = Util.getMillis() + 900L;
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ENTER && !busy)
		{
			if(skinUrlBox.isFocused())
				startSkinChange();
			else
				startRename();
			
			return true;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		nameBox.mouseClicked(context, doubleClick);
		skinUrlBox.mouseClicked(context, doubleClick);
		
		if(nameBox.isFocused() || skinUrlBox.isFocused())
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
		if(!previewName.isBlank())
		{
			AltRenderer.drawAltBack(context, previewName,
				(width / 2 - 100) / 2 - 64, height / 2 - 128, 128, 256);
			AltRenderer.drawAltBody(context, previewName,
				width - (width / 2 - 100) / 2 - 64, height / 2 - 128, 128, 256);
		}
		
		context.drawCenteredString(font, "Edit Token Account", width / 2, 12,
			CommonColors.WHITE);
		context.drawCenteredString(font,
			previewName.isBlank() ? "Current profile: unknown"
				: "Current profile: " + previewName,
			width / 2, 24, CommonColors.LIGHT_GRAY);
		
		context.drawString(font, "New account name", width / 2 - 100, 66,
			CommonColors.LIGHT_GRAY);
		context.drawString(font, "Skin URL (http/https)", width / 2 - 100, 114,
			CommonColors.LIGHT_GRAY);
		context.drawCenteredString(font, nameChangeStatus, width / 2, 226,
			CommonColors.LIGHT_GRAY);
		
		String[] lines = message.split("\n");
		for(int i = 0; i < lines.length; i++)
			context.drawCenteredString(font, lines[i], width / 2, 238 + i * 10,
				CommonColors.WHITE);
		
		if(!previewName.isBlank() && (skinRefreshBurstsRemaining > 0
			|| AltRenderer.isSkinLoading(previewName)))
		{
			String dots =
				".".repeat(Math.max(1, (int)(Util.getMillis() / 350L % 3) + 1));
			context.drawCenteredString(font, "Loading skin preview" + dots,
				width / 2, height - 12, 0xFFFFAA);
		}
		
		nameBox.render(context, mouseX, mouseY, partialTicks);
		skinUrlBox.render(context, mouseX, mouseY, partialTicks);
		
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
	public void onClose()
	{
		if(busy)
			return;
		
		minecraft.setScreen(prevScreen);
	}
}
