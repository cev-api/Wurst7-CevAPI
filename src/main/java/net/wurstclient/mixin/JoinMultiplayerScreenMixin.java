/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.wurstclient.WurstClient;
import net.cevapi.config.AntiFingerprintConfigScreen;
import net.cevapi.security.ResourcePackProtector;
import net.cevapi.config.AntiFingerprintConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
import net.wurstclient.mixinterface.IServerSelectionListExt;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.altmanager.screens.AltManagerScreen;
import net.wurstclient.serverfinder.CleanUpScreen;
import net.wurstclient.serverfinder.ServerFinderScreen;
import net.wurstclient.util.LastServerRememberer;
import net.wurstclient.util.MultiProcessingUtils;
import net.wurstclient.util.ServerPanelConfig;
import net.wurstclient.util.ServerExportFileChooser;
import net.wurstclient.util.ServerImportFileChooser;
import net.wurstclient.util.ServerListExport;

@Mixin(JoinMultiplayerScreen.class)
public class JoinMultiplayerScreenMixin extends Screen
	implements IMultiplayerMultiSelect
{
	private static final int TOP_ROW_BUTTON_WIDTH = 100;
	private static final int TOP_ROW_BUTTON_SPACING = 4;
	private static final int BOTTOM_ROW_BUTTON_WIDTH = 74;
	private static final int BOTTOM_ROW_BUTTON_SPACING = 4;
	private static final int PANEL_COUNT = 3;
	private static final int PANEL_GAP = 8;
	private static final int PANEL_TITLE_HEIGHT = 18;
	private static final int PANEL_ROW_HEIGHT = 36;
	private static final int PANEL_AUTO_SCROLL_EDGE = 18;
	private static final double PANEL_AUTO_SCROLL_SPEED = 12;
	private static final int SORT_NAME = 0;
	private static final int SORT_VERSION = 1;
	private static final int SORT_PLAYERS = 2;
	private static final int SORT_BUTTON_WIDTH = 44;
	private static final int SORT_BUTTON_SPACING = 4;
	private static final String[] SORT_LABELS = {"Name", "Ver", "Player"};
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	
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
	private final EditBox[] wurst$panelTitleBoxes = new EditBox[PANEL_COUNT];
	@Unique
	private final Button[][] wurst$sortButtons =
		new Button[PANEL_COUNT][SORT_LABELS.length];
	@Unique
	private final int[] wurst$panelSortModes = new int[PANEL_COUNT];
	@Unique
	private final boolean[] wurst$panelSortAscending = {true, true, true};
	@Unique
	private final Button[] wurst$closePanelButtons = new Button[PANEL_COUNT];
	@Unique
	private final ServerSelectionList[] wurst$panelLists =
		new ServerSelectionList[PANEL_COUNT];
	@Unique
	private Button wurst$restorePanelButton;
	@Unique
	private Button wurst$exportButton;
	@Unique
	private Button wurst$importButton;
	@Unique
	private ServerPanelConfig wurst$panelConfig;
	@Unique
	private final Set<ServerData> wurst$multiSelectedServers =
		new LinkedHashSet<>();
	@Unique
	private ServerData wurst$selectionAnchor;
	@Unique
	private ServerData wurst$draggedServer;
	@Unique
	private List<ServerData> wurst$draggedServers = List.of();
	@Unique
	private double wurst$dragStartX;
	@Unique
	private double wurst$dragStartY;
	@Unique
	private boolean wurst$isDraggingServers;
	@Unique
	private int wurst$dropPanel = -1;
	@Unique
	private int wurst$dropRow = -1;
	@Unique
	private Component wurst$statusMessage;
	@Unique
	private long wurst$statusMessageUntil;
	
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
		wurst$exportButton = null;
		wurst$importButton = null;
		for(int i = 0; i < wurst$panelTitleBoxes.length; i++)
		{
			wurst$panelTitleBoxes[i] = null;
			wurst$panelLists[i] = null;
			wurst$closePanelButtons[i] = null;
			for(int j = 0; j < wurst$sortButtons[i].length; j++)
				wurst$sortButtons[i][j] = null;
		}
		wurst$restorePanelButton = null;
		wurst$panelConfig = ServerPanelConfig.load(minecraft);
		
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
	
	@Inject(method = "init()V", at = @At("TAIL"))
	private void afterVanillaButtons(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		addRenderableOnly((context, mouseX, mouseY,
			partialTicks) -> wurst$renderPanelOverlays(context));
	}
	
	@Inject(method = "repositionElements()V", at = @At("TAIL"))
	private void onRefreshWidgetPositions(CallbackInfo ci)
	{
		updateLastServerButton();
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		wurst$layoutServerPanels();
		wurst$ensurePanelWidgets();
		
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
		
		wurst$layoutBottomButtons();
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
	
	@Unique
	private void wurst$renderPanelOverlays(GuiGraphics context)
	{
		if(wurst$panelConfig == null)
			return;
		
		int top = 32;
		int bottom = height - 86;
		for(int i = 0; i < PANEL_COUNT; i++)
		{
			boolean visible = wurst$panelConfig.isPanelVisible(i);
			int x = wurst$getPanelX(i);
			int panelWidth = wurst$getPanelWidth();
			if(!visible)
				continue;
			
			context.vLine(x, top, bottom, 0x66000000);
			context.vLine(x + panelWidth, top, bottom, 0x66000000);
		}
		
		if(wurst$statusMessage != null
			&& System.currentTimeMillis() < wurst$statusMessageUntil)
			context.drawCenteredString(font, wurst$statusMessage, width / 2,
				height - 76, 0xFFFFFFFF);
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
	
	@Inject(method = "method_19914",
		at = @At("HEAD"),
		cancellable = true,
		remap = false)
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
			ServerData serverData = entry.getServerData();
			if(!wurst$multiSelectedServers.contains(serverData)
				|| wurst$multiSelectedServers.size() <= 1)
				wurst$multiSelectedServers.clear();
			wurst$selectionAnchor = entry.getServerData();
			wurst$draggedServer = entry.getServerData();
			wurst$dragStartX = event.x();
			wurst$dragStartY = event.y();
			wurst$isDraggingServers = false;
			wurst$dropPanel = -1;
			wurst$dropRow = -1;
			wurst$selectMainListServer(entry.getServerData());
			wurst$syncMultiSelectButtons();
			wurst$prepareDraggedServers(entry.getServerData());
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
		
		wurst$selectMainListServer(serverData);
		wurst$prepareDraggedServers(serverData);
		onSelectedChange();
		return true;
	}
	
	@Override
	public boolean wurst$isMultiSelected(ServerData serverData)
	{
		return wurst$multiSelectedServers.contains(serverData);
	}
	
	@Override
	public boolean wurst$isServerHighlighted(ServerData serverData)
	{
		return wurst$multiSelectedServers.contains(serverData)
			|| serverData == wurst$draggedServer
			|| wurst$draggedServers.contains(serverData);
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
		wurst$draggedServers = List.of();
		wurst$syncMultiSelectButtons();
	}
	
	@Override
	public boolean wurst$bulkDeleteSelected()
	{
		if(wurst$multiSelectedServers.isEmpty())
			return false;
		
		List<ServerData> toDelete = new ArrayList<>(wurst$multiSelectedServers);
		for(ServerData serverData : toDelete)
		{
			servers.remove(serverData);
			wurst$panelConfig.remove(serverData);
		}
		
		servers.save();
		wurst$panelConfig.save(minecraft);
		wurst$clearMultiSelection();
		serverSelectionList.setSelected(null);
		serverSelectionList.updateOnlineServers(servers);
		wurst$refreshPanelLists();
		return true;
	}
	
	public boolean mouseDragged(MouseButtonEvent event, double dragX,
		double dragY)
	{
		if(wurst$draggedServer != null
			&& (Math.abs(event.x() - wurst$dragStartX) > 4
				|| Math.abs(event.y() - wurst$dragStartY) > 4))
		{
			wurst$isDraggingServers = true;
			wurst$autoScrollDraggedPanel(event.x(), event.y());
			wurst$updateDropTarget(event.x(), event.y());
			return true;
		}
		
		if(event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			for(int i = 0; i < PANEL_COUNT; i++)
			{
				ServerSelectionList list = wurst$panelLists[i];
				if(list != null && list.mouseDragged(event, dragX, dragY))
					return true;
			}
		
		return super.mouseDragged(event, dragX, dragY);
	}
	
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		if(event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			for(int i = 0; i < PANEL_COUNT; i++)
			{
				ServerSelectionList list = wurst$panelLists[i];
				if(list == null || !wurst$panelConfig.isPanelVisible(i))
					continue;
				if(!wurst$isOverPanelScrollbar(i, event.x(), event.y()))
					continue;
				return list.mouseClicked(event, doubleClick);
			}
		
		return super.mouseClicked(event, doubleClick);
	}
	
	public boolean mouseReleased(MouseButtonEvent event)
	{
		boolean handled = false;
		if(event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			for(int i = 0; i < PANEL_COUNT; i++)
			{
				ServerSelectionList list = wurst$panelLists[i];
				if(list != null && list.mouseReleased(event))
					handled = true;
			}
		
		if(wurst$isDraggingServers && wurst$draggedServer != null)
			wurst$applyDropTarget();
		else if(wurst$isDraggingServers)
			wurst$refreshPanelLists();
		
		wurst$isDraggingServers = false;
		wurst$draggedServer = null;
		wurst$draggedServers = List.of();
		wurst$dropPanel = -1;
		wurst$dropRow = -1;
		return handled || super.mouseReleased(event);
	}
	
	@Unique
	private void wurst$layoutServerPanels()
	{
		if(serverSelectionList == null)
			return;
		
		serverSelectionList.updateSizeAndPosition(0, 0, -10000, -10000);
		int listY = wurst$getPanelListY();
		int listHeight = Math.max(36, height - 86 - listY);
		for(int i = 0; i < PANEL_COUNT; i++)
		{
			if(wurst$panelLists[i] == null)
			{
				wurst$panelLists[i] = new ServerSelectionList(
					(JoinMultiplayerScreen)(Object)this, minecraft,
					wurst$getPanelWidth(), listHeight, listY, 36);
				((IServerSelectionListExt)wurst$panelLists[i])
					.wurst$setPanelList(true);
				addRenderableWidget(wurst$panelLists[i]);
			}
			
			wurst$panelLists[i].updateSizeAndPosition(wurst$getPanelWidth(),
				listHeight, wurst$getPanelX(i), listY);
			wurst$panelLists[i].visible = wurst$panelConfig.isPanelVisible(i);
		}
		
		wurst$refreshPanelLists();
	}
	
	@Unique
	private void wurst$ensurePanelWidgets()
	{
		for(int i = 0; i < PANEL_COUNT; i++)
		{
			final int panel = i;
			if(wurst$panelTitleBoxes[i] == null)
			{
				EditBox titleBox = new EditBox(font, 0, 0, 120, 16,
					Component.literal("Panel " + (i + 1)));
				titleBox.setMaxLength(32);
				titleBox.setValue(wurst$panelConfig.getTitle(i));
				titleBox.setCursorPosition(0);
				titleBox.setHighlightPos(0);
				titleBox.setBordered(false);
				titleBox.setTextShadow(true);
				titleBox.setResponder(title -> {
					wurst$panelConfig.setTitle(panel, title);
					wurst$panelConfig.save(minecraft);
				});
				wurst$panelTitleBoxes[i] = titleBox;
				addRenderableWidget(titleBox);
			}
			
			EditBox titleBox = wurst$panelTitleBoxes[i];
			titleBox.setX(wurst$getPanelX(i) + 4);
			titleBox.setY(34);
			titleBox.setWidth(wurst$getPanelWidth() - 8);
			if(!titleBox.isFocused())
			{
				titleBox.setCursorPosition(0);
				titleBox.setHighlightPos(0);
			}
			titleBox.visible = wurst$panelConfig.isPanelVisible(i);
		}
		
		for(int panel = 0; panel < PANEL_COUNT; panel++)
		{
			for(int sortMode = 0; sortMode < SORT_LABELS.length; sortMode++)
			{
				Button sortButton = wurst$sortButtons[panel][sortMode];
				if(sortButton == null)
				{
					final int sortPanel = panel;
					final int currentSortMode = sortMode;
					sortButton =
						Button
							.builder(Component.literal(SORT_LABELS[sortMode]),
								b -> wurst$sortPanel(sortPanel,
									currentSortMode))
							.bounds(0, 0, SORT_BUTTON_WIDTH, 14).build();
					wurst$sortButtons[panel][sortMode] = sortButton;
					addRenderableWidget(sortButton);
				}
				
				sortButton.setMessage(Component.literal(SORT_LABELS[sortMode]));
				sortButton.setX(wurst$getPanelX(panel) + 4
					+ sortMode * (SORT_BUTTON_WIDTH + SORT_BUTTON_SPACING));
				sortButton.setY(52);
				sortButton.setWidth(SORT_BUTTON_WIDTH);
				sortButton.setHeight(14);
				sortButton.visible = wurst$panelConfig.isPanelVisible(panel);
			}
		}
		
		for(int panel = 0; panel < PANEL_COUNT; panel++)
		{
			Button closeButton = wurst$closePanelButtons[panel];
			if(closeButton == null && panel != 1)
			{
				final int panelToClose = panel;
				closeButton = Button
					.builder(Component.literal("-"),
						b -> wurst$closePanel(panelToClose))
					.bounds(0, 0, 16, 14).build();
				wurst$closePanelButtons[panel] = closeButton;
				addRenderableWidget(closeButton);
			}
			
			if(closeButton == null)
				continue;
			
			closeButton.setX(wurst$getPanelX(panel) + wurst$getPanelWidth()
				- closeButton.getWidth() - 4);
			closeButton.setY(52);
			closeButton.visible = wurst$panelConfig.isPanelVisible(panel);
		}
		
		if(wurst$restorePanelButton == null)
		{
			wurst$restorePanelButton = Button
				.builder(Component.literal("+"), b -> wurst$restoreLastPanel())
				.bounds(0, 0, 16, 14).build();
			addRenderableWidget(wurst$restorePanelButton);
		}
		
		wurst$restorePanelButton.setX(wurst$getPanelX(1) + wurst$getPanelWidth()
			- wurst$restorePanelButton.getWidth() - 4);
		wurst$restorePanelButton.setY(52);
		wurst$restorePanelButton.visible = wurst$panelConfig.hasClosedPanels();
		
		if(wurst$exportButton == null)
		{
			wurst$exportButton =
				Button
					.builder(Component.literal("Export"),
						b -> wurst$exportServers())
					.bounds(0, 0, 74, 20).build();
			wurst$importButton =
				Button
					.builder(Component.literal("Import"),
						b -> wurst$importServers())
					.bounds(0, 0, 74, 20).build();
			
			addRenderableWidget(wurst$exportButton);
			addRenderableWidget(wurst$importButton);
		}
	}
	
	@Unique
	private void wurst$layoutBottomButtons()
	{
		List<AbstractWidget> topRow = new ArrayList<>();
		wurst$addIfFound(topRow, I18n.get("selectServer.select"));
		wurst$addIfFound(topRow, I18n.get("selectServer.direct"));
		wurst$addIfFound(topRow, I18n.get("selectServer.add"));
		if(cornerServerFinderButton != null && cornerServerFinderButton.visible)
			topRow.add(cornerServerFinderButton);
		if(cornerCleanUpButton != null && cornerCleanUpButton.visible)
			topRow.add(cornerCleanUpButton);
		wurst$positionRow(topRow, height - 54, TOP_ROW_BUTTON_SPACING);
		
		List<AbstractWidget> bottomRow = new ArrayList<>();
		wurst$addIfFound(bottomRow, I18n.get("selectServer.edit"));
		wurst$addIfFound(bottomRow, I18n.get("selectServer.delete"));
		wurst$addIfFound(bottomRow, I18n.get("selectServer.refresh"));
		if(wurst$exportButton != null)
			bottomRow.add(wurst$exportButton);
		if(wurst$importButton != null)
			bottomRow.add(wurst$importButton);
		wurst$positionRow(bottomRow, height - 30, BOTTOM_ROW_BUTTON_SPACING);
		
		AbstractWidget backButton =
			findWidget(CommonComponents.GUI_BACK.getString());
		if(backButton != null)
		{
			backButton.setX(6);
			backButton.setY(height - 30);
		}
	}
	
	@Unique
	private void wurst$positionRow(List<AbstractWidget> row, int y, int spacing)
	{
		row.removeIf(button -> button == null);
		int totalWidth = -spacing;
		for(AbstractWidget button : row)
			totalWidth += button.getWidth() + spacing;
		
		int x = width / 2 - totalWidth / 2;
		for(AbstractWidget button : row)
		{
			button.setX(x);
			button.setY(y);
			x += button.getWidth() + spacing;
		}
	}
	
	@Unique
	private void wurst$addIfFound(List<AbstractWidget> buttons, String label)
	{
		AbstractWidget button = findWidget(label);
		if(button != null)
			buttons.add(button);
	}
	
	@Unique
	private int wurst$getPanelWidth()
	{
		int visiblePanels = wurst$getVisiblePanelCount();
		if(visiblePanels == 1)
			return Math.max(120,
				(width - 12 - PANEL_GAP * (PANEL_COUNT - 1)) / PANEL_COUNT);
		
		return Math.max(120,
			(width - 12 - PANEL_GAP * Math.max(0, visiblePanels - 1))
				/ visiblePanels);
	}
	
	@Unique
	private int wurst$getPanelX(int panel)
	{
		if(wurst$getVisiblePanelCount() == 1)
			return width / 2 - wurst$getPanelWidth() / 2;
		
		int visibleIndex = wurst$getVisiblePanelIndex(panel);
		return 6 + visibleIndex * (wurst$getPanelWidth() + PANEL_GAP);
	}
	
	@Unique
	private int wurst$getVisiblePanelCount()
	{
		int visiblePanels = 0;
		for(int i = 0; i < PANEL_COUNT; i++)
			if(wurst$panelConfig.isPanelVisible(i))
				visiblePanels++;
			
		return Math.max(1, visiblePanels);
	}
	
	@Unique
	private int wurst$getVisiblePanelIndex(int panel)
	{
		if(!wurst$panelConfig.isPanelVisible(panel))
			return 0;
		
		int visibleIndex = 0;
		for(int i = 0; i < panel; i++)
			if(wurst$panelConfig.isPanelVisible(i))
				visibleIndex++;
			
		return visibleIndex;
	}
	
	@Unique
	private int wurst$getPanelListY()
	{
		return 72;
	}
	
	@Unique
	private int wurst$getPanelAt(double mouseX)
	{
		for(int i = 0; i < PANEL_COUNT; i++)
		{
			int x = wurst$getPanelX(i);
			if(mouseX >= x && mouseX < x + wurst$getPanelWidth()
				&& wurst$panelConfig.isPanelVisible(i))
				return i;
		}
		
		return 1;
	}
	
	@Unique
	private void wurst$updateDropTarget(double mouseX, double mouseY)
	{
		if(wurst$draggedServers.isEmpty())
			wurst$prepareDraggedServers(wurst$draggedServer);
		
		int panel = wurst$getPanelAt(mouseX);
		double scrollAmount = wurst$panelLists[panel] != null
			? wurst$panelLists[panel].scrollAmount() : 0;
		int row =
			Math.max(0, (int)((mouseY + scrollAmount - wurst$getPanelListY())
				/ PANEL_ROW_HEIGHT));
		
		if(wurst$dropPanel == panel && wurst$dropRow == row)
			return;
		
		wurst$dropPanel = panel;
		wurst$dropRow = row;
		wurst$updateDragPreview();
	}
	
	@Unique
	private void wurst$autoScrollDraggedPanel(double mouseX, double mouseY)
	{
		int panel = wurst$getPanelAt(mouseX);
		ServerSelectionList list = wurst$panelLists[panel];
		if(list == null || !wurst$panelConfig.isPanelVisible(panel))
			return;
		
		int top = wurst$getPanelListY();
		int bottom = top + list.getHeight();
		double scroll = list.scrollAmount();
		
		if(mouseY < top + PANEL_AUTO_SCROLL_EDGE)
			list.setScrollAmount(Math.max(0, scroll - PANEL_AUTO_SCROLL_SPEED));
		else if(mouseY > bottom - PANEL_AUTO_SCROLL_EDGE)
			list.setScrollAmount(scroll + PANEL_AUTO_SCROLL_SPEED);
	}
	
	@Unique
	private void wurst$applyDropTarget()
	{
		if(wurst$draggedServers.isEmpty())
			wurst$prepareDraggedServers(wurst$draggedServer);
		if(wurst$draggedServers.isEmpty() || wurst$dropPanel < 0
			|| wurst$dropRow < 0)
			return;
		
		List<ServerData> ordered = wurst$getServers();
		List<ServerData> dragged =
			ordered.stream().filter(wurst$draggedServers::contains).toList();
		if(dragged.isEmpty())
			return;
		
		for(ServerData server : dragged)
		{
			wurst$panelConfig.setPanel(server, wurst$dropPanel);
			ordered.remove(server);
		}
		
		int insertIndex = 0;
		int panelRows = 0;
		while(insertIndex < ordered.size())
		{
			ServerData server = ordered.get(insertIndex);
			if(wurst$panelConfig.getPanel(server) == wurst$dropPanel)
			{
				if(panelRows >= wurst$dropRow)
					break;
				panelRows++;
			}
			insertIndex++;
		}
		
		ordered.addAll(insertIndex, dragged);
		wurst$replaceServers(ordered);
		servers.save();
		wurst$panelConfig.save(minecraft);
		serverSelectionList.updateOnlineServers(servers);
		wurst$refreshPanelLists();
	}
	
	@Unique
	private void wurst$updateDragPreview()
	{
		if(wurst$draggedServers.isEmpty() || wurst$dropPanel < 0
			|| wurst$dropRow < 0)
			return;
		
		List<ServerData> previewOrder = wurst$getPreviewOrder();
		Map<ServerData, ServerSelectionList.OnlineServerEntry> entryMap =
			new HashMap<>();
		for(int panel = 0; panel < PANEL_COUNT; panel++)
		{
			ServerSelectionList list = wurst$panelLists[panel];
			if(list == null)
				continue;
			
			for(ServerSelectionList.Entry entry : list.children())
				if(entry instanceof ServerSelectionList.OnlineServerEntry online)
					entryMap.put(online.getServerData(), online);
		}
		
		for(int panel = 0; panel < PANEL_COUNT; panel++)
		{
			ServerSelectionList list = wurst$panelLists[panel];
			if(list == null)
				continue;
			
			List<ServerSelectionList.Entry> entries = new ArrayList<>();
			for(ServerData server : previewOrder)
			{
				int previewPanel = wurst$draggedServers.contains(server)
					? wurst$dropPanel : wurst$panelConfig.getPanel(server);
				if(previewPanel != panel)
					continue;
				
				ServerSelectionList.OnlineServerEntry entry =
					entryMap.get(server);
				if(entry != null)
					entries.add(entry);
			}
			
			list.replaceEntries(entries);
		}
	}
	
	@Unique
	private List<ServerData> wurst$getPreviewOrder()
	{
		List<ServerData> ordered = wurst$getServers();
		List<ServerData> dragged =
			ordered.stream().filter(wurst$draggedServers::contains).toList();
		if(dragged.isEmpty())
			return ordered;
		
		for(ServerData server : dragged)
			ordered.remove(server);
		
		int insertIndex = 0;
		int panelRows = 0;
		while(insertIndex < ordered.size())
		{
			ServerData server = ordered.get(insertIndex);
			int previewPanel = wurst$panelConfig.getPanel(server);
			if(previewPanel == wurst$dropPanel)
			{
				if(panelRows >= wurst$dropRow)
					break;
				panelRows++;
			}
			insertIndex++;
		}
		
		ordered.addAll(insertIndex, dragged);
		return ordered;
	}
	
	@Unique
	private boolean wurst$isOverPanelScrollbar(int panel, double mouseX,
		double mouseY)
	{
		if(!wurst$panelConfig.isPanelVisible(panel))
			return false;
		
		int panelX = wurst$getPanelX(panel);
		int panelWidth = wurst$getPanelWidth();
		int scrollbarX = panelX + panelWidth - 8;
		int listY = wurst$getPanelListY();
		int listBottom = listY + Math.max(36, height - 86 - listY);
		return mouseX >= scrollbarX && mouseX <= scrollbarX + 8
			&& mouseY >= listY && mouseY <= listBottom;
	}
	
	@Unique
	private void wurst$prepareDraggedServers(ServerData clickedServer)
	{
		if(clickedServer == null)
		{
			wurst$draggedServers = List.of();
			return;
		}
		
		if(wurst$multiSelectedServers.contains(clickedServer)
			&& wurst$multiSelectedServers.size() > 1)
			wurst$draggedServers = wurst$getServers().stream()
				.filter(wurst$multiSelectedServers::contains).toList();
		else
			wurst$draggedServers = List.of(clickedServer);
	}
	
	@Unique
	private void wurst$sortPanel(int panel, int sortMode)
	{
		if(wurst$panelSortModes[panel] == sortMode)
			wurst$panelSortAscending[panel] = !wurst$panelSortAscending[panel];
		else
		{
			wurst$panelSortModes[panel] = sortMode;
			wurst$panelSortAscending[panel] =
				wurst$isAscendingDefault(sortMode);
		}
		
		List<ServerData> ordered = wurst$getServers();
		Comparator<ServerData> comparator = wurst$getSortComparator(sortMode);
		if(!wurst$panelSortAscending[panel])
			comparator = comparator.reversed();
		
		List<ServerData> panelServers = ordered.stream()
			.filter(server -> wurst$panelConfig.getPanel(server) == panel)
			.sorted(comparator.thenComparing(s -> s.name,
				String.CASE_INSENSITIVE_ORDER))
			.toList();
		
		int next = 0;
		for(int i = 0; i < ordered.size(); i++)
			if(wurst$panelConfig.getPanel(ordered.get(i)) == panel)
				ordered.set(i, panelServers.get(next++));
			
		wurst$replaceServers(ordered);
		servers.save();
		serverSelectionList.updateOnlineServers(servers);
		wurst$refreshPanelLists();
	}
	
	@Unique
	private boolean wurst$isAscendingDefault(int sortMode)
	{
		return sortMode == SORT_NAME;
	}
	
	@Unique
	private Comparator<ServerData> wurst$getSortComparator(int sortMode)
	{
		return switch(sortMode)
		{
			case SORT_VERSION -> Comparator
				.comparingInt(this::wurst$getServerProtocol).reversed();
			case SORT_PLAYERS -> Comparator
				.comparingInt(this::wurst$getOnlinePlayers).reversed();
			default -> Comparator.comparing(
				(ServerData s) -> s.name == null ? "" : s.name,
				String.CASE_INSENSITIVE_ORDER);
		};
	}
	
	@Unique
	private List<ServerData> wurst$getServers()
	{
		List<ServerData> list = new ArrayList<>();
		for(int i = 0; i < servers.size(); i++)
			list.add(servers.get(i));
		return list;
	}
	
	@Unique
	private void wurst$replaceServers(List<ServerData> ordered)
	{
		for(int i = 0; i < ordered.size(); i++)
			servers.replace(i, ordered.get(i));
	}
	
	@Unique
	private void wurst$refreshPanelLists()
	{
		for(int panel = 0; panel < PANEL_COUNT; panel++)
		{
			ServerSelectionList panelList = wurst$panelLists[panel];
			if(panelList == null)
				continue;
			
			ServerList subset = new ServerList(minecraft);
			if(wurst$panelConfig.isPanelVisible(panel))
				for(ServerData server : wurst$getServers())
					if(wurst$panelConfig.getPanel(server) == panel)
						subset.add(server, false);
					
			panelList.updateOnlineServers(subset);
		}
	}
	
	@Unique
	private void wurst$closePanel(int panel)
	{
		wurst$panelConfig.closePanel(panel);
		wurst$panelConfig.save(minecraft);
		wurst$clearMultiSelection();
		serverSelectionList.setSelected(null);
		wurst$layoutServerPanels();
		wurst$ensurePanelWidgets();
	}
	
	@Unique
	private void wurst$restoreLastPanel()
	{
		if(!wurst$panelConfig.reopenLastPanel())
			return;
		
		wurst$panelConfig.save(minecraft);
		wurst$layoutServerPanels();
		wurst$ensurePanelWidgets();
	}
	
	@Unique
	private void wurst$selectMainListServer(ServerData serverData)
	{
		for(ServerSelectionList.Entry entry : serverSelectionList.children())
			if(entry instanceof ServerSelectionList.OnlineServerEntry onlineEntry
				&& onlineEntry.getServerData() == serverData)
			{
				serverSelectionList.setSelected(onlineEntry);
				return;
			}
		
		serverSelectionList.setSelected(null);
	}
	
	@Unique
	private int wurst$getServerIndex(ServerData server)
	{
		for(int i = 0; i < servers.size(); i++)
			if(servers.get(i) == server)
				return i;
		return Integer.MAX_VALUE;
	}
	
	@Unique
	private int wurst$getServerProtocol(ServerData server)
	{
		return server.protocol;
	}
	
	@Unique
	private int wurst$getOnlinePlayers(ServerData server)
	{
		return server.players != null ? server.players.online() : -1;
	}
	
	@Unique
	private void wurst$exportServers()
	{
		Path path = wurst$chooseJsonFile(false);
		if(path == null)
			return;
		File file = path.toFile();
		
		ServerListExport exported = new ServerListExport();
		exported.exportedAt = java.time.Instant.now().toString();
		for(int i = 0; i < PANEL_COUNT; i++)
			exported.panelTitles[i] = wurst$panelConfig.getTitle(i);
		List<ServerData> serverData = wurst$getServers();
		for(int i = 0; i < serverData.size(); i++)
		{
			ServerData server = serverData.get(i);
			int panel = wurst$panelConfig.getPanel(server);
			exported.servers.add(ServerListExport.Server.from(server, panel,
				wurst$panelConfig.getTitle(panel), i));
		}
		
		try(FileWriter writer = new FileWriter(file))
		{
			GSON.toJson(exported, writer);
			wurst$showStatus("Exported " + exported.servers.size()
				+ " servers to " + file.getName());
		}catch(IOException e)
		{
			wurst$exportButton.setMessage(Component.literal("Export failed"));
			wurst$showStatus("Export failed");
		}
	}
	
	@Unique
	private void wurst$importServers()
	{
		Path path = wurst$chooseJsonFile(true);
		if(path == null)
			return;
		File file = path.toFile();
		
		try(FileReader reader = new FileReader(file))
		{
			ServerListExport imported =
				GSON.fromJson(reader, ServerListExport.class);
			if(imported == null)
				return;
			
			if(imported.panelTitles != null)
				for(int i = 0; i < PANEL_COUNT
					&& i < imported.panelTitles.length; i++)
					if(imported.panelTitles[i] != null)
						wurst$panelConfig.setTitle(i, imported.panelTitles[i]);
					
			if(imported.servers != null)
				for(ServerListExport.Server exported : imported.servers)
				{
					if(exported.name == null || exported.ip == null)
						continue;
					if(servers.get(exported.ip) != null)
						continue;
					
					ServerData server = new ServerData(exported.name,
						exported.ip, ServerData.Type.OTHER);
					servers.add(server, false);
					wurst$panelConfig.setPanel(server,
						wurst$getImportPanel(exported.panel));
				}
			
			servers.save();
			wurst$panelConfig.save(minecraft);
			serverSelectionList.updateOnlineServers(servers);
			wurst$refreshPanelLists();
			for(int i = 0; i < PANEL_COUNT; i++)
				wurst$panelTitleBoxes[i]
					.setValue(wurst$panelConfig.getTitle(i));
			wurst$showStatus("Imported servers from " + file.getName());
		}catch(IOException | RuntimeException e)
		{
			wurst$importButton.setMessage(Component.literal("Import failed"));
			wurst$showStatus("Import failed");
		}
	}
	
	@Unique
	private int wurst$getImportPanel(int panel)
	{
		if(panel < 0 || panel >= PANEL_COUNT
			|| !wurst$panelConfig.isPanelVisible(panel))
			return 1;
		
		return panel;
	}
	
	@Unique
	private void wurst$showStatus(String message)
	{
		wurst$statusMessage = Component.literal(message);
		wurst$statusMessageUntil = System.currentTimeMillis() + 3000;
	}
	
	@Unique
	private Path wurst$chooseJsonFile(boolean open)
	{
		try
		{
			Process process = MultiProcessingUtils.startProcessWithIO(
				open ? ServerImportFileChooser.class
					: ServerExportFileChooser.class,
				minecraft.gameDirectory.getAbsolutePath());
			Path path = wurst$getFileChooserPath(process);
			process.waitFor();
			return path;
			
		}catch(IOException | InterruptedException e)
		{
			wurst$showStatus((open ? "Import" : "Export") + " dialog failed");
			if(e instanceof InterruptedException)
				Thread.currentThread().interrupt();
			return null;
		}
	}
	
	@Unique
	private Path wurst$getFileChooserPath(Process process) throws IOException
	{
		try(BufferedReader bf =
			new BufferedReader(new InputStreamReader(process.getInputStream(),
				StandardCharsets.UTF_8)))
		{
			String response = bf.readLine();
			if(response == null || response.isBlank())
				return null;
			
			try
			{
				return Paths.get(response);
				
			}catch(InvalidPathException e)
			{
				throw new IOException("Response from FileChooser is invalid",
					e);
			}
		}
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
		for(AbstractWidget button : Screens.getButtons(this))
		{
			if(button.getMessage().getString().equals(label))
				return button;
		}
		
		return null;
	}
}
