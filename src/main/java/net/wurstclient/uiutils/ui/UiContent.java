/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.wurstclient.uiutils.UiUtilsSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Layout builder for {@link UiModernScreen}.
 * <p>
 * Everything is expressed in design units, which the owning screen scales as
 * one
 * canvas. Rows share the available width by weight and can be laid out in
 * several columns, which is what keeps a dense screen inside a normal window.
 */
public final class UiContent
{
	private static final int HEADER_BUTTON_WIDTH = 44;
	
	public enum Pin
	{
		CONTENT,
		HEADER,
		FOOTER
	}
	
	/** A placed widget in design units. */
	public record Item(AbstractWidget widget, int x, int y, int width,
		int height, Pin pin, boolean fill)
	{}
	
	/** A widget being offered to {@link #row}, before widths are resolved. */
	public static final class Slot
	{
		private final AbstractWidget widget;
		private final float weight;
		private final int fixedWidth;
		private final int minWidth;
		private final int height;
		private final boolean fill;
		
		private Slot(AbstractWidget widget, float weight, int fixedWidth,
			int minWidth, int height, boolean fill)
		{
			this.widget = widget;
			this.weight = weight;
			this.fixedWidth = fixedWidth;
			this.minWidth = minWidth;
			this.height = height;
			this.fill = fill;
		}
		
		public AbstractWidget widget()
		{
			return widget;
		}
	}
	
	public abstract static class Underlay
	{
		public abstract void draw(GuiGraphics graphics, Font font,
			int cardWidth);
	}
	
	private final UiModernScreen screen;
	private final int width;
	private final List<Item> items = new ArrayList<>();
	private final List<Underlay> underlays = new ArrayList<>();
	private final List<Slot> pendingFooter = new ArrayList<>();
	/** Per-column flow position when {@link #columns} is used. */
	private final List<Integer> columnY = new ArrayList<>();
	private int activeColumn;
	private int columnCount = 1;
	private boolean autoFlow;
	/**
	 * Bottom of content the screen says it will draw itself, in content units.
	 */
	private int reportedBottom;
	private int y;
	
	UiContent(UiModernScreen screen, int width)
	{
		this.screen = screen;
		this.width = width;
		this.y = 3;
		columnY.add(3);
	}
	
	public int width()
	{
		return width;
	}
	
	public int rowHeight()
	{
		return UiTheme.ROW_HEIGHT;
	}
	
	public int cursor()
	{
		return y;
	}
	
	/**
	 * Declares the bottom, in content units, of content the screen will draw
	 * itself
	 * rather than through a widget (text blocks, result lists, separators).
	 * Rows
	 * added with {@link #row} already contribute their own bounds; anything
	 * painted
	 * directly must call this so it counts towards the scrollable extent.
	 */
	public void reportBottom(int units)
	{
		reportedBottom = Math.max(reportedBottom, units);
	}
	
	/** Lowest reported manual bottom, in content units. */
	public int reportedBottom()
	{
		return reportedBottom;
	}
	
	public void space(int units)
	{
		setColumnY(Math.max(0, y + units));
	}
	
	public void gap(int units)
	{
		space(units);
	}
	
	// ------------------------------------------------------------------
	// Columns
	// ------------------------------------------------------------------
	
	/** Splits the content area into the given number of equal columns. */
	public void columns(int count)
	{
		columns(count, false);
	}
	
	/**
	 * Splits the content area into columns and optionally flows every following
	 * row into whichever column is currently shortest, which balances the page
	 * without the caller having to track positions.
	 */
	public void columns(int count, boolean flow)
	{
		int columns = Math.max(1, Math.min(4, count));
		autoFlow = flow && columns > 1;
		if(columns == columnCount)
		{
			pickShortestColumn();
			return;
		}
		columnCount = columns;
		columnY.clear();
		for(int i = 0; i < columns; i++)
			columnY.add(y);
		activeColumn = Math.min(activeColumn, columns - 1);
	}
	
	/** Moves the flow to the column that ends highest up the page. */
	private void pickShortestColumn()
	{
		if(!autoFlow || columnCount <= 1)
			return;
		int best = 0;
		for(int i = 1; i < columnY.size(); i++)
			if(columnY.get(i) < columnY.get(best))
				best = i;
		activeColumn = best;
		y = columnY.get(best);
	}
	
	/** Makes the given column the target for the following rows. */
	public void column(int index)
	{
		activeColumn = MthClamp(index, 0, columnCount - 1);
		y = columnY.get(activeColumn);
	}
	
	/**
	 * Fans the following content across the columns of the active row block.
	 */
	public void nextColumn()
	{
		if(columnCount <= 1)
			return;
		column(activeColumn + 1);
	}
	
	/**
	 * Width of one column. Columns and the gaps between them add up to exactly
	 * {@link #width()}, so the last column ends on the same inset as the first
	 * one starts on.
	 */
	public int columnWidth()
	{
		if(columnCount <= 1)
			return width;
		int gap = UiTheme.GAP;
		return Math.max(30, (width - gap * (columnCount - 1)) / columnCount);
	}
	
	private int columnX(int index)
	{
		if(columnCount <= 1)
			return 0;
		return index * (columnWidth() + UiTheme.GAP);
	}
	
	private void setColumnY(int value)
	{
		columnY.set(activeColumn, value);
		y = value;
	}
	
	private int maxColumnY()
	{
		int max = 0;
		for(int value : columnY)
			max = Math.max(max, value);
		return max;
	}
	
	/** Brings every column to the same baseline, used by full-width blocks. */
	private void alignColumns()
	{
		int max = maxColumnY();
		for(int i = 0; i < columnY.size(); i++)
			columnY.set(i, max);
		y = max;
	}
	
	// ------------------------------------------------------------------
	// Static drawing blocks
	// ------------------------------------------------------------------
	
	public void section(String title)
	{
		alignColumns();
		int top = y + (y > 0 ? 7 : 0);
		int height = Minecraft.getInstance().font.lineHeight + 4;
		underlays.add(new SectionUnderlay(title, top, width));
		for(int i = 0; i < columnY.size(); i++)
			columnY.set(i, top + height);
		y = maxColumnY();
	}
	
	/** Compact section heading for a trailing section with only one field. */
	public void compactSection(String title)
	{
		alignColumns();
		int top = y + 1;
		int height = Minecraft.getInstance().font.lineHeight + 2;
		underlays.add(new SectionUnderlay(title, top, width));
		for(int i = 0; i < columnY.size(); i++)
			columnY.set(i, top + height);
		y = maxColumnY();
	}
	
	public void label(String text)
	{
		Font font = Minecraft.getInstance().font;
		int height = font.lineHeight + 2;
		underlays.add(new TextUnderlay(text, y, width, height,
			UiTheme.dimTextColor(), true));
		advanceAll(height);
	}
	
	public void note(String text)
	{
		Font font = Minecraft.getInstance().font;
		Component component = Component.literal(text == null ? "" : text);
		int height =
			Math.max(font.lineHeight, font.wordWrapHeight(component, width));
		underlays.add(new NoteUnderlay(component, y, width, height));
		advanceAll(height + 2);
	}
	
	public void divider()
	{
		alignColumns();
		int at = y + 2;
		underlays.add(new DividerUnderlay(at, width));
		advanceAll(5);
	}
	
	/** Advances every column, for blocks that span the whole content area. */
	private void advanceAll(int height)
	{
		for(int i = 0; i < columnY.size(); i++)
			columnY.set(i, y + height);
		y = maxColumnY();
	}
	
	// ------------------------------------------------------------------
	// Widgets
	// ------------------------------------------------------------------
	
	public UiButton button(String label, Runnable action)
	{
		return button(label, UiButton.Kind.PRIMARY, action);
	}
	
	public UiButton button(String label, UiButton.Kind kind, Runnable action)
	{
		UiButton button = new UiButton(0, 0, width, UiTheme.ROW_HEIGHT,
			Component.literal(label), action);
		button.style(kind);
		row(of(button));
		return button;
	}
	
	public UiToggle toggle(String label, BooleanSupplier getter,
		Consumer<Boolean> setter)
	{
		UiToggle toggle = new UiToggle(label, getter, value -> {
			setter.accept(value);
			UiUtilsSettings.save();
		});
		row(of(toggle));
		return toggle;
	}
	
	public UiSlider slider(String label, int min, int max, int value,
		IntConsumer onChange)
	{
		UiSlider slider = new UiSlider(label, min, max, value, onChange);
		row(of(slider));
		return slider;
	}
	
	public UiInput input(String value, Consumer<String> onChange)
	{
		UiInput field = buildInput(value, onChange);
		row(of(field));
		return field;
	}
	
	/**
	 * Builds a field without placing it, for rows that also hold other
	 * controls.
	 * Placing a widget twice would double-render it and let the later row win
	 * the
	 * geometry, which is what makes an input look broken.
	 */
	public UiInput inputSlot(String value, Consumer<String> onChange)
	{
		return buildInput(value, onChange);
	}
	
	private UiInput buildInput(String value, Consumer<String> onChange)
	{
		UiInput field = new UiInput(Minecraft.getInstance().font, width, value,
			Component.empty());
		field.setResponder(text -> {
			if(onChange != null)
				onChange.accept(text);
		});
		return field;
	}
	
	public UiListRow listRow(String label, String detail, Runnable action)
	{
		UiListRow listRow = new UiListRow(label, action).detail(detail);
		row(of(listRow));
		return listRow;
	}
	
	public static Slot of(AbstractWidget widget)
	{
		return new Slot(widget, 1F, -1, 40, UiTheme.ROW_HEIGHT, false);
	}
	
	public static Slot of(AbstractWidget widget, float weight)
	{
		return new Slot(widget, weight, -1, 40, UiTheme.ROW_HEIGHT, false);
	}
	
	public static Slot of(AbstractWidget widget, float weight, int height)
	{
		return new Slot(widget, weight, -1, 40, height, false);
	}
	
	/** Static label column, sized to one third of the row by default. */
	public static Slot text(String label)
	{
		return new Slot(new UiLabel(label), 1F, -1, 44, UiTheme.ROW_HEIGHT,
			false);
	}
	
	public static Slot fixed(AbstractWidget widget, int units)
	{
		return new Slot(widget, 0F, units, units, UiTheme.ROW_HEIGHT, false);
	}
	
	public static Slot fixed(AbstractWidget widget, int units, int height)
	{
		return new Slot(widget, 0F, units, units, height, false);
	}
	
	/**
	 * A widget that keeps its own size, such as a text area or colour picker.
	 */
	public static Slot block(AbstractWidget widget, int height)
	{
		return new Slot(widget, 1F, -1, 40, height, false);
	}
	
	public static Slot block(AbstractWidget widget, int height, int minWidth)
	{
		return new Slot(widget, 1F, -1, minWidth, height, false);
	}
	
	/** A block that stretches to the bottom of the visible content area. */
	public static Slot fill(AbstractWidget widget, int minHeight)
	{
		return new Slot(widget, 1F, -1, 40, minHeight, true);
	}
	
	public void row(Slot... slots)
	{
		placeRow(slots, Pin.CONTENT);
	}
	
	public void footerRow(Slot... slots)
	{
		placeRow(slots, Pin.FOOTER);
	}
	
	/** Adds a compact button to the pinned header band. */
	public UiButton headerButton(String label, Runnable action)
	{
		UiButton button = new UiButton(0, 0, 0, UiTheme.ROW_HEIGHT,
			Component.literal(label), action);
		button.style(UiButton.Kind.SECONDARY);
		button.setWidth(HEADER_BUTTON_WIDTH);
		placeRow(new Slot[]{fixed(button, HEADER_BUTTON_WIDTH)}, Pin.HEADER);
		return button;
	}
	
	/** Adds a button to the pinned footer band. */
	public UiButton footerButton(String label, Runnable action)
	{
		return footerButton(label, UiButton.Kind.SECONDARY, action);
	}
	
	public UiButton footerButton(String label, UiButton.Kind kind,
		Runnable action)
	{
		UiButton button = new UiButton(0, 0, width, UiTheme.ROW_HEIGHT,
			Component.literal(label), action);
		button.style(kind);
		pendingFooter.add(of(button));
		return button;
	}
	
	private void placeRow(Slot[] slots, Pin pin)
	{
		placeRow(slots, pin, 0, 0);
	}
	
	/**
	 * Lays a row across the whole content width, ignoring the column grid. Used
	 * for
	 * controls that need the full width, such as colour pickers and sliders.
	 */
	public void spanRow(Slot... slots)
	{
		if(slots.length == 0)
			return;
		alignColumns();
		placeRow(slots, Pin.CONTENT, 0, width);
	}
	
	/**
	 * Centres a group of fixed width controls across the whole content width.
	 */
	public void centeredRow(Slot... slots)
	{
		if(slots.length == 0)
			return;
		alignColumns();
		int gap = UiTheme.GAP;
		int total = gap * (slots.length - 1);
		for(Slot slot : slots)
			total += slot.fixedWidth >= 0 ? slot.fixedWidth
				: Math.max(slot.minWidth, 140);
		placeRow(slots, Pin.CONTENT, Math.max(0, (width - total) / 2), total);
	}
	
	/**
	 * Places one row. A non-zero {@code spanWidth} pins the row to that total
	 * width
	 * starting at {@code offsetX} across the content area; otherwise the row
	 * fills
	 * the active column.
	 */
	private void placeRow(Slot[] slots, Pin pin, int offsetX, int spanWidth)
	{
		if(slots.length == 0)
			return;
		boolean spanning = pin == Pin.CONTENT && spanWidth > 0;
		if(pin == Pin.CONTENT && !spanning)
			pickShortestColumn();
		int gap = UiTheme.GAP;
		int height = UiTheme.ROW_HEIGHT;
		float totalWeight = 0F;
		int fixedSpace = 0;
		for(Slot slot : slots)
		{
			height = Math.max(height, slot.height);
			if(slot.fixedWidth >= 0)
				fixedSpace += slot.fixedWidth;
			else
				totalWeight += slot.weight;
		}
		boolean gridded = pin == Pin.CONTENT && columnCount > 1 && !spanning;
		int usable = spanning ? spanWidth : (gridded ? columnWidth() : width);
		int available = usable - gap * (slots.length - 1) - fixedSpace;
		int x = spanning ? offsetX : (gridded ? columnX(activeColumn) : 0);
		// Right edge this row must stay inside. Every width is clamped to it,
		// so no
		// accumulation of minimums or rounding can push a row out of the panel.
		int limit = x + usable;
		int flexRemaining = available;
		float weightRemaining = totalWeight;
		List<Item> placed = new ArrayList<>();
		for(Slot slot : slots)
		{
			int slotWidth;
			if(slot.fixedWidth >= 0)
			{
				slotWidth = slot.fixedWidth;
			}else if(weightRemaining <= 0.0001F)
			{
				slotWidth = Math.max(slot.minWidth, flexRemaining);
			}else
			{
				float share = slot.weight / weightRemaining;
				slotWidth = Math.max(slot.minWidth,
					(int)Math.round(flexRemaining * share));
				flexRemaining -= slotWidth;
				weightRemaining -= slot.weight;
			}
			slotWidth = Math.max(8, Math.min(slotWidth, limit - x));
			placed.add(
				new Item(slot.widget, x, 0, slotWidth, height, pin, slot.fill));
			x += slotWidth + gap;
		}
		int rowY = pin == Pin.CONTENT ? y : 0;
		for(Item item : placed)
		{
			Item positioned = new Item(item.widget(), item.x(), rowY,
				item.width(), item.height(), pin, item.fill());
			items.add(positioned);
			register(item.widget());
		}
		if(pin == Pin.CONTENT)
		{
			if(spanning)
				advanceAll(height + gap);
			else
				setColumnY(y + height + gap);
		}
	}
	
	private void register(AbstractWidget widget)
	{
		screen.register(widget);
	}
	
	/** Widget created for a slot, so callers can keep a typed reference. */
	@SuppressWarnings("unchecked")
	public static <T extends AbstractWidget> T widget(Slot slot)
	{
		return (T)slot.widget();
	}
	
	public List<Item> items()
	{
		return List.copyOf(items);
	}
	
	public List<Underlay> underlays()
	{
		return List.copyOf(underlays);
	}
	
	/** Short text shown at the right of the screen header. */
	public void status(String text)
	{
		screen.setStatus(text);
	}
	
	public int height()
	{
		return Math.max(maxColumnY(), y);
	}
	
	/** Completes the footer, adding a Done button when nothing was declared. */
	void finish()
	{
		if(pendingFooter.isEmpty())
			pendingFooter.add(of(new UiButton(0, 0, width, UiTheme.ROW_HEIGHT,
				Component.literal("Done"), screen::onClose)));
		placeRow(pendingFooter.toArray(new Slot[0]), Pin.FOOTER);
	}
	
	private static int MthClamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}
	
	// ------------------------------------------------------------------
	// Underlay implementations
	// ------------------------------------------------------------------
	
	private static final class SectionUnderlay extends Underlay
	{
		private final String title;
		private final int y;
		private final int width;
		
		private SectionUnderlay(String title, int y, int width)
		{
			this.title = title;
			this.y = y;
			this.width = width;
		}
		
		@Override
		public void draw(GuiGraphics graphics, Font font, int cardWidth)
		{
			int textY = y + 1;
			String shown = UiTheme.ellipsize(font, title, width - 4);
			UiTheme.text(graphics, font, shown, 0, textY,
				UiTheme.dimTextColor());
			int lineX = UiTheme.designTextWidth(font.width(shown)) + 5;
			if(lineX < width)
				graphics.fill(lineX, textY + UiTheme.lineHeight(font) / 2,
					width, textY + UiTheme.lineHeight(font) / 2 + 1,
					UiTheme.softBorderColor());
		}
	}
	
	private static final class TextUnderlay extends Underlay
	{
		private final String text;
		private final int y;
		private final int width;
		private final int height;
		private final int color;
		private final boolean centered;
		
		private TextUnderlay(String text, int y, int width, int height,
			int color, boolean centered)
		{
			this.text = text;
			this.y = y;
			this.width = width;
			this.height = height;
			this.color = color;
			this.centered = centered;
		}
		
		@Override
		public void draw(GuiGraphics graphics, Font font, int cardWidth)
		{
			String shown = UiTheme.ellipsize(font, text, width);
			int textY = UiTheme.textY(font, y, height);
			if(centered)
				UiTheme.textCentered(graphics, font, shown, width / 2, textY,
					color);
			else
				UiTheme.text(graphics, font, shown, 0, textY, color);
		}
	}
	
	private static final class NoteUnderlay extends Underlay
	{
		private final Component text;
		private final int y;
		private final int width;
		
		private NoteUnderlay(Component text, int y, int width, int height)
		{
			this.text = text;
			this.y = y;
			this.width = width;
		}
		
		@Override
		public void draw(GuiGraphics graphics, Font font, int cardWidth)
		{
			int lineY = y;
			for(FormattedCharSequence line : font.split(text,
				UiTheme.textWidth(width)))
			{
				UiTheme.text(graphics, font, line, 0, lineY,
					UiTheme.dimTextColor());
				lineY += UiTheme.lineHeight(font);
			}
		}
	}
	
	private static final class DividerUnderlay extends Underlay
	{
		private final int y;
		private final int width;
		
		private DividerUnderlay(int y, int width)
		{
			this.y = y;
			this.width = width;
		}
		
		@Override
		public void draw(GuiGraphics graphics, Font font, int cardWidth)
		{
			graphics.fill(0, y, width, y + 1, UiTheme.softBorderColor());
		}
	}
}
