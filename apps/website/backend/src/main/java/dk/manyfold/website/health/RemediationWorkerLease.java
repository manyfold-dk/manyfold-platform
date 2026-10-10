package dk.manyfold.website.health;

import java.time.Duration;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;

import io.vertx.mutiny.redis.client.Response;
import org.jboss.logging.Logger;

/**
 * Which backend replica runs auto-remediation.
 *
 * <p>
 * Both replicas read the same active alerts from Alertmanager (issue #529), and
 * each keeps {@link RemediationService}'s cooldown and attempt limit in its own
 * memory. If both acted, one alert could get a restart from each replica within
 * the cooldown. The lease is a key in the Redis both replicas share: the
 * replica that finds it free takes it, renews it every cycle, and is the only
 * one that acts. When the holder stops, the key expires and the other replica
 * takes it over at its next cycle.
 *
 * <p>
 * A hand-over starts with an empty attempt history: the new holder can act on a
 * resource the old one restarted less than a cooldown ago, and counts the
 * hourly attempt limit from zero. That happens once per hand-over, where two
 * acting replicas could double every restart.
 */
@ApplicationScoped
public class RemediationWorkerLease {

	private static final Logger LOG = Logger.getLogger(RemediationWorkerLease.class);

	/** The key both replicas contend for; its value names the holder. */
	static final String KEY = "platform:auto-remediation:worker";

	/**
	 * How long the lease lasts unless renewed: more than two 60-second cycles, so
	 * one slow cycle does not hand it over, and short enough that a replica that
	 * stops is replaced within a few minutes.
	 */
	static final Duration TTL = Duration.ofSeconds(150);

	/**
	 * Renew the lease only while this replica holds it: the read and the renewal
	 * run as one step on the server. A lease that expired and was taken over is
	 * then never extended or overwritten by its former holder.
	 */
	static final String RENEW_SCRIPT = "if redis.call('GET', KEYS[1]) == ARGV[1] then"
			+ " return redis.call('EXPIRE', KEYS[1], ARGV[2]) else return 0 end";

	private final RedisDataSource redis;

	private final String self;

	private volatile boolean held;

	@Inject
	public RemediationWorkerLease(RedisDataSource redis) {
		this(redis, System.getenv().getOrDefault("HOSTNAME", "website-backend") + "/" + UUID.randomUUID());
	}

	/**
	 * @param self
	 *            this replica's name in the key: the pod name, and a random part
	 *            that tells two processes on one host apart
	 */
	RemediationWorkerLease(RedisDataSource redis, String self) {
		this.redis = redis;
		this.self = self;
	}

	/**
	 * Renew the lease if this replica holds it, or take it if it is free.
	 *
	 * <p>
	 * Each step is one atomic command, so two replicas never both hold the key. A
	 * replica that stalls longer than {@link #TTL} between this call and its
	 * restart can still act after the lease has passed on; the lease is checked
	 * again before every restart to keep that window to one restart.
	 *
	 * @return true when this replica holds the lease for the coming cycle; false
	 *         when another replica holds it, or when Redis gives no answer: without
	 *         an answer this replica cannot know the other is not acting
	 */
	public boolean acquire() {
		boolean holds;
		try {
			holds = renew() || take();
		} catch (Exception e) {
			LOG.warnf("Auto-remediation lease unavailable, this replica acts on nothing: %s",
					e.getMessage());
			holds = false;
		}
		if (holds != held) {
			held = holds;
			if (holds) {
				LOG.infof("This replica runs auto-remediation (%s)", self);
			} else {
				LOG.infof("This replica no longer runs auto-remediation (%s)", self);
			}
		}
		return holds;
	}

	/** Extend the lease if this replica holds it: {@link #RENEW_SCRIPT}. */
	private boolean renew() {
		Response renewed = redis.execute("EVAL", RENEW_SCRIPT, "1", KEY, self,
				String.valueOf(TTL.toSeconds()));
		return renewed != null && Integer.valueOf(1).equals(renewed.toInteger());
	}

	/** Take the lease if no replica holds it: {@code SET key self NX EX ttl}. */
	private boolean take() {
		return redis.value(String.class).setAndChanged(KEY, self, new SetArgs().nx().ex(TTL));
	}
}
