/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShadertoyShaderSource
{
	private static final Pattern NOISE_DECLARATION =
		Pattern.compile("\\b(?:float|vec[234])\\s+(noise[1-4])\\s*\\(");
	
	private ShadertoyShaderSource()
	{}
	
	public static String prepareForDesktop(String source)
	{
		// Desktop GLSL reserves noise1..noise4 with specific return types.
		// Shadertoy's GLSL ES allows helpers such as float noise2(vec2).
		Matcher declarations = NOISE_DECLARATION.matcher(source);
		while(declarations.find())
		{
			String name = declarations.group(1);
			source = source.replaceAll("\\b" + name + "\\b",
				"wurstShadertoy_" + name);
		}
		return source;
	}
}
