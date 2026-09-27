package dk.manyfold.website.api.v1.model;

import java.util.List;
import java.util.Map;

/** Models for Alertmanager webhook payloads. */
public class AlertmanagerWebhook {

	/** Alertmanager webhook payload structure. */
	public record WebhookPayload(
			String version,
			String groupKey,
			int truncatedAlerts,
			String status,
			String receiver,
			Map<String, String> groupLabels,
			Map<String, String> commonLabels,
			Map<String, String> commonAnnotations,
			String externalURL,
			List<Alert> alerts) {
	}

	/** Individual alert from Alertmanager. */
	public record Alert(
			String status,
			Map<String, String> labels,
			Map<String, String> annotations,
			String startsAt,
			String endsAt,
			String generatorURL,
			String fingerprint) {

		/** Get the alert name from labels. */
		public String getName() {
			return labels != null ? labels.getOrDefault("alertname", "unknown") : "unknown";
		}

		/** Get the alert severity from labels. */
		public String getSeverity() {
			return labels != null ? labels.getOrDefault("severity", "warning") : "warning";
		}

		/** Get the namespace from labels. */
		public String getNamespace() {
			return labels != null ? labels.getOrDefault("namespace", "") : "";
		}

		/** Get the summary from annotations. */
		public String getSummary() {
			return annotations != null ? annotations.getOrDefault("summary", "") : "";
		}

		/** Get the description from annotations. */
		public String getDescription() {
			return annotations != null ? annotations.getOrDefault("description", "") : "";
		}
	}
}
