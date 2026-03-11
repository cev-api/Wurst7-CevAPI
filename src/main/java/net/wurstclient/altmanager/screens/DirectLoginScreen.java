/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager.screens;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.wurstclient.altmanager.LoginException;
import net.wurstclient.altmanager.LoginManager;
import net.wurstclient.altmanager.MicrosoftLoginManager;

public final class DirectLoginScreen extends AltEditorScreen
{
	private LoginMode mode = LoginMode.PASSWORD;
	private Button modeButton;
	private EditBox profileNameBox;
	private String successfulProfileName = "";
	private String lastTokenInput = "";
	private String lastRefreshInput = "";
	
	public DirectLoginScreen(Screen prevScreen)
	{
		super(prevScreen, Component.literal("Direct Login"));
	}
	
	@Override
	protected String getDoneButtonText()
	{
		switch(mode)
		{
			case PASSWORD:
			return getPassword().isEmpty() ? "Change Cracked Name"
				: "Login with Password";
			
			case TOKEN_REFRESH:
			return "Login with Token/Refresh";
			
			default:
			throw new IllegalStateException();
		}
	}
	
	@Override
	protected boolean isDoneButtonActive(String nameOrEmail, String password)
	{
		switch(mode)
		{
			case PASSWORD:
			return super.isDoneButtonActive(nameOrEmail, password);
			
			case TOKEN_REFRESH:
			return !nameOrEmail.isEmpty() || !password.isEmpty();
			
			default:
			throw new IllegalStateException();
		}
	}
	
	@Override
	protected void addExtraWidgets()
	{
		modeButton = addRenderableWidget(Button
			.builder(Component.literal(getModeButtonText()), b -> toggleMode())
			.bounds(width / 2 - 100, 8, 200, 20).build());
		
		profileNameBox = addRenderableWidget(new EditBox(font, width / 2 - 100,
			getProfileBoxY(), 200, 20, Component.literal("")));
		profileNameBox.setEditable(false);
		profileNameBox.setMaxLength(64);
		profileNameBox.setValue("");
	}
	
	@Override
	protected void onTick()
	{
		if(modeButton != null)
			modeButton.setMessage(Component.literal(getModeButtonText()));
		
		if(profileNameBox != null)
		{
			profileNameBox.visible = mode == LoginMode.TOKEN_REFRESH;
			profileNameBox.active = false;
			profileNameBox.setValue(successfulProfileName);
			profileNameBox.setY(getProfileBoxY());
		}
		
		if(mode == LoginMode.TOKEN_REFRESH)
		{
			String currentToken = getNameOrEmail().trim();
			String currentRefresh = getPassword().trim();
			if(!currentToken.equals(lastTokenInput)
				|| !currentRefresh.equals(lastRefreshInput))
				successfulProfileName = "";
		}
	}
	
	@Override
	protected String getNameOrEmailLabelLine1()
	{
		switch(mode)
		{
			case PASSWORD:
			return "Name (for cracked alts), or";
			
			case TOKEN_REFRESH:
			return "Access token (optional)";
			
			default:
			throw new IllegalStateException();
		}
	}
	
	@Override
	protected String getNameOrEmailLabelLine2()
	{
		switch(mode)
		{
			case PASSWORD:
			return "E-Mail (for premium alts)";
			
			case TOKEN_REFRESH:
			return "Leave blank if using refresh token";
			
			default:
			throw new IllegalStateException();
		}
	}
	
	@Override
	protected String getPasswordLabel()
	{
		switch(mode)
		{
			case PASSWORD:
			return "Password (for premium alts)";
			
			case TOKEN_REFRESH:
			return "Refresh token (optional)";
			
			default:
			throw new IllegalStateException();
		}
	}
	
	@Override
	protected String getAccountTypeLabel()
	{
		switch(mode)
		{
			case PASSWORD:
			return super.getAccountTypeLabel();
			
			case TOKEN_REFRESH:
			return "token / refresh token";
			
			default:
			throw new IllegalStateException();
		}
	}
	
	@Override
	protected boolean shouldShowRandomNameButton()
	{
		return mode != LoginMode.TOKEN_REFRESH;
	}
	
	@Override
	protected boolean shouldShowStealSkinButton()
	{
		return mode != LoginMode.TOKEN_REFRESH;
	}
	
	@Override
	protected boolean shouldShowOpenSkinFolderButton()
	{
		return mode != LoginMode.TOKEN_REFRESH;
	}
	
	@Override
	protected boolean shouldRenderSkinPreview()
	{
		return mode != LoginMode.TOKEN_REFRESH
			|| !successfulProfileName.isEmpty();
	}
	
	@Override
	protected String getSkinPreviewName()
	{
		if(mode == LoginMode.TOKEN_REFRESH)
			return successfulProfileName;
		
		return super.getSkinPreviewName();
	}
	
	@Override
	protected int getNameOrEmailBoxY()
	{
		return mode == LoginMode.TOKEN_REFRESH ? 90 : 60;
	}
	
	@Override
	protected int getPasswordBoxY()
	{
		return mode == LoginMode.TOKEN_REFRESH ? 130 : 100;
	}
	
	@Override
	protected int getDoneButtonY()
	{
		return mode == LoginMode.TOKEN_REFRESH ? height / 4 + 108
			: super.getDoneButtonY();
	}
	
	@Override
	protected int getRandomNameButtonY()
	{
		return mode == LoginMode.TOKEN_REFRESH ? height / 4 + 132
			: super.getRandomNameButtonY();
	}
	
	@Override
	protected int getCancelButtonY()
	{
		return mode == LoginMode.TOKEN_REFRESH ? height / 4 + 156
			: super.getCancelButtonY();
	}
	
	@Override
	protected String getTopInfoLabel()
	{
		return mode == LoginMode.TOKEN_REFRESH
			? "Profile (read-only, after successful login)" : "";
	}
	
	@Override
	protected void pressDoneButton()
	{
		String nameOrEmail = getNameOrEmail().trim();
		String password = getPassword().trim();
		
		try
		{
			switch(mode)
			{
				case PASSWORD:
				if(password.isEmpty())
					LoginManager.changeCrackedName(nameOrEmail);
				else
					MicrosoftLoginManager.login(nameOrEmail, password);
				break;
				
				case TOKEN_REFRESH:
				if(!password.isEmpty())
					MicrosoftLoginManager.loginWithRefreshToken(password);
				else
					MicrosoftLoginManager.loginWithToken(nameOrEmail);
				
				successfulProfileName = minecraft.getUser().getName();
				lastTokenInput = nameOrEmail;
				lastRefreshInput = password;
				message = "\u00a7a\u00a7lLogin successful as "
					+ successfulProfileName + ".";
				return;
				
				default:
				throw new IllegalStateException();
			}
			
		}catch(LoginException e)
		{
			message = "\u00a7c\u00a7lMicrosoft:\u00a7c " + e.getMessage();
			doErrorEffect();
			return;
		}
		
		message = "\u00a7a\u00a7lLogin successful.";
		minecraft.setScreen(new TitleScreen());
	}
	
	private void toggleMode()
	{
		mode = mode.next();
		message = "";
		
		if(mode == LoginMode.PASSWORD)
		{
			setNameOrEmail(minecraft.getUser().getName());
			setPassword("");
			successfulProfileName = "";
			lastTokenInput = "";
			lastRefreshInput = "";
		}else
		{
			setNameOrEmail("");
			setPassword("");
			successfulProfileName = "";
			lastTokenInput = "";
			lastRefreshInput = "";
		}
	}
	
	private int getProfileBoxY()
	{
		return getNameOrEmailBoxY() - 46;
	}
	
	private String getModeButtonText()
	{
		switch(mode)
		{
			case PASSWORD:
			return "Mode: Password / Cracked";
			
			case TOKEN_REFRESH:
			return "Mode: Token / Refresh";
			
			default:
			throw new IllegalStateException();
		}
	}
	
	private enum LoginMode
	{
		PASSWORD,
		TOKEN_REFRESH;
		
		private LoginMode next()
		{
			LoginMode[] modes = values();
			return modes[(ordinal() + 1) % modes.length];
		}
	}
}
