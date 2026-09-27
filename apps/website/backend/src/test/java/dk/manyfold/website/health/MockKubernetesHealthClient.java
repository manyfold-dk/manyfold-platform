package dk.manyfold.website.health;

import java.util.List;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkus.test.Mock;

/** Mock Kubernetes health client for testing. */
@Mock
@Alternative
@Priority(1)
@ApplicationScoped
public class MockKubernetesHealthClient extends KubernetesHealthClient {

	public MockKubernetesHealthClient() {
		super(null);
	}

	@Override
	public ClusterHealth getClusterHealth() {
		return new ClusterHealth(
				3,
				3,
				10,
				8,
				1,
				1,
				List.of(
						new NodeHealth("node-1", true, "v1.29.0"),
						new NodeHealth("node-2", true, "v1.29.0"),
						new NodeHealth("node-3", true, "v1.29.0")));
	}

	@Override
	public List<PodHealth> getNamespacePodHealth(String namespace) {
		return List.of(
				new PodHealth(namespace, "pod-1", "Running", 0),
				new PodHealth(namespace, "pod-2", "Running", 1));
	}

	@Override
	public List<PodHealth> getNamespacePodHealth(String namespace, String labelKey, String labelValue) {
		return List.of(new PodHealth(namespace, labelValue + "-pod-1", "Running", 0));
	}
}
