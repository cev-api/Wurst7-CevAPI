/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.hack.HackList;

public enum ChatUtils
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	public static final String WURST_PREFIX =
		"\u00a7c[\u00a76Wurst\u00a7c]\u00a7r ";
	private static final String WARNING_PREFIX =
		"\u00a7c[\u00a76\u00a7lWARNING\u00a7c]\u00a7r ";
	private static final String ERROR_PREFIX =
		"\u00a7c[\u00a74\u00a7lERROR\u00a7c]\u00a7r ";
	private static final String SYNTAX_ERROR_PREFIX =
		"\u00a74Syntax error:\u00a7r ";
	private static final StackWalker STACK_WALKER =
		StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
	
	private static boolean enabled = true;
	
	public static void setEnabled(boolean enabled)
	{
		ChatUtils.enabled = enabled;
	}
	
	public static void component(Component component)
	{
		if(!enabled)
			return;
		if(MC == null)
			return;
		
		MC.execute(() -> {
			if(!enabled || MC.gui == null)
				return;
			
			ChatComponent chatHud = MC.gui.hud.getChat();
			MutableComponent prefix = Component.literal(WURST_PREFIX);
			chatHud.addClientSystemMessage(prefix.append(component));
		});
	}
	
	public static void message(String message)
	{
		String withHackPrefix = maybeAddCallerHackPrefix(message);
		component(colorHackPrefixComponent(withHackPrefix));
	}
	
	public static void warning(String message)
	{
		message(WARNING_PREFIX + message);
	}
	
	public static void error(String message)
	{
		message(ERROR_PREFIX + message);
	}
	
	public static void syntaxError(String message)
	{
		message(SYNTAX_ERROR_PREFIX + message);
	}
	
	public static String getAsString(GuiMessage.Line visible)
	{
		return getAsString(visible.content());
	}
	
	public static String getAsString(FormattedCharSequence text)
	{
		JustGiveMeTheStringVisitor visitor = new JustGiveMeTheStringVisitor();
		text.accept(visitor);
		return visitor.toString();
	}
	
	public static final String wrapText(String text, int width)
	{
		return wrapText(text, width, Style.EMPTY);
	}
	
	public static final String wrapText(String text, int width, Style style)
	{
		List<FormattedText> lines =
			MC.font.getSplitter().splitLines(text, width, Style.EMPTY);
		
		StringJoiner joiner = new StringJoiner("\n");
		lines.stream().map(FormattedText::getString)
			.forEach(s -> joiner.add(s));
		
		return joiner.toString();
	}
	
	private static Component colorHackPrefixComponent(String message)
	{
		if(message == null || message.isEmpty())
			return Component.empty();
		
		HackList hacks =
			WurstClient.INSTANCE == null ? null : WurstClient.INSTANCE.getHax();
		if(hacks == null)
			return Component.literal(message).withStyle(ChatFormatting.WHITE);
		
		for(Hack hack : hacks.getAllHax())
		{
			int color = hack.getHackListColorI(255);
			if(color == -1)
				continue;
			
			String name = hack.getName();
			String bracketPrefix = "[" + name + "]";
			if(message.startsWith(bracketPrefix))
				return buildColoredPrefixComponent(message,
					bracketPrefix.length(), color);
			
			String colonPrefix = name + ":";
			if(message.startsWith(colonPrefix))
				return buildColoredPrefixComponent(message, name.length(),
					color);
		}
		
		return Component.literal(message).withStyle(ChatFormatting.WHITE);
	}
	
	private static Component buildColoredPrefixComponent(String message,
		int prefixLength, int argb)
	{
		int rgb = argb & 0x00FFFFFF;
		MutableComponent out = Component.empty();
		out.append(Component.literal(message.substring(0, prefixLength))
			.withStyle(s -> s.withColor(TextColor.fromRgb(rgb))));
		out.append(Component.literal(message.substring(prefixLength))
			.withStyle(ChatFormatting.WHITE));
		return out;
	}
	
	private static String maybeAddCallerHackPrefix(String message)
	{
		if(message == null || message.isEmpty())
			return message;
		
		HackList hacks =
			WurstClient.INSTANCE == null ? null : WurstClient.INSTANCE.getHax();
		if(hacks == null)
			return message;
		
		Hack callerHack = findCallerHack(hacks);
		if(callerHack == null)
			return message;
		
		String hackName = callerHack.getName();
		if(hasHackPrefix(message, hackName))
			return message;
		
		return "[" + hackName + "] " + message;
	}
	
	private static Hack findCallerHack(HackList hacks)
	{
		return STACK_WALKER.walk(
			frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
				.filter(c -> c != ChatUtils.class)
				.map(c -> resolveHackForClass(hacks, c))
				.filter(Objects::nonNull).findFirst())
			.orElse(null);
	}
	
	private static Hack resolveHackForClass(HackList hacks, Class<?> cls)
	{
		Class<?> current = cls;
		while(current != null)
		{
			if(Hack.class.isAssignableFrom(current))
				for(Hack hack : hacks.getAllHax())
					if(hack.getClass() == current)
						return hack;
					
			current = current.getEnclosingClass();
		}
		
		return null;
	}
	
	private static boolean hasHackPrefix(String message, String hackName)
	{
		if(hasHackPrefixAt(message, hackName, 0))
			return true;
		
		if(message.startsWith(WARNING_PREFIX))
			return hasHackPrefixAt(message, hackName, WARNING_PREFIX.length());
		if(message.startsWith(ERROR_PREFIX))
			return hasHackPrefixAt(message, hackName, ERROR_PREFIX.length());
		if(message.startsWith(SYNTAX_ERROR_PREFIX))
			return hasHackPrefixAt(message, hackName,
				SYNTAX_ERROR_PREFIX.length());
		
		return false;
	}
	
	private static boolean hasHackPrefixAt(String message, String hackName,
		int offset)
	{
		if(offset < 0 || offset >= message.length())
			return false;
		
		String bracket = "[" + hackName + "]";
		if(message.startsWith(bracket, offset))
			return true;
		
		String colon = hackName + ":";
		return message.startsWith(colon, offset);
	}
}
