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
	
	private PathFlightRuntime()
	{}
	
	public static void initialize(PathFlightConfig newConfig)
	{
		if(controller != null)
			controller.stop();
		config = newConfig;
		controller = new FlightController(config);
	}
	
	public static PathFlightConfig config()
	{
		return config;
	}
	
	public static FlightController controller()
	{
		return controller;
	}
}
