/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.awt.Color;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.util.ColorUtils;
import net.wurstclient.util.WurstColors;

public final class EditColorScreen extends Screen
{
	private final Screen prevScreen;
	private final ColorSetting colorSetting;
	private Color color;
	
	private EditBox hexValueField;
	private EditBox redValueField;
	private EditBox greenValueField;
	private EditBox blueValueField;
	
	private Button doneButton;
	private int svX;
	private int svY;
	private int svW;
	private int svH;
	private int hueX;
	private int hueY;
	private int hueW;
	private int hueH;
	private int fieldsX;
	private int fieldsY;
	
	private float hue;
	private float saturation;
	private float brightness;
	private boolean draggingSv;
	private boolean draggingHue;
	
	private boolean ignoreChanges;
	
	public EditColorScreen(Screen prevScreen, ColorSetting colorSetting)
	{
		super(Component.literal(""));
		this.prevScreen = prevScreen;
		this.colorSetting = colorSetting;
		color = colorSetting.getColor();
	}
	
	@Override
	public void init()
	{
		Font tr = minecraft.font;
		updateHsvFromColor();
		
		svW = 196;
		svH = 128;
		svX = width / 2 - 128;
		svY = 30;
		hueW = 12;
		hueH = svH;
		hueX = svX + svW + 8;
		hueY = svY;
		
		fieldsX = svX;
		fieldsY = svY + svH + 14;
		
		hexValueField = new EditBox(tr, fieldsX + 12, fieldsY, 94, 20,
			Component.literal(""));
		hexValueField.setValue(ColorUtils.toHex(color).substring(1));
		hexValueField.setMaxLength(6);
		hexValueField.setResponder(s -> updateColor(true));
		
		// RGB fields
		redValueField = new EditBox(tr, fieldsX + 12, fieldsY + 35, 50, 20,
			Component.literal(""));
		redValueField.setValue("" + color.getRed());
		redValueField.setMaxLength(3);
		redValueField.setResponder(s -> updateColor(false));
		
		greenValueField = new EditBox(tr, fieldsX + 87, fieldsY + 35, 50, 20,
			Component.literal(""));
		greenValueField.setValue("" + color.getGreen());
		greenValueField.setMaxLength(3);
		greenValueField.setResponder(s -> updateColor(false));
		
		blueValueField = new EditBox(tr, fieldsX + 162, fieldsY + 35, 50, 20,
			Component.literal(""));
		blueValueField.setValue("" + color.getBlue());
		blueValueField.setMaxLength(3);
		blueValueField.setResponder(s -> updateColor(false));
		
		addWidget(hexValueField);
		addWidget(redValueField);
		addWidget(greenValueField);
		addWidget(blueValueField);
		
		setFocused(hexValueField);
		hexValueField.setFocused(true);
		hexValueField.setCursorPosition(0);
		hexValueField.setHighlightPos(6);
		
		doneButton = Button.builder(Component.literal("Done"), b -> done())
			.bounds(fieldsX, height - 30, 212, 20).build();
		addRenderableWidget(doneButton);
	}
	
	private void updateColor(boolean hex)
	{
		if(ignoreChanges)
			return;
		
		Color newColor;
		
		if(hex)
			newColor = ColorUtils.tryParseHex("#" + hexValueField.getValue());
		else
			newColor = ColorUtils.tryParseRGB(redValueField.getValue(),
				greenValueField.getValue(), blueValueField.getValue());
		
		if(newColor == null || newColor.equals(color))
			return;
		
		color = newColor;
		updateHsvFromColor();
		ignoreChanges = true;
		hexValueField.setValue(ColorUtils.toHex(color).substring(1));
		redValueField.setValue("" + color.getRed());
		greenValueField.setValue("" + color.getGreen());
		blueValueField.setValue("" + color.getBlue());
		ignoreChanges = false;
	}
	
	private void updateHsvFromColor()
	{
		float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(),
			color.getBlue(), null);
		hue = hsb[0];
		saturation = hsb[1];
		brightness = hsb[2];
	}
	
	private void updateColorFromHsv()
	{
		int rgb = Color.HSBtoRGB(hue, saturation, brightness);
		color = new Color(rgb);
		ignoreChanges = true;
		hexValueField.setValue(ColorUtils.toHex(color).substring(1));
		redValueField.setValue("" + color.getRed());
		greenValueField.setValue("" + color.getGreen());
		blueValueField.setValue("" + color.getBlue());
		ignoreChanges = false;
	}
	
	private void done()
	{
		colorSetting.setColor(color);
		minecraft.setScreen(prevScreen);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		Font tr = minecraft.font;
		
		context.drawCenteredString(minecraft.font, "HSV Color Picker",
			width / 2, 16, WurstColors.VERY_LIGHT_GRAY);
		
		renderSvPicker(context);
		renderHueSlider(context);
		renderPickerHandles(context);
		
		// RGB letters
		context.drawString(tr, "#", fieldsX, fieldsY + 6,
			WurstColors.VERY_LIGHT_GRAY, false);
		context.drawString(tr, "R:", fieldsX, fieldsY + 6 + 35,
			CommonColors.RED, false);
		context.drawString(tr, "G:", fieldsX + 75, fieldsY + 6 + 35,
			CommonColors.GREEN, false);
		context.drawString(tr, "B:", fieldsX + 150, fieldsY + 6 + 35,
			CommonColors.BLUE, false);
		hexValueField.render(context, mouseX, mouseY, partialTicks);
		redValueField.render(context, mouseX, mouseY, partialTicks);
		greenValueField.render(context, mouseX, mouseY, partialTicks);
		blueValueField.render(context, mouseX, mouseY, partialTicks);
		
		// Centered long preview bar below RGB row.
		int boxWidth = 200;
		int boxHeight = 12;
		int boxX = fieldsX + 12;
		int boxY = fieldsY + 62;
		context.fill(boxX - 1, boxY - 1, boxX + boxWidth + 1,
			boxY + boxHeight + 1, CommonColors.LIGHT_GRAY);
		context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight,
			color.getRGB());
		
		for(Renderable drawable : renderables)
			drawable.render(context, mouseX, mouseY, partialTicks);
	}
	
	private void renderSvPicker(GuiGraphics context)
	{
		context.fill(svX - 1, svY - 1, svX + svW + 1, svY + svH + 1,
			CommonColors.LIGHT_GRAY);
		int hueRgb = Color.HSBtoRGB(hue, 1F, 1F) & 0x00FFFFFF;
		
		for(int x = 0; x < svW; x++)
		{
			float t = svW <= 1 ? 0 : x / (float)(svW - 1);
			int topColor = blendRgb(0xFFFFFF, hueRgb, t) | 0xFF000000;
			context.fillGradient(svX + x, svY, svX + x + 1, svY + svH, topColor,
				0xFF000000);
		}
	}
	
	private void renderHueSlider(GuiGraphics context)
	{
		context.fill(hueX - 1, hueY - 1, hueX + hueW + 1, hueY + hueH + 1,
			CommonColors.LIGHT_GRAY);
		for(int y = 0; y < hueH; y++)
		{
			float h = hueH <= 1 ? 0 : y / (float)(hueH - 1);
			int rgb = Color.HSBtoRGB(h, 1F, 1F) | 0xFF000000;
			context.fill(hueX, hueY + y, hueX + hueW, hueY + y + 1, rgb);
		}
	}
	
	private void renderPickerHandles(GuiGraphics context)
	{
		int cx = svX + Math.round(saturation * (svW - 1));
		int cy = svY + Math.round((1F - brightness) * (svH - 1));
		context.fill(cx - 3, cy, cx + 4, cy + 1, 0xFFFFFFFF);
		context.fill(cx, cy - 3, cx + 1, cy + 4, 0xFFFFFFFF);
		context.fill(cx - 2, cy, cx + 3, cy + 1, 0xFF000000);
		context.fill(cx, cy - 2, cx + 1, cy + 3, 0xFF000000);
		
		int hy = hueY + Math.round(hue * (hueH - 1));
		context.fill(hueX - 3, hy - 1, hueX + hueW + 3, hy + 1, 0xFFFFFFFF);
		context.fill(hueX - 2, hy, hueX + hueW + 2, hy + 1, 0xFF000000);
	}
	
	private static int blendRgb(int rgb1, int rgb2, float t)
	{
		t = Math.max(0F, Math.min(1F, t));
		int r1 = (rgb1 >> 16) & 0xFF;
		int g1 = (rgb1 >> 8) & 0xFF;
		int b1 = rgb1 & 0xFF;
		int r2 = (rgb2 >> 16) & 0xFF;
		int g2 = (rgb2 >> 8) & 0xFF;
		int b2 = rgb2 & 0xFF;
		int r = Math.round(r1 + (r2 - r1) * t);
		int g = Math.round(g1 + (g2 - g1) * t);
		int b = Math.round(b1 + (b2 - b1) * t);
		return (r << 16) | (g << 8) | b;
	}
	
	private void updateSvFromMouse(double mouseX, double mouseY)
	{
		float s = (float)((mouseX - svX) / Math.max(1, svW - 1));
		float v = 1F - (float)((mouseY - svY) / Math.max(1, svH - 1));
		saturation = Math.max(0F, Math.min(1F, s));
		brightness = Math.max(0F, Math.min(1F, v));
		updateColorFromHsv();
	}
	
	private void updateHueFromMouse(double mouseY)
	{
		float h = (float)((mouseY - hueY) / Math.max(1, hueH - 1));
		hue = Math.max(0F, Math.min(1F, h));
		updateColorFromHsv();
	}
	
	@Override
	public boolean mouseDragged(MouseButtonEvent context, double dragX,
		double dragY)
	{
		if(draggingSv)
		{
			updateSvFromMouse(context.x(), context.y());
			return true;
		}
		if(draggingHue)
		{
			updateHueFromMouse(context.y());
			return true;
		}
		
		return super.mouseDragged(context, dragX, dragY);
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent context)
	{
		draggingSv = false;
		draggingHue = false;
		return super.mouseReleased(context);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		double mouseX = context.x();
		double mouseY = context.y();
		
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT)
		{
			if(mouseX >= svX && mouseX <= svX + svW && mouseY >= svY
				&& mouseY <= svY + svH)
			{
				draggingSv = true;
				updateSvFromMouse(mouseX, mouseY);
				return true;
			}
			
			if(mouseX >= hueX && mouseX <= hueX + hueW && mouseY >= hueY
				&& mouseY <= hueY + hueH)
			{
				draggingHue = true;
				updateHueFromMouse(mouseY);
				return true;
			}
		}
		
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public void resize(int width, int height)
	{
		String hex = hexValueField.getValue();
		String r = redValueField.getValue();
		String g = greenValueField.getValue();
		String b = blueValueField.getValue();
		
		init(width, height);
		
		hexValueField.setValue(hex);
		redValueField.setValue(r);
		greenValueField.setValue(g);
		blueValueField.setValue(b);
		updateColor(true);
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		switch(context.key())
		{
			case GLFW.GLFW_KEY_ENTER:
			done();
			break;
			
			case GLFW.GLFW_KEY_ESCAPE:
			minecraft.setScreen(prevScreen);
			break;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
}
