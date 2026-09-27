package dk.manyfold.website.health;

import java.util.Collections;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.jboss.logging.Logger;

/** Client for retrieving Kubernetes cluster health information. */
@ApplicationScoped
public class KubernetesHealthClient {

	private static final Logger LOG = Logger.getLogger(KubernetesHealthClient.class);

	private final KubernetesClient kubernetesClient;

	/** Represents the health status of a Kubernetes node. */
	public record NodeHealth(String name, boolean ready, String kubeletVersion) {
	}

	/** Represents the health status of a Kubernetes pod. */
	public record PodHealth(String namespace, String name, String phase, int restarts) {
	}

	/** Represents the overall health of the Kubernetes cluster. */
	public record ClusterHealth(
			int totalNodes,
			int readyNodes,
			int totalPods,
			int runningPods,
			int pendingPods,
			int failedPods,
			List<NodeHealth> nodes) {
	}

	@Inject
	public KubernetesHealthClient(KubernetesClient kubernetesClient) {
		this.kubernetesClient = kubernetesClient;
	}

	/**
	 * Namespaces whose pods do not count towards cluster health. Empty since the
	 * Tekton build namespace was retired (2026-09-27); a namespace with
	 * short-lived, failing-by-design pods would go here.
	 */
	private static final List<String> EXCLUDED_NAMESPACES = List.of();

	/** Retrieves the overall health of the Kubernetes cluster. */
	public ClusterHealth getClusterHealth() {
		try {
			List<Node> nodes = kubernetesClient.nodes().list().getItems();
			List<Pod> pods = kubernetesClient.pods().inAnyNamespace().list().getItems()
					.stream()
					.filter(pod -> {
						String ns = pod.getMetadata() != null
								? pod.getMetadata().getNamespace()
								: "";
						return !EXCLUDED_NAMESPACES.contains(ns);
					})
					.toList();

			List<NodeHealth> nodeHealthList = nodes.stream().map(this::toNodeHealth).toList();

			int readyNodes = (int) nodeHealthList.stream().filter(NodeHealth::ready).count();

			int runningPods = (int) pods.stream().filter(pod -> hasPhase(pod, "Running")).count();

			int pendingPods = (int) pods.stream().filter(pod -> hasPhase(pod, "Pending")).count();

			int failedPods = (int) pods.stream().filter(pod -> hasPhase(pod, "Failed")).count();

			return new ClusterHealth(
					nodes.size(),
					readyNodes,
					pods.size(),
					runningPods,
					pendingPods,
					failedPods,
					nodeHealthList);
		} catch (Exception e) {
			LOG.warnf(e, "Failed to retrieve cluster health");
			return new ClusterHealth(0, 0, 0, 0, 0, 0, Collections.emptyList());
		}
	}

	/** Retrieves the health of all pods in a specific namespace. */
	public List<PodHealth> getNamespacePodHealth(String namespace) {
		try {
			List<Pod> pods = kubernetesClient.pods().inNamespace(namespace).list().getItems();
			return pods.stream().map(this::toPodHealth).toList();
		} catch (Exception e) {
			LOG.warnf(e, "Failed to retrieve pod health for namespace %s", namespace);
			return Collections.emptyList();
		}
	}

	/**
	 * Retrieves the health of pods matching a label selector in a namespace. Use
	 * this to scope a check to a specific workload (e.g. a Deployment) and exclude
	 * unrelated CronJob/Job pods in the same namespace.
	 */
	public List<PodHealth> getNamespacePodHealth(String namespace, String labelKey, String labelValue) {
		try {
			List<Pod> pods = kubernetesClient.pods().inNamespace(namespace)
					.withLabel(labelKey, labelValue).list().getItems();
			return pods.stream().map(this::toPodHealth).toList();
		} catch (Exception e) {
			LOG.warnf(e, "Failed to retrieve pod health for namespace %s with label %s=%s",
					namespace, labelKey, labelValue);
			return Collections.emptyList();
		}
	}

	private NodeHealth toNodeHealth(Node node) {
		String name = node.getMetadata() != null ? node.getMetadata().getName() : "unknown";
		String kubeletVersion = "unknown";
		if (node.getStatus() != null && node.getStatus().getNodeInfo() != null) {
			kubeletVersion = node.getStatus().getNodeInfo().getKubeletVersion();
		}

		boolean ready = false;
		if (node.getStatus() != null && node.getStatus().getConditions() != null) {
			ready = node.getStatus().getConditions().stream()
					.filter(condition -> "Ready".equals(condition.getType()))
					.findFirst()
					.map(NodeCondition::getStatus)
					.map("True"::equals)
					.orElse(false);
		}

		return new NodeHealth(name, ready, kubeletVersion);
	}

	private PodHealth toPodHealth(Pod pod) {
		String namespace = pod.getMetadata() != null ? pod.getMetadata().getNamespace() : "unknown";
		String name = pod.getMetadata() != null ? pod.getMetadata().getName() : "unknown";
		String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";

		int restarts = 0;
		if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
			restarts = pod.getStatus().getContainerStatuses().stream()
					.mapToInt(status -> {
						Integer count = status.getRestartCount();
						return count != null ? count : 0;
					})
					.sum();
		}

		return new PodHealth(namespace, name, phase, restarts);
	}

	private boolean hasPhase(Pod pod, String phase) {
		return pod.getStatus() != null && phase.equals(pod.getStatus().getPhase());
	}
}
