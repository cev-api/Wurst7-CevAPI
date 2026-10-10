/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.fabricmc.fabric.impl.networking.CommonRegisterPayload;
import net.fabricmc.fabric.impl.networking.CommonVersionPayload;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.resources.Identifier;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.text.WText;

/**
 * Per-connection filtering for client networking advertisements and payloads.
 * The local Fabric networking registries are left untouched.
 */
@DontBlock
@SearchTags({"vanilla spoof", "vanilla mode", "AntiFabric", "anti fabric",
	"LibHatesMods", "HackedServer", "network privacy", "mod channel privacy"})
public final class VanillaSpoofOtf extends OtherFeature
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final SystemToast.SystemToastId PROBE_TOAST_ID =
		new SystemToast.SystemToastId(7000L);
	private static final Pattern TRANSLATION_ARGUMENT =
		Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");
	private static final Identifier BRAND_ID =
		Identifier.withDefaultNamespace("brand");
	private static final Set<String> MOD_NAMESPACES = createModNamespaces();
	private static final Map<Connection, ConnectionState> CONNECTIONS =
		Collections.synchronizedMap(new WeakHashMap<>());
	
	private volatile CompiledRules compiledRules =
		new CompiledRules("", Map.of(), Map.of());
	
	private final CheckboxSetting spoof =
		new CheckboxSetting("Spoof Vanilla", false);
	private final CheckboxSetting hideModCapabilities = new CheckboxSetting(
		"Hide Mod Capabilities",
		WText.literal(
			"Filters client-advertised Fabric networking channels for installed mods."),
		true);
	private final CheckboxSetting blockModHandshakes = new CheckboxSetting(
		"Block Mod Handshakes",
		WText.literal(
			"Blocks outgoing optional mod payloads covered by the selected policy."),
		true);
	private final CheckboxSetting filterPluginRegistration =
		new CheckboxSetting("Filter Plugin Channel Registration", WText.literal(
			"Filters minecraft:register and minecraft:unregister channel lists."),
			true);
	private final CheckboxSetting spoofVanillaBrand =
		new CheckboxSetting("Spoof Vanilla Brand",
			WText.literal("Advertises vanilla as the client brand."), true);
	private final CheckboxSetting blockProbeResponses = new CheckboxSetting(
		"Block Mod Probe Responses",
		WText.literal(
			"Returns an empty login-query response for blocked mod probes and blocks "
				+ "outgoing payloads on disallowed channels, including asynchronous replies. "
				+ "Also protects anvil and sign text feedback from mod translation and keybind probes."),
		true);
	private final EnumSetting<Policy> policy = new EnumSetting<>(
		"Networking Privacy Policy",
		WText.literal(
			"Conservative blocks installed-mod channels and permits unknown optional channels. "
				+ "Balanced adds custom rules and permits unknown optional channels. Strict blocks "
				+ "optional custom channels unless explicitly allowed; brand and channel negotiation are preserved. Mod-dependent servers may fail."),
		Policy.values(), Policy.BALANCED);
	private final TextFieldSetting customRules = new TextFieldSetting(
		"Custom Channel Rules",
		WText.literal(
			"One rule per line (or separated by semicolons): ALLOW namespace:channel, "
				+ "BLOCK namespace:channel, ALLOW namespace:*, or BLOCK namespace:*. "
				+ "Balanced and Strict modes apply rules. Exact rules override namespace rules; "
				+ "the last rule at the same scope wins."),
		"", s -> s.length() <= 8192);
	private final CheckboxSetting probeChatAlerts = new CheckboxSetting(
		"Probe Chat Alerts",
		WText.literal(
			"Shows a local chat alert when protected anvil or sign text could reveal "
				+ "mod translations or keybind information. These are possible probes, "
				+ "not proof of malicious intent. Each mechanism is reported once per connection. "
				+ "Requires enabled VanillaSpoof and Block Mod Probe Responses."),
		false);
	private final CheckboxSetting probeToastAlerts = new CheckboxSetting(
		"Probe Toast Alerts",
		WText.literal(
			"Shows an optional toast for possible anvil/sign mod translation or keybind probes. "
				+ "Works independently of Probe Chat Alerts; each mechanism is reported once per connection. "
				+ "Requires enabled VanillaSpoof and Block Mod Probe Responses."),
		false);
	private final CheckboxSetting debugLogging = new CheckboxSetting(
		"Advertisement Debug Logging",
		WText.literal(
			"Logs channel allow, block, and rewrite decisions without payload contents."),
		false);
	
	public VanillaSpoofOtf()
	{
		super("VanillaSpoof",
			"Filters optional mod networking advertisements and payloads."
				+ "\n\nEnable before connecting. Reconnect after changing the policy; "
				+ "the server can retain information already transmitted."
				+ "\n\nStrict can prevent joining servers that require mod networking. "
				+ "Login authentication extensions are preserved unless blocked by a rule or identified as mod channels.");
		// Keep the legacy saved state while exposing only the feature action in
		// GUIs.
		spoof.setVisibleInGui(false);
		addSetting(spoof);
		addSetting(hideModCapabilities);
		addSetting(blockModHandshakes);
		addSetting(filterPluginRegistration);
		addSetting(spoofVanillaBrand);
		addSetting(blockProbeResponses);
		addSetting(policy);
		addSetting(customRules);
		addSetting(probeChatAlerts);
		addSetting(probeToastAlerts);
		addSetting(debugLogging);
	}
	
	/** Legacy persisted master state, hidden from the settings GUIs. */
	public CheckboxSetting getSpoofSetting()
	{
		return spoof;
	}
	
	@Override
	public boolean isEnabled()
	{
		return spoof.isChecked();
	}
	
	public void setEnabled(boolean enabled)
	{
		spoof.setChecked(enabled);
	}
	
	@Override
	public String getPrimaryAction()
	{
		return isEnabled() ? "Disable" : "Enable";
	}
	
	@Override
	public void doPrimaryAction()
	{
		setEnabled(!isEnabled());
	}
	
	/**
	 * Editors can echo server-provided components as locally resolved strings.
	 * Resolve mod translation/keybind nodes as vanilla would only when seeding
	 * those feedback paths. Local translations and manually typed text stay
	 * intact.
	 */
	public boolean isTextFeedbackProtectionEnabled()
	{
		return isEnabled() && blockProbeResponses.isChecked();
	}
	
	public boolean isAnvilFeedbackProtectionEnabled()
	{
		return isTextFeedbackProtectionEnabled();
	}
	
	public String getAnvilFeedbackName(Component name)
	{
		return getTextFeedback(name, "anvil item names", "ANVIL PROBE REWRITE",
			Identifier.withDefaultNamespace("rename_item"));
	}
	
	public String getSignFeedbackLine(Component line)
	{
		return getTextFeedback(line, "sign text", "SIGN PROBE REWRITE",
			Identifier.withDefaultNamespace("sign_update"));
	}
	
	private String getTextFeedback(Component source, String surface,
		String decision, Identifier replyChannel)
	{
		if(!isTextFeedbackProtectionEnabled())
			return source.getString();
		Set<ProbeInformation> information =
			probeChatAlerts.isChecked() || probeToastAlerts.isChecked()
				? EnumSet.noneOf(ProbeInformation.class) : null;
		String result =
			vanillaFeedbackComponent(source, information).getString();
		if(!result.equals(source.getString()))
		{
			log(decision, "PLAY", replyChannel, null, null);
			if(information != null)
				alertTextProbe(surface, information);
		}
		return result;
	}
	
	private static MutableComponent vanillaFeedbackComponent(Component source,
		Set<ProbeInformation> information)
	{
		MutableComponent result;
		if(source.getContents() instanceof TranslatableContents translation)
		{
			Object[] args = translation.getArgs().clone();
			for(int i = 0; i < args.length; i++)
				if(args[i] instanceof Component argument)
					args[i] = vanillaFeedbackComponent(argument, information);
			if(Language.DEFAULT_INSTANCE.has(translation.getKey()))
				result = MutableComponent.create(new TranslatableContents(
					translation.getKey(), translation.getFallback(), args));
			else
			{
				String template = translation.getFallback() == null
					? translation.getKey() : translation.getFallback();
				result =
					Component.literal(formatVanillaFallback(template, args));
				if(information != null && !result.getString().equals(
					MutableComponent.create(source.getContents()).getString()))
					information.add(ProbeInformation.TRANSLATIONS);
			}
		}else if(source.getContents() instanceof KeybindContents keybind
			&& !Language.DEFAULT_INSTANCE.has(keybind.getName()))
		{
			result = Component.literal(keybind.getName());
			if(information != null && !result.getString().equals(
				MutableComponent.create(source.getContents()).getString()))
				information.add(ProbeInformation.KEYBINDS);
		}else
			result = MutableComponent.create(source.getContents());
		result.setStyle(source.getStyle());
		for(Component sibling : source.getSiblings())
			result.append(vanillaFeedbackComponent(sibling, information));
		return result;
	}
	
	private void alertTextProbe(String surface,
		Set<ProbeInformation> information)
	{
		if(MC == null || MC.getConnection() == null || information.isEmpty())
			return;
		Connection connection = MC.getConnection().getConnection();
		ConnectionState state = stateFor(connection);
		for(ProbeInformation info : information)
		{
			String key = surface + ":" + info.name();
			boolean chat;
			boolean toast;
			synchronized(state)
			{
				chat = probeChatAlerts.isChecked()
					&& state.reportedTextProbes.add(key);
				toast = probeToastAlerts.isChecked()
					&& state.reportedToastProbes.add(key);
			}
			if(!chat && !toast)
				continue;
			MC.execute(() -> {
				// Do not deliver a queued alert to a subsequent server
				// connection.
				if(!isTextFeedbackProtectionEnabled()
					|| CONNECTIONS.get(connection) != state
					|| MC.getConnection() == null
					|| MC.getConnection().getConnection() != connection)
					return;
				if(chat && probeChatAlerts.isChecked())
					ChatUtils.warning("[VanillaSpoof] Possible probe via "
						+ surface + ": server-provided " + info.mechanism
						+ " could reveal " + info.information
						+ ". Feedback protected.");
				if(toast && probeToastAlerts.isChecked() && MC.gui != null)
				{
					SystemToast notification = new SystemToast(PROBE_TOAST_ID,
						Component.literal("VanillaSpoof: possible mod probe"),
						Component.literal(
							surface + ": " + info.mechanism + " could reveal "
								+ info.information + ". Feedback protected."));
					synchronized(state)
					{
						state.probeToasts.add(notification);
					}
					MC.gui.toastManager().addToast(notification);
				}
			});
		}
	}
	
	private static String formatVanillaFallback(String template, Object[] args)
	{
		var matcher = TRANSLATION_ARGUMENT.matcher(template);
		StringBuilder result = new StringBuilder();
		int end = 0;
		int nextArgument = 0;
		try
		{
			while(matcher.find())
			{
				result.append(template, end, matcher.start());
				String token = matcher.group();
				if("%%".equals(token))
					result.append('%');
				else
				{
					if(!"s".equals(matcher.group(2)))
						return template;
					int index = matcher.group(1) == null ? nextArgument++
						: Integer.parseInt(matcher.group(1)) - 1;
					if(index < 0 || index >= args.length)
						return template;
					Object argument = args[index];
					result.append(argument instanceof Component component
						? component.getString() : String.valueOf(argument));
				}
				end = matcher.end();
			}
		}catch(NumberFormatException e)
		{
			return template;
		}
		return result.append(template, end, template.length()).toString();
	}
	
	/**
	 * Records server login-query channels so asynchronous replies can be
	 * filtered.
	 */
	public static void onIncomingPacket(Connection connection, Packet<?> packet)
	{
		if(!(packet instanceof ClientboundCustomQueryPacket query))
			return;
		
		ConnectionState state = stateFor(connection);
		synchronized(state)
		{
			state.loginQueries.put(query.transactionId(), query.payload().id());
		}
	}
	
	/** Clears transient query state when a connection closes. */
	public static void onDisconnect(Connection connection)
	{
		ConnectionState state = CONNECTIONS.remove(connection);
		if(state != null && MC != null)
			MC.execute(() -> {
				synchronized(state)
				{
					state.probeToasts.forEach(SystemToast::forceHide);
					state.probeToasts.clear();
				}
			});
	}
	
	/**
	 * Applies the active policy at the common packet-object send boundary. A
	 * null result means the packet must not be sent.
	 */
	public Packet<?> filterOutgoing(Connection connection, Packet<?> packet)
	{
		if(!isEnabled())
			return packet;
		
		if(packet instanceof ServerboundCustomPayloadPacket customPacket)
		{
			String phase = phaseOf(connection);
			CustomPacketPayload payload = customPacket.payload();
			Identifier id = payload.type().id();
			
			if(payload instanceof BrandPayload && spoofVanillaBrand.isChecked())
			{
				String currentBrand = ((BrandPayload)payload).brand();
				if("vanilla".equals(currentBrand))
				{
					log("BRAND ALLOW", phase, id, null, null);
					return packet;
				}
				log("BRAND REWRITE", phase, id, currentBrand, "vanilla");
				return new ServerboundCustomPayloadPacket(
					new BrandPayload("vanilla"));
			}
			
			if(payload instanceof RegistrationPayload registration)
				return filterRegistration(customPacket, registration, phase);
			
			if(payload instanceof CommonRegisterPayload registration)
				return filterCommonRegistration(customPacket, registration,
					phase);
				
			// The negotiated version frame contains no mod channel list. Keep
			// the transport handshake intact while filtering its registrations.
			if(payload instanceof CommonVersionPayload)
				return packet;
			
			if(isOptionalChannel(id) && shouldBlockPayload(id))
			{
				log("PAYLOAD BLOCK", phase, id, null, null);
				return null;
			}
			
			log("PAYLOAD ALLOW", phase, id, null, null);
			return packet;
		}
		
		if(packet instanceof ServerboundCustomQueryAnswerPacket answer)
			return filterLoginAnswer(connection, answer, phaseOf(connection));
		
		return packet;
	}
	
	private Packet<?> filterRegistration(ServerboundCustomPayloadPacket packet,
		RegistrationPayload registration, String phase)
	{
		if(!hideModCapabilities.isChecked()
			&& !filterPluginRegistration.isChecked())
			return packet;
		
		List<Identifier> allowed =
			new ArrayList<>(registration.channels().size());
		boolean hideKnown = hideModCapabilities.isChecked()
			|| filterPluginRegistration.isChecked();
		for(Identifier channel : registration.channels())
		{
			if(!shouldBlockCapability(channel, hideKnown))
			{
				allowed.add(channel);
				log("CAPABILITY ALLOW", phase, channel, null, null);
			}else
			{
				log("CAPABILITY BLOCK", phase, channel, null, null);
			}
		}
		
		int removed = registration.channels().size() - allowed.size();
		if(removed == 0)
			return packet;
		
		log("REGISTER FILTER", phase, registration.type().id(),
			Integer.toString(removed), "channels removed");
		if(allowed.isEmpty())
			return null;
		
		return new ServerboundCustomPayloadPacket(
			new RegistrationPayload(registration.type(), List.copyOf(allowed)));
	}
	
	private Packet<?> filterCommonRegistration(
		ServerboundCustomPayloadPacket packet,
		CommonRegisterPayload registration, String phase)
	{
		if(!hideModCapabilities.isChecked())
			return packet;
		
		Set<Identifier> allowed = new HashSet<>();
		for(Identifier channel : registration.channels())
		{
			if(!shouldBlockCapability(channel))
			{
				allowed.add(channel);
				log("CAPABILITY ALLOW", phase, channel, null, null);
			}else
				log("CAPABILITY BLOCK", phase, channel, null, null);
		}
		
		int removed = registration.channels().size() - allowed.size();
		if(removed == 0)
			return packet;
		
		log("CAPABILITY FILTER", phase, registration.type().id(),
			Integer.toString(removed), "channels removed");
		// An empty registration still carries the negotiated version and phase.
		return new ServerboundCustomPayloadPacket(
			new CommonRegisterPayload(registration.version(),
				registration.protocol(), Set.copyOf(allowed)));
	}
	
	private Packet<?> filterLoginAnswer(Connection connection,
		ServerboundCustomQueryAnswerPacket answer, String phase)
	{
		ConnectionState state = stateFor(connection);
		Identifier channel;
		synchronized(state)
		{
			channel = state.loginQueries.remove(answer.transactionId());
		}
		
		if(channel == null || !isOptionalChannel(channel)
			|| !shouldBlockProbeResponse(channel))
		{
			if(channel != null)
				log("PROBE RESPONSE ALLOW", phase, channel, null, null);
			return answer;
		}
		
		log("PROBE RESPONSE BLOCK", phase, channel, null, null);
		// Login-query responses are correlated by transaction ID. An empty
		// reply
		// is the vanilla-compatible response for an unsupported query.
		return new ServerboundCustomQueryAnswerPacket(answer.transactionId(),
			null);
	}
	
	private boolean shouldBlockProbeResponse(Identifier channel)
	{
		Rule rule = findRule(channel);
		if(rule != null)
			return !rule.allow();
			
		// Login custom-query responses can be part of proxy or server
		// authentication. Keep unknown login channels intact; strict filtering
		// still suppresses queries owned by installed mod namespaces.
		return (isKnownModChannel(channel)
			|| ClientLoginNetworking.getGlobalReceivers().contains(channel))
			&& (policy.getSelected() == Policy.STRICT
				|| blockProbeResponses.isChecked()
				|| blockModHandshakes.isChecked());
	}
	
	private boolean shouldBlockCapability(Identifier channel)
	{
		return shouldBlockCapability(channel, hideModCapabilities.isChecked());
	}
	
	private boolean shouldBlockCapability(Identifier channel, boolean hideKnown)
	{
		if(!isOptionalChannel(channel))
			return false;
		
		Rule rule = findRule(channel);
		if(rule != null)
			return !rule.allow();
		
		return policy.getSelected() == Policy.STRICT
			|| hideKnown && isKnownModChannel(channel);
	}
	
	private boolean shouldBlockPayload(Identifier channel)
	{
		if(channel.equals(BRAND_ID))
			return false;
		
		Rule rule = findRule(channel);
		if(rule != null)
			return !rule.allow();
		
		if(policy.getSelected() == Policy.STRICT)
			return true;
		
		if(!isKnownModChannel(channel))
			return false;
			
		// Reply provenance cannot be inferred reliably across asynchronous mod
		// work. Suppress policy-disallowed payloads whenever either protection
		// is enabled rather than allowing later replies through a timeout.
		return blockModHandshakes.isChecked()
			|| blockProbeResponses.isChecked();
	}
	
	private boolean isOptionalChannel(Identifier channel)
	{
		// minecraft:brand is the standard client brand payload. Minecraft's
		// required login/configuration traffic uses dedicated protocol packets,
		// not optional custom-payload channels.
		return !BRAND_ID.equals(channel);
	}
	
	private static boolean isKnownModChannel(Identifier channel)
	{
		// Fabric payload registration identifies mod traffic even when its
		// namespace differs from the owning mod ID (e.g. worldinfo).
		return MOD_NAMESPACES.contains(channel.getNamespace())
			|| PayloadTypeRegistryImpl.CLIENTBOUND_PLAY.get(channel) != null
			|| PayloadTypeRegistryImpl.SERVERBOUND_PLAY.get(channel) != null
			|| PayloadTypeRegistryImpl.CLIENTBOUND_CONFIGURATION
				.get(channel) != null
			|| PayloadTypeRegistryImpl.SERVERBOUND_CONFIGURATION
				.get(channel) != null;
	}
	
	private Rule findRule(Identifier channel)
	{
		if(policy.getSelected() == Policy.CONSERVATIVE)
			return null;
		
		String source = customRules.getValue();
		CompiledRules rules = compiledRules;
		if(!source.equals(rules.source()))
		{
			rules = compileRules(source);
			compiledRules = rules;
		}
		Rule exact = rules.exact().get(channel);
		return exact != null ? exact
			: rules.namespaces().get(channel.getNamespace());
	}
	
	private static CompiledRules compileRules(String source)
	{
		Map<Identifier, Rule> exact = new HashMap<>();
		Map<String, Rule> namespaces = new HashMap<>();
		for(String raw : source.split("[;\\r\\n]+"))
		{
			String[] parts = raw.trim().split("\\s+", 2);
			if(parts.length != 2)
				continue;
			boolean allow;
			if("ALLOW".equalsIgnoreCase(parts[0]))
				allow = true;
			else if("BLOCK".equalsIgnoreCase(parts[0]))
				allow = false;
			else
				continue;
			String pattern = parts[1].trim();
			if(pattern.endsWith(":*"))
			{
				String namespace = pattern.substring(0, pattern.length() - 2);
				if(Identifier.tryParse(namespace + ":rule") != null)
					namespaces.put(namespace, new Rule(allow));
			}else
			{
				Identifier id = Identifier.tryParse(pattern);
				if(id != null)
					exact.put(id, new Rule(allow));
			}
		}
		return new CompiledRules(source, Map.copyOf(exact),
			Map.copyOf(namespaces));
	}
	
	private void log(String decision, String phase, Identifier channel,
		String before, String after)
	{
		if(!debugLogging.isChecked())
			return;
		
		if(before != null)
			LOGGER.info("[VanillaSpoof] {} {} {} {} -> {}", decision, phase,
				channel, before, after);
		else
			LOGGER.info("[VanillaSpoof] {} {} {}", decision, phase, channel);
	}
	
	private static ConnectionState stateFor(Connection connection)
	{
		synchronized(CONNECTIONS)
		{
			return CONNECTIONS.computeIfAbsent(connection,
				ignored -> new ConnectionState());
		}
	}
	
	private static String phaseOf(Connection connection)
	{
		return connection.getPacketListener() == null ? "unknown"
			: connection.getPacketListener().protocol().toString();
	}
	
	private static Set<String> createModNamespaces()
	{
		Set<String> namespaces = new HashSet<>();
		FabricLoader.getInstance().getAllMods().forEach(mod -> {
			namespaces.add(mod.getMetadata().getId());
			namespaces.addAll(mod.getMetadata().getProvides());
		});
		// Fabric API payloads use the "fabric" namespace rather than the
		// aggregate fabric-api mod ID.
		namespaces.add("fabric");
		return Set.copyOf(namespaces);
	}
	
	private enum Policy
	{
		CONSERVATIVE,
		BALANCED,
		STRICT;
		
		@Override
		public String toString()
		{
			String lower = name().toLowerCase(java.util.Locale.ROOT);
			return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
		}
	}
	
	private record CompiledRules(String source, Map<Identifier, Rule> exact,
		Map<String, Rule> namespaces)
	{}
	
	private record Rule(boolean allow)
	{}
	
	private enum ProbeInformation
	{
		TRANSLATIONS("translation keys", "installed mod translations"),
		KEYBINDS("keybind names", "mod keybind information");
		
		private final String mechanism;
		private final String information;
		
		ProbeInformation(String mechanism, String information)
		{
			this.mechanism = mechanism;
			this.information = information;
		}
	}
	
	private static final class ConnectionState
	{
		private final Map<Integer, Identifier> loginQueries = new HashMap<>();
		private final Set<String> reportedTextProbes = new HashSet<>();
		private final Set<String> reportedToastProbes = new HashSet<>();
		private final List<SystemToast> probeToasts = new ArrayList<>();
	}
}
