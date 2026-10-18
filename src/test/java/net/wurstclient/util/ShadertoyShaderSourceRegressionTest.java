/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ShadertoyShaderSourceRegressionTest
{
	public static void main(String[] args) throws Exception
	{
		for(int i = 1; i <= 4; i++)
		{
			String name = "noise" + i;
			String source = "float " + name + "(vec2 p) { return p.x; }\n"
				+ "float result = " + name + "(vec2(1.0));\n" + "float my_"
				+ name + " = 0.0;\n";
			String adapted = ShadertoyShaderSource.prepareForDesktop(source);
			check(adapted.contains("float wurstShadertoy_" + name + "("),
				"helper declaration must be renamed");
			check(adapted.contains("= wurstShadertoy_" + name + "("),
				"helper calls must be renamed");
			check(adapted.contains("float my_" + name + " ="),
				"unrelated identifiers must be preserved");
			check(
				adapted
					.equals(ShadertoyShaderSource.prepareForDesktop(adapted)),
				"adapting an already generated shader must be idempotent");
		}
		String builtin = "vec2 n = noise2(vec2(1.0));";
		check(builtin.equals(ShadertoyShaderSource.prepareForDesktop(builtin)),
			"calls without user declarations must be preserved");
		if(args.length == 2)
			Files.writeString(Path.of(args[1]), ShadertoyShaderSource
				.prepareForDesktop(Files.readString(Path.of(args[0]))));
		System.out
			.println("Shadertoy desktop GLSL compatibility checks passed.");
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
