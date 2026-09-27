package dk.manyfold.website.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class RemediationCooldownTest {

	private static final String KEY = "website/deployment/website-backend";

	private final RemediationService service = new RemediationService(null);

	@Test
	void aSecondAttemptInsideTheCooldownIsBlocked() {
		assertThat(service.reserveAttempt(KEY)).isPresent();
		assertThat(service.reserveAttempt(KEY)).isEmpty();
	}

	@Test
	void concurrentRequestsReserveOneAttempt() throws Exception {
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<Future<Optional<Instant>>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return service.reserveAttempt(KEY);
				}));
			}
			start.countDown();
			int reserved = 0;
			for (Future<Optional<Instant>> result : results) {
				if (result.get().isPresent()) {
					reserved++;
				}
			}
			assertThat(reserved).isEqualTo(1);
			assertThat(service.getRecentRemediationCount()).isEqualTo(1);
		}
	}

	@Test
	void aReleasedAttemptDoesNotHoldTheCooldown() {
		Instant attempt = service.reserveAttempt(KEY).orElseThrow();
		service.releaseAttempt(KEY, attempt);
		assertThat(service.getRecentRemediationCount()).isZero();
		assertThat(service.reserveAttempt(KEY)).isPresent();
	}
}
