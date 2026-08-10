/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.wurstclient.autoflypath.flight.FlightController;

/** Shared runtime used by the imported FlyTo planner. */
public final class PathFlightRuntime
{
	public static final ExecutorService EXECUTOR =
		Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "AutoFly-Path-Worker");
			thread.setDaemon(true);
			return thread;
		});
	
	private static PathFlightConfig config;
	private static FlightController controller;
	private static boolean clientTickRegistered;
	private static volatile long landingProtectionUntilMs;
	
	private PathFlightRuntime()
	{}
	
	public static void initialize(PathFlightConfig newConfig)
	{
		if(controller != null)
			controller.stop();
		config = newConfig;
		controller = new FlightController(config);
		
		if(!clientTickRegistered)
		{
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				FlightController activeController = controller;
				if(activeController != null && client.player != null
					&& client.level != null)
					activeController.clientTick();
			});
			clientTickRegistered = true;
		}
	}
	
	public static PathFlightConfig config()
	{
		return config;
	}
	
	public static FlightController controller()
	{
		return controller;
	}
	
	public static boolean isPathFlightActive()
	{
		return controller != null && controller.isActive();
	}
	
	public static boolean isPathFlightDescending()
	{
		return isPathFlightActive() && controller.isDescending();
	}
	
	/**
	 * Returns true briefly after path flight stops so NoFall can protect the
	 * landing before the regular Flight hack is restored.
	 */
	public static boolean isLandingProtectionActive()
	{
		return System.currentTimeMillis() < landingProtectionUntilMs;
	}
	
	public static void protectLanding()
	{
		landingProtectionUntilMs = System.currentTimeMillis() + 500L;
	}
}
