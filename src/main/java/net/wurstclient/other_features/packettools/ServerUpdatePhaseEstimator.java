/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Client-receive cadence of server time updates, never server tick phase. */
public final class ServerUpdatePhaseEstimator
{
	private final ArrayDeque<Long> arrivals = new ArrayDeque<>();
	
	public synchronized void reset()
	{
		arrivals.clear();
	}
	
	public synchronized void observe(long receiveNs)
	{
		if(!arrivals.isEmpty() && receiveNs <= arrivals.getLast())
			arrivals.clear();
		arrivals.addLast(receiveNs);
		while(arrivals.size() > 12)
			arrivals.removeFirst();
	}
	
	public synchronized Map<String, Object> snapshot(long now)
	{
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("phaseSource", "CLIENT_RECEIVE_CLIENTBOUND_SET_TIME_CADENCE");
		out.put("serverPhaseDirectlyObserved", false);
		out.put("periodicUpdateSampleCount", Math.max(0, arrivals.size() - 1));
		if(arrivals.size() < 6)
			return unavailable(out, "INSUFFICIENT_CADENCE_SAMPLES");
		long latest = arrivals.getLast();
		long age = now - latest;
		if(age < 0 || age > 3_000_000_000L)
			return unavailable(out, "PERIODIC_UPDATE_CADENCE_STALE");
		ArrayList<Long> intervals = new ArrayList<>();
		Long previous = null;
		for(long time : arrivals)
		{
			if(previous != null)
			{
				long interval = time - previous;
				if(interval < 400_000_000L || interval > 2_000_000_000L)
					return unavailable(out, "NON_PERIODIC_OR_BURSTY_UPDATES");
				intervals.add(interval);
			}
			previous = time;
		}
		Collections.sort(intervals);
		long period = median(intervals);
		ArrayList<Long> deviations = new ArrayList<>();
		for(long interval : intervals)
			deviations.add(Math.abs(interval - period));
		Collections.sort(deviations);
		long jitter = median(deviations);
		if(jitter > Math.min(150_000_000L, period / 5))
			return unavailable(out, "CADENCE_JITTER_TOO_HIGH");
		long elapsed = Math.max(0, now - latest);
		long cycles = elapsed / period;
		long next = latest + (cycles + 1) * period;
		long phaseNs = elapsed % period;
		// Arrival-path jitter and small period drift grow with extrapolation.
		double arrivalJitterMs = jitter * 2.0 / 1e6;
		double extrapolationUncertaintyMs =
			cycles * Math.max(jitter, 2_000_000L) / 1e6;
		double uncertaintyMs =
			Math.max(15, arrivalJitterMs + extrapolationUncertaintyMs);
		out.put("phaseAvailable", true);
		out.put("phaseReason", "AVAILABLE_WITH_CLIENT_NETWORK_UNCERTAINTY");
		out.put("periodicUpdateCycleNs", period);
		out.put("periodicUpdatePhase", phaseNs / (double)period);
		out.put("estimatedNextPeriodicUpdateNs", next);
		out.put("latestPeriodicUpdateReceiveNs", latest);
		out.put("timingUncertaintyMs", uncertaintyMs);
		out.put("arrivalJitterUncertaintyMs", arrivalJitterMs);
		out.put("extrapolationUncertaintyMs", extrapolationUncertaintyMs);
		out.put("cadenceMedianAbsoluteDeviationMs", jitter / 1e6);
		out.put("phaseAgeMs", elapsed / 1e6);
		return out;
	}
	
	private static Map<String, Object> unavailable(Map<String, Object> out,
		String reason)
	{
		out.put("phaseAvailable", false);
		out.put("phaseReason", reason);
		return out;
	}
	
	private static long median(ArrayList<Long> sorted)
	{
		int n = sorted.size();
		return n % 2 == 0 ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2
			: sorted.get(n / 2);
	}
}
