/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils.ui;

import net.wurstclient.uiutils.UiUtilsSettings;
import net.wurstclient.uiutils.UiUtilsScannerTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

/**
 * Compact dark palette and flat drawing primitives shared by every modern
 * UI-Utils screen. The look follows the Wurst7 "modern" ClickGUI: flat fills,
 * one
 * pixel borders, a single accent colour and small rows, so a whole screen fits
 * inside a normally GUI-scaled window.
 */
public final class UiTheme
{
	private static final ThreadLocal<Float> ACTIVE_TEXT_SCALE =
		ThreadLocal.withInitial(() -> 1F);
	private static final ThreadLocal<Boolean> PIXEL_SNAP_TEXT =
		ThreadLocal.withInitial(() -> false);
	
	/** Row metrics. One row is one button or one setting. */
	public static final int ROW_HEIGHT = 20;
	public static final int ROW_HEIGHT_COMPACT = 15;
	public static final int GAP = 3;
	public static final int PAD = 7;
	public static final int HEADER_HEIGHT = 20;
	public static final int FOOTER_HEIGHT = 28;
	public static final int SCROLLBAR_WIDTH = 3;
	/** Kept for call sites that predate the flat style. */
	public static final int RADIUS = 0;
	
	/** The world stays visible behind the panel, so the scrim is light. */
	public static final int SCRIM = 0x60000000;
	public static final int WINDOW = 0xE6121216;
	public static final int SURFACE = 0xE6121216;
	public static final int SURFACE_HEADER = 0xF218181D;
	public static final int SURFACE_FOOTER = 0xF2141418;
	public static final int SURFACE_ROW = 0x1AFFFFFF;
	public static final int SURFACE_ROW_HOVER = 0x2EFFFFFF;
	public static final int SURFACE_ROW_ACTIVE = 0x554A90E2;
	public static final int SURFACE_SUNK = 0xB00C0C0F;
	/**
	 * Outline colour. Kept bright enough to read against both the panel and a
	 * control's own fill: a very low alpha made outlines look absent where a
	 * widget's fill is close to the panel background.
	 */
	public static final int BORDER = 0x4DFFFFFF;
	public static final int BORDER_SOFT = 0x1FFFFFFF;
	public static final int SHADOW = 0x80000000;
	public static final int ACCENT = 0xFF4A90E2;
	public static final int TEXT = 0xFFE8E8EC;
	public static final int TEXT_DIM = 0xFF9BA1A8;
	public static final int TEXT_MUTED = 0xFF6E747C;
	public static final int OK = 0xFF5BC46B;
	public static final int WARN = 0xFFE0A33B;
	public static final int DANGER = 0xFFD9534F;
	
	private UiTheme()
	{}
	
	/** Accent colour, taken from the user's configured button colour. */
	public static int accent()
	{
		return UiUtilsScannerTheme.color("accent",
			0xFF000000 | (UiUtilsSettings.get().uiButtonColor & 0xFFFFFF));
	}
	
	/** Text colour used on top of accent-filled surfaces. */
	public static int accentText()
	{
		return UiUtilsScannerTheme.color("text",
			0xFF000000 | (UiUtilsSettings.get().uiButtonTextColor & 0xFFFFFF));
	}
	
	/**
	 * Manual UI scale as a factor, or 0 when the screen should pick the largest
	 * scale that still fits the window.
	 */
	public static int textColor()
	{
		return UiUtilsScannerTheme.color("text", TEXT);
	}
	
	public static int dimTextColor()
	{
		return UiUtilsScannerTheme.color("text", TEXT_DIM) == TEXT_DIM
			? TEXT_DIM : scaleRgb(textColor(), 0.67F);
	}
	
	public static int mutedTextColor()
	{
		return UiUtilsScannerTheme.color("text", TEXT_MUTED) == TEXT_MUTED
			? TEXT_MUTED : scaleRgb(textColor(), 0.45F);
	}
	
	public static int windowColor()
	{
		return UiUtilsScannerTheme.color("window", WINDOW);
	}
	
	public static int headerColor()
	{
		return UiUtilsScannerTheme.color("header", SURFACE_HEADER);
	}
	
	public static int footerColor()
	{
		return UiUtilsScannerTheme.color("window", SURFACE_FOOTER);
	}
	
	public static int inputColor()
	{
		return UiUtilsScannerTheme.color("input", SURFACE_SUNK);
	}
	
	public static int borderColor()
	{
		return UiUtilsScannerTheme.color("border", BORDER);
	}
	
	public static int softBorderColor()
	{
		return UiUtilsScannerTheme.color("border", BORDER_SOFT);
	}
	
	public static float forcedScale()
	{
		int percent = UiUtilsSettings.get().uiScalePercent;
		return percent <= 0 ? 0F : percent / 100F;
	}
	
	/**
	 * The one UI scale used by everything drawn over a screen: the button
	 * panel,
	 * the overlay panels and every UI-Utils window. Deriving them all from the
	 * window size is what keeps them looking like parts of the same interface.
	 * A manual setting wins, but callers still clamp the result so it fits.
	 */
	public static float screenScale(int guiWidth, int guiHeight)
	{
		float forced = forcedScale();
		if(forced > 0F)
			return clampScale(forced);
		// Auto is the stable 100% baseline. Let each screen fit or scroll its
		// own
		// content instead of making large displays inflate some UI layers more
		// than others.
		return 1F;
	}
	
	/** Smallest scale the UI will use before text becomes unreadable. */
	public static final float MIN_SCALE = 0.4F;
	public static final float MAX_SCALE = 1.5F;
	
	public static float clampScale(float value)
	{
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
	}
	
	/**
	 * Snaps a scale down to a step and never above {@code limit}, so a fitted
	 * scale can never grow into an overflow again.
	 */
	public static float snapScaleDown(float value, float limit)
	{
		float step = 0.25F;
		float snapped = (float)(Math.floor(value / step) * step);
		float cap = (float)(Math.floor(limit / step) * step);
		if(snapped > cap)
			snapped = cap;
		return clampScale(snapped);
	}
	
	/**
	 * Accent at a given strength, for tints that must not overpower the text.
	 */
	public static int accentTint(int alpha)
	{
		return withAlpha(accent(), alpha);
	}
	
	// ------------------------------------------------------------------
	// Flat drawing primitives
	// ------------------------------------------------------------------
	
	/**
	 * Fills a rectangle. Panels are flat, so the corner radius is accepted only
	 * to let older call sites keep compiling.
	 */
	public static void fillRound(GuiGraphics graphics, int x, int y, int w,
		int h, int radius, int color)
	{
		if(w <= 0 || h <= 0)
			return;
		graphics.fill(x, y, x + w, y + h, color);
	}
	
	public static void strokeRound(GuiGraphics graphics, int x, int y, int w,
		int h, int radius, int color)
	{
		if(w <= 0 || h <= 0)
			return;
		graphics.renderOutline(x, y, w, h, color);
	}
	
	public static void hLine(GuiGraphics graphics, int x, int y, int width,
		int color)
	{
		graphics.fill(x, y, x + width, y + 1, color);
	}
	
	public static void vLine(GuiGraphics graphics, int x, int y, int height,
		int color)
	{
		graphics.fill(x, y, x + 1, y + height, color);
	}
	
	/** One pixel border inside the given rectangle. */
	public static void border(GuiGraphics graphics, int x, int y, int w, int h,
		int color)
	{
		graphics.fill(x, y, x + w, y + 1, color);
		graphics.fill(x, y + h - 1, x + w, y + h, color);
		graphics.fill(x, y + 1, x + 1, y + h - 1, color);
		graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}
	
	/** Switch shaped like the Wurst7 modern toggle: track plus a small knob. */
	public static void switchPill(GuiGraphics graphics, int x, int y, int width,
		int height, boolean on, int onColor, int offColor, int knobColor)
	{
		graphics.fill(x, y, x + width, y + height, on ? onColor : offColor);
		int knobWidth = Math.max(4, width / 4);
		int knobInset = Math.max(1, height / 4);
		int knobHeight = Math.max(3, height - knobInset * 2);
		int knobX = on ? x + width - knobWidth - knobInset : x + knobInset;
		graphics.fill(knobX, y + knobInset, knobX + knobWidth,
			y + knobInset + knobHeight, knobColor);
	}
	
	/** The "off" track colour, a dimmed version of the panel background. */
	public static int offTrack()
	{
		return 0xFF2A2A30;
	}
	
	// ------------------------------------------------------------------
	// Text helpers
	// ------------------------------------------------------------------
	
	public static void text(GuiGraphics graphics, Font font, String value,
		int x, int y, int color)
	{
		float textScale = ACTIVE_TEXT_SCALE.get();
		if(Math.abs(textScale - 1F) < 0.001F && !PIXEL_SNAP_TEXT.get())
		{
			graphics.drawString(font, value, x, y, color, false);
			return;
		}
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		if(Math.abs(textScale - 1F) >= 0.001F)
			pose.scale(1F / textScale, 1F / textScale);
		snapTextOrigin(pose);
		graphics.drawString(font, value, 0, 0, color, false);
		pose.popMatrix();
	}
	
	public static void text(GuiGraphics graphics, Font font,
		FormattedCharSequence value, int x, int y, int color)
	{
		float textScale = ACTIVE_TEXT_SCALE.get();
		if(Math.abs(textScale - 1F) < 0.001F && !PIXEL_SNAP_TEXT.get())
		{
			graphics.drawString(font, value, x, y, color);
			return;
		}
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		if(Math.abs(textScale - 1F) >= 0.001F)
			pose.scale(1F / textScale, 1F / textScale);
		snapTextOrigin(pose);
		graphics.drawString(font, value, 0, 0, color);
		pose.popMatrix();
	}
	
	public static void textCentered(GuiGraphics graphics, Font font,
		String value, int centerX, int y, int color)
	{
		float textScale = ACTIVE_TEXT_SCALE.get();
		if(Math.abs(textScale - 1F) < 0.001F && !PIXEL_SNAP_TEXT.get())
		{
			graphics.drawCenteredString(font, value, centerX, y, color);
			return;
		}
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(centerX, y);
		if(Math.abs(textScale - 1F) >= 0.001F)
			pose.scale(1F / textScale, 1F / textScale);
		snapTextOrigin(pose);
		graphics.drawCenteredString(font, value, 0, 0, color);
		pose.popMatrix();
	}
	
	public static void textRight(GuiGraphics graphics, Font font, String value,
		int rightX, int y, int color)
	{
		int x =
			rightX - Math.round(font.width(value) / ACTIVE_TEXT_SCALE.get());
		text(graphics, font, value, x, y, color);
	}
	
	/**
	 * Keeps font glyphs at normal screen size inside a scaled modern screen.
	 */
	public static float pushTextScale(float scale)
	{
		float previous = ACTIVE_TEXT_SCALE.get();
		ACTIVE_TEXT_SCALE.set(scale > 0F ? scale : 1F);
		return previous;
	}
	
	public static void popTextScale(float previous)
	{
		ACTIVE_TEXT_SCALE.set(previous > 0F ? previous : 1F);
	}
	
	/**
	 * Enables whole-pixel glyph placement for a screen rendered with fractional
	 * layout scaling.
	 */
	public static boolean pushPixelSnapText()
	{
		boolean previous = PIXEL_SNAP_TEXT.get();
		PIXEL_SNAP_TEXT.set(true);
		return previous;
	}
	
	public static void popPixelSnapText(boolean previous)
	{
		PIXEL_SNAP_TEXT.set(previous);
	}
	
	private static void snapTextOrigin(Matrix3x2fStack pose)
	{
		if(!PIXEL_SNAP_TEXT.get())
			return;
		// The inverse text scale leaves one screen pixel per local unit.
		// Correct only
		// the origin's fractional part so glyph size stays at Minecraft's
		// native scale.
		float x = pose.m20();
		float y = pose.m21();
		pose.translate(Math.round(x) - x, Math.round(y) - y);
	}
	
	public static int textWidth(int designWidth)
	{
		return Math.max(1,
			(int)Math.floor(designWidth * ACTIVE_TEXT_SCALE.get()));
	}
	
	public static int designTextWidth(int screenTextWidth)
	{
		return Math.max(0,
			(int)Math.ceil(screenTextWidth / ACTIVE_TEXT_SCALE.get()));
	}
	
	public static int lineHeight(Font font)
	{
		return Math.max(1,
			Math.round(font.lineHeight / ACTIVE_TEXT_SCALE.get()));
	}
	
	/**
	 * Draws text at a screen position with the given design-unit scale, scaling
	 * it
	 * about that position.
	 * <p>
	 * Callers must use this rather than pushing a scale themselves and drawing
	 * at
	 * (0, 0): a scale of exactly 1 pushes nothing, which would leave the text
	 * at the
	 * screen origin instead of at its own position.
	 */
	public static void textScaled(GuiGraphics graphics, Font font, String value,
		int x, int y, float scale, int color)
	{
		float effectiveScale = scale / ACTIVE_TEXT_SCALE.get();
		if(Math.abs(effectiveScale - 1F) < 0.001F)
		{
			text(graphics, font, value, x, y, color);
			return;
		}
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(effectiveScale, effectiveScale);
		graphics.drawString(font, value, 0, 0, color, false);
		pose.popMatrix();
	}
	
	/** Baseline y that vertically centres one line of text inside a row. */
	public static int textY(Font font, int rowY, int rowHeight)
	{
		return rowY + (rowHeight - lineHeight(font) + 1) / 2;
	}
	
	/** Clips styled text without losing bold or other component formatting. */
	public static FormattedCharSequence ellipsize(Font font, Component value,
		int maxWidth)
	{
		int width = maxWidth <= 0 ? 0 : textWidth(maxWidth);
		if(font.width(value) <= width)
			return value.getVisualOrderText();
		Component suffix =
			Component.literal(font.width("...") <= width ? "..." : "")
				.setStyle(value.getStyle());
		var clipped =
			font.substrByWidth(value, Math.max(0, width - font.width(suffix)));
		return net.minecraft.locale.Language.getInstance()
			.getVisualOrder(net.minecraft.network.chat.FormattedText
				.composite(clipped, suffix));
	}
	
	public static String ellipsize(Font font, String value, int maxWidth)
	{
		if(value == null)
			return "";
		if(maxWidth <= 0)
			return "";
		maxWidth = textWidth(maxWidth);
		if(maxWidth <= 0)
			return "";
		if(font.width(value) <= maxWidth)
			return value;
		String suffix = font.width("...") <= maxWidth ? "..." : "";
		return font.plainSubstrByWidth(value,
			Math.max(1, maxWidth - font.width(suffix))) + suffix;
	}
	
	// ------------------------------------------------------------------
	// Colour helpers
	// ------------------------------------------------------------------
	
	public static int mix(int from, int to, float t)
	{
		float f = Math.max(0F, Math.min(1F, t));
		int a = channel((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, f);
		int r = channel((from >> 16) & 0xFF, (to >> 16) & 0xFF, f);
		int g = channel((from >> 8) & 0xFF, (to >> 8) & 0xFF, f);
		int b = channel(from & 0xFF, to & 0xFF, f);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
	
	public static int scaleRgb(int argb, float factor)
	{
		int r = clamp((int)(((argb >> 16) & 0xFF) * factor));
		int g = clamp((int)(((argb >> 8) & 0xFF) * factor));
		int b = clamp((int)((argb & 0xFF) * factor));
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}
	
	public static int withAlpha(int argb, int alpha)
	{
		return (clamp(alpha) << 24) | (argb & 0xFFFFFF);
	}
	
	/** Colour for a state label such as ON/OFF, ALLOW/DROP or True/False. */
	public static int stateColor(boolean enabled)
	{
		return enabled ? OK : TEXT_MUTED;
	}
	
	/**
	 * Pushes a scale transform around a widget that draws itself in design
	 * units
	 * while it is positioned in raw GUI pixels, which is how the overlay panels
	 * place their buttons. Returns whether the caller must pop the pose.
	 */
	public static boolean pushWidgetScale(GuiGraphics graphics, int x, int y,
		float scale)
	{
		if(scale <= 0F || Math.abs(scale - 1F) < 0.001F)
			return false;
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		return true;
	}
	
	public static void popWidgetScale(GuiGraphics graphics, boolean pushed)
	{
		if(pushed)
			graphics.pose().popMatrix();
	}
	
	/**
	 * Local-space width for a widget drawing through {@link #pushWidgetScale}.
	 */
	public static int localSize(int size, float scale, boolean pushed)
	{
		return pushed ? Math.max(1, Math.round(size / scale)) : size;
	}
	
	private static int channel(int from, int to, float t)
	{
		return clamp(Math.round(from + (to - from) * t));
	}
	
	private static int clamp(int value)
	{
		return Math.max(0, Math.min(255, value));
	}
}
