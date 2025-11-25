/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import java.util.Objects;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.KeyboardInput;
import net.wurstclient.clickgui.Window;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;

public final class TextFieldEditButton extends Component
	implements KeyboardInput
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = MC.font;
	private static final int TEXT_HEIGHT = 11;
	
	private final TextFieldSetting setting;
	private final EditBox inlineField;
	private boolean editing;
	private String originalValue = "";
	
	public TextFieldEditButton(TextFieldSetting setting)
	{
		this.setting = Objects.requireNonNull(setting);
		inlineField = new EditBox(TR, 0, 0, 0, TEXT_HEIGHT,
			net.minecraft.network.chat.Component.literal(""));
		inlineField.setBordered(false);
		inlineField.setMaxLength(Integer.MAX_VALUE);
		inlineField.setFilter(setting::isValidValue);
		inlineField.setValue(setting.getValue());
		inlineField.setEditable(false);
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton)
	{
		double localY = mouseY - getY();
		if(localY < 0 || localY >= getHeight())
			return;
		
		boolean overBox = localY >= TEXT_HEIGHT;
		
		switch(mouseButton)
		{
			case GLFW.GLFW_MOUSE_BUTTON_LEFT:
			if(overBox)
			{
				if(!editing)
					startEditing();
				inlineField.mouseClicked(context, false);
			}else if(editing)
				finishEditing(true);
			break;
			
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT:
			if(editing)
				finishEditing(false);
			setting.resetToDefault();
			inlineField.setValue(setting.getValue());
			break;
		}
	}
	
	private void startEditing()
	{
		editing = true;
		originalValue = setting.getValue();
		inlineField.setValue(originalValue);
		inlineField.setEditable(true);
		inlineField.setFocused(true);
		moveCaretToEnd();
		GUI.requestKeyboardInput(this);
	}
	
	private void moveCaretToEnd()
	{
		int end = inlineField.getValue().length();
		inlineField.setCursorPosition(end);
		inlineField.setHighlightPos(end);
	}
	
	private void finishEditing(boolean apply)
	{
		if(!editing)
			return;
		
		if(apply)
			setting.setValue(inlineField.getValue());
		else
			inlineField.setValue(originalValue);
		
		inlineField.setEditable(false);
		inlineField.setFocused(false);
		editing = false;
		GUI.releaseKeyboardInput(this);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		updateInlineFieldBounds();
		if(!editing)
			inlineField.setValue(setting.getValue());
		
		float[] bgColor = GUI.getBgColor();
		float opacity = GUI.getOpacity();
		
		int x1 = getX();
		int x2 = x1 + getWidth();
		int y1 = getY();
		int y2 = y1 + getHeight();
		int boxY1 = y1 + TEXT_HEIGHT;
		
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hText = hovering && mouseY < boxY1;
		boolean hBox = hovering && mouseY >= boxY1;
		
		if(hText)
			GUI.setTooltip(ChatUtils.wrapText(setting.getDescription(), 200));
		else if(hBox && !editing)
			GUI.setTooltip(ChatUtils.wrapText(setting.getValue(), 200));
		
		context.fill(x1, y1, x2, boxY1,
			RenderUtils.toIntColor(bgColor, opacity));
		context.fill(x1, boxY1, x2, y2,
			RenderUtils.toIntColor(bgColor, opacity * (hBox ? 1.5F : 1)));
		RenderUtils.drawBorder2D(context, x1, boxY1, x2, y2,
			RenderUtils.toIntColor(GUI.getAcColor(), 0.5F));
		
		int txtColor = GUI.getTxtColor();
		context.guiRenderState.up();
		context.drawString(TR, setting.getName(), x1, y1 + 2, txtColor, false);
		inlineField.setTextColor(txtColor);
		inlineField.setTextColorUneditable(txtColor);
		inlineField.render(context, toAbsoluteMouseX(mouseX),
			toAbsoluteMouseY(mouseY), partialTicks);
	}
	
	private void updateInlineFieldBounds()
	{
		inlineField.setX(getX() + 2);
		inlineField.setY(getY() + TEXT_HEIGHT + 2);
		inlineField.setWidth(Math.max(0, getWidth() - 4));
		inlineField.setHeight(Math.max(0, getHeight() - TEXT_HEIGHT - 4));
	}
	
	private int toAbsoluteMouseX(double mouseX)
	{
		Window parent = getParent();
		return parent == null ? (int)Math.round(mouseX)
			: (int)Math.round(mouseX + parent.getX());
	}
	
	private int toAbsoluteMouseY(double mouseY)
	{
		Window parent = getParent();
		if(parent == null)
			return (int)Math.round(mouseY);
		
		return (int)Math
			.round(mouseY + parent.getY() + 13 + parent.getScrollOffset());
	}
	
	@Override
	public boolean onKeyPressed(KeyEvent event)
	{
		if(!editing)
			return false;
		
		int keyCode = event.key();
		if(keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
		{
			finishEditing(true);
			return true;
		}
		
		if(keyCode == GLFW.GLFW_KEY_ESCAPE)
		{
			finishEditing(false);
			return true;
		}
		
		return inlineField.keyPressed(event);
	}
	
	@Override
	public boolean onCharTyped(CharacterEvent event)
	{
		return editing && inlineField.charTyped(event);
	}
	
	@Override
	public void onKeyboardFocusLost()
	{
		finishEditing(true);
	}
	
	@Override
	public int getDefaultWidth()
	{
		return TR.width(setting.getName()) + 4;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return TEXT_HEIGHT * 2;
	}
}
