package dk.manyfold.website.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class KeycloakLogoutService {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final HttpClient httpClient;

	@Inject
	ObjectMapper objectMapper;

	@ConfigProperty(name = "manyfold.keycloak.base-url")
	Optional<String> baseUrl;

	@ConfigProperty(name = "manyfold.keycloak.realm")
	Optional<String> realm;

	@ConfigProperty(name = "manyfold.keycloak.logout-client-id")
	Optional<String> clientId;

	@ConfigProperty(name = "manyfold.keycloak.logout-client-secret")
	Optional<String> clientSecret;

	public KeycloakLogoutService() {
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	public boolean isConfigured() {
		return baseUrl.filter(value -> !value.isBlank()).isPresent()
				&& realm.filter(value -> !value.isBlank()).isPresent()
				&& clientId.filter(value -> !value.isBlank()).isPresent()
				&& clientSecret.filter(value -> !value.isBlank()).isPresent();
	}

	public void logoutUser(String email, String username) throws IOException, InterruptedException {
		String resolvedBaseUrl = required(baseUrl, "manyfold.keycloak.base-url");
		String resolvedRealm = required(realm, "manyfold.keycloak.realm");
		String accessToken = fetchAccessToken();
		String userId = findUserId(accessToken, resolvedBaseUrl, resolvedRealm, email, username);

		if (userId == null || userId.isBlank()) {
			throw new IOException("Unable to resolve Keycloak user for logout");
		}

		String logoutUri = resolvedBaseUrl
				+ "/admin/realms/" + encodePath(resolvedRealm)
				+ "/users/" + encodePath(userId)
				+ "/logout";

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(logoutUri))
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + accessToken)
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		int status = response.statusCode();
		if (status != 204 && status != 200) {
			throw new IOException("Keycloak logout failed with status " + status);
		}
	}

	private String fetchAccessToken() throws IOException, InterruptedException {
		String resolvedBaseUrl = required(baseUrl, "manyfold.keycloak.base-url");
		String resolvedRealm = required(realm, "manyfold.keycloak.realm");
		String resolvedClientId = required(clientId, "manyfold.keycloak.logout-client-id");
		String resolvedClientSecret = required(clientSecret, "manyfold.keycloak.logout-client-secret");
		String form = "grant_type=client_credentials"
				+ "&client_id=" + encode(resolvedClientId)
				+ "&client_secret=" + encode(resolvedClientSecret);

		String tokenUri = resolvedBaseUrl
				+ "/realms/" + encodePath(resolvedRealm)
				+ "/protocol/openid-connect/token";

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(tokenUri))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Failed to obtain Keycloak admin token: " + response.statusCode());
		}

		JsonNode body = objectMapper.readTree(response.body());
		String accessToken = textValue(body, "access_token");
		if (accessToken == null || accessToken.isBlank()) {
			throw new IOException("Keycloak token response did not include an access_token");
		}

		return accessToken;
	}

	private String findUserId(
			String accessToken,
			String resolvedBaseUrl,
			String resolvedRealm,
			String email,
			String username)
			throws IOException, InterruptedException {
		if (email != null && !email.isBlank()) {
			String userId = findUserIdByField(accessToken, resolvedBaseUrl, resolvedRealm, "email", email);
			if (userId != null) {
				return userId;
			}
		}

		if (username != null && !username.isBlank()) {
			return findUserIdByField(accessToken, resolvedBaseUrl, resolvedRealm, "username", username);
		}

		return null;
	}

	private String findUserIdByField(
			String accessToken,
			String resolvedBaseUrl,
			String resolvedRealm,
			String field,
			String value)
			throws IOException, InterruptedException {
		String query = field + "=" + encode(value) + "&exact=true";
		String usersUri = resolvedBaseUrl
				+ "/admin/realms/" + encodePath(resolvedRealm)
				+ "/users?" + query;
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(usersUri))
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException(
					"Failed to query Keycloak users by " + field + ": " + response.statusCode());
		}

		JsonNode users = objectMapper.readTree(response.body());
		if (!users.isArray()) {
			return null;
		}

		Iterator<JsonNode> iterator = users.elements();
		while (iterator.hasNext()) {
			JsonNode user = iterator.next();
			String candidate = textValue(user, field);
			if (value.equals(candidate)) {
				return textValue(user, "id");
			}
		}

		return null;
	}

	private static String textValue(JsonNode node, String fieldName) {
		JsonNode value = node.get(fieldName);
		return value == null || value.isNull() ? null : value.asText();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String encodePath(String value) {
		return value.replace(" ", "%20");
	}

	private static String required(Optional<String> value, String key) {
		return value.filter(candidate -> !candidate.isBlank())
				.orElseThrow(() -> new IllegalStateException("Missing config value for " + key));
	}
}
