/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.vaultroll;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Exact XoroshiroRandomSource/RandomSequence behavior used by loot tables. */
public final class VaultRollRandom
{
	private static final long SILVER_RATIO_64 = 0x6A09E667F3BCC909L;
	private static final long GOLDEN_RATIO_64 = 0x9E3779B97F4A7C15L;
	private static final long MIX_CONST_1 = 0xBF58476D1CE4E5B9L;
	private static final long MIX_CONST_2 = 0x94D049BB133111EBL;
	
	private long s0;
	private long s1;
	
	private VaultRollRandom(long s0, long s1)
	{
		if((s0 | s1) == 0L)
		{
			s0 = GOLDEN_RATIO_64;
			s1 = SILVER_RATIO_64;
		}
		this.s0 = s0;
		this.s1 = s1;
	}
	
	public static VaultRollRandom forSequence(long worldSeed,
		VaultRollMode mode)
	{
		long lo = ((long)worldSeed) ^ SILVER_RATIO_64;
		long hi = lo + GOLDEN_RATIO_64;
		byte[] digest;
		try
		{
			MessageDigest md5 = MessageDigest.getInstance("MD5");
			digest =
				md5.digest(mode.sequenceId().getBytes(StandardCharsets.UTF_8));
		}catch(NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("MD5 unavailable", e);
		}
		long keyLo = readBigEndianLong(digest, 0);
		long keyHi = readBigEndianLong(digest, 8);
		return new VaultRollRandom(mixStafford13(lo ^ keyLo),
			mixStafford13(hi ^ keyHi));
	}
	
	public long nextLong()
	{
		long a = s0;
		long b = s1;
		long result = Long.rotateLeft(a + b, 17) + a;
		b ^= a;
		s0 = Long.rotateLeft(a, 49) ^ b ^ (b << 21);
		s1 = Long.rotateLeft(b, 28);
		return result;
	}
	
	public int nextIntRaw()
	{
		return (int)nextLong();
	}
	
	/** XoroshiroRandomSource.nextInt(bound), using Lemire rejection. */
	public int nextInt(int bound)
	{
		if(bound <= 0)
			throw new IllegalArgumentException("Bound must be positive");
		int unsignedBound = bound;
		long l = Integer.toUnsignedLong(nextIntRaw());
		long m = l * unsignedBound;
		long n = m & 0xFFFFFFFFL;
		if(n < unsignedBound)
		{
			int j =
				Integer.remainderUnsigned(~unsignedBound + 1, unsignedBound);
			while(n < Integer.toUnsignedLong(j))
			{
				l = Integer.toUnsignedLong(nextIntRaw());
				m = l * unsignedBound;
				n = m & 0xFFFFFFFFL;
			}
		}
		return (int)(m >>> 32);
	}
	
	public float nextFloat()
	{
		return (nextLong() >>> 40) * 5.9604645E-8F;
	}
	
	public double nextDouble()
	{
		return (nextLong() >>> 11) * 1.1102230246251565E-16D;
	}
	
	public int nextIntInclusive(int min, int max)
	{
		if(min >= max)
			return min;
		return nextInt(max - min + 1) + min;
	}
	
	public float nextFloat(float min, float max)
	{
		if(min >= max)
			return min;
		return nextFloat() * (max - min) + min;
	}
	
	private static long mixStafford13(long value)
	{
		value = (value ^ (value >>> 30)) * MIX_CONST_1;
		value = (value ^ (value >>> 27)) * MIX_CONST_2;
		return value ^ (value >>> 31);
	}
	
	private static long readBigEndianLong(byte[] bytes, int offset)
	{
		long result = 0L;
		for(int i = 0; i < 8; i++)
			result = (result << 8) | (bytes[offset + i] & 0xFFL);
		return result;
	}
}
