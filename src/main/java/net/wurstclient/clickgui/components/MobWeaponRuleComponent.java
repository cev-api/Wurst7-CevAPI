/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.ClickGuiIcons;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.Popup;
import net.wurstclient.settings.MobWeaponRuleSetting;
import net.wurstclient.settings.MobWeaponRuleSetting.MobOption;
import net.wurstclient.settings.MobWeaponRuleSetting.WeaponCategory;
import net.wurstclient.util.GuiRenderStateHelper;
import net.wurstclient.util.RenderUtils;

public final class MobWeaponRuleComponent extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = MC.font;
	private static final int BOX_HEIGHT = 11;
	private static final int ARROW_SIZE = 11;
	private static final int BOX_GAP = 4;
	private static final int PADDING = 2;
	
	private final MobWeaponRuleSetting setting;
	
	private OptionPopup<MobOption> mobPopup;
	private OptionPopup<WeaponCategory> weaponPopup;
	
	public MobWeaponRuleComponent(MobWeaponRuleSetting setting)
	{
		this.setting = Objects.requireNonNull(setting);
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton)
	{
		Box mobBox = getMobBox();
		Box weaponBox = getWeaponBox();
		
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
		{
			if(mobBox.contains(mouseX, mouseY))
				toggleMobPopup(mobBox);
			else if(weaponBox.contains(mouseX, mouseY))
				toggleWeaponPopup(weaponBox);
			
			return;
		}
		
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
		{
			if(mobBox.contains(mouseX, mouseY))
				setting.resetMob();
			else if(weaponBox.contains(mouseX, mouseY))
				setting.resetWeapon();
		}
	}
	
	private void toggleMobPopup(Box mobBox)
	{
		if(mobPopup != null && !mobPopup.isClosing())
		{
			mobPopup.close();
			mobPopup = null;
			return;
		}
		
		int labelWidth =
			getMaxWidth(setting.getMobOptions(), MobOption::displayName);
		int popupWidth = labelWidth + 15;
		int anchorX = (int)(mobBox.x2 - getX() - popupWidth);
		int anchorY = (int)(mobBox.y2 - getY());
		
		mobPopup = new OptionPopup<>(this, setting.getMobOptions(),
			setting::getSelectedMob, setting::setSelectedMob,
			MobOption::displayName, labelWidth, anchorX, anchorY);
		GUI.addPopup(mobPopup);
	}
	
	private void toggleWeaponPopup(Box weaponBox)
	{
		if(weaponPopup != null && !weaponPopup.isClosing())
		{
			weaponPopup.close();
			weaponPopup = null;
			return;
		}
		
		List<WeaponCategory> values = List.of(WeaponCategory.values());
		int labelWidth = getMaxWidth(values, WeaponCategory::toString);
		int popupWidth = labelWidth + 15;
		int anchorX = (int)(weaponBox.x2 - getX() - popupWidth);
		int anchorY = (int)(weaponBox.y2 - getY());
		
		weaponPopup = new OptionPopup<>(this, values,
			setting::getSelectedWeapon, setting::setSelectedWeapon,
			WeaponCategory::toString, labelWidth, anchorX, anchorY);
		GUI.addPopup(weaponPopup);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		int x1 = getX();
		int y1 = getY();
		context.fill(x1, y1, x1 + getWidth(), y1 + getHeight(),
			RenderUtils.toIntColor(GUI.getBgColor(), GUI.getOpacity()));
		
		int txtColor = GUI.getTxtColor();
		
		context.drawString(TR, setting.getName(), x1, y1, txtColor, false);
		
		Box mobBox = getMobBox();
		Box weaponBox = getWeaponBox();
		
		boolean mobOpen = mobPopup != null && !mobPopup.isClosing();
		drawDropdown(context, mobBox, setting.getSelectedMob().displayName(),
			mouseX, mouseY, "Pick which mob should trigger this rule.", false,
			mobOpen);
		
		String weaponLabel = setting.getSelectedWeapon().toString();
		boolean disabled = setting.getSelectedWeapon() == WeaponCategory.NONE;
		drawDropdown(context, weaponBox, weaponLabel, mouseX, mouseY,
			disabled ? "Right-click to reset. Select a tool/weapon to enable."
				: "Select which tool/weapon should be used when targeting.",
			disabled, weaponPopup != null && !weaponPopup.isClosing());
	}
	
	private void drawDropdown(GuiGraphics context, Box box, String value,
		int mouseX, int mouseY, String tooltip, boolean dimmed,
		boolean expanded)
	{
		int arrowX1 = box.x2 - ARROW_SIZE;
		int arrowX2 = box.x2;
		
		boolean hovering = box.contains(mouseX, mouseY);
		if(hovering)
			GUI.setTooltip(tooltip);
		
		// background
		context.fill(box.x1, box.y1, arrowX1, (int)(box.y1 + BOX_HEIGHT),
			RenderUtils.toIntColor(GUI.getBgColor(),
				GUI.getOpacity() * (hovering ? 1.25F : 1F)));
		
		// arrow box
		context.fill(arrowX1, box.y1, arrowX2, (int)(box.y1 + BOX_HEIGHT),
			RenderUtils.toIntColor(GUI.getBgColor(),
				GUI.getOpacity() * (hovering ? 1.5F : 1F)));
		
		GuiRenderStateHelper.up(context);
		
		// outlines
		int outlineColor = RenderUtils.toIntColor(GUI.getAcColor(), 0.5F);
		RenderUtils.drawBorder2D(context, box.x1, box.y1, box.x2,
			(int)(box.y1 + BOX_HEIGHT), outlineColor);
		RenderUtils.drawLine2D(context, arrowX1, box.y1, arrowX1,
			(int)(box.y1 + BOX_HEIGHT), outlineColor);
		
		// arrow
		ClickGuiIcons.drawMinimizeArrow(context, arrowX1, box.y1 + 0.5F,
			arrowX2, box.y1 + BOX_HEIGHT - 0.5F, hovering, !expanded);
		
		// value
		int color = dimmed ? 0xFFAAAAAA : GUI.getTxtColor();
		context.drawString(TR, value, box.x1 + 2, box.y1 + 2, color, false);
	}
	
	private Box getMobBox()
	{
		int width = getWidth() - PADDING * 2 - BOX_GAP;
		int boxWidth = width / 2;
		int x1 = getX() + PADDING;
		int y = getY() + TR.lineHeight + 2;
		return new Box(x1, y, x1 + boxWidth, y + BOX_HEIGHT);
	}
	
	private Box getWeaponBox()
	{
		Box mobBox = getMobBox();
		int x1 = mobBox.x2 + BOX_GAP;
		return new Box(x1, mobBox.y1, x1 + (mobBox.x2 - mobBox.x1),
			mobBox.y1 + BOX_HEIGHT);
	}
	
	private <T> int getMaxWidth(List<T> values, Function<T, String> labelGetter)
	{
		int max = 0;
		for(T value : values)
			max = Math.max(max, TR.width(labelGetter.apply(value)));
		
		return max;
	}
	
	@Override
	public int getDefaultWidth()
	{
		return TR.width(setting.getName()) + 40;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return TR.lineHeight + BOX_HEIGHT + 3;
	}
	
	private record Box(int x1, int y1, int x2, int y2)
	{
		boolean contains(double x, double y)
		{
			return x >= x1 && x < x2 && y >= y1 && y < y2;
		}
	}
	
	private static final class OptionPopup<T> extends Popup
	{
		private static final int MAX_VISIBLE_ROWS = 8;
		
		private final List<T> options;
		private final Supplier<T> selectedSupplier;
		private final Consumer<T> onSelect;
		private final Function<T, String> labelGetter;
		private final int textWidth;
		private final int totalRows;
		private final int visibleRows;
		private int scrollOffset;
		
		public OptionPopup(Component owner, List<T> options,
			Supplier<T> selectedSupplier, Consumer<T> onSelect,
			Function<T, String> labelGetter, int textWidth, int anchorX,
			int anchorY)
		{
			super(owner);
			this.options = options;
			this.selectedSupplier = selectedSupplier;
			this.onSelect = onSelect;
			this.labelGetter = labelGetter;
			this.textWidth = textWidth;
			
			totalRows = Math.max(0, options.size() - 1);
			visibleRows = Math.min(MAX_VISIBLE_ROWS, totalRows);
			
			setWidth(textWidth + 15);
			setHeight(getDefaultHeight());
			setX(anchorX);
			setY(anchorY);
		}
		
		@Override
		public void handleMouseClick(int mouseX, int mouseY, int mouseButton)
		{
			if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT || visibleRows <= 0)
				return;
			
			int localX = mouseX - getX();
			int localY = mouseY - getY();
			if(localX < 0 || localX >= getWidth() || localY < 0
				|| localY >= getHeight())
				return;
			
			int row = localY / BOX_HEIGHT;
			T option = getOptionAt(row + scrollOffset);
			if(option == null)
				return;
			
			onSelect.accept(option);
			close();
		}
		
		@Override
		public boolean handleMouseScroll(int mouseX, int mouseY, double delta)
		{
			if(totalRows <= visibleRows || visibleRows <= 0)
				return false;
			
			int direction = (int)Math.signum(delta);
			if(direction == 0)
				return false;
			
			scrollOffset -= direction;
			clampScroll();
			return true;
		}
		
		@Override
		public void render(GuiGraphics context, int mouseX, int mouseY)
		{
			if(visibleRows <= 0)
				return;
			
			clampScroll();
			
			int x1 = getX();
			int x2 = x1 + getWidth();
			int y1 = getY();
			int y2 = y1 + getHeight();
			
			RenderUtils.drawBorder2D(context, x1, y1, x2, y2,
				RenderUtils.toIntColor(GUI.getAcColor(), 0.5F));
			
			int drawn = 0;
			int skipped = 0;
			for(T option : options)
			{
				if(option.equals(selectedSupplier.get()))
					continue;
				
				if(skipped++ < scrollOffset)
					continue;
				
				if(drawn >= visibleRows)
					break;
				
				int currentY = y1 + drawn * BOX_HEIGHT;
				int nextY = currentY + BOX_HEIGHT;
				boolean hovering = mouseY >= currentY && mouseY < nextY
					&& mouseX >= x1 && mouseX < x2;
				
				context.fill(x1, currentY, x2, nextY,
					RenderUtils.toIntColor(GUI.getBgColor(),
						GUI.getOpacity() * (hovering ? 1.5F : 1F)));
				
				GuiRenderStateHelper.up(context);
				context.drawString(TR, labelGetter.apply(option), x1 + 2,
					currentY + 2, GUI.getTxtColor(), false);
				
				drawn++;
			}
		}
		
		private void clampScroll()
		{
			int maxOffset = Math.max(0, totalRows - visibleRows);
			if(scrollOffset < 0)
				scrollOffset = 0;
			else if(scrollOffset > maxOffset)
				scrollOffset = maxOffset;
		}
		
		private T getOptionAt(int index)
		{
			if(index < 0)
				return null;
			
			int skipped = 0;
			for(T option : options)
			{
				if(option.equals(selectedSupplier.get()))
					continue;
				
				if(skipped == index)
					return option;
				
				skipped++;
			}
			
			return null;
		}
		
		@Override
		public int getDefaultWidth()
		{
			return textWidth + 15;
		}
		
		@Override
		public int getDefaultHeight()
		{
			return visibleRows * BOX_HEIGHT;
		}
	}
	
}
