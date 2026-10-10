package dk.manyfold.website.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * One backend replica at a time runs auto-remediation: the one holding the
 * lease in the Redis both share. The holder keeps it by renewing it every
 * cycle; the other takes it over only once it has expired.
 */
class RemediationWorkerLeaseTest {

	private static final Duration CYCLE = Duration.ofSeconds(60);

	private final StandInKeys redis = new StandInKeys();

	private final RemediationWorkerLease first = new RemediationWorkerLease(redis.dataSource(), "backend-a/1");

	private final RemediationWorkerLease second = new RemediationWorkerLease(redis.dataSource(), "backend-b/2");

	@Test
	void theFirstReplicaToAskTakesAFreeLease() {
		assertThat(first.acquire()).isTrue();
		assertThat(second.acquire()).isFalse();
		assertThat(redis.get(RemediationWorkerLease.KEY)).isEqualTo("backend-a/1");
	}

	/** Renewed every cycle, the lease outlives its TTL many times over. */
	@Test
	void theHolderKeepsTheLeaseForAsLongAsItRenewsIt() {
		assertThat(first.acquire()).isTrue();
		for (int cycle = 0; cycle < 10; cycle++) {
			redis.advance(CYCLE);
			assertThat(second.acquire()).isFalse();
			assertThat(first.acquire()).isTrue();
		}
	}

	/** One late cycle does not hand the lease over. */
	@Test
	void oneMissedCycleDoesNotHandTheLeaseOver() {
		assertThat(first.acquire()).isTrue();
		redis.advance(CYCLE.multipliedBy(2));

		assertThat(second.acquire()).isFalse();
		assertThat(first.acquire()).isTrue();
	}

	/** A holder that stopped renewing is replaced once its lease expires. */
	@Test
	void theOtherReplicaTakesOverOnceTheLeaseExpires() {
		assertThat(first.acquire()).isTrue();
		redis.advance(RemediationWorkerLease.TTL);

		assertThat(second.acquire()).isTrue();
		assertThat(first.acquire()).isFalse();
	}

	/**
	 * A former holder neither extends nor overwrites the lease of the replica that
	 * took it over: its renewal compares and extends in one step.
	 */
	@Test
	void aFormerHolderNeverRenewsTheNewHoldersLease() {
		assertThat(first.acquire()).isTrue();
		redis.advance(RemediationWorkerLease.TTL);
		redis.put(RemediationWorkerLease.KEY, "backend-b/2", Duration.ofSeconds(10));

		assertThat(first.acquire()).isFalse();
		assertThat(redis.get(RemediationWorkerLease.KEY)).isEqualTo("backend-b/2");
		redis.advance(Duration.ofSeconds(10));
		assertThat(redis.get(RemediationWorkerLease.KEY)).isNull();
	}

	/** Without Redis a replica cannot know the other is not acting. */
	@Test
	void withoutAnAnswerFromRedisNoReplicaHoldsTheLease() {
		assertThat(first.acquire()).isTrue();
		redis.goDown();

		assertThat(first.acquire()).isFalse();
		assertThat(second.acquire()).isFalse();
	}
}
