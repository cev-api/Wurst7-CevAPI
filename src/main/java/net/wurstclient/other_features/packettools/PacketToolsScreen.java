/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.wurstclient.other_features.PacketToolsOtf;
import net.wurstclient.other_features.PacketToolsOtf.PacketDirection;
import net.wurstclient.other_features.PacketToolsOtf.PacketMode;

public final class PacketToolsScreen extends Screen
{
	private final Screen parent;
	private final PacketToolsOtf packetTools;
	
	private PacketMode editMode = PacketMode.LOG;
	
	private Button loggingButton;
	private Button denyButton;
	private Button delayButton;
	private Button outputButton;
	private Button modeButton;
	private Button delayMinusButton;
	private Button delayPlusButton;
	
	private boolean holdingDelayMinus;
	private boolean holdingDelayPlus;
	private int holdTicks;
	
	private DualPacketListWidget s2cSelector;
	private DualPacketListWidget c2sSelector;
	
	public PacketToolsScreen(Screen parent, PacketToolsOtf packetTools)
	{
		super(Component.literal("Advanced Packet Tool"));
		this.parent = parent;
		this.packetTools = packetTools;
	}
	
	@Override
	protected void init()
	{
		super.init();
		
		int panelWidth = Math.min(560, width - 30);
		int panelX = (width - panelWidth) / 2;
		int y = 28;
		int gap = 6;
		
		int half = (panelWidth - gap) / 2;
		int third = (panelWidth - gap * 2) / 3;
		
		loggingButton =
			addRenderableWidget(
				Button.builder(
					enabledLabel("Logging",
						packetTools.getLoggingEnabledSetting().isChecked()),
					b -> {
						boolean value =
							!packetTools.getLoggingEnabledSetting().isChecked();
						packetTools.getLoggingEnabledSetting()
							.setChecked(value);
						b.setMessage(enabledLabel("Logging", value));
					}).bounds(panelX, y, third, 20).build());
		
		denyButton =
			addRenderableWidget(
				Button
					.builder(
						enabledLabel("Deny",
							packetTools.getDenyEnabledSetting().isChecked()),
						b -> {
							boolean value = !packetTools.getDenyEnabledSetting()
								.isChecked();
							packetTools.getDenyEnabledSetting()
								.setChecked(value);
							b.setMessage(enabledLabel("Deny", value));
						})
					.bounds(panelX + third + gap, y, third, 20).build());
		
		delayButton =
			addRenderableWidget(
				Button.builder(
					enabledLabel("Delay",
						packetTools.getDelayEnabledSetting().isChecked()),
					b -> {
						boolean value =
							!packetTools.getDelayEnabledSetting().isChecked();
						packetTools.getDelayEnabledSetting().setChecked(value);
						b.setMessage(enabledLabel("Delay", value));
					}).bounds(panelX + (third + gap) * 2, y, third, 20)
					.build());
		
		y += 24;
		
		outputButton = addRenderableWidget(Button.builder(Component.literal(
			"Output: " + (packetTools.getFileOutputSetting().isChecked()
				? "File" : "Chat")),
			b -> {
				boolean file = !packetTools.getFileOutputSetting().isChecked();
				packetTools.getFileOutputSetting().setChecked(file);
				b.setMessage(
					Component.literal("Output: " + (file ? "File" : "Chat")));
			}).bounds(panelX, y, half, 20).build());
		
		modeButton = addRenderableWidget(
			Button.builder(Component.literal("Editing: " + editMode.getLabel()),
				b -> {
					PacketMode previous = editMode;
					editMode = editMode.next();
					b.setMessage(
						Component.literal("Editing: " + editMode.getLabel()));
					reloadSelectorsFromMode(previous);
				}).bounds(panelX + half + gap, y, half, 20).build());
		
		y += 24;
		
		int controlWidth = 70;
		addRenderableWidget(Button
			.builder(Component.literal("S2C All"), b -> s2cSelector.selectAll())
			.bounds(panelX, y, controlWidth, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("S2C None"), b -> s2cSelector.clearAll())
			.bounds(panelX + controlWidth + gap, y, controlWidth + 12, 20)
			.build());
		addRenderableWidget(Button
			.builder(Component.literal("C2S All"), b -> c2sSelector.selectAll())
			.bounds(panelX + panelWidth - (controlWidth * 2 + gap + 12), y,
				controlWidth, 20)
			.build());
		addRenderableWidget(Button
			.builder(Component.literal("C2S None"), b -> c2sSelector.clearAll())
			.bounds(panelX + panelWidth - (controlWidth + 12), y,
				controlWidth + 12, 20)
			.build());
		
		int delayCenter = panelX + panelWidth / 2;
		delayMinusButton = addRenderableWidget(
			Button.builder(Component.literal("-"), b -> changeDelay(-1))
				.bounds(delayCenter - 66, y, 20, 20).build());
		delayPlusButton = addRenderableWidget(
			Button.builder(Component.literal("+"), b -> changeDelay(1))
				.bounds(delayCenter + 46, y, 20, 20).build());
		
		y += 24;
		int selectorHeight = (height - y - 56) / 2 - 4;
		
		s2cSelector = new DualPacketListWidget(panelX, y, panelWidth,
			selectorHeight, "S2C Packets (Server -> Client)",
			packetTools.getAvailablePackets(PacketDirection.S2C),
			packetTools.getSelection(editMode, PacketDirection.S2C), set -> {});
		addRenderableWidget(s2cSelector.getSearchBox());
		
		y += selectorHeight + 8;
		
		c2sSelector = new DualPacketListWidget(panelX, y, panelWidth,
			selectorHeight, "C2S Packets (Client -> Server)",
			packetTools.getAvailablePackets(PacketDirection.C2S),
			packetTools.getSelection(editMode, PacketDirection.C2S), set -> {});
		addRenderableWidget(c2sSelector.getSearchBox());
		
		int bottomY = height - 28;
		addRenderableWidget(
			Button.builder(Component.literal("Save"), b -> saveAndClose())
				.bounds(width / 2 - 105, bottomY, 100, 20).build());
		addRenderableWidget(
			Button.builder(Component.literal("Cancel"), b -> onClose())
				.bounds(width / 2 + 5, bottomY, 100, 20).build());
	}
	
	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY,
		float partialTick)
	{
		context.fillGradient(0, 0, width, height, 0xA0101010, 0xB0101010);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		renderBackground(context, mouseX, mouseY, partialTicks);
		
		int panelWidth = Math.min(560, width - 30);
		int panelX = (width - panelWidth) / 2;
		int panelY = 20;
		int panelHeight = height - 48;
		context.fill(panelX - 2, panelY - 2, panelX + panelWidth + 2,
			panelY + panelHeight + 2, 0xFF2A2A2A);
		context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight,
			0xE0181818);
		
		s2cSelector.render(context, mouseX, mouseY, partialTicks);
		c2sSelector.render(context, mouseX, mouseY, partialTicks);
		
		Matrix3x2fStack stack = context.pose();
		stack.pushMatrix();
		for(var renderable : renderables)
			renderable.render(context, mouseX, mouseY, partialTicks);
		stack.popMatrix();
		
		context.drawCenteredString(font, title, width / 2, 7, 0xFFFFFFFF);
		
		String delayLabel =
			"Delay ticks: " + packetTools.getDelayTicksSetting().getValueI();
		context.drawCenteredString(font, delayLabel, width / 2, 76, 0xFFFFFFFF);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		boolean superHandled = super.mouseClicked(event, doubleClick);
		boolean selectorHandled = s2cSelector.mouseClicked(event, doubleClick)
			|| c2sSelector.mouseClicked(event, doubleClick);
		
		boolean leftClick =
			event.button() == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
		holdingDelayMinus = leftClick && delayMinusButton != null
			&& delayMinusButton.isHoveredOrFocused();
		holdingDelayPlus = leftClick && delayPlusButton != null
			&& delayPlusButton.isHoveredOrFocused();
		if(holdingDelayMinus || holdingDelayPlus)
			holdTicks = 0;
		
		return superHandled || selectorHandled;
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent event)
	{
		s2cSelector.mouseReleased(event);
		c2sSelector.mouseReleased(event);
		
		holdingDelayMinus = false;
		holdingDelayPlus = false;
		holdTicks = 0;
		return super.mouseReleased(event);
	}
	
	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX,
		double deltaY)
	{
		boolean dragged = s2cSelector.mouseDragged(event, deltaX, deltaY)
			|| c2sSelector.mouseDragged(event, deltaX, deltaY);
		return dragged || super.mouseDragged(event, deltaX, deltaY);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount)
	{
		if(s2cSelector.mouseScrolled(mouseX, mouseY, verticalAmount))
			return true;
		if(c2sSelector.mouseScrolled(mouseX, mouseY, verticalAmount))
			return true;
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount,
			verticalAmount);
	}
	
	@Override
	public boolean keyPressed(KeyEvent event)
	{
		if(super.keyPressed(event))
			return true;
		return s2cSelector.keyPressed(event) || c2sSelector.keyPressed(event);
	}
	
	@Override
	public boolean charTyped(CharacterEvent event)
	{
		if(super.charTyped(event))
			return true;
		return s2cSelector.charTyped(event) || c2sSelector.charTyped(event);
	}
	
	@Override
	public void tick()
	{
		super.tick();
		
		if(!(holdingDelayMinus || holdingDelayPlus))
		{
			holdTicks = 0;
			return;
		}
		
		holdTicks++;
		if(holdTicks < 6)
			return;
		
		int step = holdTicks >= 50 ? 50 : holdTicks >= 30 ? 20 : 10;
		changeDelay(holdingDelayPlus ? step : -step);
	}
	
	private void saveAndClose()
	{
		packetTools.updateSelection(editMode, PacketDirection.S2C,
			s2cSelector.getSelection());
		packetTools.updateSelection(editMode, PacketDirection.C2S,
			c2sSelector.getSelection());
		packetTools.saveSelectionConfig();
		onClose();
	}
	
	private void reloadSelectorsFromMode(PacketMode previousMode)
	{
		packetTools.updateSelection(previousMode, PacketDirection.S2C,
			s2cSelector.getSelection());
		packetTools.updateSelection(previousMode, PacketDirection.C2S,
			c2sSelector.getSelection());
		
		s2cSelector
			.setPackets(packetTools.getAvailablePackets(PacketDirection.S2C));
		c2sSelector
			.setPackets(packetTools.getAvailablePackets(PacketDirection.C2S));
		s2cSelector.setSelection(
			packetTools.getSelection(editMode, PacketDirection.S2C));
		c2sSelector.setSelection(
			packetTools.getSelection(editMode, PacketDirection.C2S));
	}
	
	private void changeDelay(int delta)
	{
		int value = packetTools.getDelayTicksSetting().getValueI();
		value = Math.max(0, Math.min(9999, value + delta));
		packetTools.getDelayTicksSetting().setValue(value);
	}
	
	@Override
	public void onClose()
	{
		if(minecraft != null)
			minecraft.setScreen(parent);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	private static Component enabledLabel(String name, boolean enabled)
	{
		Component state = Component.literal(enabled ? "ON" : "OFF")
			.withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
		return Component.literal(name + ": ").append(state);
	}
}
