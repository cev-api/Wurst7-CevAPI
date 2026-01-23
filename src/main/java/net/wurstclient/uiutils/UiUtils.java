/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.util.Mth;

public final class UiUtils
{
	public static final String VERSION = "2.4.0";
	public static KeyMapping restoreScreenKey;
	public static final Logger LOGGER = LoggerFactory.getLogger("ui-utils");
	
	private static boolean initialized;
	
	private UiUtils()
	{
		
	}
	
	public static void init()
	{
		if(initialized)
			return;
		
		restoreScreenKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.wurst.uiutils.restore_screen", InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_V, KeyMapping.Category.MISC));
		WurstClient.INSTANCE.getEventManager().add(UpdateListener.class,
			new RestoreScreenHandler());
		
		initialized = true;
	}
	
	public static void chatIfEnabled(String msg)
	{
		try
		{
			if(!UiUtilsState.isUiEnabled())
				return;
			net.wurstclient.hacks.UiUtilsHack hack =
				WurstClient.INSTANCE.getHax().uiUtilsHack;
			Minecraft mc = Minecraft.getInstance();
			if(hack != null && hack.isLogToChat() && mc.player != null)
				mc.player.displayClientMessage(
					Component.literal("[UI-Utils] " + msg), false);
		}catch(Throwable t)
		{
			// ignore if GUI/hack not available
		}
	}
	
	public static void renderSyncInfo(Minecraft mc, GuiGraphics graphics,
		AbstractContainerMenu menu)
	{
		if(menu == null)
			return;
		
		graphics.drawString(mc.font, "Sync Id: " + menu.containerId, 200, 5,
			0xFFFFFF, false);
		graphics.drawString(mc.font, "Revision: " + menu.getStateId(), 200, 35,
			0xFFFFFF, false);
	}
	
	public static int addUiWidgets(Minecraft mc, int baseX, int baseY,
		int spacing, Consumer<AbstractWidget> adder)
	{
		adder.accept(
			Button.builder(Component.literal("Close without packet"), b -> {
				mc.setScreen(null);
				chatIfEnabled("Closed GUI without packet");
			}).bounds(baseX, baseY, 115, 20).build());
		int y = baseY + 20 + spacing;
		
		adder.accept(Button.builder(Component.literal("De-sync"), b -> {
			if(mc.getConnection() != null && mc.player != null)
				mc.getConnection().send(new ServerboundContainerClosePacket(
					mc.player.containerMenu.containerId));
			else
				LOGGER.warn(
					"Minecraft connection or player was null while using 'De-sync'.");
			chatIfEnabled("De-synced (sent close packet)");
		}).bounds(baseX, y, 115, 20).build());
		y += 20 + spacing;
		
		adder.accept(Button.builder(
			Component.literal("Send packets: " + UiUtilsState.sendUiPackets),
			b -> {
				UiUtilsState.sendUiPackets = !UiUtilsState.sendUiPackets;
				b.setMessage(Component
					.literal("Send packets: " + UiUtilsState.sendUiPackets));
				chatIfEnabled("Send packets: " + UiUtilsState.sendUiPackets);
			}).bounds(baseX, y, 115, 20).build());
		y += 20 + spacing;
		
		adder.accept(Button.builder(
			Component.literal("Delay packets: " + UiUtilsState.delayUiPackets),
			b -> {
				UiUtilsState.delayUiPackets = !UiUtilsState.delayUiPackets;
				b.setMessage(Component
					.literal("Delay packets: " + UiUtilsState.delayUiPackets));
				if(!UiUtilsState.delayUiPackets
					&& !UiUtilsState.delayedUiPackets.isEmpty()
					&& mc.getConnection() != null)
				{
					for(Packet<?> packet : UiUtilsState.delayedUiPackets)
						mc.getConnection().send(packet);
					if(mc.player != null)
						mc.player.displayClientMessage(Component.literal(
							"Sent " + UiUtilsState.delayedUiPackets.size()
								+ " packets."),
							false);
					UiUtilsState.delayedUiPackets.clear();
				}
				chatIfEnabled("Delay packets: " + UiUtilsState.delayUiPackets);
			}).bounds(baseX, y, 115, 20).build());
		y += 20 + spacing;
		
		adder.accept(Button.builder(Component.literal("Save GUI"), b -> {
			if(mc.player != null)
			{
				UiUtilsState.storedScreen = mc.screen;
				UiUtilsState.storedMenu = mc.player.containerMenu;
				String title = mc.screen != null
					? mc.screen.getTitle().getString() : "<none>";
				AbstractContainerMenu m = mc.player.containerMenu;
				int sid = m != null ? m.containerId : -1;
				int rev = m != null ? m.getStateId() : -1;
				String screenName = mc.screen != null
					? mc.screen.getClass().getSimpleName() : "<none>";
				chatIfEnabled("Saved GUI: title=\"" + title + "\", syncId="
					+ sid + ", revision=" + rev + ", screen=" + screenName);
			}
		}).bounds(baseX, y, 115, 20).build());
		y += 20 + spacing;
		
		adder.accept(Button
			.builder(Component.literal("Disconnect and send packets"), b -> {
				UiUtilsState.delayUiPackets = false;
				if(mc.getConnection() != null)
				{
					for(Packet<?> packet : UiUtilsState.delayedUiPackets)
						mc.getConnection().send(packet);
					mc.getConnection().getConnection().disconnect(
						Component.literal("Disconnecting (UI-UTILS)"));
				}else
					LOGGER.warn(
						"Minecraft connection was null while disconnecting.");
				UiUtilsState.delayedUiPackets.clear();
				chatIfEnabled("Disconnected and sent queued packets");
			}).bounds(baseX, y, 160, 20).build());
		y += 20 + spacing;
		
		adder
			.accept(Button.builder(Component.literal("Fabricate packet"), b -> {
				AbstractContainerMenu menu =
					mc.player != null ? mc.player.containerMenu : null;
				int syncId = menu != null ? menu.containerId : 0;
				int revision = menu != null ? menu.getStateId() : 0;
				if(mc.screen instanceof AbstractContainerScreen)
				{
					UiUtilsState.fabricateOverlayOpen =
						!UiUtilsState.fabricateOverlayOpen;
					chatIfEnabled("Fabricate overlay: "
						+ (UiUtilsState.fabricateOverlayOpen ? "opened"
							: "closed"));
				}else
				{
					UiUtilsState.skipNextContainerRemoval = true;
					mc.setScreen(
						new FabricatePacketScreen(mc.screen, syncId, revision));
				}
			}).bounds(baseX, y, 115, 20).build());
		y += 20 + spacing;
		
		adder.accept(
			Button.builder(Component.literal("Copy GUI Title JSON"), b -> {
				try
				{
					if(mc.screen == null)
						throw new IllegalStateException(
							"Minecraft screen was null.");
					String json = new Gson().toJson(ComponentSerialization.CODEC
						.encodeStart(JsonOps.INSTANCE, mc.screen.getTitle())
						.getOrThrow());
					mc.keyboardHandler.setClipboard(json);
					chatIfEnabled("Copied GUI title JSON to clipboard");
				}catch(IllegalStateException e)
				{
					LOGGER.error("Error while copying title JSON to clipboard",
						e);
					chatIfEnabled("Failed to copy GUI title JSON");
				}
			}).bounds(baseX, y, 115, 20).build());
		return y + 20;
	}
	
	public static EditBox createChatField(Minecraft mc, Font font, int x, int y)
	{
		EditBox field =
			new EditBox(font, x, y, 160, 20, Component.literal("Chat ..."))
			{
				@Override
				public boolean keyPressed(KeyEvent keyEvent)
				{
					if(keyEvent.key() == GLFW.GLFW_KEY_ENTER)
					{
						String text = getValue();
						if("^toggleuiutils".equals(text))
						{
							UiUtilsState.enabled = !UiUtilsState.enabled;
							if(mc.player != null)
								mc.player
									.displayClientMessage(
										Component
											.literal("UI-Utils is now "
												+ (UiUtilsState.enabled
													? "enabled" : "disabled")
												+ "."),
										false);
							return false;
						}
						
						if(mc.getConnection() != null && mc.player != null)
						{
							if(text.startsWith("/"))
								mc.player.connection.sendCommand(
									text.replaceFirst(Pattern.quote("/"), ""));
							else
								mc.player.connection.sendChat(text);
						}else
							LOGGER.warn(
								"Minecraft player/connection was null while sending chat.");
						
						setValue("");
					}
					return super.keyPressed(keyEvent);
				}
			};
		field.setMaxLength(256);
		field.setHint(Component.literal("Chat ..."));
		return field;
	}
	
	@NotNull
	public static Runnable getFabricatePacketRunnable(Minecraft mc,
		boolean delay, Packet<?> packet)
	{
		Runnable toRun;
		if(delay)
		{
			toRun = () -> {
				if(mc.getConnection() == null)
				{
					LOGGER.warn(
						"Minecraft connection was null while sending packets.");
					return;
				}
				mc.getConnection().send(packet);
			};
		}else
		{
			toRun = () -> {
				if(mc.getConnection() == null)
				{
					LOGGER.warn(
						"Minecraft connection was null while sending packets.");
					return;
				}
				// Connection.send already writes to the Netty channel; avoid
				// double-send
				mc.getConnection().send(packet);
			};
		}
		return toRun;
	}
	
	public static boolean isInteger(String string)
	{
		try
		{
			Integer.parseInt(string);
			return true;
		}catch(Exception e)
		{
			return false;
		}
	}
	
	public static void queueTask(Runnable runnable, long delayMs)
	{
		Timer timer = new Timer();
		TimerTask task = new TimerTask()
		{
			@Override
			public void run()
			{
				Minecraft.getInstance().execute(runnable);
			}
		};
		timer.schedule(task, delayMs);
	}
	
	private static final class FabricatePacketScreen extends Screen
	{
		private static final int FIELD_WIDTH = 110;
		private static final int ROW_SPACING = 36;
		private static final int LABEL_OFFSET = 10;
		private static final int CONTENT_TOP_OFFSET = 12;
		private static final int PANEL_WIDTH = 260;
		private static final int PANEL_HEIGHT = 320;
		private static final int PANEL_PADDING = 16;
		private static final int PANEL_BORDER = 4;
		private static final int MODE_BUTTON_WIDTH = 110;
		private static final int MODE_BUTTON_GAP = 10;
		private static final int DELAY_BUTTON_WIDTH = 90;
		private static final int TIMES_FIELD_WIDTH = 50;
		private static final int STATUS_DURATION_MS = 1500;
		private static final int STATUS_SUCCESS = 0xFF55FF55;
		private static final int STATUS_ERROR = 0xFFFF5555;
		
		private final Screen parent;
		private PacketMode selectedMode = PacketMode.CLICK_SLOT;
		private final int initialSyncId;
		private final int initialRevision;
		private int panelX;
		private int panelY;
		
		private Button clickSlotModeButton;
		private Button buttonClickModeButton;
		
		private EditBox clickSyncIdField;
		private EditBox clickRevisionField;
		private EditBox clickSlotField;
		private EditBox clickButtonField;
		private CycleButton<ClickType> clickActionButton;
		private CycleButton<Boolean> clickDelayToggle;
		private EditBox clickTimesField;
		private Button clickSendButton;
		
		private EditBox buttonSyncIdField;
		private EditBox buttonIdField;
		private CycleButton<Boolean> buttonDelayToggle;
		private EditBox buttonTimesField;
		private Button buttonSendButton;
		
		private Component statusMessage;
		private int statusColor;
		
		private enum PacketMode
		{
			CLICK_SLOT,
			BUTTON_CLICK
		}
		
		private FabricatePacketScreen(Screen parent, int syncId, int revision)
		{
			super(Component.literal("Fabricate Packet"));
			this.parent = parent;
			this.initialSyncId = syncId;
			this.initialRevision = revision;
		}
		
		@Override
		protected void init()
		{
			super.init();
			panelX = (width - PANEL_WIDTH) / 2;
			panelY = (height - PANEL_HEIGHT) / 2;
			clampPanelPosition();
			
			clickSlotModeButton = Button
				.builder(Component.literal("Click Slot"),
					b -> switchMode(PacketMode.CLICK_SLOT))
				.bounds(0, 0, MODE_BUTTON_WIDTH, 20).build();
			buttonClickModeButton = Button
				.builder(Component.literal("Button Click"),
					b -> switchMode(PacketMode.BUTTON_CLICK))
				.bounds(0, 0, MODE_BUTTON_WIDTH, 20).build();
			addRenderableWidget(clickSlotModeButton);
			addRenderableWidget(buttonClickModeButton);
			
			clickSyncIdField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
				Component.literal("Sync Id"));
			clickSyncIdField.setValue(String.valueOf(initialSyncId));
			addRenderableWidget(clickSyncIdField);
			
			clickRevisionField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
				Component.literal("Revision"));
			clickRevisionField.setValue(String.valueOf(initialRevision));
			addRenderableWidget(clickRevisionField);
			
			clickSlotField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
				Component.literal("Slot"));
			clickSlotField.setValue("0");
			addRenderableWidget(clickSlotField);
			
			clickButtonField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
				Component.literal("Button"));
			clickButtonField.setValue("0");
			addRenderableWidget(clickButtonField);
			
			clickActionButton = CycleButton
				.<ClickType> builder(action -> Component.literal(action.name()),
					() -> ClickType.PICKUP)
				.withValues(ClickType.values()).create(0, 0, FIELD_WIDTH, 20,
					Component.literal("Action"), (button, value) -> {});
			addRenderableWidget(clickActionButton);
			
			clickDelayToggle =
				CycleButton.onOffBuilder(false).create(0, 0, DELAY_BUTTON_WIDTH,
					20, Component.literal("Delay"), (button, value) -> {});
			addRenderableWidget(clickDelayToggle);
			
			clickTimesField = new EditBox(font, 0, 0, TIMES_FIELD_WIDTH, 20,
				Component.literal("Times to send"));
			clickTimesField.setValue("1");
			addRenderableWidget(clickTimesField);
			
			clickSendButton =
				Button.builder(Component.literal("Send"), b -> sendClickSlot())
					.bounds(0, 0, 90, 20).build();
			addRenderableWidget(clickSendButton);
			
			buttonSyncIdField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
				Component.literal("Sync Id"));
			buttonSyncIdField.setValue(String.valueOf(initialSyncId));
			addRenderableWidget(buttonSyncIdField);
			
			buttonIdField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
				Component.literal("Button Id"));
			buttonIdField.setValue("0");
			addRenderableWidget(buttonIdField);
			
			buttonDelayToggle =
				CycleButton.onOffBuilder(false).create(0, 0, DELAY_BUTTON_WIDTH,
					20, Component.literal("Delay"), (button, value) -> {});
			addRenderableWidget(buttonDelayToggle);
			
			buttonTimesField = new EditBox(font, 0, 0, TIMES_FIELD_WIDTH, 20,
				Component.literal("Times to send"));
			buttonTimesField.setValue("1");
			addRenderableWidget(buttonTimesField);
			
			buttonSendButton = Button
				.builder(Component.literal("Send"), b -> sendButtonClick())
				.bounds(0, 0, 90, 20).build();
			addRenderableWidget(buttonSendButton);
			
			layoutWidgets();
			switchMode(PacketMode.CLICK_SLOT);
		}
		
		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY,
			float partialTicks)
		{
			super.render(graphics, mouseX, mouseY, partialTicks);
			drawFieldLabels(graphics);
			graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFFFF);
			if(statusMessage != null)
				graphics.drawCenteredString(font, statusMessage, width / 2,
					height - 25, statusColor);
		}
		
		@Override
		public void renderBackground(GuiGraphics graphics, int mouseX,
			int mouseY, float partialTicks)
		{
			super.renderBackground(graphics, mouseX, mouseY, partialTicks);
			drawPanel(graphics);
		}
		
		private void drawFieldLabels(GuiGraphics graphics)
		{
			int labelColor = 0xFFAAAAAA;
			if(selectedMode == PacketMode.CLICK_SLOT)
			{
				drawFieldLabel(graphics, "Sync Id", clickSyncIdField.getX(),
					clickSyncIdField.getY(), labelColor);
				drawFieldLabel(graphics, "Revision", clickRevisionField.getX(),
					clickRevisionField.getY(), labelColor);
				drawFieldLabel(graphics, "Slot", clickSlotField.getX(),
					clickSlotField.getY(), labelColor);
				drawFieldLabel(graphics, "Button", clickButtonField.getX(),
					clickButtonField.getY(), labelColor);
			}else
			{
				drawFieldLabel(graphics, "Sync Id", buttonSyncIdField.getX(),
					buttonSyncIdField.getY(), labelColor);
				drawFieldLabel(graphics, "Button Id", buttonIdField.getX(),
					buttonIdField.getY(), labelColor);
			}
		}
		
		private void drawFieldLabel(GuiGraphics graphics, String text, int x,
			int y, int color)
		{
			graphics.drawString(font, text, x, y - LABEL_OFFSET, color, false);
		}
		
		private void drawPanel(GuiGraphics graphics)
		{
			int x0 = panelX - PANEL_BORDER;
			int y0 = panelY - PANEL_BORDER;
			int x1 = panelX + PANEL_WIDTH + PANEL_BORDER;
			int y1 = panelY + PANEL_HEIGHT + PANEL_BORDER;
			graphics.fill(x0, y0, x1, y1, 0x90000000);
			graphics.fill(panelX, panelY, panelX + PANEL_WIDTH,
				panelY + PANEL_HEIGHT, 0xE0202030);
		}
		
		@Override
		public void onClose()
		{
			minecraft.setScreen(parent);
		}
		
		@Override
		public boolean isPauseScreen()
		{
			return false;
		}
		
		private void switchMode(PacketMode mode)
		{
			selectedMode = mode;
			updateModeButtons();
			updateFieldVisibility();
		}
		
		private void updateModeButtons()
		{
			clickSlotModeButton.setMessage(Component.literal("Click Slot"
				+ (selectedMode == PacketMode.CLICK_SLOT ? " ✓" : "")));
			buttonClickModeButton.setMessage(Component.literal("Button Click"
				+ (selectedMode == PacketMode.BUTTON_CLICK ? " ✓" : "")));
		}
		
		private void updateFieldVisibility()
		{
			boolean showClick = selectedMode == PacketMode.CLICK_SLOT;
			clickSyncIdField.visible = showClick;
			clickRevisionField.visible = showClick;
			clickSlotField.visible = showClick;
			clickButtonField.visible = showClick;
			clickActionButton.visible = showClick;
			clickDelayToggle.visible = showClick;
			clickTimesField.visible = showClick;
			clickSendButton.visible = showClick;
			if(!showClick)
			{
				clickSyncIdField.setFocused(false);
				clickRevisionField.setFocused(false);
				clickSlotField.setFocused(false);
				clickButtonField.setFocused(false);
				clickActionButton.setFocused(false);
				clickDelayToggle.setFocused(false);
				clickTimesField.setFocused(false);
			}
			
			boolean showButton = selectedMode == PacketMode.BUTTON_CLICK;
			buttonSyncIdField.visible = showButton;
			buttonIdField.visible = showButton;
			buttonDelayToggle.visible = showButton;
			buttonTimesField.visible = showButton;
			buttonSendButton.visible = showButton;
			if(!showButton)
			{
				buttonSyncIdField.setFocused(false);
				buttonIdField.setFocused(false);
				buttonDelayToggle.setFocused(false);
				buttonTimesField.setFocused(false);
			}
		}
		
		private void layoutWidgets()
		{
			clampPanelPosition();
			int modeGroupWidth = MODE_BUTTON_WIDTH * 2 + MODE_BUTTON_GAP;
			int modeStartX = panelX + (PANEL_WIDTH - modeGroupWidth) / 2;
			int modeY = panelY + 22;
			clickSlotModeButton.setX(modeStartX);
			clickSlotModeButton.setY(modeY);
			buttonClickModeButton
				.setX(modeStartX + MODE_BUTTON_WIDTH + MODE_BUTTON_GAP);
			buttonClickModeButton.setY(modeY);
			
			int inputX = panelX + (PANEL_WIDTH - FIELD_WIDTH) / 2;
			int y = modeY + 32 + CONTENT_TOP_OFFSET;
			clickSyncIdField.setX(inputX);
			clickSyncIdField.setY(y);
			y += ROW_SPACING;
			clickRevisionField.setX(inputX);
			clickRevisionField.setY(y);
			y += ROW_SPACING;
			clickSlotField.setX(inputX);
			clickSlotField.setY(y);
			y += ROW_SPACING;
			clickButtonField.setX(inputX);
			clickButtonField.setY(y);
			y += ROW_SPACING;
			clickActionButton.setX(inputX);
			clickActionButton.setY(y);
			y += ROW_SPACING;
			int delayTotal = DELAY_BUTTON_WIDTH + 8 + TIMES_FIELD_WIDTH;
			int delayX = panelX + (PANEL_WIDTH - delayTotal) / 2;
			clickDelayToggle.setX(delayX);
			clickDelayToggle.setY(y);
			clickTimesField.setX(delayX + DELAY_BUTTON_WIDTH + 8);
			clickTimesField.setY(y);
			y += ROW_SPACING;
			clickSendButton.setX(panelX + (PANEL_WIDTH - 90) / 2);
			clickSendButton.setY(y);
			
			int buttonY = modeY + 32 + CONTENT_TOP_OFFSET;
			buttonSyncIdField.setX(inputX);
			buttonSyncIdField.setY(buttonY);
			buttonY += ROW_SPACING;
			buttonIdField.setX(inputX);
			buttonIdField.setY(buttonY);
			buttonY += ROW_SPACING;
			buttonDelayToggle.setX(delayX);
			buttonDelayToggle.setY(buttonY);
			buttonTimesField.setX(delayX + DELAY_BUTTON_WIDTH + 8);
			buttonTimesField.setY(buttonY);
			buttonY += ROW_SPACING;
			buttonSendButton.setX(panelX + (PANEL_WIDTH - 90) / 2);
			buttonSendButton.setY(buttonY);
		}
		
		private void clampPanelPosition()
		{
			panelX = Mth.clamp(panelX, 8, Math.max(width - PANEL_WIDTH - 8, 8));
			panelY =
				Mth.clamp(panelY, 8, Math.max(height - PANEL_HEIGHT - 8, 8));
		}
		
		private void sendClickSlot()
		{
			if(!UiUtils.isInteger(clickSyncIdField.getValue())
				|| !UiUtils.isInteger(clickRevisionField.getValue())
				|| !UiUtils.isInteger(clickSlotField.getValue())
				|| !UiUtils.isInteger(clickButtonField.getValue())
				|| !UiUtils.isInteger(clickTimesField.getValue()))
			{
				showStatus(Component.literal("Invalid arguments!"),
					STATUS_ERROR);
				return;
			}
			
			int syncId = Integer.parseInt(clickSyncIdField.getValue());
			short slot = Short.parseShort(clickSlotField.getValue());
			byte button = Byte.parseByte(clickButtonField.getValue());
			int timesToSend = Integer.parseInt(clickTimesField.getValue());
			if(timesToSend < 1)
			{
				showStatus(Component.literal("Invalid arguments!"),
					STATUS_ERROR);
				return;
			}
			
			ClickType action = clickActionButton.getValue();
			if(action == null)
			{
				showStatus(Component.literal("Invalid arguments!"),
					STATUS_ERROR);
				return;
			}
			
			if(minecraft.getConnection() == null || minecraft.player == null)
			{
				showStatus(
					Component.literal("You must be connected to a server!"),
					STATUS_ERROR);
				return;
			}
			
			AbstractContainerMenu menu = minecraft.player.containerMenu;
			if(menu == null)
			{
				showStatus(Component.literal("No open container!"),
					STATUS_ERROR);
				return;
			}
			
			HashedPatchMap.HashGenerator hashGenerator =
				minecraft.getConnection().decoratedHashOpsGenenerator();
			// Capture slot contents before simulating the click
			java.util.List<net.minecraft.world.item.ItemStack> beforeStacks =
				new java.util.ArrayList<>(menu.slots.size());
			for(int i = 0; i < menu.slots.size(); i++)
				beforeStacks.add(menu.slots.get(i).getItem().copy());
			net.minecraft.world.item.ItemStack carriedBeforeStack =
				menu.getCarried().copy();
			
			// Locally simulate the click
			menu.clicked(slot, button, action, minecraft.player);
			
			// Use current revision after local simulation (matches vanilla
			// client behavior)
			int revision = menu.getStateId();
			
			// Build diff by comparing ItemStacks (item + count) to avoid
			// over-reporting
			Int2ObjectMap<HashedStack> diffSlots = new Int2ObjectArrayMap<>();
			StringBuilder diffLog = new StringBuilder();
			for(int i = 0; i < menu.slots.size(); i++)
			{
				net.minecraft.world.item.ItemStack beforeStack =
					beforeStacks.get(i);
				net.minecraft.world.item.ItemStack afterStack =
					menu.slots.get(i).getItem();
				boolean changed;
				if(beforeStack.isEmpty() && afterStack.isEmpty())
					changed = false;
				else if(beforeStack.isEmpty() != afterStack.isEmpty())
					changed = true;
				else
					changed = beforeStack.getItem() != afterStack.getItem()
						|| beforeStack.getCount() != afterStack.getCount();
				if(changed)
				{
					HashedStack afterHashed =
						HashedStack.create(afterStack, hashGenerator);
					diffSlots.put(i, afterHashed);
					diffLog.append("[").append(i).append(": ")
						.append(beforeStack.isEmpty() ? "empty"
							: beforeStack.getItem().toString() + "x"
								+ beforeStack.getCount())
						.append(" -> ")
						.append(afterStack.isEmpty() ? "empty"
							: afterStack.getItem().toString() + "x"
								+ afterStack.getCount())
						.append("] ");
				}
			}
			
			HashedStack cursor =
				HashedStack.create(menu.getCarried(), hashGenerator);
			HashedStack carriedBefore =
				HashedStack.create(carriedBeforeStack, hashGenerator); // for
																		// logging
																		// only;
																		// not
																		// used
																		// in
																		// packet
			
			ServerboundContainerClickPacket packet =
				new ServerboundContainerClickPacket(syncId, revision, slot,
					button, action, diffSlots, cursor);
			
			try
			{
				LOGGER.info(
					"Fabricate ClickSlot: syncId={}, revision={}, slot={}, button={}, action={}, times={}, diffSlots={}, carriedBefore={}, carriedAfter={}",
					syncId, revision, slot, button, action, timesToSend,
					diffSlots.size(), carriedBefore, cursor);
				LOGGER.info(
					"Fabricate ClickSlot: menu.containerId={}, syncIdMatch={}, diffDetail={}",
					menu.containerId, (menu.containerId == syncId),
					diffLog.toString());
				chatIfEnabled("ClickSlot: slot=" + slot + ", action=" + action
					+ ", diff=" + diffSlots.size());
				Runnable toRun = getFabricatePacketRunnable(minecraft,
					clickDelayToggle.getValue(), packet);
				for(int i = 0; i < timesToSend; i++)
					toRun.run();
			}catch(Exception e)
			{
				showStatus(
					Component.literal("You must be connected to a server!"),
					STATUS_ERROR);
				return;
			}
			
			showStatus(Component.literal("Sent successfully!"), STATUS_SUCCESS);
		}
		
		private void sendButtonClick()
		{
			if(!UiUtils.isInteger(buttonSyncIdField.getValue())
				|| !UiUtils.isInteger(buttonIdField.getValue())
				|| !UiUtils.isInteger(buttonTimesField.getValue()))
			{
				showStatus(Component.literal("Invalid arguments!"),
					STATUS_ERROR);
				return;
			}
			
			int syncId = Integer.parseInt(buttonSyncIdField.getValue());
			int buttonId = Integer.parseInt(buttonIdField.getValue());
			int timesToSend = Integer.parseInt(buttonTimesField.getValue());
			if(timesToSend < 1)
			{
				showStatus(Component.literal("Invalid arguments!"),
					STATUS_ERROR);
				return;
			}
			
			ServerboundContainerButtonClickPacket packet =
				new ServerboundContainerButtonClickPacket(syncId, buttonId);
			
			try
			{
				LOGGER.info(
					"Fabricate ButtonClick: syncId={}, buttonId={}, times={}",
					syncId, buttonId, timesToSend);
				chatIfEnabled("ButtonClick: buttonId=" + buttonId + ", times="
					+ timesToSend);
				Runnable toRun = getFabricatePacketRunnable(minecraft,
					buttonDelayToggle.getValue(), packet);
				for(int i = 0; i < timesToSend; i++)
					toRun.run();
			}catch(Exception e)
			{
				showStatus(
					Component.literal("You must be connected to a server!"),
					STATUS_ERROR);
				return;
			}
			
			showStatus(Component.literal("Sent successfully!"), STATUS_SUCCESS);
		}
		
		private void showStatus(Component message, int color)
		{
			statusMessage = message;
			statusColor = color;
			queueTask(() -> {
				if(minecraft.screen == this)
				{
					statusMessage = null;
					statusColor = 0;
				}
			}, STATUS_DURATION_MS);
		}
		
	}
	
	private static final class RestoreScreenHandler implements UpdateListener
	{
		private boolean customKeyDown;
		
		@Override
		public void onUpdate()
		{
			if(!UiUtilsState.isUiEnabled())
				return;
			
			Minecraft mc = Minecraft.getInstance();
			InputConstants.Key customKey = null;
			try
			{
				customKey =
					WurstClient.INSTANCE.getHax().uiUtilsHack.getRestoreKey();
			}catch(Throwable t)
			{
				customKey = null;
			}
			
			if(customKey != null)
			{
				boolean down = InputConstants.isKeyDown(mc.getWindow(),
					customKey.getValue());
				if(down && !customKeyDown)
					restoreScreen(mc);
				customKeyDown = down;
				return;
			}
			
			customKeyDown = false;
			while(restoreScreenKey.consumeClick())
				restoreScreen(mc);
		}
		
		private void restoreScreen(Minecraft mc)
		{
			if(UiUtilsState.storedScreen != null
				&& UiUtilsState.storedMenu != null && mc.player != null)
			{
				mc.setScreen(UiUtilsState.storedScreen);
				mc.player.containerMenu = UiUtilsState.storedMenu;
				try
				{
					String title =
						UiUtilsState.storedScreen.getTitle().getString();
					int sid = UiUtilsState.storedMenu.containerId;
					int rev = UiUtilsState.storedMenu.getStateId();
					chatIfEnabled("Loaded GUI: title=\"" + title + "\", syncId="
						+ sid + ", revision=" + rev);
				}catch(Throwable ignored)
				{}
			}
		}
	}
}
