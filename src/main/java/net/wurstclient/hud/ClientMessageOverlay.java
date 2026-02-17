/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.ClientChatOverlayHack;

public final class ClientMessageOverlay
{
	private static final int MAX_STORED_MESSAGES = 128;
	private static final int CHAT_BOTTOM_MARGIN = 40;
	private static final int OVERLAY_GAP = 6;
	private static final int LINE_SPACING = 1;
	private static final int HORIZONTAL_PADDING = 2;
	private static final int BACKGROUND_COLOR = 0x64000000;
	private static final long DEFAULT_FADE_OUT_MS = 10000L;
	private static final int VANILLA_CHAT_VISIBLE_TICKS = 200;
	private static final Pattern PLAYER_CHAT_PATTERN =
		Pattern.compile("^<[^>]{1,32}>\\s+.+$");
	private static final String CHAT_PREFIX_PATTERN =
		"(?:(?:\\[[^\\]]{1,32}\\]|‹[^›]{1,32}›)\\s*)*";
	private static final Pattern DECORATED_PLAYER_CHAT_PATTERN =
		Pattern.compile("^" + CHAT_PREFIX_PATTERN + "<[^>]{1,32}>\\s+.+$");
	private static final Pattern COLON_PLAYER_CHAT_PATTERN = Pattern
		.compile("^" + CHAT_PREFIX_PATTERN + "[A-Za-z0-9_\\-*.]{1,24}:\\s+.+$");
	private static final Pattern ARROW_PLAYER_CHAT_PATTERN = Pattern.compile(
		"^" + CHAT_PREFIX_PATTERN + "[A-Za-z0-9_\\-*.]{1,24}\\s*[»>]\\s+.+$");
	private static final Pattern BRACKETED_PLAYER_CHAT_PATTERN =
		Pattern.compile("^\\[[^\\]]{1,32}\\]\\s+\\[[^\\]]{1,32}\\]\\s+.+$");
	private static final Pattern JOIN_LEAVE_PATTERN =
		Pattern.compile("^[A-Za-z0-9_]{1,16}\\s+(joined|left)\\s+the\\s+game$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern DISCORD_PREFIX_PATTERN =
		Pattern.compile("^\\[Discord\\]\\s+.+$", Pattern.CASE_INSENSITIVE);
	private static final ClientMessageOverlay INSTANCE =
		new ClientMessageOverlay();
	
	private final Deque<Entry> messages = new ArrayDeque<>(MAX_STORED_MESSAGES);
	private boolean dragging;
	private double dragStartMouseX;
	private double dragStartMouseY;
	private int dragStartOffsetX;
	private int dragStartOffsetY;
	private int dragOffsetX;
	private int dragOffsetY;
	private boolean hovered;
	private int scrollOffset;
	private int visibleLineCount;
	private int totalLineCount;
	private int lastX1;
	private int lastY1;
	private int lastX2;
	private int lastY2;
	
	private ClientMessageOverlay()
	{}
	
	public static ClientMessageOverlay getInstance()
	{
		return INSTANCE;
	}
	
	public boolean captureSingleArgMessage(Component message)
	{
		if(!isEnabled() || message == null)
			return false;
		
		String plain = stripLegacyFormatting(message.getString()).trim();
		if(isForcedToNormalChat(plain))
			return false;
		
		if(isWurstMessage(plain))
		{
			addMessage(message);
			scrollOffset = 0;
			logToConsoleIfEnabled(message);
			return true;
		}
		
		if(isForcedToClientChat(plain))
		{
			addMessage(message);
			scrollOffset = 0;
			logToConsoleIfEnabled(message);
			return true;
		}
		
		if(looksLikePlayerChat(plain))
			return false;
		
		if(!shouldCaptureByFilter(message))
			return false;
		
		addMessage(message);
		scrollOffset = 0;
		logToConsoleIfEnabled(message);
		return true;
	}
	
	public boolean captureIfNonPlayerMessage(Component message,
		@Nullable MessageSignature signature)
	{
		if(!isEnabled() || message == null)
			return false;
		
		String plain = stripLegacyFormatting(message.getString()).trim();
		if(isForcedToNormalChat(plain))
			return false;
		
		if(isWurstMessage(plain))
		{
			addMessage(message);
			scrollOffset = 0;
			logToConsoleIfEnabled(message);
			return true;
		}
		
		if(isForcedToClientChat(plain))
		{
			addMessage(message);
			scrollOffset = 0;
			logToConsoleIfEnabled(message);
			return true;
		}
		
		// Signed messages are usually player chat. Keep them in vanilla chat
		// unless explicitly forced into client chat via keyword.
		if(signature != null)
			return false;
		
		if(looksLikePlayerChat(plain))
			return false;
		
		if(!shouldCaptureByFilter(message))
			return false;
		
		addMessage(message);
		scrollOffset = 0;
		logToConsoleIfEnabled(message);
		return true;
	}
	
	public void onMouseScroll(double vertical)
	{
		if(!isEnabled() || !hovered || totalLineCount <= visibleLineCount)
			return;
		
		if(vertical > 0)
			scrollOffset += 2;
		else if(vertical < 0)
			scrollOffset -= 2;
		
		int maxScroll = Math.max(0, totalLineCount - visibleLineCount);
		scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
	}
	
	public boolean isControllingScrollEvents()
	{
		return isEnabled() && hovered && totalLineCount > visibleLineCount;
	}
	
	public void notifyVanillaChatMessage(Component message)
	{
		// No-op. Kept for ChatHudMixin compatibility.
	}
	
	public void render(GuiGraphics context)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		ClientChatOverlayHack hack = getSettings();
		if(hack == null || !hack.isEnabled())
			return;
		
		if(messages.isEmpty())
			return;
		
		float chatScale = WurstClient.MC.options.chatScale().get().floatValue();
		if(chatScale <= 0)
			return;
		
		boolean chatOpen = WurstClient.MC.screen instanceof ChatScreen;
		int maxLines = hack.getMaxLines();
		int maxWidth = Mth.floor(
			ChatComponent.getWidth(WurstClient.MC.options.chatWidth().get())
				/ chatScale);
		if(maxWidth <= 0)
			return;
		
		List<RenderLine> allLines =
			buildAllLines(maxWidth, chatOpen || hovered);
		totalLineCount = allLines.size();
		if(allLines.isEmpty())
			return;
		
		int maxScroll = Math.max(0, totalLineCount - maxLines);
		scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
		List<RenderLine> lines =
			getVisibleLines(allLines, maxLines, scrollOffset);
		if(lines.isEmpty())
			return;
		visibleLineCount = lines.size();
		
		int chatHeight = ChatComponent.getHeight(
			chatOpen ? WurstClient.MC.options.chatHeightFocused().get()
				: WurstClient.MC.options.chatHeightUnfocused().get());
		int baseY = context.guiHeight() - CHAT_BOTTOM_MARGIN;
		int visibleVanillaLines =
			getVisibleVanillaLineCount(chatHeight, chatOpen);
		
		float boxWidth = 0;
		for(RenderLine line : lines)
			boxWidth = Math.max(boxWidth, WurstClient.MC.font.width(line.text())
				+ HORIZONTAL_PADDING * 2F);
		
		float boxHeight = lines.size() * WurstClient.MC.font.lineHeight
			+ Math.max(0, lines.size() - 1) * LINE_SPACING;
		
		int drawX = 4 + getCurrentOffsetX();
		int drawY = baseY + getCurrentOffsetY();
		
		if(visibleVanillaLines > 0)
		{
			int nudgeUp = visibleVanillaLines
				* (WurstClient.MC.font.lineHeight + LINE_SPACING) + OVERLAY_GAP;
			drawY -= Math.round(nudgeUp * chatScale);
		}
		
		handleDrag(context, drawX, drawY, boxWidth * chatScale,
			boxHeight * chatScale);
		
		updateHoverBounds(context, drawX, drawY, boxWidth * chatScale,
			boxHeight * chatScale);
		
		context.pose().pushMatrix();
		context.pose().translate(drawX, drawY);
		context.pose().scale(chatScale, chatScale);
		
		int y = -WurstClient.MC.font.lineHeight;
		for(RenderLine line : lines)
		{
			int width = WurstClient.MC.font.width(line.text());
			int alpha = Mth.clamp(line.alpha(), 0, 255);
			int bgColor = (alpha / 2 << 24) | (BACKGROUND_COLOR & 0x00FFFFFF);
			int textColor = (alpha << 24) | 0x00FFFFFF;
			context.fill(0, y - 1, width + HORIZONTAL_PADDING * 2,
				y + WurstClient.MC.font.lineHeight, bgColor);
			context.drawString(WurstClient.MC.font, line.text(),
				HORIZONTAL_PADDING, y, textColor, false);
			y -= WurstClient.MC.font.lineHeight + LINE_SPACING;
		}
		
		context.pose().popMatrix();
	}
	
	private void handleDrag(GuiGraphics context, int drawX, int drawY,
		float width, float height)
	{
		hovered = isMouseOverOverlay(context);
		
		if(WurstClient.MC.screen == null)
		{
			if(dragging)
				commitDraggedOffset();
			dragging = false;
			return;
		}
		
		Window window = WurstClient.MC.getWindow();
		if(window == null)
		{
			if(dragging)
				commitDraggedOffset();
			dragging = false;
			return;
		}
		
		double mouseX = getScaledMouseX(context);
		double mouseY = getScaledMouseY(context);
		boolean leftDown = GLFW.glfwGetMouseButton(window.handle(),
			GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		
		float x = drawX;
		float y = drawY - height;
		boolean overHud = mouseX >= x && mouseX <= x + width && mouseY >= y
			&& mouseY <= y + height;
		
		if(leftDown && overHud)
		{
			if(!dragging)
			{
				dragging = true;
				dragStartMouseX = mouseX;
				dragStartMouseY = mouseY;
				dragStartOffsetX = getSettings().getHudOffsetX();
				dragStartOffsetY = getSettings().getHudOffsetY();
				dragOffsetX = dragStartOffsetX;
				dragOffsetY = dragStartOffsetY;
			}
			
			dragOffsetX = Mth.clamp(
				dragStartOffsetX + (int)Math.round(mouseX - dragStartMouseX),
				-320, 320);
			dragOffsetY = Mth.clamp(
				dragStartOffsetY + (int)Math.round(mouseY - dragStartMouseY),
				-240, 240);
			return;
		}
		
		if(!leftDown)
		{
			if(dragging)
				commitDraggedOffset();
			dragging = false;
		}
	}
	
	private int getCurrentOffsetX()
	{
		ClientChatOverlayHack hack = getSettings();
		return dragging ? dragOffsetX
			: (hack == null ? 0 : hack.getHudOffsetX());
	}
	
	private int getCurrentOffsetY()
	{
		ClientChatOverlayHack hack = getSettings();
		return dragging ? dragOffsetY
			: (hack == null ? 0 : hack.getHudOffsetY());
	}
	
	private void commitDraggedOffset()
	{
		ClientChatOverlayHack hack = getSettings();
		if(hack != null)
			hack.setHudOffsets(dragOffsetX, dragOffsetY);
	}
	
	private ClientChatOverlayHack getSettings()
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getHax() == null)
			return null;
		
		return WurstClient.INSTANCE.getHax().clientChatOverlayHack;
	}
	
	private boolean isEnabled()
	{
		ClientChatOverlayHack hack = getSettings();
		return hack != null && hack.isEnabled();
	}
	
	private void addMessage(Component message)
	{
		synchronized(messages)
		{
			if(messages.size() >= MAX_STORED_MESSAGES)
				messages.removeFirst();
			
			messages
				.addLast(new Entry(message.copy(), System.currentTimeMillis()));
		}
	}
	
	private static boolean looksLikePlayerChat(String plain)
	{
		if(plain == null || plain.isEmpty())
			return false;
		
		if(PLAYER_CHAT_PATTERN.matcher(plain).matches())
			return true;
		
		if(DECORATED_PLAYER_CHAT_PATTERN.matcher(plain).matches())
			return true;
		
		if(COLON_PLAYER_CHAT_PATTERN.matcher(plain).matches())
			return true;
		
		if(ARROW_PLAYER_CHAT_PATTERN.matcher(plain).matches())
			return true;
		
		if(BRACKETED_PLAYER_CHAT_PATTERN.matcher(plain).matches())
			return true;
		
		if(JOIN_LEAVE_PATTERN.matcher(plain).matches())
			return true;
		
		if(DISCORD_PREFIX_PATTERN.matcher(plain).matches())
			return true;
		
		String lower = plain.toLowerCase();
		return lower.contains(" whispers to you:")
			|| lower.startsWith("to ") && plain.contains(": ");
	}
	
	private List<RenderLine> buildAllLines(int maxWidth, boolean fullOpacity)
	{
		synchronized(messages)
		{
			if(messages.isEmpty())
				return List.of();
			
			List<RenderLine> lines = new ArrayList<>();
			long now = System.currentTimeMillis();
			for(Entry message : messages.reversed())
			{
				List<FormattedCharSequence> wrapped =
					ComponentRenderUtils.wrapComponents(message.component(),
						maxWidth, WurstClient.MC.font);
				int alpha =
					fullOpacity ? 255 : getLineAlpha(now - message.timestamp());
				if(alpha <= 3)
					continue;
				
				for(int i = wrapped.size() - 1; i >= 0; i--)
					lines.add(new RenderLine(wrapped.get(i), alpha));
			}
			
			return lines;
		}
	}
	
	private static List<RenderLine> getVisibleLines(List<RenderLine> allLines,
		int maxLines, int startOffset)
	{
		if(allLines.isEmpty() || maxLines <= 0)
			return List.of();
		
		ArrayList<RenderLine> visible = new ArrayList<>(maxLines);
		int start = Math.min(startOffset, Math.max(0, allLines.size() - 1));
		for(int i = start; i < allLines.size()
			&& visible.size() < maxLines; i++)
			visible.add(allLines.get(i));
		
		return visible;
	}
	
	private int getLineAlpha(long ageMs)
	{
		long fadeMs = Math.max(1000L, getFadeOutMs());
		double fade = 1.0 - (double)Math.max(0L, ageMs) / fadeMs;
		fade = Mth.clamp(fade, 0.0, 1.0);
		fade *= fade;
		
		double chatOpacity = WurstClient.MC.options.chatOpacity().get();
		ClientChatOverlayHack hack = getSettings();
		double transparency = Math.max(0.0,
			Math.min(100.0, hack == null ? 35 : hack.getTransparencyPercent()))
			/ 100.0;
		double baseOpacity = (chatOpacity * 0.9 + 0.1) * (1.0 - transparency);
		return Mth.clamp((int)Math.round(255 * fade * baseOpacity), 0, 255);
	}
	
	private long getFadeOutMs()
	{
		ClientChatOverlayHack hack = getSettings();
		return hack == null ? DEFAULT_FADE_OUT_MS : hack.getFadeOutTimeMs();
	}
	
	private boolean shouldCaptureByFilter(Component message)
	{
		ClientChatOverlayHack hack = getSettings();
		if(hack == null || !hack.isOnlyWurstMessages())
			return true;
		
		String plain = stripLegacyFormatting(message.getString()).trim();
		return isWurstMessage(plain);
	}
	
	private boolean isForcedToClientChat(String plain)
	{
		ClientChatOverlayHack hack = getSettings();
		return hack != null && hack.matchesForceClientKeyword(plain);
	}
	
	private boolean isForcedToNormalChat(String plain)
	{
		ClientChatOverlayHack hack = getSettings();
		return hack != null && hack.matchesForceNormalKeyword(plain);
	}
	
	private static boolean isWurstMessage(String plain)
	{
		return plain.regionMatches(true, 0, "[Wurst]", 0, 7);
	}
	
	private int getVisibleVanillaLineCount(int chatHeight, boolean chatOpen)
	{
		List<GuiMessage.Line> lines =
			WurstClient.MC.gui.getChat().trimmedMessages;
		if(lines == null || lines.isEmpty())
			return 0;
		
		int lineHeightWithSpacing =
			WurstClient.MC.font.lineHeight + LINE_SPACING;
		int maxVisibleLines = Math.max(1, chatHeight / lineHeightWithSpacing);
		if(chatOpen)
			return Math.min(lines.size(), maxVisibleLines);
		
		int guiTicks = WurstClient.MC.gui.getGuiTicks();
		int visibleLines = 0;
		for(GuiMessage.Line line : lines)
		{
			if(guiTicks - line.addedTime() < VANILLA_CHAT_VISIBLE_TICKS)
			{
				visibleLines++;
				if(visibleLines >= maxVisibleLines)
					break;
			}
		}
		
		return Math.min(visibleLines, maxVisibleLines);
	}
	
	private void logToConsoleIfEnabled(Component message)
	{
		ClientChatOverlayHack hack = getSettings();
		if(hack == null || !hack.isRoutingToConsole())
			return;
		
		String plain = stripLegacyFormatting(message.getString()).trim();
		if(plain.isEmpty())
			return;
		
		System.out.println("[ClientChatOverlay] " + plain);
	}
	
	private static String stripLegacyFormatting(String text)
	{
		if(text == null || text.isEmpty())
			return "";
		
		StringBuilder sb = new StringBuilder(text.length());
		for(int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if(c == '\u00a7' && i + 1 < text.length())
			{
				i++;
				continue;
			}
			
			sb.append(c);
		}
		
		return sb.toString();
	}
	
	private static double getScaledMouseX(GuiGraphics context)
	{
		Window window = WurstClient.MC.getWindow();
		if(window == null)
			return 0;
		
		return WurstClient.MC.mouseHandler.xpos() * context.guiWidth()
			/ window.getScreenWidth();
	}
	
	private static double getScaledMouseY(GuiGraphics context)
	{
		Window window = WurstClient.MC.getWindow();
		if(window == null)
			return 0;
		
		return WurstClient.MC.mouseHandler.ypos() * context.guiHeight()
			/ window.getScreenHeight();
	}
	
	private void updateHoverBounds(GuiGraphics context, int x, int y,
		float width, float height)
	{
		lastX1 = x;
		lastY1 = (int)(y - height);
		lastX2 = (int)(x + width);
		lastY2 = y;
		hovered = isMouseOverOverlay(context);
	}
	
	private boolean isMouseOverOverlay(GuiGraphics context)
	{
		double mouseX = getScaledMouseX(context);
		double mouseY = getScaledMouseY(context);
		return mouseX >= lastX1 && mouseX <= lastX2 && mouseY >= lastY1
			&& mouseY <= lastY2;
	}
	
	private record Entry(Component component, long timestamp)
	{}
	
	private record RenderLine(FormattedCharSequence text, int alpha)
	{}
}
