/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.wurstclient.WurstClient;
import net.cevapi.config.AntiFingerprintConfigScreen;
import net.cevapi.security.ResourcePackProtector;
import net.cevapi.config.AntiFingerprintConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import net.wurstclient.mixinterface.IMultiplayerMultiSelect;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.altmanager.screens.AltManagerScreen;
import net.wurstclient.serverfinder.CleanUpScreen;
import net.wurstclient.serverfinder.ServerFinderScreen;
import net.wurstclient.util.LastServerRememberer;

@Mixin(JoinMultiplayerScreen.class)
public class JoinMultiplayerScreenMixin extends Screen
	implements IMultiplayerMultiSelect
{
	private static final int TOP_ROW_BUTTON_WIDTH = 100;
	private static final int TOP_ROW_BUTTON_SPACING = 4;
	private static final int BOTTOM_ROW_BUTTON_WIDTH = 74;
	private static final int BOTTOM_ROW_BUTTON_SPACING = 4;
	
	@Shadow
	protected ServerSelectionList serverSelectionList;
	@Shadow
	private ServerList servers;
	@Shadow
	private Button editButton;
	@Shadow
	private Button selectButton;
	@Shadow
	private Button deleteButton;
	
	@Shadow
	protected native void onSelectedChange();
	
	private Button lastServerButton;
	@Unique
	private Button antiFingerprintButton;
	@Unique
	private Button cornerServerFinderButton;
	@Unique
	private Button cornerCleanUpButton;
	@Unique
	private Button cornerAltManagerButton;
	@Unique
	private Button bypassResourcePackButton;
	@Unique
	private Button forceDenyResourcePackButton;
	@Unique
	private final Set<ServerData> wurst$multiSelectedServers =
		new LinkedHashSet<>();
	@Unique
	private ServerData wurst$selectionAnchor;
	
	private JoinMultiplayerScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	@Inject(method = "init()V", at = @At("HEAD"))
	private void beforeVanillaButtons(CallbackInfo ci)
	{
		antiFingerprintButton = null;
		cornerServerFinderButton = null;
		cornerCleanUpButton = null;
		cornerAltManagerButton = null;
		bypassResourcePackButton = null;
		forceDenyResourcePackButton = null;
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		JoinMultiplayerScreen mpScreen = (JoinMultiplayerScreen)(Object)this;
		
		// Add Last Server button early for better tab navigation
		lastServerButton = Button
			.builder(Component.nullToEmpty("Last Server"),
				b -> LastServerRememberer.joinLastServer(mpScreen))
			.width(100).build();
		addRenderableWidget(lastServerButton);
	}
	
	@Inject(method = "repositionElements()V", at = @At("TAIL"))
	private void onRefreshWidgetPositions(CallbackInfo ci)
	{
		updateLastServerButton();
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(NiceWurstModule.showAltManager())
		{
			if(cornerAltManagerButton == null)
			{
				cornerAltManagerButton = Button
					.builder(Component.literal("Alt Manager"),
						b -> minecraft.setScreen(new AltManagerScreen(
							(JoinMultiplayerScreen)(Object)this,
							WurstClient.INSTANCE.getAltManager())))
					.bounds(0, 0, 100, 20).build();
				addRenderableWidget(cornerAltManagerButton);
			}
			
			cornerAltManagerButton.setX(6);
			cornerAltManagerButton.setY(6);
			cornerAltManagerButton.setWidth(100);
			cornerAltManagerButton.visible = true;
		}else if(cornerAltManagerButton != null)
		{
			cornerAltManagerButton.visible = false;
		}
		
		boolean showAntiFingerprintButton = NiceWurstModule
			.showAntiFingerprintControls()
			&& ResourcePackProtector.getConfig().shouldShowMultiplayerButton();
		
		if(showAntiFingerprintButton)
		{
			if(antiFingerprintButton == null)
			{
				antiFingerprintButton = Button.builder(
					Component.literal("Anti-Fingerprint"),
					b -> minecraft.setScreen(new AntiFingerprintConfigScreen(
						(JoinMultiplayerScreen)(Object)this)))
					.bounds(0, 0, 100, 20).build();
				addRenderableWidget(antiFingerprintButton);
			}
			
			antiFingerprintButton.setX(width / 2 + 54);
			antiFingerprintButton.setY(10);
			antiFingerprintButton.setWidth(100);
			antiFingerprintButton.visible = true;
		}else if(antiFingerprintButton != null)
		{
			antiFingerprintButton.visible = false;
		}
		
		AntiFingerprintConfig config = ResourcePackProtector.getConfig();
		boolean showResourcePackButtons =
			config.shouldShowResourcePackBypassButtons();
		if(showResourcePackButtons)
		{
			if(bypassResourcePackButton == null)
			{
				bypassResourcePackButton =
					Button.builder(getBypassResourcePackLabel(), b -> {
						config.getBypassResourcePackSetting()
							.setChecked(!config.shouldBypassResourcePack());
						b.setMessage(getBypassResourcePackLabel());
					}).bounds(0, 0, 200, 20).build();
				addRenderableWidget(bypassResourcePackButton);
			}
			if(forceDenyResourcePackButton == null)
			{
				forceDenyResourcePackButton =
					Button.builder(getForceDenyResourcePackLabel(), b -> {
						config.getResourcePackForceDenySetting()
							.setChecked(!config.shouldForceDenyResourcePack());
						b.setMessage(getForceDenyResourcePackLabel());
					}).bounds(0, 0, 200, 20).build();
				addRenderableWidget(forceDenyResourcePackButton);
			}
			
			bypassResourcePackButton.setX(6);
			bypassResourcePackButton.setY(height - 54);
			bypassResourcePackButton.setWidth(200);
			bypassResourcePackButton.visible = true;
			bypassResourcePackButton.setMessage(getBypassResourcePackLabel());
			
			forceDenyResourcePackButton.setX(6);
			forceDenyResourcePackButton.setY(height - 30);
			forceDenyResourcePackButton.setWidth(200);
			forceDenyResourcePackButton.visible = true;
			forceDenyResourcePackButton
				.setMessage(getForceDenyResourcePackLabel());
		}else
		{
			if(bypassResourcePackButton != null)
				bypassResourcePackButton.visible = false;
			if(forceDenyResourcePackButton != null)
				forceDenyResourcePackButton.visible = false;
		}
		
		AbstractWidget addServerButton =
			findWidget(I18n.get("selectServer.add"));
		if(addServerButton != null)
		{
			if(cornerServerFinderButton == null)
			{
				cornerServerFinderButton = Button
					.builder(Component.literal("Server Finder"),
						b -> minecraft.setScreen(new ServerFinderScreen(
							(JoinMultiplayerScreen)(Object)this)))
					.bounds(0, 0, 100, 20).build();
				addRenderableWidget(cornerServerFinderButton);
			}
			cornerServerFinderButton
				.setX(addServerButton.getX() + addServerButton.getWidth() + 4);
			cornerServerFinderButton.setY(addServerButton.getY());
			cornerServerFinderButton.setWidth(100);
			cornerServerFinderButton.visible = true;
		}else if(cornerServerFinderButton != null)
		{
			cornerServerFinderButton.visible = false;
		}
		
		AbstractWidget backButton =
			findWidget(CommonComponents.GUI_BACK.getString());
		if(backButton != null)
		{
			if(cornerCleanUpButton == null)
			{
				cornerCleanUpButton = Button
					.builder(Component.literal("Clean Up"),
						b -> minecraft.setScreen(new CleanUpScreen(
							(JoinMultiplayerScreen)(Object)this)))
					.bounds(0, 0, 74, 20).build();
				addRenderableWidget(cornerCleanUpButton);
			}
			cornerCleanUpButton
				.setX(backButton.getX() + backButton.getWidth() + 4);
			cornerCleanUpButton.setY(backButton.getY());
			cornerCleanUpButton.setWidth(74);
			cornerCleanUpButton.visible = true;
		}else if(cornerCleanUpButton != null)
		{
			cornerCleanUpButton.visible = false;
		}
		
		if(showResourcePackButtons)
		{
			int resourcePackButtonWidth =
				Math.max(font.width(getBypassResourcePackLabel().getString()),
					font.width(getForceDenyResourcePackLabel().getString()))
					+ 20;
			
			bypassResourcePackButton.setWidth(resourcePackButtonWidth);
			forceDenyResourcePackButton.setWidth(resourcePackButtonWidth);
			bypassResourcePackButton.setMessage(getBypassResourcePackLabel());
			forceDenyResourcePackButton
				.setMessage(getForceDenyResourcePackLabel());
		}
	}
	
	@Inject(method = "join(Lnet/minecraft/client/multiplayer/ServerData;)V",
		at = @At("HEAD"))
	private void onConnect(ServerData entry, CallbackInfo ci)
	{
		LastServerRememberer.setLastServer(entry);
		updateLastServerButton();
	}
	
	@Inject(method = "onSelectedChange()V", at = @At("TAIL"))
	private void afterSelectedChange(CallbackInfo ci)
	{
		wurst$syncMultiSelectButtons();
	}
	
	@Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
		at = @At("HEAD"),
		cancellable = true)
	private void onBulkDeleteKey(KeyEvent event,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(event.key() != GLFW.GLFW_KEY_DELETE
			|| wurst$multiSelectedServers.size() <= 1)
			return;
		
		wurst$showBulkDeleteConfirm();
		cir.setReturnValue(true);
	}
	
	@Inject(method = "lambda$init$4", at = @At("HEAD"), cancellable = true)
	private void onDeleteButton(Button button, CallbackInfo ci)
	{
		if(wurst$multiSelectedServers.size() <= 1)
			return;
		
		wurst$showBulkDeleteConfirm();
		ci.cancel();
	}
	
	@Override
	public boolean wurst$handleServerClick(
		ServerSelectionList.OnlineServerEntry entry, MouseButtonEvent event,
		boolean doubleClick)
	{
		if(event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return false;
		
		if(!event.hasControlDown() && !event.hasShiftDown())
		{
			wurst$multiSelectedServers.clear();
			wurst$selectionAnchor = entry.getServerData();
			wurst$syncMultiSelectButtons();
			return false;
		}
		
		ServerData serverData = entry.getServerData();
		if(event.hasShiftDown())
			wurst$selectRange(serverData);
		else if(wurst$multiSelectedServers.remove(serverData))
			wurst$selectionAnchor = serverData;
		else
		{
			wurst$multiSelectedServers.add(serverData);
			wurst$selectionAnchor = serverData;
		}
		
		serverSelectionList.setSelected(entry);
		onSelectedChange();
		return true;
	}
	
	@Override
	public boolean wurst$isMultiSelected(ServerData serverData)
	{
		return wurst$multiSelectedServers.contains(serverData);
	}
	
	@Override
	public int wurst$getMultiSelectedCount()
	{
		return wurst$multiSelectedServers.size();
	}
	
	@Override
	public void wurst$clearMultiSelection()
	{
		wurst$multiSelectedServers.clear();
		wurst$selectionAnchor = null;
		wurst$syncMultiSelectButtons();
	}
	
	@Override
	public boolean wurst$bulkDeleteSelected()
	{
		if(wurst$multiSelectedServers.isEmpty())
			return false;
		
		List<ServerData> toDelete = new ArrayList<>(wurst$multiSelectedServers);
		for(ServerData serverData : toDelete)
			servers.remove(serverData);
		
		servers.save();
		wurst$clearMultiSelection();
		serverSelectionList.setSelected(null);
		serverSelectionList.updateOnlineServers(servers);
		return true;
	}
	
	@Unique
	private void updateLastServerButton()
	{
		if(lastServerButton == null)
			return;
		
		lastServerButton.active = LastServerRememberer.getLastServer() != null;
		lastServerButton.setX(width / 2 - 154);
		lastServerButton.setY(6);
	}
	
	@Unique
	private Component getBypassResourcePackLabel()
	{
		return Component.literal("Bypass Resource Pack: "
			+ (ResourcePackProtector.getConfig().shouldBypassResourcePack()
				? "ON" : "OFF"));
	}
	
	@Unique
	private Component getForceDenyResourcePackLabel()
	{
		return Component.literal("Force Deny: "
			+ (ResourcePackProtector.getConfig().shouldForceDenyResourcePack()
				? "ON" : "OFF"));
	}
	
	@Unique
	private void wurst$selectRange(ServerData serverData)
	{
		if(wurst$selectionAnchor == null)
			wurst$selectionAnchor = serverData;
		
		List<ServerSelectionList.Entry> entries =
			serverSelectionList.children();
		int anchorIndex = wurst$getServerEntryIndex(wurst$selectionAnchor);
		int clickedIndex = wurst$getServerEntryIndex(serverData);
		if(anchorIndex < 0 || clickedIndex < 0)
		{
			wurst$multiSelectedServers.clear();
			wurst$multiSelectedServers.add(serverData);
			wurst$selectionAnchor = serverData;
			return;
		}
		
		wurst$multiSelectedServers.clear();
		int start = Math.min(anchorIndex, clickedIndex);
		int end = Math.max(anchorIndex, clickedIndex);
		for(int i = start; i <= end && i < entries.size(); i++)
			if(entries
				.get(i) instanceof ServerSelectionList.OnlineServerEntry e)
				wurst$multiSelectedServers.add(e.getServerData());
	}
	
	@Unique
	private int wurst$getServerEntryIndex(ServerData serverData)
	{
		List<ServerSelectionList.Entry> entries =
			serverSelectionList.children();
		for(int i = 0; i < entries.size(); i++)
			if(entries.get(i) instanceof ServerSelectionList.OnlineServerEntry e
				&& e.getServerData() == serverData)
				return i;
			
		return -1;
	}
	
	@Unique
	private void wurst$showBulkDeleteConfirm()
	{
		int count = wurst$multiSelectedServers.size();
		if(count <= 0)
			return;
		
		Component title = Component.translatable("selectServer.deleteQuestion");
		Component message = Component.literal(
			"Are you sure you want to delete " + count + " selected servers?");
		Component delete = Component.translatable("selectServer.deleteButton");
		minecraft.setScreen(new ConfirmScreen(confirmed -> {
			if(confirmed)
				wurst$bulkDeleteSelected();
			minecraft.setScreen((JoinMultiplayerScreen)(Object)this);
		}, title, message, delete, CommonComponents.GUI_CANCEL));
	}
	
	@Unique
	private void wurst$syncMultiSelectButtons()
	{
		int count = wurst$multiSelectedServers.size();
		if(count <= 1 || editButton == null || selectButton == null
			|| deleteButton == null)
			return;
		
		selectButton.active = false;
		editButton.active = false;
		deleteButton.active = true;
	}
	
	@Unique
	private AbstractWidget findWidget(String label)
	{
		for(AbstractWidget button : Screens.getWidgets(this))
		{
			if(button.getMessage().getString().equals(label))
				return button;
		}
		
		return null;
	}
}
