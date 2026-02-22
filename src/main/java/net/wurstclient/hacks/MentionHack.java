/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatOutputListener;
import net.wurstclient.hack.Hack;
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
		"^([A-Za-z0-9_\\-*.]{1,24})\\s+whispers\\s+to\\s+you:\\s+(.+)$",
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
	
	private final ArrayDeque<RecentSentMessage> recentSentMessages =
		new ArrayDeque<>();
	
	public MentionHack()
	{
		super("Mention");
		setCategory(Category.CHAT);
		addSetting(nicknameAliases);
		addSetting(sound);
		addSetting(volume);
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
		if(line.sender().equalsIgnoreCase(ownName))
			return;
		if(isLikelyJoinLeaveAnnouncement(line, ownName))
			return;
		if(line.sender().equals("System") && isLikelyOwnEcho(line.text()))
			return;
		
		if(!containsMention(line.text(), ownName))
			return;
		
		playMentionSound();
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
		Matcher angle = DECORATED_ANGLE_CHAT.matcher(plain);
		if(angle.matches())
			return new ChatLine(angle.group(1), angle.group(2));
		
		Matcher colon = COLON_CHAT.matcher(plain);
		if(colon.matches())
			return new ChatLine(colon.group(1), colon.group(2));
		
		Matcher arrow = ARROW_CHAT.matcher(plain);
		if(arrow.matches())
			return new ChatLine(arrow.group(1), arrow.group(2));
		
		Matcher whisper = WHISPER_TO_YOU_CHAT.matcher(plain);
		if(whisper.matches())
			return new ChatLine(whisper.group(1), whisper.group(2));
		
		return new ChatLine("System", plain);
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
	
	private record ChatLine(String sender, String text)
	{}
	
	private record RecentSentMessage(String message, long timestampMs)
	{}
}
