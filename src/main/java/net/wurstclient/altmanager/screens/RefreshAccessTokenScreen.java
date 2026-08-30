/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager.screens;

import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.altmanager.AltManager;
import net.wurstclient.altmanager.TokenAlt;

public final class RefreshAccessTokenScreen extends Screen
{
	private final Screen prevScreen;
	private final AltManager altManager;
	private final TokenAlt oldAlt;
	private final Consumer<TokenAlt> onRefreshed;
	
	private EditBox tokenBox;
	private String error = "";
	
	public RefreshAccessTokenScreen(Screen prevScreen, AltManager altManager,
		TokenAlt oldAlt, Consumer<TokenAlt> onRefreshed)
	{
		super(Component.literal("Refresh Access Token"));
		this.prevScreen = prevScreen;
		this.altManager = altManager;
		this.oldAlt = oldAlt;
		this.onRefreshed = onRefreshed;
	}
	
	@Override
	protected void init()
	{
		tokenBox = new EditBox(font, width / 2 - 150, height / 2 - 8, 300, 20,
			Component.literal("New Minecraft access token"));
		tokenBox.setMaxLength(8192);
		addWidget(tokenBox);
		setFocused(tokenBox);
		
		addRenderableWidget(Button
			.builder(Component.literal("Replace & Login"), b -> replaceToken())
			.bounds(width / 2 - 150, height / 2 + 20, 148, 20).build());
		addRenderableWidget(
			Button.builder(Component.literal("Cancel"), b -> onClose())
				.bounds(width / 2 + 2, height / 2 + 20, 148, 20).build());
	}
	
	private void replaceToken()
	{
		String token = tokenBox.getValue().trim();
		if(token.isEmpty())
		{
			error = "Paste a new Minecraft access token first.";
			return;
		}
		
		TokenAlt replacement = new TokenAlt(token, "", oldAlt.getName(),
			oldAlt.isFavorite(), oldAlt.getClientId());
		replacement.setProxyStorageId(oldAlt.getProxyStorageId());
		if(!altManager.replaceTokenAlt(oldAlt, replacement))
		{
			error = "This account is no longer in the Alt Manager.";
			return;
		}
		
		onRefreshed.accept(replacement);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		context.centeredText(font, "Refresh Access Token", width / 2,
			height / 2 - 54, CommonColors.WHITE);
		context.centeredText(font,
			"The stored access token was rejected (HTTP 401).", width / 2,
			height / 2 - 38, 0xFFFF5555);
		context.centeredText(font,
			"Paste a replacement token to keep this account and its settings.",
			width / 2, height / 2 - 24, CommonColors.LIGHT_GRAY);
		context.text(font, "New Minecraft access token", width / 2 - 150,
			height / 2 - 20, CommonColors.LIGHT_GRAY);
		if(!error.isBlank())
			context.centeredText(font, error, width / 2, height / 2 + 48,
				0xFFFF5555);
		
		tokenBox.extractRenderState(context, mouseX, mouseY, partialTicks);
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ENTER)
		{
			replaceToken();
			return true;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public void onClose()
	{
		minecraft.gui.setScreen(prevScreen);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
}
