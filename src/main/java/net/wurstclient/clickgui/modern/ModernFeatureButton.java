/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.modern;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.Feature;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.Window;
import net.wurstclient.hacks.TooManyHaxHack;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import org.lwjgl.glfw.GLFW;

/** A Modern feature row with mutually exclusive highlight and switch states. */
public final class ModernFeatureButton extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font FONT = MC.font;
	private static final int ROW_HEIGHT = 22;
	private static final int SWITCH_WIDTH = 24;
	private static final int FAVORITE_WIDTH = 12;
	
	private final Feature feature;
	private final boolean hasSettings;
	private final boolean toggleable;
	private ModernSettingsWindow settingsWindow;
	
	public ModernFeatureButton(Feature feature)
	{
		this.feature = feature;
		hasSettings = !feature.getSettings().isEmpty();
		toggleable = feature instanceof net.wurstclient.hack.Hack
			&& !(feature instanceof net.wurstclient.hacks.ClickGuiHack)
			&& !(feature instanceof net.wurstclient.hacks.GlobalToggleHack);
		setWidth(getDefaultWidth());
		setHeight(GUI.getModernRowHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_MIDDLE)
		{
			toggleFavorite();
			return;
		}
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
		{
			if(hasSettings)
				openSettingsWindow();
			return;
		}
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		
		boolean rowHighlightMode = GUI.isModernEnabledRowHighlight();
		int switchX = getX() + getWidth() - SWITCH_WIDTH - 7;
		if(toggleable && (rowHighlightMode || mouseX >= switchX))
		{
			toggleFeature();
			return;
		}
		if(hasSettings)
			openSettingsWindow();
		else
			toggleFeature();
	}
	
	void toggleFeature()
	{
		TooManyHaxHack tooManyHax = WURST.getHax().tooManyHaxHack;
		if(tooManyHax.shouldBlockStarting(feature))
		{
			ChatUtils.error(feature.getName() + " is blocked by TooManyHax.");
			return;
		}
		feature.doPrimaryAction();
	}
	
	void toggleFavorite()
	{
		if(feature instanceof net.wurstclient.hack.Hack hack)
			hack.setFavorite(!hack.isFavorite());
	}
	
	void openSettingsWindow()
	{
		String title = feature.getDisplayName() + " Settings";
		Window existing = GUI.findWindowByTitle(title);
		if(existing instanceof ModernSettingsWindow && !existing.isClosing())
		{
			existing.close();
			settingsWindow = null;
			return;
		}
		settingsWindow = new ModernSettingsWindow(feature, getParent(), getY());
		GUI.addWindow(settingsWindow);
		GUI.bringWindowToFront(settingsWindow);
	}
	
	public static boolean matches(Feature feature, String query)
	{
		String needle = query.toLowerCase(java.util.Locale.ROOT);
		return feature.getDisplayName().toLowerCase(java.util.Locale.ROOT)
			.contains(needle)
			|| feature.getCategoryName().toLowerCase(java.util.Locale.ROOT)
				.contains(needle)
			|| feature.getDescription().toLowerCase(java.util.Locale.ROOT)
				.contains(needle);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		int x1 = getX();
		int x2 = x1 + getWidth();
		int y1 = getY();
		int y2 = y1 + getHeight();
		int switchX = x2 - SWITCH_WIDTH - 7;
		boolean hovering = isHovering(mouseX, mouseY);
		boolean enabled = feature.isEnabled();
		boolean rowHighlightMode = GUI.isModernEnabledRowHighlight();
		boolean fullHighlight = enabled && rowHighlightMode;
		float opacity = GUI.getOpacity();
		int background = RenderUtils.toIntColor(GUI.getBgColor(), opacity);
		int hover = RenderUtils.toIntColor(GUI.getBgColor(),
			Math.min(1F, opacity + 0.18F));
		int enabledColor =
			RenderUtils.toIntColor(GUI.getEnabledHackColor(), opacity);
		int rowColor = fullHighlight ? enabledColor : background;
		if(hovering && !fullHighlight)
			rowColor = hover;
		context.fill(x1, y1, x2, y2, rowColor);
		if(enabled && !fullHighlight)
		{
			context.fill(x1, y1, x2, y2, RenderUtils
				.toIntColor(GUI.getEnabledHackColor(), opacity * 0.18F));
			context.fill(x1, y1, x1 + 2, y2, enabledColor);
		}
		RenderUtils.drawBorder2D(context, x1, y1, x2, y2,
			RenderUtils.toIntColor(GUI.getModernHackRowBorderColor(),
				opacity * GUI.getModernHackRowBorderOpacity()));
		
		int textY = Math.round(y1 + (getHeight() - FONT.lineHeight) / 2F);
		if(toggleable && !rowHighlightMode)
		{
			float[] baseColor = GUI.getBgColor();
			int offTrack =
				RenderUtils.toIntColor(new float[]{baseColor[0] * 0.75F,
					baseColor[1] * 0.75F, baseColor[2] * 0.75F}, 1F);
			int track = enabled ? enabledColor : offTrack;
			int switchHeight = Math.min(12, Math.max(8, getHeight() - 8));
			int switchY = y1 + (getHeight() - switchHeight) / 2;
			context.fill(switchX, switchY, switchX + SWITCH_WIDTH,
				switchY + switchHeight, track);
			int knobX = enabled ? switchX + 15 : switchX + 3;
			int knobHeight = Math.max(4, switchHeight - 6);
			int knobY = switchY + (switchHeight - knobHeight) / 2;
			context.fill(knobX, knobY, knobX + 6, knobY + knobHeight,
				GUI.getTxtColor());
		}else if(!toggleable)
			context.text(FONT, "›", x2 - 12, textY, GUI.getTxtColor(), false);
		
		boolean favorite = feature instanceof net.wurstclient.hack.Hack hack
			&& hack.isFavorite();
		int nameX = x1 + 8;
		if(favorite)
		{
			context.text(FONT, "★", nameX, textY,
				RenderUtils.toIntColor(GUI.getPinButtonColor(), opacity),
				false);
			nameX += FAVORITE_WIDTH;
		}
		context.text(FONT, feature.getDisplayName(), nameX, textY,
			GUI.getTxtColor(), false);
		if(hovering)
			GUI.setTooltip(feature.getWrappedDescription(200));
	}
	
	@Override
	public int getDefaultWidth()
	{
		return Math.max(178, FONT.width(feature.getDisplayName()) + 50);
	}
	
	@Override
	public int getDefaultHeight()
	{
		return GUI.getModernRowHeight();
	}
}
