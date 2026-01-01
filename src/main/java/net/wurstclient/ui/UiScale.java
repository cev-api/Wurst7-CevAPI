/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.ui;

public final class UiScale
{
	/**
	 * When not 1.0 this overrides calculated UI scale. Kept public for
	 * compatibility with code that modifies this at runtime.
	 */
	public static double OVERRIDE_SCALE = 1.0;
	
	private UiScale()
	{}
	
	/**
	 * Return current UI scale. This minimal implementation returns 1.0.
	 * If you want the true Minecraft GUI scale, replace this with the
	 * appropriate query to the client options.
	 */
	public static double getScale()
	{
		return 1.0;
	}
}
