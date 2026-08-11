/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altbot;

/**
 * State of an account-switch operation.
 */
public enum SwitchState
{
	/** No switch in progress. */
	IDLE,
	
	/** Checking the target account and the current server. */
	VALIDATING,
	
	/** Disconnecting the target account's existing bot connection. */
	STOPPING_TARGET_BOT,
	
	/** Disconnecting the rendered client from the server. */
	DISCONNECTING_RENDERED_CLIENT,
	
	/** Authenticating the source account for background use. */
	AUTHENTICATING_SOURCE_BOT,
	
	/** Connecting the source account as a bot. */
	CONNECTING_SOURCE_BOT,
	
	/** Changing the rendered client's authentication session. */
	CHANGING_CLIENT_SESSION,
	
	/** Reconnecting the rendered client to the server. */
	RECONNECTING_RENDERED_CLIENT,
	
	/** Waiting for the rendered client to reach the play state. */
	VERIFYING,
	
	/** Undoing a partially completed switch after a failure. */
	ROLLING_BACK,
	
	/** The switch finished successfully. */
	COMPLETE,
	
	/** The switch failed. */
	FAILED;
}
