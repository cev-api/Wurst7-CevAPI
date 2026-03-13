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

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
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
import net.wurstclient.altmanager.*;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.mixinterface.IMinecraftClient;
import net.wurstclient.util.MultiProcessingUtils;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonObject;

public final class AltManagerScreen extends Screen
{
	private static final HashSet<Alt> failedLogins = new HashSet<>();
	private static final LinkedHashMap<Alt, String> failedLoginReasons =
		new LinkedHashMap<>();
	
	private final Screen prevScreen;
	private final AltManager altManager;
	
	private ListGui listGui;
	private boolean shouldAsk = true;
	private int errorTimer;
	
	private Button useButton;
	private Button starButton;
	private Button editButton;
	private Button deleteButton;
	
	private Button importButton;
	private Button exportButton;
	private Button checkButton;
	private Button logoutButton;
	
	private List<Alt> pendingDeletion = Collections.emptyList();
	private Alt pendingLogin;
	private volatile boolean autoCheckCancelled;
	private volatile boolean autoCheckInProgress;
	private final HashSet<Alt> checkingAlts = new HashSet<>();
	private volatile boolean importInProgress;
	private volatile String importStatus = "";
	private volatile int importDone;
	private volatile int importTotal;
	private volatile boolean importHasCounts;
	
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
			minecraft.setScreen(screen);
			
		}else if(altManager.getList().isEmpty() && shouldAsk)
		{
			Component title = Component
				.literal(wurst.translate("gui.wurst.altmanager.empty.title"));
			Component message = Component
				.literal(wurst.translate("gui.wurst.altmanager.empty.message"));
			BooleanConsumer callback = this::confirmGenerate;
			
			ConfirmScreen screen = new ConfirmScreen(callback, title, message);
			minecraft.setScreen(screen);
		}
		
		addRenderableWidget(useButton =
			Button.builder(Component.literal("Login"), b -> pressLogin())
				.bounds(width / 2 - 154, height - 52, 100, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Direct Login"),
				b -> minecraft.setScreen(new DirectLoginScreen(this)))
			.bounds(width / 2 - 50, height - 52, 100, 20).build());
		
		addRenderableWidget(
			Button
				.builder(Component.literal("Add"),
					b -> minecraft
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
				b -> minecraft.setScreen(prevScreen))
			.bounds(width / 2 + 80, height - 28, 75, 20).build());
		
		addRenderableWidget(importButton =
			Button.builder(Component.literal("Import"), b -> pressImportAlts())
				.bounds(8, 8, 50, 20).build());
		
		addRenderableWidget(exportButton =
			Button.builder(Component.literal("Export"), b -> pressExportAlts())
				.bounds(58, 8, 50, 20).build());
		
		addRenderableWidget(checkButton =
			Button.builder(Component.literal("Check"), b -> pressCheckAlts())
				.bounds(width - 50 - 8 - 52, 8, 50, 20).build());
		
		addRenderableWidget(logoutButton =
			Button.builder(Component.literal("Logout"), b -> pressLogout())
				.bounds(width - 50 - 8, 8, 50, 20).build());
		
		listGui.ensureSelection();
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
		
		if(importInProgress)
		{
			useButton.active = false;
			starButton.active = false;
			editButton.active = false;
			deleteButton.active = false;
			logoutButton.active = false;
			checkButton.active = false;
			if(importButton != null)
				importButton.active = false;
			if(exportButton != null)
				exportButton.active = false;
			return;
		}
		
		int selectionCount = listGui != null ? listGui.getSelectionCount() : 0;
		boolean hasSingleSelection = selectionCount == 1;
		Alt selectedAlt = listGui != null ? listGui.getSelectedAlt() : null;
		
		useButton.active = hasSingleSelection;
		starButton.active = hasSingleSelection;
		editButton.active =
			hasSingleSelection && !(selectedAlt instanceof TokenAlt);
		deleteButton.active = selectionCount > 0;
		
		logoutButton.active =
			((IMinecraftClient)minecraft).getWurstSession() != null;
		
		checkButton.active = !autoCheckInProgress && hasUncheckedPremiumAlts();
		
		if(importButton != null)
			importButton.active =
				!importInProgress && !minecraft.options.fullscreen().get();
		
		if(exportButton != null)
			exportButton.active =
				!importInProgress && !minecraft.options.fullscreen().get();
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
		minecraft.setScreen(screen);
	}
	
	private void confirmLogin(boolean confirmed)
	{
		Alt alt = pendingLogin;
		pendingLogin = null;
		
		if(!confirmed || alt == null)
		{
			minecraft.setScreen(this);
			return;
		}
		
		try
		{
			altManager.login(alt);
			clearLoginFailure(alt);
			minecraft.setScreen(new AltLoginSuccessScreen(prevScreen,
				minecraft.getUser().getName()));
			
		}catch(LoginException e)
		{
			errorTimer = 8;
			recordLoginFailure(alt, e);
			minecraft.setScreen(new AltLoginFailedScreen(this, e.getMessage()));
		}
	}
	
	private void pressLogout()
	{
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
		minecraft
			.setScreen(new AltLogoutResultScreen(this, restored, currentName));
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
		
		autoCheckInProgress = true;
		updateAltButtons();
		
		List<Alt> firstPhase = List.copyOf(prioritized);
		List<Alt> secondPhase = List.copyOf(remaining);
		Thread thread =
			new Thread(() -> runAutoCheckAndDedupe(firstPhase, secondPhase),
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
			if(minecraft.screen != this)
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
				minecraft.setScreen(this);
				latch.countDown();
			}, title, message, Component.literal("Continue"),
				Component.literal("Stop"));
			minecraft.setScreen(screen);
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
		
		minecraft.setScreen(new EditAltScreen(this, altManager, alt));
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
		minecraft.setScreen(screen);
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
					if(minecraft.screen == this)
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
				
				if(!token.isEmpty() || !refreshToken.isEmpty())
					alts.add(new TokenAlt(token, refreshToken, name, false));
				
				continue;
			}
			
			switch(data.length)
			{
				case 1:
				alts.add(new CrackedAlt(data[0]));
				break;
				
				case 2:
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
		
		return line.startsWith("e") && line.length() > 80;
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
				
				try
				{
					if(isRefreshToken)
						MicrosoftLoginManager.loginWithRefreshToken(tokenLine);
					else
						MicrosoftLoginManager.loginWithToken(tokenLine);
					
					String name = minecraft.getUser().getName();
					if(name == null || name.isEmpty())
					{
						unresolved.add(isRefreshToken
							? new TokenAlt("", tokenLine, "", false)
							: new TokenAlt(tokenLine, "", "", false));
						continue;
					}
					
					String key = name.toLowerCase(Locale.ROOT);
					TokenAlt importedAlt = isRefreshToken
						? new TokenAlt("", tokenLine, name, false)
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
		List<Alt> remaining)
	{
		IMinecraftClient imc = (IMinecraftClient)minecraft;
		User previousSession = imc.getWurstSession();
		boolean changed = false;
		
		try
		{
			for(Alt alt : prioritized)
			{
				if(!isOpenScreen())
					return;
				
				setChecking(alt, true);
				try
				{
					altManager.login(alt);
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
							altManager.login(alt);
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
				if(minecraft.screen == this)
					updateAltButtons();
			});
		}
		
		if(changed)
			minecraft.execute(() -> {
				if(minecraft.screen == this)
					reloadScreen();
			});
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
		minecraft.setScreen(new AltManagerScreen(prevScreen, altManager));
	}
	
	private boolean isOpenScreen()
	{
		return !autoCheckCancelled && minecraft != null
			&& minecraft.screen == this;
	}
	
	private boolean hasUncheckedPremiumAlts()
	{
		return altManager.getList().stream()
			.anyMatch(alt -> !alt.isCracked() && alt.isUncheckedPremium());
	}
	
	private void pressExportAlts()
	{
		try
		{
			Process process = MultiProcessingUtils.startProcessWithIO(
				ExportAltsFileChooser.class,
				WurstClient.INSTANCE.getWurstFolder().toString());
			
			Path path = getFileChooserPath(process);
			
			process.waitFor();
			
			if(path.getFileName().toString().endsWith(".json"))
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
		minecraft.setScreen(this);
	}
	
	private void confirmRemove(boolean confirmed)
	{
		if(confirmed)
			pendingDeletion.forEach(alt -> {
				clearLoginFailure(alt);
				altManager.remove(alt);
			});
		
		pendingDeletion = Collections.emptyList();
		minecraft.setScreen(this);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		listGui.render(context, mouseX, mouseY, partialTicks);
		
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
		context.drawCenteredString(font, "Alt Manager", width / 2, 4,
			CommonColors.WHITE);
		context.drawCenteredString(font, "Alts: " + altManager.getList().size(),
			width / 2, 14, CommonColors.LIGHT_GRAY);
		context.drawCenteredString(font,
			"premium: " + altManager.getNumPremium() + ", cracked: "
				+ altManager.getNumCracked(),
			width / 2, 24, CommonColors.LIGHT_GRAY);
		
		if(!importStatus.isEmpty())
			context.drawCenteredString(font, importStatus, width / 2, 42,
				importInProgress ? 0xFFFF55 : CommonColors.LIGHT_GRAY);
		
		if(((IMinecraftClient)minecraft).getWurstSession() != null)
			context.drawCenteredString(font,
				"Logged in as " + minecraft.getUser().getName(), width / 2, 50,
				0x55FF55);
		
		// red flash for errors
		if(errorTimer > 0)
		{
			int alpha = (int)(Math.min(1, errorTimer / 16F) * 255);
			int color = 0xFF0000 | alpha << 24;
			context.fill(0, 0, width, height, color);
			errorTimer--;
		}
		
		for(Renderable drawable : renderables)
			drawable.render(context, mouseX, mouseY, partialTicks);
		
		renderImportOverlay(context);
		renderButtonTooltip(context, mouseX, mouseY);
		renderAltTooltip(context, mouseX, mouseY);
	}
	
	private void renderImportOverlay(GuiGraphics context)
	{
		if(!importInProgress)
			return;
		
		int now = (int)(Util.getMillis() / 450L);
		String dots = ".".repeat(Math.max(1, (now % 3) + 1));
		String headline = "Loading accounts" + dots;
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
		
		context.drawCenteredString(font, headline, width / 2, y1 + 16,
			CommonColors.WHITE);
		context.drawCenteredString(font, status, width / 2, y1 + 34, 0xFFFFAA);
		if(!counts.isBlank())
			context.drawCenteredString(font, counts, width / 2, y1 + 50,
				0xFFA8D0FF);
		context.drawCenteredString(font,
			"Import/Export/Login controls are temporarily disabled.", width / 2,
			importHasCounts ? y1 + 68 : y1 + 54, CommonColors.LIGHT_GRAY);
	}
	
	private void renderAltTooltip(GuiGraphics context, int mouseX, int mouseY)
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
			addTooltip(tooltip, "premium");
			
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
		
		context.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
	}
	
	private void renderButtonTooltip(GuiGraphics context, int mouseX,
		int mouseY)
	{
		for(AbstractWidget button : Screens.getButtons(this))
		{
			if(!button.isHoveredOrFocused())
				continue;
			
			if(button != importButton && button != exportButton)
				continue;
			
			ArrayList<Component> tooltip = new ArrayList<>();
			addTooltip(tooltip, "window");
			
			if(minecraft.options.fullscreen().get())
				addTooltip(tooltip, "fullscreen");
			else
				addTooltip(tooltip, "window_freeze");
			
			context.setComponentTooltipForNextFrame(font, tooltip, mouseX,
				mouseY);
			break;
		}
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
		minecraft.setScreen(prevScreen);
	}
	
	@Override
	public void removed()
	{
		autoCheckCancelled = true;
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
		public void renderContent(GuiGraphics context, int mouseX, int mouseY,
			boolean hovered, float tickDelta)
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
			context.drawString(tr, "Name: " + alt.getDisplayName(), x + 31,
				y + 3, CommonColors.LIGHT_GRAY, false);
			
			// status
			context.drawString(tr, getBottomText(), x + 31, y + 15,
				CommonColors.LIGHT_GRAY, false);
		}
		
		private String getBottomText()
		{
			String text = alt.isCracked() ? "\u00a78cracked" : "\u00a72premium";
			
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
			
			return text;
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
			super(minecraft, screen.width, screen.height - 96, 36, 30);
			
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
