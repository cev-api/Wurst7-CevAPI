/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Editable SNBT field with syntax colors, line numbers, and folding. */
public final class NBTSyntaxEditor extends AbstractTextAreaWidget
{
	private static final int LINE_HEIGHT = 9;
	private static final int LINE_NUMBER_WIDTH = 44;
	private static final int CODE_LEFT_PADDING = 4;
	private final Font font;
	private final EditorTextField textField;
	private final Set<Integer> collapsedLines = new HashSet<>();
	private final List<DisplayRow> visibleRows = new ArrayList<>();
	private String value = "";
	private String[] lines = {""};
	
	public NBTSyntaxEditor(Font font, int x, int y, int width, int height)
	{
		super(x, y, width, height, Component.literal("NBT"),
			defaultSettings(LINE_HEIGHT), true, true);
		this.font = font;
		textField = new EditorTextField(font,
			Math.max(1, width - LINE_NUMBER_WIDTH - totalInnerPadding()));
		textField.setCharacterLimit(Integer.MAX_VALUE);
		textField.setValueListener(text -> {
			value = text;
			lines = text.split("\\n", -1);
			// Edits can move every fold boundary. Rebuild from the same wrapped
			// lines used by the text field for keyboard navigation and
			// selection.
			collapsedLines.clear();
			rebuildVisibleRows();
			refreshScrollAmount();
		});
		textField.setCursorListener(this::scrollToCursor);
		setValue("");
	}
	
	public void setValue(String value)
	{
		textField.setSelecting(false);
		textField.setValue(value == null ? "" : value);
		textField.seekCursor(Whence.ABSOLUTE, 0);
		setScrollAmount(0);
	}
	
	public String getValue()
	{
		return value;
	}
	
	@Override
	protected int getInnerHeight()
	{
		return visibleRows.size() * LINE_HEIGHT;
	}
	
	private void rebuildVisibleRows()
	{
		visibleRows.clear();
		boolean[] hidden = new boolean[lines.length];
		int[] starts = new int[lines.length];
		int depth = 0;
		for(int line = 0; line < lines.length; line++)
		{
			hidden[line] = depth > 0;
			if(line > 0)
				starts[line] = starts[line - 1] + lines[line - 1].length() + 1;
			if(depth > 0 || collapsedLines.contains(line))
				depth = Math.max(0, depth + bracketBalance(lines[line]));
		}
		
		int sourceLine = 0;
		for(int fieldLine = 0; fieldLine < textField
			.getLineCount(); fieldLine++)
		{
			int begin = textField.lineStart(fieldLine);
			while(sourceLine + 1 < starts.length
				&& begin >= starts[sourceLine + 1])
				sourceLine++;
			if(!hidden[sourceLine])
				visibleRows.add(new DisplayRow(fieldLine, sourceLine, begin,
					textField.lineEnd(fieldLine), begin == starts[sourceLine]));
		}
	}
	
	@Override
	protected void extractContents(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		int left = getCodeLeft();
		// The parent clips the viewport BEFORE translating by scrollAmount().
		// A second scissor here would move with the content, progressively cut
		// off the bottom of the viewport, and eventually hide all of the text.
		int top = getInnerTop();
		int fixedTop = getY() + (int)scrollAmount();
		int fixedBottom = getBottom() + (int)Math.ceil(scrollAmount());
		context.fill(getX(), fixedTop, left - CODE_LEFT_PADDING, fixedBottom,
			0xCC10151C);
		context.fill(left - CODE_LEFT_PADDING, fixedTop,
			left - CODE_LEFT_PADDING + 1, fixedBottom, 0xFF384552);
		
		int cursorRow = findCursorRow();
		int selectionStart = textField.selectionStart();
		int selectionEnd = textField.selectionEnd();
		int firstRow =
			Math.max(0, (int)((scrollAmount() - innerPadding()) / LINE_HEIGHT));
		for(int i = firstRow; i < visibleRows.size(); i++)
		{
			DisplayRow row = visibleRows.get(i);
			int y = top + i * LINE_HEIGHT;
			if(y - scrollAmount() >= getBottom())
				break;
			if(!withinContentAreaTopBottom(y, y + LINE_HEIGHT))
				continue;
			if(row.first())
			{
				String number = Integer.toString(row.sourceLine() + 1);
				context.text(font, number,
					left - CODE_LEFT_PADDING - 3 - font.width(number), y,
					0xFF82909D);
				if(isFoldable(lines[row.sourceLine()]))
					context.text(font,
						collapsedLines.contains(row.sourceLine()) ? "+" : "-",
						getX() + 3, y, 0xFF8A9BA8);
			}
			renderLine(context, value.substring(row.begin(), row.end()), left,
				y);
			if(isFocused() && i == cursorRow)
			{
				int cursorX = left + font
					.width(value.substring(row.begin(), textField.cursor()));
				context.fill(cursorX, y, cursorX + 1, y + LINE_HEIGHT,
					0xFFE8E8E8);
			}
			if(selectionStart < selectionEnd && selectionStart <= row.end()
				&& selectionEnd > row.begin())
			{
				int startX = left + font.width(value.substring(row.begin(),
					Math.max(selectionStart, row.begin())));
				int endX = selectionEnd > row.end()
					? getRight() - innerPadding() : left + font
						.width(value.substring(row.begin(), selectionEnd));
				context.textHighlight(startX, y, endX, y + LINE_HEIGHT, true);
			}
		}
	}
	
	private int getCodeLeft()
	{
		return getInnerLeft() + LINE_NUMBER_WIDTH;
	}
	
	private int findCursorRow()
	{
		int fieldLine = textField.getLineAtCursor();
		for(int i = 0; i < visibleRows.size(); i++)
			if(visibleRows.get(i).fieldLine() == fieldLine)
				return i;
		return -1;
	}
	
	private void scrollToCursor()
	{
		int row = findCursorRow();
		if(row < 0 && !collapsedLines.isEmpty())
		{
			// Keyboard navigation into a folded region must reveal the caret.
			collapsedLines.clear();
			rebuildVisibleRows();
			row = findCursorRow();
		}
		if(row < 0)
			return;
		double top = row * LINE_HEIGHT;
		int viewportHeight = getHeight() - totalInnerPadding();
		if(top < scrollAmount())
			setScrollAmount(top);
		else if(top + LINE_HEIGHT > scrollAmount() + viewportHeight)
			setScrollAmount(top + LINE_HEIGHT - viewportHeight);
		else
			refreshScrollAmount();
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
		return (trimmed.endsWith("{") || trimmed.endsWith("["))
			&& bracketBalance(text) > 0;
	}
	
	private int bracketBalance(String text)
	{
		int result = 0;
		char quote = 0;
		boolean escaped = false;
		for(int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if(quote != 0)
			{
				if(escaped)
					escaped = false;
				else if(c == '\\')
					escaped = true;
				else if(c == quote)
					quote = 0;
			}else if(c == '"' || c == '\'')
				quote = c;
			else if(c == '{' || c == '[')
				result++;
			else if(c == '}' || c == ']')
				result--;
		}
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
		if(!isActive() || !isFocused())
			return false;
		return textField.keyPressed(event);
	}
	
	@Override
	public boolean charTyped(CharacterEvent event)
	{
		if(!isActive() || !isFocused() || !event.isAllowedChatCharacter())
			return false;
		textField.insertText(event.codepointAsString());
		return true;
	}
	
	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick)
	{
		// Let the parent start scrollbar dragging and handle mouse release.
		if(isOverScrollbar(event.x(), event.y()))
			return;
		DisplayRow row = rowAtScreenY(event.y());
		if(row == null)
			return;
		if(event.x() < getInnerLeft() + 7 && row.first()
			&& isFoldable(lines[row.sourceLine()]))
		{
			if(!collapsedLines.add(row.sourceLine()))
				collapsedLines.remove(row.sourceLine());
			rebuildVisibleRows();
			refreshScrollAmount();
			return;
		}
		textField.setSelecting(event.hasShiftDown());
		seekCursorScreen(event.x(), event.y());
		if(doubleClick)
			textField.selectWordAtCursor();
	}
	
	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy)
	{
		textField.setSelecting(true);
		seekCursorScreen(event.x(), event.y());
		textField.setSelecting(event.hasShiftDown());
	}
	
	private DisplayRow rowAtScreenY(double y)
	{
		if(visibleRows.isEmpty())
			return null;
		int index =
			(int)Math.floor((y - getInnerTop() + scrollAmount()) / LINE_HEIGHT);
		return visibleRows.get(Math.clamp(index, 0, visibleRows.size() - 1));
	}
	
	private void seekCursorScreen(double x, double y)
	{
		DisplayRow row = rowAtScreenY(y);
		if(row != null)
			textField.seekCursorToPoint(Math.max(0, x - getCodeLeft()),
				row.fieldLine() * LINE_HEIGHT);
	}
	
	private record DisplayRow(int fieldLine, int sourceLine, int begin, int end,
		boolean first)
	{}
	
	// StringView is protected. Expose just the offsets needed to render and
	// hit-test the text field's own wrapped line layout.
	private static final class EditorTextField extends MultilineTextField
	{
		private EditorTextField(Font font, int width)
		{
			super(font, width);
		}
		
		private int lineStart(int line)
		{
			return getLineView(line).beginIndex();
		}
		
		private int lineEnd(int line)
		{
			return getLineView(line).endIndex();
		}
		
		private int selectionStart()
		{
			return getSelected().beginIndex();
		}
		
		private int selectionEnd()
		{
			return getSelected().endIndex();
		}
	}
}
