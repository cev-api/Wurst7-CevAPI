/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Tracks whether the current server actually answers
 * {@code ServerboundCommandSuggestionPacket} probes.
 *
 * The state is shared by the command, plugin and legacy scanners so that a
 * server which drops suggestion responses is only probed once instead of being
 * re-interrogated by every scanner. Anti-enumeration setups (and some packet
 * limiters) silently swallow these requests, which previously made each
 * scanner burn through its full probe list before giving up.
 */
public final class UiUtilsSuggestionCapability
{
	public enum State
	{
		UNKNOWN,
		SUPPORTED,
		SUPPRESSED
	}
	
	/**
	 * Consecutive unanswered probes tolerated before the server is considered
	 * to be suppressing suggestion responses.
	 */
	private static final int SUPPRESSION_THRESHOLD = 8;
	/**
	 * Minimum number of probes that must have been observed before any verdict
	 * is allowed, so a single dropped packet cannot brand a server as
	 * suppressing.
	 */
	private static final int MIN_PROBES_FOR_VERDICT = 3;
	
	private static State state = State.UNKNOWN;
	private static int consecutiveUnanswered;
	private static int probesObserved;
	private static String boundServerKey = "";
	
	private UiUtilsSuggestionCapability()
	{}
	
	public static State getState()
	{
		return state;
	}
	
	public static boolean isSupported()
	{
		return state == State.SUPPORTED;
	}
	
	public static boolean isSuppressed()
	{
		return state == State.SUPPRESSED;
	}
	
	public static int getConsecutiveUnanswered()
	{
		return consecutiveUnanswered;
	}
	
	public static int getProbesObserved()
	{
		return probesObserved;
	}
	
	public static int getThreshold()
	{
		return SUPPRESSION_THRESHOLD;
	}
	
	/**
	 * Binds the capability to the server currently being scanned, resetting it
	 * when the server changes. Blank keys (unknown server) leave the state as
	 * it is.
	 */
	public static void bindTo(String serverKey)
	{
		if(serverKey == null || serverKey.isBlank())
			return;
		if(serverKey.equals(boundServerKey))
			return;
		boundServerKey = serverKey;
		clear();
	}
	
	/**
	 * Records a valid suggestion response. Any response proves the pipeline
	 * works, so this outranks earlier unanswered probes.
	 */
	public static void recordResponse()
	{
		probesObserved++;
		consecutiveUnanswered = 0;
		if(state != State.SUPPORTED)
		{
			boolean recovered = state == State.SUPPRESSED;
			state = State.SUPPORTED;
			if(recovered)
				announce(
					"Server answered a command-suggestion probe again; active probing re-enabled.");
		}
	}
	
	/**
	 * Records a probe that was never answered.
	 *
	 * @return true if this probe pushed the server into the SUPPRESSED state
	 */
	public static boolean recordUnansweredProbe()
	{
		return recordUnansweredProbes(1);
	}
	
	/**
	 * Records several probes that were never answered at once, for scanners
	 * that do not wait for each individual response.
	 *
	 * @return true if this batch pushed the server into the SUPPRESSED state
	 */
	public static boolean recordUnansweredProbes(int count)
	{
		if(count <= 0)
			return false;
		probesObserved += count;
		if(state != State.UNKNOWN)
			return false;
		consecutiveUnanswered += count;
		// Never judge a server before a few probes have been seen, so a single
		// dropped packet cannot brand it as suppressing.
		if(probesObserved < MIN_PROBES_FOR_VERDICT)
			return false;
		if(consecutiveUnanswered < SUPPRESSION_THRESHOLD)
			return false;
		state = State.SUPPRESSED;
		announce(
			"Server appears to suppress command-suggestion responses. Active probing is disabled for this server.");
		return true;
	}
	
	public static String getStatusLabel()
	{
		return switch(state)
		{
			case SUPPORTED -> "suggestion probes answered";
			case SUPPRESSED -> "suggestion responses suppressed (passive evidence only)";
			case UNKNOWN -> "suggestion support unknown ("
				+ consecutiveUnanswered + "/" + SUPPRESSION_THRESHOLD
				+ " unanswered)";
		};
	}
	
	/**
	 * Forgets everything learned about the current server.
	 */
	public static void clear()
	{
		state = State.UNKNOWN;
		consecutiveUnanswered = 0;
		probesObserved = 0;
	}
	
	private static void announce(String message)
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null || mc.player == null)
			return;
		mc.player.sendSystemMessage(
			Component.literal("[UI-Utils] " + message).withColor(0xFFCC66));
	}
}
