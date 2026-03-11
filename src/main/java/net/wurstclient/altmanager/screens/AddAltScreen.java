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
import net.wurstclient.altmanager.MojangAlt;
import net.wurstclient.altmanager.MicrosoftLoginManager;
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
	
	public AddAltScreen(Screen prevScreen, AltManager altManager)
	{
		super(prevScreen, Component.literal("New Alt"));
		this.altManager = altManager;
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
			profileNameBox.visible = mode == AddMode.TOKEN_REFRESH;
			profileNameBox.active = false;
			profileNameBox.setValue(verifiedProfileName);
			profileNameBox.setY(getProfileBoxY());
		}
		
		if(mode == AddMode.TOKEN_REFRESH && !isCurrentTokenStateVerified())
			verifiedProfileName = "";
	}
	
	@Override
	protected boolean isDoneButtonActive(String nameOrEmail, String password)
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return isCurrentTokenStateVerified() || !nameOrEmail.isEmpty()
				|| !password.isEmpty();
		
		return super.isDoneButtonActive(nameOrEmail, password);
	}
	
	@Override
	protected String getNameOrEmailLabelLine1()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return "Access token (optional)";
		
		return super.getNameOrEmailLabelLine1();
	}
	
	@Override
	protected String getNameOrEmailLabelLine2()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return "Leave blank if using refresh token";
		
		return super.getNameOrEmailLabelLine2();
	}
	
	@Override
	protected String getPasswordLabel()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return "Refresh token (optional)";
		
		return super.getPasswordLabel();
	}
	
	@Override
	protected String getAccountTypeLabel()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return "token / refresh token";
		
		return super.getAccountTypeLabel();
	}
	
	@Override
	protected boolean shouldShowRandomNameButton()
	{
		return mode != AddMode.TOKEN_REFRESH;
	}
	
	@Override
	protected boolean shouldShowStealSkinButton()
	{
		return mode != AddMode.TOKEN_REFRESH;
	}
	
	@Override
	protected boolean shouldShowOpenSkinFolderButton()
	{
		return mode != AddMode.TOKEN_REFRESH;
	}
	
	@Override
	protected boolean shouldRenderSkinPreview()
	{
		return mode != AddMode.TOKEN_REFRESH || !verifiedProfileName.isEmpty();
	}
	
	@Override
	protected String getSkinPreviewName()
	{
		if(mode == AddMode.TOKEN_REFRESH)
			return verifiedProfileName;
		
		return super.getSkinPreviewName();
	}
	
	@Override
	protected int getNameOrEmailBoxY()
	{
		return mode == AddMode.TOKEN_REFRESH ? 90 : 60;
	}
	
	@Override
	protected int getPasswordBoxY()
	{
		return mode == AddMode.TOKEN_REFRESH ? 130 : 100;
	}
	
	@Override
	protected int getDoneButtonY()
	{
		return mode == AddMode.TOKEN_REFRESH ? height / 4 + 108
			: super.getDoneButtonY();
	}
	
	@Override
	protected int getRandomNameButtonY()
	{
		return mode == AddMode.TOKEN_REFRESH ? height / 4 + 132
			: super.getRandomNameButtonY();
	}
	
	@Override
	protected int getCancelButtonY()
	{
		return mode == AddMode.TOKEN_REFRESH ? height / 4 + 156
			: super.getCancelButtonY();
	}
	
	@Override
	protected String getTopInfoLabel()
	{
		return mode == AddMode.TOKEN_REFRESH
			? "Profile (read-only, after successful login)" : "";
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
				minecraft.setScreen(prevScreen);
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
		
		if(password.isEmpty())
			altManager.add(new CrackedAlt(nameOrEmail));
		else
			altManager.add(new MojangAlt(nameOrEmail, password));
		
		minecraft.setScreen(prevScreen);
	}
	
	private void toggleMode()
	{
		mode = mode.next();
		message = "";
		
		if(mode == AddMode.PASSWORD)
			setNameOrEmail(minecraft.getUser().getName());
		else
			setNameOrEmail("");
		
		setPassword("");
		clearVerificationState();
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
	
	private boolean isCurrentTokenStateVerified()
	{
		return !verifiedProfileName.isEmpty()
			&& getNameOrEmail().trim().equals(verifiedToken)
			&& getPassword().trim().equals(verifiedRefreshToken);
	}
	
	private void clearVerificationState()
	{
		verifiedProfileName = "";
		verifiedToken = "";
		verifiedRefreshToken = "";
	}
	
	private enum AddMode
	{
		PASSWORD,
		TOKEN_REFRESH;
		
		private AddMode next()
		{
			AddMode[] modes = values();
			return modes[(ordinal() + 1) % modes.length];
		}
	}
}
