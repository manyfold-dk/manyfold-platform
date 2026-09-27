package dk.manyfold.website.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The client against a stand-in Prometheus on loopback: what an answer, an
 * empty answer and no answer each turn into.
 */
class PrometheusHealthClientTest {

	private static final String NO_SERIES = "{\"status\":\"success\","
			+ "\"data\":{\"resultType\":\"vector\",\"result\":[]}}";

	private HttpServer server;
	private volatile int status;
	private volatile String body;
	private volatile String lastQuery;

	@BeforeEach
	void startPrometheus() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/api/v1/query", exchange -> {
			lastQuery = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
	}

	@AfterEach
	void stopPrometheus() {
		server.stop(0);
	}

	private PrometheusHealthClient client() {
		PrometheusHealthClient client = new PrometheusHealthClient();
		client.prometheusUrl = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
		return client;
	}

	private void answer(int statusCode, String responseBody) {
		status = statusCode;
		body = responseBody;
	}

	private static String oneSeries(String value) {
		return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":"
				+ "[{\"metric\":{},\"value\":[1790000000,\"" + value + "\"]}]}}";
	}

	@Test
	void readsTheValueOfTheFirstSeries() throws PrometheusQueryException {
		answer(200, oneSeries("42"));
		assertThat(client().queryInstant("vector(42)")).contains(42.0);
	}

	@Test
	void noSeriesIsAnEmptyAnswerNotAFailure() throws PrometheusQueryException {
		answer(200, NO_SERIES);
		assertThat(client().queryInstant("count(nothing)")).isEmpty();
	}

	@Test
	void anErrorStatusIsAFailure() {
		answer(400, "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}");
		assertThatThrownBy(() -> client().queryInstant("count("))
				.isInstanceOf(PrometheusQueryException.class)
				.hasMessageContaining("400")
				.hasMessageContaining("parse error");
	}

	@Test
	void aBodyThatIsNotJsonIsAFailure() {
		answer(502, "<html>bad gateway</html>");
		assertThatThrownBy(() -> client().queryInstant("vector(1)"))
				.isInstanceOf(PrometheusQueryException.class);
	}

	@Test
	void anEmptyBodyIsAFailure() {
		// A proxy in front of Prometheus can answer with no body at all. Jackson reads
		// that as a missing node, not null, so it must land here as a failed query
		// rather than as a NullPointerException that callers would report unhealthy.
		answer(200, "");
		assertThatThrownBy(() -> client().queryInstant("vector(1)"))
				.isInstanceOf(PrometheusQueryException.class);
		answer(502, "");
		assertThatThrownBy(() -> client().queryInstant("vector(1)"))
				.isInstanceOf(PrometheusQueryException.class);
	}

	@Test
	void aSuccessWithoutAResultIsAFailure() {
		answer(200, "{\"status\":\"success\",\"data\":{}}");
		assertThatThrownBy(() -> client().queryInstant("vector(1)"))
				.isInstanceOf(PrometheusQueryException.class);
	}

	@Test
	void anUnreadableSampleIsAFailure() {
		answer(200, oneSeries("not a number"));
		assertThatThrownBy(() -> client().queryInstant("vector(1)"))
				.isInstanceOf(PrometheusQueryException.class);
	}

	@Test
	void anUnreachablePrometheusIsAFailure() {
		PrometheusHealthClient client = client();
		server.stop(0);
		assertThatThrownBy(() -> client.queryInstant("vector(1)")).isInstanceOf(PrometheusQueryException.class);
	}

	@Test
	void aFailedCountIsAFailureNotZero() {
		answer(503, "{\"status\":\"error\",\"error\":\"unavailable\"}");
		assertThatThrownBy(() -> client().getArgoCDSyncedApps()).isInstanceOf(PrometheusQueryException.class);
	}

	@Test
	void noApplicationStuckOutOfSyncCountsZero() throws PrometheusQueryException {
		answer(200, NO_SERIES);
		assertThat(client().getArgoCDAppsOutOfSyncFor(15)).isZero();
	}

	@Test
	void countsTheApplicationsStuckOutOfSyncOverTheGivenWindow() throws PrometheusQueryException {
		answer(200, oneSeries("2"));
		assertThat(client().getArgoCDAppsOutOfSyncFor(20)).isEqualTo(2);
		// OutOfSync now, OutOfSync at the start of the window, and no other sync
		// status reported anywhere in it.
		assertThat(lastQuery)
				.contains("argocd_app_info{sync_status=\"OutOfSync\"})")
				.contains("argocd_app_info{sync_status=\"OutOfSync\"} offset 20m")
				.contains("unless on (exported_namespace, name)")
				.contains("max_over_time(argocd_app_info{sync_status!=\"OutOfSync\"}[20m])");
	}
}
