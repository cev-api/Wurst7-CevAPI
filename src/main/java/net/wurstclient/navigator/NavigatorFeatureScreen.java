/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.navigator;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Mth;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.Window;
import net.wurstclient.command.Command;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.TooManyHaxHack;
import net.wurstclient.keybinds.Keybind;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.WurstColors;

public final class NavigatorFeatureScreen extends NavigatorScreen
{
	private Feature feature;
	private NavigatorMainScreen parent;
	private ButtonData activeButton;
	private Button primaryButton;
	private String text;
	private ArrayList<ButtonData> buttonDatas = new ArrayList<>();
	
	private Window window = new Window("");
	private int windowComponentY;
	private int keybindTextOffset;
	private String keybindText = "";
	private int cachedWindowContentHeight = -1;
	private boolean rebuildingSettings;
	
	public NavigatorFeatureScreen(Feature feature, NavigatorMainScreen parent)
	{
		this.feature = feature;
		this.parent = parent;
		hasBackground = false;
		
		window.setClampPosition(false);
		rebuildSettingComponents();
	}
	
	private void rebuildSettingComponents()
	{
		if(rebuildingSettings)
			return;
		
		rebuildingSettings = true;
		
		try
		{
			window.clearChildren();
			
			for(Setting setting : feature.getSettings().values())
			{
				setting.update();
				
				if(isShownViaAnySettingGroup(setting))
					continue;
				if(!setting.isVisibleInGui())
					continue;
				
				Component c;
				if(setting instanceof SettingGroup group)
					c = group.getComponent(false);
				else
					c = setting.getComponent();
				
				if(c != null)
					window.add(c);
			}
			
			window.setWidth(308);
			window.setFixedWidth(true);
			window.setPositionClampingEnabled(false);
			window.pack();
			cachedWindowContentHeight = window.getInnerHeight();
			
		}finally
		{
			rebuildingSettings = false;
		}
	}
	
	private boolean isShownViaAnySettingGroup(Setting setting)
	{
		for(Feature f : WurstClient.INSTANCE.getNavigator().getList())
		{
			for(Setting s : f.getSettings().values())
			{
				if(!(s instanceof SettingGroup group))
					continue;
				if(group.getChildren().contains(setting))
					return true;
			}
		}
		return false;
	}
	
	@Override
	protected void onResize()
	{
		buttonDatas.clear();
		windowComponentY = 0;
		keybindText = "";
		
		// primary button
		String primaryAction = feature.getPrimaryAction();
		boolean hasPrimaryAction = !primaryAction.isEmpty();
		if(hasPrimaryAction)
		{
			primaryButton = Button.builder(
				net.minecraft.network.chat.Component.literal(primaryAction),
				b -> {
					TooManyHaxHack tooManyHax =
						WurstClient.INSTANCE.getHax().tooManyHaxHack;
					if(tooManyHax.shouldBlockStarting(feature))
					{
						ChatUtils.error(
							feature.getName() + " is blocked by TooManyHax.");
						return;
					}
					
					feature.doPrimaryAction();
					
					primaryButton
						.setMessage(net.minecraft.network.chat.Component
							.literal(feature.getPrimaryAction()));
					WurstClient.INSTANCE.getNavigator()
						.addPreference(feature.getName());
				}).bounds(width / 2 - 151, height - 65, 302, 18).build();
			addRenderableWidget(primaryButton);
		}
		
		// type
		text = "Type: ";
		if(feature instanceof Hack)
			text += "Hack";
		else if(feature instanceof Command)
			text += "Command";
		else
			text += "Other Feature";
		
		// category
		String categoryName = feature.getCategoryName();
		if(categoryName != null && !categoryName.isBlank())
			text += ", Category: " + categoryName;
		
		// description
		String description = feature.getWrappedDescription(300);
		if(!description.isEmpty())
			text += "\n\nDescription:\n" + description;
		
		// area
		Rectangle area = new Rectangle(middleX - 154, 60, 308, height - 103);
		int contentBottom = hasPrimaryAction ? height - 67 : height - 43;
		int contentViewportHeight = contentBottom - area.y;
		
		// settings
		if(window.countChildren() > 0)
		{
			text += "\n\nSettings:";
			window.validate();
			
			int fontHeight = minecraft.font.lineHeight;
			if(fontHeight <= 0)
				fontHeight = 9;
			
			windowComponentY = getStringHeight(text) + 2;
			cachedWindowContentHeight = window.getInnerHeight();
		}else
			windowComponentY = getStringHeight(text) + 2;
		
		// keybinds
		Set<PossibleKeybind> possibleKeybinds = feature.getPossibleKeybinds();
		if(!possibleKeybinds.isEmpty())
		{
			StringBuilder kbText = new StringBuilder("Keybinds:");
			
			// keybind list
			HashMap<String, String> possibleKeybindsMap = new HashMap<>();
			for(PossibleKeybind possibleKeybind : possibleKeybinds)
				possibleKeybindsMap.put(possibleKeybind.getCommand(),
					possibleKeybind.getDescription());
			TreeMap<String, PossibleKeybind> existingKeybinds = new TreeMap<>();
			boolean noKeybindsSet = true;
			for(Keybind keybind : WurstClient.INSTANCE.getKeybinds()
				.getAllKeybinds())
			{
				String commands = keybind.getCommands();
				commands = commands.replace(";", "\u00a7")
					.replace("\u00a7\u00a7", ";");
				for(String command : commands.split("\u00a7"))
				{
					command = command.trim();
					String keybindDescription =
						possibleKeybindsMap.get(command);
					
					if(keybindDescription != null)
					{
						if(noKeybindsSet)
							noKeybindsSet = false;
						kbText.append("\n")
							.append(
								keybind.getKey().replace("key.keyboard.", ""))
							.append(": ").append(keybindDescription);
						existingKeybinds.put(keybind.getKey(),
							new PossibleKeybind(command, keybindDescription));
						
					}else if(feature instanceof Hack
						&& command.equalsIgnoreCase(feature.getName()))
					{
						if(noKeybindsSet)
							noKeybindsSet = false;
						kbText.append("\n")
							.append(
								keybind.getKey().replace("key.keyboard.", ""))
							.append(": ").append("Toggle " + feature.getName());
						existingKeybinds.put(keybind.getKey(),
							new PossibleKeybind(command,
								"Toggle " + feature.getName()));
					}
				}
			}
			if(noKeybindsSet)
				kbText.append("\nNone");
			
			keybindText = kbText.toString();
			
			ButtonData addKeybindButton = new ButtonData(
				area.x + area.width - 16, 0, 12, 8, "+", 0x00ff00)
			{
				@Override
				public void press()
				{
					WurstClient.MC.setScreen(new NavigatorNewKeybindScreen(
						possibleKeybinds, NavigatorFeatureScreen.this));
				}
			};
			buttonDatas.add(addKeybindButton);
			
			if(!noKeybindsSet)
			{
				buttonDatas.add(new ButtonData(area.x + area.width - 16, 0, 12,
					8, "-", 0xff0000)
				{
					@Override
					public void press()
					{
						minecraft.setScreen(new NavigatorRemoveKeybindScreen(
							existingKeybinds, NavigatorFeatureScreen.this));
					}
				});
			}
		}
		
		int keybindHeight =
			keybindText.isEmpty() ? 0 : getStringHeight(keybindText) + 8;
		if(window.countChildren() > 0)
		{
			int availableWindowHeight =
				contentViewportHeight - windowComponentY - keybindHeight - 6;
			window.setMaxHeight(Math.max(26, availableWindowHeight));
			window.validate();
			
			int visibleWindowBottom =
				windowComponentY + window.getHeight() - 13;
			int textHeight = getStringHeight(text);
			int fontHeight = minecraft.font.lineHeight;
			if(fontHeight <= 0)
				fontHeight = 9;
			
			while(textHeight < visibleWindowBottom)
			{
				text += "\n";
				textHeight += fontHeight;
			}
			
			keybindTextOffset = windowComponentY + window.getHeight() - 7;
			cachedWindowContentHeight = window.getInnerHeight();
			
		}else
			keybindTextOffset = windowComponentY + 6;
		
		if(!buttonDatas.isEmpty())
		{
			int buttonX = area.x + area.width - 16;
			int buttonY = area.y + keybindTextOffset - 7;
			buttonDatas.get(0).x = buttonX;
			buttonDatas.get(0).y = buttonY;
			if(buttonDatas.size() > 1)
			{
				buttonDatas.get(1).x = buttonX;
				buttonDatas.get(1).y = buttonY;
				buttonDatas.get(0).x -= 16;
			}
		}
		
		int visibleWindowBottom = window.countChildren() > 0
			? windowComponentY + window.getHeight() - 13 : windowComponentY;
		setContentHeight(Math.max(getStringHeight(text), visibleWindowBottom)
			+ keybindHeight);
	}
	
	@Override
	protected void onKeyPress(KeyEvent context)
	{
		int keyCode = context.key();
		
		if(keyCode == GLFW.GLFW_KEY_ESCAPE
			|| keyCode == GLFW.GLFW_KEY_BACKSPACE)
			goBack();
	}
	
	@Override
	protected void onMouseClick(MouseButtonEvent context)
	{
		double x = context.x();
		double y = context.y();
		int button = context.button();
		
		// popups
		if(WurstClient.INSTANCE.getGui().handlePopupMouseClick(x, y, button))
			return;
		
		// back button
		if(button == GLFW.GLFW_MOUSE_BUTTON_4)
		{
			goBack();
			return;
		}
		
		boolean noButtons = Screens.getButtons(this).isEmpty();
		Rectangle area = new Rectangle(width / 2 - 154, 60, 308,
			height - 60 - (noButtons ? 43 : 67));
		if(!area.contains(x, y))
			return;
		
		// buttons
		if(activeButton != null)
		{
			minecraft.getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
			activeButton.press();
			WurstClient.INSTANCE.getNavigator()
				.addPreference(feature.getName());
			return;
		}
		
		if(handleWindowScrollbarClick(x, y, button))
			return;
		
		// component settings
		WurstClient.INSTANCE.getGui().handleNavigatorMouseClick(
			x - middleX + 154,
			y - 60 - scroll - windowComponentY - window.getScrollOffset(),
			button, window, context);
	}
	
	private void goBack()
	{
		parent.setExpanding(false);
		minecraft.setScreen(parent);
	}
	
	@Override
	protected void onMouseDrag(double mouseX, double mouseY, int button,
		double double_3, double double_4)
	{
		if(button == GLFW.GLFW_MOUSE_BUTTON_LEFT
			&& window.isDraggingScrollbar())
			window.dragScrollbarTo((int)mouseY);
	}
	
	@Override
	protected void onMouseRelease(double x, double y, int button)
	{
		window.stopDraggingScrollbar();
		WurstClient.INSTANCE.getGui().handleMouseRelease(x, y, button);
	}
	
	@Override
	protected boolean onMouseScroll(double mouseX, double mouseY,
		double verticalAmount)
	{
		if(verticalAmount == 0)
			return false;
		if(!isInsideVisibleWindow(mouseX, mouseY))
			return false;
		
		if(WurstClient.INSTANCE.getGui()
			.handleNavigatorComponentMouseScroll(mouseX - middleX + 154, mouseY
				- 60 - scroll - windowComponentY - window.getScrollOffset(),
				verticalAmount, window))
			return true;
		if(!window.isScrollingEnabled())
			return false;
		
		int scrollAmount = (int)verticalAmount * 4;
		if(scrollAmount == 0)
			return false;
		
		int scrollOffset = window.getScrollOffset() + scrollAmount;
		scrollOffset = Math.min(scrollOffset, 0);
		scrollOffset = Math.max(scrollOffset,
			-window.getInnerHeight() + window.getHeight() - 13);
		window.setScrollOffset(scrollOffset);
		return true;
	}
	
	@Override
	protected void onUpdate()
	{
		if(primaryButton != null)
			primaryButton.setMessage(net.minecraft.network.chat.Component
				.literal(feature.getPrimaryAction()));
	}
	
	@Override
	protected void onRender(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		Matrix3x2fStack matrixStack = context.pose();
		ClickGui gui = WurstClient.INSTANCE.getGui();
		int txtColor = gui.getTxtColor();
		
		// title bar
		context.drawCenteredString(minecraft.font, feature.getName(), middleX,
			32, txtColor);
		
		// background
		int bgx1 = middleX - 154;
		window.setX(bgx1);
		int bgx2 = middleX + 154;
		int bgy1 = 60;
		int bgy2 = height - 43;
		boolean noButtons = Screens.getButtons(this).isEmpty();
		int bgy3 = bgy2 - (noButtons ? 0 : 24);
		int windowY1 = bgy1 + scroll + windowComponentY;
		int windowY2 = windowY1 + window.getInnerHeight();
		
		context.fill(bgx1, bgy1, bgx2, Mth.clamp(windowY1, bgy1, bgy3),
			getBackgroundColor());
		context.fill(bgx1, Mth.clamp(windowY2, bgy1, bgy3), bgx2, bgy2,
			getBackgroundColor());
		RenderUtils.drawBoxShadow2D(context, bgx1, bgy1, bgx2, bgy2);
		
		context.enableScissor(bgx1, bgy1, bgx2, bgy3);
		
		// settings
		gui.setTooltip("");
		window.validate();
		int innerHeight = window.getInnerHeight();
		int extraPadding = (primaryButton != null ? 32 : 0)
			+ buttonDatas.stream().mapToInt(b -> b.height + 6).sum();
		int keybindHeight =
			keybindText.isEmpty() ? 0 : getStringHeight(keybindText) + 8;
		if(innerHeight != cachedWindowContentHeight)
		{
			int desiredBottom = windowComponentY + window.getHeight() - 13;
			int textHeight = getStringHeight(text);
			int fontHeight = minecraft.font.lineHeight;
			if(fontHeight <= 0)
				fontHeight = 9;
			while(textHeight < desiredBottom)
			{
				text += "\n";
				textHeight += fontHeight;
			}
			
			keybindTextOffset = windowComponentY + window.getHeight() - 7;
			setContentHeight(Math.max(textHeight, desiredBottom) + extraPadding
				+ keybindHeight);
			cachedWindowContentHeight = innerHeight;
		}else
			setContentHeight(Math.max(getStringHeight(text),
				windowComponentY + window.getHeight() - 13) + extraPadding
				+ keybindHeight);
		
		window.setY(windowY1 - 13);
		matrixStack.pushMatrix();
		int x1 = 0;
		int y1 = -13;
		int x2 = x1 + window.getWidth();
		int y2 = y1 + window.getHeight();
		int y3 = y1 + 13;
		int x3 = x1 + 2;
		int x4 = window.isScrollingEnabled() ? x2 - 3 : x2;
		int x5 = x4 - 2;
		int y4 = windowY1 + window.getScrollOffset();
		
		if(window.isScrollingEnabled())
		{
			int xs1 = x2 - 3;
			int xs2 = xs1 + 2;
			int xs3 = x2;
			
			double outerHeight = window.getHeight() - 13;
			double maxScrollbarHeight = outerHeight - 2;
			double scrollbarY =
				outerHeight * (-window.getScrollOffset() / (double)innerHeight)
					+ 1;
			double scrollbarHeight =
				maxScrollbarHeight * outerHeight / (double)innerHeight;
			
			int ys1 = windowY1;
			int ys2 = ys1 + (int)outerHeight;
			int ys3 = ys1 + (int)scrollbarY;
			int ys4 = ys3 + (int)scrollbarHeight;
			
			context.fill(bgx1 + xs2, ys1, bgx1 + xs3, ys2,
				getBackgroundColor());
			context.fill(bgx1 + xs1, ys1, bgx1 + xs2, ys3,
				getBackgroundColor());
			context.fill(bgx1 + xs1, ys4, bgx1 + xs2, ys2,
				getBackgroundColor());
			context.fill(bgx1 + xs1, ys3, bgx1 + xs2, ys4,
				RenderUtils.toIntColor(gui.getAcColor(), 0.75F));
		}
		
		context.enableScissor(bgx1, windowY1, bgx2,
			windowY1 + window.getHeight() - 13);
		matrixStack.translate(bgx1, y4);
		
		{
			// window background
			// left & right
			int bgColor = getBackgroundColor();
			context.fill(x1, y3, x3, y2, bgColor);
			context.fill(x5, y3, x2, y2, bgColor);
			
			// window background
			// between children
			int xc1 = 2;
			int xc2 = x5 - x1;
			for(int i = 0; i < window.countChildren(); i++)
			{
				int yc1 = window.getChild(i).getY();
				int yc2 = yc1 - 2;
				if(yc1 < bgy1 - windowY1)
					continue;
				if(yc2 > bgy3 - windowY1)
					break;
				
				context.fill(xc1, yc1, xc2, yc2, bgColor);
			}
			
			// window background
			// bottom
			int yc1;
			if(window.countChildren() == 0)
				yc1 = 0;
			else
			{
				Component lastChild =
					window.getChild(window.countChildren() - 1);
				yc1 = lastChild.getY() + lastChild.getHeight();
			}
			int yc2 = yc1 + 2;
			context.fill(xc1, yc1, xc2, yc2, bgColor);
		}
		
		for(int i = 0; i < window.countChildren(); i++)
		{
			Component child = window.getChild(i);
			if(child.getY() + child.getHeight() < bgy1 - y4)
				continue;
			if(child.getY() > window.getHeight() - 13
				- window.getScrollOffset())
				break;
			
			child.render(context, mouseX - bgx1, mouseY - y4,
				partialTicks);
		}
		matrixStack.popMatrix();
		context.disableScissor();
		context.enableScissor(bgx1, bgy1, bgx2, bgy3);
		
		// buttons
		activeButton = null;
		for(ButtonData buttonData : buttonDatas)
		{
			// positions
			int bx1 = buttonData.x;
			int bx2 = bx1 + buttonData.width;
			int by1 = buttonData.y + scroll;
			int by2 = by1 + buttonData.height;
			
			// color
			float alpha;
			if(buttonData.isLocked())
				alpha = 0.25F;
			else if(mouseX >= bx1 && mouseX <= bx2 && mouseY >= by1
				&& mouseY <= by2)
			{
				alpha = 0.75F;
				activeButton = buttonData;
			}else
				alpha = 0.375F;
			float[] rgb = buttonData.color.getColorComponents(null);
			
			// button
			drawBox(context, bx1, by1, bx2, by2,
				RenderUtils.toIntColor(rgb, alpha));
			
			// text
			context.guiRenderState.up();
			context.drawCenteredString(minecraft.font, buttonData.buttonText,
				(bx1 + bx2) / 2, by1 + (buttonData.height - 10) / 2 + 1,
				buttonData.isLocked() ? WurstColors.VERY_LIGHT_GRAY
					: buttonData.textColor);
		}
		
		// text
		int textY = bgy1 + scroll + 2;
		context.guiRenderState.up();
		for(String line : text.split("\n"))
		{
			context.drawString(minecraft.font, line, bgx1 + 2, textY, txtColor,
				false);
			textY += minecraft.font.lineHeight;
		}
		if(!keybindText.isEmpty())
		{
			int keybindY = bgy1 + scroll + keybindTextOffset;
			for(String line : keybindText.split("\n"))
			{
				context.drawString(minecraft.font, line, bgx1 + 2, keybindY,
					txtColor, false);
				keybindY += minecraft.font.lineHeight;
			}
		}
		
		context.disableScissor();
		
		// buttons below scissor box
		for(AbstractWidget button : Screens.getButtons(this))
		{
			// positions
			int bx1 = button.getX();
			int bx2 = bx1 + button.getWidth();
			int by1 = button.getY();
			int by2 = by1 + 18;
			
			// color
			boolean hovering = mouseX >= bx1 && mouseX <= bx2 && mouseY >= by1
				&& mouseY <= by2;
			int buttonColor;
			if(feature.isEnabled() && button == primaryButton)
				buttonColor = hovering ? 0x4000FF00 : 0x4000E000;
			else
				buttonColor = hovering ? 0x40606060 : 0x40404040;
			
			// button
			drawBox(context, bx1, by1, bx2, by2, buttonColor);
			
			// text
			String buttonText = button.getMessage().getString();
			context.guiRenderState.up();
			context.drawString(minecraft.font, buttonText,
				(bx1 + bx2 - minecraft.font.width(buttonText)) / 2, by1 + 5,
				txtColor, false);
		}
		
		// popups & tooltip
		gui.closePopupsOutsideArea(window, bgx1, bgy1, bgx2, bgy3);
		gui.renderPopups(context, mouseX, mouseY);
		gui.renderTooltip(context, mouseX, mouseY);
	}
	
	@Override
	public void onClose()
	{
		window.close();
		WurstClient.INSTANCE.getGui().handleMouseClick(new MouseButtonEvent(
			Double.MIN_VALUE, Double.MIN_VALUE, new MouseButtonInfo(0, 0)));
	}
	
	public Feature getFeature()
	{
		return feature;
	}
	
	public void refreshSettingsWindow()
	{
		rebuildSettingComponents();
		if(rebuildingSettings)
			return;
		
		for(AbstractWidget widget : List.copyOf(Screens.getWidgets(this)))
			removeWidget(widget);
		
		onResize();
	}
	
	public boolean isRebuildingSettings()
	{
		return rebuildingSettings;
	}
	
	public int getMiddleX()
	{
		return middleX;
	}
	
	public void addText(String text)
	{
		this.text += text;
	}
	
	public int getTextHeight()
	{
		return getStringHeight(text);
	}
	
	private boolean handleWindowScrollbarClick(double mouseX, double mouseY,
		int mouseButton)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT
			|| !window.isScrollingEnabled())
			return false;
		if(!isInsideVisibleWindow(mouseX, mouseY))
			return false;
		
		int relativeMouseX = (int)(mouseX - (middleX - 154));
		int relativeMouseY = (int)(mouseY - (60 + scroll + windowComponentY));
		int scrollbarX = window.getWidth() - 3;
		if(relativeMouseX < scrollbarX || relativeMouseX >= window.getWidth())
			return false;
		
		double outerHeight = window.getHeight() - 13;
		double innerHeight = window.getInnerHeight();
		double maxScrollbarHeight = outerHeight - 2;
		int scrollbarY =
			(int)(outerHeight * (-window.getScrollOffset() / innerHeight) + 1);
		int scrollbarHeight =
			(int)(maxScrollbarHeight * outerHeight / innerHeight);
		
		if(relativeMouseY < scrollbarY
			|| relativeMouseY >= scrollbarY + scrollbarHeight)
			return false;
		
		window.startDraggingScrollbar((int)mouseY);
		return true;
	}
	
	private boolean isInsideVisibleWindow(double mouseX, double mouseY)
	{
		if(window.countChildren() == 0)
			return false;
		
		int x1 = middleX - 154;
		int x2 = x1 + window.getWidth();
		int y1 = 60 + scroll + windowComponentY;
		int y2 = y1 + window.getHeight() - 13;
		return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
	}
	
	public abstract static class ButtonData extends Rectangle
	{
		public String buttonText;
		public Color color;
		public int textColor = CommonColors.WHITE;
		
		public ButtonData(int x, int y, int width, int height,
			String buttonText, int color)
		{
			super(x, y, width, height);
			this.buttonText = buttonText;
			this.color = new Color(color);
		}
		
		public abstract void press();
		
		public boolean isLocked()
		{
			return false;
		}
	}
}
