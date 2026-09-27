package dk.manyfold.website.api.v1.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Single Web Vital metric entry from the frontend. */
public record WebVitalEntry(
		@NotBlank @Pattern(regexp = "^(CLS|INP|LCP|FCP|TTFB)$") String name,
		@NotNull Double value,
		@NotBlank @Pattern(regexp = "^(good|needs-improvement|poor)$") String rating,
		@NotNull Double delta,
		@NotBlank @Size(max = 64) String id,
		@Size(max = 32) String navigationType,
		@NotBlank @Size(max = 256) String route,
		@NotNull @Positive Long timestamp) {
}
