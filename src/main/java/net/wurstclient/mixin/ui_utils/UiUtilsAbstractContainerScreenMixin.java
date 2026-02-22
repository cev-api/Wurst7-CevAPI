/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.ui_utils;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.util.Mth;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.UiUtilsHack;
import net.minecraft.world.inventory.ClickType;
import net.wurstclient.uiutils.UiUtils;
import net.wurstclient.uiutils.UiUtilsState;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

@Mixin(AbstractContainerScreen.class)
public abstract class UiUtilsAbstractContainerScreenMixin<T extends AbstractContainerMenu>
	extends Screen
{
	@Unique
	private EditBox uiUtilsChatField;
	
	@Shadow
	protected int leftPos;
	
	@Shadow
	protected int topPos;
	
	@Shadow
	protected int imageWidth;
	
	@Unique
	private boolean fabricateOverlayInitialized;
	
	@Unique
	private int fabricateMode = MODE_CLICK_SLOT;
	
	@Unique
	private Button overlayClickSlotModeButton;
	
	@Unique
	private Button overlayButtonClickModeButton;
	
	@Unique
	private EditBox overlayClickSyncIdField;
	
	@Unique
	private EditBox overlayClickRevisionField;
	
	@Unique
	private EditBox overlayClickSlotField;
	
	@Unique
	private EditBox overlayClickButtonField;
	
	@Unique
	private CycleButton<ClickType> overlayClickActionButton;
	
	@Unique
	private CycleButton<Boolean> overlayClickDelayToggle;
	
	@Unique
	private EditBox overlayClickTimesField;
	
	@Unique
	private Button overlayClickSendButton;
	
	@Unique
	private EditBox overlayButtonSyncIdField;
	
	@Unique
	private EditBox overlayButtonIdField;
	
	@Unique
	private CycleButton<Boolean> overlayButtonDelayToggle;
	
	@Unique
	private EditBox overlayButtonTimesField;
	
	@Unique
	private Button overlayButtonSendButton;
	
	@Unique
	private static final int OVERLAY_WIDTH = 260;
	
	@Unique
	private static final int MODE_BUTTON_WIDTH = 110;
	
	@Unique
	private static final int MODE_BUTTON_GAP = 10;
	
	@Unique
	private static final int FIELD_WIDTH = 110;
	
	@Unique
	private static final int ROW_SPACING = 36;
	
	@Unique
	private static final int LABEL_OFFSET = 10;
	
	@Unique
	private static final int CONTENT_TOP_OFFSET = 12;
	
	@Unique
	private static final int OVERLAY_DRAG_BAR_HEIGHT = 10;
	
	@Unique
	private static final int DELAY_BUTTON_WIDTH = 90;
	
	@Unique
	private static final int TIMES_FIELD_WIDTH = 50;
	
	@Unique
	private static final int MODE_CLICK_SLOT = 0;
	
	@Unique
	private static final int MODE_BUTTON_CLICK = 1;
	
	@Unique
	private int overlayXPos;
	
	@Unique
	private int overlayYPos;
	
	@Unique
	private int overlayBottomY;
	
	@Unique
	private boolean overlayDragging;
	
	@Unique
	private int dragOffsetX;
	
	@Unique
	private int dragOffsetY;
	
	private UiUtilsAbstractContainerScreenMixin(Component title)
	{
		super(title);
	}
	
	@Inject(at = @At("TAIL"), method = "init()V")
	private void onInit(CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(!UiUtilsState.isUiEnabled())
			return;
			
		// Don't carry the fabricate overlay over to the player inventory.
		// It can momentarily leak controls in the top-left during init/layout.
		if((Object)this instanceof InventoryScreen)
			UiUtilsState.fabricateOverlayOpen = false;
		
		Minecraft mc = Minecraft.getInstance();
		int spacing = 4;
		int buttonHeight = 20;
		int buttonCount = UiUtils.getUiWidgetRows();
		int chatHeight = 20;
		int blockHeight = buttonCount * buttonHeight
			+ (buttonCount - 1) * spacing + spacing + chatHeight;
		int startY = Math.max(5, (this.height - blockHeight) / 2);
		int baseX = 8;
		int nextY = UiUtils.addUiWidgets(mc, baseX, startY, spacing,
			this::addRenderableWidget);
		uiUtilsChatField =
			UiUtils.createChatField(mc, this.font, baseX, nextY + spacing);
		addRenderableWidget(uiUtilsChatField);
		
		initFabricateOverlay();
	}
	
	@Inject(at = @At("TAIL"),
		method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
	private void onRender(GuiGraphics graphics, int mouseX, int mouseY,
		float partialTicks, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(!UiUtilsState.isUiEnabled())
			return;
		
		AbstractContainerMenu menu =
			((AbstractContainerScreen<?>)(Object)this).getMenu();
		UiUtils.renderSyncInfo(Minecraft.getInstance(), graphics, menu);
		
		// Always update widget visibility to reflect current open/closed state
		updateOverlayVisibility();
		
		if(UiUtilsState.fabricateOverlayOpen)
		{
			layoutFabricateOverlay();
			updateOverlaySyncInfo(menu);
			drawFabricateLabels(graphics);
			// If dragging, continuously update position with current mouse
			if(overlayDragging)
			{
				int newX = Mth.clamp(mouseX - dragOffsetX, 0,
					this.width - OVERLAY_WIDTH);
				int newY = Mth.clamp(mouseY - dragOffsetY, 0,
					Math.max(0, this.height - 40));
				UiUtilsState.fabricateOverlayX = newX;
				UiUtilsState.fabricateOverlayY = newY;
			}
		}
	}
	
	@Inject(at = @At("HEAD"),
		method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
	private void wurst$renderFabricateOverlayBackground(GuiGraphics graphics,
		int mouseX, int mouseY, float partialTicks, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(!UiUtilsState.isUiEnabled())
			return;
		if(!UiUtilsState.fabricateOverlayOpen)
			return;
		layoutFabricateOverlay();
		UiUtilsHack hack = WurstClient.INSTANCE.getHax().uiUtilsHack;
		int alpha = hack != null ? hack.getFabricateOverlayBgAlpha() : 120;
		if(alpha <= 0)
			return;
		int x1 = Math.max(0, overlayXPos - 6);
		int y1 = Math.max(0, overlayYPos - 6);
		int x2 = Math.min(this.width, overlayXPos + OVERLAY_WIDTH + 6);
		int y2 = Math.min(this.height, overlayBottomY + 6);
		int color = (alpha << 24);
		graphics.fill(x1, y1, x2, y2, color);
	}
	
	@Inject(at = @At("HEAD"),
		method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
		cancellable = true)
	private void onKeyPressed(KeyEvent keyEvent,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(!UiUtilsState.isUiEnabled())
			return;
		
		if(uiUtilsChatField == null || !uiUtilsChatField.isFocused())
			return;
		
		if(uiUtilsChatField.keyPressed(keyEvent))
		{
			cir.setReturnValue(true);
			return;
		}
		
		if(keyEvent.isEscape())
		{
			uiUtilsChatField.setFocused(false);
			cir.setReturnValue(true);
			return;
		}
		
		cir.setReturnValue(true);
	}
	
	@Unique
	private void initFabricateOverlay()
	{
		if(fabricateOverlayInitialized)
			return;
		
		overlayClickSlotModeButton = Button
			.builder(Component.literal("Click Slot"),
				b -> switchFabricateMode(MODE_CLICK_SLOT))
			.bounds(0, 0, MODE_BUTTON_WIDTH, 20).build();
		addRenderableWidget(overlayClickSlotModeButton);
		
		overlayButtonClickModeButton = Button
			.builder(Component.literal("Button Click"),
				b -> switchFabricateMode(MODE_BUTTON_CLICK))
			.bounds(0, 0, MODE_BUTTON_WIDTH, 20).build();
		addRenderableWidget(overlayButtonClickModeButton);
		
		overlayClickSyncIdField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
			Component.literal("Sync Id"));
		addRenderableWidget(overlayClickSyncIdField);
		
		overlayClickRevisionField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
			Component.literal("Revision"));
		addRenderableWidget(overlayClickRevisionField);
		
		overlayClickSlotField =
			new EditBox(font, 0, 0, FIELD_WIDTH, 20, Component.literal("Slot"));
		overlayClickSlotField.setValue("0");
		addRenderableWidget(overlayClickSlotField);
		
		overlayClickButtonField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
			Component.literal("Button"));
		overlayClickButtonField.setValue("0");
		addRenderableWidget(overlayClickButtonField);
		
		overlayClickActionButton =
			CycleButton
				.<ClickType> builder(action -> Component.literal(action.name()),
					() -> ClickType.PICKUP)
				.withValues(ClickType.values()).create(0, 0, FIELD_WIDTH, 20,
					Component.literal("Action"), (button, value) -> {});
		addRenderableWidget(overlayClickActionButton);
		
		overlayClickDelayToggle =
			CycleButton.onOffBuilder(false).create(0, 0, DELAY_BUTTON_WIDTH, 20,
				Component.literal("Delay"), (button, value) -> {});
		addRenderableWidget(overlayClickDelayToggle);
		
		overlayClickTimesField = new EditBox(font, 0, 0, TIMES_FIELD_WIDTH, 20,
			Component.literal("Times to send"));
		overlayClickTimesField.setValue("1");
		addRenderableWidget(overlayClickTimesField);
		
		overlayClickSendButton =
			Button.builder(Component.literal("Send"), b -> sendClickSlot())
				.bounds(0, 0, 90, 20).build();
		addRenderableWidget(overlayClickSendButton);
		
		overlayButtonSyncIdField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
			Component.literal("Sync Id"));
		addRenderableWidget(overlayButtonSyncIdField);
		
		overlayButtonIdField = new EditBox(font, 0, 0, FIELD_WIDTH, 20,
			Component.literal("Button Id"));
		overlayButtonIdField.setValue("0");
		addRenderableWidget(overlayButtonIdField);
		
		overlayButtonDelayToggle =
			CycleButton.onOffBuilder(false).create(0, 0, DELAY_BUTTON_WIDTH, 20,
				Component.literal("Delay"), (button, value) -> {});
		addRenderableWidget(overlayButtonDelayToggle);
		
		overlayButtonTimesField = new EditBox(font, 0, 0, TIMES_FIELD_WIDTH, 20,
			Component.literal("Times to send"));
		overlayButtonTimesField.setValue("1");
		addRenderableWidget(overlayButtonTimesField);
		
		overlayButtonSendButton =
			Button.builder(Component.literal("Send"), b -> sendButtonClick())
				.bounds(0, 0, 90, 20).build();
		addRenderableWidget(overlayButtonSendButton);
		
		fabricateOverlayInitialized = true;
		switchFabricateMode(MODE_CLICK_SLOT);
		updateOverlayVisibility();
	}
	
	@Unique
	private void switchFabricateMode(int mode)
	{
		fabricateMode = mode;
		overlayClickSlotModeButton.setMessage(Component.literal(
			"Click Slot" + (fabricateMode == MODE_CLICK_SLOT ? " ✓" : "")));
		overlayButtonClickModeButton.setMessage(Component.literal(
			"Button Click" + (fabricateMode == MODE_BUTTON_CLICK ? " ✓" : "")));
		
		boolean showClick = fabricateMode == MODE_CLICK_SLOT;
		setWidgetVisibleAndActive(overlayClickSyncIdField, showClick);
		setWidgetVisibleAndActive(overlayClickRevisionField, showClick);
		setWidgetVisibleAndActive(overlayClickSlotField, showClick);
		setWidgetVisibleAndActive(overlayClickButtonField, showClick);
		setWidgetVisibleAndActive(overlayClickActionButton, showClick);
		setWidgetVisibleAndActive(overlayClickDelayToggle, showClick);
		setWidgetVisibleAndActive(overlayClickTimesField, showClick);
		setWidgetVisibleAndActive(overlayClickSendButton, showClick);
		
		boolean showButton = fabricateMode == MODE_BUTTON_CLICK;
		setWidgetVisibleAndActive(overlayButtonSyncIdField, showButton);
		setWidgetVisibleAndActive(overlayButtonIdField, showButton);
		setWidgetVisibleAndActive(overlayButtonDelayToggle, showButton);
		setWidgetVisibleAndActive(overlayButtonTimesField, showButton);
		setWidgetVisibleAndActive(overlayButtonSendButton, showButton);
	}
	
	@Unique
	private void updateOverlayVisibility()
	{
		if(!fabricateOverlayInitialized)
			return;
		
		boolean visible = UiUtilsState.fabricateOverlayOpen;
		setWidgetVisibleAndActive(overlayClickSlotModeButton, visible);
		setWidgetVisibleAndActive(overlayButtonClickModeButton, visible);
		if(!visible)
		{
			// Force-hide and disable every overlay widget to prevent controls
			// from leaking at (0,0) on inventory/recipe-book init cycles.
			hideAndParkOverlayWidgets();
			return;
		}
		
		switchFabricateMode(fabricateMode);
	}
	
	@Unique
	private void setWidgetVisibleAndActive(AbstractWidget widget,
		boolean visible)
	{
		if(widget == null)
			return;
		widget.visible = visible;
		widget.active = visible;
	}
	
	@Unique
	private void hideAndParkOverlayWidgets()
	{
		setWidgetVisibleAndActive(overlayClickSyncIdField, false);
		setWidgetVisibleAndActive(overlayClickRevisionField, false);
		setWidgetVisibleAndActive(overlayClickSlotField, false);
		setWidgetVisibleAndActive(overlayClickButtonField, false);
		setWidgetVisibleAndActive(overlayClickActionButton, false);
		setWidgetVisibleAndActive(overlayClickDelayToggle, false);
		setWidgetVisibleAndActive(overlayClickTimesField, false);
		setWidgetVisibleAndActive(overlayClickSendButton, false);
		setWidgetVisibleAndActive(overlayButtonSyncIdField, false);
		setWidgetVisibleAndActive(overlayButtonIdField, false);
		setWidgetVisibleAndActive(overlayButtonDelayToggle, false);
		setWidgetVisibleAndActive(overlayButtonTimesField, false);
		setWidgetVisibleAndActive(overlayButtonSendButton, false);
		
		final int offscreen = -2000;
		parkWidget(overlayClickSlotModeButton, offscreen);
		parkWidget(overlayButtonClickModeButton, offscreen);
		parkWidget(overlayClickSyncIdField, offscreen);
		parkWidget(overlayClickRevisionField, offscreen);
		parkWidget(overlayClickSlotField, offscreen);
		parkWidget(overlayClickButtonField, offscreen);
		parkWidget(overlayClickActionButton, offscreen);
		parkWidget(overlayClickDelayToggle, offscreen);
		parkWidget(overlayClickTimesField, offscreen);
		parkWidget(overlayClickSendButton, offscreen);
		parkWidget(overlayButtonSyncIdField, offscreen);
		parkWidget(overlayButtonIdField, offscreen);
		parkWidget(overlayButtonDelayToggle, offscreen);
		parkWidget(overlayButtonTimesField, offscreen);
		parkWidget(overlayButtonSendButton, offscreen);
	}
	
	@Unique
	private void parkWidget(AbstractWidget widget, int offscreen)
	{
		if(widget == null)
			return;
		widget.setX(offscreen);
		widget.setY(offscreen);
	}
	
	@Unique
	private void layoutFabricateOverlay()
	{
		int overlayX = this.leftPos + this.imageWidth + 8;
		int overlayY = this.topPos;
		// Use remembered position if present
		if(UiUtilsState.fabricateOverlayX >= 0)
			overlayX = Mth.clamp(UiUtilsState.fabricateOverlayX, 0,
				this.width - OVERLAY_WIDTH);
		if(UiUtilsState.fabricateOverlayY >= 0)
			overlayY = Mth.clamp(UiUtilsState.fabricateOverlayY, 0,
				Math.max(0, this.height - 40));
		// Clamp to screen if using defaults
		if(overlayX + OVERLAY_WIDTH > this.width - 8)
			overlayX = this.width - OVERLAY_WIDTH - 8;
		if(overlayX < 8)
			overlayX = 8;
		int modeGroupWidth = MODE_BUTTON_WIDTH * 2 + MODE_BUTTON_GAP;
		int modeStartX = overlayX + (OVERLAY_WIDTH - modeGroupWidth) / 2;
		int modeY = overlayY + 12;
		
		overlayClickSlotModeButton.setX(modeStartX);
		overlayClickSlotModeButton.setY(modeY);
		overlayButtonClickModeButton
			.setX(modeStartX + MODE_BUTTON_WIDTH + MODE_BUTTON_GAP);
		overlayButtonClickModeButton.setY(modeY);
		
		int inputX = overlayX + (OVERLAY_WIDTH - FIELD_WIDTH) / 2;
		int y = modeY + 32 + CONTENT_TOP_OFFSET;
		overlayClickSyncIdField.setX(inputX);
		overlayClickSyncIdField.setY(y);
		y += ROW_SPACING;
		overlayClickRevisionField.setX(inputX);
		overlayClickRevisionField.setY(y);
		y += ROW_SPACING;
		overlayClickSlotField.setX(inputX);
		overlayClickSlotField.setY(y);
		y += ROW_SPACING;
		overlayClickButtonField.setX(inputX);
		overlayClickButtonField.setY(y);
		y += ROW_SPACING;
		overlayClickActionButton.setX(inputX);
		overlayClickActionButton.setY(y);
		y += ROW_SPACING;
		int delayTotal = DELAY_BUTTON_WIDTH + 8 + TIMES_FIELD_WIDTH;
		int delayX = overlayX + (OVERLAY_WIDTH - delayTotal) / 2;
		overlayClickDelayToggle.setX(delayX);
		overlayClickDelayToggle.setY(y);
		overlayClickTimesField.setX(delayX + DELAY_BUTTON_WIDTH + 8);
		overlayClickTimesField.setY(y);
		y += ROW_SPACING;
		overlayClickSendButton.setX(overlayX + (OVERLAY_WIDTH - 90) / 2);
		overlayClickSendButton.setY(y);
		
		int buttonY = modeY + 32 + CONTENT_TOP_OFFSET;
		overlayButtonSyncIdField.setX(inputX);
		overlayButtonSyncIdField.setY(buttonY);
		buttonY += ROW_SPACING;
		overlayButtonIdField.setX(inputX);
		overlayButtonIdField.setY(buttonY);
		buttonY += ROW_SPACING;
		overlayButtonDelayToggle.setX(delayX);
		overlayButtonDelayToggle.setY(buttonY);
		overlayButtonTimesField.setX(delayX + DELAY_BUTTON_WIDTH + 8);
		overlayButtonTimesField.setY(buttonY);
		buttonY += ROW_SPACING;
		overlayButtonSendButton.setX(overlayX + (OVERLAY_WIDTH - 90) / 2);
		overlayButtonSendButton.setY(buttonY);
		// Record overlay rect for background rendering
		this.overlayXPos = overlayX;
		this.overlayYPos = overlayY;
		this.overlayBottomY = (fabricateMode == MODE_CLICK_SLOT) ? (y + 20 + 12)
			: (buttonY + 20 + 12);
	}
	
	@Unique
	private void drawFabricateLabels(GuiGraphics graphics)
	{
		if(fabricateMode == MODE_CLICK_SLOT)
		{
			drawLabel(graphics, "Sync Id", overlayClickSyncIdField);
			drawLabel(graphics, "Revision", overlayClickRevisionField);
			drawLabel(graphics, "Slot", overlayClickSlotField);
			drawLabel(graphics, "Button", overlayClickButtonField);
		}else
		{
			drawLabel(graphics, "Sync Id", overlayButtonSyncIdField);
			drawLabel(graphics, "Button Id", overlayButtonIdField);
		}
	}
	
	@Unique
	private void drawLabel(GuiGraphics graphics, String text, EditBox field)
	{
		graphics.drawString(font, text, field.getX(),
			field.getY() - LABEL_OFFSET, 0xFFAAAAAA, false);
	}
	
	@Inject(at = @At("HEAD"),
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
		cancellable = true)
	private void wurst$fabricateOverlayMouseClicked(
		net.minecraft.client.input.MouseButtonEvent context,
		boolean doubleClick, CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(!UiUtilsState.isUiEnabled() || !UiUtilsState.fabricateOverlayOpen)
			return;
		double mx = context.x();
		double my = context.y();
		int btn = context.button();
		if(btn != 0)
			return;
		// Drag bar: top area of overlay
		int dragBarTop = overlayYPos;
		int dragBarBottom = overlayYPos + OVERLAY_DRAG_BAR_HEIGHT;
		if(mx >= overlayXPos && mx <= overlayXPos + OVERLAY_WIDTH
			&& my >= dragBarTop && my <= dragBarBottom)
		{
			overlayDragging = true;
			dragOffsetX = (int)Math.round(mx - overlayXPos);
			dragOffsetY = (int)Math.round(my - overlayYPos);
			cir.setReturnValue(true);
			cir.cancel();
		}
	}
	
	@Inject(at = @At("HEAD"),
		method = "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
		cancellable = true)
	private void wurst$fabricateOverlayMouseReleased(
		net.minecraft.client.input.MouseButtonEvent context,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(!overlayDragging)
			return;
		overlayDragging = false;
		cir.setReturnValue(true);
		cir.cancel();
	}
	
	@Unique
	private void updateOverlaySyncInfo(AbstractContainerMenu menu)
	{
		if(menu == null)
			return;
		
		if(!overlayClickSyncIdField.isFocused())
			overlayClickSyncIdField.setValue(String.valueOf(menu.containerId));
		if(!overlayClickRevisionField.isFocused())
			overlayClickRevisionField
				.setValue(String.valueOf(menu.getStateId()));
		if(!overlayButtonSyncIdField.isFocused())
			overlayButtonSyncIdField.setValue(String.valueOf(menu.containerId));
	}
	
	@Unique
	private void sendClickSlot()
	{
		if(!UiUtils.isInteger(overlayClickSyncIdField.getValue())
			|| !UiUtils.isInteger(overlayClickRevisionField.getValue())
			|| !UiUtils.isInteger(overlayClickSlotField.getValue())
			|| !UiUtils.isInteger(overlayClickButtonField.getValue())
			|| !UiUtils.isInteger(overlayClickTimesField.getValue()))
		{
			return;
		}
		
		Minecraft mc = Minecraft.getInstance();
		int syncId = Integer.parseInt(overlayClickSyncIdField.getValue());
		short slot = Short.parseShort(overlayClickSlotField.getValue());
		byte button = Byte.parseByte(overlayClickButtonField.getValue());
		int timesToSend = Integer.parseInt(overlayClickTimesField.getValue());
		if(timesToSend < 1)
			return;
		
		ClickType action = overlayClickActionButton.getValue();
		if(action == null)
			return;
		
		if(mc.getConnection() == null || mc.player == null)
			return;
		
		AbstractContainerMenu menu = mc.player.containerMenu;
		if(menu == null)
			return;
		
		HashedPatchMap.HashGenerator hashGenerator =
			mc.getConnection().decoratedHashOpsGenenerator();
		// Snapshot before state
		java.util.List<net.minecraft.world.item.ItemStack> beforeStacks =
			new java.util.ArrayList<>(menu.slots.size());
		for(int i = 0; i < menu.slots.size(); i++)
			beforeStacks.add(menu.slots.get(i).getItem().copy());
		net.minecraft.world.item.ItemStack carriedBeforeStack =
			menu.getCarried().copy();
		
		// Locally simulate the click
		menu.clicked(slot, button, action, mc.player);
		
		// Use revision after local simulation
		int revision = menu.getStateId();
		
		// Build diff based on ItemStack (item + count)
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
				diffSlots.put(i, HashedStack.create(afterStack, hashGenerator));
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
			HashedStack.create(carriedBeforeStack, hashGenerator);
		
		ServerboundContainerClickPacket packet =
			new ServerboundContainerClickPacket(syncId, revision, slot, button,
				action, diffSlots, cursor);
		
		UiUtils.LOGGER.info(
			"Fabricate ClickSlot: syncId={}, revision={}, slot={}, button={}, action={}, times={}, diffSlots={}, carriedBefore={}, carriedAfter={}",
			syncId, revision, slot, button, action, timesToSend,
			diffSlots.size(), carriedBefore, cursor);
		UiUtils.chatIfEnabled("ClickSlot: slot=" + slot + ", action=" + action
			+ ", diff=" + diffSlots.size());
		UiUtils.LOGGER.info(
			"Fabricate ClickSlot: menu.containerId={}, syncIdMatch={}, diffDetail={}",
			menu.containerId, (menu.containerId == syncId), diffLog.toString());
		
		Runnable toRun = UiUtils.getFabricatePacketRunnable(mc,
			overlayClickDelayToggle.getValue(), packet);
		for(int i = 0; i < timesToSend; i++)
			toRun.run();
	}
	
	@Unique
	private void sendButtonClick()
	{
		if(!UiUtils.isInteger(overlayButtonSyncIdField.getValue())
			|| !UiUtils.isInteger(overlayButtonIdField.getValue())
			|| !UiUtils.isInteger(overlayButtonTimesField.getValue()))
		{
			return;
		}
		
		Minecraft mc = Minecraft.getInstance();
		int syncId = Integer.parseInt(overlayButtonSyncIdField.getValue());
		int buttonId = Integer.parseInt(overlayButtonIdField.getValue());
		int timesToSend = Integer.parseInt(overlayButtonTimesField.getValue());
		if(timesToSend < 1)
			return;
		
		ServerboundContainerButtonClickPacket packet =
			new ServerboundContainerButtonClickPacket(syncId, buttonId);
		
		UiUtils.LOGGER.info(
			"Fabricate ButtonClick: syncId={}, buttonId={}, times={}", syncId,
			buttonId, timesToSend);
		UiUtils.chatIfEnabled(
			"ButtonClick: buttonId=" + buttonId + ", times=" + timesToSend);
		
		Runnable toRun = UiUtils.getFabricatePacketRunnable(mc,
			overlayButtonDelayToggle.getValue(), packet);
		for(int i = 0; i < timesToSend; i++)
			toRun.run();
	}
	
	@Inject(at = @At("HEAD"), method = "removed()V", cancellable = true)
	private void onRemoved(CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(UiUtilsState.skipNextContainerRemoval)
		{
			UiUtilsState.skipNextContainerRemoval = false;
			ci.cancel();
			return;
		}
		
		fabricateOverlayInitialized = false;
	}
	
}
