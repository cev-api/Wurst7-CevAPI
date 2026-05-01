/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatOutputListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hud.ClientMessageOverlay;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

@SearchTags({"mention", "name mention", "ping", "chat ping", "nickname ping"})
public final class MentionHack extends Hack
	implements ChatInputListener, ChatOutputListener
{
	private static final long SELF_ECHO_WINDOW_MS = 5000L;
	private static final int JOIN_GRACE_TICKS = 200;
	
	private static final String CHAT_PREFIX_PATTERN =
		"(?:(?:\\[[^\\]]{1,32}\\]|‹[^›]{1,32}›)\\s*)*";
	private static final Pattern DECORATED_ANGLE_CHAT =
		Pattern.compile("^" + CHAT_PREFIX_PATTERN + "<([^>]{1,32})>\\s+(.+)$");
	private static final Pattern COLON_CHAT = Pattern.compile(
		"^" + CHAT_PREFIX_PATTERN + "([A-Za-z0-9_\\-*.]{1,24}):\\s+(.+)$");
	private static final Pattern ARROW_CHAT = Pattern.compile("^"
		+ CHAT_PREFIX_PATTERN + "([A-Za-z0-9_\\-*.]{1,24})\\s*[»>]\\s+(.+)$");
	private static final Pattern WHISPER_TO_YOU_CHAT = Pattern.compile(
		"^.*?([A-Za-z0-9_\\-*.]{1,24})\\s+"
			+ "(?:whispers|msgs|messages)\\s+(?:to\\s+)?you:\\s+(.+)$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern INCOMING_ARROW_WHISPER_CHAT = Pattern.compile(
		"^\\s*" + CHAT_PREFIX_PATTERN + "(?:←|<-)\\s*" + CHAT_PREFIX_PATTERN
			+ "([A-Za-z0-9_\\-*.]{1,24})\\s*[»>]\\s+(.+)$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern OUTGOING_ARROW_WHISPER_CHAT = Pattern.compile(
		"^\\s*" + CHAT_PREFIX_PATTERN + "(?:→|->)\\s*" + CHAT_PREFIX_PATTERN
			+ "([A-Za-z0-9_\\-*.]{1,24})\\s*[»>]\\s+(.+)$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern FROM_WHISPER_CHAT = Pattern.compile(
		"^" + CHAT_PREFIX_PATTERN + "from\\s+"
			+ "([A-Za-z0-9_\\-*.]{1,24})\\s*[:>]\\s+(.+)$",
		Pattern.CASE_INSENSITIVE);
	
	private final TextFieldSetting nicknameAliases = new TextFieldSetting(
		"Nickname aliases", "Optional comma-separated nicknames that should"
			+ " trigger a mention sound (e.g. neco, conneco).",
		"");
	
	private final EnumSetting<NoteBlockSound> sound = new EnumSetting<>("Sound",
		"Which note-block sound should play when you're mentioned.",
		NoteBlockSound.values(), NoteBlockSound.CHIME);
	
	private final SliderSetting volume =
		new SliderSetting("Volume", "Sound volume for mention pings.", 100, 0,
			200, 1, ValueDisplay.INTEGER.withSuffix("%"));
	
	private final CheckboxSetting toastPopup =
		new CheckboxSetting("Toast popup",
			"Shows a toast notification when you're mentioned or whispered to.",
			false);
	
	private final CheckboxSetting colorMessages =
		new CheckboxSetting("Color messages",
			"Changes the color of messages that mention you.", true);
	
	private final ColorSetting mentionColor = new ColorSetting("Mention color",
		"Color used for messages that mention your name/alias or whisper to"
			+ " you.",
		new Color(0x55FFFF));
	
	private final ArrayDeque<RecentSentMessage> recentSentMessages =
		new ArrayDeque<>();
	
	public MentionHack()
	{
		super("Mention");
		setCategory(Category.CHAT);
		addSetting(nicknameAliases);
		addSetting(sound);
		addSetting(volume);
		addSetting(toastPopup);
		addSetting(colorMessages);
		addSetting(mentionColor);
	}
	
	public int getMentionColorI()
	{
		return mentionColor.getColorI() & 0x00FFFFFF;
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(ChatInputListener.class, this);
		EVENTS.add(ChatOutputListener.class, this);
		synchronized(recentSentMessages)
		{
			recentSentMessages.clear();
		}
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(ChatInputListener.class, this);
		EVENTS.remove(ChatOutputListener.class, this);
		synchronized(recentSentMessages)
		{
			recentSentMessages.clear();
		}
	}
	
	@Override
	public void onSentMessage(ChatOutputEvent event)
	{
		String normalized = normalizeOutgoingMessage(event.getMessage());
		if(normalized.isEmpty())
			return;
		
		long now = System.currentTimeMillis();
		synchronized(recentSentMessages)
		{
			pruneRecentMessages(now);
			if(recentSentMessages.size() >= 20)
				recentSentMessages.removeFirst();
			
			recentSentMessages.addLast(new RecentSentMessage(normalized, now));
		}
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		if(MC.player == null || MC.level == null)
			return;
		
		String ownName = MC.getUser().getName();
		String plain =
			stripLegacyFormatting(event.getComponent().getString()).trim();
		if(plain.isEmpty() || plain.regionMatches(true, 0, "[Wurst]", 0, 7))
			return;
		
		ChatLine line = parseChatLine(plain);
		if(line.outgoingWhisper())
			return;
		if(line.sender().equalsIgnoreCase(ownName))
			return;
		if(isLikelyJoinLeaveAnnouncement(line, ownName))
			return;
		if(line.sender().equals("System") && isLikelyOwnEcho(line.text()))
			return;
		
		boolean isMention = containsMention(line.text(), ownName);
		boolean isWhisperToYou = line.whisperToYou();
		if(!isMention && !isWhisperToYou)
			return;
		
		if(colorMessages.isChecked())
			colorizeMentionMessage(event, plain, line);
		playMentionSound();
		showMentionToast(event.getComponent(), isWhisperToYou);
	}
	
	private void colorizeMentionMessage(ChatInputEvent event, String plain,
		ChatLine line)
	{
		event.setComponent(
			colorizeMentionComponent(event.getComponent(), plain, line));
	}
	
	public Component colorizeForDisplayIfNeeded(Component component)
	{
		if(!isEnabled() || !colorMessages.isChecked() || MC.player == null
			|| MC.level == null || component == null)
			return component;
		
		String ownName = MC.getUser().getName();
		String plain = stripLegacyFormatting(component.getString()).trim();
		if(plain.isEmpty() || plain.regionMatches(true, 0, "[Wurst]", 0, 7))
			return component;
		
		ChatLine line = parseChatLine(plain);
		if(line.outgoingWhisper())
			return component;
		if(line.sender().equalsIgnoreCase(ownName))
			return component;
		if(isLikelyJoinLeaveAnnouncement(line, ownName))
			return component;
		
		boolean isMention = containsMention(line.text(), ownName);
		if(!isMention && !line.whisperToYou())
			return component;
		
		return colorizeMentionComponent(component, plain, line);
	}
	
	private Component colorizeMentionComponent(Component component,
		String plain, ChatLine line)
	{
		int rgb = mentionColor.getColorI() & 0x00FFFFFF;
		int textStart = line.textStart();
		if(textStart <= 0 || textStart >= plain.length())
			return ClientMessageOverlay.colorizeWholeComponent(component, rgb);
		
		return ClientMessageOverlay.colorizeComponentRangeForDisplay(component,
			textStart, plain.length(), rgb);
	}
	
	private void showMentionToast(Component message, boolean whisperToYou)
	{
		if(!toastPopup.isChecked())
			return;
		
		MutableComponent title =
			Component.literal(whisperToYou ? "Whisper" : "Mention");
		MutableComponent toastMessage = removeColorFromComponent(message);
		
		SystemToast toast = SystemToast.multiline(MC,
			SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title,
			toastMessage);
		MC.getToastManager().addToast(toast);
	}
	
	private MutableComponent removeColorFromComponent(Component component)
	{
		MutableComponent result;
		if(component.getContents() instanceof TranslatableContents tr)
		{
			Object[] args = tr.getArgs();
			Object[] newArgs = args == null ? new Object[0] : args.clone();
			for(int i = 0; i < newArgs.length; i++)
			{
				if(newArgs[i] instanceof Component argComponent)
					newArgs[i] = removeColorFromComponent(argComponent);
				else if(newArgs[i] instanceof String argString)
					newArgs[i] = Component.literal(argString)
						.withStyle(style -> style.withColor((TextColor)null));
			}
			
			result = Component.translatable(tr.getKey(), newArgs);
			
		}else if(component.getContents() instanceof LiteralContents literal)
			result = Component.literal(literal.text());
		else
			result = MutableComponent.create(component.getContents());
		
		result.setStyle(component.getStyle().withColor((TextColor)null));
		for(Component sibling : component.getSiblings())
			result.append(removeColorFromComponent(sibling));
		
		return result;
	}
	
	private boolean containsMention(String text, String ownName)
	{
		for(String variant : getNameVariants(ownName))
		{
			Pattern mentionPattern = Pattern
				.compile("(?i)(^|\\W)@?" + Pattern.quote(variant) + "(\\W|$)");
			if(mentionPattern.matcher(text).find())
				return true;
		}
		
		return false;
	}
	
	private boolean containsNameVariant(String text, String ownName)
	{
		if(text == null || text.isBlank())
			return false;
		
		for(String variant : getNameVariants(ownName))
		{
			Pattern namePattern = Pattern
				.compile("(?i)(^|\\W)" + Pattern.quote(variant) + "(\\W|$)");
			if(namePattern.matcher(text).find())
				return true;
		}
		
		return false;
	}
	
	private boolean isLikelyJoinLeaveAnnouncement(ChatLine line, String ownName)
	{
		if(line == null || ownName == null || ownName.isBlank())
			return false;
		if(!"System".equals(line.sender()))
			return false;
		
		String text = line.text();
		if(!containsNameVariant(text, ownName))
			return false;
		
		String lower = text.toLowerCase(Locale.ROOT);
		boolean joinLeaveKeywords = lower.contains(" joined")
			|| lower.contains(" left") || lower.contains(" disconnected")
			|| lower.contains(" logged in") || lower.contains(" logged out")
			|| lower.contains("welcome");
		
		// Also ignore unnamed join banners shortly after connecting.
		boolean withinJoinGrace =
			MC.player != null && MC.player.tickCount <= JOIN_GRACE_TICKS;
		return joinLeaveKeywords || withinJoinGrace;
	}
	
	private Set<String> getNameVariants(String ownName)
	{
		LinkedHashSet<String> variants = new LinkedHashSet<>();
		String ownLower = ownName.toLowerCase(Locale.ROOT);
		variants.add(ownLower);
		
		if(ownLower.length() >= 4)
			variants.add(ownLower.substring(0, 4));
		
		for(String part : ownName.split("[_\\-]"))
		{
			String p = part.toLowerCase(Locale.ROOT).trim();
			if(p.length() >= 3)
				variants.add(p);
		}
		
		for(String part : ownName.split("(?<=[a-z])(?=[A-Z])"))
		{
			String p = part.toLowerCase(Locale.ROOT).trim();
			if(p.length() >= 3)
				variants.add(p);
		}
		
		for(String alias : nicknameAliases.getValue().split(","))
		{
			String a = alias.toLowerCase(Locale.ROOT).trim();
			if(a.length() >= 2)
				variants.add(a);
		}
		
		return variants;
	}
	
	private void playMentionSound()
	{
		SoundEvent soundEvent = sound.getSelected().resolve();
		if(soundEvent == null)
			return;
		
		float target = (float)(volume.getValue() / 100.0);
		if(target <= 0f)
			return;
		
		int whole = (int)target;
		float remainder = target - whole;
		
		double x = MC.player.getX();
		double y = MC.player.getY();
		double z = MC.player.getZ();
		
		for(int i = 0; i < whole; i++)
			MC.level.playLocalSound(x, y, z, soundEvent, SoundSource.PLAYERS,
				1F, 1F, false);
		
		if(remainder > 0f)
			MC.level.playLocalSound(x, y, z, soundEvent, SoundSource.PLAYERS,
				remainder, 1F, false);
	}
	
	private static ChatLine parseChatLine(String plain)
	{
		Matcher whisper = WHISPER_TO_YOU_CHAT.matcher(plain);
		if(whisper.matches())
			return new ChatLine(whisper.group(1), whisper.group(2), true, false,
				whisper.start(2));
		
		Matcher arrowWhisper = INCOMING_ARROW_WHISPER_CHAT.matcher(plain);
		if(arrowWhisper.matches())
			return new ChatLine(arrowWhisper.group(1), arrowWhisper.group(2),
				true, false, arrowWhisper.start(2));
		
		Matcher outgoingArrowWhisper =
			OUTGOING_ARROW_WHISPER_CHAT.matcher(plain);
		if(outgoingArrowWhisper.matches())
			return new ChatLine(outgoingArrowWhisper.group(1),
				outgoingArrowWhisper.group(2), false, true,
				outgoingArrowWhisper.start(2));
		
		Matcher fromWhisper = FROM_WHISPER_CHAT.matcher(plain);
		if(fromWhisper.matches())
			return new ChatLine(fromWhisper.group(1), fromWhisper.group(2),
				true, false, fromWhisper.start(2));
		
		Matcher angle = DECORATED_ANGLE_CHAT.matcher(plain);
		if(angle.matches())
			return new ChatLine(angle.group(1), angle.group(2), false, false,
				angle.start(2));
		
		Matcher colon = COLON_CHAT.matcher(plain);
		if(colon.matches())
			return new ChatLine(colon.group(1), colon.group(2), false, false,
				colon.start(2));
		
		Matcher arrow = ARROW_CHAT.matcher(plain);
		if(arrow.matches())
			return new ChatLine(arrow.group(1), arrow.group(2), false, false,
				arrow.start(2));
		
		return new ChatLine("System", plain, false, false, 0);
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
	
	private String normalizeOutgoingMessage(String message)
	{
		if(message == null)
			return "";
		
		return message.trim().replaceAll("\\s+", " ");
	}
	
	private boolean isLikelyOwnEcho(String text)
	{
		String normalizedText =
			normalizeOutgoingMessage(stripLegacyFormatting(text));
		if(normalizedText.isEmpty())
			return false;
		
		long now = System.currentTimeMillis();
		synchronized(recentSentMessages)
		{
			pruneRecentMessages(now);
			for(RecentSentMessage recent : recentSentMessages)
				if(normalizedText.endsWith(recent.message()))
					return true;
		}
		
		return false;
	}
	
	private void pruneRecentMessages(long now)
	{
		while(!recentSentMessages.isEmpty() && now - recentSentMessages
			.peekFirst().timestampMs() > SELF_ECHO_WINDOW_MS)
			recentSentMessages.removeFirst();
	}
	
	private enum NoteBlockSound
	{
		HARP("block.note_block.harp"),
		BASS("block.note_block.bass"),
		BASEDRUM("block.note_block.basedrum"),
		SNARE("block.note_block.snare"),
		HAT("block.note_block.hat"),
		GUITAR("block.note_block.guitar"),
		FLUTE("block.note_block.flute"),
		BELL("block.note_block.bell"),
		CHIME("block.note_block.chime"),
		XYLOPHONE("block.note_block.xylophone"),
		IRON_XYLOPHONE("block.note_block.iron_xylophone"),
		COW_BELL("block.note_block.cow_bell"),
		DIDGERIDOO("block.note_block.didgeridoo"),
		BIT("block.note_block.bit"),
		BANJO("block.note_block.banjo"),
		PLING("block.note_block.pling");
		
		private final String idPath;
		
		private NoteBlockSound(String idPath)
		{
			this.idPath = idPath;
		}
		
		private SoundEvent resolve()
		{
			try
			{
				Identifier id =
					Identifier.fromNamespaceAndPath("minecraft", idPath);
				return BuiltInRegistries.SOUND_EVENT.getValue(id);
				
			}catch(Exception e)
			{
				return null;
			}
		}
		
		@Override
		public String toString()
		{
			String pretty = idPath.substring("block.note_block.".length())
				.replace('_', ' ').toLowerCase(Locale.ROOT);
			String[] words = pretty.split(" ");
			StringBuilder sb = new StringBuilder("Note Block ");
			for(int i = 0; i < words.length; i++)
			{
				String w = words[i];
				if(w.isEmpty())
					continue;
				
				sb.append(Character.toUpperCase(w.charAt(0)))
					.append(w.substring(1));
				if(i < words.length - 1)
					sb.append(' ');
			}
			
			return sb.toString();
		}
	}
	
	private record ChatLine(String sender, String text, boolean whisperToYou,
		boolean outgoingWhisper, int textStart)
	{}
	
	private record RecentSentMessage(String message, long timestampMs)
	{}
}
