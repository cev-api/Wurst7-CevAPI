/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

/**
 * A sanitized API failure. Messages are safe to display and never include
 * request credentials.
 */
public final class PatreonApiException extends Exception
{
	private final int statusCode;
	private final String errorCode;
	private final boolean networkError;
	
	private PatreonApiException(String message, int statusCode,
		String errorCode, boolean networkError, Throwable cause)
	{
		super(message, cause);
		this.statusCode = statusCode;
		this.errorCode = errorCode == null ? "" : errorCode;
		this.networkError = networkError;
	}
	
	public static PatreonApiException network(String message, Throwable cause)
	{
		return new PatreonApiException(message, 0, "", true, cause);
	}
	
	public static PatreonApiException response(int statusCode, String errorCode,
		String message)
	{
		return new PatreonApiException(message, statusCode, errorCode, false,
			null);
	}
	
	public int getStatusCode()
	{
		return statusCode;
	}
	
	public String getErrorCode()
	{
		return errorCode;
	}
	
	public boolean isNetworkError()
	{
		return networkError;
	}
	
	public boolean isServiceUnavailable()
	{
		return networkError || statusCode == 408 || statusCode == 429
			|| statusCode >= 500;
	}
	
	/**
	 * True when the backend has authoritatively rejected the credential or
	 * confirmed that the membership is no longer entitled. Transient network
	 * and provider failures return false.
	 */
	public boolean isDefinitiveInvalid()
	{
		if(networkError)
			return false;
		
		if(statusCode == 401 || statusCode == 403)
			return true;
		
		return switch(errorCode.toLowerCase(java.util.Locale.ROOT))
		{
			case "unauthorized", "invalid_token", "invalid_grant", "expired_token", "revoked", "membership_inactive", "not_supporter", "inactive" -> true;
			default -> false;
		};
	}
}
