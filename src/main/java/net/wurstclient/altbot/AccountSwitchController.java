/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altbot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ServerData;
import net.wurstclient.WurstClient;
import net.wurstclient.altmanager.AltManager;
import net.wurstclient.altmanager.LoginException;
import net.wurstclient.altmanager.TokenAlt;
import net.wurstclient.mixinterface.IMinecraftClient;
import net.wurstclient.util.ChatUtils;

/**
 * Implements the rendered-client / bot role exchange as an explicit
 * asynchronous state machine. A second switch is rejected while one is
 * running. Every failure path goes through {@link #rollback(String)} so the
 * client and the bot manager are never left in a broken state.
 *
 * <p>
 * All switching work happens on a dedicated single-thread executor, never on
 * the Minecraft render thread. Rendered-client operations are marshalled to
 * the client thread with {@link AltBotUtils#runOnClientThread(Runnable)}.
 */
public final class AccountSwitchController
{
	private static final long BOT_WAIT_TIMEOUT_MS = 15_000;
	private static final long CLIENT_PLAY_TIMEOUT_MS = 30_000;
	
	private final AltBotManager botManager;
	private final ExecutorService executor;
	private final AtomicBoolean switchInProgress = new AtomicBoolean();
	private final AtomicBoolean cancelRequested = new AtomicBoolean();
	
	private volatile SwitchState state = SwitchState.IDLE;
	private volatile String lastError;
	private volatile String rollbackProgress;
	
	// Captured state of the current switch operation.
	private volatile TokenAlt sourceAlt;
	private volatile TokenAlt targetAlt;
	private volatile String serverIp;
	private volatile ServerData serverData;
	private volatile String sourceHost;
	private volatile int sourcePort;
	private volatile boolean targetWasBot;
	private volatile boolean sourceBotStarted;
	private volatile boolean disconnectedClient;
	private volatile boolean sessionChanged;
	private volatile User previousSession;
	private volatile String sourceName;
	
	public AccountSwitchController(AltBotManager botManager)
	{
		this.botManager = botManager;
		executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "Wurst AltSwitch");
			t.setDaemon(true);
			return t;
		});
		Runtime.getRuntime().addShutdownHook(
			new Thread(this::shutdown, "Wurst AltSwitch Shutdown"));
	}
	
	// ------------------------------------------------------- public API
	
	/**
	 * Starts switching the rendered client to the given token alt. Rejected if
	 * another switch is already running.
	 *
	 * @return true if the switch was accepted.
	 */
	public boolean startSwitch(TokenAlt target)
	{
		if(!switchInProgress.compareAndSet(false, true))
		{
			ChatUtils.error("An account switch is already in progress.");
			return false;
		}
		executor.execute(() -> runSwitch(target));
		return true;
	}
	
	/** Requests a best-effort cancellation of the running switch. */
	public void cancelSwitch()
	{
		if(switchInProgress.get())
			cancelRequested.set(true);
		else
			ChatUtils.message("No account switch is in progress.");
	}
	
	public boolean isBusy()
	{
		return switchInProgress.get();
	}
	
	public SwitchState getState()
	{
		return state;
	}
	
	public String getStatus()
	{
		SwitchState s = state;
		if(s == SwitchState.ROLLING_BACK)
			return rollbackProgress == null ? "Rolling back..."
				: rollbackProgress;
		if(s == SwitchState.FAILED)
			return lastError == null ? "Switch failed." : lastError;
		if(s == SwitchState.COMPLETE)
			return "Switched to "
				+ (targetAlt == null ? "" : targetAlt.getDisplayName()) + ".";
		return describe(s);
	}
	
	private static String describe(SwitchState s)
	{
		return switch(s)
		{
			case IDLE -> "Idle";
			case VALIDATING -> "Validating target account...";
			case STOPPING_TARGET_BOT -> "Disconnecting target bot...";
			case DISCONNECTING_RENDERED_CLIENT -> "Disconnecting rendered client...";
			case AUTHENTICATING_SOURCE_BOT -> "Authenticating source account...";
			case CONNECTING_SOURCE_BOT -> "Connecting source bot...";
			case CHANGING_CLIENT_SESSION -> "Changing client session...";
			case RECONNECTING_RENDERED_CLIENT -> "Reconnecting rendered client...";
			case VERIFYING -> "Verifying connection...";
			case ROLLING_BACK -> "Rolling back...";
			case COMPLETE -> "Complete";
			case FAILED -> "Failed";
		};
	}
	
	void shutdown()
	{
		executor.shutdown();
	}
	
	// ------------------------------------------------------- switch flow
	
	private void runSwitch(TokenAlt target)
	{
		cancelRequested.set(false);
		targetAlt = target;
		state = SwitchState.VALIDATING;
		
		try
		{
			checkCancelled();
			
			// 1. Verify the player is connected to a multiplayer server.
			if(!AltBotUtils.isOnServer())
				throw new SwitchAborted(
					"You must be connected to a multiplayer server to switch accounts.");
			
			// 2. Verify the target is a real authenticated alt we can use.
			if(target == null)
				throw new SwitchAborted("No target account selected.");
			if(target.isCracked())
				throw new SwitchAborted(
					"Cracked accounts cannot be used by the bot engine.");
			if(target.getName().isBlank() && !target.isCheckedPremium())
				throw new SwitchAborted(
					"Target account has no validated profile.");
			
			// 3. Verify the target is not the account we are currently on.
			if(botManager.isActiveClientAlt(target))
				throw new SwitchAborted("\"" + target.getDisplayName()
					+ "\" is already the rendered client.");
			
			// 4. Capture required state.
			serverData = Minecraft.getInstance().getCurrentServer();
			if(serverData == null)
				throw new SwitchAborted("No current server data.");
			serverIp = serverData.ip;
			String[] hostPort = AltBotUtils.resolveHostPort(serverIp);
			sourceHost = hostPort[0];
			sourcePort = Integer.parseInt(hostPort[1]);
			
			IMinecraftClient imc = WurstClient.IMC;
			previousSession = imc.getWurstSession();
			sourceAlt = botManager.resolveCurrentAlt();
			sourceName = Minecraft.getInstance().getUser().getName();
			
			log("captured server " + serverIp + " as " + sourceName + " -> "
				+ target.getDisplayName());
			
			// 5. Prevent manual/duplicate switch operations.
			// (switchInProgress already set in startSwitch)
			
			// 6. Stop the target's existing bot connection if any.
			targetWasBot = botManager.isBotConnected(target);
			if(targetWasBot)
			{
				state = SwitchState.STOPPING_TARGET_BOT;
				botManager.disconnectBot(target);
				if(!waitForBotGone(target, BOT_WAIT_TIMEOUT_MS))
					throw new SwitchAborted(
						"Timed out disconnecting the target bot \""
							+ target.getDisplayName() + "\".");
				// The local protocol session can disappear before the server
				// has
				// processed its disconnect. Give the server a short grace
				// period
				// before logging the same account in through the rendered
				// client.
				Thread.sleep(2_000);
			}
			checkCancelled();
			
			// 7. Disconnect the rendered client.
			state = SwitchState.DISCONNECTING_RENDERED_CLIENT;
			AltBotUtils.runOnClientThread(AltBotUtils::disconnectClient);
			disconnectedClient = true;
			Thread.sleep(500);
			checkCancelled();
			
			// 8. + 9. Authenticate the source account and connect it as a bot.
			// If the source account is not a stored token alt (e.g. the
			// launcher's original account), skip parking it as a bot.
			state = SwitchState.AUTHENTICATING_SOURCE_BOT;
			if(sourceAlt != null)
			{
				state = SwitchState.CONNECTING_SOURCE_BOT;
				botManager.connectBotForSwitch(sourceAlt, sourceHost,
					sourcePort);
				sourceBotStarted = true;
				
				// 10. Wait for the source bot to reach the play state.
				if(!waitForBotReady(sourceAlt, BOT_WAIT_TIMEOUT_MS))
					throw new SwitchAborted("Source bot \"" + sourceAlt
						+ "\" failed to reach the play state.");
			}else
				log("source account is not a stored token alt; skipping source bot");
			checkCancelled();
			
			// 11. Change the rendered client's session to the target using the
			// existing Alt Manager login code.
			state = SwitchState.CHANGING_CLIENT_SESSION;
			loginRenderedClient(target);
			sessionChanged = true;
			checkCancelled();
			
			// 12. Reconnect the rendered client to the recorded server.
			state = SwitchState.RECONNECTING_RENDERED_CLIENT;
			AltBotUtils.runOnClientThread(
				() -> AltBotUtils.reconnectClient(serverData, null));
			checkCancelled();
			
			// 13. Verify the rendered client reached the play state as B.
			state = SwitchState.VERIFYING;
			if(!waitForClientPlay(target, CLIENT_PLAY_TIMEOUT_MS))
				throw new SwitchAborted(
					"Rendered client did not reach the play state as \""
						+ target.getDisplayName() + "\".");
			
			// 14. + 15. Mark complete and refresh the Alt Manager.
			state = SwitchState.COMPLETE;
			lastError = null;
			ChatUtils.message("Switched to " + target.getDisplayName() + ".");
			log("switch complete: " + sourceName + " -> "
				+ target.getDisplayName());
			// A successful switch must clear the busy flag, otherwise every
			// later switch is rejected as "already in progress".
			switchInProgress.set(false);
			cancelRequested.set(false);
			
		}catch(SwitchAborted e)
		{
			if(cancelRequested.get())
				e = new SwitchAborted("Account switch cancelled.");
			state = SwitchState.ROLLING_BACK;
			lastError = e.getMessage();
			rollback(e.getMessage());
			
		}catch(Throwable e)
		{
			state = SwitchState.ROLLING_BACK;
			lastError = e.getMessage() == null || e.getMessage().isBlank()
				? e.getClass().getSimpleName() : e.getMessage();
			rollback(lastError);
		}
	}
	
	/** Logs the rendered client in as the given alt (Alt Manager code path). */
	private void loginRenderedClient(TokenAlt target) throws LoginException
	{
		AltManager altManager = WurstClient.INSTANCE.getAltManager();
		// altManager.login() authenticates off-thread-safe (we are on our own
		// executor) and applies the session via the standard Wurst code.
		altManager.login(target);
	}
	
	// ------------------------------------------------------- rollback
	
	private void rollback(String reason)
	{
		state = SwitchState.ROLLING_BACK;
		boolean ok = true;
		try
		{
			// If the rendered client is currently in-game (e.g. the target
			// connected but verification failed), disconnect it first.
			if(AltBotUtils.isOnServer())
			{
				rollbackProgress = "Disconnecting rendered client...";
				AltBotUtils.runOnClientThread(AltBotUtils::disconnectClient);
				Thread.sleep(500);
			}
			
			// Restore the previous session (A) if we changed it.
			if(sessionChanged)
			{
				rollbackProgress = "Restoring previous account session...";
				AltBotUtils.runOnClientThread(
					() -> WurstClient.IMC.setWurstSession(previousSession));
			}
			
			// Stop the source bot (A) to avoid duplicate-session problems.
			if(sourceBotStarted && sourceAlt != null)
			{
				rollbackProgress =
					"Stopping source bot " + sourceAlt.getDisplayName() + "...";
				botManager.disconnectBot(sourceAlt);
				waitForBotGone(sourceAlt, 5_000);
			}
			
			// Reconnect the rendered client as A if we disconnected it.
			if(disconnectedClient)
			{
				rollbackProgress = "Reconnecting rendered client as "
					+ (sourceName == null ? "previous account" : sourceName)
					+ "...";
				AltBotUtils.runOnClientThread(
					() -> AltBotUtils.reconnectClient(serverData, null));
			}
			
			// Restore the target bot (B) if it was connected before.
			if(targetWasBot && targetAlt != null)
			{
				rollbackProgress = "Restoring target bot "
					+ targetAlt.getDisplayName() + "...";
				botManager.connectBot(targetAlt, sourceHost, sourcePort);
			}
			
			ok = true;
			rollbackProgress = null;
			
		}catch(Throwable e)
		{
			ok = false;
			rollbackProgress =
				e.getMessage() == null || e.getMessage().isBlank()
					? e.getClass().getSimpleName() : e.getMessage();
		}
		
		state = SwitchState.FAILED;
		ChatUtils.error("Account switch failed: " + reason);
		if(ok)
			ChatUtils.message("Restored the previous account successfully.");
		else
			ChatUtils.error("Rollback failed: " + rollbackProgress);
		log("switch failed (" + (ok ? "rollback ok" : "rollback failed") + "): "
			+ reason);
		switchInProgress.set(false);
	}
	
	// ------------------------------------------------------- wait helpers
	
	private boolean waitForBotGone(TokenAlt alt, long timeoutMs)
	{
		long deadline = System.currentTimeMillis() + timeoutMs;
		while(System.currentTimeMillis() < deadline)
		{
			if(cancelRequested.get())
				return true;
			if(!botManager.isBotConnected(alt))
				return true;
			sleep(100);
		}
		return !botManager.isBotConnected(alt);
	}
	
	private boolean waitForBotReady(TokenAlt alt, long timeoutMs)
	{
		long deadline = System.currentTimeMillis() + timeoutMs;
		while(System.currentTimeMillis() < deadline)
		{
			if(cancelRequested.get())
				return false;
			if(botManager.isBotReady(alt))
				return true;
			AltBotState st = botManager.getState(alt);
			if(st.getState() == BotState.FAILED)
				return false;
			sleep(100);
		}
		return botManager.isBotReady(alt);
	}
	
	private boolean waitForClientPlay(TokenAlt target, long timeoutMs)
	{
		long deadline = System.currentTimeMillis() + timeoutMs;
		while(System.currentTimeMillis() < deadline)
		{
			if(cancelRequested.get())
				return false;
			if(AltBotUtils.isOnServer())
			{
				// Verify the session actually is the target account.
				String name = Minecraft.getInstance().getUser().getName();
				if(AltBotUtils.matchesName(name, target.getName()))
					return true;
			}
			sleep(200);
		}
		return AltBotUtils.isOnServer();
	}
	
	private void checkCancelled() throws SwitchAborted
	{
		if(cancelRequested.get())
			throw new SwitchAborted("Account switch cancelled.");
	}
	
	private static void sleep(long ms)
	{
		try
		{
			Thread.sleep(ms);
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
	
	private void log(String message)
	{
		AltBotUtils.log("switch", message);
	}
	
	private static final class SwitchAborted extends Exception
	{
		private static final long serialVersionUID = 1L;
		
		SwitchAborted(String message)
		{
			super(message);
		}
	}
}
