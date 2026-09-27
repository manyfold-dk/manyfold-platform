package dk.manyfold.website.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.stream.StreamCommands;

import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.Alert;
import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.WebhookPayload;

import org.junit.jupiter.api.Test;

/**
 * The events an Alertmanager webhook puts on the platform:alerts stream, held
 * to the slack-bot's {@code AlertEvent}: {@code pod} and {@code deployment} are
 * optional, and the consumer posts an alert with its Restart Pod button only
 * when the event names a pod. Only alerts whose pod label names the pod in
 * trouble name one: others often carry the pod of the exporter behind the
 * metric.
 */
class RedisEventPublisherTest {

	private static final String CRASH_LOOPING = "KubePodCrashLooping";

	private static final String POD = "website-7d9f8b6c5-x2x4q";

	private static final String KSM_POD = "observability-kube-state-metrics-86448cbf45-dhdk2";

	/** The fields every alert event carries, in order. */
	private static final List<String> BASE_FIELDS = List.of("fingerprint", "status", "alertName", "severity",
			"namespace", "summary", "timestamp");

	/** Every xadd the publisher made, in call order. */
	private final List<Xadd> published = new ArrayList<>();

	private final AlertStoreService store = storePublishingTo(published);

	@Test
	void aCrashLoopingPodIsPublishedWithItsDeployment() {
		fire("crash1", crashLooping(POD, "website"));

		assertThat(published).singleElement().satisfies(event -> {
			assertThat(event.stream()).isEqualTo("platform:alerts");
			assertThat(event.fields())
					.containsEntry("status", "firing")
					.containsEntry("alertName", CRASH_LOOPING)
					.containsEntry("namespace", "website")
					.containsEntry("pod", POD)
					.containsEntry("deployment", "website");
		});
	}

	@Test
	void everySubjectPodAlertPublishesItsPod() {
		for (String alertName : RedisEventPublisher.SUBJECT_POD_ALERTS) {
			fire("fp-" + alertName, labels(alertName, "website", POD, null));
		}

		assertThat(published).hasSize(RedisEventPublisher.SUBJECT_POD_ALERTS.size())
				.allSatisfy(event -> assertThat(event.fields()).containsEntry("pod", POD));
	}

	/** Every pod the auto-remediator restarts is one the button may offer. */
	@Test
	void everyAutoRestartAlertIsASubjectPodAlert() {
		Set<String> autoRestart = AutoRemediationService.POD_RESTART_ALERTS;
		assertThat(RedisEventPublisher.SUBJECT_POD_ALERTS).containsAll(autoRestart);
	}

	/**
	 * An alert on one workload's pod names it, though nothing restarts it unasked.
	 */
	@Test
	void aBackendPodNotReadyAlertNamesItsPod() {
		fire("ready1", labels("BackendPodNotReady", "website", POD, null));

		assertThat(published).singleElement()
				.satisfies(event -> assertThat(event.fields()).containsEntry("pod", POD));
	}

	@Test
	void theResolutionNamesThePodToo() {
		fire("crash2", crashLooping(POD, null));
		store.processWebhook(payload(alert("resolved", "crash2", crashLooping(POD, null))));

		assertThat(published).hasSize(2);
		assertThat(published.get(1).fields())
				.containsEntry("status", "resolved")
				.containsEntry("pod", POD)
				.doesNotContainKey("deployment");
	}

	/**
	 * kube-state-metrics object metrics carry the scraping pod, not the workload's.
	 */
	@Test
	void theKubeStateMetricsPodOfADeploymentAlertIsNotPublished() {
		fire("replicas1", labels("KubeDeploymentReplicasMismatch", "website", KSM_POD, "website"));

		assertThat(published).singleElement().satisfies(event -> assertThat(event.fields())
				.doesNotContainKey("pod")
				.containsEntry("deployment", "website"));
	}

	/** Argo CD's application metrics carry the controller's pod. */
	@Test
	void theArgoCdControllerPodIsNotPublished() {
		String controller = "argocd-application-controller-0";
		fire("argo1", labels("ArgoCDAppDegraded", "argocd", controller, null));

		assertThat(published).hasSize(1);
		assertThat(published.get(0).fields().keySet()).containsExactlyElementsOf(BASE_FIELDS);
	}

	@Test
	void anAlertWithoutAPodPublishesNeitherField() {
		fire("nopod1", crashLooping(null, null));

		assertThat(published).hasSize(1);
		assertThat(published.get(0).fields().keySet()).containsExactlyElementsOf(BASE_FIELDS);
	}

	@Test
	void aBlankLabelIsLeftOut() {
		fire("blank1", crashLooping(" ", "website"));

		assertThat(published).singleElement().satisfies(event -> assertThat(event.fields())
				.doesNotContainKey("pod")
				.containsEntry("deployment", "website"));
	}

	@Test
	void anAlertWithoutLabelsPublishesNeitherField() {
		fire("nolabels1", null);

		assertThat(published).singleElement().satisfies(event -> assertThat(event.fields())
				.doesNotContainKeys("pod", "deployment"));
	}

	/** One xadd: the stream key and the fields written. */
	private record Xadd(String stream, Map<String, String> fields) {
	}

	private void fire(String fingerprint, Map<String, String> labels) {
		store.processWebhook(payload(alert("firing", fingerprint, labels)));
	}

	private static Map<String, String> crashLooping(String pod, String deployment) {
		return labels(CRASH_LOOPING, "website", pod, deployment);
	}

	private static Map<String, String> labels(String alertName, String namespace, String pod, String deployment) {
		Map<String, String> labels = new HashMap<>();
		labels.put("alertname", alertName);
		labels.put("severity", "warning");
		labels.put("namespace", namespace);
		if (pod != null) {
			labels.put("pod", pod);
		}
		if (deployment != null) {
			labels.put("deployment", deployment);
		}
		return labels;
	}

	private static Alert alert(String status, String fingerprint, Map<String, String> labels) {
		return new Alert(status, labels, Map.of("summary", "Something is wrong"),
				"2026-09-27T10:00:00Z", "0001-01-01T00:00:00Z", "", fingerprint);
	}

	private static WebhookPayload payload(Alert alert) {
		return new WebhookPayload("4", "test", 0, alert.status(), "backend-webhook", Map.of(), Map.of(),
				Map.of(), "http://alertmanager", List.of(alert));
	}

	/**
	 * An alert store whose publisher writes to a recording stream. JDK proxies
	 * stand in for the Redis client interfaces; any call other than
	 * {@code stream(...)} and {@code xadd(...)} fails.
	 */
	private static AlertStoreService storePublishingTo(List<Xadd> sink) {
		InvocationHandler recordXadd = (proxy, method, args) -> {
			if (!"xadd".equals(method.getName())) {
				throw new UnsupportedOperationException(method.getName());
			}
			sink.add(new Xadd((String) args[0], fieldsOf(args[args.length - 1])));
			return "1-0";
		};
		Object streams = proxy(StreamCommands.class, recordXadd);
		InvocationHandler serveStreams = (proxy, method, args) -> {
			if (!"stream".equals(method.getName())) {
				throw new UnsupportedOperationException(method.getName());
			}
			return streams;
		};

		AlertStoreService store = new AlertStoreService();
		store.redisEventPublisher = new RedisEventPublisher(proxy(RedisDataSource.class, serveStreams));
		return store;
	}

	private static <T> T proxy(Class<T> type, InvocationHandler handler) {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return type.cast(Proxy.newProxyInstance(loader, new Class<?>[]{type}, handler));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> fieldsOf(Object payload) {
		return (Map<String, String>) payload;
	}
}
