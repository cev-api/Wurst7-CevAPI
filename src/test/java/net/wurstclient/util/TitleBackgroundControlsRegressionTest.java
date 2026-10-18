/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import org.lwjgl.glfw.GLFW;

import net.wurstclient.util.TitleBackgroundControls.Action;

public final class TitleBackgroundControlsRegressionTest
{
	public static void main(String[] args)
	{
		TitleBackgroundControls controls = new TitleBackgroundControls();
		check(controls.isCameraMoving(), "background must animate immediately");
		check(!controls.isXray(), "X-Ray must start disabled");
		check(
			controls.handleKey(GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS) == Action.XRAY
				&& controls.isXray(),
			"X must enable background X-Ray");
		controls.handleKey(GLFW.GLFW_KEY_X, GLFW.GLFW_REPEAT);
		controls.handleKey(GLFW.GLFW_KEY_X, GLFW.GLFW_RELEASE);
		check(controls.isXray(),
			"held keys and releases must not toggle again");
		controls.handleKey(GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS);
		check(!controls.isXray(), "a second X press must disable X-Ray");
		check(controls.handleKey(GLFW.GLFW_KEY_ESCAPE,
			GLFW.GLFW_PRESS) == Action.REALM, "Escape must change realm");
		check(
			controls.handleKey(GLFW.GLFW_KEY_ESCAPE,
				GLFW.GLFW_REPEAT) == Action.NONE,
			"holding Escape must not cycle realms repeatedly");
		controls.handleKey(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_PRESS);
		check(!controls.isCameraMoving(), "Ctrl must pause flight immediately");
		controls.handleKey(GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_PRESS);
		check(controls.isCameraMoving(), "either Ctrl key must resume flight");
		check(
			controls.handleKey(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS) == Action.NONE,
			"unrelated keys must pass through to the menu");
		System.out.println("Title background controls checks passed.");
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
