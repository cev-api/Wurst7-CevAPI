/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ServerData;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;

@DontBlock
@SearchTags({"discord", "rich presence", "rpc", "status"})
public final class DiscordRpcOtf extends OtherFeature implements UpdateListener
{
	private static final String APPLICATION_ID_WURST = "1495043910651740202";
	private static final String APPLICATION_ID_NICEWURST =
		"1495070274465300541";
	
	// Discord IPC opcodes.
	private static final int OP_HANDSHAKE = 0;
	private static final int OP_FRAME = 1;
	
	private final CheckboxSetting enabled =
		new CheckboxSetting("Discord Rich Presence", false);
	
	private final CheckboxSetting showServerIp = new CheckboxSetting(
		"Discord show server IP",
		"Shows your current server address in Discord Rich Presence.", false);
	
	private final CheckboxSetting showUsername =
		new CheckboxSetting("Discord show username",
			"Shows your Minecraft username in Discord Rich Presence.", false);
	
	private final EnumSetting<PresenceMessage> statusMessage =
		new EnumSetting<>("Discord status message", PresenceMessage.values(),
			PresenceMessage.DEFAULT);
	
	private RandomAccessFile pipe;
	private boolean connected;
	private long startedAtEpochSeconds;
	private long nextPresenceRefreshMs;
	private long nextConnectAttemptMs;
	private String lastLoggedDetails = "";
	private String lastLoggedState = "";
	private boolean unsupportedPlatformLogged;
	
	public DiscordRpcOtf()
	{
		super("DiscordRPC",
			"Shows your game status in Discord (optional server IP and username).");
		
		addSetting(enabled);
		addSetting(statusMessage);
		addSetting(showServerIp);
		addSetting(showUsername);
		
		// Ensure these are available in Navigator/GUI setting lists.
		enabled.setVisibleInGui(true);
		statusMessage.setVisibleInGui(true);
		showServerIp.setVisibleInGui(true);
		showUsername.setVisibleInGui(true);
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(!enabled.isChecked() || !WURST.isEnabled())
		{
			shutdownPresence();
			return;
		}
		
		if(!isSupportedPlatform())
		{
			if(!unsupportedPlatformLogged)
			{
				unsupportedPlatformLogged = true;
				System.err.println(
					"[Wurst] Discord Rich Presence currently supports Windows only.");
			}
			return;
		}
		
		long now = System.currentTimeMillis();
		if(!connected)
		{
			if(now < nextConnectAttemptMs)
				return;
			
			nextConnectAttemptMs = now + 5000L;
			tryConnect();
			return;
		}
		
		if(now < nextPresenceRefreshMs)
			return;
		
		nextPresenceRefreshMs = now + 3000L;
		updatePresence();
	}
	
	public CheckboxSetting getEnabledSetting()
	{
		return enabled;
	}
	
	public CheckboxSetting getShowServerIpSetting()
	{
		return showServerIp;
	}
	
	public EnumSetting<PresenceMessage> getStatusMessageSetting()
	{
		return statusMessage;
	}
	
	public CheckboxSetting getShowUsernameSetting()
	{
		return showUsername;
	}
	
	private boolean isSupportedPlatform()
	{
		String os = System.getProperty("os.name", "").toLowerCase();
		return os.contains("win");
	}
	
	private void tryConnect()
	{
		try
		{
			pipe = openDiscordPipe();
			if(pipe == null)
				return;
			
			sendHandshake();
			connected = true;
			startedAtEpochSeconds = System.currentTimeMillis() / 1000L;
			lastLoggedDetails = "";
			lastLoggedState = "";
			System.out.println("[Wurst] Discord RPC connected.");
			
		}catch(Exception e)
		{
			// Discord likely not running; quietly retry later.
			shutdownPresence();
		}
	}
	
	private RandomAccessFile openDiscordPipe() throws IOException
	{
		for(int i = 0; i < 10; i++)
		{
			String path = "\\\\.\\pipe\\discord-ipc-" + i;
			try
			{
				return new RandomAccessFile(path, "rw");
				
			}catch(IOException ignored)
			{}
		}
		
		return null;
	}
	
	private void sendHandshake() throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("v", 1);
		payload.addProperty("client_id", getApplicationId());
		sendFrame(OP_HANDSHAKE, payload.toString());
	}
	
	private String getApplicationId()
	{
		return NiceWurstModule.isActive() ? APPLICATION_ID_NICEWURST
			: APPLICATION_ID_WURST;
	}
	
	private void updatePresence()
	{
		try
		{
			String details = statusMessage.getSelected().getMessage();
			String state = buildStateText();
			
			JsonObject activity = new JsonObject();
			activity.addProperty("details", details);
			activity.addProperty("state", state);
			JsonObject timestamps = new JsonObject();
			timestamps.addProperty("start", startedAtEpochSeconds);
			activity.add("timestamps", timestamps);
			
			JsonObject args = new JsonObject();
			args.addProperty("pid", ProcessHandle.current().pid());
			args.add("activity", activity);
			
			JsonObject payload = new JsonObject();
			payload.addProperty("cmd", "SET_ACTIVITY");
			payload.add("args", args);
			payload.addProperty("nonce", UUID.randomUUID().toString());
			
			sendFrame(OP_FRAME, payload.toString());
			
			if(!details.equals(lastLoggedDetails)
				|| !state.equals(lastLoggedState))
			{
				System.out
					.println("[Wurst] Discord presence updated: details=\""
						+ details + "\", state=\"" + state + "\"");
				lastLoggedDetails = details;
				lastLoggedState = state;
			}
			
		}catch(IOException e)
		{
			shutdownPresence();
		}
	}
	
	private String buildStateText()
	{
		if(MC == null || MC.level == null)
			return "In menus";
		
		ArrayList<String> bits = new ArrayList<>();
		
		if(showServerIp.isChecked())
		{
			ServerData server = MC.getCurrentServer();
			String ip = server == null ? "" : server.ip;
			if(ip != null && !ip.isBlank())
				bits.add(ip);
			else if(MC.hasSingleplayerServer())
				bits.add("Singleplayer");
		}
		
		if(showUsername.isChecked() && MC.getUser() != null
			&& MC.getUser().getName() != null
			&& !MC.getUser().getName().isBlank())
			bits.add(MC.getUser().getName());
		
		if(bits.isEmpty())
			return "In game";
		
		return String.join(" | ", bits);
	}
	
	private void sendFrame(int opCode, String jsonPayload) throws IOException
	{
		if(pipe == null)
			throw new IOException("Discord pipe is not connected.");
		
		byte[] payload = jsonPayload.getBytes(StandardCharsets.UTF_8);
		ByteBuffer header =
			ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		header.putInt(opCode);
		header.putInt(payload.length);
		pipe.write(header.array());
		pipe.write(payload);
	}
	
	private void shutdownPresence()
	{
		connected = false;
		startedAtEpochSeconds = 0;
		nextPresenceRefreshMs = 0;
		lastLoggedDetails = "";
		lastLoggedState = "";
		
		if(pipe != null)
		{
			try
			{
				pipe.close();
				
			}catch(IOException ignored)
			{}
		}
		
		pipe = null;
	}
	
	public static enum PresenceMessage
	{
		DEFAULT("0. Playing Wurst7-CevAPI", "Playing Wurst7-CevAPI")
		{
			@Override
			public String getMessage()
			{
				return NiceWurstModule.isActive() ? "Playing NiceWurst"
					: "Playing Wurst7-CevAPI";
			}
			
			@Override
			public String toString()
			{
				return NiceWurstModule.isActive() ? "0. Playing NiceWurst"
					: "0. Playing Wurst7-CevAPI";
			}
		},
		CHEATING("1. Cheating in Minecraft", "Cheating in Minecraft"),
		GRIEFING("2. Griefing in Minecraft", "Griefing in Minecraft"),
		BASEHUNTING("3. Basehunting in Minecraft", "Basehunting in Minecraft"),
		DUPING("4. Duping in Minecraft", "Duping in Minecraft"),
		PLAYING_MINECRAFT("5. Playing Minecraft", "Playing Minecraft"),
		ENJOYING_MINECRAFT("6. Enjoying Minecraft", "Enjoying Minecraft"),
		PLAYING_BLOCKGAME("7. Playing BlockGame", "Playing BlockGame"),
		BESTIES("8. Playing MC With My Besties!",
			"Playing MC With My Besties!"),
		EATING_CEVAPCICI("9. Eating Cevapcici", "Eating Cevapcici"),
		COOKING_CEVAPCICI("10. Cooking Cevapcici", "Cooking Cevapcici");
		
		private final String name;
		private final String message;
		
		private PresenceMessage(String name, String message)
		{
			this.name = name;
			this.message = message;
		}
		
		public String getMessage()
		{
			return message;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
