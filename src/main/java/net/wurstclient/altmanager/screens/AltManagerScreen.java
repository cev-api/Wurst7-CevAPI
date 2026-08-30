/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager.screens;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;
import net.wurstclient.WurstClient;
import net.wurstclient.altbot.AltBotManager;
import net.wurstclient.altbot.AltBotState;
import net.wurstclient.altbot.BotState;
import net.wurstclient.altmanager.*;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.mixinterface.IMinecraftClient;
import net.wurstclient.proxy.SocksProxy;
import net.wurstclient.proxy.ProxyManagerScreen;
import net.wurstclient.util.MultiProcessingUtils;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonObject;

public final class AltManagerScreen extends Screen
{
	private static final DateTimeFormatter VALIDATED_FORMAT = DateTimeFormatter
		.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
	private static final HashSet<Alt> failedLogins = new HashSet<>();
	private static final LinkedHashMap<Alt, String> failedLoginReasons =
		new LinkedHashMap<>();
	private static net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget.SelectionState lastListState;
	
	private final Screen prevScreen;
	private final AltManager altManager;
	
	private ListGui listGui;
	private boolean shouldAsk = true;
	private int errorTimer;
	
	private Button useButton;
	private Button randomButton;
	private Button autoLoginButton;
	private Button starButton;
	private Button editButton;
	private Button deleteButton;
	
	private Button importButton;
	private Button exportButton;
	private Button autoRespawnButton;
	private Button checkButton;
	private Button logoutButton;
	
	private Button botConnectButton;
	private Button botProxyButton;
	private Button botDisconnectButton;
	private Button botSwitchButton;
	private Button botSendChatButton;
	private Button botDetailsButton;
	private int botButtonRefreshTicks;
	
	private List<Alt> pendingDeletion = Collections.emptyList();
	private Alt pendingLogin;
	private volatile boolean autoCheckCancelled;
	private volatile boolean autoCheckInProgress;
	private final HashSet<Alt> checkingAlts = new HashSet<>();
	private volatile boolean importInProgress;
	private volatile boolean importPrismInProgress;
	private volatile boolean exportInProgress;
	private volatile String importStatus = "";
	private volatile int importDone;
	private volatile int importTotal;
	private volatile boolean importHasCounts;
	private volatile boolean editValidationInProgress;
	private volatile String editValidationStatus = "";
	private volatile boolean randomLoginInProgress;
	
	public AltManagerScreen(Screen prevScreen, AltManager altManager)
	{
		super(Component.literal("Alt Manager"));
		this.prevScreen = prevScreen;
		this.altManager = altManager;
	}
	
	@Override
	public void init()
	{
		autoCheckCancelled = false;
		listGui = new ListGui(minecraft, this, altManager.getList());
		addWidget(listGui);
		if(lastListState != null)
			listGui.restoreState(lastListState);
		else
			listGui.ensureSelection();
		
		WurstClient wurst = WurstClient.INSTANCE;
		
		Exception folderException = altManager.getFolderException();
		if(folderException != null && shouldAsk)
		{
			Component title = Component.literal(
				wurst.translate("gui.wurst.altmanager.folder_error.title"));
			Component message = Component.literal(wurst.translate(
				"gui.wurst.altmanager.folder_error.message", folderException));
			Component buttonText = Component.translatable("gui.done");
			
			// This just sets shouldAsk to false and closes the message.
			Runnable action = () -> confirmGenerate(false);
			
			AlertScreen screen =
				new AlertScreen(action, title, message, buttonText, false);
			minecraft.gui.setScreen(screen);
			
		}else if(altManager.getList().isEmpty() && shouldAsk)
		{
			Component title = Component
				.literal(wurst.translate("gui.wurst.altmanager.empty.title"));
			Component message = Component
				.literal(wurst.translate("gui.wurst.altmanager.empty.message"));
			BooleanConsumer callback = this::confirmGenerate;
			
			ConfirmScreen screen = new ConfirmScreen(callback, title, message);
			minecraft.gui.setScreen(screen);
		}
		
		addRenderableWidget(useButton =
			Button.builder(Component.literal("Login"), b -> pressLogin())
				.bounds(width / 2 - 154, height - 52, 100, 20).build());
		
		addRenderableWidget(randomButton = Button
			.builder(Component.literal("Login Random"), b -> pressLoginRandom())
			.bounds(width - 50 - 8 - 52 - 52 - 100 - 6, 8, 100, 20).build());
		
		int randomX = width - 50 - 8 - 52 - 52 - 100 - 6;
		addRenderableWidget(autoLoginButton =
			Button.builder(getAutoLoginLabel(), b -> pressToggleAutoLogin())
				.bounds(randomX - 104, 8, 100, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Direct Login"),
				b -> minecraft.gui.setScreen(new DirectLoginScreen(this)))
			.bounds(width / 2 - 50, height - 52, 100, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Add"),
				b -> minecraft.gui
					.setScreen(new AddAltScreen(this, altManager)))
			.bounds(width / 2 + 54, height - 52, 100, 20).build());
		
		addRenderableWidget(starButton =
			Button.builder(Component.literal("Favorite"), b -> pressFavorite())
				.bounds(width / 2 - 154, height - 28, 75, 20).build());
		
		addRenderableWidget(editButton =
			Button.builder(Component.literal("Edit"), b -> pressEdit())
				.bounds(width / 2 - 76, height - 28, 74, 20).build());
		
		addRenderableWidget(deleteButton =
			Button.builder(Component.literal("Delete"), b -> pressDelete())
				.bounds(width / 2 + 2, height - 28, 74, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Cancel"),
				b -> minecraft.gui.setScreen(prevScreen))
			.bounds(width / 2 + 80, height - 28, 75, 20).build());
		
		addRenderableWidget(importButton =
			Button.builder(Component.literal("Import"), b -> pressImportAlts())
				.bounds(8, 8, 50, 20).build());
		
		addRenderableWidget(exportButton = Button
			.builder(Component.literal("Export"), b -> pressExportFormat())
			.bounds(58, 8, 50, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Import Prism"), b -> pressImportPrism())
			.bounds(112, 8, 80, 20).build());
		
		addRenderableWidget(checkButton =
			Button.builder(Component.literal("Check"), b -> pressCheckAlts())
				.bounds(width - 50 - 8 - 52, 8, 50, 20).build());
		
		addRenderableWidget(logoutButton =
			Button.builder(Component.literal("Logout"), b -> pressLogout())
				.bounds(width - 50 - 8, 8, 50, 20).build());
		
		// ---- AltBot controls ----
		int botX = width / 2 - 206;
		addRenderableWidget(botConnectButton = Button
			.builder(Component.literal("Connect Bot"), b -> pressBotConnect())
			.bounds(botX, height - 100, 100, 20).build());
		
		addRenderableWidget(autoRespawnButton =
			Button.builder(getAutoRespawnLabel(), b -> pressToggleAutoRespawn())
				.bounds(botX + 104, height - 100, 100, 20).build());
		
		addRenderableWidget(botDisconnectButton = Button
			.builder(Component.literal("Disconnect Bot"),
				b -> pressBotDisconnect())
			.bounds(botX + 208, height - 100, 100, 20).build());
		
		addRenderableWidget(botProxyButton =
			Button.builder(getBotProxyLabel(), b -> pressBotProxyMode())
				.bounds(botX + 312, height - 100, 100, 20).build());
		
		addRenderableWidget(botSwitchButton = Button
			.builder(Component.literal("Switch To"), b -> pressBotSwitch())
			.bounds(botX + 52, height - 76, 100, 20).build());
		
		addRenderableWidget(botSendChatButton = Button
			.builder(Component.literal("Send Chat"), b -> pressBotSendChat())
			.bounds(botX + 156, height - 76, 100, 20).build());
		
		addRenderableWidget(botDetailsButton = Button
			.builder(Component.literal("Bot Details"), b -> pressBotDetails())
			.bounds(botX + 260, height - 76, 100, 20).build());
		
		updateAltButtons();
		boolean windowMode = !minecraft.options.fullscreen().get();
		importButton.active = windowMode;
		exportButton.active = windowMode;
	}
	
	private void updateAltButtons()
	{
		if(useButton == null || starButton == null || editButton == null
			|| deleteButton == null || logoutButton == null
			|| checkButton == null)
			return;
		
		if(importInProgress || importPrismInProgress || exportInProgress)
		{
			useButton.active = false;
			if(randomButton != null)
				randomButton.active = false;
			starButton.active = false;
			editButton.active = false;
			deleteButton.active = false;
			logoutButton.active = false;
			checkButton.active = false;
			if(importButton != null)
				importButton.active = false;
			if(exportButton != null)
				exportButton.active = false;
			setBotButtonsInactive();
			return;
		}
		
		if(editValidationInProgress)
		{
			useButton.active = false;
			if(randomButton != null)
				randomButton.active = false;
			starButton.active = false;
			editButton.active = false;
			deleteButton.active = false;
			logoutButton.active = false;
			checkButton.active = false;
			if(importButton != null)
				importButton.active = false;
			if(exportButton != null)
				exportButton.active = false;
			setBotButtonsInactive();
			return;
		}
		
		int selectionCount = listGui != null ? listGui.getSelectionCount() : 0;
		boolean hasSingleSelection = selectionCount == 1;
		
		useButton.active = hasSingleSelection;
		if(randomButton != null)
			randomButton.active =
				!randomLoginInProgress && !altManager.getList().isEmpty();
		starButton.active = hasSingleSelection;
		editButton.active = hasSingleSelection;
		deleteButton.active = selectionCount > 0;
		
		logoutButton.active =
			((IMinecraftClient)minecraft).getWurstSession() != null;
		
		checkButton.active = !autoCheckInProgress
			&& altManager.getList().stream().anyMatch(alt -> !alt.isCracked());
		
		if(importButton != null)
			importButton.active = !importInProgress && !importPrismInProgress
				&& !minecraft.options.fullscreen().get();
		
		if(exportButton != null)
			exportButton.active = !importInProgress && !importPrismInProgress
				&& !minecraft.options.fullscreen().get();
		
		updateBotButtons();
	}
	
	private void setBotButtonsInactive()
	{
		if(botConnectButton != null)
			botConnectButton.active = false;
		if(botDisconnectButton != null)
			botDisconnectButton.active = false;
		if(autoRespawnButton != null)
			autoRespawnButton.active = false;
		if(botSwitchButton != null)
			botSwitchButton.active = false;
		if(botSendChatButton != null)
			botSendChatButton.active = false;
		if(botDetailsButton != null)
			botDetailsButton.active = false;
		if(botProxyButton != null)
			botProxyButton.active = false;
	}
	
	private void updateBotButtons()
	{
		if(botConnectButton == null || botDisconnectButton == null
			|| autoRespawnButton == null || botSwitchButton == null
			|| botSendChatButton == null || botDetailsButton == null
			|| botProxyButton == null)
			return;
		botProxyButton.setMessage(getBotProxyLabel());
		botProxyButton.active = true;
		
		Alt alt = listGui != null ? listGui.getSelectedAlt() : null;
		boolean hasToken = alt instanceof TokenAlt;
		TokenAlt token = hasToken ? (TokenAlt)alt : null;
		
		AltBotManager botManager = WurstClient.INSTANCE.getAltBotManager();
		boolean switchBusy =
			WurstClient.INSTANCE.getAltSwitchController().isBusy();
		boolean onServer = net.wurstclient.altbot.AltBotUtils.isOnServer();
		boolean botStateFailed = hasToken && token != null
			&& botManager.getState(token).getState() == BotState.FAILED;
		
		botConnectButton.active = hasToken && token != null && onServer
			&& !botManager.isActiveClientAlt(token)
			&& !botManager.isBotConnected(token) && !switchBusy;
		
		botDisconnectButton.active = hasToken && token != null
			&& (botManager.isBotConnected(token) || botStateFailed);
		autoRespawnButton.active = true;
		autoRespawnButton.setMessage(getAutoRespawnLabel());
		
		botSwitchButton.active = hasToken && token != null
			&& !botManager.isActiveClientAlt(token) && !switchBusy;
		
		botSendChatButton.active =
			hasToken && token != null && botManager.isBotReady(token);
		
		botDetailsButton.active = hasToken;
	}
	
	@Override
	public void tick()
	{
		if(--botButtonRefreshTicks <= 0)
		{
			botButtonRefreshTicks = 10;
			updateAltButtons();
		}
	}
	
	private void pressToggleAutoLogin()
	{
		altManager.setAutoLoginLastAltEnabled(
			!altManager.isAutoLoginLastAltEnabled());
		if(autoLoginButton != null)
			autoLoginButton.setMessage(getAutoLoginLabel());
	}
	
	private Component getAutoLoginLabel()
	{
		return Component.literal("Auto Alt Login: "
			+ (altManager.isAutoLoginLastAltEnabled() ? "ON" : "OFF"));
	}
	
	private void pressToggleAutoRespawn()
	{
		AltBotManager manager = WurstClient.INSTANCE.getAltBotManager();
		manager.setAutoRespawnEnabled(!manager.isAutoRespawnEnabled());
		if(autoRespawnButton != null)
			autoRespawnButton.setMessage(getAutoRespawnLabel());
	}
	
	private Component getAutoRespawnLabel()
	{
		return Component.literal("Auto Respawn: "
			+ (WurstClient.INSTANCE.getAltBotManager().isAutoRespawnEnabled()
				? "ON" : "OFF"));
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ENTER)
			useButton.onPress(context);
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_4)
		{
			onClose();
			return true;
		}
		
		return super.mouseClicked(context, doubleClick);
	}
	
	private void pressLogin()
	{
		Alt alt = listGui.getSelectedAlt();
		if(alt == null)
			return;
		
		pendingLogin = alt;
		Component text = Component.literal("Login as this alt?");
		Component message =
			Component.literal("Log in as \"" + alt.getDisplayName() + "\"?");
		ConfirmScreen screen = new ConfirmScreen(this::confirmLogin, text,
			message, Component.literal("Login"), Component.literal("Cancel"));
		minecraft.gui.setScreen(screen);
	}
	
	private void confirmLogin(boolean confirmed)
	{
		Alt alt = pendingLogin;
		pendingLogin = null;
		
		if(!confirmed || alt == null)
		{
			minecraft.gui.setScreen(this);
			return;
		}
		
		// If this account is connected as a bot, disconnect it first so we
		// don't end up with a duplicate session on the server.
		if(alt instanceof TokenAlt tokenAlt
			&& WurstClient.INSTANCE.getAltBotManager().isBotConnected(tokenAlt))
		{
			minecraft.gui.setScreen(this);
			net.wurstclient.util.ChatUtils.message("Disconnecting bot \""
				+ tokenAlt.getDisplayName() + "\" before logging in...");
			WurstClient.INSTANCE.getAltBotManager().disconnectBot(tokenAlt,
				() -> doLogin(alt));
			return;
		}
		
		doLogin(alt);
	}
	
	private void doLogin(Alt alt)
	{
		try
		{
			altManager.login(alt);
			clearLoginFailure(alt);
			minecraft.gui.setScreen(new AltLoginSuccessScreen(prevScreen,
				minecraft.getUser().getName()));
			
		}catch(LoginException e)
		{
			errorTimer = 8;
			recordLoginFailure(alt, e);
			if(canRefreshAccessToken(alt, e))
			{
				minecraft.gui.setScreen(new RefreshAccessTokenScreen(this,
					altManager, (TokenAlt)alt, this::doLogin));
				return;
			}
			
			minecraft.gui
				.setScreen(new AltLoginFailedScreen(this, e.getMessage()));
		}
	}
	
	private boolean canRefreshAccessToken(Alt alt, LoginException exception)
	{
		if(!(alt instanceof TokenAlt tokenAlt)
			|| !tokenAlt.getRefreshToken().isEmpty() || exception == null)
			return false;
		
		String message = exception.getMessage();
		return message != null && message.contains("401");
	}
	
	private void pressLoginRandom()
	{
		if(randomLoginInProgress)
			return;
		
		randomLoginInProgress = true;
		updateAltButtons();
		
		Thread thread =
			new Thread(this::runRandomLogin, "Wurst Alt Random Login");
		thread.setDaemon(true);
		thread.start();
	}
	
	private void runRandomLogin()
	{
		IMinecraftClient imc = (IMinecraftClient)minecraft;
		User previousSession = imc.getWurstSession();
		boolean keepNewSession = false;
		
		try
		{
			List<Alt> list = new ArrayList<>(altManager.getList());
			if(list.isEmpty())
			{
				minecraft.execute(() -> {
					randomLoginInProgress = false;
					updateAltButtons();
				});
				return;
			}
			
			Collections.shuffle(list);
			
			for(Alt alt : list)
			{
				if(!isOpenScreen())
					return;
				
				setChecking(alt, true);
				try
				{
					altManager.login(alt);
					keepNewSession = true;
					clearLoginFailure(alt);
					String name = minecraft.getUser().getName();
					minecraft.execute(() -> {
						randomLoginInProgress = false;
						updateAltButtons();
						if(minecraft.gui.screen() == this)
							minecraft.gui.setScreen(
								new AltLoginSuccessScreen(prevScreen, name));
					});
					return;
					
				}catch(LoginException e)
				{
					recordLoginFailure(alt, e);
					
				}finally
				{
					setChecking(alt, false);
				}
			}
			
			if(!isOpenScreen())
				return;
			
			errorTimer = 8;
			minecraft.execute(() -> {
				randomLoginInProgress = false;
				updateAltButtons();
				if(minecraft.gui.screen() == this)
					minecraft.gui.setScreen(new AltLoginFailedScreen(this,
						"Random login failed for all accounts."));
			});
			
		}finally
		{
			if(!keepNewSession)
				imc.setWurstSession(previousSession);
		}
	}
	
	private void pressLogout()
	{
		altManager.clearLastLoggedInAlt();
		IMinecraftClient imc = (IMinecraftClient)minecraft;
		User original = imc.getOriginalSession();
		boolean restored = imc.restoreOriginalSession();
		String currentName = minecraft.getUser().getName();
		String expectedName =
			original == null ? currentName : original.getName();
		boolean matchesOriginal =
			expectedName.equalsIgnoreCase(minecraft.getUser().getName());
		restored = restored && matchesOriginal;
		
		updateAltButtons();
		minecraft.gui
			.setScreen(new AltLogoutResultScreen(this, restored, currentName));
	}
	
	private void pressBotConnect()
	{
		Alt alt = listGui.getSelectedAlt();
		if(!(alt instanceof TokenAlt tokenAlt))
			return;
		
		AltBotManager botManager = WurstClient.INSTANCE.getAltBotManager();
		if(botManager.getBotProxyMode() == AltBotManager.BotProxyMode.SELECTED)
		{
			minecraft.gui.setScreen(new ProxyManagerScreen(this,
				WurstClient.INSTANCE.getProxyManager(), proxy -> botManager
					.connectBotToCurrentServer(tokenAlt, proxy)));
			return;
		}
		botManager.connectBotToCurrentServer(tokenAlt);
	}
	
	private void pressBotProxyMode()
	{
		WurstClient.INSTANCE.getAltBotManager().cycleBotProxyMode();
		updateAltButtons();
	}
	
	private Component getBotProxyLabel()
	{
		return Component.literal("Bot Proxy: "
			+ WurstClient.INSTANCE.getAltBotManager().getBotProxyMode());
	}
	
	private void pressBotDisconnect()
	{
		Alt alt = listGui.getSelectedAlt();
		if(!(alt instanceof TokenAlt tokenAlt))
			return;
		
		WurstClient.INSTANCE.getAltBotManager().disconnectBot(tokenAlt);
	}
	
	private void pressBotSwitch()
	{
		Alt alt = listGui.getSelectedAlt();
		if(!(alt instanceof TokenAlt tokenAlt))
			return;
		
		WurstClient.INSTANCE.getAltSwitchController().startSwitch(tokenAlt);
	}
	
	private void pressBotSendChat()
	{
		Alt alt = listGui.getSelectedAlt();
		if(!(alt instanceof TokenAlt tokenAlt))
			return;
		
		minecraft.gui.setScreen(new AltBotSendChatScreen(this, tokenAlt));
	}
	
	private void pressBotDetails()
	{
		Alt alt = listGui.getSelectedAlt();
		if(!(alt instanceof TokenAlt tokenAlt))
			return;
		
		minecraft.gui.setScreen(new AltBotDetailsScreen(this, tokenAlt));
	}
	
	private void pressCheckAlts()
	{
		if(autoCheckInProgress)
			return;
		
		List<Alt> allPremium = altManager.getList().stream()
			.filter(alt -> !alt.isCracked()).toList();
		List<Alt> unchecked =
			allPremium.stream().filter(Alt::isUncheckedPremium).toList();
		List<Alt> failed =
			allPremium.stream().filter(alt -> failedLogins.contains(alt))
				.filter(alt -> !unchecked.contains(alt)).toList();
		List<Alt> remaining =
			allPremium.stream().filter(alt -> !unchecked.contains(alt))
				.filter(alt -> !failed.contains(alt)).toList();
		
		if(unchecked.isEmpty() && failed.isEmpty() && remaining.isEmpty())
		{
			updateAltButtons();
			return;
		}
		
		List<Alt> prioritized = new ArrayList<>();
		prioritized.addAll(unchecked);
		prioritized.addAll(failed);
		
		List<Alt> firstPhase = List.copyOf(prioritized);
		List<Alt> secondPhase = List.copyOf(remaining);
		List<SocksProxy> proxies = getTokenCheckProxies();
		if(!proxies.isEmpty())
		{
			minecraft.gui.setScreen(new ConfirmScreen(useProxies -> {
				minecraft.gui.setScreen(this);
				startAutoCheck(firstPhase, secondPhase,
					useProxies ? proxies : Collections.emptyList());
			}, Component.literal("Use configured proxies?"),
				Component.literal("Wurst has " + proxies.size() + " proxy entr"
					+ (proxies.size() == 1 ? "y" : "ies")
					+ " configured. Use them for account checks?")));
			return;
		}
		
		startAutoCheck(firstPhase, secondPhase, proxies);
	}
	
	private void startAutoCheck(List<Alt> firstPhase, List<Alt> secondPhase,
		List<SocksProxy> proxies)
	{
		autoCheckInProgress = true;
		updateAltButtons();
		
		Thread thread = new Thread(
			() -> runAutoCheckAndDedupe(firstPhase, secondPhase, proxies),
			"Wurst Alt Auto-Check");
		thread.setDaemon(true);
		thread.start();
	}
	
	private boolean promptContinueWithRemaining(int remainingCount)
	{
		if(minecraft == null)
			return false;
		
		AtomicBoolean result = new AtomicBoolean(false);
		CountDownLatch latch = new CountDownLatch(1);
		
		minecraft.execute(() -> {
			if(minecraft.gui.screen() != this)
			{
				latch.countDown();
				return;
			}
			
			Component title =
				Component.literal("Continue checking remaining accounts?");
			Component message =
				Component.literal("Checked prioritized accounts. Continue with "
					+ remainingCount + " already-checked account"
					+ (remainingCount == 1 ? "?" : "s?"));
			ConfirmScreen screen = new ConfirmScreen(confirmed -> {
				result.set(confirmed);
				minecraft.gui.setScreen(this);
				latch.countDown();
			}, title, message, Component.literal("Continue"),
				Component.literal("Stop"));
			minecraft.gui.setScreen(screen);
		});
		
		try
		{
			latch.await();
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
		
		return result.get();
	}
	
	private void pressFavorite()
	{
		Alt alt = listGui.getSelectedAlt();
		if(alt == null)
			return;
		
		altManager.toggleFavorite(alt);
		listGui.setSelected(null);
	}
	
	private void pressEdit()
	{
		Alt alt = listGui.getSelectedAlt();
		if(alt == null)
			return;
		
		if(alt instanceof TokenAlt tokenAlt)
		{
			validateTokenAltBeforeEditing(tokenAlt);
			return;
		}
		
		minecraft.gui.setScreen(new EditAltScreen(this, altManager, alt));
	}
	
	private void validateTokenAltBeforeEditing(TokenAlt tokenAlt)
	{
		if(editValidationInProgress)
			return;
		
		List<SocksProxy> proxies = getTokenCheckProxies();
		if(proxies.isEmpty())
		{
			startTokenAltValidation(tokenAlt, null);
			return;
		}
		
		minecraft.gui.setScreen(new ConfirmScreen(useProxies -> {
			minecraft.gui.setScreen(this);
			startTokenAltValidation(tokenAlt,
				useProxies ? proxies.get(0) : null);
		}, Component.literal("Use configured proxies?"),
			Component.literal("Wurst has " + proxies.size() + " proxy entr"
				+ (proxies.size() == 1 ? "y" : "ies")
				+ " configured. Use one for this token check?")));
	}
	
	private void startTokenAltValidation(TokenAlt tokenAlt, SocksProxy proxy)
	{
		
		editValidationInProgress = true;
		editValidationStatus = "Validating token account...";
		updateAltButtons();
		
		Thread thread = new Thread(() -> {
			try
			{
				MinecraftProfile profile =
					tokenAlt.authenticateWithoutSession(proxy);
				
				minecraft.execute(() -> {
					editValidationInProgress = false;
					editValidationStatus = "";
					altManager.saveTokenAlt(tokenAlt);
					
					String resolvedName = profile.getName();
					if(resolvedName != null && !resolvedName.isBlank())
					{
						altManager.updateTokenAltName(tokenAlt, resolvedName);
						AltRenderer.refreshSkin(resolvedName);
					}
					
					if(minecraft.gui.screen() == this)
						minecraft.gui.setScreen(
							new EditTokenAltScreen(this, altManager, tokenAlt));
				});
				
			}catch(LoginException e)
			{
				minecraft.execute(() -> {
					editValidationInProgress = false;
					editValidationStatus = "";
					errorTimer = 8;
					updateAltButtons();
					
					String details =
						e.getMessage() == null || e.getMessage().isBlank()
							? "Unknown error" : e.getMessage();
					minecraft.gui.setScreen(new AltLoginFailedScreen(this,
						"Token validation failed: " + details));
				});
			}
		}, "Wurst Edit Token Validation");
		
		thread.setDaemon(true);
		thread.start();
	}
	
	private List<SocksProxy> getTokenCheckProxies()
	{
		return WurstClient.INSTANCE.getProxyManager().getProxies();
	}
	
	private void pressDelete()
	{
		List<Alt> selected = listGui.getSelectedAlts();
		if(selected.isEmpty())
			return;
		
		pendingDeletion = List.copyOf(selected);
		boolean plural = pendingDeletion.size() > 1;
		
		Component text = plural ? Component.literal("Remove selected alts?")
			: Component.literal("Remove this alt?");
		
		Component message;
		if(plural)
		{
			message = Component.literal(pendingDeletion.size()
				+ " accounts will be lost forever! (A long time!)");
		}else
		{
			String altName = pendingDeletion.get(0).getDisplayName();
			message = Component.literal(
				"\"" + altName + "\" will be lost forever! (A long time!)");
		}
		
		ConfirmScreen screen = new ConfirmScreen(this::confirmRemove, text,
			message, Component.literal("Delete"), Component.literal("Cancel"));
		minecraft.gui.setScreen(screen);
	}
	
	private void pressImportAlts()
	{
		if(importInProgress)
			return;
		
		try
		{
			Process process = MultiProcessingUtils.startProcessWithIO(
				ImportAltsFileChooser.class,
				WurstClient.INSTANCE.getWurstFolder().toString());
			
			Path path = getFileChooserPath(process);
			process.waitFor();
			startImport(path);
			
		}catch(IOException | InterruptedException e)
		{
			importStatus = "Import failed: " + e.getClass().getSimpleName();
			e.printStackTrace();
		}
	}
	
	private void pressImportPrism()
	{
		if(importPrismInProgress)
			return;
		
		String appData = System.getenv("APPDATA");
		if(appData == null)
		{
			importStatus = "Import Prism failed: APPDATA not found";
			return;
		}
		
		Path prismPath = Paths.get(appData, "PrismLauncher", "accounts.json");
		
		if(!Files.exists(prismPath))
		{
			importStatus =
				"Import Prism failed: File not found at " + prismPath;
			return;
		}
		
		importPrismInProgress = true;
		importStatus = "Starting Prism import...";
		importDone = 0;
		importTotal = 0;
		importHasCounts = false;
		updateAltButtons();
		
		List<Alt> existing = new ArrayList<>(altManager.getList());
		
		Thread thread = new Thread(() -> runPrismImport(prismPath, existing),
			"Wurst Alt Prism Import");
		thread.setDaemon(true);
		thread.start();
	}
	
	private void runPrismImport(Path path, List<Alt> existing)
	{
		try
		{
			importStatus = "Reading Prism Launcher file...";
			ArrayList<Alt> imported =
				importAsPrismJSON(path, this::setImportProgress);
			
			importStatus = "Filtering duplicates...";
			ImportResult result = filterDuplicates(imported, existing);
			int totalImported = imported.size();
			
			minecraft.execute(() -> {
				try
				{
					if(result.addedCount > 0)
						altManager.addAll(result.toAdd);
					
					altManager.dedupeByUsernamePreferRefreshToken();
					if(minecraft.gui.screen() == this)
						reloadScreen();
					
					importStatus = "Imported " + result.addedCount + " of "
						+ totalImported + " accounts from Prism Launcher ("
						+ result.duplicateCount + " duplicates skipped).";
					
				}finally
				{
					importPrismInProgress = false;
					importHasCounts = false;
					updateAltButtons();
				}
			});
			
		}catch(Exception e)
		{
			minecraft.execute(() -> {
				importStatus =
					"Prism import failed: " + e.getClass().getSimpleName();
				importPrismInProgress = false;
				importHasCounts = false;
				updateAltButtons();
			});
			e.printStackTrace();
		}
	}
	
	private void startImport(Path path)
	{
		importInProgress = true;
		importStatus = "Starting import...";
		importDone = 0;
		importTotal = 0;
		importHasCounts = false;
		updateAltButtons();
		
		List<Alt> existing = new ArrayList<>(altManager.getList());
		
		Thread thread =
			new Thread(() -> runImport(path, existing), "Wurst Alt Import");
		thread.setDaemon(true);
		thread.start();
	}
	
	private void runImport(Path path, List<Alt> existing)
	{
		try
		{
			importStatus = "Reading file...";
			ArrayList<Alt> imported = importAlts(path, this::setImportProgress);
			
			importStatus = "Filtering duplicates...";
			ImportResult result = filterDuplicates(imported, existing);
			int totalImported = imported.size();
			
			minecraft.execute(() -> {
				try
				{
					if(result.addedCount > 0)
						altManager.addAll(result.toAdd);
					
					altManager.dedupeByUsernamePreferRefreshToken();
					if(minecraft.gui.screen() == this)
						reloadScreen();
					
					importStatus = "Imported " + result.addedCount + " of "
						+ totalImported + " accounts (" + result.duplicateCount
						+ " duplicates skipped).";
					
				}finally
				{
					importInProgress = false;
					importHasCounts = false;
					updateAltButtons();
				}
			});
			
		}catch(Exception e)
		{
			minecraft.execute(() -> {
				importStatus = "Import failed: " + e.getClass().getSimpleName();
				importInProgress = false;
				importHasCounts = false;
				updateAltButtons();
			});
			e.printStackTrace();
		}
	}
	
	private ArrayList<Alt> importAlts(Path path, Consumer<String> progress)
		throws IOException, JsonException
	{
		if(path.getFileName().toString().endsWith(".json"))
			return importAsJSON(path, progress);
		
		return importAsTXT(path, progress);
	}
	
	private ArrayList<Alt> importAsJSON(Path path, Consumer<String> progress)
		throws IOException, JsonException
	{
		progress.accept("Parsing JSON...");
		importHasCounts = false;
		WsonObject wson = JsonUtils.parseFileToObject(path);
		return AltsFile.parseJson(wson);
	}
	
	private ArrayList<Alt> importAsPrismJSON(Path path,
		Consumer<String> progress) throws IOException, JsonException
	{
		progress.accept("Parsing Prism Launcher JSON...");
		importHasCounts = false;
		
		// Read the raw file and parse with plain Gson to avoid
		// any WsonObject quirks with hyphenated keys like "msa-client-id".
		String raw;
		try(BufferedReader reader = Files.newBufferedReader(path))
		{
			raw = reader.lines().collect(Collectors.joining());
		}
		JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
		
		int formatVersion = root.has("formatVersion")
			? root.get("formatVersion").getAsInt() : 0;
		if(formatVersion < 1)
			throw new JsonException(
				"Invalid or missing formatVersion in Prism Launcher file");
		
		if(!root.has("accounts"))
			throw new JsonException(
				"No accounts array found in Prism Launcher file");
		
		JsonArray accountsArray = root.getAsJsonArray("accounts");
		int total = accountsArray.size();
		int done = 0;
		ArrayList<Alt> alts = new ArrayList<>();
		
		for(JsonElement elem : accountsArray)
		{
			if(!elem.isJsonObject())
				continue;
			
			JsonObject account = elem.getAsJsonObject();
			done++;
			if(done == 1 || done == total || done % 10 == 0)
				setImportProgressCounts("Importing Prism accounts", done,
					total);
			
			// Get profile name
			String name = "";
			if(account.has("profile") && account.get("profile").isJsonObject())
			{
				JsonObject profile = account.getAsJsonObject("profile");
				if(profile.has("name"))
					name = profile.get("name").getAsString();
			}
			
			// Get MSA tokens
			String token = "";
			String refreshToken = "";
			if(account.has("msa") && account.get("msa").isJsonObject())
			{
				JsonObject msa = account.getAsJsonObject("msa");
				if(msa.has("token"))
					token = msa.get("token").getAsString();
				if(msa.has("refresh_token"))
					refreshToken = msa.get("refresh_token").getAsString();
			}
			
			// Get MSA client ID (used by Prism Launcher to refresh)
			String clientId = "";
			if(account.has("msa-client-id"))
				clientId = account.get("msa-client-id").getAsString();
			
			System.out.println("[PrismImport] name=" + name + " clientId='"
				+ clientId + "' tokenLen=" + token.length() + " refreshLen="
				+ refreshToken.length());
			
			// Skip accounts without any token
			if(token.isEmpty() && refreshToken.isEmpty())
				continue;
			
			alts.add(new TokenAlt(token, refreshToken, name, false, clientId));
		}
		
		return alts;
	}
	
	private ArrayList<Alt> importAsTXT(Path path, Consumer<String> progress)
		throws IOException
	{
		progress.accept("Reading text lines...");
		List<String> lines = Files.readAllLines(path);
		ArrayList<Alt> alts = new ArrayList<>();
		ArrayList<String> rawTokenLines = new ArrayList<>();
		int totalLines = lines.size();
		setImportProgressCounts("Parsing lines", 0, totalLines);
		int lineIndex = 0;
		
		for(String line : lines)
		{
			lineIndex++;
			if(lineIndex == 1 || lineIndex == totalLines || lineIndex % 25 == 0)
				setImportProgressCounts("Parsing lines", lineIndex, totalLines);
			
			String trimmed = line.trim();
			if(trimmed.isEmpty())
				continue;
			
			if(isRawTokenLine(trimmed))
			{
				rawTokenLines.add(trimmed);
				continue;
			}
			
			String[] data = trimmed.split(":", -1);
			
			if(data.length >= 4 && data[0].equalsIgnoreCase("token"))
			{
				String token = data[1];
				String refreshToken = data[2];
				String name = data[3];
				String clientId = data.length >= 5 ? data[4] : "";
				
				if(!token.isEmpty() || !refreshToken.isEmpty())
					alts.add(new TokenAlt(token, refreshToken, name, false,
						clientId));
				
				continue;
			}
			
			switch(data.length)
			{
				case 1:
				alts.add(new CrackedAlt(data[0]));
				break;
				
				case 2:
				if(isTokenCredential(data[1]))
					alts.add(new TokenAlt("", data[1], data[0], false));
				else
					alts.add(new MojangAlt(data[0], data[1]));
				break;
			}
		}
		
		alts.addAll(resolveRawTokenLines(rawTokenLines, progress));
		
		return alts;
	}
	
	private boolean isRawTokenLine(String line)
	{
		if(line.contains(":"))
			return false;
		
		if(line.startsWith("M.") && line.length() > 20)
			return true;
		
		return (line.startsWith("e") || line.startsWith("Ew"))
			&& line.length() > 80;
	}
	
	private boolean isTokenCredential(String value)
	{
		String trimmed = value == null ? "" : value.trim();
		return trimmed.startsWith("M.") || trimmed.startsWith("eyJ")
			|| trimmed.startsWith("Ew");
	}
	
	private List<Alt> resolveRawTokenLines(List<String> lines,
		Consumer<String> progress)
	{
		if(lines.isEmpty())
			return Collections.emptyList();
		
		IMinecraftClient imc = (IMinecraftClient)minecraft;
		User previousSession = imc.getWurstSession();
		LinkedHashMap<String, TokenAlt> byName = new LinkedHashMap<>();
		ArrayList<Alt> unresolved = new ArrayList<>();
		
		try
		{
			int total = lines.size();
			int done = 0;
			setImportProgressCounts("Resolving tokens", done, total);
			
			for(String tokenLine : lines)
			{
				done++;
				setImportProgressCounts("Resolving tokens", done, total);
				
				boolean isRefreshToken = tokenLine.startsWith("M.");
				String resolvedToken = tokenLine;
				
				try
				{
					if(isRefreshToken)
						resolvedToken = MicrosoftLoginManager
							.loginWithRefreshTokenAndGetUpdatedToken(tokenLine,
								null);
					else
						MicrosoftLoginManager.loginWithToken(tokenLine);
					
					String name = minecraft.getUser().getName();
					if(name == null || name.isEmpty())
					{
						unresolved.add(isRefreshToken
							? new TokenAlt("", resolvedToken, "", false)
							: new TokenAlt(tokenLine, "", "", false));
						continue;
					}
					
					String key = name.toLowerCase(Locale.ROOT);
					TokenAlt importedAlt = isRefreshToken
						? new TokenAlt("", resolvedToken, name, false)
						: new TokenAlt(tokenLine, "", name, false);
					
					TokenAlt existing = byName.get(key);
					if(existing == null || (existing.getRefreshToken().isEmpty()
						&& isRefreshToken))
						byName.put(key, importedAlt);
					
				}catch(LoginException e)
				{
					unresolved.add(
						isRefreshToken ? new TokenAlt("", tokenLine, "", false)
							: new TokenAlt(tokenLine, "", "", false));
				}
			}
			
		}finally
		{
			imc.setWurstSession(previousSession);
		}
		
		ArrayList<Alt> result = new ArrayList<>();
		result.addAll(byName.values());
		result.addAll(unresolved);
		return result;
	}
	
	private void setImportProgress(String status)
	{
		importStatus = status;
		importHasCounts = false;
	}
	
	private void setImportProgressCounts(String phase, int done, int total)
	{
		importStatus = phase + "... " + done + "/" + total;
		importDone = Math.max(0, done);
		importTotal = Math.max(0, total);
		importHasCounts = total > 0;
	}
	
	private ImportResult filterDuplicates(List<Alt> imported,
		List<Alt> existing)
	{
		HashSet<String> seen = new HashSet<>();
		for(Alt alt : existing)
			registerKeys(seen, alt);
		
		ArrayList<Alt> toAdd = new ArrayList<>();
		int duplicateCount = 0;
		
		for(Alt alt : imported)
		{
			if(isDuplicate(seen, alt))
			{
				duplicateCount++;
				continue;
			}
			
			registerKeys(seen, alt);
			toAdd.add(alt);
		}
		
		return new ImportResult(toAdd, duplicateCount);
	}
	
	private boolean isDuplicate(HashSet<String> seen, Alt alt)
	{
		String credentialKey = getCredentialKey(alt);
		if(credentialKey != null && seen.contains(credentialKey))
			return true;
		
		String name = alt.getName();
		if(name != null && !name.isEmpty())
		{
			String nameKey = "name:" + name.toLowerCase(Locale.ROOT);
			if(seen.contains(nameKey))
				return true;
		}
		
		return false;
	}
	
	private void registerKeys(HashSet<String> seen, Alt alt)
	{
		String credentialKey = getCredentialKey(alt);
		if(credentialKey != null)
			seen.add(credentialKey);
		
		String name = alt.getName();
		if(name != null && !name.isEmpty())
			seen.add("name:" + name.toLowerCase(Locale.ROOT));
	}
	
	private String getCredentialKey(Alt alt)
	{
		if(alt instanceof CrackedAlt cracked)
			return "cracked:" + cracked.getName().toLowerCase(Locale.ROOT);
		
		if(alt instanceof MojangAlt mojang)
			return "mojang:" + mojang.getEmail().toLowerCase(Locale.ROOT);
		
		if(alt instanceof TokenAlt token)
		{
			String refreshToken = token.getRefreshToken();
			if(!refreshToken.isEmpty())
				return "refresh:" + refreshToken;
			
			return "token:" + token.getToken();
		}
		
		return null;
	}
	
	private void runAutoCheckAndDedupe(List<Alt> prioritized,
		List<Alt> remaining, List<SocksProxy> proxies)
	{
		IMinecraftClient imc = (IMinecraftClient)minecraft;
		User previousSession = imc.getWurstSession();
		boolean changed = false;
		int proxyIndex = 0;
		
		try
		{
			for(Alt alt : prioritized)
			{
				if(!isOpenScreen())
					return;
				
				setChecking(alt, true);
				try
				{
					SocksProxy proxy =
						nextTokenCheckProxy(alt, proxies, proxyIndex);
					if(alt instanceof TokenAlt)
						proxyIndex++;
					MicrosoftLoginManager.setAuthenticationProxy(proxy);
					try
					{
						altManager.login(alt);
					}finally
					{
						MicrosoftLoginManager.clearAuthenticationProxy();
					}
					clearLoginFailure(alt);
					changed = true;
					
				}catch(LoginException e)
				{
					recordLoginFailure(alt, e);
				}finally
				{
					setChecking(alt, false);
				}
			}
			
			if(!isOpenScreen())
				return;
			
			if(!remaining.isEmpty())
			{
				boolean continueWithRemaining =
					promptContinueWithRemaining(remaining.size());
				if(continueWithRemaining)
				{
					for(Alt alt : remaining)
					{
						if(!isOpenScreen())
							return;
						
						setChecking(alt, true);
						try
						{
							SocksProxy proxy =
								nextTokenCheckProxy(alt, proxies, proxyIndex);
							if(alt instanceof TokenAlt)
								proxyIndex++;
							MicrosoftLoginManager.setAuthenticationProxy(proxy);
							try
							{
								altManager.login(alt);
							}finally
							{
								MicrosoftLoginManager
									.clearAuthenticationProxy();
							}
							clearLoginFailure(alt);
							changed = true;
							
						}catch(LoginException e)
						{
							recordLoginFailure(alt, e);
						}finally
						{
							setChecking(alt, false);
						}
					}
				}
			}
			
			if(!isOpenScreen())
				return;
			
			changed |= altManager.dedupeByUsernamePreferRefreshToken();
			
		}finally
		{
			imc.setWurstSession(previousSession);
			autoCheckInProgress = false;
			minecraft.execute(() -> {
				if(minecraft.gui.screen() == this)
					updateAltButtons();
			});
		}
		
		if(changed)
			minecraft.execute(() -> {
				if(minecraft.gui.screen() == this)
					reloadScreen();
			});
	}
	
	private SocksProxy nextTokenCheckProxy(Alt alt, List<SocksProxy> proxies,
		int index)
	{
		if(!(alt instanceof TokenAlt) || proxies.isEmpty())
			return null;
		return proxies.get(index % proxies.size());
	}
	
	private void setChecking(Alt alt, boolean checking)
	{
		synchronized(checkingAlts)
		{
			if(checking)
				checkingAlts.add(alt);
			else
				checkingAlts.remove(alt);
		}
	}
	
	private void recordLoginFailure(Alt alt, LoginException exception)
	{
		if(alt == null)
			return;
		
		failedLogins.add(alt);
		String reason = shortenLoginFailureReason(
			exception != null ? exception.getMessage() : null);
		failedLoginReasons.put(alt, reason);
	}
	
	private void clearLoginFailure(Alt alt)
	{
		if(alt == null)
			return;
		
		failedLogins.remove(alt);
		failedLoginReasons.remove(alt);
	}
	
	private String getFailedReason(Alt alt)
	{
		String reason = failedLoginReasons.get(alt);
		return reason == null ? "" : reason;
	}
	
	private String shortenLoginFailureReason(String message)
	{
		if(message == null || message.isBlank())
			return "error";
		
		String lower = message.toLowerCase(Locale.ROOT);
		if(lower.contains("banned"))
			return "banned";
		if(lower.contains("deleted") || lower.contains("deactivated")
			|| lower.contains("closed")
			|| lower.contains("account does not exist")
			|| lower.contains("no account")
			|| lower.contains("cannot find account"))
			return "deleted";
		if(lower.contains("blocked") || lower.contains("block"))
			return "blocked";
		if(lower.contains("restricted"))
			return "restricted";
		if(lower.contains("suspended"))
			return "suspended";
		if(lower.contains("expired"))
			return "expired";
		if(lower.contains("timeout") || lower.contains("timed out"))
			return "timeout";
		if(lower.contains("rate limit") || lower.contains("too many requests")
			|| lower.contains("too many") || lower.contains("429"))
			return "rate-limited";
		if(lower.contains("forbidden") || lower.contains("unauthorized")
			|| lower.contains("invalid token") || lower.contains("invalid")
			|| lower.contains("credentials"))
			return "invalid";
		if(lower.contains("connection") || lower.contains("network"))
			return "network";
		
		Matcher matcher =
			Pattern.compile("\\b([45][0-9]{2})\\b").matcher(message);
		if(matcher.find())
			return matcher.group(1);
		
		return "error";
	}
	
	private boolean isChecking(Alt alt)
	{
		synchronized(checkingAlts)
		{
			return checkingAlts.contains(alt);
		}
	}
	
	private void reloadScreen()
	{
		minecraft.gui.setScreen(new AltManagerScreen(prevScreen, altManager));
	}
	
	private boolean isOpenScreen()
	{
		return !autoCheckCancelled && minecraft != null
			&& minecraft.gui.screen() == this;
	}
	
	private boolean hasUncheckedPremiumAlts()
	{
		return altManager.getList().stream()
			.anyMatch(alt -> !alt.isCracked() && alt.isUncheckedPremium());
	}
	
	private void pressExportFormat()
	{
		if(exportInProgress)
			return;
		
		minecraft.gui.setScreen(
			new ExportTokenFormatScreen(this, this::pressExportAlts));
	}
	
	private void pressExportAlts(ExportTokenFormatScreen.Format format)
	{
		try
		{
			Process process = MultiProcessingUtils.startProcessWithIO(
				ExportAltsFileChooser.class,
				WurstClient.INSTANCE.getWurstFolder().toString(),
				format.getFileChooserArgument());
			
			Path path = getFileChooserPath(process);
			
			process.waitFor();
			
			if(format == ExportTokenFormatScreen.Format.REFRESH_TOKENS)
				exportRefreshTokens(path);
			else if(format == ExportTokenFormatScreen.Format.ACCESS_TOKENS)
				promptAccessTokenExport(path);
			else if(path.getFileName().toString().endsWith(".json"))
				exportAsJSON(path);
			else
				exportAsTXT(path);
			
		}catch(IOException | InterruptedException | JsonException e)
		{
			e.printStackTrace();
		}
	}
	
	private Path getFileChooserPath(Process process) throws IOException
	{
		try(BufferedReader bf =
			new BufferedReader(new InputStreamReader(process.getInputStream(),
				StandardCharsets.UTF_8)))
		{
			String response = bf.readLine();
			
			if(response == null)
				throw new IOException("No response from FileChooser");
			
			try
			{
				return Paths.get(response);
				
			}catch(InvalidPathException e)
			{
				throw new IOException(
					"Response from FileChooser is not a valid path");
			}
		}
	}
	
	private void exportAsJSON(Path path) throws IOException, JsonException
	{
		JsonObject json = AltsFile.createJson(altManager);
		JsonUtils.toJson(json, path);
	}
	
	private void exportAsTXT(Path path) throws IOException
	{
		List<String> lines = new ArrayList<>();
		
		for(Alt alt : altManager.getList())
			lines.add(alt.exportAsTXT());
		
		Files.write(path, lines);
	}
	
	private void exportRefreshTokens(Path path) throws IOException
	{
		List<String> refreshTokens = new ArrayList<>();
		
		for(Alt alt : altManager.getList())
		{
			if(!(alt instanceof TokenAlt tokenAlt))
				continue;
			
			String refreshToken = tokenAlt.getRefreshToken();
			if(!refreshToken.isEmpty())
				refreshTokens.add(refreshToken);
		}
		
		Files.write(path, refreshTokens, StandardCharsets.UTF_8);
	}
	
	private void promptAccessTokenExport(Path path)
	{
		List<SocksProxy> proxies = getTokenCheckProxies();
		if(proxies.isEmpty())
		{
			exportAccessTokens(path, proxies);
			return;
		}
		
		minecraft.gui.setScreen(new ConfirmScreen(useProxies -> {
			minecraft.gui.setScreen(this);
			exportAccessTokens(path,
				useProxies ? proxies : Collections.emptyList());
		}, Component.literal("Use configured proxies?"),
			Component.literal("Wurst has " + proxies.size() + " proxy entr"
				+ (proxies.size() == 1 ? "y" : "ies")
				+ " configured. Use them round-robin for token checks?")));
	}
	
	private void exportAccessTokens(Path path, List<SocksProxy> proxies)
	{
		if(exportInProgress)
			return;
		
		exportInProgress = true;
		importStatus = "Exporting fresh access tokens...";
		updateAltButtons();
		
		Thread thread = new Thread(() -> {
			ArrayList<String> tokens = new ArrayList<>();
			ArrayList<TokenAlt> authenticatedAlts = new ArrayList<>();
			int failures = 0;
			
			int checked = 0;
			for(Alt alt : altManager.getList())
			{
				if(!(alt instanceof TokenAlt tokenAlt))
					continue;
				
				try
				{
					SocksProxy proxy = proxies.isEmpty() ? null
						: proxies.get(checked % proxies.size());
					checked++;
					MinecraftProfile profile =
						tokenAlt.authenticateWithoutSession(proxy);
					tokens.add(profile.getAccessToken());
					authenticatedAlts.add(tokenAlt);
					
				}catch(LoginException e)
				{
					failures++;
				}
			}
			
			try
			{
				if(tokens.isEmpty())
					throw new IOException(
						"None of the token accounts could provide an access token.");
				
				Files.write(path, tokens, StandardCharsets.UTF_8);
				int exported = tokens.size();
				int failed = failures;
				minecraft.execute(() -> {
					for(TokenAlt tokenAlt : authenticatedAlts)
						altManager.saveTokenAlt(tokenAlt);
					finishAccessTokenExport(exported, failed, null);
				});
				
			}catch(IOException e)
			{
				int failed = failures;
				minecraft.execute(() -> {
					for(TokenAlt tokenAlt : authenticatedAlts)
						altManager.saveTokenAlt(tokenAlt);
					finishAccessTokenExport(0, failed, e);
				});
			}
		}, "Wurst Access Token Export");
		thread.setDaemon(true);
		thread.start();
	}
	
	private void finishAccessTokenExport(int exported, int failures,
		IOException error)
	{
		exportInProgress = false;
		String result;
		if(error != null)
			result = "Access-token export failed: " + error.getMessage();
		else
			result = "Exported " + exported + " fresh access token"
				+ (exported == 1 ? "" : "s")
				+ (failures == 0 ? "." : " (" + failures + " failed).");
		
		importStatus = "";
		updateAltButtons();
		minecraft.gui.setScreen(new AlertScreen(
			() -> minecraft.gui.setScreen(this),
			Component.literal("Access-token export"), Component.literal(result),
			Component.translatable("gui.done"), false));
	}
	
	private void confirmGenerate(boolean confirmed)
	{
		if(confirmed)
		{
			ArrayList<Alt> alts = new ArrayList<>();
			for(int i = 0; i < 8; i++)
				alts.add(new CrackedAlt(NameGenerator.generateName()));
			
			altManager.addAll(alts);
		}
		
		shouldAsk = false;
		minecraft.gui.setScreen(this);
	}
	
	private void confirmRemove(boolean confirmed)
	{
		if(confirmed)
			pendingDeletion.forEach(alt -> {
				clearLoginFailure(alt);
				WurstClient.INSTANCE.getAltBotManager().onAltRemoved(alt);
				altManager.remove(alt);
			});
		
		pendingDeletion = Collections.emptyList();
		minecraft.gui.setScreen(this);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		listGui.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		// skin preview
		Alt alt = listGui.getSelectedAlt();
		if(alt != null)
		{
			AltRenderer.drawAltBack(context, alt.getName(),
				(width / 2 - 125) / 2 - 32, height / 2 - 64 - 9, 64, 128);
			AltRenderer.drawAltBody(context, alt.getName(),
				width - (width / 2 - 140) / 2 - 32, height / 2 - 64 - 9, 64,
				128);
		}
		
		// title text
		context.centeredText(font, "Alt Manager", width / 2, 4,
			CommonColors.WHITE);
		context.centeredText(font, "Alts: " + altManager.getList().size(),
			width / 2, 14, CommonColors.LIGHT_GRAY);
		context.centeredText(font,
			"premium: " + altManager.getNumPremium() + ", cracked: "
				+ altManager.getNumCracked(),
			width / 2, 24, CommonColors.LIGHT_GRAY);
		
		if(!importStatus.isEmpty())
			context.centeredText(font, importStatus, width / 2, 42,
				importInProgress ? 0xFFFF55 : CommonColors.LIGHT_GRAY);
		
		if(editValidationInProgress && !editValidationStatus.isBlank())
			context.centeredText(font, editValidationStatus, width / 2, 58,
				0xFFFF55);
		
		if(((IMinecraftClient)minecraft).getWurstSession() != null)
			context.centeredText(font,
				"Logged in as " + minecraft.getUser().getName(), width / 2, 50,
				0x55FF55);
		
		if(WurstClient.INSTANCE.getAltSwitchController().isBusy())
			context.centeredText(font,
				"Switch: "
					+ WurstClient.INSTANCE.getAltSwitchController().getStatus(),
				width / 2, 66, 0xFFFF55);
		
		// red flash for errors
		if(errorTimer > 0)
		{
			int alpha = (int)(Math.min(1, errorTimer / 16F) * 255);
			int color = 0xFF0000 | alpha << 24;
			context.fill(0, 0, width, height, color);
			errorTimer--;
		}
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		renderImportOverlay(context);
		renderAltTooltip(context, mouseX, mouseY);
	}
	
	private void renderImportOverlay(GuiGraphicsExtractor context)
	{
		if(!importInProgress && !importPrismInProgress && !exportInProgress)
			return;
		
		int now = (int)(Util.getMillis() / 450L);
		String dots = ".".repeat(Math.max(1, (now % 3) + 1));
		String headline = exportInProgress ? "Exporting access tokens" + dots
			: "Loading accounts" + dots;
		String status = importStatus == null || importStatus.isBlank()
			? "Please wait..." : importStatus;
		String counts = "";
		if(importHasCounts && importTotal > 0)
		{
			int left = Math.max(0, importTotal - importDone);
			counts = "Processed: " + importDone + " / " + importTotal
				+ "   Left: " + left;
		}
		
		context.fill(0, 0, width, height, 0x8A000000);
		
		int panelW = Math.min(430, width - 40);
		int panelH = importHasCounts ? 102 : 88;
		int x1 = (width - panelW) / 2;
		int y1 = (height - panelH) / 2;
		int x2 = x1 + panelW;
		int y2 = y1 + panelH;
		
		context.fill(x1, y1, x2, y2, 0xF0181A22);
		context.fill(x1, y1, x2, y1 + 1, 0xFF5FA3FF);
		context.fill(x1, y2 - 1, x2, y2, 0xFF5FA3FF);
		context.fill(x1, y1, x1 + 1, y2, 0xFF5FA3FF);
		context.fill(x2 - 1, y1, x2, y2, 0xFF5FA3FF);
		
		context.centeredText(font, headline, width / 2, y1 + 16,
			CommonColors.WHITE);
		context.centeredText(font, status, width / 2, y1 + 34, 0xFFFFAA);
		if(!counts.isBlank())
			context.centeredText(font, counts, width / 2, y1 + 50, 0xFFA8D0FF);
		context.centeredText(font,
			"Import/Export/Login controls are temporarily disabled.", width / 2,
			importHasCounts ? y1 + 68 : y1 + 54, CommonColors.LIGHT_GRAY);
	}
	
	private void renderAltTooltip(GuiGraphicsExtractor context, int mouseX,
		int mouseY)
	{
		if(!listGui.isMouseOver(mouseX, mouseY))
			return;
		
		Entry hoveredEntry = listGui.getHoveredEntry(mouseX, mouseY);
		if(hoveredEntry == null)
			return;
		
		int hoveredIndex = listGui.children().indexOf(hoveredEntry);
		int itemX = mouseX - listGui.getRowLeft();
		int itemY = mouseY - listGui.getRowTop(hoveredIndex);
		
		if(itemX < 31 || itemY < 15 || itemY >= 25)
			return;
		
		Alt alt = hoveredEntry.alt;
		ArrayList<Component> tooltip = new ArrayList<>();
		
		if(itemX >= 31 + font.width(hoveredEntry.getBottomText()))
			return;
		
		if(alt.isCracked())
			addTooltip(tooltip, "cracked");
		else
		{
			tooltip.add(Component.literal(alt.getCredentialType()));
			
			if(failedLogins.contains(alt))
			{
				addTooltip(tooltip, "failed");
				String reason = getFailedReason(alt);
				if(!reason.isBlank())
					tooltip.add(Component.literal("Reason: " + reason));
			}
			
			if(alt.isCheckedPremium())
				addTooltip(tooltip, "checked");
			else
				addTooltip(tooltip, "unchecked");
		}
		
		if(alt.isFavorite())
			addTooltip(tooltip, "favorite");
		
		if(alt.getLastValidatedAt() > 0)
			tooltip.add(Component.literal("Last validated: " + VALIDATED_FORMAT
				.format(Instant.ofEpochMilli(alt.getLastValidatedAt()))));
		else
			tooltip.add(Component.literal("Last validated: never"));
		
		context.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
	}
	
	private void addTooltip(ArrayList<Component> tooltip, String trKey)
	{
		// translate
		String translated = WurstClient.INSTANCE
			.translate("description.wurst.altmanager." + trKey);
		
		// line-wrap
		StringJoiner joiner = new StringJoiner("\n");
		font.getSplitter().splitLines(translated, 200, Style.EMPTY).stream()
			.map(FormattedText::getString).forEach(s -> joiner.add(s));
		String wrapped = joiner.toString();
		
		// add to tooltip
		for(String line : wrapped.split("\n"))
			tooltip.add(Component.literal(line));
	}
	
	@Override
	public void onClose()
	{
		autoCheckCancelled = true;
		minecraft.gui.setScreen(prevScreen);
	}
	
	@Override
	public void removed()
	{
		autoCheckCancelled = true;
		if(listGui != null)
			lastListState = listGui.captureState();
		super.removed();
	}
	
	private List<Alt> getDisplayedAlts(List<Alt> list)
	{
		ArrayList<Alt> displayed = new ArrayList<>(list);
		String loggedInName =
			minecraft != null ? minecraft.getUser().getName() : null;
		
		if(loggedInName == null || loggedInName.isBlank())
			return displayed;
		
		for(int i = 0; i < displayed.size(); i++)
		{
			Alt alt = displayed.get(i);
			if(!alt.getName().equalsIgnoreCase(loggedInName))
				continue;
			
			if(i > 0)
			{
				displayed.remove(i);
				displayed.add(0, alt);
			}
			
			break;
		}
		
		return displayed;
	}
	
	private final class Entry
		extends MultiSelectEntryListWidget.Entry<AltManagerScreen.Entry>
	{
		private final Alt alt;
		private long lastClickTime;
		private final String selectionKey;
		
		public Entry(ListGui parent, Alt alt)
		{
			super(parent);
			this.alt = Objects.requireNonNull(alt);
			selectionKey = Integer.toHexString(System.identityHashCode(alt));
		}
		
		@Override
		public String selectionKey()
		{
			return selectionKey;
		}
		
		@Override
		public Component getNarration()
		{
			return Component.translatable("narrator.select",
				"Alt " + alt + ", " + StringUtil.stripColor(getBottomText()));
		}
		
		@Override
		public boolean mouseClicked(MouseButtonEvent context,
			boolean doubleClick)
		{
			if(context.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT)
				return false;
			
			super.mouseClicked(context, doubleClick);
			
			long timeSinceLastClick = Util.getMillis() - lastClickTime;
			lastClickTime = Util.getMillis();
			
			if(timeSinceLastClick < 250)
				pressLogin();
			
			return true;
		}
		
		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX,
			int mouseY, boolean hovered, float tickDelta)
		{
			int x = getContentX();
			int y = getContentY();
			
			// green glow when logged in
			if(minecraft.getUser().getName().equals(alt.getName()))
			{
				float opacity =
					0.3F - Math.abs(Mth.sin(System.currentTimeMillis() % 10000L
						/ 10000F * (float)Math.PI * 2.0F) * 0.15F);
				
				int color = 0x00FF00 | (int)(opacity * 255) << 24;
				context.fill(x - 2, y - 2, x + 218, y + 28, color);
			}
			
			// face
			boolean selected = parent().getSelectedEntries().contains(this);
			AltRenderer.drawAltFace(context, alt.getName(), x + 1, y + 1, 24,
				24, selected);
			
			Font tr = minecraft.font;
			
			// name / email
			context.text(tr, "Name: " + alt.getDisplayName(), x + 31, y + 3,
				CommonColors.LIGHT_GRAY, false);
			
			// status
			context.text(tr, getBottomText(), x + 31, y + 15,
				CommonColors.LIGHT_GRAY, false);
		}
		
		private String getBottomText()
		{
			String text = alt.isCracked() ? "\u00a78cracked"
				: "\u00a72" + alt.getCredentialType();
			
			if(alt.isFavorite())
				text += "\u00a7r, \u00a7efavorite";
			
			if(failedLogins.contains(alt))
			{
				String reason = getFailedReason(alt);
				text += "\u00a7r, \u00a7clogin failed";
				if(!reason.isBlank())
					text += "\u00a7r \u00a76(" + reason + ")\u00a7c";
			}else if(isChecking(alt))
				text += "\u00a7r, \u00a7echecking...";
			else if(alt.isUncheckedPremium())
				text += "\u00a7r, \u00a7cunchecked";
			
			// AltBot connection status
			AltBotState botState =
				WurstClient.INSTANCE.getAltBotManager().getState(alt);
			if(botState.isActiveClient())
				text += "\u00a7r, \u00a7aActive Client";
			else
			{
				String botText = getBotStatusText(botState);
				if(!botText.isEmpty())
					text += "\u00a7r, " + botText;
			}
			
			return text;
		}
		
		private static String getBotStatusText(AltBotState state)
		{
			switch(state.getState())
			{
				case AUTHENTICATING:
				return "\u00a7eAuthenticating";
				
				case CONNECTING:
				return "\u00a7eConnecting";
				
				case LOGIN:
				return "\u00a7eLogin";
				
				case CONFIGURING:
				return "\u00a7eConfiguring";
				
				case PLAY:
				return "\u00a72Connected to " + state.getServer();
				
				case DISCONNECTING:
				return "\u00a7eDisconnecting";
				
				case FAILED:
				{
					String error = state.getLastError();
					return "\u00a7cFailed" + (error == null || error.isBlank()
						? "" : ": " + (error.length() > 40
							? error.substring(0, 40) + "..." : error));
				}
				
				case DISCONNECTED:
				default:
				return "";
			}
		}
	}
	
	private static final class ImportResult
	{
		private final ArrayList<Alt> toAdd;
		private final int addedCount;
		private final int duplicateCount;
		
		private ImportResult(ArrayList<Alt> toAdd, int duplicateCount)
		{
			this.toAdd = toAdd;
			addedCount = toAdd.size();
			this.duplicateCount = duplicateCount;
		}
	}
	
	private final class ListGui
		extends MultiSelectEntryListWidget<AltManagerScreen.Entry>
	{
		public ListGui(Minecraft minecraft, AltManagerScreen screen,
			List<Alt> list)
		{
			super(minecraft, screen.width, screen.height - 140, 36, 30);
			
			screen.getDisplayedAlts(list).stream()
				.map(alt -> new AltManagerScreen.Entry(this, alt))
				.forEach(this::addEntry);
			
			setSelectionListener(screen::updateAltButtons);
		}
		
		@Override
		protected String getSelectionKey(AltManagerScreen.Entry entry)
		{
			return entry.selectionKey();
		}
		
		@Override
		public void setSelected(@Nullable AltManagerScreen.Entry entry)
		{
			super.setSelected(entry);
			updateAltButtons();
		}
		
		// This method sets selected to null without calling setSelected().
		@Override
		protected void clearEntries()
		{
			super.clearEntries();
			updateAltButtons();
		}
		
		/**
		 * @return The selected Alt, or null if no Alt is selected.
		 */
		public Alt getSelectedAlt()
		{
			return getSelectedAlts().stream().findFirst().orElse(null);
		}
		
		public List<Alt> getSelectedAlts()
		{
			return getSelectedEntries().stream().map(entry -> entry.alt)
				.toList();
		}
		
		public int getSelectionCount()
		{
			return getSelectedEntries().size();
		}
		
		/**
		 * @return The hovered Entry, or null if no Entry is hovered.
		 */
		public AltManagerScreen.Entry getHoveredEntry(double mouseX,
			double mouseY)
		{
			Optional<GuiEventListener> hovered = getChildAt(mouseX, mouseY);
			return hovered.map(e -> ((AltManagerScreen.Entry)e)).orElse(null);
		}
	}
}
