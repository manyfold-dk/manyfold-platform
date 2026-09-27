package dk.manyfold.website.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Checks the bearer token on machine-to-machine webhooks (Alertmanager,
 * Tekton).
 *
 * <p>
 * The webhooks drive auto-remediation and the Slack event stream, and the
 * homepage proxy forwards {@code /api} for any logged-in user, so a network
 * boundary alone is not enough (review finding W2). The token is shared only
 * with the senders. Without a configured token every webhook is refused: a
 * missing secret fails closed.
 */
@ApplicationScoped
public class WebhookAuthenticator {

	private static final String BEARER = "Bearer ";

	private final Optional<byte[]> expected;

	public WebhookAuthenticator(@ConfigProperty(name = "manyfold.webhook.token") Optional<String> token) {
		this.expected = token.filter(t -> !t.isBlank()).map(t -> t.getBytes(StandardCharsets.UTF_8));
	}

	/** True when the Authorization header carries the configured token. */
	public boolean isAuthorized(String authorizationHeader) {
		if (expected.isEmpty() || authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
			return false;
		}
		byte[] provided = authorizationHeader.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(provided, expected.get());
	}
}
