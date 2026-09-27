package dk.manyfold.website.api.v1.model;

/** Event representing a CI pipeline completion. */
public record PipelineEvent(
		String pipeline,
		String runName,
		String status,
		String gitRevision,
		String gitUrl,
		String imageTag,
		String environment,
		String duration) {
}
