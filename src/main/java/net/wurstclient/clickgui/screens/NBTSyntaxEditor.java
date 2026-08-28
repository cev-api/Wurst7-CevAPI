/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Editable SNBT field with syntax colors, indentation guides, and fold gutters.
 */
public final class NBTSyntaxEditor extends AbstractTextAreaWidget
{
	private final Font font;
	private final MultilineTextField textField;
	private final Set<Integer> collapsedLines = new HashSet<>();
	private String value = "";
	private String[] lines = {""};
	private boolean dragging;
	
	public NBTSyntaxEditor(Font font, int x, int y, int width, int height)
	{
		super(x, y, width, height, Component.literal("NBT"), defaultSettings(9),
			true, true);
		this.font = font;
		textField = new MultilineTextField(font, Math.max(1, width - 18));
		textField.setCharacterLimit(Integer.MAX_VALUE);
		textField.setValueListener(text -> {
			value = text;
			lines = text.split("\\n", -1);
		});
	}
	
	public void setValue(String value)
	{
		this.value = value == null ? "" : value;
		lines = this.value.split("\\n", -1);
		textField.setValue(this.value);
		setScrollAmount(0);
		refreshScrollAmount();
	}
	
	public String getValue()
	{
		return value;
	}
	
	@Override
	protected int contentHeight()
	{
		return Math.max(getHeight() - 8, visibleLineCount() * 9 + 8);
	}
	
	@Override
	protected int getInnerHeight()
	{
		return contentHeight();
	}
	
	private int visibleLineCount()
	{
		int depth = 0;
		int visible = 0;
		for(int line = 0; line < lines.length; line++)
		{
			if(depth == 0)
				visible++;
			String text = lineText(line);
			int balance = count(text, '{') + count(text, '[') - count(text, '}')
				- count(text, ']');
			if(depth > 0 || collapsedLines.contains(line))
				depth += balance;
			if(depth < 0)
				depth = 0;
		}
		return visible;
	}
	
	private String lineText(int line)
	{
		return line >= 0 && line < lines.length ? lines[line] : "";
	}
	
	private int lineStart(int line)
	{
		int position = 0;
		for(int i = 0; i < line; i++)
		{
			int newline = value.indexOf('\n', position);
			if(newline < 0)
				return value.length();
			position = newline + 1;
		}
		return position;
	}
	
	@Override
	protected void extractContents(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		int left = getInnerLeft();
		int top = getInnerTop() - (int)scrollAmount();
		context.enableScissor(getX(), getY(), getRight(), getBottom());
		int depth = 0;
		int visualLine = 0;
		for(int line = 0; line < lines.length; line++)
		{
			String text = lineText(line);
			boolean hidden = depth > 0;
			if(!hidden)
			{
				int y = top + visualLine++ * 9;
				if(y + 9 >= getY() && y <= getBottom())
					renderLine(context, text, left, y);
				if(isFoldable(text))
					context.text(font,
						collapsedLines.contains(line) ? "+" : "-", getX() + 3,
						y, 0xFF8A9BA8);
			}
			int balance = count(text, '{') + count(text, '[') - count(text, '}')
				- count(text, ']');
			if(depth > 0 || collapsedLines.contains(line))
				depth += balance;
			if(depth < 0)
				depth = 0;
		}
		if(isFocused())
			renderCursor(context, left, top, visualLine);
		context.disableScissor();
	}
	
	private void renderCursor(GuiGraphicsExtractor context, int left, int top,
		int visualLines)
	{
		int line = textField.getLineAtCursor();
		int visual = 0;
		int depth = 0;
		for(int i = 0; i < line; i++)
		{
			String text = lineText(i);
			if(depth == 0)
				visual++;
			int balance = count(text, '{') + count(text, '[') - count(text, '}')
				- count(text, ']');
			if(depth > 0 || collapsedLines.contains(i))
				depth += balance;
			if(depth < 0)
				depth = 0;
		}
		int start = lineStart(line);
		int offset = Math.max(0, textField.cursor() - start);
		String prefix = value.substring(start, Math.min(start + offset,
			Math.min(value.length(), start + lineText(line).length())));
		int y = top + visual * 9;
		context.fill(left + font.width(prefix), y,
			left + font.width(prefix) + 1, y + 9, 0xFFE8E8E8);
	}
	
	private void renderLine(GuiGraphicsExtractor context, String line, int x,
		int y)
	{
		int cursor = 0;
		while(cursor < line.length())
		{
			char c = line.charAt(cursor);
			if(c == '"')
			{
				int end = cursor + 1;
				while(end < line.length())
				{
					if(line.charAt(end) == '"' && line.charAt(end - 1) != '\\')
					{
						end++;
						break;
					}
					end++;
				}
				String token = line.substring(cursor, end);
				int color = end < line.length() && line.charAt(end) == ':'
					? 0xFF7DD3FC : 0xFFA7F3D0;
				context.text(font, token, x, y, color);
				x += font.width(token);
				cursor = end;
			}else if("{}[],:".indexOf(c) >= 0)
			{
				String token = String.valueOf(c);
				context.text(font, token, x, y, 0xFFFF8A26);
				x += font.width(token);
				cursor++;
			}else if(Character.isDigit(c) || c == '-')
			{
				int end = cursor + 1;
				while(end < line.length()
					&& !Character.isWhitespace(line.charAt(end))
					&& "{}[],:".indexOf(line.charAt(end)) < 0)
					end++;
				String token = line.substring(cursor, end);
				context.text(font, token, x, y, 0xFFF2C26B);
				x += font.width(token);
				cursor = end;
			}else
			{
				context.text(font, String.valueOf(c), x, y, 0xFFD7DEE8);
				x += font.width(String.valueOf(c));
				cursor++;
			}
		}
	}
	
	private boolean isFoldable(String text)
	{
		String trimmed = text.trim();
		return trimmed.endsWith("{") || trimmed.endsWith("[");
	}
	
	private int count(String text, char wanted)
	{
		int result = 0;
		for(int i = 0; i < text.length(); i++)
			if(text.charAt(i) == wanted)
				result++;
		return result;
	}
	
	@Override
	protected void updateWidgetNarration(NarrationElementOutput output)
	{
		defaultButtonNarrationText(output);
	}
	
	@Override
	public boolean keyPressed(KeyEvent event)
	{
		if(!isActive())
			return false;
		return textField.keyPressed(event);
	}
	
	@Override
	public boolean charTyped(CharacterEvent event)
	{
		if(!isActive() || !event.isAllowedChatCharacter())
			return false;
		textField.insertText(event.codepointAsString());
		return true;
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		if(!isMouseOver(event.x(), event.y()))
			return false;
		setFocused(true);
		dragging = true;
		if(event.x() < getInnerLeft())
		{
			int line = (int)((event.y() - getInnerTop() + scrollAmount()) / 9);
			if(line >= 0 && line < lines.length && isFoldable(lineText(line)))
			{
				if(!collapsedLines.add(line))
					collapsedLines.remove(line);
				refreshScrollAmount();
				return true;
			}
		}
		textField.seekCursorToPoint(event.x() - getInnerLeft(),
			event.y() - getInnerTop() + scrollAmount());
		return true;
	}
	
	@Override
	public void onRelease(MouseButtonEvent event)
	{
		dragging = false;
	}
	
	@Override
	protected void onDrag(MouseButtonEvent event, double x, double y)
	{
		if(dragging)
			textField.seekCursorToPoint(x - getInnerLeft(),
				y - getInnerTop() + scrollAmount());
	}
}
