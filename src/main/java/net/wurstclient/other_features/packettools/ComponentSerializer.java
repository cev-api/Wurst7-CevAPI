/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.NbtContents;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.ScoreContents;
import net.minecraft.network.chat.contents.SelectorContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.network.chat.contents.objects.ObjectInfo;
import net.minecraft.network.chat.contents.objects.PlayerSprite;

/**
 * Recursively serializes chat components, keeping the styles, click events and
 * hover events that plain text would lose. Content types that are not literal
 * text - translatable, keybind, score, selector, nbt, object - keep their
 * identifying data as well, and hover text recurses back into this class.
 * <p>
 * A tree built from nothing but unstyled literal text still collapses to its
 * string form, so ordinary messages stay short.
 */
public final class ComponentSerializer
{
	/** Recursive decoder for the values that are not components themselves. */
	@FunctionalInterface
	public interface Fallback
	{
		Object decode(Object value, int depth);
	}
	
	private static final int MAX_COMPONENT_DEPTH = 20;
	private static final int MAX_CHILD_COMPONENTS = 256;
	private static final int MAX_TREE_NODES = 512;
	private static final int MAX_TRANSLATION_ARGS = 64;
	
	/**
	 * {@code Style} keeps its flags as tri-state values, so reading the fields
	 * is the only way to tell an explicit false from an unset flag.
	 */
	private static final Field STYLE_BOLD = findStyleField("bold");
	private static final Field STYLE_ITALIC = findStyleField("italic");
	private static final Field STYLE_UNDERLINED = findStyleField("underlined");
	private static final Field STYLE_STRIKETHROUGH =
		findStyleField("strikethrough");
	private static final Field STYLE_OBFUSCATED = findStyleField("obfuscated");
	
	private ComponentSerializer()
	{}
	
	/**
	 * @return the plain text when the whole tree is unstyled literal text,
	 *         otherwise a map holding every component, style and event.
	 */
	public static Object serialize(Component component, int depth,
		Fallback fallback)
	{
		if(component == null)
			return null;
		if(depth > MAX_COMPONENT_DEPTH)
			return "_MAX_DEPTH";
		if(isPlainLiteralTree(component))
			return textOf(component);
		return serializeDetailed(component, depth, fallback);
	}
	
	/**
	 * @return the full component map, or null when no component in the tree
	 *         carries a style, so callers that already have the plain text
	 *         don't have to store a second copy of it.
	 */
	public static Map<String, Object> serializeIfStyled(Component component,
		int depth, Fallback fallback)
	{
		if(component == null || depth > MAX_COMPONENT_DEPTH
			|| !hasStyle(component))
			return null;
		return serializeDetailed(component, depth, fallback);
	}
	
	private static Map<String, Object> serializeDetailed(Component component,
		int depth, Fallback fallback)
	{
		Map<String, Object> node = new LinkedHashMap<>();
		node.put("_type", "Component");
		node.put("text", textOf(component));
		node.put("contents",
			serializeContents(component.getContents(), depth, fallback));
		
		Map<String, Object> style =
			serializeStyle(component.getStyle(), depth, fallback);
		if(!style.isEmpty())
			node.put("style", style);
		
		List<Component> siblings = component.getSiblings();
		if(siblings != null && !siblings.isEmpty())
			node.put("siblings", serializeChildren(siblings, depth, fallback));
		
		return node;
	}
	
	private static List<Object> serializeChildren(List<Component> children,
		int depth, Fallback fallback)
	{
		List<Object> out = new ArrayList<>();
		int count = 0;
		for(Component child : children)
		{
			if(count++ >= MAX_CHILD_COMPONENTS)
			{
				out.add(Map.of("_truncated", true, "_remainingCount",
					children.size() - MAX_CHILD_COMPONENTS));
				break;
			}
			out.add(decode(child, depth, fallback));
		}
		return out;
	}
	
	/**
	 * Decodes a nested value through the caller's recursive decoder, so that
	 * repeated instances are still caught by its cycle detection.
	 */
	private static Object decode(Object value, int depth, Fallback fallback)
	{
		if(value == null)
			return null;
		
		if(fallback != null)
			return fallback.decode(value, depth + 1);
		
		if(value instanceof Component component)
			return serialize(component, depth + 1, null);
		if(value instanceof Optional<?> optional)
			return optional.map(v -> decode(v, depth + 1, null)).orElse(null);
		return String.valueOf(value);
	}
	
	// ---- Contents ----
	
	private static Object serializeContents(ComponentContents contents,
		int depth, Fallback fallback)
	{
		if(contents == null)
			return null;
		
		Map<String, Object> m = new LinkedHashMap<>();
		
		if(contents instanceof PlainTextContents plain)
		{
			m.put("kind", "text");
			m.put("text", plain.text());
			return m;
		}
		
		if(contents instanceof TranslatableContents translatable)
		{
			m.put("kind", "translatable");
			m.put("key", translatable.getKey());
			if(translatable.getFallback() != null)
				m.put("fallback", translatable.getFallback());
			Object[] args = translatable.getArgs();
			if(args != null && args.length > 0)
				m.put("args", serializeArgs(args, depth, fallback));
			return m;
		}
		
		if(contents instanceof KeybindContents keybind)
		{
			m.put("kind", "keybind");
			m.put("name", keybind.getName());
			return m;
		}
		
		if(contents instanceof ScoreContents score)
		{
			m.put("kind", "score");
			m.put("name", decode(score.name(), depth, fallback));
			m.put("objective", score.objective());
			return m;
		}
		
		if(contents instanceof SelectorContents selector)
		{
			m.put("kind", "selector");
			m.put("selector", decode(selector.selector(), depth, fallback));
			selector.separator()
				.ifPresent(s -> m.put("separator", decode(s, depth, fallback)));
			return m;
		}
		
		if(contents instanceof NbtContents nbt)
		{
			m.put("kind", "nbt");
			m.put("nbtPath", decode(nbt.nbtPath(), depth, fallback));
			m.put("interpreting", nbt.interpreting());
			m.put("plain", nbt.plain());
			nbt.separator()
				.ifPresent(s -> m.put("separator", decode(s, depth, fallback)));
			m.put("dataSource", decode(nbt.dataSource(), depth, fallback));
			return m;
		}
		
		if(contents instanceof ObjectContents object)
		{
			m.put("kind", "object");
			m.put("object",
				serializeObjectInfo(object.contents(), depth, fallback));
			object.fallback()
				.ifPresent(f -> m.put("fallback", decode(f, depth, fallback)));
			return m;
		}
		
		// Unknown content types still get decoded generically.
		m.put("kind", contents.getClass().getSimpleName());
		mergeGeneric(m, contents, depth, fallback);
		return m;
	}
	
	private static List<Object> serializeArgs(Object[] args, int depth,
		Fallback fallback)
	{
		List<Object> out = new ArrayList<>();
		int count = 0;
		for(Object arg : args)
		{
			if(count++ >= MAX_TRANSLATION_ARGS)
			{
				out.add(Map.of("_truncated", true, "_remainingCount",
					args.length - MAX_TRANSLATION_ARGS));
				break;
			}
			out.add(decode(arg, depth, fallback));
		}
		return out;
	}
	
	private static Object serializeObjectInfo(ObjectInfo info, int depth,
		Fallback fallback)
	{
		if(info == null)
			return null;
		
		Map<String, Object> m = new LinkedHashMap<>();
		
		if(info instanceof AtlasSprite atlas)
		{
			m.put("kind", "atlas_sprite");
			m.put("atlas", String.valueOf(atlas.atlas()));
			m.put("sprite", String.valueOf(atlas.sprite()));
			
		}else if(info instanceof PlayerSprite player)
		{
			m.put("kind", "player_sprite");
			m.put("hat", player.hat());
			m.put("player", decode(player.player(), depth, fallback));
			
		}else
		{
			m.put("kind", info.getClass().getSimpleName());
			mergeGeneric(m, info, depth, fallback);
		}
		
		m.put("defaultFallback", info.defaultFallback());
		return m;
	}
	
	// ---- Style ----
	
	private static Map<String, Object> serializeStyle(Style style, int depth,
		Fallback fallback)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		if(style == null || style.isEmpty())
			return m;
		
		TextColor color = style.getColor();
		if(color != null)
			m.put("color", color.serialize());
		
		Integer shadowColor = style.getShadowColor();
		if(shadowColor != null)
			m.put("shadowColor",
				String.format("#%06X", shadowColor.intValue() & 0xFFFFFF));
		
		putFlag(m, "bold", STYLE_BOLD, style, style.isBold());
		putFlag(m, "italic", STYLE_ITALIC, style, style.isItalic());
		putFlag(m, "underlined", STYLE_UNDERLINED, style, style.isUnderlined());
		putFlag(m, "strikethrough", STYLE_STRIKETHROUGH, style,
			style.isStrikethrough());
		putFlag(m, "obfuscated", STYLE_OBFUSCATED, style, style.isObfuscated());
		
		if(style.getClickEvent() != null)
			m.put("clickEvent",
				serializeClickEvent(style.getClickEvent(), depth, fallback));
		
		if(style.getHoverEvent() != null)
			m.put("hoverEvent",
				serializeHoverEvent(style.getHoverEvent(), depth, fallback));
		
		if(style.getInsertion() != null)
			m.put("insertion", style.getInsertion());
		
		FontDescription font = style.getFont();
		if(font != null && !FontDescription.DEFAULT.equals(font))
			m.put("font", serializeFont(font, depth, fallback));
		
		return m;
	}
	
	/**
	 * Writes a style flag only when the packet actually set it, so an explicit
	 * false is preserved and inherited flags are not invented.
	 */
	private static void putFlag(Map<String, Object> m, String name, Field field,
		Style style, boolean fallbackValue)
	{
		Boolean value = field != null ? readBoolean(field, style) : null;
		if(value == null && fallbackValue)
			value = Boolean.TRUE;
		if(value != null)
			m.put(name, value);
	}
	
	private static Map<String, Object> serializeClickEvent(ClickEvent event,
		int depth, Fallback fallback)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("_type", "ClickEvent");
		m.put("action",
			event.action() != null ? event.action().getSerializedName() : null);
		
		if(event instanceof ClickEvent.RunCommand e)
			m.put("command", e.command());
		else if(event instanceof ClickEvent.SuggestCommand e)
			m.put("command", e.command());
		else if(event instanceof ClickEvent.OpenUrl e)
			m.put("url", String.valueOf(e.uri()));
		else if(event instanceof ClickEvent.OpenFile e)
			m.put("path", e.path());
		else if(event instanceof ClickEvent.CopyToClipboard e)
			m.put("value", e.value());
		else if(event instanceof ClickEvent.ChangePage e)
			m.put("page", e.page());
		else if(event instanceof ClickEvent.Custom e)
		{
			m.put("id", String.valueOf(e.id()));
			e.payload()
				.ifPresent(p -> m.put("payload", decode(p, depth, fallback)));
			
		}else if(event instanceof ClickEvent.ShowDialog e)
			m.put("dialog", decode(e.dialog(), depth, fallback));
		else
			mergeGeneric(m, event, depth, fallback);
		
		return m;
	}
	
	private static Map<String, Object> serializeHoverEvent(HoverEvent event,
		int depth, Fallback fallback)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("_type", "HoverEvent");
		m.put("action",
			event.action() != null ? event.action().getSerializedName() : null);
		
		if(event instanceof HoverEvent.ShowText e)
			m.put("value", decode(e.value(), depth, fallback));
		else if(event instanceof HoverEvent.ShowItem e)
			m.put("item", decode(e.item(), depth, fallback));
		else if(event instanceof HoverEvent.ShowEntity e)
			m.put("entity", decode(e.entity(), depth, fallback));
		else
			mergeGeneric(m, event, depth, fallback);
		
		return m;
	}
	
	private static Object serializeFont(FontDescription font, int depth,
		Fallback fallback)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		
		if(font instanceof FontDescription.Resource resource)
		{
			m.put("kind", "resource");
			m.put("id", String.valueOf(resource.id()));
			
		}else if(font instanceof FontDescription.AtlasSprite atlas)
		{
			m.put("kind", "atlas_sprite");
			m.put("atlas", String.valueOf(atlas.atlasId()));
			m.put("sprite", String.valueOf(atlas.spriteId()));
			
		}else if(font instanceof FontDescription.PlayerSprite player)
		{
			m.put("kind", "player_sprite");
			m.put("hat", player.hat());
			m.put("profile", decode(player.profile(), depth, fallback));
			
		}else
		{
			m.put("kind", font.getClass().getSimpleName());
			mergeGeneric(m, font, depth, fallback);
		}
		
		return m;
	}
	
	// ---- Helpers ----
	
	private static void mergeGeneric(Map<String, Object> out, Object value,
		int depth, Fallback fallback)
	{
		if(fallback == null)
			return;
		Object generic = fallback.decode(value, depth + 1);
		if(!(generic instanceof Map<?, ?> map))
			return;
		for(Map.Entry<?, ?> e : map.entrySet())
			out.putIfAbsent(String.valueOf(e.getKey()), e.getValue());
	}
	
	private static boolean isPlainLiteralTree(Component root)
	{
		ArrayDeque<Component> pending = new ArrayDeque<>();
		pending.add(root);
		int nodes = 0;
		
		while(!pending.isEmpty())
		{
			Component c = pending.removeFirst();
			if(c == null)
				continue;
			if(++nodes > MAX_TREE_NODES)
				return false;
			if(!(c.getContents() instanceof PlainTextContents))
				return false;
			if(!isEmpty(c.getStyle()))
				return false;
			pending.addAll(c.getSiblings());
		}
		return true;
	}
	
	private static boolean hasStyle(Component root)
	{
		ArrayDeque<Component> pending = new ArrayDeque<>();
		pending.add(root);
		int nodes = 0;
		
		while(!pending.isEmpty())
		{
			Component c = pending.removeFirst();
			if(c == null)
				continue;
			if(++nodes > MAX_TREE_NODES)
				return false;
			if(!isEmpty(c.getStyle()))
				return true;
			pending.addAll(c.getSiblings());
		}
		return false;
	}
	
	private static boolean isEmpty(Style style)
	{
		return style == null || style.isEmpty();
	}
	
	private static String textOf(Component component)
	{
		try
		{
			return component.getString();
			
		}catch(RuntimeException e)
		{
			return null;
		}
	}
	
	private static Boolean readBoolean(Field field, Object target)
	{
		try
		{
			Object value = field.get(target);
			return value instanceof Boolean b ? b : null;
			
		}catch(ReflectiveOperationException | RuntimeException e)
		{
			return null;
		}
	}
	
	private static Field findStyleField(String name)
	{
		try
		{
			Field field = Style.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
			
		}catch(ReflectiveOperationException e)
		{
			return null;
		}
	}
}
