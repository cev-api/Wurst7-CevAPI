/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.cevapi.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.cevapi.config.AntiFingerprintConfig;
import net.cevapi.config.AntiFingerprintConfig.Policy;
import net.cevapi.config.AntiFingerprintConfig.ToastLevel;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.wurstclient.WurstClient;
import net.wurstclient.util.ChatUtils;

/**
 * Central decision maker for incoming resource pack requests.
 */
public final class ResourcePackProtector
{
	private static final Logger LOGGER =
		LogManager.getLogger("AntiFingerprint");
	
	private static final AntiFingerprintConfig CONFIG =
		AntiFingerprintConfig.INSTANCE;
	
	private static final int CACHE_LIMIT = 128;
	private static final int TOAST_LIMIT = 16;
	
	private static final Pattern IPV4_PATTERN =
		Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");
	
	private static final Map<String, Decision> SESSION_CACHE = createCache();
	private static final Map<String, PackHistory> PACK_HISTORY =
		new ConcurrentHashMap<>();
	
	private static final Deque<ToastPayload> TOAST_QUEUE = new ArrayDeque<>();
	private static final UUID SESSION_CACHE_SALT = UUID.randomUUID();
	private static final Map<UUID, PackContext> CONTEXTS_BY_ID =
		new ConcurrentHashMap<>();
	private static final Set<String> PROMPT_SNAPSHOTS =
		ConcurrentHashMap.newKeySet();
	private static final Map<String, Path> DOWNLOAD_TARGETS =
		new ConcurrentHashMap<>();
	
	private ResourcePackProtector()
	{
		
	}
	
	public static AntiFingerprintConfig getConfig()
	{
		return CONFIG;
	}
	
	private static void clearServerCacheIfEnabled()
	{
		if(!CONFIG.shouldClearCache())
			return;
		
		Minecraft client = WurstClient.MC;
		if(client == null || client.gameDirectory == null)
			return;
		
		Path cacheDir = client.gameDirectory.toPath().resolve("resourcepacks")
			.resolve("server");
		if(!Files.exists(cacheDir))
			return;
		
		try
		{
			Files.walkFileTree(cacheDir, new SimpleFileVisitor<>()
			{
				@Override
				public FileVisitResult visitFile(Path file,
					BasicFileAttributes attrs) throws IOException
				{
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}
				
				@Override
				public FileVisitResult postVisitDirectory(Path dir,
					IOException exc) throws IOException
				{
					if(exc != null)
						throw exc;
					
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}catch(IOException e)
		{
			LOGGER.debug("Failed to clear server resource-pack cache.", e);
		}
	}
	
	public static Path remapDownloadPath(Path directory, Path original,
		UUID packId)
	{
		PackContext context = getContext(packId);
		if(directory == null || original == null)
			return original;
		
		Path target = original;
		if(CONFIG.shouldIsolateCache())
		{
			Minecraft mc = Minecraft.getInstance();
			String accountSegment = "no-account";
			if(mc != null && mc.getUser() != null)
			{
				try
				{
					UUID accountId = mc.getUser().getProfileId();
					if(accountId != null)
						accountSegment = accountId.toString();
				}catch(Exception e)
				{
					LOGGER.debug(
						"Failed to obtain session UUID for cache isolation.",
						e);
				}
			}
			
			String fileName = original.getFileName() == null
				? packId != null ? packId.toString()
					: UUID.randomUUID().toString()
				: original.getFileName().toString();
			
			target = directory.resolve("cevapi").resolve(accountSegment)
				.resolve(SESSION_CACHE_SALT.toString()).resolve(fileName);
		}
		
		logDownloadLocation("CACHE_PATH", context, target);
		registerDownloadTarget(context, target);
		return target;
	}
	
	private static InetSocketAddress getRemoteAddress()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null)
			return null;
		
		ClientPacketListener handler = mc.getConnection();
		if(handler == null)
			return null;
		
		var address = handler.getConnection().getRemoteAddress();
		return address instanceof InetSocketAddress isa ? isa : null;
	}
	
	public static PolicyResult evaluate(
		ClientboundResourcePackPushPacket packet)
	{
		if(packet == null)
			return new PolicyResult(Decision.ALLOW, "No packet",
				PackContext.empty());
		
		try
		{
			clearServerCacheIfEnabled();
			
			PackContext context = PackContext.from(packet);
			if(context.cacheKey != null)
			{
				Decision cached = SESSION_CACHE.get(context.cacheKey);
				if(cached != null)
					return new PolicyResult(cached, "Cached decision", context);
			}
			registerContext(context);
			
			PolicyResult result = evaluateDecision(context);
			cacheDecision(context.cacheKey, result.decision());
			return result;
			
		}catch(Exception e)
		{
			LOGGER.warn("Failed to evaluate resource pack policy, falling back"
				+ " to vanilla behaviour.", e);
			pushToast(ToastLevel.WARN, "Anti-Fingerprint",
				"Policy evaluation failed. Allowing vanilla handling.");
			return new PolicyResult(Decision.ALLOW, "Evaluation error",
				PackContext.empty());
		}
	}
	
	public static boolean applyDecision(PolicyResult result,
		Connection connection, Minecraft mcClient)
	{
		if(result == null)
			return false;
		
		PackContext context = result.context();
		switch(result.decision())
		{
			case BLOCK ->
			{
				Minecraft target =
					mcClient != null ? mcClient : Minecraft.getInstance();
				if(target != null)
					target.getToastManager().queued.clear();
				sendStatus(connection, context,
					ServerboundResourcePackPacket.Action.DECLINED);
				noteHandled(context);
				return true;
			}
			
			case SANDBOX ->
			{
				sendStatus(connection, context,
					ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD);
				
				Minecraft target =
					mcClient != null ? mcClient : Minecraft.getInstance();
				if(target != null)
					target.execute(() -> sandboxDownload(context));
				return true;
			}
			
			default ->
			{
				return false;
			}
		}
	}
	
	public static void noteHandled(PackContext context)
	{
		if(context == null)
			return;
		
		try
		{
			cacheDecision(context.cacheKey,
				SESSION_CACHE.getOrDefault(context.cacheKey, Decision.ALLOW));
			clearContext(context);
		}catch(Exception e)
		{
			LOGGER.debug("Failed to record handled resource pack.", e);
		}
	}
	
	public static void sandboxDownload(PackContext context)
	{
		sandboxDownload(context, SandboxRequest.POLICY, null);
	}
	
	private static void sandboxDownload(PackContext context,
		SandboxRequest sandboxRequest, String dedupeKey)
	{
		try
		{
			URI uri = null;
			if(!context.url.isBlank())
			{
				try
				{
					uri = new URI(context.url);
				}catch(URISyntaxException e)
				{
					pushToast(ToastLevel.ERROR, context,
						sandboxRequest.failTitle, "Invalid URL syntax.");
					LOGGER.warn("Invalid resource pack URI {}", context.url, e);
					logAudit(sandboxRequest.auditFail, context,
						"error=invalid_url message=" + shortMessage(e));
					if(dedupeKey != null)
						PROMPT_SNAPSHOTS.remove(dedupeKey);
					return;
				}
			}
			
			Path sandboxFolder =
				WurstClient.INSTANCE.getWurstFolder().resolve("sandbox-packs");
			Files.createDirectories(sandboxFolder);
			
			String fileName = buildSandboxFileName(context, uri);
			Path sandboxTarget = ensureUnique(sandboxFolder.resolve(fileName));
			
			pushToast(sandboxRequest.startLevel, context,
				sandboxRequest.startTitle, sandboxRequest.startBody);
			logAudit(sandboxRequest.auditStart, context,
				"target=" + sandboxTarget.toAbsolutePath());
			
			if(uri == null)
			{
				copyCachedPack(context, sandboxTarget, sandboxRequest,
					dedupeKey);
				return;
			}
			
			HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(15)).build();
			
			HttpRequest httpRequest = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(60)).GET().build();
			
			HttpResponse.BodyHandler<Path> handler =
				HttpResponse.BodyHandlers.ofFile(sandboxTarget);
			
			client.sendAsync(httpRequest, handler)
				.whenCompleteAsync((response, throwable) -> {
					if(throwable != null)
					{
						handleSandboxFailure(context, sandboxTarget, throwable,
							sandboxRequest, dedupeKey);
						return;
					}
					
					int status = response.statusCode();
					if(status >= 200 && status < 300)
					{
						Path absolute = sandboxTarget.toAbsolutePath();
						if(dedupeKey != null)
							PROMPT_SNAPSHOTS.remove(dedupeKey);
						handleSandboxSuccess(absolute, context, sandboxRequest,
							"status=" + status + " path=" + absolute);
						return;
					}
					
					handleSandboxFailure(context, sandboxTarget,
						new IOException("HTTP " + status), sandboxRequest,
						dedupeKey);
				}, Util.ioPool());
			
		}catch(Exception e)
		{
			LOGGER.warn("Sandbox download failed unexpectedly.", e);
			pushToast(ToastLevel.ERROR, context, sandboxRequest.failTitle,
				"Unexpected error: " + e.getClass().getSimpleName());
			logAudit(sandboxRequest.auditFail, context,
				"error=unexpected:" + e.getClass().getSimpleName());
			if(dedupeKey != null)
				PROMPT_SNAPSHOTS.remove(dedupeKey);
		}
	}
	
	public static void flushToasts()
	{
		Minecraft client = WurstClient.MC;
		if(client == null)
			return;
		
		List<ToastPayload> payloads;
		synchronized(TOAST_QUEUE)
		{
			if(TOAST_QUEUE.isEmpty())
				return;
			
			payloads = new ArrayList<>(TOAST_QUEUE);
			TOAST_QUEUE.clear();
		}
		
		ToastManager manager = client.getToastManager();
		for(ToastPayload payload : payloads)
		{
			try
			{
				SystemToast.add(manager, payload.type, payload.title,
					payload.description);
			}catch(Exception e)
			{
				LOGGER.debug("Failed to show toast {}", payload, e);
			}
		}
	}
	
	private static PolicyResult evaluateDecision(PackContext context)
	{
		recordFingerprint(context);
		
		Policy policy = CONFIG.getPolicy();
		boolean whitelisted = isWhitelisted(context);
		boolean local = context.host.local;
		
		if(whitelisted)
		{
			logAudit("ALLOW_WHITELIST", context, "");
			pushToast(ToastLevel.INFO, context, "Whitelisted host",
				"Bypassed Anti-Fingerprint policy.");
			return new PolicyResult(Decision.ALLOW, "Whitelisted host",
				context);
		}
		
		return switch(policy)
		{
			case BLOCK_ALL -> block(context, "Policy set to Block All");
			case SANDBOX_ALL -> sandbox(context, "Policy set to Sandbox All");
			case BLOCK_LOCAL -> local
				? block(context, "Blocked local host request")
				: observe(context, "Local host allowed (policy passive)");
			case OBSERVE -> observe(context, "");
		};
	}
	
	private static PolicyResult block(PackContext context, String reason)
	{
		pushToast(ToastLevel.WARN, context, "Resource pack blocked", reason);
		logAudit("BLOCK", context, reason);
		return new PolicyResult(Decision.BLOCK, reason, context);
	}
	
	private static PolicyResult sandbox(PackContext context, String reason)
	{
		pushToast(ToastLevel.WARN, context, "Resource pack sandboxed", reason);
		logAudit("SANDBOX", context, reason);
		return new PolicyResult(Decision.SANDBOX, reason, context);
	}
	
	private static PolicyResult observe(PackContext context, String extra)
	{
		String detail = extra == null || extra.isBlank()
			? "Server requested a resource pack." : extra;
		pushToast(ToastLevel.INFO, context, "Resource pack request", detail);
		logAudit("OBSERVE", context, detail);
		if(context != null && (!context.required || !context.prompt.isBlank()))
			schedulePromptSandboxDownload(context);
		return new PolicyResult(Decision.ALLOW, detail, context);
	}
	
	private static void schedulePromptSandboxDownload(PackContext context)
	{
		if(context == null)
			return;
		
		String key = context.cacheKey;
		if(key == null || key.isBlank())
		{
			if(context.packId != null)
				key = context.packId.toString();
			else if(!context.url.isBlank())
				key = context.url;
		}
		
		if(key == null || key.isBlank())
			key = context.url;
		if(key == null || key.isBlank())
			return;
		
		if(!PROMPT_SNAPSHOTS.add(key))
			return;
		
		Minecraft client = WurstClient.MC;
		if(client == null)
		{
			PROMPT_SNAPSHOTS.remove(key);
			return;
		}
		
		final String snapshotKey = key;
		try
		{
			client.execute(() -> sandboxDownload(context, SandboxRequest.PROMPT,
				snapshotKey));
		}catch(Exception e)
		{
			PROMPT_SNAPSHOTS.remove(snapshotKey);
			LOGGER.warn("Failed to schedule prompt sandbox download.", e);
		}
	}
	
	private static void recordFingerprint(PackContext context)
	{
		String key = context.host.canonicalOrFallback();
		long now = System.currentTimeMillis();
		PackHistory history =
			PACK_HISTORY.computeIfAbsent(key, unused -> new PackHistory());
		
		int threshold = CONFIG.getFingerprintThreshold();
		long windowMs = CONFIG.getFingerprintWindowMs();
		
		synchronized(history)
		{
			history.timestamps.addLast(now);
			while(!history.timestamps.isEmpty()
				&& now - history.timestamps.peekFirst() > windowMs)
				history.timestamps.removeFirst();
			
			if(history.timestamps.size() >= threshold)
			{
				if(now - history.lastAlert > windowMs)
				{
					history.lastAlert = now;
					String message =
						"Possible resource-pack fingerprint attempt" + " from "
							+ context.host.displayName() + " ("
							+ history.timestamps.size() + " packs / " + windowMs
							+ "ms)";
					Minecraft client = WurstClient.MC;
					if(client != null)
					{
						client.execute(() -> ChatUtils.warning(message));
					}
					pushToast(ToastLevel.WARN, context,
						"Fingerprint attempt detected", message);
					logAudit("FINGERPRINT", context, message);
				}
			}
		}
	}
	
	private static boolean isWhitelisted(PackContext context)
	{
		Set<String> whitelist = CONFIG.getWhitelistedHosts();
		if(whitelist.isEmpty())
			return false;
		
		String canonical = context.host.canonical;
		if(!canonical.isEmpty()
			&& whitelist.contains(canonical.toLowerCase(Locale.ROOT)))
			return true;
		
		String host = context.host.host;
		return !host.isEmpty()
			&& whitelist.contains(host.toLowerCase(Locale.ROOT));
	}
	
	private static void handleSandboxFailure(PackContext context, Path target,
		Throwable throwable, SandboxRequest sandboxRequest, String dedupeKey)
	{
		try
		{
			Files.deleteIfExists(target);
		}catch(IOException e)
		{
			LOGGER.debug("Failed to delete failed sandbox file {}", target, e);
		}
		
		LOGGER.warn("Sandbox download failed for {}", context.url, throwable);
		pushToast(ToastLevel.ERROR, context, sandboxRequest.failTitle,
			shortMessage(throwable));
		logAudit(sandboxRequest.auditFail, context,
			shortMessage(throwable) + " target=" + target.toAbsolutePath());
		if(dedupeKey != null)
			PROMPT_SNAPSHOTS.remove(dedupeKey);
		noteHandled(context);
	}
	
	private static void handleSandboxSuccess(Path absolute, PackContext context,
		SandboxRequest sandboxRequest, String auditDetail)
	{
		pushToast(sandboxRequest.successLevel, context,
			sandboxRequest.successTitle, sandboxRequest.successBodyPrefix
				+ trimToLength(absolute.toString(), 160));
		logAudit(sandboxRequest.auditOk, context, auditDetail);
		clearDownloadTarget(context);
		if(CONFIG.shouldExtractSandbox())
			CompletableFuture
				.runAsync(() -> extractSandboxPack(absolute, context),
					Util.ioPool())
				.whenComplete((unused, throwable) -> noteHandled(context));
		else
			noteHandled(context);
	}
	
	private static void copyCachedPack(PackContext context, Path sandboxTarget,
		SandboxRequest sandboxRequest, String dedupeKey)
	{
		CompletableFuture
			.supplyAsync(() -> copyCachedPackSync(context, sandboxTarget),
				Util.ioPool())
			.whenComplete((absolute, throwable) -> {
				if(throwable != null)
				{
					Throwable cause = throwable;
					if(throwable instanceof CompletionException completion)
						cause = completion.getCause();
					if(cause == null)
						cause = throwable;
					
					handleSandboxFailure(context, sandboxTarget, cause,
						sandboxRequest, dedupeKey);
					return;
				}
				
				if(absolute == null)
				{
					Path cached = getDownloadTarget(context);
					String message = cached == null
						? context != null && context.url.isBlank()
							? "Cached pack path unavailable (no URL provided)"
							: "Cached pack path unavailable"
						: "Cached pack not ready";
					handleSandboxFailure(context, sandboxTarget,
						new IOException(message), sandboxRequest, dedupeKey);
					return;
				}
				
				if(dedupeKey != null)
					PROMPT_SNAPSHOTS.remove(dedupeKey);
				handleSandboxSuccess(absolute, context, sandboxRequest,
					"source=CACHE path=" + absolute);
			});
	}
	
	private static Path copyCachedPackSync(PackContext context,
		Path sandboxTarget)
	{
		if(context == null)
			return null;
		
		long deadline =
			System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
		long lastSize = -1L;
		Path lastPath = null;
		int stableTicks = 0;
		IOException lastError = null;
		
		while(System.currentTimeMillis() < deadline)
		{
			Path cachePath = getDownloadTarget(context);
			if(cachePath != null && Files.exists(cachePath))
			{
				try
				{
					if(!cachePath.equals(lastPath))
					{
						lastPath = cachePath;
						lastSize = -1L;
						stableTicks = 0;
					}
					
					long size = Files.size(cachePath);
					if(size > 0 && size == lastSize)
						stableTicks++;
					else
					{
						lastSize = size;
						stableTicks = 0;
					}
					
					if(stableTicks >= 2 && size > 0)
					{
						Path parent = sandboxTarget.getParent();
						if(parent != null)
							Files.createDirectories(parent);
						Files.copy(cachePath, sandboxTarget,
							StandardCopyOption.REPLACE_EXISTING);
						return sandboxTarget.toAbsolutePath();
					}
				}catch(IOException e)
				{
					lastError = e;
				}
			}
			
			try
			{
				Thread.sleep(200);
			}catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new RuntimeException(
					"Interrupted while copying cached resource pack", e);
			}
		}
		
		if(lastError != null)
			throw new UncheckedIOException(lastError);
		return null;
	}
	
	private static String shortMessage(Throwable throwable)
	{
		String msg = throwable.getMessage();
		if(msg == null || msg.isBlank())
			return throwable.getClass().getSimpleName();
		return msg;
	}
	
	private static void cacheDecision(String key, Decision decision)
	{
		if(key == null || key.isEmpty() || decision == null)
			return;
		
		SESSION_CACHE.put(key, decision);
	}
	
	private static void registerContext(PackContext context)
	{
		if(context == null)
			return;
		
		if(context.packId != null)
			CONTEXTS_BY_ID.put(context.packId, context);
	}
	
	private static PackContext getContext(UUID packId)
	{
		if(packId == null)
			return null;
		return CONTEXTS_BY_ID.get(packId);
	}
	
	private static void clearContext(PackContext context)
	{
		if(context == null)
			return;
		
		if(context.packId != null)
			CONTEXTS_BY_ID.remove(context.packId);
		
		clearDownloadTarget(context);
	}
	
	private static void logDownloadLocation(String action, PackContext context,
		Path path)
	{
		if(path == null)
			return;
		
		logAudit(action, context, "path=" + path.toAbsolutePath());
	}
	
	private static void registerDownloadTarget(PackContext context, Path path)
	{
		String key = contextKey(context);
		if(key == null || path == null)
			return;
		
		DOWNLOAD_TARGETS.put(key, path);
	}
	
	private static Path getDownloadTarget(PackContext context)
	{
		String key = contextKey(context);
		if(key == null)
			return null;
		return DOWNLOAD_TARGETS.get(key);
	}
	
	private static void clearDownloadTarget(PackContext context)
	{
		String key = contextKey(context);
		if(key == null)
			return;
		DOWNLOAD_TARGETS.remove(key);
	}
	
	private static String contextKey(PackContext context)
	{
		if(context == null)
			return null;
		
		if(context.packId != null)
			return "id:" + context.packId;
		
		if(context.cacheKey != null && !context.cacheKey.isBlank())
			return "cache:" + context.cacheKey;
		
		if(!context.url.isBlank())
			return "url:" + context.url;
		
		return null;
	}
	
	private static void extractSandboxPack(Path zipPath, PackContext context)
	{
		Path extractDir;
		try
		{
			extractDir = createExtractionDirectory(zipPath);
		}catch(IOException e)
		{
			LOGGER.warn("Failed to prepare extraction directory for {}",
				zipPath, e);
			logAudit("SANDBOX_EXTRACT_FAIL", context, "dir=<unprepared> error="
				+ e.getClass().getSimpleName() + ":" + shortMessage(e));
			return;
		}
		
		try(FileSystem fs =
			FileSystems.newFileSystem(zipPath, (ClassLoader)null))
		{
			for(Path root : fs.getRootDirectories())
			{
				try(var walk = Files.walk(root))
				{
					walk.forEach(source -> {
						Path relative = root.relativize(source);
						Path target = extractDir.resolve(relative.toString());
						try
						{
							if(Files.isDirectory(source))
							{
								Files.createDirectories(target);
							}else
							{
								Path parent = target.getParent();
								if(parent != null)
									Files.createDirectories(parent);
								Files.copy(source, target,
									StandardCopyOption.REPLACE_EXISTING);
							}
						}catch(IOException ex)
						{
							throw new UncheckedIOException(ex);
						}
					});
				}
			}
			logAudit("SANDBOX_EXTRACT", context,
				"dir=" + extractDir.toAbsolutePath());
		}catch(UncheckedIOException e)
		{
			IOException cause = e.getCause();
			LOGGER.warn("Failed while extracting sandboxed pack {}", zipPath,
				cause);
			logAudit("SANDBOX_EXTRACT_FAIL", context,
				"dir=" + extractDir.toAbsolutePath() + " error="
					+ cause.getClass().getSimpleName() + ":"
					+ shortMessage(cause));
		}catch(Exception e)
		{
			LOGGER.warn("Failed to extract sandboxed pack {}", zipPath, e);
			logAudit("SANDBOX_EXTRACT_FAIL", context,
				"dir=" + extractDir.toAbsolutePath() + " error="
					+ e.getClass().getSimpleName() + ":" + shortMessage(e));
		}
	}
	
	private static Path createExtractionDirectory(Path zipPath)
		throws IOException
	{
		Path parent = zipPath.getParent();
		if(parent == null)
			parent = zipPath.toAbsolutePath().getParent();
		if(parent == null)
			parent = zipPath.toAbsolutePath();
		
		String baseName = stripExtension(zipPath.getFileName().toString());
		if(baseName.isBlank())
			baseName = "resource-pack";
		
		Path candidate = parent.resolve(baseName + "-extracted");
		int index = 1;
		while(Files.exists(candidate))
			candidate = parent.resolve(baseName + "-extracted-" + index++);
		
		Files.createDirectories(candidate);
		return candidate;
	}
	
	private static String stripExtension(String value)
	{
		if(value == null)
			return "";
		int dot = value.lastIndexOf('.');
		return dot > 0 ? value.substring(0, dot) : value;
	}
	
	private static void sendStatus(Connection connection, PackContext context,
		ServerboundResourcePackPacket.Action status)
	{
		if(connection == null || status == null)
			return;
		
		try
		{
			connection.send(
				new ServerboundResourcePackPacket(context.packId, status));
		}catch(Exception e)
		{
			LOGGER.debug("Failed to send resource-pack status update.", e);
		}
	}
	
	private static Map<String, Decision> createCache()
	{
		return Collections.synchronizedMap(
			new LinkedHashMap<String, Decision>(CACHE_LIMIT, 0.75F, true)
			{
				private static final long serialVersionUID = 1L;
				
				@Override
				protected boolean removeEldestEntry(
					Map.Entry<String, Decision> eldest)
				{
					return size() > CACHE_LIMIT;
				}
			});
	}
	
	private static void pushToast(ToastLevel level, PackContext context,
		String title, String detail)
	{
		pushToast(level, title, composeToastDetail(context, detail));
	}
	
	private static void pushToast(ToastLevel level, String title,
		String message)
	{
		if(!CONFIG.getToastVerbosity().allows(level))
			return;
		
		Component toastTitle = Component.literal(title == null ? "" : title);
		Component toastMessage = message == null || message.isBlank()
			? Component.literal("") : Component.literal(message);
		
		ToastPayload payload =
			new ToastPayload(toToastType(level), toastTitle, toastMessage);
		
		synchronized(TOAST_QUEUE)
		{
			while(TOAST_QUEUE.size() >= TOAST_LIMIT)
				TOAST_QUEUE.pollFirst();
			TOAST_QUEUE.addLast(payload);
		}
	}
	
	private static String composeToastDetail(PackContext context, String detail)
	{
		StringBuilder sb = new StringBuilder();
		
		if(context != null)
		{
			if(context.host != null)
			{
				String hostDisplay = context.host.displayName();
				if(hostDisplay != null && !hostDisplay.isEmpty()
					&& !"<unknown>".equals(hostDisplay))
				{
					sb.append("Host: ").append(hostDisplay);
				}
				
				if(context.host.ip() != null && !context.host.ip().isBlank())
				{
					if(sb.length() > 0)
						sb.append('\n');
					sb.append("IP: ").append(context.host.ip());
				}
				
				if(context.host.remote() != null
					&& !context.host.remote().isBlank())
				{
					if(sb.length() > 0)
						sb.append('\n');
					sb.append("Remote: ").append(context.host.remote());
				}
			}
			
			if(context.packId != null)
			{
				if(sb.length() > 0)
					sb.append('\n');
				sb.append("Pack ID: ").append(context.packId);
			}
			
			if(context.hash != null && !context.hash.isBlank())
			{
				if(sb.length() > 0)
					sb.append('\n');
				sb.append("Hash: ").append(context.hash);
			}
			
			if(!context.url.isBlank())
			{
				if(sb.length() > 0)
					sb.append('\n');
				sb.append("URL: ").append(trimToLength(context.url, 200));
			}
			
			if(sb.length() > 0)
				sb.append('\n');
			sb.append("Required: ").append(context.required);
			
			if(detail != null && !detail.isBlank())
			{
				if(sb.length() > 0)
					sb.append('\n');
				sb.append(detail);
			}
			
			if(!context.prompt.isBlank())
			{
				if(sb.length() > 0)
					sb.append('\n');
				sb.append("Prompt: ").append(trimToLength(context.prompt, 160));
			}
			
			if(sb.length() == 0)
				return detail == null ? "" : detail;
			
			return sb.toString();
		}
		
		return detail == null ? "" : detail;
	}
	
	private static String describeHost(HostInfo host)
	{
		if(host == null)
			return "<unknown>";
		
		String display = host.displayName();
		return display == null || display.isBlank() ? "<unknown>" : display;
	}
	
	private static String trimToLength(String value, int max)
	{
		if(value == null)
			return "";
		if(value.length() <= max)
			return value;
		return value.substring(0, Math.max(0, max - 1)) + "\u2026";
	}
	
	private static String sanitizeMultiline(String value)
	{
		if(value == null)
			return "";
		return value.replace('\r', ' ').replace('\n', ' ').trim();
	}
	
	private static SystemToast.SystemToastId toToastType(ToastLevel level)
	{
		return switch(level)
		{
			case INFO -> SystemToast.SystemToastId.NARRATOR_TOGGLE;
			case WARN -> SystemToast.SystemToastId.UNSECURE_SERVER_WARNING;
			case ERROR -> SystemToast.SystemToastId.PACK_LOAD_FAILURE;
		};
	}
	
	private static String buildSandboxFileName(PackContext context, URI uri)
	{
		String base;
		if(!context.hash.isEmpty())
			base = context.hash;
		else if(!context.host.canonical.isEmpty())
			base = context.host.canonical.replace(':', '_');
		else if(context.packId != null)
			base = context.packId.toString();
		else
			base = "resource-pack";
		
		String path = uri != null ? uri.getPath() : null;
		String extension = ".zip";
		if(path != null && path.contains("."))
		{
			String candidate = path.substring(path.lastIndexOf('.'));
			if(candidate.length() <= 6 && candidate.matches("\\.[A-Za-z0-9]+"))
				extension = candidate;
		}else
		{
			Path cached = getDownloadTarget(context);
			if(cached != null)
			{
				String cachedName = cached.getFileName().toString();
				int dot = cachedName.lastIndexOf('.');
				if(dot > 0 && cachedName.length() - dot <= 6)
				{
					String candidate = cachedName.substring(dot);
					if(candidate.matches("\\.[A-Za-z0-9]+"))
						extension = candidate;
				}
			}
		}
		
		String fileName = (base + extension).replaceAll("[^A-Za-z0-9._-]", "_");
		if(fileName.length() > 64)
			fileName = fileName.substring(0, 64);
		
		if(!fileName.contains("."))
			fileName += ".zip";
		return fileName;
	}
	
	private static Path ensureUnique(Path target)
	{
		if(!Files.exists(target))
			return target;
		
		String fileName = target.getFileName().toString();
		String base = fileName;
		String ext = "";
		int dot = fileName.lastIndexOf('.');
		if(dot > 0)
		{
			base = fileName.substring(0, dot);
			ext = fileName.substring(dot);
		}
		
		for(int i = 1; i < 1000; i++)
		{
			Path candidate = target.getParent().resolve(base + "-" + i + ext);
			if(!Files.exists(candidate))
				return candidate;
		}
		
		return target.getParent()
			.resolve(base + "-" + System.currentTimeMillis() + ext);
	}
	
	private static void logAudit(String action, PackContext context,
		String extra)
	{
		if(!CONFIG.isAuditLogEnabled())
			return;
		
		try
		{
			Path logDir = WurstClient.INSTANCE.getWurstFolder().resolve("logs");
			Files.createDirectories(logDir);
			
			Path logFile = logDir.resolve("anti-fingerprint.log");
			StringBuilder line = new StringBuilder();
			line.append(Instant.now()).append(" [").append(action).append("] ");
			
			if(context != null)
			{
				line.append("host=").append(describeHost(context.host));
				
				if(context.host != null)
				{
					if(context.host.ip() != null
						&& !context.host.ip().isBlank())
						line.append(" ip=").append(context.host.ip());
					
					if(context.host.remote() != null
						&& !context.host.remote().isBlank())
						line.append(" remote=").append(context.host.remote());
				}
				
				if(context.packId != null)
					line.append(" packId=").append(context.packId);
				
				if(context.cacheKey != null && !context.cacheKey.isBlank())
					line.append(" cacheKey=").append(context.cacheKey);
				
				line.append(" url=")
					.append(context.url.isEmpty() ? "<none>" : context.url);
				line.append(" hash=")
					.append(context.hash.isEmpty() ? "<none>" : context.hash);
				line.append(" required=").append(context.required);
				if(!context.prompt.isBlank())
					line.append(" prompt=\"")
						.append(sanitizeMultiline(context.prompt)).append('"');
				
			}else
				line.append("host=<unknown>");
			
			if(extra != null && !extra.isBlank())
				line.append(' ').append(extra);
			
			line.append(System.lineSeparator());
			
			Files.writeString(logFile, line.toString(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			
		}catch(IOException e)
		{
			LOGGER.debug("Failed to write audit log entry.", e);
		}
	}
	
	private static String invokeString(Object target, String... candidates)
	{
		for(String name : candidates)
		{
			try
			{
				Method method = target.getClass().getMethod(name);
				method.setAccessible(true);
				Object result = method.invoke(target);
				if(result == null)
					continue;
				
				if(result instanceof Optional<?> optional)
				{
					if(optional.isPresent())
						return Objects.toString(optional.get(), "");
					continue;
				}
				
				return Objects.toString(result, "");
				
			}catch(ReflectiveOperationException e)
			{
				// Try next candidate
			}
		}
		
		return "";
	}
	
	private static UUID invokeUuid(Object target, String... candidates)
	{
		for(String name : candidates)
		{
			try
			{
				Method method = target.getClass().getMethod(name);
				method.setAccessible(true);
				Object result = method.invoke(target);
				if(result == null)
					continue;
				
				if(result instanceof Optional<?> optional)
				{
					if(optional.isPresent())
					{
						Object value = optional.get();
						if(value instanceof UUID uuid)
							return uuid;
						
						try
						{
							return UUID.fromString(value.toString());
						}catch(IllegalArgumentException ignored)
						{}
					}
					continue;
				}
				
				if(result instanceof UUID uuid)
					return uuid;
				
				return UUID.fromString(result.toString());
				
			}catch(ReflectiveOperationException | IllegalArgumentException e)
			{
				// Try next candidate
			}
		}
		
		return null;
	}
	
	private static Component invokeText(Object target, String... candidates)
	{
		for(String name : candidates)
		{
			try
			{
				Method method = target.getClass().getMethod(name);
				method.setAccessible(true);
				Object result = method.invoke(target);
				if(result == null)
					continue;
				
				if(result instanceof Optional<?> optional)
				{
					if(optional.isPresent())
					{
						Object value = optional.get();
						if(value instanceof Component text)
							return text;
						return Component.literal(Objects.toString(value, ""));
					}
					continue;
				}
				
				if(result instanceof Component text)
					return text;
				
				return Component.literal(Objects.toString(result, ""));
				
			}catch(ReflectiveOperationException e)
			{
				// Try next candidate
			}
		}
		
		return null;
	}
	
	private static boolean invokeBoolean(Object target, boolean fallback,
		String... candidates)
	{
		for(String name : candidates)
		{
			try
			{
				Method method = target.getClass().getMethod(name);
				method.setAccessible(true);
				Object result = method.invoke(target);
				if(result instanceof Boolean b)
					return b.booleanValue();
				
				if(result != null)
					return Boolean.parseBoolean(result.toString());
				
			}catch(ReflectiveOperationException e)
			{
				// Try next
			}
		}
		
		return fallback;
	}
	
	private static HostInfo parseHost(String url)
	{
		if(url == null || url.isBlank())
			return HostInfo.unknown(url);
		
		try
		{
			URI uri = new URI(url);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			int port = uri.getPort();
			
			if(host == null)
			{
				if("file".equalsIgnoreCase(scheme))
					return HostInfo.local("file", url);
				
				return HostInfo.unknown(url);
			}
			
			String hostLower = host.toLowerCase(Locale.ROOT);
			InetAddress resolved = null;
			try
			{
				resolved = InetAddress.getByName(hostLower);
			}catch(UnknownHostException e)
			{
				LOGGER.debug("Failed to resolve host {}", hostLower, e);
			}
			
			boolean local = isLikelyLocal(hostLower, scheme, port, resolved);
			String ip = resolved == null ? "" : resolved.getHostAddress();
			return HostInfo.create(hostLower, port, scheme, local, url, ip);
			
		}catch(URISyntaxException e)
		{
			LOGGER.debug("Unable to parse resource pack URL {}", url, e);
			return HostInfo.unknown(url);
		}
	}
	
	private static boolean isLikelyLocal(String host, String scheme, int port,
		InetAddress resolved)
	{
		if(host == null || host.isEmpty())
			return true;
		
		if("file".equalsIgnoreCase(scheme))
			return true;
		
		if(host.equals("localhost") || host.equals("0.0.0.0")
			|| host.equals("127.0.0.1") || host.equals("::1"))
			return true;
		
		if(host.endsWith(".local") || host.endsWith(".lan"))
			return true;
		
		if(isPrivateIpv4(host))
			return true;
		
		if(resolved != null && (resolved.isAnyLocalAddress()
			|| resolved.isLoopbackAddress() || resolved.isLinkLocalAddress()
			|| resolved.isSiteLocalAddress()))
			return true;
		
		return false;
	}
	
	private static boolean isPrivateIpv4(String host)
	{
		if(!IPV4_PATTERN.matcher(host).matches())
			return false;
		
		String[] parts = host.split("\\.");
		int p0 = Integer.parseInt(parts[0]);
		int p1 = Integer.parseInt(parts[1]);
		
		if(p0 == 10)
			return true;
		
		if(p0 == 127)
			return true;
		
		if(p0 == 192 && p1 == 168)
			return true;
		
		if(p0 == 172 && p1 >= 16 && p1 <= 31)
			return true;
		
		return false;
	}
	
	private static PackContext buildContext(
		ClientboundResourcePackPushPacket packet)
	{
		String url = invokeString(packet, "getUrl", "getURL", "url");
		String hash = invokeString(packet, "getHash", "hash", "getHashValue");
		
		HostInfo hostInfo = parseHost(url);
		InetSocketAddress remoteAddress = getRemoteAddress();
		hostInfo = hostInfo.withFallback(remoteAddress);
		boolean required =
			invokeBoolean(packet, false, "isRequired", "isMandatory");
		
		UUID packId = invokeUuid(packet, "getId", "id");
		Component promptText = invokeText(packet, "getPrompt", "prompt");
		String prompt =
			promptText == null ? "" : sanitizeMultiline(promptText.getString());
		
		String keyCandidate =
			packId != null ? packId.toString() : (hash.isEmpty() ? url : hash);
		if(hostInfo.canonical != null && !hostInfo.canonical.isEmpty())
			keyCandidate = hostInfo.canonical + "|" + keyCandidate;
		
		String cacheKey = keyCandidate == null || keyCandidate.isBlank() ? null
			: keyCandidate;
		
		return new PackContext(url == null ? "" : url, hash == null ? "" : hash,
			hostInfo, required, cacheKey, packId, prompt);
	}
	
	private record PackContext(String url, String hash, HostInfo host,
		boolean required, String cacheKey, UUID packId, String prompt)
	{
		static PackContext from(ClientboundResourcePackPushPacket packet)
		{
			try
			{
				return buildContext(packet);
			}catch(Exception e)
			{
				LOGGER.debug("Failed to build resource-pack context.", e);
				return empty();
			}
		}
		
		static PackContext empty()
		{
			return new PackContext("", "", HostInfo.unknown(""), false, null,
				null, "");
		}
	}
	
	private record HostInfo(String host, int port, String scheme, boolean local,
		String canonical, String original, String ip, String remote)
	{
		static HostInfo unknown(String original)
		{
			return new HostInfo("", -1, "", true, "",
				original == null ? "" : original, "", "");
		}
		
		static HostInfo local(String scheme, String original)
		{
			return new HostInfo("", -1, scheme, true, "",
				original == null ? "" : original, "", "");
		}
		
		static HostInfo create(String host, int port, String scheme,
			boolean local, String original, String ip)
		{
			String canonical = host == null ? "" : host;
			if(port > 0)
				canonical += ":" + port;
			return new HostInfo(host == null ? "" : host, port,
				scheme == null ? "" : scheme, local,
				canonical.toLowerCase(Locale.ROOT),
				original == null ? "" : original, ip == null ? "" : ip, "");
		}
		
		String displayName()
		{
			if(!canonical.isEmpty())
				return canonical;
			if(!host.isEmpty())
				return host;
			if(original != null && !original.isEmpty())
				return original;
			if(remote != null && !remote.isEmpty())
				return remote;
			return "<unknown>";
		}
		
		String canonicalOrFallback()
		{
			if(!canonical.isEmpty())
				return canonical;
			if(!host.isEmpty())
				return host;
			if(original != null && !original.isEmpty())
				return original;
			if(remote != null && !remote.isEmpty())
				return remote;
			return "<unknown>";
		}
		
		HostInfo withFallback(InetSocketAddress address)
		{
			if(address == null)
				return this;
			
			String fallbackHost = address.getHostString();
			String hostLower = fallbackHost == null ? ""
				: fallbackHost.toLowerCase(Locale.ROOT);
			InetAddress inet = address.getAddress();
			String fallbackIp = inet == null ? "" : inet.getHostAddress();
			boolean fallbackLocal = local;
			if(inet != null)
			{
				fallbackLocal = fallbackLocal || inet.isAnyLocalAddress()
					|| inet.isLoopbackAddress() || inet.isLinkLocalAddress()
					|| inet.isSiteLocalAddress();
			}
			
			String schemeValue = scheme.isEmpty() ? "tcp" : scheme;
			int portValue = port >= 0 ? port : address.getPort();
			
			String canonicalValue = canonical;
			if(canonicalValue.isEmpty() && !hostLower.isEmpty())
			{
				canonicalValue = hostLower;
				if(portValue > 0)
					canonicalValue += ":" + portValue;
			}
			
			String hostValue = host.isEmpty() ? hostLower : host;
			String originalValue = original.isEmpty() && fallbackHost != null
				? fallbackHost : original;
			String ipValue = ip.isEmpty() ? fallbackIp : ip;
			String remoteValue = address.toString();
			
			return new HostInfo(hostValue, portValue, schemeValue,
				fallbackLocal, canonicalValue, originalValue, ipValue,
				remoteValue);
		}
	}
	
	private static final class PackHistory
	{
		private final Deque<Long> timestamps = new ArrayDeque<>();
		private long lastAlert;
	}
	
	private enum SandboxRequest
	{
		POLICY("SANDBOX_START", "SANDBOX_OK", "SANDBOX_FAIL",
			"Sandbox download", "Fetching remote pack.",
			"Sandboxed resource pack", "Stored copy: ", ToastLevel.INFO,
			ToastLevel.WARN, "Sandbox download failed"),
		PROMPT("PROMPT_SNAPSHOT_START", "PROMPT_SNAPSHOT_OK",
			"PROMPT_SNAPSHOT_FAIL", "Prompt inspection copy",
			"Downloading pack snapshot.", "Prompt inspection copy ready",
			"Saved copy: ", ToastLevel.INFO, ToastLevel.INFO,
			"Prompt inspection copy failed");
		
		private final String auditStart;
		private final String auditOk;
		private final String auditFail;
		private final String startTitle;
		private final String startBody;
		private final String successTitle;
		private final String successBodyPrefix;
		private final ToastLevel startLevel;
		private final ToastLevel successLevel;
		private final String failTitle;
		
		SandboxRequest(String auditStart, String auditOk, String auditFail,
			String startTitle, String startBody, String successTitle,
			String successBodyPrefix, ToastLevel startLevel,
			ToastLevel successLevel, String failTitle)
		{
			this.auditStart = auditStart;
			this.auditOk = auditOk;
			this.auditFail = auditFail;
			this.startTitle = startTitle;
			this.startBody = startBody;
			this.successTitle = successTitle;
			this.successBodyPrefix = successBodyPrefix;
			this.startLevel = startLevel;
			this.successLevel = successLevel;
			this.failTitle = failTitle;
		}
	}
	
	public static enum Decision
	{
		ALLOW,
		BLOCK,
		SANDBOX
	}
	
	public static record PolicyResult(Decision decision, String reason,
		PackContext context)
	{}
	
	private record ToastPayload(SystemToast.SystemToastId type, Component title,
		Component description)
	{}
}
