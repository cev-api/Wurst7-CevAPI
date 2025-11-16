/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.Category;
import net.wurstclient.DontBlock;
import net.wurstclient.Feature;
import net.wurstclient.SearchTags;
import net.wurstclient.TooManyHaxFile;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.ClickGuiIcons;
import net.wurstclient.clickgui.Component;
import net.wurstclient.hack.Hack;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.text.WText;

@SearchTags({"too many hax", "TooManyHacks", "too many hacks", "YesCheat+",
	"YesCheatPlus", "yes cheat plus"})
@DontBlock
public final class TooManyHaxHack extends Hack
{
	private final ArrayList<Feature> blockedFeatures = new ArrayList<>();
	private final Path profilesFolder;
	private final TooManyHaxFile file;
	private final HackSelectionSetting hackSelectionSetting =
		new HackSelectionSetting();
	
	public TooManyHaxHack()
	{
		super("TooManyHax");
		setCategory(Category.OTHER);
		addSetting(hackSelectionSetting);
		
		Path wurstFolder = WURST.getWurstFolder();
		profilesFolder = wurstFolder.resolve("toomanyhax");
		
		Path filePath = wurstFolder.resolve("toomanyhax.json");
		file = new TooManyHaxFile(filePath, blockedFeatures);
	}
	
	public void loadBlockedHacksFile()
	{
		file.load();
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + " [" + blockedFeatures.size() + " blocked]";
	}
	
	@Override
	protected void onEnable()
	{
		disableBlockedHacks();
	}
	
	private void disableBlockedHacks()
	{
		for(Feature feature : blockedFeatures)
		{
			if(!(feature instanceof Hack))
				continue;
			
			((Hack)feature).setEnabled(false);
		}
		// Refresh ClickGUI so hacks disabled by TooManyHax are hidden there
		try
		{
			if(WURST.getGui() != null)
				WURST.getGui().init();
		}catch(Exception e)
		{
			// ignore GUI refresh failures
		}
	}
	
	public ArrayList<Path> listProfiles()
	{
		if(!Files.isDirectory(profilesFolder))
			return new ArrayList<>();
		
		try(Stream<Path> files = Files.list(profilesFolder))
		{
			return files.filter(Files::isRegularFile)
				.collect(Collectors.toCollection(ArrayList::new));
			
		}catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	public void loadProfile(String fileName) throws IOException, JsonException
	{
		file.loadProfile(profilesFolder.resolve(fileName));
		disableBlockedHacks();
	}
	
	public void saveProfile(String fileName) throws IOException, JsonException
	{
		file.saveProfile(profilesFolder.resolve(fileName));
	}
	
	public boolean isBlocked(Feature feature)
	{
		return blockedFeatures.contains(feature);
	}
	
	public void setBlocked(Feature feature, boolean blocked)
	{
		if(blocked)
		{
			if(!feature.isSafeToBlock())
				throw new IllegalArgumentException();
			
			blockedFeatures.add(feature);
			blockedFeatures
				.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
			
		}else
			blockedFeatures.remove(feature);
		
		file.save();
		try
		{
			if(WURST.getGui() != null)
				WURST.getGui().init();
		}catch(Exception e)
		{
			// ignore GUI refresh failures
		}
	}
	
	public void blockAll()
	{
		blockedFeatures.clear();
		
		ArrayList<Feature> features = new ArrayList<>();
		features.addAll(WURST.getHax().getAllHax());
		features.addAll(WURST.getCmds().getAllCmds());
		features.addAll(WURST.getOtfs().getAllOtfs());
		
		for(Feature feature : features)
		{
			if(!feature.isSafeToBlock())
				continue;
			
			blockedFeatures.add(feature);
		}
		
		blockedFeatures
			.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
		
		file.save();
		try
		{
			if(WURST.getGui() != null)
				WURST.getGui().init();
		}catch(Exception e)
		{
			// ignore GUI refresh failures
		}
	}
	
	public void unblockAll()
	{
		blockedFeatures.clear();
		file.save();
		try
		{
			if(WURST.getGui() != null)
				WURST.getGui().init();
		}catch(Exception e)
		{
			// ignore GUI refresh failures
		}
	}
	
	@Override
	protected void onDisable()
	{
		// Rebuild ClickGUI so previously hidden hacks reappear
		try
		{
			if(WURST.getGui() != null)
				WURST.getGui().init();
		}catch(Exception e)
		{
			// ignore GUI refresh failures
		}
	}
	
	public List<Feature> getBlockedFeatures()
	{
		return Collections.unmodifiableList(blockedFeatures);
	}
	
	private List<Hack> getSortedHacks()
	{
		return WURST.getHax().getAllHax().stream()
			.sorted(Comparator.comparing(h -> h.getName().toLowerCase()))
			.collect(Collectors.toList());
	}
	
	private final class HackSelectionSetting extends Setting
	{
		private HackSelectionSetting()
		{
			super("Blocked hacks", WText.literal(
				"Select the hacks that TooManyHax should keep disabled."));
		}
		
		@Override
		public Component getComponent()
		{
			HackSelectionComponent component = new HackSelectionComponent();
			component.refreshSize();
			return component;
		}
		
		@Override
		public void fromJson(JsonElement json)
		{
			// UI-only setting, nothing to load
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
	
	private final class HackSelectionComponent extends Component
	{
		private static final int ROW_HEIGHT = 11;
		private static final int BOX_SIZE = 11;
		
		private void refreshSize()
		{
			refreshSize(getSortedHacks());
		}
		
		private void refreshSize(List<Hack> hacks)
		{
			int desiredHeight =
				Math.max(ROW_HEIGHT * Math.max(hacks.size(), 1), ROW_HEIGHT);
			
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
			
			int index = relativeY / ROW_HEIGHT;
			if(index < 0 || index >= hacks.size())
				return;
			
			Hack hack = hacks.get(index);
			if(!hack.isSafeToBlock())
			{
				ChatUtils.error(
					"The hack '" + hack.getName() + "' is not safe to block.");
				return;
			}
			
			boolean blocked = isBlocked(hack);
			setBlocked(hack, !blocked);
		}
		
		@Override
		public void render(GuiGraphics context, int mouseX, int mouseY,
			float partialTicks)
		{
			List<Hack> hacks = getSortedHacks();
			refreshSize(hacks);
			
			if(hacks.isEmpty())
			{
				context.drawString(MC.font, "No hacks available.", getX() + 2,
					getY() + 2, WURST.getGui().getTxtColor(), false);
				return;
			}
			
			ClickGui gui = WURST.getGui();
			int x1 = getX();
			int x2 = x1 + getWidth();
			
			boolean hovering = isHovering(mouseX, mouseY);
			
			for(int i = 0; i < hacks.size(); i++)
			{
				Hack hack = hacks.get(i);
				int y1 = getY() + i * ROW_HEIGHT;
				int y2 = y1 + ROW_HEIGHT;
				boolean blocked = isBlocked(hack);
				
				boolean rowHover = hovering && mouseY >= y1 && mouseY < y2;
				if(rowHover)
				{
					String tooltip = hack.getWrappedDescription(200);
					if(!hack.isSafeToBlock())
						tooltip += "\n\nThis hack cannot be blocked.";
					gui.setTooltip(tooltip);
				}
				
				float[] bg = gui.getBgColor();
				float opacity = gui.getOpacity();
				float intensity = rowHover ? 1.2F : blocked ? 1.05F : 1.0F;
				float rowOpacity = opacity * intensity;
				if(!hack.isSafeToBlock())
					rowOpacity *= 0.6F;
				
				int boxX2 = x1 + BOX_SIZE;
				
				context.fill(boxX2, y1, x2, y2,
					RenderUtils.toIntColor(bg, rowOpacity));
				
				float boxOpacity = opacity * (rowHover ? 1.3F : 1.0F);
				context.fill(x1, y1, boxX2, y2,
					RenderUtils.toIntColor(bg, boxOpacity));
				
				int outlineColor =
					RenderUtils.toIntColor(gui.getAcColor(), 0.5F);
				RenderUtils.drawBorder2D(context, x1, y1, boxX2, y2,
					outlineColor);
				
				if(blocked)
					ClickGuiIcons.drawCheck(context, x1, y1, boxX2, y2,
						rowHover, !hack.isSafeToBlock());
				
				int textColor = gui.getTxtColor();
				if(!hack.isSafeToBlock())
					textColor = (textColor & 0x00FFFFFF) | 0x55000000;
				
				context.drawString(MC.font, hack.getName(), boxX2 + 2, y1 + 2,
					textColor, false);
			}
		}
		
		@Override
		public int getDefaultWidth()
		{
			List<Hack> hacks = getSortedHacks();
			
			int maxNameWidth = 0;
			for(Hack hack : hacks)
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
}
