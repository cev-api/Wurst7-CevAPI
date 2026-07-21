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
		this(prevScreen, LoginMode.PASSWORD);
	}
	
	private DirectLoginScreen(Screen prevScreen, LoginMode mode)
	{
		super(prevScreen, Component.literal("Direct Login"));
		this.mode = mode;
	}
	
	@Override
	protected String getDefaultNameOrEmail()
	{
		return mode == LoginMode.PASSWORD ? super.getDefaultNameOrEmail() : "";
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
			
			case COOKIE:
			return "Login with Cookies";
			
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
			
			case COOKIE:
			return !nameOrEmail.isEmpty();
			
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
			boolean showProfile = mode == LoginMode.TOKEN_REFRESH;
			profileNameBox.visible = showProfile;
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
		
		if(mode == LoginMode.COOKIE)
			setNameOrEmailMaxLength(131072);
		else
			setNameOrEmailMaxLength(4096);
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
			
			case COOKIE:
			return "Paste Netscape cookies here";
			
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
			
			case COOKIE:
			return "Export from browser in Netscape format";
			
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
			
			case COOKIE:
			return "";
			
			default:
			throw new IllegalStateException();
		}
	}
	
	@Override
	protected boolean shouldMaskPassword()
	{
		return mode != LoginMode.TOKEN_REFRESH && mode != LoginMode.COOKIE;
	}
	
	@Override
	protected boolean shouldShowPasswordInput()
	{
		return mode != LoginMode.COOKIE;
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
			
			case COOKIE:
			return "cookies";
			
			default:
			throw new IllegalStateException();
		}
	}
	
	@Override
	protected boolean shouldShowRandomNameButton()
	{
		return mode != LoginMode.TOKEN_REFRESH && mode != LoginMode.COOKIE;
	}
	
	@Override
	protected boolean shouldShowStealSkinButton()
	{
		return mode != LoginMode.TOKEN_REFRESH && mode != LoginMode.COOKIE;
	}
	
	@Override
	protected boolean shouldShowOpenSkinFolderButton()
	{
		return mode != LoginMode.TOKEN_REFRESH && mode != LoginMode.COOKIE;
	}
	
	@Override
	protected boolean shouldRenderSkinPreview()
	{
		if(mode == LoginMode.TOKEN_REFRESH)
			return !successfulProfileName.isEmpty();
		
		if(mode == LoginMode.COOKIE)
			return !successfulProfileName.isEmpty();
		
		return true;
	}
	
	@Override
	protected String getSkinPreviewName()
	{
		if(mode == LoginMode.TOKEN_REFRESH || mode == LoginMode.COOKIE)
			return successfulProfileName;
		
		return super.getSkinPreviewName();
	}
	
	@Override
	protected int getNameOrEmailBoxY()
	{
		if(mode == LoginMode.COOKIE)
			return 88;
		return mode == LoginMode.TOKEN_REFRESH ? 90 : 60;
	}
	
	@Override
	protected int getNameOrEmailBoxWidth()
	{
		return mode == LoginMode.COOKIE ? Math.min(720, width - 80) : 200;
	}
	
	@Override
	protected int getNameOrEmailBoxHeight()
	{
		return mode == LoginMode.COOKIE ? 240 : 20;
	}
	
	@Override
	protected boolean useMultiLineNameOrEmailInput()
	{
		return mode == LoginMode.COOKIE;
	}
	
	@Override
	protected boolean preserveTabsOnPaste()
	{
		return mode == LoginMode.COOKIE;
	}
	
	@Override
	protected int getPasswordBoxY()
	{
		if(mode == LoginMode.COOKIE)
			return 340;
		return mode == LoginMode.TOKEN_REFRESH ? 130 : 100;
	}
	
	@Override
	protected int getDoneButtonY()
	{
		if(mode == LoginMode.COOKIE)
			return getNameOrEmailBoxY() + getNameOrEmailBoxHeight() + 24;
		if(mode == LoginMode.TOKEN_REFRESH)
			return height / 4 + 108;
		return super.getDoneButtonY();
	}
	
	@Override
	protected int getRandomNameButtonY()
	{
		if(mode == LoginMode.COOKIE)
			return getDoneButtonY() + 24;
		if(mode == LoginMode.TOKEN_REFRESH)
			return height / 4 + 132;
		return super.getRandomNameButtonY();
	}
	
	@Override
	protected int getCancelButtonY()
	{
		if(mode == LoginMode.COOKIE)
			return getDoneButtonY() + 48;
		if(mode == LoginMode.TOKEN_REFRESH)
			return height / 4 + 156;
		return super.getCancelButtonY();
	}
	
	@Override
	protected String getTopInfoLabel()
	{
		if(mode == LoginMode.TOKEN_REFRESH)
			return "Profile (read-only, after successful login)";
		return "";
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
				
				case COOKIE:
				if(nameOrEmail.isEmpty())
				{
					message =
						"\u00a7c\u00a7lPlease paste your Netscape cookies first.";
					doErrorEffect();
					return;
				}
				MicrosoftLoginManager.loginWithCookies(nameOrEmail);
				successfulProfileName = minecraft.getUser().getName();
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
		minecraft.gui.setScreen(new TitleScreen());
	}
	
	private void toggleMode()
	{
		minecraft.gui.setScreen(new DirectLoginScreen(prevScreen, mode.next()));
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
			
			case COOKIE:
			return "Mode: Cookies";
			
			default:
			throw new IllegalStateException();
		}
	}
	
	private enum LoginMode
	{
		PASSWORD,
		TOKEN_REFRESH,
		COOKIE;
		
		private LoginMode next()
		{
			LoginMode[] modes = values();
			return modes[(ordinal() + 1) % modes.length];
		}
	}
}
