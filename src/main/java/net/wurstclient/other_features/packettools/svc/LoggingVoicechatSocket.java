/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools.svc;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import net.wurstclient.WurstClient;

/** Passive replacement for SVC's default client DatagramSocket. */
public final class LoggingVoicechatSocket implements ClientVoicechatSocket
{
	private DatagramSocket socket;
	
	@Override
	public void open() throws Exception
	{
		socket = new DatagramSocket();
	}
	
	@Override
	public RawUdpPacket read() throws Exception
	{
		DatagramSocket current = requireSocket();
		byte[] data = new byte[65535];
		DatagramPacket packet = new DatagramPacket(data, data.length);
		current.receive(packet);
		byte[] received = new byte[packet.getLength()];
		System.arraycopy(packet.getData(), packet.getOffset(), received, 0,
			packet.getLength());
		log("S2C", current.getLocalSocketAddress(), packet.getSocketAddress(),
			received);
		return new ObservedRawUdpPacket(received, packet.getSocketAddress(),
			System.currentTimeMillis());
	}
	
	@Override
	public void send(byte[] data, SocketAddress address) throws Exception
	{
		DatagramSocket current = requireSocket();
		current.send(new DatagramPacket(data, data.length, address));
		log("C2S", current.getLocalSocketAddress(), address, data);
	}
	
	@Override
	public void close()
	{
		if(socket != null)
			socket.close();
	}
	
	@Override
	public boolean isClosed()
	{
		return socket == null || socket.isClosed();
	}
	
	public SocketAddress getLocalSocketAddress()
	{
		return socket != null ? socket.getLocalSocketAddress() : null;
	}
	
	private DatagramSocket requireSocket()
	{
		if(socket == null)
			throw new IllegalStateException("Voice chat socket is not open");
		return socket;
	}
	
	private void log(String direction, SocketAddress local,
		SocketAddress remote, byte[] data)
	{
		try
		{
			WurstClient.INSTANCE.getOtfs().packetToolsOtf.logUdpDatagram(
				direction, local, remote, data.length, getClass().getName(),
				"simple_voice_chat", null, sha256(data));
		}catch(Throwable ignored)
		{
			// Monitoring must never affect the voice-chat socket.
		}
	}
	
	private static String sha256(byte[] data)
	{
		try
		{
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for(byte b : hash)
				hex.append(String.format("%02x", b));
			return hex.toString();
		}catch(NoSuchAlgorithmException e)
		{
			return null;
		}
	}
	
	private record ObservedRawUdpPacket(byte[] data,
		SocketAddress socketAddress, long timestamp) implements RawUdpPacket
	{
		@Override
		public byte[] getData()
		{
			return data;
		}
		
		@Override
		public SocketAddress getSocketAddress()
		{
			return socketAddress;
		}
		
		@Override
		public long getTimestamp()
		{
			return timestamp;
		}
	}
}
