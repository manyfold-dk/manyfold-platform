package dk.manyfold.website.health;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Alertmanager's {@code GET /api/v2/alerts} on loopback, answering what a test
 * sets. The alerts it builds have the shape Alertmanager v0.32 answers with.
 */
final class StandInAlertmanager implements AutoCloseable {

	static final String RECEIVER = "backend-webhook";

	static final String STARTS_AT = "2026-09-27T10:00:00.000Z";

	static final String UPDATED_AT = "2026-09-27T10:04:00.000Z";

	private static final ObjectMapper JSON = new ObjectMapper();

	private final HttpServer server;

	private volatile int status = 200;

	private volatile String body = "[]";

	private volatile URI lastRequest;

	private boolean closed;

	StandInAlertmanager() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/api/v2/alerts", exchange -> {
			lastRequest = exchange.getRequestURI();
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
	}

	/** A client of this stand-in, configured as the backend is. */
	AlertmanagerClient client() {
		AlertmanagerClient client = new AlertmanagerClient();
		client.alertmanagerUrl = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
		client.receiver = RECEIVER;
		return client;
	}

	void answer(int statusCode, String responseBody) {
		status = statusCode;
		body = responseBody;
	}

	/** Answer 200 with these alerts, each built by {@link #alert}. */
	void answerAlerts(String... alerts) {
		answer(200, "[" + String.join(",", alerts) + "]");
	}

	URI lastRequest() {
		return lastRequest;
	}

	/**
	 * Stop answering; a client then finds nothing listening. Safe to call twice.
	 */
	@Override
	public void close() {
		if (!closed) {
			closed = true;
			server.stop(0);
		}
	}

	/** A firing alert, neither silenced nor inhibited, routed to the webhook. */
	static String alert(String fingerprint, Map<String, String> labels) {
		return alert(fingerprint, labels, Status.ACTIVE, List.of(RECEIVER));
	}

	static String alert(String fingerprint, Map<String, String> labels, Status status, List<String> receivers) {
		Map<String, Object> alert = new LinkedHashMap<>();
		alert.put("annotations", Map.of(
				"summary", labels.getOrDefault("alertname", "") + " summary",
				"description", labels.getOrDefault("alertname", "") + " description"));
		alert.put("endsAt", "2026-09-27T10:08:00.000Z");
		alert.put("fingerprint", fingerprint);
		alert.put("receivers", receivers.stream().map(name -> Map.of("name", name)).toList());
		alert.put("startsAt", STARTS_AT);
		alert.put("status", status.json());
		alert.put("updatedAt", UPDATED_AT);
		alert.put("generatorURL", "http://prometheus.example/graph");
		alert.put("labels", labels);
		try {
			return JSON.writeValueAsString(alert);
		} catch (JsonProcessingException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** An alert's {@code status} object. */
	record Status(String state, List<String> silencedBy, List<String> inhibitedBy, List<String> mutedBy) {

		static final Status ACTIVE = new Status("active", List.of(), List.of(), List.of());

		static final Status SILENCED = new Status("suppressed",
				List.of("0b1d3f5e-silence"), List.of(), List.of());

		static final Status INHIBITED = new Status("suppressed",
				List.of(), List.of("fp-of-the-inhibitor"), List.of());

		Map<String, Object> json() {
			return Map.of("state", state,
					"silencedBy", silencedBy,
					"inhibitedBy", inhibitedBy,
					"mutedBy", mutedBy);
		}
	}
}
