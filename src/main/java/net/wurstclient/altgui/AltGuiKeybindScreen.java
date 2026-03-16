/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altgui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.AltGuiHack;
import net.wurstclient.keybinds.Keybind;
import net.wurstclient.keybinds.PossibleKeybind;

public final class AltGuiKeybindScreen extends Screen
{
	private enum Mode
	{
		VIEW,
		ADD_SELECT,
		ADD_PRESS_KEY,
		REMOVE_SELECT
	}
	
	private final Screen returnScreen;
	private final Feature feature;
	private Mode mode = Mode.VIEW;
	private int scroll;
	
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int listX1;
	private int listY1;
	private int listX2;
	private int listY2;
	
	private PossibleKeybind selectedAddCommand;
	
	public AltGuiKeybindScreen(Screen returnScreen, Feature feature)
	{
		super(Component.literal("AltGUI Keybinds"));
		this.returnScreen = returnScreen;
		this.feature = feature;
	}
	
	@Override
	protected void init()
	{
		rebuildLayout();
	}
	
	@Override
	public void resize(int width, int height)
	{
		super.resize(width, height);
		rebuildLayout();
	}
	
	private void rebuildLayout()
	{
		AltGuiHack cfg = cfg();
		int targetW = (int)(width * cfg.getWidthPercent());
		int targetH = (int)(height * cfg.getHeightPercent());
		int edgePadding = 8;
		panelW = Math.max(360, Math.min(width - edgePadding * 2, targetW));
		panelH = Math.max(240, Math.min(height - edgePadding * 2, targetH));
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		
		listX1 = panelX + 10;
		listY1 = panelY + 42;
		listX2 = panelX + panelW - 10;
		listY2 = panelY + panelH - 34;
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(mode == Mode.ADD_PRESS_KEY)
		{
			String selectedKey = InputConstants.getKey(context).getName();
			if(!"key.keyboard.unknown".equals(selectedKey)
				&& selectedAddCommand != null)
			{
				addBinding(selectedKey, selectedAddCommand.getCommand());
				mode = Mode.VIEW;
				selectedAddCommand = null;
				return true;
			}
		}
		
		if(context.key() == GLFW.GLFW_KEY_ESCAPE
			|| context.key() == GLFW.GLFW_KEY_BACKSPACE)
		{
			if(mode != Mode.VIEW)
			{
				mode = Mode.VIEW;
				selectedAddCommand = null;
				return true;
			}
			
			minecraft.setScreen(returnScreen);
			return true;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		double mouseX = context.x();
		double mouseY = context.y();
		int button = context.button();
		
		if(button == GLFW.GLFW_MOUSE_BUTTON_4)
		{
			minecraft.setScreen(returnScreen);
			return true;
		}
		
		if(button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return super.mouseClicked(context, doubleClick);
		
		int backX1 = panelX + 10;
		int backX2 = backX1 + 64;
		int backY1 = panelY + panelH - 24;
		int backY2 = backY1 + 14;
		
		int addX2 = panelX + panelW - 10;
		int addX1 = addX2 - 60;
		int removeX2 = addX1 - 6;
		int removeX1 = removeX2 - 74;
		int btnY1 = backY1;
		int btnY2 = backY2;
		
		if(isInside(mouseX, mouseY, backX1, backY1, backX2, backY2))
		{
			minecraft.setScreen(returnScreen);
			return true;
		}
		
		if(mode == Mode.VIEW)
		{
			if(isInside(mouseX, mouseY, addX1, btnY1, addX2, btnY2))
			{
				mode = Mode.ADD_SELECT;
				scroll = 0;
				return true;
			}
			
			if(isInside(mouseX, mouseY, removeX1, btnY1, removeX2, btnY2))
			{
				if(!getExistingBindings().isEmpty())
				{
					mode = Mode.REMOVE_SELECT;
					scroll = 0;
				}
				return true;
			}
		}
		
		List<Entry> entries = switch(mode)
		{
			case ADD_SELECT -> buildAddEntries();
			case REMOVE_SELECT, VIEW -> buildExistingEntries();
			default -> List.of();
		};
		
		if(mode == Mode.ADD_PRESS_KEY || entries.isEmpty())
			return true;
		
		int entryHeight = 20;
		int visibleRows = Math.max(1, (listY2 - listY1) / entryHeight);
		int maxScroll = Math.max(0, entries.size() - visibleRows);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		
		for(int i = 0; i < visibleRows; i++)
		{
			int index = i + scroll;
			if(index >= entries.size())
				break;
			
			int y1 = listY1 + i * entryHeight;
			int y2 = y1 + entryHeight - 2;
			if(!isInside(mouseX, mouseY, listX1 + 2, y1, listX2 - 2, y2))
				continue;
			
			Entry entry = entries.get(index);
			if(mode == Mode.ADD_SELECT)
			{
				selectedAddCommand = entry.keybind();
				mode = Mode.ADD_PRESS_KEY;
			}else if(mode == Mode.REMOVE_SELECT)
			{
				removeBinding(entry.key(), entry.keybind().getCommand());
				mode = Mode.VIEW;
			}
			return true;
		}
		
		return true;
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount)
	{
		if(!isInside(mouseX, mouseY, listX1, listY1, listX2, listY2))
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount,
				verticalAmount);
		
		List<Entry> entries = switch(mode)
		{
			case ADD_SELECT -> buildAddEntries();
			case REMOVE_SELECT, VIEW -> buildExistingEntries();
			default -> List.of();
		};
		
		int entryHeight = 20;
		int visibleRows = Math.max(1, (listY2 - listY1) / entryHeight);
		int maxScroll = Math.max(0, entries.size() - visibleRows);
		scroll -= (int)Math.signum(verticalAmount);
		if(scroll < 0)
			scroll = 0;
		if(scroll > maxScroll)
			scroll = maxScroll;
		
		return true;
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		Font font = minecraft.font;
		AltGuiHack cfg = cfg();
		int bg =
			withAlpha(cfg.getBackgroundColor(), cfg.getBackgroundOpacity());
		int panel = withAlpha(cfg.getPanelColor(), cfg.getUiOpacity());
		int header = withAlpha(cfg.getPanelLightColor(), cfg.getUiOpacity());
		
		context.fill(0, 0, width, height, bg);
		context.fill(panelX, panelY, panelX + panelW, panelY + panelH, panel);
		context.fill(panelX, panelY, panelX + panelW, panelY + 26, header);
		
		context.drawString(font, "Keybinds: " + feature.getName(), panelX + 10,
			panelY + 9, cfg.getTextColor(), false);
		
		String subtitle = switch(mode)
		{
			case VIEW -> "View current bindings, add new ones, or remove existing ones.";
			case ADD_SELECT -> "Select what this keybind should do.";
			case ADD_PRESS_KEY -> "Press the key that should trigger this command.";
			case REMOVE_SELECT -> "Select a keybind to remove.";
		};
		context.drawString(font, subtitle, panelX + 10, panelY + 30,
			cfg.getMutedTextColor(), false);
		
		renderEntries(context, font, mouseX, mouseY);
		renderFooter(context, font, mouseX, mouseY);
	}
	
	private void renderEntries(GuiGraphics context, Font font, int mouseX,
		int mouseY)
	{
		AltGuiHack cfg = cfg();
		context.fill(listX1, listY1, listX2, listY2,
			withAlpha(cfg.getPanelLightColor(), 0.55F));
		
		List<Entry> entries = switch(mode)
		{
			case ADD_SELECT -> buildAddEntries();
			case REMOVE_SELECT, VIEW -> buildExistingEntries();
			default -> List.of();
		};
		
		if(mode == Mode.ADD_PRESS_KEY)
		{
			String selected = selectedAddCommand == null ? "(none)"
				: selectedAddCommand.getDescription() + " ["
					+ selectedAddCommand.getCommand() + "]";
			context.drawCenteredString(font, "Selected: " + selected,
				(listX1 + listX2) / 2, listY1 + 12, cfg.getTextColor());
			context.drawCenteredString(font,
				"Press a key now (Esc to cancel add mode).",
				(listX1 + listX2) / 2, listY1 + 28, cfg.getMutedTextColor());
			return;
		}
		
		if(entries.isEmpty())
		{
			String emptyText =
				mode == Mode.ADD_SELECT ? "No keybind actions available."
					: "No keybinds bound for this feature.";
			context.drawCenteredString(font, emptyText, (listX1 + listX2) / 2,
				listY1 + 12, cfg.getMutedTextColor());
			return;
		}
		
		int entryHeight = 20;
		int visibleRows = Math.max(1, (listY2 - listY1) / entryHeight);
		int maxScroll = Math.max(0, entries.size() - visibleRows);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		
		for(int i = 0; i < visibleRows; i++)
		{
			int index = i + scroll;
			if(index >= entries.size())
				break;
			
			Entry entry = entries.get(index);
			int y1 = listY1 + i * entryHeight;
			int y2 = y1 + entryHeight - 2;
			boolean hovered =
				isInside(mouseX, mouseY, listX1 + 2, y1, listX2 - 2, y2);
			int rowColor = hovered ? withAlpha(cfg.getAccentColor(), 0.26F)
				: withAlpha(cfg.getPanelColor(), 0.86F);
			context.fill(listX1 + 2, y1, listX2 - 2, y2, rowColor);
			
			String left;
			String right;
			if(mode == Mode.ADD_SELECT)
			{
				left = entry.keybind().getDescription();
				right = entry.keybind().getCommand();
			}else
			{
				left = entry.key().replace("key.keyboard.", "") + ": "
					+ entry.keybind().getDescription();
				right = entry.keybind().getCommand();
			}
			
			int rowX1 = listX1 + 6;
			int rowX2 = listX2 - 6;
			int rowW = Math.max(1, rowX2 - rowX1);
			int columnGap = 10;
			int leftW = Math.max(80, (int)(rowW * 0.56));
			int leftX1 = rowX1;
			int leftX2 = Math.min(rowX2 - columnGap, leftX1 + leftW);
			int rightX1 = Math.min(rowX2, leftX2 + columnGap);
			int rightX2 = rowX2;
			
			drawStaticStringInBox(context, font, left, leftX1, y1, leftX2, y2,
				cfg.getTextColor(), 0);
			drawMarqueeStringInBox(context, font, right, rightX1, y1, rightX2,
				y2, cfg.getMutedTextColor(), 0);
		}
	}
	
	private void drawStaticStringInBox(GuiGraphics context, Font font,
		String text, int x1, int y1, int x2, int y2, int color, int padX)
	{
		if(text == null || text.isEmpty())
			return;
		
		int innerX1 = x1 + Math.max(0, padX);
		int innerX2 = x2 - Math.max(0, padX);
		if(innerX2 <= innerX1)
			return;
		
		int textY = y1 + Math.max(0, ((y2 - y1) - font.lineHeight) / 2);
		context.enableScissor(innerX1, y1, innerX2, y2);
		context.drawString(font, text, innerX1, textY, color, false);
		context.disableScissor();
	}
	
	private void drawMarqueeStringInBox(GuiGraphics context, Font font,
		String text, int x1, int y1, int x2, int y2, int color, int padX)
	{
		if(text == null || text.isEmpty())
			return;
		
		int innerX1 = x1 + Math.max(0, padX);
		int innerX2 = x2 - Math.max(0, padX);
		if(innerX2 <= innerX1)
			return;
		
		int innerW = innerX2 - innerX1;
		int textW = Math.max(1, font.width(text));
		int textY = y1 + Math.max(0, ((y2 - y1) - font.lineHeight) / 2);
		int textX = innerX1;
		
		if(textW > innerW)
		{
			int overflow = textW - innerW;
			int ticks = minecraft != null && minecraft.gui != null
				? minecraft.gui.getGuiTicks()
				: (int)(System.currentTimeMillis() / 50L);
			float cycle = 220F;
			float phase = (ticks % (int)cycle) / cycle;
			float pingPong = phase <= 0.5F ? phase * 2F : (1F - phase) * 2F;
			textX = innerX1 - Math.round(overflow * pingPong);
		}
		
		context.enableScissor(innerX1, y1, innerX2, y2);
		context.drawString(font, text, textX, textY, color, false);
		context.disableScissor();
	}
	
	private void renderFooter(GuiGraphics context, Font font, int mouseX,
		int mouseY)
	{
		AltGuiHack cfg = cfg();
		
		int backX1 = panelX + 10;
		int backX2 = backX1 + 64;
		int backY1 = panelY + panelH - 24;
		int backY2 = backY1 + 14;
		
		int addX2 = panelX + panelW - 10;
		int addX1 = addX2 - 60;
		int removeX2 = addX1 - 6;
		int removeX1 = removeX2 - 74;
		
		drawFooterButton(context, font, "Back", backX1, backY1, backX2, backY2,
			isInside(mouseX, mouseY, backX1, backY1, backX2, backY2), true);
		
		if(mode == Mode.VIEW)
		{
			drawFooterButton(context, font, "Add", addX1, backY1, addX2, backY2,
				isInside(mouseX, mouseY, addX1, backY1, addX2, backY2), true);
			
			boolean canRemove = !getExistingBindings().isEmpty();
			drawFooterButton(context, font, "Remove", removeX1, backY1,
				removeX2, backY2,
				isInside(mouseX, mouseY, removeX1, backY1, removeX2, backY2),
				canRemove);
		}else
		{
			String modeLabel = switch(mode)
			{
				case ADD_SELECT -> "Add Mode";
				case ADD_PRESS_KEY -> "Press Key";
				case REMOVE_SELECT -> "Remove Mode";
				default -> "";
			};
			context.drawCenteredString(font, modeLabel, (removeX1 + addX2) / 2,
				backY1 + 3, cfg.getMutedTextColor());
		}
	}
	
	private void drawFooterButton(GuiGraphics context, Font font, String label,
		int x1, int y1, int x2, int y2, boolean hovered, boolean active)
	{
		AltGuiHack cfg = cfg();
		int base = active ? cfg.getAccentColor() : cfg.getDisabledColor();
		int fill = hovered && active ? withAlpha(base, 0.45F)
			: withAlpha(base, active ? 0.33F : 0.2F);
		context.fill(x1, y1, x2, y2, fill);
		context.drawCenteredString(font, label, (x1 + x2) / 2, y1 + 3,
			active ? cfg.getTextColor() : cfg.getMutedTextColor());
	}
	
	private void addBinding(String key, String command)
	{
		String newCommands = command;
		String oldCommands =
			WurstClient.INSTANCE.getKeybinds().getCommands(key);
		if(oldCommands != null)
			newCommands = oldCommands + " ; " + newCommands;
		
		WurstClient.INSTANCE.getKeybinds().add(key, newCommands);
		WurstClient.INSTANCE.getNavigator().addPreference(feature.getName());
	}
	
	private void removeBinding(String key, String command)
	{
		String oldCommands =
			WurstClient.INSTANCE.getKeybinds().getCommands(key);
		if(oldCommands == null)
			return;
		
		ArrayList<String> commands =
			new ArrayList<>(List.of(oldCommands.replace(";", "\u00a7")
				.replace("\u00a7\u00a7", ";").split("\u00a7")));
		for(int i = 0; i < commands.size(); i++)
			commands.set(i, commands.get(i).trim());
		
		while(commands.remove(command))
		{}
		
		if(commands.isEmpty())
			WurstClient.INSTANCE.getKeybinds().remove(key);
		else
		{
			String joined = String.join("\u00a7", commands)
				.replace(";", "\u00a7\u00a7").replace("\u00a7", ";");
			WurstClient.INSTANCE.getKeybinds().add(key, joined);
		}
		
		WurstClient.INSTANCE.getNavigator().addPreference(feature.getName());
	}
	
	private List<Entry> buildAddEntries()
	{
		ArrayList<Entry> out = new ArrayList<>();
		for(PossibleKeybind keybind : getPossibleCommands())
			out.add(new Entry("", keybind));
		return out;
	}
	
	private List<Entry> buildExistingEntries()
	{
		ArrayList<Entry> out = new ArrayList<>();
		for(var entry : getExistingBindings().entrySet())
			out.add(new Entry(entry.getKey(), entry.getValue()));
		return out;
	}
	
	private Set<PossibleKeybind> getPossibleCommands()
	{
		LinkedHashSet<PossibleKeybind> possible =
			new LinkedHashSet<>(feature.getPossibleKeybinds());
		if(feature instanceof Hack)
			possible.add(new PossibleKeybind(feature.getName(),
				"Toggle " + feature.getName()));
		return possible;
	}
	
	private java.util.TreeMap<String, PossibleKeybind> getExistingBindings()
	{
		java.util.TreeMap<String, PossibleKeybind> existing =
			new java.util.TreeMap<>();
		java.util.TreeMap<String, String> possibleByCommand =
			new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for(PossibleKeybind pkb : getPossibleCommands())
			possibleByCommand.put(pkb.getCommand(), pkb.getDescription());
		
		for(Keybind keybind : WurstClient.INSTANCE.getKeybinds()
			.getAllKeybinds())
		{
			String commands = keybind.getCommands().replace(";", "\u00a7")
				.replace("\u00a7\u00a7", ";");
			for(String raw : commands.split("\u00a7"))
			{
				String command = raw.trim();
				String description = possibleByCommand.get(command);
				if(description != null)
				{
					existing.put(keybind.getKey(),
						new PossibleKeybind(command, description));
					continue;
				}
				
				if(feature instanceof Hack
					&& command.equalsIgnoreCase(feature.getName()))
					existing.put(keybind.getKey(), new PossibleKeybind(command,
						"Toggle " + feature.getName()));
			}
		}
		
		return existing;
	}
	
	private AltGuiHack cfg()
	{
		return WurstClient.INSTANCE.getHax().altGuiHack;
	}
	
	private boolean isInside(double mouseX, double mouseY, int x1, int y1,
		int x2, int y2)
	{
		return mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2;
	}
	
	private static int withAlpha(int color, float alpha)
	{
		int a = Math.max(0, Math.min(255, (int)(alpha * 255)));
		return (color & 0x00FFFFFF) | (a << 24);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	private record Entry(String key, PossibleKeybind keybind)
	{}
}
