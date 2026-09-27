package dk.manyfold.website.api.v1.model;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Batch of Web Vital metrics from the frontend. */
public record WebVitalsBatch(
		@NotEmpty @Size(max = 50) @Valid List<WebVitalEntry> entries,
		@Size(max = 512) String userAgent,
		@Size(max = 2048) String url) {
}
