package dk.manyfold.accounting.integrations.dinero;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import java.util.concurrent.TimeUnit;

/**
 * The one HTTP client for Dinero, shared by the token provider, the read proxy and the writer.
 * Built on first use, so an inert service (no credentials) never opens one, and closed with the
 * application.
 */
@ApplicationScoped
public class DineroHttp {

  private static final long CONNECT_TIMEOUT_SECONDS = 10;
  private static final long READ_TIMEOUT_SECONDS = 60;

  private Client client;

  /** The client, built on the first call. */
  public synchronized Client get() {
    if (client == null) {
      client =
          ClientBuilder.newBuilder()
              .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .build();
    }
    return client;
  }

  @PreDestroy
  synchronized void close() {
    if (client != null) {
      client.close();
    }
  }
}
