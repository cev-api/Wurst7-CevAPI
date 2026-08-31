/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.modern;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Component;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.RenderUtils;
import org.lwjgl.glfw.GLFW;

/** Modern controls backed directly by Wurst's real Setting instances. */
public final class ModernSettingComponent extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final int SLIDER_RAIL_HEIGHT = 3;
	private static final int SLIDER_VERTICAL_GAP = 4;
	private static final int SLIDER_RAIL_BOTTOM_INSET = 4;
	private final Setting setting;
	private boolean dragging;
	
	public ModernSettingComponent(Setting setting)
	{
		this.setting = setting;
		setHeight(getDefaultHeight());
	}
	
	public boolean isSlider()
	{
		return setting instanceof SliderSetting;
	}
	
	public static boolean supports(Setting setting)
	{
		return setting instanceof CheckboxSetting
			|| setting instanceof SliderSetting;
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int button,
		MouseButtonEvent context)
	{
		if(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
		{
			setting.resetToDefault();
			return;
		}
		if(button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		if(setting instanceof CheckboxSetting checkbox)
			checkbox.setChecked(!checkbox.isChecked());
		else if(setting instanceof SliderSetting)
		{
			dragging = true;
			updateSlider((int)mouseX);
		}
	}
	
	private void updateSlider(int mouseX)
	{
		if(!(setting instanceof SliderSetting slider) || !dragging)
			return;
		if(!GUI.isLeftMouseButtonPressed())
		{
			dragging = false;
			return;
		}
		int left = getX() + 6;
		int right = getX() + getWidth() - getRightPadding();
		double percent = Math.max(0,
			Math.min(1, (mouseX - left) / (double)Math.max(1, right - left)));
		slider.setValue(slider.getMinimum() + slider.getRange() * percent);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		updateSlider(mouseX);
		int x1 = getX();
		int x2 = x1 + getWidth();
		int y1 = getY();
		int y2 = y1 + getHeight();
		boolean hovering = isHovering(mouseX, mouseY);
		float opacity = GUI.getOpacity();
		int background = RenderUtils.toIntColor(GUI.getBgColor(), opacity);
		int accent = RenderUtils.toIntColor(GUI.getAcColor(), opacity);
		context.fill(x1, y1, x2, y2, hovering ? accent : background);
		int labelY = setting instanceof SliderSetting
			? Math.round(y1 + (2 + MC.font.lineHeight + SLIDER_VERTICAL_GAP
				- MC.font.lineHeight) / 2F)
			: Math.round(y1 + (getHeight() - MC.font.lineHeight) / 2F);
		context.text(MC.font, setting.getName(), x1 + 8, labelY,
			GUI.getTxtColor(), false);
		if(hovering)
			GUI.setTooltip(setting.getWrappedDescription(200));
		if(setting instanceof CheckboxSetting checkbox)
			renderSwitch(context, x2 - 32 - getScrollbarGap(),
				y1 + (getHeight() - 12) / 2, checkbox.isChecked());
		else if(setting instanceof SliderSetting slider)
			renderSlider(context, slider, x1 + 6, x2 - getRightPadding(),
				y1 + 2 + MC.font.lineHeight + SLIDER_VERTICAL_GAP);
	}
	
	private void renderSwitch(GuiGraphicsExtractor context, int x, int y,
		boolean checked)
	{
		context.fill(x, y, x + 25, y + 12,
			checked ? RenderUtils.toIntColor(GUI.getEnabledHackColor(),
				GUI.getOpacity()) : getOffTrackColor());
		int knob = checked ? x + 16 : x + 3;
		context.fill(knob, y + 3, knob + 6, y + 9, GUI.getTxtColor());
	}
	
	private void renderSlider(GuiGraphicsExtractor context,
		SliderSetting slider, int left, int right, int y)
	{
		context.fill(left, y - 1, right, y + 2, RenderUtils
			.toIntColor(GUI.getDropdownButtonColor(), GUI.getOpacity()));
		int knob = left + (int)((right - left) * slider.getPercentage());
		context.fill(left, y - 1, knob, y + 2,
			RenderUtils.toIntColor(GUI.getAcColor(), GUI.getOpacity()));
		context.fill(knob - 1, y - 2, knob + 2, y + 2, GUI.getTxtColor());
		String value = slider.getValueString();
		int valueY = Math.round(getY() + (2 + MC.font.lineHeight
			+ SLIDER_VERTICAL_GAP - MC.font.lineHeight) / 2F);
		context.text(MC.font, value, right - MC.font.width(value), valueY,
			GUI.getTxtColor(), false);
	}
	
	private int getScrollbarGap()
	{
		return getParent() != null && getParent().isScrollingEnabled() ? 6 : 0;
	}
	
	private int getRightPadding()
	{
		return 8 + getScrollbarGap();
	}
	
	private int getOffTrackColor()
	{
		float[] base = GUI.getBgColor();
		return RenderUtils.toIntColor(
			new float[]{base[0] * 0.75F, base[1] * 0.75F, base[2] * 0.75F}, 1F);
	}
	
	@Override
	public int getDefaultWidth()
	{
		return 200;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return GUI.getModernRowHeight();
	}
}
