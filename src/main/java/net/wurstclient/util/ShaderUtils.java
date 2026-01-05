/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

public enum ShaderUtils
{
	;
	
	private static final String IRIS_API_CLASS =
		"net.irisshaders.iris.api.v0.IrisApi";
	private static final String OPTIFINE_SHADERS_CLASS =
		"net.optifine.shaders.Shaders";
	
	private static volatile boolean lastShadersActive;
	private static volatile boolean lastShadersActiveKnown;
	private static volatile boolean shadersJustToggledOn;
	private static volatile boolean shadersJustToggledOff;
	
	public static boolean isIrisLoaded()
	{
		try
		{
			Class.forName(IRIS_API_CLASS);
			return true;
			
		}catch(Throwable t)
		{
			return false;
		}
	}
	
	public static boolean areIrisShadersActive()
	{
		try
		{
			Class<?> irisApiClass = Class.forName(IRIS_API_CLASS);
			Object irisApi = irisApiClass.getMethod("getInstance").invoke(null);
			Object inUse = irisApi.getClass().getMethod("isShaderPackInUse")
				.invoke(irisApi);
			return inUse instanceof Boolean ? (Boolean)inUse : false;
			
		}catch(Throwable t)
		{
			return false;
		}
	}
	
	public static boolean isOptiFineLoaded()
	{
		try
		{
			Class.forName(OPTIFINE_SHADERS_CLASS);
			return true;
			
		}catch(Throwable t)
		{
			return false;
		}
	}
	
	public static boolean areOptiFineShadersActive()
	{
		try
		{
			Class<?> shadersClass = Class.forName(OPTIFINE_SHADERS_CLASS);
			Object result =
				shadersClass.getMethod("isShaderPackLoaded").invoke(null);
			return result instanceof Boolean ? (Boolean)result : false;
			
		}catch(Throwable t)
		{
			return false;
		}
	}
	
	public static boolean areShadersActive()
	{
		return areIrisShadersActive() || areOptiFineShadersActive();
	}
	
	public static boolean refreshShadersActive()
	{
		boolean current = areShadersActive();
		
		if(!lastShadersActiveKnown)
		{
			lastShadersActive = current;
			lastShadersActiveKnown = true;
			shadersJustToggledOn = false;
			shadersJustToggledOff = false;
			return current;
		}
		
		if(current != lastShadersActive)
		{
			shadersJustToggledOn = current;
			shadersJustToggledOff = !current;
			lastShadersActive = current;
		}else
		{
			shadersJustToggledOn = false;
			shadersJustToggledOff = false;
		}
		
		return current;
	}
	
	public static boolean shadersJustToggledOn()
	{
		boolean value = shadersJustToggledOn;
		shadersJustToggledOn = false;
		return value;
	}
	
	public static boolean shadersJustToggledOff()
	{
		boolean value = shadersJustToggledOff;
		shadersJustToggledOff = false;
		return value;
	}
	
	public static boolean isShaderSafeMode()
	{
		return refreshShadersActive();
	}
}



