package dk.manyfold.website.auth;

import java.io.IOException;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkus.test.Mock;

@Mock
@Alternative
@Priority(1)
@ApplicationScoped
public class MockKeycloakLogoutService extends KeycloakLogoutService {

	public enum FailureMode {
		NONE, IO, INTERRUPTED
	}

	public static boolean configured = true;
	public static FailureMode failureMode = FailureMode.NONE;
	public static String lastEmail = "";
	public static String lastUsername = "";

	public static void reset() {
		configured = true;
		failureMode = FailureMode.NONE;
		lastEmail = "";
		lastUsername = "";
	}

	@Override
	public boolean isConfigured() {
		return configured;
	}

	@Override
	public void logoutUser(String email, String username) throws IOException, InterruptedException {
		lastEmail = email;
		lastUsername = username;

		if (failureMode == FailureMode.INTERRUPTED) {
			throw new InterruptedException("boom");
		}

		if (failureMode == FailureMode.IO) {
			throw new IOException("boom");
		}
	}
}
