package dk.manyfold.website.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CachedTest {

	/** Refreshes inline, so a test can assert on them without waiting. */
	private static final Executor INLINE = Runnable::run;

	@Test
	void computesOnceWhileTheValueIsFresh() {
		AtomicInteger calls = new AtomicInteger();
		Cached<Integer> cached = new Cached<>(
				"test", Duration.ofMinutes(5), calls::incrementAndGet, INLINE);

		for (int i = 0; i < 1000; i++) {
			assertThat(cached.get()).isEqualTo(1);
		}
		// This is the property the public status endpoints depend on: a flood of
		// requests must not become a flood of Prometheus queries.
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void refreshesOnceTheValueHasAgedOut() {
		AtomicInteger calls = new AtomicInteger();
		Cached<Integer> cached = new Cached<>(
				"test", Duration.ZERO, calls::incrementAndGet, INLINE);

		assertThat(cached.get()).isEqualTo(1);
		// A stale read is served the old value and starts the refresh, so nobody
		// waits on Prometheus; the next caller sees the new value.
		assertThat(cached.get()).isEqualTo(1);
		assertThat(cached.get()).isEqualTo(2);
	}

	@Test
	void keepsTheLastGoodValueWhenARefreshFails() {
		AtomicBoolean broken = new AtomicBoolean(false);
		Cached<String> cached = new Cached<>(
				"test",
				Duration.ZERO,
				() -> {
					if (broken.get()) {
						throw new IllegalStateException("Prometheus is unreachable");
					}
					return "good";
				},
				INLINE);

		assertThat(cached.get()).isEqualTo("good");

		broken.set(true);
		// A transient failure in a backend must not become an outage on the
		// status page: the older reading is still the best answer available.
		assertThat(cached.get()).isEqualTo("good");
		assertThat(cached.get()).isEqualTo("good");
	}

	@Test
	void propagatesAFailureWhenThereIsNothingToServeYet() {
		Cached<String> cached = new Cached<>(
				"test",
				Duration.ofMinutes(5),
				() -> {
					throw new IllegalStateException("Prometheus is unreachable");
				},
				INLINE);

		assertThatThrownBy(cached::get).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void reportsHowOldTheServedValueIs() {
		Cached<String> cached = new Cached<>("test", Duration.ofMinutes(5), () -> "v", INLINE);

		assertThat(cached.age()).isNull();
		cached.get();
		assertThat(cached.age()).isNotNull().isLessThan(Duration.ofSeconds(5));
	}

	@Test
	void refreshesOnlyOnceWhenManyCallersFindItStaleAtTheSameTime() throws Exception {
		// A refresh that is still in flight must not be started again by the next
		// caller, or a burst of traffic would produce a burst of queries.
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch refreshStarted = new CountDownLatch(1);

		Cached<Integer> cached = new Cached<>(
				"test",
				Duration.ZERO,
				() -> {
					int n = calls.incrementAndGet();
					if (n > 1) {
						refreshStarted.countDown();
						awaitQuietly(release);
					}
					return n;
				},
				CachedTest::runOnDaemonThread);

		try {
			assertThat(cached.get()).isEqualTo(1);

			// Starts the one refresh, which then parks inside the supplier.
			cached.get();
			assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();

			for (int i = 0; i < 100; i++) {
				assertThat(cached.get()).isEqualTo(1);
			}
			assertThat(calls.get()).isEqualTo(2);
		} finally {
			release.countDown();
		}
	}

	private static void runOnDaemonThread(Runnable runnable) {
		// Daemon, so a failed assertion can never leave the JVM unable to exit.
		Thread thread = new Thread(runnable, "cached-test-refresh");
		thread.setDaemon(true);
		thread.start();
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			// Bounded: the test must fail rather than hang if it is never released.
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
