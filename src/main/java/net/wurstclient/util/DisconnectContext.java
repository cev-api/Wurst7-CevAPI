/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public final class DisconnectContext
{
	private static final DateTimeFormatter TIME_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final long LOCAL_QUIT_WINDOW_MS = 15000L;
	private static volatile long lastExpectedDisconnectMs;
	private static volatile String pendingDisconnectDetails;
	
	private DisconnectContext()
	{}
	
	public static Component createAutoQuitReason(String source,
		BlockPos selfPos, String details)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(source).append(" triggered auto-quit.");
		if(selfPos != null)
			sb.append("\nYour position: ").append(formatBlockPos(selfPos));
		if(details != null && !details.isBlank())
			sb.append("\n").append(details.trim());
		sb.append("\nTime: ").append(LocalDateTime.now().format(TIME_FORMAT));
		return Component.literal(sb.toString());
	}
	
	public static String buildAutoDisconnectDetails(String source, String mode,
		BlockPos selfPos, String details)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(source).append(" triggered auto-leave.");
		if(mode != null && !mode.isBlank())
			sb.append("\nMode: ").append(mode);
		if(selfPos != null)
			sb.append("\nYour position: ").append(formatBlockPos(selfPos));
		if(details != null && !details.isBlank())
			sb.append("\n").append(details.trim());
		sb.append("\nTime: ").append(LocalDateTime.now().format(TIME_FORMAT));
		return sb.toString();
	}
	
	public static String formatPlayerDetectionDetails(Vec3 selfPos,
		Vec3 otherPos)
	{
		if(otherPos == null)
			return "unknown";
		
		StringBuilder sb = new StringBuilder();
		sb.append(describePlayerDistance(selfPos, otherPos));
		if(selfPos != null)
		{
			sb.append("\nDistance: ")
				.append(formatBlockDistance(selfPos.distanceTo(otherPos)));
		}
		sb.append("\nPosition: ").append(formatVec(otherPos));
		return sb.toString();
	}
	
	public static void markExpectedDisconnect(String details)
	{
		lastExpectedDisconnectMs = System.currentTimeMillis();
		pendingDisconnectDetails = details;
	}
	
	public static boolean consumeRecentExpectedDisconnect()
	{
		long now = System.currentTimeMillis();
		if(now - lastExpectedDisconnectMs > LOCAL_QUIT_WINDOW_MS)
			return false;
		
		lastExpectedDisconnectMs = 0L;
		return true;
	}
	
	public static String consumePendingDisconnectDetails()
	{
		long now = System.currentTimeMillis();
		if(now - lastExpectedDisconnectMs > LOCAL_QUIT_WINDOW_MS)
		{
			pendingDisconnectDetails = null;
			return null;
		}
		
		String details = pendingDisconnectDetails;
		pendingDisconnectDetails = null;
		return details;
	}
	
	private static String formatBlockPos(BlockPos pos)
	{
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
	
	private static String formatVec(Vec3 vec)
	{
		return String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", vec.x,
			vec.y, vec.z);
	}
	
	private static String describePlayerDistance(Vec3 selfPos, Vec3 otherPos)
	{
		if(selfPos == null || otherPos == null)
			return "unknown";
		
		double dx = otherPos.x - selfPos.x;
		double dy = otherPos.y - selfPos.y;
		double dz = otherPos.z - selfPos.z;
		double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
		if(Math.abs(dy) >= 0.5D
			&& Math.abs(dy) >= Math.max(Math.abs(dx), Math.abs(dz)))
		{
			long blocks = Math.max(1L, Math.round(Math.abs(dy)));
			return blocks + " Blocks " + (dy > 0 ? "Above" : "Below");
		}
		
		long blocks = Math.max(1L, Math.round(horizontalDistance));
		return blocks + " Blocks Horizontally";
	}
	
	private static String formatBlockDistance(double distance)
	{
		return String.format(java.util.Locale.ROOT, "%.1f blocks", distance);
	}
}
