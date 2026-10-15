/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;

/**
 * One bounded worker and one immutable file owner for one forensic activation.
 */
public final class ForensicSessionWriter
{
	private final ItemInventoryForensics model;
	private final Path directory;
	private final Path firstFile;
	private final Map<String, Object> settings;
	private final PacketTimingTracker timing;
	private final ArrayDeque<Job> jobs = new ArrayDeque<>();
	private final List<String> files =
		new java.util.concurrent.CopyOnWriteArrayList<>();
	private final int capacity;
	private final long byteCapacity;
	private final Thread worker;
	private long queuedBytes;
	private long highWater;
	private long dropped;
	private long overflowEpisodes;
	private long lossSinceReport;
	private long firstLossNs;
	private long lastLossNs;
	private final Map<String, Long> lostTypes = new LinkedHashMap<>();
	private boolean overflowActive;
	private boolean closing;
	private String endReason = "disabled";
	private volatile boolean finished;
	private volatile long ioErrors;
	private volatile long failedWrites;
	private volatile long diagnosticErrors;
	private volatile boolean drainCompleted, outputClosed;
	private BufferedWriter output;
	private volatile long writtenRecords;
	private final long startedNs = System.nanoTime();
	private final String startedAt = Instant.now().toString();
	private final String startThread = Thread.currentThread().getName();
	private long endedNs;
	private String endedAt;
	
	public ForensicSessionWriter(Path directory, ItemInventoryForensics model,
		Map<String, Object> settings, PacketTimingTracker timing)
	{
		this(directory, model, settings, timing, 2048, 64L * 1024 * 1024);
	}
	
	public ForensicSessionWriter(Path directory, ItemInventoryForensics model,
		Map<String, Object> settings, PacketTimingTracker timing, int capacity,
		long byteCapacity)
	{
		this.directory = directory;
		this.model = model;
		this.settings = Map.copyOf(settings);
		this.timing = timing;
		this.capacity = capacity;
		this.byteCapacity = byteCapacity;
		firstFile =
			directory.resolve("session-" + model.getSessionId() + ".jsonl");
		model.setEventSink(this::writeLine);
		model.setCaptureHealth(this::health);
		worker = new Thread(this::run,
			"PacketTools-forensics-" + model.getSessionId());
		worker.setDaemon(true);
		worker.start();
	}
	
	public synchronized boolean submit(String type, long observedNs, long bytes,
		Runnable operation)
	{
		if(closing)
			return false;
		bytes = Math.max(1, bytes);
		if(jobs.size() >= capacity || queuedBytes + bytes > byteCapacity)
		{
			if(!overflowActive)
				overflowEpisodes++;
			overflowActive = true;
			if(lossSinceReport++ == 0)
				firstLossNs = observedNs;
			lastLossNs = observedNs;
			dropped++;
			lostTypes.merge(type, 1L, Long::sum);
			return false;
		}
		overflowActive = false;
		jobs.addLast(new Job(bytes, operation));
		queuedBytes += bytes;
		highWater = Math.max(highWater, jobs.size());
		notifyAll();
		return true;
	}
	
	public synchronized void finish(String reason)
	{
		if(closing)
			return;
		closing = true;
		endedNs = System.nanoTime();
		endedAt = Instant.now().toString();
		endReason = reason;
		notifyAll();
	}
	
	public boolean await(long milliseconds) throws InterruptedException
	{
		worker.join(milliseconds);
		return finished;
	}
	
	public static boolean effectiveEnabled(boolean main, boolean forensic)
	{
		return main && forensic;
	}
	
	public boolean isFinished()
	{
		return finished;
	}
	
	public Path getFile()
	{
		return firstFile;
	}
	
	public void requestSummary()
	{
		submit("summary_export", System.nanoTime(), 1024, () -> {
			writeSummary();
		});
	}
	
	private void run()
	{
		long lastFlush = System.nanoTime();
		long lastLossReport = lastFlush;
		try
		{
			model.recordObservation(Map.of("eventType", "session_start",
				"captureSettings", settings, "monotonicNs", startedNs,
				"timestamp", startedAt, "thread", startThread));
			while(true)
			{
				Job job;
				synchronized(this)
				{
					if(jobs.isEmpty() && !closing)
						wait(200);
					job = jobs.pollFirst();
					if(job != null)
						queuedBytes -= job.bytes;
					else if(closing)
						break;
				}
				if(job != null)
					try
					{
						job.operation.run();
					}catch(RuntimeException e)
					{
						diagnosticErrors++;
						model.recordObservation(Map.of("eventType",
							"diagnostic_error", "error", e.toString(), "stack",
							java.util.Arrays.stream(e.getStackTrace()).limit(12)
								.map(Object::toString).toList(),
							"captureComplete", false));
						failedWrites++;
					}
				long now = System.nanoTime();
				if(now - lastLossReport >= TimeUnit.SECONDS.toNanos(1))
				{
					reportLoss();
					lastLossReport = now;
				}
				if(now - lastFlush >= TimeUnit.MILLISECONDS.toNanos(200))
				{
					flush();
					lastFlush = now;
				}
			}
			drainCompleted = true;
			reportLoss();
			model.recordObservation(Map.of("eventType", "session_end", "reason",
				endReason, "outstandingTransactionsMarkedInterrupted", true,
				"monotonicNs", endedNs, "timestamp", endedAt));
			flush();
			closeOutput();
			outputClosed = true;
			writeSummary();
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			failedWrites++;
		}finally
		{
			closeOutput();
			model.releaseSessionResources();
			finished = true;
		}
	}
	
	private void reportLoss()
	{
		Map<String, Object> event = new LinkedHashMap<>();
		synchronized(this)
		{
			if(lossSinceReport == 0)
				return;
			event.put("eventType", "diagnostic_overflow");
			event.put("droppedObservationJobs", lossSinceReport);
			event.put("lossUnit",
				"queued_observation_jobs; generated_record_count_unknown");
			event.put("firstDroppedMonotonicNs", firstLossNs);
			event.put("lastDroppedMonotonicNs", lastLossNs);
			event.put("affectedEventTypes", new LinkedHashMap<>(lostTypes));
			event.put("captureComplete", false);
			lossSinceReport = 0;
			lostTypes.clear();
		}
		model.recordObservation(event);
	}
	
	public synchronized Map<String, Object> health()
	{
		Map<String, Object> health = new LinkedHashMap<>();
		health.put("droppedObservationJobs", dropped);
		health.put("bufferOverflowEpisodes", overflowEpisodes);
		health.put("queueHighWaterMark", highWater);
		health.put("ioErrors", ioErrors);
		health.put("diagnosticErrors",
			diagnosticErrors + model.getDiagnosticErrorCount());
		health.put("failedDiagnosticRecords", failedWrites);
		health.put("writtenRecords", writtenRecords);
		health.put("writerDrained", drainCompleted);
		health.put("writerOutputClosed", outputClosed);
		health.put("writerCompletedSuccessfully",
			drainCompleted && outputClosed && ioErrors == 0);
		health.put("endedCleanly",
			closing && drainCompleted && outputClosed && ioErrors == 0);
		health.put("captureComplete",
			dropped == 0 && ioErrors == 0 && failedWrites == 0
				&& diagnosticErrors == 0
				&& model.getDiagnosticErrorCount() == 0);
		health.put("files", List.copyOf(files));
		return health;
	}
	
	private void writeLine(String line)
	{
		try
		{
			if(output == null)
			{
				Files.createDirectories(directory);
				output =
					Files.newBufferedWriter(firstFile, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				if(!files.contains(firstFile.getFileName().toString()))
					files.add(firstFile.getFileName().toString());
			}
			output.write(line);
			output.newLine();
			writtenRecords++;
		}catch(IOException e)
		{
			failedWrites++;
			reportIoError(e);
			closeOutput();
		}
	}
	
	private void writeSummary()
	{
		try
		{
			Path path =
				directory.resolve("session-" + model.getSessionId() + ".json");
			Files.createDirectories(directory);
			if(!files.contains(path.getFileName().toString()))
				files.add(path.getFileName().toString());
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("schemaVersion", 2);
			summary.put("sessionId", model.getSessionId());
			summary.put("startedAt", startedAt);
			summary.put("endedAt",
				endedAt == null ? Instant.now().toString() : endedAt);
			summary.put("flushCompletedAt", Instant.now().toString());
			summary.put("elapsedNs", Math.max(0,
				(endedNs == 0 ? System.nanoTime() : endedNs) - startedNs));
			summary.put("captureHealth", health());
			summary.put("packetTimings", timing.summary());
			summary.put("forensics", com.google.gson.JsonParser
				.parseString(model.buildSummary(true, settings)));
			String json = new GsonBuilder().disableHtmlEscaping()
				.setPrettyPrinting().create().toJson(summary);
			Files.writeString(path, json, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING);
		}catch(IOException e)
		{
			reportIoError(e);
		}
	}
	
	private void flush()
	{
		try
		{
			if(output != null)
				output.flush();
		}catch(IOException e)
		{
			reportIoError(e);
		}
	}
	
	private void closeOutput()
	{
		try
		{
			if(output != null)
				output.close();
		}catch(IOException e)
		{
			reportIoError(e);
		}finally
		{
			output = null;
		}
	}
	
	private void reportIoError(IOException error)
	{
		if(ioErrors++ == 0)
			LogUtils.getLogger().error(
				"Forensic session {} I/O failure; capture is incomplete: {}",
				model.getSessionId(), firstFile, error);
	}
	
	private record Job(long bytes, Runnable operation)
	{}
}
