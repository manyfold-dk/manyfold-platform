package dk.manyfold.website.health;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;

import io.vertx.mutiny.redis.client.Response;
import io.vertx.redis.client.ResponseType;

/**
 * String keys as Redis keeps them, on a clock the test moves: {@code GET},
 * {@code SET} with {@code NX}, {@code XX} and {@code EX}, and the lease's
 * renewal script. Every replica in a test gets its {@link #dataSource()} from
 * the same instance, as every backend replica talks to the same Redis. The
 * commands themselves were checked against Redis 7.4 (see the pull request of
 * issue #529).
 */
final class StandInKeys {

	private record Entry(String value, Instant expiresAt) {
	}

	private final Map<String, Entry> keys = new HashMap<>();

	private Instant now = Instant.parse("2026-09-27T10:00:00Z");

	/** Redis cannot be reached. */
	private volatile boolean down;

	/** Let time pass: keys whose expiry falls in it are gone. */
	synchronized void advance(Duration duration) {
		now = now.plus(duration);
	}

	void goDown() {
		down = true;
	}

	/** The key's value, or null when it is absent or expired. */
	synchronized String get(String key) {
		Entry entry = keys.get(key);
		if (entry == null || (entry.expiresAt() != null && !now.isBefore(entry.expiresAt()))) {
			keys.remove(key);
			return null;
		}
		return entry.value();
	}

	/** Write the key directly, as another client would. */
	synchronized void put(String key, String value, Duration ttl) {
		keys.put(key, new Entry(value, now.plus(ttl)));
	}

	/** @return true when the key was set */
	private synchronized boolean set(String key, String value, List<String> args) {
		boolean exists = get(key) != null;
		if (args.contains("NX") && exists || args.contains("XX") && !exists) {
			return false;
		}
		int ex = args.indexOf("EX");
		Instant expiresAt = ex >= 0 ? now.plusSeconds(Long.parseLong(args.get(ex + 1))) : null;
		keys.put(key, new Entry(value, expiresAt));
		return true;
	}

	/**
	 * {@link RemediationWorkerLease#RENEW_SCRIPT}: GET, compare, EXPIRE, in one
	 * step.
	 */
	private synchronized long renew(String key, String value, long seconds) {
		if (!value.equals(get(key))) {
			return 0;
		}
		keys.put(key, new Entry(value, now.plusSeconds(seconds)));
		return 1;
	}

	RedisDataSource dataSource() {
		InvocationHandler values = (proxy, method, args) -> {
			checkUp();
			switch (method.getName()) {
				case "get" :
					return get((String) args[0]);
				case "set" :
					set((String) args[0], (String) args[1], argsOf(args[2]));
					return null;
				case "setAndChanged" :
					return set((String) args[0], (String) args[1], argsOf(args[2]));
				default :
					throw new UnsupportedOperationException(method.getName());
			}
		};
		InvocationHandler dataSource = (proxy, method, args) -> {
			switch (method.getName()) {
				case "value" :
					return proxy(ValueCommands.class, values);
				case "execute" :
					checkUp();
					return execute((String) args[0], (String[]) args[1]);
				default :
					throw new UnsupportedOperationException(method.getName());
			}
		};
		return proxy(RedisDataSource.class, dataSource);
	}

	private void checkUp() {
		if (down) {
			throw new IllegalStateException("Redis is unreachable");
		}
	}

	private Response execute(String command, String... args) {
		boolean isRenewal = "EVAL".equals(command) && args.length == 5
				&& RemediationWorkerLease.RENEW_SCRIPT.equals(args[0]) && "1".equals(args[1]);
		if (!isRenewal) {
			throw new UnsupportedOperationException(command);
		}
		return integer(renew(args[2], args[3], Long.parseLong(args[4])));
	}

	private static List<String> argsOf(Object setArgs) {
		return ((SetArgs) setArgs).toArgs().stream().map(String::valueOf).toList();
	}

	/** An integer reply, as the Redis client hands it over. */
	private static Response integer(long value) {
		InvocationHandler reply = (proxy, method, args) -> switch (method.getName()) {
			case "type" -> ResponseType.NUMBER;
			case "toInteger" -> (int) value;
			case "toLong" -> value;
			case "toNumber" -> value;
			case "toString" -> String.valueOf(value);
			default -> throw new UnsupportedOperationException(method.getName());
		};
		return Response.newInstance(proxy(io.vertx.redis.client.Response.class, reply));
	}

	private static <T> T proxy(Class<T> type, InvocationHandler handler) {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return type.cast(Proxy.newProxyInstance(loader, new Class<?>[]{type}, handler));
	}
}
