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
import net.minecraft.network.chat.Component;
import net.wurstclient.altmanager.AltManager;
import net.wurstclient.altmanager.CrackedAlt;
import net.wurstclient.altmanager.LoginException;
import net.wurstclient.altmanager.MicrosoftLoginManager;
import net.wurstclient.altmanager.MojangAlt;
import net.wurstclient.altmanager.MicrosoftLoginManager.CookieAuthResult;
import net.wurstclient.altmanager.TokenAlt;

public final class AddAltScreen extends AltEditorScreen
{
	private final AltManager altManager;
	private AddMode mode = AddMode.PASSWORD;
	private Button modeButton;
	private EditBox profileNameBox;
	private String verifiedProfileName = "";
	private String verifiedToken = "";
	private String verifiedRefreshToken = "";
	private String verifiedCookieRefreshToken = "";
	
	public AddAltScreen(Screen prevScreen, AltManager altManager)
	{
		this(prevScreen, altManager, AddMode.PASSWORD);
	}
	
	private AddAltScreen(Screen prevScreen, AltManager altManager, AddMode mode)
	{
		super(prevScreen, Component.literal("New Alt"));
		this.altManager = altManager;
		this.mode = mode;
	}
	
	@Override
	protected String getDefaultNameOrEmail()
	{
		return mode == AddMode.PASSWORD ? super.getDefaultNameOrEmail() : "";
	}
	
	@Override
	protected String getDoneButtonText()
	{
		switch(mode)
		{
			case PASSWORD:
			return getPassword().isEmpty() ? "Add Cracked Alt"
				: "Add Premium Alt";
			
			case TOKEN_REFRESH:
			return isCurrentTokenStateVerified() ? "Add Token Alt"
				: "Verify Token Login";
			
			case COOKIE:
			return isCookieStateVerified() ? "Add Token Alt"
				: "Verify Cookies & Extract Token";
			
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
			boolean showProfile = mode == AddMode.TOKEN_REFRESH;
			profileNameBox.visible = showProfile;
			profileNameBox.active = false;
			profileNameBox.setValue(verifiedProfileName);
			profileNameBox.setY(getProfileBoxY());
		}
		
		if(mode == AddMode.COOKIE)
			setNameOrEmailMaxLength(131072);
		else
			setNameOrEmailMaxLength(4096);
		
		if(mode == AddMode.TOKEN_REFRESH && !isCurrentTokenStateVerified())
			verifiedProfileName = "";
		
		if(mode == AddMode.COOKIE && !isCookieStateVerified())
			verifiedProfileName = "";
	}
	
	@Override
	protected boolean isDoneButtonActive(String nameOrEmail, String password)
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return isCurrentTokenStateVerified() || !nameOrEmail.isEmpty()
				|| !password.isEmpty();
		
		if(mode == AddMode.COOKIE)
			return isCookieStateVerified() || hasCookieInput();
		
		return super.isDoneButtonActive(nameOrEmail, password);
	}
	
	@Override
	protected String getNameOrEmailLabelLine1()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return "Access token (optional)";
		
		if(mode == AddMode.COOKIE)
			return "Paste Netscape cookies here";
		
		return super.getNameOrEmailLabelLine1();
	}
	
	@Override
	protected String getNameOrEmailLabelLine2()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return "Leave blank if using refresh token";
		
		if(mode == AddMode.COOKIE)
			return "Export from browser in Netscape format";
		
		return super.getNameOrEmailLabelLine2();
	}
	
	@Override
	protected String getPasswordLabel()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return "Refresh token (optional)";
		
		if(mode == AddMode.COOKIE)
			return "";
		
		return super.getPasswordLabel();
	}
	
	@Override
	protected boolean shouldMaskPassword()
	{
		return mode != AddMode.TOKEN_REFRESH && mode != AddMode.COOKIE;
	}
	
	@Override
	protected boolean shouldShowPasswordInput()
	{
		return mode != AddMode.COOKIE;
	}
	
	@Override
	protected String getAccountTypeLabel()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return "token / refresh token";
		
		if(mode == AddMode.COOKIE)
			return "cookies";
		
		return super.getAccountTypeLabel();
	}
	
	@Override
	protected boolean shouldShowRandomNameButton()
	{
		return mode != AddMode.TOKEN_REFRESH && mode != AddMode.COOKIE;
	}
	
	@Override
	protected boolean shouldShowStealSkinButton()
	{
		return mode != AddMode.TOKEN_REFRESH && mode != AddMode.COOKIE;
	}
	
	@Override
	protected boolean shouldShowOpenSkinFolderButton()
	{
		return mode != AddMode.TOKEN_REFRESH && mode != AddMode.COOKIE;
	}
	
	@Override
	protected boolean shouldRenderSkinPreview()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return !verifiedProfileName.isEmpty();
		
		if(mode == AddMode.COOKIE)
			return !verifiedProfileName.isEmpty();
		
		return super.shouldRenderSkinPreview();
	}
	
	@Override
	protected String getSkinPreviewName()
	{
		if(mode == AddMode.TOKEN_REFRESH || mode == AddMode.COOKIE)
			return verifiedProfileName;
		
		return super.getSkinPreviewName();
	}
	
	@Override
	protected int getNameOrEmailBoxY()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return 90;
		
		if(mode == AddMode.COOKIE)
			return 88;
		
		return super.getNameOrEmailBoxY();
	}
	
	@Override
	protected int getPasswordBoxY()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return 130;
		
		if(mode == AddMode.COOKIE)
			return 340;
		
		return super.getPasswordBoxY();
	}
	
	@Override
	protected int getNameOrEmailBoxWidth()
	{
		// Keep the cookie form centered between the two skin previews.
		return mode == AddMode.COOKIE ? Math.min(420, width - 80) : 200;
	}
	
	@Override
	protected int getNameOrEmailBoxHeight()
	{
		return mode == AddMode.COOKIE ? 200 : 20;
	}
	
	@Override
	protected boolean useMultiLineNameOrEmailInput()
	{
		return mode == AddMode.COOKIE;
	}
	
	@Override
	protected boolean preserveTabsOnPaste()
	{
		return mode == AddMode.COOKIE;
	}
	
	@Override
	protected int getDoneButtonY()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return height / 4 + 108;
		
		if(mode == AddMode.COOKIE)
			return getNameOrEmailBoxY() + getNameOrEmailBoxHeight() + 24;
		
		return super.getDoneButtonY();
	}
	
	@Override
	protected int getRandomNameButtonY()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return height / 4 + 132;
		
		if(mode == AddMode.COOKIE)
			return getDoneButtonY() + 24;
		
		return super.getRandomNameButtonY();
	}
	
	@Override
	protected int getCancelButtonY()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return height / 4 + 156;
		
		if(mode == AddMode.COOKIE)
			return getDoneButtonY() + 48;
		
		return super.getCancelButtonY();
	}
	
	@Override
	protected String getTopInfoLabel()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return "Profile (read-only, after successful login)";
		
		return "";
	}
	
	@Override
	protected void pressDoneButton()
	{
		String nameOrEmail = getNameOrEmail().trim();
		String password = getPassword().trim();
		
		if(mode == AddMode.TOKEN_REFRESH)
		{
			if(isCurrentTokenStateVerified())
			{
				altManager.add(new TokenAlt(verifiedToken, verifiedRefreshToken,
					verifiedProfileName, false));
				minecraft.gui.setScreen(prevScreen);
				return;
			}
			
			try
			{
				if(!password.isEmpty())
					MicrosoftLoginManager.loginWithRefreshToken(password);
				else
					MicrosoftLoginManager.loginWithToken(nameOrEmail);
				
				verifiedProfileName = minecraft.getUser().getName();
				verifiedToken = nameOrEmail;
				verifiedRefreshToken = password;
				message = "\u00a7a\u00a7lLogin successful as "
					+ verifiedProfileName + ". Click again to add.";
				return;
				
			}catch(LoginException e)
			{
				message = "\u00a7c\u00a7lMicrosoft:\u00a7c " + e.getMessage();
				doErrorEffect();
				return;
			}
		}
		
		if(mode == AddMode.COOKIE)
		{
			if(isCookieStateVerified())
			{
				altManager.add(new TokenAlt("", verifiedCookieRefreshToken,
					verifiedProfileName, false));
				minecraft.gui.setScreen(prevScreen);
				return;
			}
			
			String cookieText = getCookieText();
			if(cookieText.isEmpty())
			{
				message =
					"\u00a7c\u00a7lPlease paste your Netscape cookies first.";
				doErrorEffect();
				return;
			}
			
			try
			{
				CookieAuthResult result =
					MicrosoftLoginManager.authenticateCookies(cookieText);
				
				verifiedProfileName = result.getProfile().getName();
				verifiedCookieRefreshToken = result.getRefreshToken();
				message = "\u00a7a\u00a7lVerified as " + verifiedProfileName
					+ ". Click again to add.";
				return;
				
			}catch(LoginException e)
			{
				message = "\u00a7c\u00a7lCookie auth:\u00a7c " + e.getMessage();
				doErrorEffect();
				return;
			}
		}
		
		if(password.isEmpty())
			altManager.add(new CrackedAlt(nameOrEmail));
		else
			altManager.add(new MojangAlt(nameOrEmail, password));
		
		minecraft.gui.setScreen(prevScreen);
	}
	
	private void toggleMode()
	{
		minecraft.gui
			.setScreen(new AddAltScreen(prevScreen, altManager, mode.next()));
	}
	
	private int getProfileBoxY()
	{
		if(mode == AddMode.COOKIE)
			return 326;
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
	
	private boolean isCurrentTokenStateVerified()
	{
		return !verifiedProfileName.isEmpty()
			&& getNameOrEmail().trim().equals(verifiedToken)
			&& getPassword().trim().equals(verifiedRefreshToken);
	}
	
	private boolean isCookieStateVerified()
	{
		return !verifiedProfileName.isEmpty()
			&& !verifiedCookieRefreshToken.isEmpty();
	}
	
	private boolean hasCookieInput()
	{
		return !getNameOrEmail().trim().isEmpty();
	}
	
	private String getCookieText()
	{
		return getNameOrEmail().trim();
	}
	
	private void clearVerificationState()
	{
		verifiedProfileName = "";
		verifiedToken = "";
		verifiedRefreshToken = "";
		verifiedCookieRefreshToken = "";
	}
	
	private enum AddMode
	{
		PASSWORD,
		TOKEN_REFRESH,
		COOKIE;
		
		private AddMode next()
		{
			AddMode[] modes = values();
			return modes[(ordinal() + 1) % modes.length];
		}
	}
}
