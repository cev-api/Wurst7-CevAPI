/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.awt.Color;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.MacosUtil;
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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
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
import net.wurstclient.mixin.ui_utils.ConnectionAccessor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UiUtils
{
	public static final String VERSION = "2.4.0";
	public static java.awt.Font monospace;
	public static Color darkWhite;
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
		
		if(!MacosUtil.IS_MACOS)
		{
			System.setProperty("java.awt.headless", "false");
			monospace = new java.awt.Font(java.awt.Font.MONOSPACED,
				java.awt.Font.PLAIN, 10);
			darkWhite = new Color(220, 220, 220);
		}
		
		initialized = true;
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
		adder
			.accept(Button
				.builder(Component.literal("Close without packet"),
					b -> mc.setScreen(null))
				.bounds(baseX, baseY, 115, 20).build());
		int y = baseY + 20 + spacing;
		
		adder.accept(Button.builder(Component.literal("De-sync"), b -> {
			if(mc.getConnection() != null && mc.player != null)
				mc.getConnection().send(new ServerboundContainerClosePacket(
					mc.player.containerMenu.containerId));
			else
				LOGGER.warn(
					"Minecraft connection or player was null while using 'De-sync'.");
		}).bounds(baseX, y, 115, 20).build());
		y += 20 + spacing;
		
		adder.accept(Button.builder(
			Component.literal("Send packets: " + UiUtilsState.sendUiPackets),
			b -> {
				UiUtilsState.sendUiPackets = !UiUtilsState.sendUiPackets;
				b.setMessage(Component
					.literal("Send packets: " + UiUtilsState.sendUiPackets));
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
			}).bounds(baseX, y, 115, 20).build());
		y += 20 + spacing;
		
		adder.accept(Button.builder(Component.literal("Save GUI"), b -> {
			if(mc.player != null)
			{
				UiUtilsState.storedScreen = mc.screen;
				UiUtilsState.storedMenu = mc.player.containerMenu;
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
			}).bounds(baseX, y, 160, 20).build());
		y += 20 + spacing;
		
		Button fabricatePacketButton =
			Button.builder(Component.literal("Fabricate packet"), b -> {
				JFrame frame = new JFrame("Choose Packet");
				frame.setBounds(0, 0, 450, 100);
				frame.setResizable(false);
				frame.setLocationRelativeTo(null);
				frame.setLayout(null);
				
				JButton clickSlotButton = getPacketOptionButton("Click Slot");
				clickSlotButton.setBounds(100, 25, 110, 20);
				clickSlotButton.addActionListener(event -> {
					frame.setVisible(false);
					
					JFrame clickSlotFrame = new JFrame("Click Slot Packet");
					clickSlotFrame.setBounds(0, 0, 450, 300);
					clickSlotFrame.setResizable(false);
					clickSlotFrame.setLocationRelativeTo(null);
					clickSlotFrame.setLayout(null);
					
					JLabel syncIdLabel = new JLabel("Sync Id:");
					syncIdLabel.setFocusable(false);
					syncIdLabel.setFont(monospace);
					syncIdLabel.setBounds(25, 25, 100, 20);
					
					JLabel revisionLabel = new JLabel("Revision:");
					revisionLabel.setFocusable(false);
					revisionLabel.setFont(monospace);
					revisionLabel.setBounds(25, 50, 100, 20);
					
					JLabel slotLabel = new JLabel("Slot:");
					slotLabel.setFocusable(false);
					slotLabel.setFont(monospace);
					slotLabel.setBounds(25, 75, 100, 20);
					
					JLabel buttonLabel = new JLabel("Button:");
					buttonLabel.setFocusable(false);
					buttonLabel.setFont(monospace);
					buttonLabel.setBounds(25, 100, 100, 20);
					
					JLabel actionLabel = new JLabel("Action:");
					actionLabel.setFocusable(false);
					actionLabel.setFont(monospace);
					actionLabel.setBounds(25, 125, 100, 20);
					
					JLabel timesToSendLabel = new JLabel("Times to send:");
					timesToSendLabel.setFocusable(false);
					timesToSendLabel.setFont(monospace);
					timesToSendLabel.setBounds(25, 190, 100, 20);
					
					JTextField syncIdField = new JTextField(1);
					syncIdField.setFont(monospace);
					syncIdField.setBounds(125, 25, 100, 20);
					
					JTextField revisionField = new JTextField(1);
					revisionField.setFont(monospace);
					revisionField.setBounds(125, 50, 100, 20);
					
					JTextField slotField = new JTextField(1);
					slotField.setFont(monospace);
					slotField.setBounds(125, 75, 100, 20);
					
					JTextField buttonField = new JTextField(1);
					buttonField.setFont(monospace);
					buttonField.setBounds(125, 100, 100, 20);
					
					JComboBox<String> actionField =
						new JComboBox<>(new Vector<>(ImmutableList.of("PICKUP",
							"QUICK_MOVE", "SWAP", "CLONE", "THROW",
							"QUICK_CRAFT", "PICKUP_ALL")));
					actionField.setFocusable(false);
					actionField.setEditable(false);
					actionField.setBorder(BorderFactory.createEmptyBorder());
					actionField.setBackground(darkWhite);
					actionField.setFont(monospace);
					actionField.setBounds(125, 125, 100, 20);
					
					JLabel statusLabel = new JLabel();
					statusLabel.setVisible(false);
					statusLabel.setFocusable(false);
					statusLabel.setFont(monospace);
					statusLabel.setBounds(210, 150, 190, 20);
					
					JCheckBox delayBox = new JCheckBox("Delay");
					delayBox.setBounds(115, 150, 85, 20);
					delayBox.setSelected(false);
					delayBox.setFont(monospace);
					delayBox.setFocusable(false);
					
					JTextField timesToSendField = new JTextField("1");
					timesToSendField.setFont(monospace);
					timesToSendField.setBounds(125, 190, 100, 20);
					
					JButton sendButton = new JButton("Send");
					sendButton.setFocusable(false);
					sendButton.setBounds(25, 150, 75, 20);
					sendButton.setBorder(BorderFactory.createEtchedBorder());
					sendButton.setBackground(darkWhite);
					sendButton.setFont(monospace);
					sendButton.addActionListener(event0 -> {
						if(isInteger(syncIdField.getText())
							&& isInteger(revisionField.getText())
							&& isInteger(slotField.getText())
							&& isInteger(buttonField.getText())
							&& isInteger(timesToSendField.getText())
							&& actionField.getSelectedItem() != null)
						{
							int syncId =
								Integer.parseInt(syncIdField.getText());
							int revision =
								Integer.parseInt(revisionField.getText());
							short slot = Short.parseShort(slotField.getText());
							byte button0 =
								Byte.parseByte(buttonField.getText());
							ClickType action = stringToClickType(
								actionField.getSelectedItem().toString());
							int timesToSend =
								Integer.parseInt(timesToSendField.getText());
							
							if(action != null)
							{
								Int2ObjectMap<HashedStack> changedSlots =
									new Int2ObjectArrayMap<>();
								ServerboundContainerClickPacket packet =
									new ServerboundContainerClickPacket(syncId,
										revision, slot, button0, action,
										changedSlots, HashedStack.EMPTY);
								try
								{
									Runnable toRun = getFabricatePacketRunnable(
										mc, delayBox.isSelected(), packet);
									for(int i = 0; i < timesToSend; i++)
										toRun.run();
								}catch(Exception e)
								{
									statusLabel
										.setForeground(Color.RED.darker());
									statusLabel.setText(
										"You must be connected to a server!");
									queueTask(() -> {
										statusLabel.setVisible(false);
										statusLabel.setText("");
									}, 1500L);
									return;
								}
								statusLabel.setVisible(true);
								statusLabel.setForeground(Color.GREEN.darker());
								statusLabel.setText("Sent successfully!");
								queueTask(() -> {
									statusLabel.setVisible(false);
									statusLabel.setText("");
								}, 1500L);
							}else
								showInvalidArgs(statusLabel);
						}else
							showInvalidArgs(statusLabel);
					});
					
					clickSlotFrame.add(syncIdLabel);
					clickSlotFrame.add(revisionLabel);
					clickSlotFrame.add(slotLabel);
					clickSlotFrame.add(buttonLabel);
					clickSlotFrame.add(actionLabel);
					clickSlotFrame.add(timesToSendLabel);
					clickSlotFrame.add(syncIdField);
					clickSlotFrame.add(revisionField);
					clickSlotFrame.add(slotField);
					clickSlotFrame.add(buttonField);
					clickSlotFrame.add(actionField);
					clickSlotFrame.add(sendButton);
					clickSlotFrame.add(statusLabel);
					clickSlotFrame.add(delayBox);
					clickSlotFrame.add(timesToSendField);
					clickSlotFrame.setVisible(true);
				});
				
				JButton buttonClickButton =
					getPacketOptionButton("Button Click");
				buttonClickButton.setBounds(250, 25, 110, 20);
				buttonClickButton.addActionListener(event -> {
					frame.setVisible(false);
					
					JFrame buttonClickFrame = new JFrame("Button Click Packet");
					buttonClickFrame.setBounds(0, 0, 450, 250);
					buttonClickFrame.setResizable(false);
					buttonClickFrame.setLocationRelativeTo(null);
					buttonClickFrame.setLayout(null);
					
					JLabel syncIdLabel = new JLabel("Sync Id:");
					syncIdLabel.setFocusable(false);
					syncIdLabel.setFont(monospace);
					syncIdLabel.setBounds(25, 25, 100, 20);
					
					JLabel buttonIdLabel = new JLabel("Button Id:");
					buttonIdLabel.setFocusable(false);
					buttonIdLabel.setFont(monospace);
					buttonIdLabel.setBounds(25, 50, 100, 20);
					
					JTextField syncIdField = new JTextField(1);
					syncIdField.setFont(monospace);
					syncIdField.setBounds(125, 25, 100, 20);
					
					JTextField buttonIdField = new JTextField(1);
					buttonIdField.setFont(monospace);
					buttonIdField.setBounds(125, 50, 100, 20);
					
					JLabel statusLabel = new JLabel();
					statusLabel.setVisible(false);
					statusLabel.setFocusable(false);
					statusLabel.setFont(monospace);
					statusLabel.setBounds(210, 95, 190, 20);
					
					JCheckBox delayBox = new JCheckBox("Delay");
					delayBox.setBounds(115, 95, 85, 20);
					delayBox.setSelected(false);
					delayBox.setFont(monospace);
					delayBox.setFocusable(false);
					
					JLabel timesToSendLabel = new JLabel("Times to send:");
					timesToSendLabel.setFocusable(false);
					timesToSendLabel.setFont(monospace);
					timesToSendLabel.setBounds(25, 130, 100, 20);
					
					JTextField timesToSendField = new JTextField("1");
					timesToSendField.setFont(monospace);
					timesToSendField.setBounds(125, 130, 100, 20);
					
					JButton sendButton = new JButton("Send");
					sendButton.setFocusable(false);
					sendButton.setBounds(25, 95, 75, 20);
					sendButton.setBorder(BorderFactory.createEtchedBorder());
					sendButton.setBackground(darkWhite);
					sendButton.setFont(monospace);
					sendButton.addActionListener(event0 -> {
						if(isInteger(syncIdField.getText())
							&& isInteger(buttonIdField.getText())
							&& isInteger(timesToSendField.getText()))
						{
							int syncId =
								Integer.parseInt(syncIdField.getText());
							int buttonId =
								Integer.parseInt(buttonIdField.getText());
							int timesToSend =
								Integer.parseInt(timesToSendField.getText());
							
							ServerboundContainerButtonClickPacket packet =
								new ServerboundContainerButtonClickPacket(
									syncId, buttonId);
							try
							{
								Runnable toRun = getFabricatePacketRunnable(mc,
									delayBox.isSelected(), packet);
								for(int i = 0; i < timesToSend; i++)
									toRun.run();
							}catch(Exception e)
							{
								statusLabel.setVisible(true);
								statusLabel.setForeground(Color.RED.darker());
								statusLabel.setText(
									"You must be connected to a server!");
								queueTask(() -> {
									statusLabel.setVisible(false);
									statusLabel.setText("");
								}, 1500L);
								return;
							}
							statusLabel.setVisible(true);
							statusLabel.setForeground(Color.GREEN.darker());
							statusLabel.setText("Sent successfully!");
							queueTask(() -> {
								statusLabel.setVisible(false);
								statusLabel.setText("");
							}, 1500L);
						}else
							showInvalidArgs(statusLabel);
					});
					
					buttonClickFrame.add(syncIdLabel);
					buttonClickFrame.add(buttonIdLabel);
					buttonClickFrame.add(syncIdField);
					buttonClickFrame.add(timesToSendLabel);
					buttonClickFrame.add(buttonIdField);
					buttonClickFrame.add(sendButton);
					buttonClickFrame.add(statusLabel);
					buttonClickFrame.add(delayBox);
					buttonClickFrame.add(timesToSendField);
					buttonClickFrame.setVisible(true);
				});
				
				frame.add(clickSlotButton);
				frame.add(buttonClickButton);
				frame.setVisible(true);
			}).bounds(baseX, y, 115, 20).build();
		fabricatePacketButton.active = !MacosUtil.IS_MACOS;
		adder.accept(fabricatePacketButton);
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
				}catch(IllegalStateException e)
				{
					LOGGER.error("Error while copying title JSON to clipboard",
						e);
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
	private static JButton getPacketOptionButton(String label)
	{
		JButton button = new JButton(label);
		button.setFocusable(false);
		button.setBorder(BorderFactory.createEtchedBorder());
		button.setBackground(darkWhite);
		button.setFont(monospace);
		return button;
	}
	
	@NotNull
	private static Runnable getFabricatePacketRunnable(Minecraft mc,
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
				mc.getConnection().send(packet);
				((ConnectionAccessor)mc.getConnection().getConnection())
					.getChannel().writeAndFlush(packet);
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
	
	public static ClickType stringToClickType(String string)
	{
		return switch(string)
		{
			case "PICKUP" -> ClickType.PICKUP;
			case "QUICK_MOVE" -> ClickType.QUICK_MOVE;
			case "SWAP" -> ClickType.SWAP;
			case "CLONE" -> ClickType.CLONE;
			case "THROW" -> ClickType.THROW;
			case "QUICK_CRAFT" -> ClickType.QUICK_CRAFT;
			case "PICKUP_ALL" -> ClickType.PICKUP_ALL;
			default -> null;
		};
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
	
	private static void showInvalidArgs(JLabel statusLabel)
	{
		statusLabel.setVisible(true);
		statusLabel.setForeground(Color.RED.darker());
		statusLabel.setText("Invalid arguments!");
		queueTask(() -> {
			statusLabel.setVisible(false);
			statusLabel.setText("");
		}, 1500L);
	}
	
	private static final class RestoreScreenHandler implements UpdateListener
	{
		@Override
		public void onUpdate()
		{
			if(!UiUtilsState.isUiEnabled())
				return;
			
			Minecraft mc = Minecraft.getInstance();
			while(restoreScreenKey.consumeClick())
			{
				if(UiUtilsState.storedScreen != null
					&& UiUtilsState.storedMenu != null && mc.player != null)
				{
					mc.setScreen(UiUtilsState.storedScreen);
					mc.player.containerMenu = UiUtilsState.storedMenu;
				}
			}
		}
	}
}
