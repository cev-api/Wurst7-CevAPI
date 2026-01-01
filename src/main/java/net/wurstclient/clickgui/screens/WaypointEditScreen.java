/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.waypoints.Waypoint;
import net.wurstclient.waypoints.WaypointDimension;
import net.wurstclient.waypoints.WaypointsManager;

public final class WaypointEditScreen extends Screen
{
	private final Screen prev;
	private final WaypointsManager manager;
	private final WaypointsScreen listScreen;
	private final Waypoint waypoint;
	// removed unused: private final boolean isNew;
	// cached layout positions
	private int fieldsBaseX;
	private int fieldsWidth;
	private boolean narrow;
	private int yName;
	private int yXYZ;
	// removed unused: private int yDim;
	private int yToggles;
	// removed unused: private int yIcon;
	private int yColor;
	private int xXYZ1, xXYZ2, xXYZ3;
	private int yYField, yZField;
	private boolean compactMode = false; // when true, show only name +
											// Save/Cancel
	
	private EditBox nameField;
	private EditBox xField;
	private EditBox yField;
	private EditBox zField;
	private Button colorButton;
	private ColorSetting colorSetting;
	private int alphaPercent = 100; // 1..100 persisted across picker
	// Draft values to preserve user input across color picker navigation
	private String draftName;
	private String draftX;
	private String draftY;
	private String draftZ;
	
	private int dimIndex;
	private Button dimButton;
	// Draft storage for dimension selection when navigating to child screens
	private Integer draftDimIndex;
	
	private static final String[] ICON_KEYS =
		new String[]{"square", "circle", "triangle", "star", "diamond", "skull",
			"heart", "check", "x", "arrow_down", "sun", "snowflake"};
	private static final String[] ICONS; // display labels (symbol + name)
	static
	{
		ICONS = new String[ICON_KEYS.length];
		for(int i = 0; i < ICON_KEYS.length; i++)
			ICONS[i] = iconChar(ICON_KEYS[i]) + " " + ICON_KEYS[i];
	}
	private int iconIndex;
	private Button iconButton;
	
	private Button oppositeButton;
	private Button visibleButton;
	private Button linesButton;
	private Button beaconButton;
	
	public WaypointEditScreen(Screen prev, WaypointsManager manager,
		Waypoint waypoint, boolean isNew)
	{
		super(Component.literal("Edit Waypoint"));
		this.prev = prev;
		this.manager = manager;
		this.listScreen =
			prev instanceof WaypointsScreen ? (WaypointsScreen)prev : null;
		this.waypoint = waypoint;
		// removed: this.isNew = isNew;
	}
	
	@Override
	protected void init()
	{
		int cw = Math.max(220, Math.min(this.width - 40, 360));
		narrow = cw < 340;
		int x = this.width / 2 - cw / 2;
		int y = 36;
		fieldsBaseX = x;
		fieldsWidth = cw;
		// Compact mode: collapse non-essential controls and only show name +
		// Save/Cancel when very narrow.
		compactMode = cw < 260;
		
		// Name (always present)
		yName = y;
		nameField =
			new EditBox(minecraft.font, x, y, cw, 20, Component.literal(""));
		String baseName = waypoint.getName() == null ? "" : waypoint.getName();
		nameField.setValue(draftName != null ? draftName : baseName);
		addRenderableWidget(nameField);
		setFocused(nameField);
		// increased gap to avoid XYZ labels overlapping name field
		y += 44;
		
		if(!compactMode)
		{
			// Position fields (responsive)
			BlockPos p = waypoint.getPos();
			yXYZ = y;
			int gap = 8;
			int colW = (cw - gap * 2) / 3;
			xXYZ1 = x;
			xXYZ2 = x + colW + gap;
			xXYZ3 = x + (colW + gap) * 2;
			xField = new EditBox(minecraft.font, xXYZ1, y, colW, 20,
				Component.literal(""));
			xField
				.setValue(draftX != null ? draftX : Integer.toString(p.getX()));
			addRenderableWidget(xField);
			yField = new EditBox(minecraft.font, xXYZ2, narrow ? y + 28 : y,
				colW, 20, Component.literal(""));
			yField
				.setValue(draftY != null ? draftY : Integer.toString(p.getY()));
			addRenderableWidget(yField);
			zField = new EditBox(minecraft.font, xXYZ3, narrow ? y + 56 : y,
				colW, 20, Component.literal(""));
			zField
				.setValue(draftZ != null ? draftZ : Integer.toString(p.getZ()));
			addRenderableWidget(zField);
			// Track individual field Y for labels
			yYField = narrow ? y + 28 : y;
			yZField = narrow ? y + 56 : y;
			y += narrow ? 84 : 28;
			
			// Dimension cycle
			WaypointDimension[] dims = WaypointDimension.values();
			dimIndex = 0;
			for(int i = 0; i < dims.length; i++)
				if(dims[i] == waypoint.getDimension())
				{
					dimIndex = i;
					break;
				}
			// If we have a draft index (from opening a child screen), restore
			// it
			if(draftDimIndex != null)
			{
				dimIndex = draftDimIndex;
				draftDimIndex = null;
			}
			dimButton = Button.builder(
				Component.literal("Dimension: " + dims[dimIndex].name()), b -> {
					dimIndex = (dimIndex + 1) % dims.length;
					b.setMessage(Component
						.literal("Dimension: " + dims[dimIndex].name()));
				}).bounds(x, y, cw, 20).build();
			addRenderableWidget(dimButton);
			y += 28;
			
			// Opposite / Visible toggles + Lines
			yToggles = y;
			int halfGap = 10;
			int halfW = (cw - halfGap) / 2;
			oppositeButton =
				Button
					.builder(
						Component.literal(
							buttonLabel("Opposite", waypoint.isOpposite())),
						b -> {
							waypoint.setOpposite(!waypoint.isOpposite());
							b.setMessage(Component.literal(buttonLabel(
								"Opposite", waypoint.isOpposite())));
						})
					.bounds(x, y, halfW, 20).build();
			addRenderableWidget(oppositeButton);
			
			visibleButton = Button.builder(
				Component.literal(buttonLabel("Visible", waypoint.isVisible())),
				b -> {
					waypoint.setVisible(!waypoint.isVisible());
					b.setMessage(Component
						.literal(buttonLabel("Visible", waypoint.isVisible())));
				}).bounds(x + halfW + halfGap, y, halfW, 20).build();
			addRenderableWidget(visibleButton);
			y += 28;
			
			// Reserve space for opposite preview text, then Lines/Beacon row
			y += 16;
			int toggleWidth = (cw - halfGap) / 2;
			linesButton = Button.builder(
				Component.literal(buttonLabel("Lines", waypoint.isLines())),
				b -> {
					waypoint.setLines(!waypoint.isLines());
					b.setMessage(Component
						.literal(buttonLabel("Lines", waypoint.isLines())));
				}).bounds(x, y, toggleWidth, 20).build();
			addRenderableWidget(linesButton);
			beaconButton = Button.builder(
				Component.literal(beaconLabel(waypoint.getBeaconMode())), b -> {
					Waypoint.BeaconMode next =
						nextBeaconMode(waypoint.getBeaconMode());
					waypoint.setBeaconMode(next);
					b.setMessage(Component.literal(beaconLabel(next)));
				}).bounds(x + toggleWidth + halfGap, y, toggleWidth, 20)
				.build();
			addRenderableWidget(beaconButton);
			y += 28;
			
			// Icon selector
			iconIndex = 0;
			String currentIcon = waypoint.getIcon();
			for(int i = 0; i < ICON_KEYS.length; i++)
				if(ICON_KEYS[i].equalsIgnoreCase(currentIcon))
				{
					iconIndex = i;
					break;
				}
			iconButton = Button
				.builder(Component.literal("Icon: " + ICONS[iconIndex]), b -> {
					iconIndex = (iconIndex + 1) % ICONS.length;
					b.setMessage(
						Component.literal("Icon: " + ICONS[iconIndex]));
				}).bounds(x, y, cw, 20).build();
			addRenderableWidget(iconButton);
			y += 28;
			// extra spacing before color row
			y += 8;
			
			// Color editor + transparency slider
			yColor = y;
			if(colorSetting == null)
				colorSetting =
					new ColorSetting("Waypoint Color", new java.awt.Color(
						(waypoint.getColor() & 0x00FFFFFF) | 0xFF000000, true));
			if(alphaPercent == 100)
			{
				int a = (waypoint.getColor() >>> 24) & 0xFF;
				if(a > 0)
					alphaPercent = Math.max(1,
						Math.min(100, (int)Math.round(a / 255.0 * 100)));
			}
			colorButton = Button.builder(
				Component.literal(
					"Pick color (#" + toHex6(colorSetting.getColorI()) + ")"),
				b -> {
					draftName = nameField.getValue();
					draftX = xField.getValue();
					draftY = yField.getValue();
					draftZ = zField.getValue();
					// Preserve selected dimension index so it isn't lost when
					// the child color screen re-initializes this screen.
					draftDimIndex = dimIndex;
					minecraft
						.setScreen(new EditColorScreen(this, colorSetting));
				}).bounds(x, y, cw - 24, 20).build();
			addRenderableWidget(colorButton);
			y += 28;
			addRenderableWidget(
				new net.minecraft.client.gui.components.AbstractSliderButton(x,
					y, fieldsWidth, 20,
					Component.literal("Transparency: " + alphaPercent + "%"),
					(alphaPercent - 1) / 99.0)
				{
					@Override
					protected void updateMessage()
					{
						int val = 1 + (int)Math.round(value * 99.0);
						alphaPercent = Math.max(1, Math.min(100, val));
						setMessage(Component
							.literal("Transparency: " + alphaPercent + "%"));
					}
					
					@Override
					protected void applyValue()
					{
						int val = 1 + (int)Math.round(value * 99.0);
						alphaPercent = Math.max(1, Math.min(100, val));
					}
				});
			y += 28;
			
			// Use player pos & delete buttons
			addRenderableWidget(
				Button.builder(Component.literal("Use player pos"),
					b -> usePlayerPos()).bounds(x, y, halfW, 20).build());
			addRenderableWidget(
				Button.builder(Component.literal("Delete"), b -> doDelete())
					.bounds(x + halfW + halfGap, y, halfW, 20).build());
			y += 28;
		}
		
		// Always add Save/Cancel anchored at bottom
		int halfGap = 10;
		int halfW = (fieldsWidth - halfGap) / 2;
		addRenderableWidget(
			Button.builder(Component.literal("Save"), b -> saveAndBack())
				.bounds(fieldsBaseX, height - 52, halfW, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Cancel"),
				b -> minecraft.setScreen(prev))
			.bounds(fieldsBaseX + halfW + halfGap, height - 52, halfW, 20)
			.build());
	}
	
	@Override
	public void resize(int width, int height)
	{
		// Preserve current edits before re-initializing layout for new size
		if(nameField != null)
		{
			draftName = nameField.getValue();
			draftX = xField.getValue();
			draftY = yField.getValue();
			draftZ = zField.getValue();
			// Preserve dimension selection across resize/child screens
			draftDimIndex = dimIndex;
		}
		super.resize(width, height);
	}
	
	private static String buttonLabel(String name, boolean on)
	{
		return name + ": " + (on ? "ON" : "OFF");
	}
	
	private String beaconLabel(Waypoint.BeaconMode mode)
	{
		Waypoint.BeaconMode safe =
			mode == null ? Waypoint.BeaconMode.OFF : mode;
		String state = switch(safe)
		{
			case OFF -> "OFF";
			case SOLID -> "ON";
			case ESP -> "ESP";
		};
		return "Beacon: " + state;
	}
	
	private Waypoint.BeaconMode nextBeaconMode(Waypoint.BeaconMode mode)
	{
		Waypoint.BeaconMode safe =
			mode == null ? Waypoint.BeaconMode.OFF : mode;
		return switch(safe)
		{
			case OFF -> Waypoint.BeaconMode.SOLID;
			case SOLID -> Waypoint.BeaconMode.ESP;
			case ESP -> Waypoint.BeaconMode.OFF;
		};
	}
	
	private void usePlayerPos()
	{
		if(minecraft.player == null)
			return;
		BlockPos p = BlockPos.containing(minecraft.player.getX(),
			minecraft.player.getY(), minecraft.player.getZ());
		xField.setValue(Integer.toString(p.getX()));
		yField.setValue(Integer.toString(p.getY()));
		zField.setValue(Integer.toString(p.getZ()));
	}
	
	private void doDelete()
	{
		manager.remove(waypoint);
		if(listScreen != null)
			listScreen.saveNow();
		minecraft.setScreen(prev);
	}
	
	private void saveAndBack()
	{
		// Name
		waypoint.setName(nameField.getValue());
		
		// Position
		if(xField != null && yField != null && zField != null)
		{
			try
			{
				int x = Integer.parseInt(xField.getValue());
				int y = Integer.parseInt(yField.getValue());
				int z = Integer.parseInt(zField.getValue());
				waypoint.setPos(new BlockPos(x, y, z));
			}catch(Exception ignored)
			{}
		}
		
		// Dimension
		waypoint.setDimension(WaypointDimension.values()[dimIndex]);
		
		// Icon
		waypoint.setIcon(ICON_KEYS[iconIndex]);
		
		// Color + transparency
		int saveAlpha = (int)Math
			.round(Math.max(1, Math.min(100, alphaPercent)) / 100.0 * 255);
		waypoint.setColor(colorSetting.getColorI(saveAlpha));
		
		manager.addOrUpdate(waypoint);
		if(listScreen != null)
			listScreen.saveNow();
		minecraft.setScreen(prev);
	}
	
	private String toHex6(int argb)
	{
		int rgb = argb & 0x00FFFFFF;
		String s = Integer.toHexString(rgb).toUpperCase();
		while(s.length() < 6)
			s = "0" + s;
		return s;
	}
	
	private static String iconChar(String icon)
	{
		if(icon == null)
			return "";
		switch(icon.toLowerCase())
		{
			case "square":
			return "■";
			case "circle":
			return "●";
			case "triangle":
			return "▲";
			case "triangle_down":
			return "▼";
			case "star":
			return "★";
			case "diamond":
			return "♦";
			case "skull":
			return "☠";
			case "heart":
			return "♥";
			case "check":
			return "✓";
			case "x":
			return "✗";
			case "arrow_down":
			return "↓";
			case "sun":
			return "☀";
			case "snowflake":
			return "❄";
			default:
			return "";
		}
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta)
	{
		context.fill(0, 0, this.width, this.height, 0x88000000);
		super.render(context, mouseX, mouseY, delta);
		
		// Update color button label in case it changed in child screen
		if(colorButton != null && colorSetting != null)
			colorButton.setMessage(Component.literal(
				"Pick color (#" + toHex6(colorSetting.getColorI()) + ")"));
		
		// Labels
		int x = fieldsBaseX;
		context.drawString(minecraft.font, "Name", x, yName - 18,
			CommonColors.LIGHT_GRAY, false);
		if(xField != null)
		{
			context.drawString(minecraft.font, "X", xXYZ1, yXYZ - 18,
				CommonColors.LIGHT_GRAY, false);
			context.drawString(minecraft.font, "Y", xXYZ2,
				(narrow ? yYField : yXYZ) - 18, CommonColors.LIGHT_GRAY, false);
			context.drawString(minecraft.font, "Z", xXYZ3,
				(narrow ? yZField : yXYZ) - 18, CommonColors.LIGHT_GRAY, false);
		}
		// removed explicit "Color" text label to avoid redundancy and crowding
		
		// Color preview box
		int boxX = x + fieldsWidth - 20;
		int boxY = yColor;
		int alpha = (int)Math
			.round(Math.max(1, Math.min(100, alphaPercent)) / 100.0 * 255);
		int color = colorSetting.getColorI(alpha);
		context.fill(boxX - 1, boxY - 1, boxX + 18, boxY + 18,
			CommonColors.GRAY);
		context.fill(boxX, boxY, boxX + 16, boxY + 16, color);
		
		// Opposite preview text – render below the toggles and lines rows
		String opp = oppositePreview();
		if(!opp.isEmpty())
			context.drawString(minecraft.font, opp, fieldsBaseX,
				/* directly below the opposite/visible row */
				yToggles + 28 + 8, 0xFFCCCCCC, false);
	}
	
	private String oppositePreview()
	{
		if(!waypoint.isOpposite())
			return "";
		WaypointDimension d = WaypointDimension.values()[dimIndex];
		if(d == WaypointDimension.END)
			return "Opposite has no effect in the End";
		try
		{
			int x = Integer.parseInt(xField.getValue());
			int z = Integer.parseInt(zField.getValue());
			int y = Integer.parseInt(yField.getValue());
			int ox = x;
			int oz = z;
			WaypointDimension td;
			if(d == WaypointDimension.OVERWORLD)
			{
				ox = Math.floorDiv(x, 8);
				oz = Math.floorDiv(z, 8);
				td = WaypointDimension.NETHER;
			}else // NETHER
			{
				ox = x * 8;
				oz = z * 8;
				td = WaypointDimension.OVERWORLD;
			}
			return "Opposite shows in " + td.name() + " at (" + ox + ", " + y
				+ ", " + oz + ")";
		}catch(Exception e)
		{
			return "";
		}
	}
}
