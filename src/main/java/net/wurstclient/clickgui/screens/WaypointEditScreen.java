/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.math.BlockPos;
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
	
	private TextFieldWidget nameField;
	private TextFieldWidget xField;
	private TextFieldWidget yField;
	private TextFieldWidget zField;
	private ButtonWidget colorButton;
	private ColorSetting colorSetting;
	private int alphaPercent = 100; // 1..100 persisted across picker
	// Draft values to preserve user input across color picker navigation
	private String draftName;
	private String draftX;
	private String draftY;
	private String draftZ;
	
	private int dimIndex;
	private ButtonWidget dimButton;
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
	private ButtonWidget iconButton;
	
	private ButtonWidget oppositeButton;
	private ButtonWidget visibleButton;
	private ButtonWidget linesButton;
	private ButtonWidget beaconButton;
	
	public WaypointEditScreen(Screen prev, WaypointsManager manager,
		Waypoint waypoint, boolean isNew)
	{
		super(Text.literal("Edit Waypoint"));
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
		nameField = new TextFieldWidget(client.textRenderer, x, y, cw, 20,
			Text.literal(""));
		String baseName = waypoint.getName() == null ? "" : waypoint.getName();
		nameField.setText(draftName != null ? draftName : baseName);
		addDrawableChild(nameField);
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
			xField = new TextFieldWidget(client.textRenderer, xXYZ1, y, colW,
				20, Text.literal(""));
			xField
				.setText(draftX != null ? draftX : Integer.toString(p.getX()));
			addDrawableChild(xField);
			yField = new TextFieldWidget(client.textRenderer, xXYZ2,
				narrow ? y + 28 : y, colW, 20, Text.literal(""));
			yField
				.setText(draftY != null ? draftY : Integer.toString(p.getY()));
			addDrawableChild(yField);
			zField = new TextFieldWidget(client.textRenderer, xXYZ3,
				narrow ? y + 56 : y, colW, 20, Text.literal(""));
			zField
				.setText(draftZ != null ? draftZ : Integer.toString(p.getZ()));
			addDrawableChild(zField);
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
			dimButton = ButtonWidget
				.builder(Text.literal("Dimension: " + dims[dimIndex].name()),
					b -> {
						dimIndex = (dimIndex + 1) % dims.length;
						b.setMessage(Text
							.literal("Dimension: " + dims[dimIndex].name()));
					})
				.dimensions(x, y, cw, 20).build();
			addDrawableChild(dimButton);
			y += 28;
			
			// Opposite / Visible toggles + Lines
			yToggles = y;
			int halfGap = 10;
			int halfW = (cw - halfGap) / 2;
			oppositeButton = ButtonWidget.builder(
				Text.literal(buttonLabel("Opposite", waypoint.isOpposite())),
				b -> {
					waypoint.setOpposite(!waypoint.isOpposite());
					b.setMessage(Text.literal(
						buttonLabel("Opposite", waypoint.isOpposite())));
				}).dimensions(x, y, halfW, 20).build();
			addDrawableChild(oppositeButton);
			
			visibleButton = ButtonWidget.builder(
				Text.literal(buttonLabel("Visible", waypoint.isVisible())),
				b -> {
					waypoint.setVisible(!waypoint.isVisible());
					b.setMessage(Text
						.literal(buttonLabel("Visible", waypoint.isVisible())));
				}).dimensions(x + halfW + halfGap, y, halfW, 20).build();
			addDrawableChild(visibleButton);
			y += 28;
			
			// Reserve space for opposite preview text, then Lines/Beacon row
			y += 16;
			int toggleWidth = (cw - halfGap) / 2;
			linesButton = ButtonWidget
				.builder(Text.literal(buttonLabel("Lines", waypoint.isLines())),
					b -> {
						waypoint.setLines(!waypoint.isLines());
						b.setMessage(Text
							.literal(buttonLabel("Lines", waypoint.isLines())));
					})
				.dimensions(x, y, toggleWidth, 20).build();
			addDrawableChild(linesButton);
			beaconButton = ButtonWidget
				.builder(Text.literal(beaconLabel(waypoint.getBeaconMode())),
					b -> {
						Waypoint.BeaconMode next =
							nextBeaconMode(waypoint.getBeaconMode());
						waypoint.setBeaconMode(next);
						b.setMessage(Text.literal(beaconLabel(next)));
					})
				.dimensions(x + toggleWidth + halfGap, y, toggleWidth, 20)
				.build();
			addDrawableChild(beaconButton);
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
			iconButton = ButtonWidget
				.builder(Text.literal("Icon: " + ICONS[iconIndex]), b -> {
					iconIndex = (iconIndex + 1) % ICONS.length;
					b.setMessage(Text.literal("Icon: " + ICONS[iconIndex]));
				}).dimensions(x, y, cw, 20).build();
			addDrawableChild(iconButton);
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
			colorButton = ButtonWidget.builder(
				Text.literal(
					"Pick color (#" + toHex6(colorSetting.getColorI()) + ")"),
				b -> {
					draftName = nameField.getText();
					draftX = xField.getText();
					draftY = yField.getText();
					draftZ = zField.getText();
					// Preserve selected dimension index so it isn't lost when
					// the child color screen re-initializes this screen.
					draftDimIndex = dimIndex;
					client.setScreen(new EditColorScreen(this, colorSetting));
				}).dimensions(x, y, cw - 24, 20).build();
			addDrawableChild(colorButton);
			y += 28;
			addDrawableChild(new net.minecraft.client.gui.widget.SliderWidget(x,
				y, fieldsWidth, 20,
				Text.literal("Transparency: " + alphaPercent + "%"),
				(alphaPercent - 1) / 99.0)
			{
				@Override
				protected void updateMessage()
				{
					int val = 1 + (int)Math.round(value * 99.0);
					alphaPercent = Math.max(1, Math.min(100, val));
					setMessage(
						Text.literal("Transparency: " + alphaPercent + "%"));
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
			addDrawableChild(ButtonWidget
				.builder(Text.literal("Use player pos"), b -> usePlayerPos())
				.dimensions(x, y, halfW, 20).build());
			addDrawableChild(
				ButtonWidget.builder(Text.literal("Delete"), b -> doDelete())
					.dimensions(x + halfW + halfGap, y, halfW, 20).build());
			y += 28;
		}
		
		// Always add Save/Cancel anchored at bottom
		int halfGap = 10;
		int halfW = (fieldsWidth - halfGap) / 2;
		addDrawableChild(
			ButtonWidget.builder(Text.literal("Save"), b -> saveAndBack())
				.dimensions(fieldsBaseX, height - 52, halfW, 20).build());
		addDrawableChild(ButtonWidget
			.builder(Text.literal("Cancel"), b -> client.setScreen(prev))
			.dimensions(fieldsBaseX + halfW + halfGap, height - 52, halfW, 20)
			.build());
	}
	
	@Override
	public void resize(net.minecraft.client.MinecraftClient client, int width,
		int height)
	{
		// Preserve current edits before re-initializing layout for new size
		if(nameField != null)
		{
			draftName = nameField.getText();
			draftX = xField.getText();
			draftY = yField.getText();
			draftZ = zField.getText();
			// Preserve dimension selection across resize/child screens
			draftDimIndex = dimIndex;
		}
		init(client, width, height);
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
		if(client.player == null)
			return;
		BlockPos p = BlockPos.ofFloored(client.player.getPos());
		xField.setText(Integer.toString(p.getX()));
		yField.setText(Integer.toString(p.getY()));
		zField.setText(Integer.toString(p.getZ()));
	}
	
	private void doDelete()
	{
		manager.remove(waypoint);
		if(listScreen != null)
			listScreen.saveNow();
		client.setScreen(prev);
	}
	
	private void saveAndBack()
	{
		// Name
		waypoint.setName(nameField.getText());
		
		// Position
		if(xField != null && yField != null && zField != null)
		{
			try
			{
				int x = Integer.parseInt(xField.getText());
				int y = Integer.parseInt(yField.getText());
				int z = Integer.parseInt(zField.getText());
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
		client.setScreen(prev);
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
	public void render(DrawContext context, int mouseX, int mouseY, float delta)
	{
		context.fill(0, 0, this.width, this.height, 0x88000000);
		super.render(context, mouseX, mouseY, delta);
		
		// Update color button label in case it changed in child screen
		if(colorButton != null && colorSetting != null)
			colorButton.setMessage(Text.literal(
				"Pick color (#" + toHex6(colorSetting.getColorI()) + ")"));
		
		// Labels
		int x = fieldsBaseX;
		context.drawText(client.textRenderer, "Name", x, yName - 18,
			Colors.LIGHT_GRAY, false);
		if(xField != null)
		{
			context.drawText(client.textRenderer, "X", xXYZ1, yXYZ - 18,
				Colors.LIGHT_GRAY, false);
			context.drawText(client.textRenderer, "Y", xXYZ2,
				(narrow ? yYField : yXYZ) - 18, Colors.LIGHT_GRAY, false);
			context.drawText(client.textRenderer, "Z", xXYZ3,
				(narrow ? yZField : yXYZ) - 18, Colors.LIGHT_GRAY, false);
		}
		// removed explicit "Color" text label to avoid redundancy and crowding
		
		// Color preview box
		int boxX = x + fieldsWidth - 20;
		int boxY = yColor;
		int alpha = (int)Math
			.round(Math.max(1, Math.min(100, alphaPercent)) / 100.0 * 255);
		int color = colorSetting.getColorI(alpha);
		context.fill(boxX - 1, boxY - 1, boxX + 18, boxY + 18, Colors.GRAY);
		context.fill(boxX, boxY, boxX + 16, boxY + 16, color);
		
		// Opposite preview text – render below the toggles and lines rows
		String opp = oppositePreview();
		if(!opp.isEmpty())
			context.drawText(client.textRenderer, opp, fieldsBaseX,
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
			int x = Integer.parseInt(xField.getText());
			int z = Integer.parseInt(zField.getText());
			int y = Integer.parseInt(yField.getText());
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
