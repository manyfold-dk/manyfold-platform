package dk.manyfold.website.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class WebhookAuthenticatorTest {

	private final WebhookAuthenticator auth = new WebhookAuthenticator(Optional.of("s3cret"));

	@Test
	void acceptsTheConfiguredBearerToken() {
		assertThat(auth.isAuthorized("Bearer s3cret")).isTrue();
	}

	@Test
	void refusesAWrongOrMissingToken() {
		assertThat(auth.isAuthorized("Bearer s3cret-not")).isFalse();
		assertThat(auth.isAuthorized("s3cret")).isFalse();
		assertThat(auth.isAuthorized(null)).isFalse();
	}

	@Test
	void refusesEverythingWhenNoTokenIsConfigured() {
		WebhookAuthenticator unconfigured = new WebhookAuthenticator(Optional.empty());
		assertThat(unconfigured.isAuthorized("Bearer ")).isFalse();
		assertThat(unconfigured.isAuthorized("Bearer anything")).isFalse();
		assertThat(new WebhookAuthenticator(Optional.of(" ")).isAuthorized("Bearer  ")).isFalse();
	}
}
