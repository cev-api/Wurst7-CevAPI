/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager.screens;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.wurstclient.altmanager.Alt;
import net.wurstclient.altmanager.AltManager;
import net.wurstclient.altmanager.MojangAlt;

import net.wurstclient.WurstClient;
import net.wurstclient.proxy.ProxyManagerScreen;
import net.wurstclient.proxy.SocksProxy;

public final class EditAltScreen extends AltEditorScreen
{
	private final AltManager altManager;
	private Alt editedAlt;
	private Button proxyButton;
	
	public EditAltScreen(Screen prevScreen, AltManager altManager,
		Alt editedAlt)
	{
		super(prevScreen, Component.literal("Edit Alt"));
		this.altManager = altManager;
		this.editedAlt = editedAlt;
	}
	
	@Override
	protected String getDefaultNameOrEmail()
	{
		return editedAlt instanceof MojangAlt
			? ((MojangAlt)editedAlt).getEmail() : editedAlt.getName();
	}
	
	@Override
	protected String getDefaultPassword()
	{
		return editedAlt instanceof MojangAlt
			? ((MojangAlt)editedAlt).getPassword() : "";
	}
	
	@Override
	protected String getDoneButtonText()
	{
		return "Save";
	}
	
	@Override
	protected void addExtraWidgets()
	{
		addRenderableWidget(proxyButton = Button
			.builder(getProxyButtonText(), b -> openProxyManager())
			.bounds(width / 2 - 100, getCancelButtonY() + 24, 98, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Copy Credentials"),
				b -> copyCredentials())
			.bounds(width / 2 + 2, getCancelButtonY() + 24, 98, 20).build());
	}
	
	private Component getProxyButtonText()
	{
		SocksProxy proxy = altManager.getProxyAssociation(editedAlt);
		String name = proxy == null
			? (altManager.hasProxyAssociation(editedAlt) ? "Missing" : "None")
			: proxy.getDisplayName();
		return Component.literal("Proxy: " + name);
	}
	
	private void openProxyManager()
	{
		minecraft.gui.setScreen(new ProxyManagerScreen(this,
			WurstClient.INSTANCE.getProxyManager(), proxy -> {
				altManager.setProxyAssociation(editedAlt, proxy);
				proxyButton.setMessage(getProxyButtonText());
			}));
	}
	
	private void copyCredentials()
	{
		String credentials = getNameOrEmail().trim();
		String password = getPassword();
		if(!password.isEmpty())
			credentials += ":" + password;
		minecraft.keyboardHandler.setClipboard(credentials);
		message = "Credentials copied to clipboard.";
	}
	
	@Override
	protected void pressDoneButton()
	{
		altManager.edit(editedAlt, getNameOrEmail(), getPassword());
		minecraft.gui.setScreen(prevScreen);
	}
}
