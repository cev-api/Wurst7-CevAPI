/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.joml.Matrix3x2fStack;
import net.wurstclient.uiutils.UiUtilsSettings;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Base for every modern UI-Utils screen.
 * <p>
 * Content is described in design units by {@link UiContent}. The screen picks
 * one
 * scale for the whole panel, applies that scale to rendering and to mouse
 * input,
 * and scrolls the content when it is taller than the window. One transform
 * covers
 * everything, so text never ends up at different sizes and no control can be
 * pushed off screen.
 * <p>
 * Scaling rules: the panel only shrinks below 1x when the window is too narrow
 * for it (horizontal overflow cannot be scrolled), it grows into spare room up
 * to
 * 1.5x, and vertical overflow scrolls instead of shrinking the text.
 */
public abstract class UiModernScreen extends Screen
{
	private static final double WHEEL_UNITS = 14D;
	private static final int MARGIN = 4;
	// Symmetric inset for the card's content and header. The scrollbar sits in
	// the
	// remaining right-side gutter, outside this inset.
	private static final int CONTENT_X = UiTheme.PAD + 3;
	private static final int CONTENT_Y = UiTheme.HEADER_HEIGHT;
	
	private final List<UiContent.Item> items = new ArrayList<>();
	private final List<UiContent.Underlay> underlays = new ArrayList<>();
	private final List<AbstractWidget> headerWidgets = new ArrayList<>();
	private final List<AbstractWidget> contentWidgets = new ArrayList<>();
	private final List<AbstractWidget> footerWidgets = new ArrayList<>();
	private final Set<AbstractWidget> owned =
		Collections.newSetFromMap(new IdentityHashMap<>());
	
	private float scale = 1F;
	private int tooltipMouseX, tooltipMouseY;
	private int originX;
	private int originY;
	private int cardWidth;
	private int cardHeight;
	private int viewportHeight;
	/**
	 * Raw content extents, in content units. These are NEVER clamped to the
	 * viewport: the scroll range has to be derived from what the content
	 * actually
	 * needs, otherwise maxScroll is forced to zero and content past the
	 * viewport can
	 * never be reached.
	 */
	private int cursorBottom;
	private int maxWidgetBottom;
	private int maxManualBottom;
	/** Bottom of the lowest thing drawn, and the natural content height. */
	private int measuredContentHeight;
	/** Bottom padding kept below the last row when deciding the run extent. */
	private static final int CONTENT_BOTTOM_PADDING = 8;
	private double scroll;
	private double maxScroll;
	private String status = "";
	private long statusStartedNanos = System.nanoTime();
	private int barLeft;
	private int barTop;
	private int barBottom;
	private int barThumbY;
	private int barThumbHeight;
	private boolean draggingBar;
	private int barGrabOffset;
	
	protected UiModernScreen(Component title)
	{
		super(title);
	}
	
	/** Natural design width of the panel. */
	protected int naturalWidth()
	{
		return 240;
	}
	
	/**
	 * Grow the panel into spare horizontal room instead of leaving it narrow.
	 */
	protected boolean expandWidth()
	{
		return false;
	}
	
	/** Upper bound used when {@link #expandWidth()} is on. */
	protected int maxWidth()
	{
		return 460;
	}
	
	/**
	 * Let the panel use the full window height instead of hugging its content.
	 */
	protected boolean expandHeight()
	{
		return false;
	}
	
	/** Fill in the screen content. Called again on every resize. */
	protected abstract void buildContent(UiContent content);
	
	/**
	 * Extra drawing inside the clipped, scrolled content area. Content units.
	 */
	protected void drawContentOverlay(GuiGraphics graphics, Font font,
		int mouseX, int mouseY)
	{}
	
	/**
	 * Click inside the clipped, scrolled content area, in content units.
	 * Screens
	 * that draw their own text blocks use this instead of raw hit testing.
	 */
	protected boolean onContentClick(int x, int y, boolean doubleClick)
	{
		return false;
	}
	
	/**
	 * Short text shown at the right of the header, such as a pending prompt.
	 */
	public void setStatus(String value)
	{
		String next = value == null ? "" : value;
		if(!status.equals(next))
		{
			status = next;
			statusStartedNanos = System.nanoTime();
		}
	}
	
	protected final int contentHeightUnits()
	{
		return Math.max(1, cardHeight - CONTENT_Y - UiTheme.FOOTER_HEIGHT);
	}
	
	protected final int contentWidthUnits()
	{
		return contentWidth();
	}
	
	protected final int contentWidth()
	{
		return designContentWidth(cardWidth);
	}
	
	protected final double scrollOffset()
	{
		return scroll;
	}
	
	protected final int cardWidthUnits()
	{
		return cardWidth;
	}
	
	protected final float uiScale()
	{
		return scale;
	}
	
	/** Current scale, so callers can skip work that is not visible. */
	protected final float currentScale()
	{
		return scale;
	}
	
	/**
	 * Rebuilds dynamic content without throwing away the user's scroll
	 * position.
	 */
	protected final void rebuildWidgetsPreservingScroll()
	{
		double previousScroll = scroll;
		java.util.List<net.minecraft.client.gui.components.EditBox> oldFields =
			children().stream().filter(
				net.minecraft.client.gui.components.EditBox.class::isInstance)
				.map(net.minecraft.client.gui.components.EditBox.class::cast)
				.toList();
		int focusedIndex = oldFields.indexOf(getFocused());
		int cursor = focusedIndex < 0 ? 0
			: oldFields.get(focusedIndex).getCursorPosition();
		int highlight = cursor;
		if(focusedIndex >= 0)
		{
			try
			{
				var field = net.minecraft.client.gui.components.EditBox.class
					.getDeclaredField("highlightPos");
				field.setAccessible(true);
				highlight = field.getInt(oldFields.get(focusedIndex));
			}catch(ReflectiveOperationException ignored)
			{}
		}
		rebuildWidgets();
		java.util.List<net.minecraft.client.gui.components.EditBox> newFields =
			children().stream().filter(
				net.minecraft.client.gui.components.EditBox.class::isInstance)
				.map(net.minecraft.client.gui.components.EditBox.class::cast)
				.toList();
		if(focusedIndex >= 0 && focusedIndex < newFields.size())
		{
			var field = newFields.get(focusedIndex);
			setFocused(field);
			field.setCursorPosition(cursor);
			field.setHighlightPos(highlight);
		}
		scroll = Mth.clamp(previousScroll, 0, maxScroll);
		placeItems();
	}
	
	/** Scroller handed to screens that manage their own text blocks. */
	protected final Consumer<Double> scroller()
	{
		return this::scrollBy;
	}
	
	/** Raw screen x/y converted into panel units, for subclass hit testing. */
	protected final int designX(double screenX)
	{
		return (int)Math.floor((screenX - originX) / scale);
	}
	
	protected final int designY(double screenY)
	{
		return (int)Math.floor((screenY - originY) / scale);
	}
	
	// ------------------------------------------------------------------
	// Layout
	// ------------------------------------------------------------------
	
	@Override
	protected void init()
	{
		clearWidgets();
		reset();
		// Pass 1: provisional layout, only to learn how tall the content is.
		int natural = UiTheme.HEADER_HEIGHT
			+ buildAndCollect(designContentWidth(naturalWidth()))
			+ UiTheme.FOOTER_HEIGHT;
		computeScaleAndWidth();
		// Pass 2: rebuild using the FINAL card width. Card width and scale are
		// then
		// locked before the content is measured, so the width the content was
		// laid
		// out for is always the width it is drawn into. Recomputing the card
		// width
		// after building was what clipped the right hand side of screens whose
		// content height changed the scale.
		clearWidgets();
		reset();
		int settled = UiTheme.HEADER_HEIGHT
			+ buildAndCollect(designContentWidth(cardWidth))
			+ UiTheme.FOOTER_HEIGHT;
		measuredContentHeight =
			settled - UiTheme.HEADER_HEIGHT - UiTheme.FOOTER_HEIGHT;
		cursorBottom = measuredContentHeight;
		maxWidgetBottom = 0;
		computeHeight(settled);
		// Place the children, then settle the scroll extent against their REAL
		// bounds
		// plus anything the screen reported drawing itself. The layout cursor
		// alone is
		// an estimate and misses manually rendered content.
		placeItems();
		settleExtent();
	}
	
	/**
	 * Escape always closes the screen, independent of whatever the vanilla
	 * {@code shouldCloseOnEsc()} path decides.
	 */
	@Override
	public boolean keyPressed(KeyEvent event)
	{
		if(event.isEscape())
		{
			onClose();
			return true;
		}
		
		return super.keyPressed(event);
	}
	
	/**
	 * Width the content column actually has inside a card of the given width.
	 * The content inset is symmetric; the scrollbar has its own gutter beyond
	 * it.
	 */
	protected static int designContentWidth(int cardWidth)
	{
		return Math.max(40, cardWidth - CONTENT_X * 2);
	}
	
	private void reset()
	{
		items.clear();
		underlays.clear();
		headerWidgets.clear();
		contentWidgets.clear();
		footerWidgets.clear();
		owned.clear();
		// Extents are per-generation: a rebuild after the content shrank must
		// not
		// keep reporting the old, taller extent or the scroll range would be
		// stale.
		maxManualBottom = 0;
		scroll = 0;
	}
	
	private int buildAndCollect(int width)
	{
		UiContent content = new UiContent(this, width);
		buildContent(content);
		content.finish();
		items.addAll(content.items());
		underlays.addAll(content.underlays());
		for(UiContent.Item item : content.items())
		{
			switch(item.pin())
			{
				case HEADER -> headerWidgets.add(item.widget());
				case FOOTER -> footerWidgets.add(item.widget());
				case CONTENT -> contentWidgets.add(item.widget());
			}
		}
		// Content the screen declares that it will draw itself (text blocks,
		// result
		// lists) has to contribute to the extent too - those are not widgets.
		maxManualBottom = Math.max(maxManualBottom, content.reportedBottom());
		return content.height();
	}
	
	/**
	 * Declares the bottom of content this screen draws itself, in content
	 * units.
	 * Screens that paint their own text or lists must call this for the same
	 * span
	 * they will draw, or that content cannot be scrolled to and may be cut off.
	 */
	protected final void reportContentBottom(int units)
	{
		maxManualBottom = Math.max(maxManualBottom, units);
	}
	
	/** Full natural content extent, including reported manual content. */
	protected final int naturalContentHeight()
	{
		return Math.max(Math.max(measuredContentHeight, maxWidgetBottom),
			maxManualBottom);
	}
	
	/** Registers a widget with the screen; used by the content builder. */
	<T extends AbstractWidget> T register(T widget)
	{
		return addRenderableWidget(widget);
	}
	
	/**
	 * Fixes the scale and the card width.
	 * <p>
	 * Only the WIDTH constrains the scale: horizontal overflow cannot be
	 * scrolled,
	 * so the panel has to fit across. Height is deliberately left alone -
	 * growing
	 * content (scan results, long lists) makes the panel scroll, it does not
	 * shrink
	 * the whole UI.
	 */
	private void computeScaleAndWidth()
	{
		int availW = Math.max(80, this.width - MARGIN * 2);
		int availH = Math.max(60, this.height - MARGIN * 2);
		float fitWidth = availW / (float)Math.max(1, naturalWidth());
		float preferred = UiTheme.screenScale(this.width, this.height);
		// Grow into spare width, but never above what fits and never above the
		// user's target. snapScaleDown cannot round up past the fit.
		// Small scales keep the box geometry and native-size glyphs too close
		// together. Preserve a practical layout floor while still honoring
		// smaller
		// scales when the card must fit horizontally.
		scale = Math.min(Math.max(preferred, 0.875F), fitWidth);
		
		// The viewport height MUST be known before the content is built:
		// screens use
		// availableContentHeight() to size their own lists while building.
		// Setting it
		// only after the build left it at zero during layout, so every list was
		// sized
		// from a viewport of nothing.
		viewportHeight = Math.max(60, (int)Math.floor(availH / scale));
		
		cardWidth = naturalWidth();
		if(expandWidth())
			cardWidth = Math.max(cardWidth,
				Math.min(maxWidth(), (int)Math.floor(availW / scale)));
	}
	
	/** Sizes the card to the content and the window, then centres it. */
	private void computeHeight(int naturalHeight)
	{
		cardHeight = expandHeight() ? viewportHeight
			: Math.min(naturalHeight, viewportHeight);
		maxScroll = Math.max(0, naturalHeight - cardHeight);
		scroll = Mth.clamp(scroll, 0, maxScroll);
		originX = Math.round((this.width - cardWidth * scale) / 2F);
		originY = Math.round((this.height - cardHeight * scale) / 2F);
	}
	
	/**
	 * Places every item and records how far down the placed children actually
	 * reach.
	 */
	private void placeItems()
	{
		int offset = (int)Math.round(scroll);
		int contentBottom = cardHeight - UiTheme.FOOTER_HEIGHT;
		int contentRight = cardWidth - CONTENT_X;
		// Raw: no viewport clamp, so a short child list does not mask a long
		// one.
		maxWidgetBottom = 0;
		for(UiContent.Item item : items)
		{
			AbstractWidget widget = item.widget();
			int x;
			int y;
			switch(item.pin())
			{
				case HEADER ->
				{
					x = cardWidth - CONTENT_X - item.x() - item.width();
					y = (UiTheme.HEADER_HEIGHT - item.height()) / 2;
				}
				case FOOTER ->
				{
					x = CONTENT_X + item.x();
					y = contentBottom
						+ (UiTheme.FOOTER_HEIGHT - item.height()) / 2;
				}
				default ->
				{
					x = CONTENT_X + item.x();
					y = CONTENT_Y + item.y() - offset;
				}
			}
			// Keep at least the widget's minimum width inside both panel
			// insets,
			// whatever a screen's own row arithmetic does.
			x = Math.max(CONTENT_X, Math.min(x, contentRight - 8));
			int width = Math.min(item.width(), contentRight - x);
			width = Math.max(1, width);
			int previousWidth = widget.getWidth();
			int height = item.height();
			if(item.fill() && item.pin() == UiContent.Pin.CONTENT)
				height = Math.max(height,
					contentHeightUnits() - (item.y() - offset));
			widget.setX(x);
			widget.setY(y);
			widget.setWidth(width);
			widget.setHeight(height);
			if(widget instanceof UiInput input && previousWidth != width)
				input.refreshVisibleText();
			if(item.pin() == UiContent.Pin.CONTENT)
			{
				widget.visible = y + height > CONTENT_Y && y < contentBottom;
				// Content units, so it can be compared with the layout cursor
				// directly.
				maxWidgetBottom = Math.max(maxWidgetBottom,
					(y - CONTENT_Y + offset) + height);
			}
			owned.add(widget);
		}
		updateScrollbar();
	}
	
	/**
	 * Sizes the card (or the scroll range) from the REAL bottom of the placed
	 * children rather than from the layout cursor alone.
	 * <p>
	 * Invariant: the card holds every child when the window has room for it,
	 * and
	 * otherwise maxScroll is large enough to scroll the final child fully into
	 * the
	 * viewport. The final widget is never left chopped with no way to reach it.
	 */
	private void settleExtent()
	{
		// The natural extent is the RAW bottom of everything drawn, plus
		// padding. It
		// is deliberately not floored at the viewport height.
		int naturalExtent = naturalContentHeight() + CONTENT_BOTTOM_PADDING;
		measuredContentHeight = naturalExtent;
		int required =
			UiTheme.HEADER_HEIGHT + naturalExtent + UiTheme.FOOTER_HEIGHT;
		if(required <= viewportHeight)
		{
			if(expandHeight())
			{
				// Full-height screens, such as the scanner dashboard,
				// deliberately keep
				// the taller viewport even when the current result set is
				// short.
				cardHeight = viewportHeight;
				maxScroll = 0;
				scroll = 0;
				updateScrollbar();
				return;
			}
			// The window has room: grow the card so every child is inside it.
			maxScroll = 0;
			scroll = 0;
			if(cardHeight != required)
			{
				cardHeight = required;
				originY = Math.round((this.height - cardHeight * scale) / 2F);
				// A taller card changes the room a filling item gets, so
				// re-place once.
				placeItems();
			}
			updateScrollbar();
			return;
		}
		// Not enough room: scroll. maxScroll is derived from the natural
		// extent, so the
		// whole of the final row can be scrolled into the viewport.
		maxScroll = Math.max(0, required - cardHeight);
		scroll = Mth.clamp(scroll, 0, maxScroll);
		updateScrollbar();
	}
	
	/**
	 * Room for content in content units, based on the WINDOW rather than the
	 * card.
	 * Derived from the viewport, which is fixed for a given window and scale,
	 * so a
	 * screen that fills the window lands on the same answer on every layout
	 * pass and
	 * cannot feed its own height back into the measurement.
	 */
	protected final int availableContentHeight()
	{
		return Math.max(40, viewportHeight - CONTENT_Y - UiTheme.FOOTER_HEIGHT);
	}
	
	/**
	 * Viewport height in content units, same basis as acceptContentHeight().
	 */
	protected final int viewportHeightUnits()
	{
		return Math.max(40, viewportHeight - CONTENT_Y - UiTheme.FOOTER_HEIGHT);
	}
	
	private void updateScrollbar()
	{
		barLeft = cardWidth - UiTheme.SCROLLBAR_WIDTH - 2;
		barTop = CONTENT_Y + 1;
		barBottom = cardHeight - UiTheme.FOOTER_HEIGHT - 1;
		int track = Math.max(1, barBottom - barTop);
		if(maxScroll <= 0)
		{
			barThumbHeight = track;
			barThumbY = barTop;
			return;
		}
		double visible = cardHeight - CONTENT_Y - UiTheme.FOOTER_HEIGHT;
		double total = Math.max(1D, visible + maxScroll);
		barThumbHeight =
			Math.max(12, (int)Math.round(track * (visible / total)));
		int travel = Math.max(1, track - barThumbHeight);
		barThumbY = barTop + (int)Math.round((scroll / maxScroll) * travel);
	}
	
	private void scrollBy(double units)
	{
		if(maxScroll <= 0)
		{
			scroll = 0;
			return;
		}
		scroll = Mth.clamp(scroll + units, 0, maxScroll);
		placeItems();
	}
	
	// ------------------------------------------------------------------
	// Rendering
	// ------------------------------------------------------------------
	
	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY,
		float partialTicks)
	{
		graphics.fill(0, 0, this.width, this.height, UiTheme.SCRIM);
		tooltipMouseX = mouseX;
		tooltipMouseY = mouseY;
		
		int designMouseX = designX(mouseX);
		int designMouseY = designY(mouseY);
		int offset = (int)Math.round(scroll);
		int contentBottom = cardHeight - UiTheme.FOOTER_HEIGHT;
		int clipHeight = Math.max(1, contentBottom - CONTENT_Y);
		
		float previousTextScale = UiTheme.pushTextScale(scale);
		boolean previousPixelSnap = UiTheme.pushPixelSnapText();
		try
		{
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(originX, originY);
			pose.scale(scale, scale);
			
			drawPanel(graphics);
			
			// Clip only scrollable content. A scissor left enabled when
			// scrolling is off
			// also clips the pinned header, footer, chrome, and later GUI
			// rendering.
			boolean clipped = maxScroll > 0;
			if(clipped)
				graphics.enableScissor(0, CONTENT_Y - 1, cardWidth,
					contentBottom + 1);
			pose.pushMatrix();
			pose.translate(CONTENT_X, CONTENT_Y - offset);
			for(UiContent.Underlay underlay : underlays)
				underlay.draw(graphics, this.font, contentWidth());
			drawContentOverlay(graphics, this.font, designMouseX - CONTENT_X,
				designMouseY - CONTENT_Y + offset);
			pose.popMatrix();
			for(AbstractWidget widget : contentWidgets)
				renderWidget(graphics, widget, designMouseX, designMouseY,
					partialTicks);
			if(clipped)
				graphics.disableScissor();
			
			for(AbstractWidget widget : headerWidgets)
				renderWidget(graphics, widget, designMouseX, designMouseY,
					partialTicks);
			for(AbstractWidget widget : footerWidgets)
				renderWidget(graphics, widget, designMouseX, designMouseY,
					partialTicks);
			
			drawChrome(graphics, designMouseX, designMouseY);
			drawDebugBounds(graphics, offset);
			pose.popMatrix();
		}finally
		{
			UiTheme.popPixelSnapText(previousPixelSnap);
			UiTheme.popTextScale(previousTextScale);
		}
		
		renderForeignWidgets(graphics, mouseX, mouseY, partialTicks);
	}
	
	/**
	 * Optional layout overlay: outlines the card, the content region and every
	 * content widget box, so a cut-off control can be measured on screen
	 * instead of
	 * guessed at. Toggled by the "Debug layout boxes" setting.
	 */
	private void drawDebugBounds(GuiGraphics graphics, int offset)
	{
		if(!UiUtilsSettings.get().uiDebugBounds)
			return;
		int contentBottom = cardHeight - UiTheme.FOOTER_HEIGHT;
		// Card edge (yellow) and content viewport (magenta).
		UiTheme.border(graphics, 0, 0, cardWidth, cardHeight, 0xFFFFFF00);
		UiTheme.border(graphics, CONTENT_X, CONTENT_Y,
			Math.max(1, contentWidth()), Math.max(1, contentBottom - CONTENT_Y),
			0xFFFF00FF);
		int index = 0;
		for(UiContent.Item item : items)
		{
			if(item.pin() != UiContent.Pin.CONTENT)
				continue;
			AbstractWidget widget = item.widget();
			if(!widget.visible)
				continue;
			int x = widget.getX();
			int y = widget.getY();
			if(y + widget.getHeight() < CONTENT_Y || y > contentBottom)
				continue;
			// Cyan fits inside the viewport, red does not (and so cannot be
			// reached).
			boolean reachable =
				y >= CONTENT_Y && y + widget.getHeight() <= contentBottom;
			UiTheme.border(graphics, x, y, Math.max(1, widget.getWidth()),
				Math.max(1, widget.getHeight()),
				reachable ? 0xFF30E0FF : 0xFFFF3030);
			index++;
			if(index > 200)
				break;
		}
		// RAW values only: none of these are clamped to the viewport, so a
		// short
		// child list cannot mask a taller manual one.
		Font font = this.font;
		int textX = CONTENT_X + 2;
		int textY = UiTheme.HEADER_HEIGHT + 2;
		int viewportH = Math.max(0, contentBottom - CONTENT_Y);
		String[] metrics =
			{"cardH=" + cardHeight + " scale=" + scale + " cardW=" + cardWidth,
				"viewportH=" + viewportH, "maxCursorBottom=" + cursorBottom,
				"maxWidgetBottom=" + maxWidgetBottom,
				"maxManualBottom=" + maxManualBottom,
				"naturalContentH=" + measuredContentHeight, "maxScroll="
					+ (int)maxScroll + " scroll=" + (int)Math.round(scroll),};
		int lineHeight = UiTheme.lineHeight(font);
		int line = 0;
		for(String metric : metrics)
		{
			UiTheme.text(graphics, font, metric, textX,
				textY + line * lineHeight, 0xFFFFFF00);
			line++;
		}
		// Per child: index, y, h, bottom in content units.
		int shown = 0;
		for(UiContent.Item item : items)
		{
			if(item.pin() != UiContent.Pin.CONTENT)
				continue;
			AbstractWidget widget = item.widget();
			int relY = widget.getY() - CONTENT_Y + offset;
			int relBottom = relY + widget.getHeight();
			String entry = "#" + shown + " y=" + relY + " h="
				+ widget.getHeight() + " b=" + relBottom;
			UiTheme.text(graphics, font, entry, textX + 200,
				textY + shown * lineHeight, 0xFF80FFFF);
			shown++;
			if(shown >= 18)
				break;
		}
	}
	
	private void renderWidget(GuiGraphics graphics, AbstractWidget widget,
		int mouseX, int mouseY, float partialTicks)
	{
		if(widget == null || !widget.visible)
			return;
		
		float previousTextScale = UiTheme.pushTextScale(1F);
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		// Fractional screen scaling otherwise places widgets between GUI
		// pixels.
		// Snap the frame and native text together, including vanilla EditBox
		// text.
		float screenX = originX + widget.getX() * scale;
		float screenY = originY + widget.getY() * scale;
		pose.translate((Math.round(screenX) - screenX) / scale,
			(Math.round(screenY) - screenY) / scale);
		UiWidgetTooltip.Context previousTooltip =
			UiWidgetTooltip.push(widget, tooltipMouseX, tooltipMouseY,
				widget.getRectangle().transformAxisAligned(pose));
		try
		{
			if(widget instanceof UiScalable scalable)
				scalable.applyUiScale(1F / scale);
			widget.render(graphics, mouseX, mouseY, partialTicks);
		}finally
		{
			UiWidgetTooltip.pop(previousTooltip);
			pose.popMatrix();
			UiTheme.popTextScale(previousTextScale);
		}
	}
	
	/** Widgets owned by other systems keep rendering in raw screen space. */
	private void renderForeignWidgets(GuiGraphics graphics, int mouseX,
		int mouseY, float partialTicks)
	{
		for(Object child : children())
		{
			if(child instanceof AbstractWidget widget && widget.visible
				&& !owned.contains(widget))
				widget.render(graphics, mouseX, mouseY, partialTicks);
		}
	}
	
	private void drawPanel(GuiGraphics graphics)
	{
		graphics.fill(0, 0, cardWidth, cardHeight, UiTheme.windowColor());
		graphics.fill(0, 0, cardWidth, UiTheme.HEADER_HEIGHT,
			UiTheme.headerColor());
		graphics.fill(0, cardHeight - UiTheme.FOOTER_HEIGHT, cardWidth,
			cardHeight, UiTheme.footerColor());
	}
	
	private void drawChrome(GuiGraphics graphics, int mouseX, int mouseY)
	{
		int accent = UiTheme.accent();
		UiTheme.hLine(graphics, 0, UiTheme.HEADER_HEIGHT - 1, cardWidth,
			UiTheme.withAlpha(accent, 0x99));
		UiTheme.hLine(graphics, 0, cardHeight - UiTheme.FOOTER_HEIGHT,
			cardWidth, UiTheme.borderColor());
		UiTheme.border(graphics, 0, 0, cardWidth, cardHeight,
			UiTheme.borderColor());
		
		int titleY = UiTheme.textY(this.font, 0, UiTheme.HEADER_HEIGHT);
		int headerRight = cardWidth - CONTENT_X;
		for(AbstractWidget widget : headerWidgets)
			if(widget.visible)
				headerRight = Math.min(headerRight, widget.getX() - 6);
		int headerWidth = Math.max(0, headerRight - CONTENT_X);
		int titleWidth = status.isEmpty() ? headerWidth
			: Math.min(UiTheme.designTextWidth(this.font.width(this.title)),
				Math.max(0, headerWidth / 2 - 4));
		String title =
			UiTheme.ellipsize(this.font, this.title.getString(), titleWidth);
		UiTheme.text(graphics, this.font, title, CONTENT_X, titleY,
			UiTheme.textColor());
		if(!status.isEmpty())
			drawHeaderStatus(graphics, CONTENT_X + titleWidth + 8, headerRight,
				titleY);
		
		if(maxScroll > 0)
		{
			graphics.fill(barLeft, barTop, barLeft + UiTheme.SCROLLBAR_WIDTH,
				barBottom, 0x40000000);
			boolean hot = draggingBar || (mouseX >= barLeft - 3
				&& mouseX <= barLeft + UiTheme.SCROLLBAR_WIDTH + 3
				&& mouseY >= barThumbY && mouseY <= barThumbY + barThumbHeight);
			graphics.fill(barLeft, barThumbY, barLeft + UiTheme.SCROLLBAR_WIDTH,
				barThumbY + barThumbHeight, hot ? accent : 0x80FFFFFF);
		}
	}
	
	/** Long status messages scroll within their own clipped header region. */
	private void drawHeaderStatus(GuiGraphics graphics, int left, int right,
		int y)
	{
		int available = right - left;
		if(available <= 0)
			return;
		int textWidth = UiTheme.designTextWidth(this.font.width(status));
		if(textWidth <= available)
		{
			UiTheme.textRight(graphics, this.font, status, right, y,
				UiTheme.WARN);
			return;
		}
		// Pause at each end, then move at a steady speed so the full message is
		// readable.
		double overflow = textWidth - available;
		double travelSeconds = overflow / 28D;
		double pauseSeconds = 1.25D;
		double elapsed =
			(System.nanoTime() - statusStartedNanos) / 1_000_000_000D;
		double phase = elapsed % (2D * (travelSeconds + pauseSeconds));
		double offset = phase < pauseSeconds ? 0D
			: phase < pauseSeconds + travelSeconds
				? (phase - pauseSeconds) * 28D
				: phase < 2D * pauseSeconds + travelSeconds ? overflow
					: overflow
						- (phase - 2D * pauseSeconds - travelSeconds) * 28D;
		graphics.enableScissor(left, 1, right, UiTheme.HEADER_HEIGHT - 1);
		try
		{
			UiTheme.text(graphics, this.font, status,
				left - (int)Math.round(offset), y, UiTheme.WARN);
		}finally
		{
			graphics.disableScissor();
		}
	}
	
	// ------------------------------------------------------------------
	// Input
	// ------------------------------------------------------------------
	
	private MouseButtonEvent toDesign(MouseButtonEvent event)
	{
		return new MouseButtonEvent((event.x() - originX) / scale,
			(event.y() - originY) / scale, event.buttonInfo());
	}
	
	private boolean inContentRegion(int designY)
	{
		return designY >= CONTENT_Y
			&& designY < cardHeight - UiTheme.FOOTER_HEIGHT;
	}
	
	private boolean overScrollbar(int x, int y)
	{
		return maxScroll > 0 && x >= barLeft - 3
			&& x <= barLeft + UiTheme.SCROLLBAR_WIDTH + 3 && y >= barTop
			&& y <= barBottom;
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		MouseButtonEvent design = toDesign(event);
		int dx = (int)Math.floor(design.x());
		int dy = (int)Math.floor(design.y());
		if(event.buttonInfo()
			.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
			&& overScrollbar(dx, dy))
		{
			if(dy >= barThumbY && dy <= barThumbY + barThumbHeight)
			{
				draggingBar = true;
				barGrabOffset = dy - barThumbY;
			}else
			{
				jumpScrollTo(dy, barThumbHeight / 2);
			}
			return true;
		}
		if(inContentRegion(dy))
		{
			if(onContentClick(dx - CONTENT_X,
				dy - CONTENT_Y + (int)Math.round(scroll), doubleClick))
				return true;
			return super.mouseClicked(design, doubleClick);
		}
		// Rows that scrolled under the header or footer must not take clicks.
		return dispatchWithoutContent(design, doubleClick);
	}
	
	private boolean dispatchWithoutContent(MouseButtonEvent design,
		boolean doubleClick)
	{
		// A footer action can rebuild the screen during dispatch. Restore the
		// old
		// generation rather than indexing into the new, potentially longer
		// list.
		List<AbstractWidget> dispatched = new ArrayList<>(contentWidgets);
		boolean[] restore = new boolean[dispatched.size()];
		for(int i = 0; i < dispatched.size(); i++)
		{
			restore[i] = dispatched.get(i).visible;
			dispatched.get(i).visible = false;
		}
		try
		{
			return super.mouseClicked(design, doubleClick);
		}finally
		{
			for(int i = 0; i < dispatched.size(); i++)
				dispatched.get(i).visible = restore[i];
		}
	}
	
	private void jumpScrollTo(int designY, int grabOffset)
	{
		if(maxScroll <= 0)
			return;
		int travel = Math.max(1, barBottom - barTop - barThumbHeight);
		int thumbTop =
			Mth.clamp(designY - grabOffset, barTop, barBottom - barThumbHeight);
		double ratio = (thumbTop - barTop) / (double)travel;
		scrollBy(ratio * maxScroll - scroll);
	}
	
	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX,
		double dragY)
	{
		if(draggingBar)
		{
			jumpScrollTo(designY(event.y()), barGrabOffset);
			return true;
		}
		return super.mouseDragged(toDesign(event), dragX / scale,
			dragY / scale);
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent event)
	{
		if(draggingBar && event.buttonInfo()
			.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT)
		{
			draggingBar = false;
			return true;
		}
		return super.mouseReleased(toDesign(event));
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX,
		double scrollY)
	{
		int designY = designY(mouseY);
		if(maxScroll > 0 && inContentRegion(designY) && scrollY != 0)
		{
			scrollBy(-scrollY * WHEEL_UNITS);
			return true;
		}
		return super.mouseScrolled(designX(mouseX), designY, scrollX, scrollY);
	}
}
