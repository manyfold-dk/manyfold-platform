package dk.manyfold.website.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import dk.manyfold.website.api.v1.model.WebVitalEntry;

/** In-memory cache for recent Web Vitals entries for demo/status visibility. */
@ApplicationScoped
public class WebVitalsStore {

	private static final int MAX_ENTRIES = 200;
	private final Deque<WebVitalEntry> entries = new ArrayDeque<>();

	public synchronized void add(WebVitalEntry entry) {
		entries.addLast(entry);
		while (entries.size() > MAX_ENTRIES) {
			entries.removeFirst();
		}
	}

	public synchronized List<WebVitalEntry> getRecent(int limit) {
		int safeLimit = Math.max(1, Math.min(limit, MAX_ENTRIES));
		List<WebVitalEntry> sample = new ArrayList<>();
		var iterator = entries.descendingIterator();
		while (iterator.hasNext() && sample.size() < safeLimit) {
			sample.add(iterator.next());
		}
		return sample;
	}

	public synchronized void clear() {
		entries.clear();
	}
}
