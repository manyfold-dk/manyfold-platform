package dk.manyfold.website.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.stream.StreamCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;

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

	private static final String FIRST_START = "2026-09-27T10:00:00Z";

	private static final String SECOND_START = "2026-09-27T14:00:00Z";

	/** The fields every alert event carries, in order. */
	private static final List<String> BASE_FIELDS = List.of("fingerprint", "status", "alertName", "severity",
			"namespace", "summary", "timestamp");

	/** The Redis every replica in a test shares. */
	private final StandInRedis redis = new StandInRedis();

	/** Every xadd the publisher made, in call order. */
	private final List<Xadd> published = redis.stream;

	private final AlertStoreService store = redis.replica();

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
	 * Alertmanager delivers each webhook call to one of the backend's replicas, so
	 * a resolution can reach a replica that never saw the alert fire. That replica
	 * publishes it, built from the payload like a firing event (issue #518).
	 */
	@Test
	void aResolutionThisReplicaNeverSawFireIsPublished() {
		resolve("unseen1", crashLooping(POD, "website"), FIRST_START);

		assertThat(published).singleElement().satisfies(event -> {
			assertThat(event.stream()).isEqualTo("platform:alerts");
			assertThat(event.fields())
					.containsEntry("fingerprint", "unseen1")
					.containsEntry("status", "resolved")
					.containsEntry("alertName", CRASH_LOOPING)
					.containsEntry("severity", "warning")
					.containsEntry("namespace", "website")
					.containsEntry("pod", POD)
					.containsEntry("deployment", "website")
					.containsEntry("summary", "Something is wrong");
		});
		assertThat(store.getActiveAlerts()).isEmpty();
	}

	/** A resolution follows the firing event's rule: no exporter pod. */
	@Test
	void anUnseenResolutionLeavesOutTheExportersPod() {
		Map<String, String> replicasMismatch = labels("KubeDeploymentReplicasMismatch", "website", KSM_POD,
				"website");
		resolve("replicas2", replicasMismatch, FIRST_START);

		assertThat(published).singleElement().satisfies(event -> assertThat(event.fields())
				.containsEntry("status", "resolved")
				.doesNotContainKey("pod")
				.containsEntry("deployment", "website"));
	}

	@Test
	void anAlertFiredAndResolvedOnOneReplicaPublishesOneOfEach() {
		fire("once1", crashLooping(POD, null));
		resolve("once1", crashLooping(POD, null), FIRST_START);

		assertThat(statuses()).containsExactly("firing", "resolved");
		assertThat(store.getActiveAlerts()).isEmpty();
	}

	/** Two replicas, one webhook call each: the stream still gets one of each. */
	@Test
	void anAlertFiredOnOneReplicaAndResolvedOnTheOtherPublishesOneOfEach() {
		AlertStoreService otherReplica = redis.replica();

		fire("split1", crashLooping(POD, "website"));
		otherReplica.processWebhook(
				payload(alert("resolved", "split1", crashLooping(POD, "website"), FIRST_START)));

		assertThat(statuses()).containsExactly("firing", "resolved");
		assertThat(published.get(1).fields())
				.containsEntry("fingerprint", "split1")
				.containsEntry("pod", POD);
	}

	/**
	 * Alertmanager redelivers a resolution it could not confirm, or that rides
	 * along in the group's next notification, to either replica.
	 */
	@Test
	void aResolutionDeliveredToBothReplicasIsPublishedOnce() {
		AlertStoreService otherReplica = redis.replica();

		fire("both1", crashLooping(POD, null));
		resolve("both1", crashLooping(POD, null), FIRST_START);
		otherReplica.processWebhook(payload(alert("resolved", "both1", crashLooping(POD, null), FIRST_START)));

		assertThat(statuses()).containsExactly("firing", "resolved");
	}

	/** The record of a published resolution expires, so Redis does not fill up. */
	@Test
	void aPublishedResolutionIsRecordedPerEpisodeForADay() {
		resolve("record1", crashLooping(POD, null), FIRST_START);

		assertThat(redis.keys).containsOnlyKeys("platform:alerts:resolved:record1@" + FIRST_START);
		assertThat(redis.setArgs).singleElement()
				.satisfies(args -> assertThat(args).containsSubsequence("EX", "86400"));
	}

	/**
	 * A lost resolution leaves the alert open everywhere; a duplicate is cheaper.
	 */
	@Test
	void aResolutionIsPublishedWhenRedisCannotRecordIt() {
		redis.keysDown = true;

		resolve("norecord1", crashLooping(POD, null), FIRST_START);

		assertThat(statuses()).containsExactly("resolved");
	}

	/**
	 * A resolution is recorded only once it reached the stream, so a later delivery
	 * still publishes one that did not.
	 */
	@Test
	void aResolutionThatFailedToReachTheStreamIsPublishedOnItsNextDelivery() {
		redis.failingXadds = 1;
		resolve("lost1", crashLooping(POD, null), FIRST_START);

		assertThat(published).isEmpty();
		assertThat(redis.keys).isEmpty();

		AlertStoreService otherReplica = redis.replica();
		otherReplica.processWebhook(payload(alert("resolved", "lost1", crashLooping(POD, null), FIRST_START)));

		assertThat(statuses()).containsExactly("resolved");
	}

	/**
	 * A replica that published but could not record, or stopped before it did,
	 * leaves a second resolution behind, never none.
	 */
	@Test
	void aResolutionThatWasNotRecordedIsPublishedAgain() {
		redis.failingSets = 1;
		resolve("unrecorded1", crashLooping(POD, null), FIRST_START);

		assertThat(redis.keys).isEmpty();

		AlertStoreService otherReplica = redis.replica();
		otherReplica.processWebhook(
				payload(alert("resolved", "unrecorded1", crashLooping(POD, null), FIRST_START)));

		assertThat(statuses()).containsExactly("resolved", "resolved");
	}

	/** Alertmanager redelivers a notification it could not confirm. */
	@Test
	void aResolutionDeliveredTwiceIsPublishedOnce() {
		fire("twice1", crashLooping(POD, null));
		resolve("twice1", crashLooping(POD, null), FIRST_START);
		resolve("twice1", crashLooping(POD, null), FIRST_START);
		resolve("unseen2", crashLooping(POD, null), FIRST_START);
		resolve("unseen2", crashLooping(POD, null), FIRST_START);

		assertThat(published)
				.extracting(event -> event.fields().get("fingerprint") + " "
						+ event.fields().get("status"))
				.containsExactly("twice1 firing", "twice1 resolved", "unseen2 resolved");
	}

	/** An alert that fires again later is a new episode with its own resolution. */
	@Test
	void anAlertThatFiresAgainIsResolvedAgain() {
		store.processWebhook(payload(alert("firing", "again1", crashLooping(POD, null), FIRST_START)));
		resolve("again1", crashLooping(POD, null), FIRST_START);
		store.processWebhook(payload(alert("firing", "again1", crashLooping(POD, null), SECOND_START)));
		resolve("again1", crashLooping(POD, null), SECOND_START);

		assertThat(statuses()).containsExactly("firing", "resolved", "firing", "resolved");
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

	private void resolve(String fingerprint, Map<String, String> labels, String startsAt) {
		store.processWebhook(payload(alert("resolved", fingerprint, labels, startsAt)));
	}

	private List<String> statuses() {
		return published.stream().map(event -> event.fields().get("status")).toList();
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
		return alert(status, fingerprint, labels, FIRST_START);
	}

	/** Alertmanager zeroes a firing alert's end; a resolved one carries it. */
	private static Alert alert(String status, String fingerprint, Map<String, String> labels, String startsAt) {
		String endsAt = "resolved".equals(status) ? "2026-09-27T16:00:00Z" : "0001-01-01T00:00:00Z";
		return new Alert(status, labels, Map.of("summary", "Something is wrong"), startsAt, endsAt, "",
				fingerprint);
	}

	private static WebhookPayload payload(Alert alert) {
		return new WebhookPayload("4", "test", 0, alert.status(), "backend-webhook", Map.of(), Map.of(),
				Map.of(), "http://alertmanager", List.of(alert));
	}

	/**
	 * A Redis that replicas share: a recording stream and a map of string keys. JDK
	 * proxies stand in for the client interfaces; any call other than {@code xadd},
	 * {@code get} and {@code set} fails.
	 */
	private static final class StandInRedis {

		/** Every xadd, in call order. */
		final List<Xadd> stream = new ArrayList<>();

		final Map<String, String> keys = new ConcurrentHashMap<>();

		/** The arguments of every SET, in call order. */
		final List<List<String>> setArgs = new ArrayList<>();

		/** The keys cannot be read or written; the stream still works. */
		boolean keysDown;

		/** How many of the next xadds fail. */
		int failingXadds;

		/** How many of the next sets fail. */
		int failingSets;

		private final InvocationHandler streamCommand = (proxy, method, args) -> {
			if (!"xadd".equals(method.getName())) {
				throw new UnsupportedOperationException(method.getName());
			}
			if (failingXadds > 0) {
				failingXadds--;
				throw new IllegalStateException("XADD failed");
			}
			stream.add(new Xadd((String) args[0], fieldsOf(args[args.length - 1])));
			return "1-0";
		};

		private final InvocationHandler valueCommand = (proxy, method, args) -> {
			if (keysDown) {
				throw new IllegalStateException("Redis is unreachable");
			}
			if ("get".equals(method.getName()) && args.length == 1) {
				return keys.get((String) args[0]);
			}
			if ("set".equals(method.getName()) && args.length == 3) {
				setArgs.add(((SetArgs) args[2]).toArgs().stream().map(String::valueOf).toList());
				if (failingSets > 0) {
					failingSets--;
					throw new IllegalStateException("SET failed");
				}
				keys.put((String) args[0], (String) args[1]);
				return null;
			}
			throw new UnsupportedOperationException(method.getName());
		};

		private final InvocationHandler dataSource = (proxy, method, args) -> switch (method.getName()) {
			case "stream" -> proxy(StreamCommands.class, streamCommand);
			case "value" -> proxy(ValueCommands.class, valueCommand);
			default -> throw new UnsupportedOperationException(method.getName());
		};

		/** An alert store on this Redis: one backend replica. */
		AlertStoreService replica() {
			AlertStoreService store = new AlertStoreService();
			store.redisEventPublisher = new RedisEventPublisher(proxy(RedisDataSource.class, dataSource));
			return store;
		}
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
