/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.TooManyHaxFile;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.ClickGuiIcons;
import net.wurstclient.clickgui.Component;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.AltGuiHack;
import net.wurstclient.hacks.ClickGuiHack;
import net.wurstclient.hacks.NavigatorHack;
import net.wurstclient.hacks.XpGuiHack;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"hack list", "HakList", "hak list", "HacksList", "hacks list",
	"HaxList", "hax list", "ArrayList", "array list", "ModList", "mod list",
	"CheatList", "cheat list"})
@DontBlock
public final class HackListOtf extends OtherFeature
{
	private final ArrayList<net.wurstclient.Feature> hiddenHacks =
		new ArrayList<>();
	private final TooManyHaxFile hiddenHacksFile;
	private final CheckboxSetting hideFromHackListEnabled = new CheckboxSetting(
		"Enabled",
		"Enable or disable the Hide from HackList filter without clearing its saved list.",
		true)
	{
		@Override
		public void update()
		{
			syncHiddenHackStates();
			refreshGui();
		}
	};
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lAuto\u00a7r mode renders the whole list if it fits onto the screen.\n"
			+ "\u00a7lCount\u00a7r mode only renders the number of active hacks.\n"
			+ "\u00a7lHidden\u00a7r mode renders nothing.",
		Mode.values(), Mode.AUTO);
	
	private final EnumSetting<Position> position = new EnumSetting<>("Position",
		"Which side of the screen the HackList should be shown on."
			+ "\nChange this to \u00a7lRight\u00a7r when using TabGUI.",
		Position.values(), Position.TOP_LEFT);
	
	private final ColorSetting color = new ColorSetting("Color",
		"Color of the HackList text.\n"
			+ "Only visible when \u00a76RainbowUI\u00a7r is disabled.",
		Color.WHITE);
	
	// New: Use each hack's own color for its entry (if available)
	private final CheckboxSetting useHackColors = new CheckboxSetting(
		"Use hack colors",
		"When enabled, each entry uses the hack's own color (if available), e.g. ESP highlight colors.\n"
			+ "Has no effect while RainbowUI is enabled.",
		false);
	
	private final CheckboxSetting shadowBox = new CheckboxSetting("Shadow box",
		"Replace the text shadow with a transparent black box background. Useful when scaled fonts produce ugly shadows.",
		false);
	
	// Shadow box alpha (0.0 - 1.0)
	private final net.wurstclient.settings.SliderSetting shadowBoxAlpha =
		new net.wurstclient.settings.SliderSetting("Shadow box alpha", 0.5, 0.0,
			1.0, 0.01,
			net.wurstclient.settings.SliderSetting.ValueDisplay.DECIMAL);
	
	// Transparency (0.0 - 1.0)
	private final net.wurstclient.settings.SliderSetting transparency =
		new net.wurstclient.settings.SliderSetting("Transparency", 1.0, 0.0,
			1.0, 0.01,
			net.wurstclient.settings.SliderSetting.ValueDisplay.DECIMAL);
	
	// X and Y offset from screen edge in pixels (can be negative)
	private final net.wurstclient.settings.SliderSetting xOffset =
		new net.wurstclient.settings.SliderSetting("X offset", 0.0, -200.0,
			200.0, 1.0,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	
	private final net.wurstclient.settings.SliderSetting yOffset =
		new net.wurstclient.settings.SliderSetting("Y offset", 0.0, -200.0,
			200.0, 1.0,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	
	private final EnumSetting<SortBy> sortBy = new EnumSetting<>("Sort by",
		"Determines how the HackList entries are sorted.\n"
			+ "Only visible when \u00a76Mode\u00a7r is set to \u00a76Auto\u00a7r.",
		SortBy.values(), SortBy.NAME);
	
	private final CheckboxSetting revSort =
		new CheckboxSetting("Reverse sorting", false);
	
	private final CheckboxSetting animations = new CheckboxSetting("Animations",
		"When enabled, entries slide into and out of the HackList as hacks are enabled and disabled.",
		true);
	private final HiddenHacksSetting hiddenHacksSetting =
		new HiddenHacksSetting();
	private final SettingGroup hiddenHacksGroup = new SettingGroup(
		"Hide from HackList",
		WText.literal(
			"Choose which active hacks should not be shown in the HackList."))
				.addChildren(hideFromHackListEnabled, hiddenHacksSetting);
	
	private SortBy prevSortBy;
	private Boolean prevRevSort;
	
	private final net.wurstclient.settings.SliderSetting fontSize =
		new net.wurstclient.settings.SliderSetting("Font size", 1.0, 0.5, 2.0,
			0.05, net.wurstclient.settings.SliderSetting.ValueDisplay.DECIMAL);
	
	// New: spacing between entries in screen pixels (can be negative)
	private final net.wurstclient.settings.SliderSetting entrySpacing =
		new net.wurstclient.settings.SliderSetting("Entry spacing", 0.0, 0,
			24.0, 1.0,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	
	public HackListOtf()
	{
		super("HackList", "Shows a list of active hacks on the screen.");
		hiddenHacksFile = new TooManyHaxFile(
			WURST.getWurstFolder().resolve("toomanyhax_hacklist.json"),
			hiddenHacks, false);
		
		addSetting(mode);
		addSetting(position);
		addSetting(color);
		addSetting(useHackColors);
		addSetting(shadowBox);
		addSetting(shadowBoxAlpha);
		addSetting(fontSize);
		addSetting(entrySpacing);
		addSetting(transparency);
		addSetting(xOffset);
		addSetting(yOffset);
		addSetting(sortBy);
		addSetting(revSort);
		addSetting(animations);
		addSetting(hiddenHacksGroup);
	}
	
	public void loadHiddenHacksFile()
	{
		hiddenHacksFile.load();
		syncHiddenHackStates();
	}
	
	public boolean isHidden(Hack hack)
	{
		return hideFromHackListEnabled.isChecked()
			&& hiddenHacks.contains(hack);
	}
	
	public void setHidden(Hack hack, boolean hidden)
	{
		if(hidden)
		{
			if(hiddenHacks.contains(hack))
				return;
			hiddenHacks.add(hack);
			hiddenHacks
				.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
		}else
			hiddenHacks.remove(hack);
		
		hiddenHacksFile.save();
		syncHackState(hack);
		refreshGui();
	}
	
	public int getEnabledHiddenHackCount()
	{
		if(!hideFromHackListEnabled.isChecked())
			return 0;
		
		int count = 0;
		for(net.wurstclient.Feature feature : hiddenHacks)
			if(feature instanceof Hack hack && hack.isEnabled())
				count++;
		return count;
	}
	
	private void refreshGui()
	{
		try
		{
			if(WURST.getGui() != null)
				WURST.getGui().requestRefresh();
		}catch(Exception ignored)
		{}
	}
	
	private void syncHiddenHackStates()
	{
		var hud = WURST.getHud();
		if(hud == null)
			return;
		
		var hackList = hud.getHackList();
		for(net.wurstclient.Feature feature : hiddenHacks)
			if(feature instanceof Hack hack)
				hackList.updateState(hack);
	}
	
	private void syncHackState(Hack hack)
	{
		var hud = WURST.getHud();
		if(hud == null)
			return;
		
		hud.getHackList().updateState(hack);
	}
	
	private List<Hack> getSortedHacks()
	{
		return WURST.getHax().getAllHax().stream()
			.filter(this::canAppearInHackList)
			.sorted(Comparator.comparing(h -> h.getName().toLowerCase()))
			.collect(Collectors.toList());
	}
	
	private boolean canAppearInHackList(Hack hack)
	{
		return !(hack instanceof NavigatorHack || hack instanceof ClickGuiHack
			|| hack instanceof AltGuiHack || hack instanceof XpGuiHack);
	}
	
	public Mode getMode()
	{
		return mode.getSelected();
	}
	
	public Position getPosition()
	{
		return position.getSelected();
	}
	
	public double getTransparency()
	{
		return transparency.getValue();
	}
	
	public double getFontSize()
	{
		return fontSize.getValue();
	}
	
	// New: get entry spacing in screen pixels
	public int getEntrySpacing()
	{
		return entrySpacing.getValueI();
	}
	
	public int getXOffset()
	{
		return xOffset.getValueI();
	}
	
	public int getYOffset()
	{
		return yOffset.getValueI();
	}
	
	public boolean isAnimations()
	{
		return animations.isChecked();
	}
	
	public boolean useShadowBox()
	{
		return shadowBox.isChecked();
	}
	
	public Comparator<Hack> getComparator()
	{
		if(revSort.isChecked())
			return sortBy.getSelected().comparator.reversed();
		
		return sortBy.getSelected().comparator;
	}
	
	public boolean shouldSort()
	{
		try
		{
			// width of a renderName could change at any time
			// must sort the HackList every tick
			if(sortBy.getSelected() == SortBy.WIDTH)
				return true;
			
			if(sortBy.getSelected() != prevSortBy)
				return true;
			
			if(!Boolean.valueOf(revSort.isChecked()).equals(prevRevSort))
				return true;
			
			return false;
			
		}finally
		{
			prevSortBy = sortBy.getSelected();
			prevRevSort = revSort.isChecked();
		}
	}
	
	public int getColor(int alpha)
	{
		return color.getColorI(alpha);
	}
	
	public boolean useHackColors()
	{
		return useHackColors.isChecked();
	}
	
	public double getShadowBoxAlpha()
	{
		return shadowBoxAlpha.getValue();
	}
	
	private final class HiddenHacksSetting extends Setting
	{
		private HiddenHacksSetting()
		{
			super("Hacks", WText.literal(
				"Select active hacks that should not be shown in the HackList."));
		}
		
		@Override
		public Component getComponent()
		{
			HiddenHacksComponent component = new HiddenHacksComponent();
			component.refreshSize();
			return component;
		}
		
		@Override
		public void fromJson(JsonElement json)
		{
			// Stored separately in toomanyhax_hacklist.json.
		}
		
		@Override
		public JsonElement toJson()
		{
			return JsonNull.INSTANCE;
		}
		
		@Override
		public JsonObject exportWikiData()
		{
			JsonObject json = new JsonObject();
			json.addProperty("name", getName());
			json.addProperty("description", getDescription());
			json.addProperty("type", "Custom");
			return json;
		}
		
		@Override
		public java.util.Set<PossibleKeybind> getPossibleKeybinds(
			String featureName)
		{
			return Collections.emptySet();
		}
	}
	
	private final class HiddenHacksComponent extends Component
	{
		private static final int ROW_HEIGHT = 11;
		private static final int BOX_SIZE = 11;
		private static final int MAX_VISIBLE_ROWS = 10;
		private int scrollOffset;
		
		private void refreshSize()
		{
			refreshSize(getSortedHacks());
		}
		
		private void refreshSize(List<Hack> hacks)
		{
			int visibleRows = Math.max(1,
				Math.min(MAX_VISIBLE_ROWS, Math.max(hacks.size(), 1)));
			int desiredHeight = ROW_HEIGHT * visibleRows;
			
			if(getHeight() != desiredHeight)
				setHeight(desiredHeight);
		}
		
		@Override
		public void handleMouseClick(double mouseX, double mouseY,
			int mouseButton, MouseButtonEvent context)
		{
			if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
				return;
			
			int mx = (int)Math.floor(mouseX);
			int my = (int)Math.floor(mouseY);
			if(!isHovering(mx, my))
				return;
			
			List<Hack> hacks = getSortedHacks();
			if(hacks.isEmpty())
				return;
			
			int relativeY = my - getY();
			if(relativeY < 0)
				return;
			
			int index = scrollOffset + relativeY / ROW_HEIGHT;
			if(index < 0 || index >= hacks.size())
				return;
			
			Hack hack = hacks.get(index);
			setHidden(hack, !isHidden(hack));
		}
		
		@Override
		public boolean handleMouseScroll(double mouseX, double mouseY,
			double delta)
		{
			int mx = (int)Math.floor(mouseX);
			int my = (int)Math.floor(mouseY);
			if(!isHovering(mx, my))
				return false;
			
			List<Hack> hacks = getSortedHacks();
			int visibleRows = Math.max(1, getHeight() / ROW_HEIGHT);
			int maxOffset = Math.max(0, hacks.size() - visibleRows);
			if(maxOffset <= 0)
				return false;
			
			int direction = (int)Math.signum(delta);
			if(direction == 0)
				return true;
			
			scrollOffset -= direction;
			if(scrollOffset < 0)
				scrollOffset = 0;
			else if(scrollOffset > maxOffset)
				scrollOffset = maxOffset;
			return true;
		}
		
		@Override
		public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
			int mouseY, float partialTicks)
		{
			List<Hack> hacks = getSortedHacks();
			refreshSize(hacks);
			int visibleRows = Math.max(1, getHeight() / ROW_HEIGHT);
			int maxOffset = Math.max(0, hacks.size() - visibleRows);
			if(scrollOffset > maxOffset)
				scrollOffset = maxOffset;
			
			if(hacks.isEmpty())
			{
				context.text(MC.font, "No hacks available.", getX() + 2,
					getY() + 2, WURST.getGui().getTxtColor(), false);
				return;
			}
			
			ClickGui gui = WURST.getGui();
			int x1 = getX();
			int x2 = x1 + getWidth();
			int yTop = getY();
			int yBottom = yTop + getHeight();
			boolean hovering = isHovering(mouseX, mouseY);
			
			for(int row = 0; row < visibleRows; row++)
			{
				int index = scrollOffset + row;
				int y1 = getY() + row * ROW_HEIGHT;
				int y2 = y1 + ROW_HEIGHT;
				Hack hack = index < hacks.size() ? hacks.get(index) : null;
				boolean hidden = hack != null && isHidden(hack);
				boolean rowHover = hovering && mouseY >= y1 && mouseY < y2;
				
				if(rowHover && hack != null)
					gui.setTooltip(hack.getWrappedDescription(200));
				
				float[] bg = gui.getBgColor();
				float opacity = gui.getOpacity();
				float intensity = rowHover ? 1.2F : hidden ? 1.05F : 1.0F;
				int boxX2 = x1 + BOX_SIZE;
				
				context.fill(boxX2, y1, x2, y2,
					RenderUtils.toIntColor(bg, opacity * intensity));
				context.fill(x1, y1, boxX2, y2, RenderUtils.toIntColor(bg,
					opacity * (rowHover ? 1.3F : 1.0F)));
				
				int outlineColor =
					RenderUtils.toIntColor(gui.getAcColor(), 0.5F);
				RenderUtils.drawBorder2D(context, x1, y1, boxX2, y2,
					outlineColor);
				
				if(hidden)
					ClickGuiIcons.drawCheck(context, x1, y1, boxX2, y2,
						rowHover, false);
				
				if(hack != null)
				{
					int textColor =
						hack.isEnabled() ? 0xFF55FF55 : gui.getTxtColor();
					context.text(MC.font, hack.getName(), boxX2 + 2, y1 + 2,
						textColor, false);
				}
			}
			
			if(maxOffset > 0)
			{
				int trackX1 = x2 - 3;
				int trackX2 = x2 - 1;
				int trackY1 = yTop;
				int trackY2 = yBottom;
				context.fill(trackX1, trackY1, trackX2, trackY2,
					RenderUtils.toIntColor(gui.getBgColor(), gui.getOpacity()));
				
				double thumbHeight = Math.max(8,
					getHeight() * (visibleRows / (double)hacks.size()));
				double travel = Math.max(0, getHeight() - thumbHeight);
				double thumbY =
					trackY1 + travel * (scrollOffset / (double)maxOffset);
				context.fill(trackX1, (int)Math.round(thumbY), trackX2,
					(int)Math.round(thumbY + thumbHeight),
					RenderUtils.toIntColor(gui.getAcColor(), 0.75F));
			}
		}
		
		@Override
		public int getDefaultWidth()
		{
			int maxNameWidth = 0;
			for(Hack hack : getSortedHacks())
				maxNameWidth =
					Math.max(maxNameWidth, MC.font.width(hack.getName()));
			
			return Math.max(130, BOX_SIZE + 4 + maxNameWidth);
		}
		
		@Override
		public int getDefaultHeight()
		{
			return 110;
		}
	}
	
	public static enum Mode
	{
		AUTO("Auto"),
		
		COUNT("Count"),
		
		HIDDEN("Hidden");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public static enum Position
	{
		// Legacy values for backward compatibility
		LEFT("Left"),
		RIGHT("Right"),
		TOP_LEFT("Top left"),
		
		TOP_RIGHT("Top right"),
		
		BOTTOM_LEFT("Bottom left"),
		
		BOTTOM_RIGHT("Bottom right");
		
		private final String name;
		
		private Position(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public static enum SortBy
	{
		NAME("Name", (a, b) -> a.getName().compareToIgnoreCase(b.getName())),
		
		WIDTH("Width", Comparator
			.comparingInt(h -> WurstClient.MC.font.width(h.getRenderName())));
		
		private final String name;
		private final Comparator<Hack> comparator;
		
		private SortBy(String name, Comparator<Hack> comparator)
		{
			this.name = name;
			this.comparator = comparator;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
