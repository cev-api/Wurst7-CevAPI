/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import org.lwjgl.glfw.GLFW;

public final class TitleBackgroundControls
{
	private boolean xray;
	private boolean cameraMoving = true;
	
	public Action handleKey(int key, int action)
	{
		if(action != GLFW.GLFW_PRESS)
			return Action.NONE;
		
		switch(key)
		{
			case GLFW.GLFW_KEY_X:
			xray = !xray;
			return Action.XRAY;
			case GLFW.GLFW_KEY_ESCAPE:
			return Action.REALM;
			case GLFW.GLFW_KEY_LEFT_CONTROL:
			case GLFW.GLFW_KEY_RIGHT_CONTROL:
			cameraMoving = !cameraMoving;
			return Action.FLIGHT;
			default:
			return Action.NONE;
		}
	}
	
	public boolean isXray()
	{
		return xray;
	}
	
	public boolean isCameraMoving()
	{
		return cameraMoving;
	}
	
	public enum Action
	{
		NONE,
		XRAY,
		REALM,
		FLIGHT
	}
}
