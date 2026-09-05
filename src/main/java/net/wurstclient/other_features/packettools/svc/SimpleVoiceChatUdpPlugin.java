/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools.svc;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * Optional Simple Voice Chat bridge. Fabric only instantiates this entrypoint
 * when SVC is installed; the rest of PacketTools has no SVC runtime dependency.
 */
public final class SimpleVoiceChatUdpPlugin implements VoicechatPlugin
{
	@Override
	public String getPluginId()
	{
		return "wurst_packettools_udp";
	}
	
	@Override
	public void registerEvents(EventRegistration registration)
	{
		registration.registerEvent(ClientVoicechatInitializationEvent.class,
			event -> event
				.setSocketImplementation(new LoggingVoicechatSocket()));
	}
}
