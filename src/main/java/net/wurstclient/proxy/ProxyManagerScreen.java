/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.proxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.mixinterface.IMultiplayerTitleRefresher;
import net.wurstclient.mixin.ScreenAccessor;
import net.wurstclient.util.MultiProcessingUtils;

public final class ProxyManagerScreen extends Screen
{
	private static final int PROXY_LIST_WIDTH = 700;
	private static final int ACTIVE_PROXY_BORDER = 0xFF0A2A10;
	private static final int ACTIVE_PROXY_BACKGROUND = 0xFF1A4A1A;
	private static final int ACTIVE_PROXY_GREEN = 0xFF55FF55;
	
	private final Screen prevScreen;
	private final ProxyManager proxyManager;
	
	private EditBox proxyBox;
	private ProxyList proxyList;
	private Button addButton;
	private Button importButton;
	private Button testButton;
	private Button selectButton;
	private Button disableButton;
	private Button removeButton;
	private volatile boolean testing;
	private volatile String status = "";
	private volatile boolean statusError;
	
	public ProxyManagerScreen(Screen prevScreen, ProxyManager proxyManager)
	{
		super(Component.literal("Multiplayer Proxies"));
		this.prevScreen = prevScreen;
		this.proxyManager = proxyManager;
	}
	
	@Override
	protected void init()
	{
		int x = width / 2 - PROXY_LIST_WIDTH / 2;
		proxyBox = new EditBox(font, x, 46, PROXY_LIST_WIDTH, 20,
			Component.literal("host:port or socks5://host:port"));
		proxyBox.setMaxLength(512);
		addWidget(proxyBox);
		
		addRenderableWidget(addButton =
			Button.builder(Component.literal("Add"), b -> addProxy())
				.bounds(x, 70, 72, 20).build());
		addRenderableWidget(importButton =
			Button.builder(Component.literal("Import"), b -> importProxies())
				.bounds(x + 76, 70, 72, 20).build());
		addRenderableWidget(testButton =
			Button.builder(Component.literal("Test"), b -> testSelectedProxy())
				.bounds(x + 152, 70, 72, 20).build());
		addRenderableWidget(selectButton =
			Button.builder(Component.literal("Use"), b -> selectProxy())
				.bounds(x + 228, 70, 72, 20).build());
		
		addRenderableWidget(disableButton =
			Button.builder(Component.literal("Disable"), b -> disableProxy())
				.bounds(width / 2 - 152, 94, 98, 20).build());
		addRenderableWidget(removeButton = Button
			.builder(Component.literal("Remove"), b -> removeSelectedProxy())
			.bounds(width / 2 - 50, 94, 98, 20).build());
		addRenderableWidget(
			Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(width / 2 + 52, 94, 100, 20).build());
		
		proxyList = new ProxyList();
		proxyList.setSelectionListener(this::updateButtons);
		addWidget(proxyList);
		refreshList();
		setFocused(proxyBox);
	}
	
	@Override
	public void tick()
	{
		updateButtons();
	}
	
	private void updateButtons()
	{
		if(proxyBox == null)
			return;
		
		SocksProxy proxy = getSelectedListProxy();
		int selectedCount =
			proxyList == null ? 0 : proxyList.getSelectedEntries().size();
		addButton.active = !proxyBox.getValue().trim().isEmpty() && !testing;
		importButton.active = !testing;
		testButton.active = proxy != null && !testing;
		selectButton.active = proxy != null && !testing
			&& !proxy.equals(proxyManager.getSelectedProxy());
		disableButton.active =
			proxyManager.getSelectedProxy() != null && !testing;
		removeButton.active = selectedCount > 0 && !testing;
		removeButton.setMessage(Component
			.literal(selectedCount > 1 ? "Remove selected" : "Remove"));
		selectButton.setMessage(Component.literal("Use"));
	}
	
	private void addProxy()
	{
		try
		{
			SocksProxy proxy = SocksProxy.parse(proxyBox.getValue());
			proxy.validateCredentialsForSocks5();
			if(proxyManager.add(proxy))
			{
				proxyBox.setValue("");
				status = "Added " + proxy.getDisplayName();
				refreshList(proxy);
			}else
				status = "That proxy is already in the list.";
			
		}catch(IllegalArgumentException e)
		{
			status = e.getMessage();
		}
	}
	
	private void importProxies()
	{
		try
		{
			Process process = MultiProcessingUtils.startProcessWithIO(
				ProxyImportFileChooser.class,
				WurstClient.INSTANCE.getWurstFolder().toString());
			Path path = getFileChooserPath(process);
			process.waitFor();
			if(path == null)
				return;
			
			ProxyManager.ImportResult result = proxyManager
				.importLines(Files.readAllLines(path, StandardCharsets.UTF_8));
			status = "Imported " + result.added()
				+ (result.added() == 1 ? " proxy, " : " proxies, ")
				+ result.duplicates() + " duplicate"
				+ (result.duplicates() == 1 ? "" : "s") + ", "
				+ result.invalid() + " invalid.";
			refreshList();
			
		}catch(IOException e)
		{
			status = "Could not read proxy file.";
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			status = "Proxy import interrupted.";
		}
	}
	
	private Path getFileChooserPath(Process process) throws IOException
	{
		try(BufferedReader reader =
			new BufferedReader(new InputStreamReader(process.getInputStream(),
				StandardCharsets.UTF_8)))
		{
			String response = reader.readLine();
			if(response == null || response.isBlank())
				return null;
			
			try
			{
				return Paths.get(response);
			}catch(InvalidPathException e)
			{
				throw new IOException("Proxy file path is invalid.", e);
			}
		}
	}
	
	private void selectProxy()
	{
		SocksProxy proxy = getSelectedListProxy();
		if(proxy == null)
			return;
		
		proxyManager.select(proxy);
		status = "Using " + proxy.getDisplayName() + " for multiplayer.";
		refreshList(proxy);
	}
	
	private void disableProxy()
	{
		proxyManager.clearSelection();
		status = "Multiplayer proxy disabled.";
		refreshList();
	}
	
	private void removeSelectedProxy()
	{
		if(proxyList == null)
			return;
		
		List<SocksProxy> selected = proxyList.getSelectedEntries().stream()
			.map(ProxyEntry::getProxy).toList();
		if(selected.isEmpty())
			return;
		
		for(SocksProxy proxy : selected)
			proxyManager.remove(proxy);
		status = selected.size() == 1
			? "Removed " + selected.get(0).getDisplayName() + "."
			: "Removed " + selected.size() + " proxies.";
		refreshList();
	}
	
	private void testSelectedProxy()
	{
		SocksProxy proxy = getSelectedListProxy();
		if(proxy == null || testing)
			return;
		
		testing = true;
		statusError = false;
		status = "Testing " + proxy.getDisplayName() + "...";
		Thread thread = new Thread(() -> {
			try
			{
				String result = proxyManager.test(proxy);
				minecraft.execute(() -> finishTest(result, false));
			}catch(IOException | IllegalArgumentException e)
			{
				minecraft.execute(
					() -> finishTest("INVALID for Minecraft multiplayer: "
						+ cleanMessage(e.getMessage()), true));
			}
		}, "Wurst Proxy Test");
		thread.setDaemon(true);
		thread.start();
	}
	
	private void finishTest(String result, boolean error)
	{
		testing = false;
		statusError = error;
		status = result;
	}
	
	private String cleanMessage(String message)
	{
		return message == null || message.isBlank() ? "Unknown error."
			: message.replace('\n', ' ').replace('\r', ' ').trim();
	}
	
	private SocksProxy getSelectedListProxy()
	{
		if(proxyList == null)
			return null;
		
		return proxyList.getSelectedEntries().stream().findFirst()
			.map(ProxyEntry::getProxy).orElse(null);
	}
	
	private void refreshList()
	{
		refreshList(getSelectedListProxy());
	}
	
	private void refreshList(SocksProxy preferredSelection)
	{
		proxyList.reload(proxyManager.getProxies(), preferredSelection);
		updateButtons();
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ENTER && proxyBox.isFocused())
		{
			addProxy();
			return true;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		proxyBox.mouseClicked(context, doubleClick);
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_4)
		{
			onClose();
			return true;
		}
		
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		context.centeredText(font, "Multiplayer Proxies", width / 2, 12,
			CommonColors.WHITE);
		context.centeredText(font,
			"Selected: "
				+ (proxyManager.getSelectedProxy() == null ? "direct connection"
					: proxyManager.getSelectedProxy().getDisplayName()),
			width / 2, 24, CommonColors.LIGHT_GRAY);
		context.text(font, "Proxy (http:// or socks5://, optional user:pass)",
			width / 2 - 150, 34, CommonColors.LIGHT_GRAY);
		
		proxyList.extractRenderState(context, mouseX, mouseY, partialTicks);
		proxyBox.extractRenderState(context, mouseX, mouseY, partialTicks);
		context.centeredText(font, status, width / 2, height - 42,
			statusError ? 0xFFFF5555 : CommonColors.LIGHT_GRAY);
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public void onClose()
	{
		if(prevScreen instanceof IMultiplayerTitleRefresher refresher)
		{
			ScreenAccessor accessor = (ScreenAccessor)prevScreen;
			Component oldTitle = accessor.getWurstTitle();
			refresher.wurst$refreshAccountTitle();
			Component newTitle = accessor.getWurstTitle();
			for(GuiEventListener child : prevScreen.children())
				if(child instanceof StringWidget titleWidget
					&& titleWidget.getMessage().equals(oldTitle))
				{
					titleWidget.setMessage(newTitle);
					break;
				}
		}
		
		minecraft.gui.setScreen(prevScreen);
	}
	
	private final class ProxyList extends MultiSelectEntryListWidget<ProxyEntry>
	{
		private ProxyList()
		{
			super(ProxyManagerScreen.this.minecraft,
				ProxyManagerScreen.this.width,
				ProxyManagerScreen.this.height - 178, 122, 24);
		}
		
		@Override
		public int getRowWidth()
		{
			return PROXY_LIST_WIDTH;
		}
		
		private void reload(List<SocksProxy> proxies, SocksProxy preferred)
		{
			List<ProxyEntry> entries = proxies.stream()
				.map(proxy -> new ProxyEntry(this, proxy)).toList();
			replaceEntries(entries);
			SocksProxy selection =
				preferred == null ? proxyManager.getSelectedProxy() : preferred;
			if(selection != null)
				setSelection(List.of(selection.getStorageId()), 0);
			else
				ensureSelection();
		}
		
		@Override
		protected String getSelectionKey(ProxyEntry entry)
		{
			return entry.selectionKey();
		}
		
		@Override
		protected void extractItem(GuiGraphicsExtractor context, int mouseX,
			int mouseY, float delta, ProxyEntry entry)
		{
			// The active proxy draws its own single, opaque highlight. This
			// avoids combining the vanilla selection outline with a second
			// background rectangle of slightly different bounds.
			if(entry.isActiveProxy())
			{
				entry.extractContent(context, mouseX, mouseY,
					entry == getHovered(), delta);
				return;
			}
			
			super.extractItem(context, mouseX, mouseY, delta, entry);
		}
	}
	
	private final class ProxyEntry
		extends MultiSelectEntryListWidget.Entry<ProxyEntry>
	{
		private final SocksProxy proxy;
		
		private ProxyEntry(ProxyList parent, SocksProxy proxy)
		{
			super(parent);
			this.proxy = proxy;
		}
		
		@Override
		public String selectionKey()
		{
			return proxy.getStorageId();
		}
		
		@Override
		public Component getNarration()
		{
			return Component.literal(proxy.getDisplayName());
		}
		
		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX,
			int mouseY, boolean hovered, float tickDelta)
		{
			boolean active = isActiveProxy();
			if(active)
			{
				context.fill(getX(), getY(), getX() + getWidth(),
					getY() + getHeight(), ACTIVE_PROXY_BORDER);
				context.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1,
					getY() + getHeight() - 1, ACTIVE_PROXY_BACKGROUND);
			}
			
			int textY =
				getContentY() + (getContentHeight() - font.lineHeight) / 2;
			context.text(font,
				active
					? "\u2713 " + proxy.getDisplayName() + " [IN USE: "
						+ proxy.getProtocol().getDisplayName() + "]"
					: proxy.getDisplayName(),
				getContentX(), textY,
				active ? ACTIVE_PROXY_GREEN : CommonColors.LIGHT_GRAY, false);
		}
		
		private SocksProxy getProxy()
		{
			return proxy;
		}
		
		private boolean isActiveProxy()
		{
			return proxy.equals(proxyManager.getSelectedProxy());
		}
	}
}
