package dk.manyfold.website.web;

import io.quarkus.vertx.web.RouteFilter;

import io.vertx.ext.web.RoutingContext;

/**
 * Serves the Vue SPA for paths that don't match API routes or static files.
 * Replaces nginx's try_files $uri $uri/ /index.html behavior. Also adds cache
 * headers for hashed assets and security headers.
 */
public class SpaRoutingFilter {

	private static final String CACHE_CONTROL_IMMUTABLE = "public, immutable, max-age=31536000";

	@RouteFilter(400)
	void spaRouting(RoutingContext rc) {
		String path = rc.normalizedPath();

		// Let API, health, metrics, openapi, and swagger routes pass through
		if (path.startsWith("/api") || path.startsWith("/health")
				|| path.startsWith("/q") || path.startsWith("/openapi")
				|| path.startsWith("/swagger-ui") || path.startsWith("/metrics")) {
			rc.next();
			return;
		}

		// Let static files (paths with a file extension) pass through
		if (path.contains(".")) {
			if (path.startsWith("/assets/")) {
				rc.response().putHeader("Cache-Control", CACHE_CONTROL_IMMUTABLE);
			}
			rc.next();
			return;
		}

		// SPA catch-all: serve index.html
		rc.reroute("/index.html");
	}

	@RouteFilter(500)
	void securityHeaders(RoutingContext rc) {
		rc.response()
				.putHeader("X-Frame-Options", "SAMEORIGIN")
				.putHeader("X-Content-Type-Options", "nosniff");
		rc.next();
	}
}
