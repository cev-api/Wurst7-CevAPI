/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.TextFieldSetting;

@SearchTags({"no player chat", "mute player chat", "chat mute", "join leave",
	"death messages"})
public final class NoPlayerChatHack extends Hack implements ChatInputListener
{
	private static final String CHAT_PREFIX_PATTERN =
		"(?:(?:\\[[^\\]]{1,32}\\]|‹[^›]{1,32}›)\\s*)*";
	private static final String CHAT_NAME_PATTERN =
		"(?:[\\p{S}\\p{P}]{0,6}\\s*)?[A-Za-z0-9_\\-*.]{1,24}";
	private static final Pattern DECORATED_ANGLE_CHAT =
		Pattern.compile("^" + CHAT_PREFIX_PATTERN + "<([^>]{1,32})>\\s+(.+)$");
	private static final Pattern COLON_CHAT = Pattern.compile(
		"^" + CHAT_PREFIX_PATTERN + "(" + CHAT_NAME_PATTERN + "):\\s+(.+)$");
	private static final Pattern ARROW_CHAT = Pattern.compile("^"
		+ CHAT_PREFIX_PATTERN + "(" + CHAT_NAME_PATTERN + ")\\s*[»>]\\s+(.+)$");
	private static final Pattern WHISPER_TO_YOU_CHAT = Pattern.compile(
		"^([A-Za-z0-9_\\-*.]{1,24})\\s+whispers\\s+to\\s+you:\\s+(.+)$",
		Pattern.CASE_INSENSITIVE);
	private static final String[] DEATH_MESSAGE_PHRASES =
		{"was slain", "was shot", "was blown", "was killed", "was fireballed",
			"was roasted", "was doomed", "was squashed", "was pummeled",
			"was pricked", "was poked", "was impaled", "was stung",
			"was struck by lightning", "was finished off", "was obliterated",
			"was killed by magic", "was slain by magic", "burned to death",
			"burnt to a crisp", "went up in flames", "walked into",
			"tried to swim", "tried to escape", "drowned", "starved to death",
			"suffocated", "hit the ground", "experienced kinetic energy",
			"fell from", "fell off", "fell out of the world", "blew up",
			"went off with a bang", "froze to death", "withered away",
			"discovered the floor was lava", "died"};
	
	private final CheckboxSetting allowJoinLeave =
		new CheckboxSetting("Allow join/leave", true);
	private final CheckboxSetting allowWhispers =
		new CheckboxSetting("Allow whispers", true);
	private final CheckboxSetting allowDeathMessages =
		new CheckboxSetting("Allow death messages", true);
	private final CheckboxSetting filterOnly =
		new CheckboxSetting("Filter-only (remove matching)", false);
	private final TextFieldSetting keywordFilter = new TextFieldSetting(
		"Keyword filter",
		"Comma-separated rules. Supports boolean operators: and/or/not, &, |, !, and parentheses.\nExample: scam & !trusted, \"join now\" | giveaway",
		"");
	
	private String cachedFilterRaw = "";
	private List<RuleNode> cachedFilterRules = List.of();
	
	public NoPlayerChatHack()
	{
		super("NoPlayerChat");
		setCategory(Category.CHAT);
		addSetting(allowJoinLeave);
		addSetting(allowWhispers);
		addSetting(allowDeathMessages);
		addSetting(filterOnly);
		addSetting(keywordFilter);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(ChatInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(ChatInputListener.class, this);
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		Component component = event.getComponent();
		String plain = stripLegacyFormatting(component.getString()).trim();
		if(plain.isEmpty())
			return;
		
		// Keep Wurst/system client messages visible.
		if(plain.regionMatches(true, 0, "[Wurst]", 0, 7))
			return;
		
		if(matchesKeywordFilter(plain))
		{
			event.cancel();
			return;
		}
		
		if(isWhisperMessage(plain))
		{
			if(!allowWhispers.isChecked())
				event.cancel();
			return;
		}
		
		if(isPlayerChatMessage(plain))
		{
			if(!filterOnly.isChecked())
				event.cancel();
			return;
		}
		
		if(isJoinLeaveMessage(plain))
		{
			if(!allowJoinLeave.isChecked())
				event.cancel();
			return;
		}
		
		if(isDeathMessage(component, plain) && !allowDeathMessages.isChecked())
			event.cancel();
	}
	
	private boolean matchesKeywordFilter(String message)
	{
		String raw = keywordFilter.getValue();
		if(raw == null || raw.isBlank())
			return false;
		
		if(!raw.equals(cachedFilterRaw))
		{
			cachedFilterRaw = raw;
			cachedFilterRules = compileRules(raw);
		}
		
		if(cachedFilterRules.isEmpty())
			return false;
		
		String lower = message.toLowerCase(Locale.ROOT);
		for(RuleNode rule : cachedFilterRules)
			if(rule.matches(lower))
				return true;
			
		return false;
	}
	
	private static List<RuleNode> compileRules(String raw)
	{
		ArrayList<RuleNode> rules = new ArrayList<>();
		for(String part : splitTopLevelComma(raw))
		{
			String trimmed = part.trim();
			if(trimmed.isEmpty())
				continue;
			
			rules.add(compileRule(trimmed));
		}
		
		return rules;
	}
	
	private static RuleNode compileRule(String expression)
	{
		try
		{
			Parser parser = new Parser(expression);
			RuleNode root = parser.parseExpression();
			if(root != null && parser.atEnd())
				return root;
			
		}catch(RuntimeException ignored)
		{}
		
		// Fallback: invalid expression behaves like simple substring filter.
		return new TermNode(unquote(expression).toLowerCase(Locale.ROOT));
	}
	
	private static List<String> splitTopLevelComma(String input)
	{
		ArrayList<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder(input.length());
		int depth = 0;
		boolean inQuote = false;
		
		for(int i = 0; i < input.length(); i++)
		{
			char c = input.charAt(i);
			if(c == '"' && (i == 0 || input.charAt(i - 1) != '\\'))
			{
				inQuote = !inQuote;
				current.append(c);
				continue;
			}
			
			if(!inQuote)
			{
				if(c == '(')
				{
					depth++;
					current.append(c);
					continue;
				}
				
				if(c == ')')
				{
					if(depth > 0)
						depth--;
					current.append(c);
					continue;
				}
				
				if(c == ',' && depth == 0)
				{
					parts.add(current.toString());
					current.setLength(0);
					continue;
				}
			}
			
			current.append(c);
		}
		
		parts.add(current.toString());
		return parts;
	}
	
	private static String unquote(String text)
	{
		String t = text.trim();
		if(t.length() >= 2 && t.startsWith("\"") && t.endsWith("\""))
			return t.substring(1, t.length() - 1);
		return t;
	}
	
	private static boolean isPlayerChatMessage(String plain)
	{
		return DECORATED_ANGLE_CHAT.matcher(plain).matches()
			|| COLON_CHAT.matcher(plain).matches()
			|| ARROW_CHAT.matcher(plain).matches();
	}
	
	private static boolean isWhisperMessage(String plain)
	{
		return WHISPER_TO_YOU_CHAT.matcher(plain).matches();
	}
	
	private static boolean isJoinLeaveMessage(String plain)
	{
		String lower = plain.toLowerCase(Locale.ROOT);
		return lower.contains(" joined the game")
			|| lower.contains(" left the game")
			|| lower.contains(" disconnected") || lower.contains(" logged in")
			|| lower.contains(" logged out");
	}
	
	private static boolean isDeathMessage(Component component, String plain)
	{
		if(component != null
			&& component.getContents() instanceof TranslatableContents tr
			&& tr.getKey().startsWith("death."))
			return true;
		
		String lower = plain.toLowerCase(Locale.ROOT);
		if(lower.contains("left the game") || lower.contains("joined the game"))
			return false;
		
		for(String phrase : DEATH_MESSAGE_PHRASES)
			if(lower.contains(phrase))
				return true;
			
		return false;
	}
	
	private interface RuleNode
	{
		boolean matches(String text);
	}
	
	private record TermNode(String term) implements RuleNode
	{
		@Override
		public boolean matches(String text)
		{
			return !term.isEmpty() && text.contains(term);
		}
	}
	
	private record AndNode(RuleNode left, RuleNode right) implements RuleNode
	{
		@Override
		public boolean matches(String text)
		{
			return left.matches(text) && right.matches(text);
		}
	}
	
	private record OrNode(RuleNode left, RuleNode right) implements RuleNode
	{
		@Override
		public boolean matches(String text)
		{
			return left.matches(text) || right.matches(text);
		}
	}
	
	private record NotNode(RuleNode inner) implements RuleNode
	{
		@Override
		public boolean matches(String text)
		{
			return !inner.matches(text);
		}
	}
	
	private static final class Parser
	{
		private final String input;
		private int pos;
		
		private Parser(String input)
		{
			this.input = input;
		}
		
		private RuleNode parseExpression()
		{
			return parseOr();
		}
		
		private RuleNode parseOr()
		{
			RuleNode node = parseAnd();
			while(true)
			{
				skipWhitespace();
				if(match("||") || match("|") || matchWord("or"))
					node = new OrNode(node, parseAnd());
				else
					return node;
			}
		}
		
		private RuleNode parseAnd()
		{
			RuleNode node = parseUnary();
			while(true)
			{
				skipWhitespace();
				if(match("&&") || match("&") || matchWord("and"))
					node = new AndNode(node, parseUnary());
				else
					return node;
			}
		}
		
		private RuleNode parseUnary()
		{
			skipWhitespace();
			if(match("!"))
				return new NotNode(parseUnary());
			if(matchWord("not"))
				return new NotNode(parseUnary());
			return parsePrimary();
		}
		
		private RuleNode parsePrimary()
		{
			skipWhitespace();
			if(match("("))
			{
				RuleNode inside = parseExpression();
				skipWhitespace();
				if(!match(")"))
					throw new IllegalArgumentException("Missing ')'");
				return inside;
			}
			
			String term = parseTerm();
			if(term.isEmpty())
				throw new IllegalArgumentException("Missing term");
			return new TermNode(term.toLowerCase(Locale.ROOT));
		}
		
		private String parseTerm()
		{
			skipWhitespace();
			if(atEnd())
				return "";
			
			if(peek() == '"')
			{
				pos++;
				StringBuilder quoted = new StringBuilder();
				while(!atEnd())
				{
					char c = input.charAt(pos++);
					if(c == '"' && (pos < 2 || input.charAt(pos - 2) != '\\'))
						break;
					quoted.append(c);
				}
				return quoted.toString();
			}
			
			StringBuilder plain = new StringBuilder();
			while(!atEnd())
			{
				char c = peek();
				if(Character.isWhitespace(c) || c == '(' || c == ')' || c == '!'
					|| c == '&' || c == '|' || c == ',')
					break;
				plain.append(c);
				pos++;
			}
			
			return plain.toString();
		}
		
		private boolean atEnd()
		{
			skipWhitespace();
			return pos >= input.length();
		}
		
		private void skipWhitespace()
		{
			while(pos < input.length()
				&& Character.isWhitespace(input.charAt(pos)))
				pos++;
		}
		
		private char peek()
		{
			return input.charAt(pos);
		}
		
		private boolean match(String s)
		{
			if(input.regionMatches(pos, s, 0, s.length()))
			{
				pos += s.length();
				return true;
			}
			return false;
		}
		
		private boolean matchWord(String word)
		{
			int start = pos;
			int end = pos + word.length();
			if(end > input.length())
				return false;
			
			if(!input.regionMatches(true, start, word, 0, word.length()))
				return false;
			
			boolean leftBoundary = start == 0
				|| !Character.isLetterOrDigit(input.charAt(start - 1));
			boolean rightBoundary = end >= input.length()
				|| !Character.isLetterOrDigit(input.charAt(end));
			if(leftBoundary && rightBoundary)
			{
				pos = end;
				return true;
			}
			
			return false;
		}
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
}
