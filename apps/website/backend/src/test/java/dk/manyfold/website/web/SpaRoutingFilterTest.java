package dk.manyfold.website.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.Test;

@QuarkusTest
class SpaRoutingFilterTest {

	@Test
	void unknownPathReturnsIndexHtml() {
		given().when().get("/some/spa/path")
				.then().statusCode(200)
				.body(containsString("SPA root"));
	}

	@Test
	void staticAssetServesDirectly() {
		given().when().get("/assets/test.js")
				.then().statusCode(200)
				.body(containsString("static asset"));
	}

	@Test
	void apiPathPassesThrough() {
		// An unknown API path reaches JAX-RS and gets its 404; the SPA fallback would
		// answer 200.
		given().when().get("/api/v1/no-such-endpoint")
				.then().statusCode(404);
	}

	@Test
	void healthPathPassesThrough() {
		given().when().get("/health/live")
				.then().statusCode(200);
	}

	@Test
	void staticAssetCacheHeaders() {
		given().when().get("/assets/test.js")
				.then().statusCode(200)
				.header("Cache-Control", "public, immutable, max-age=31536000");
	}

	@Test
	void securityHeaders() {
		given().when().get("/")
				.then().statusCode(200)
				.header("X-Frame-Options", "SAMEORIGIN")
				.header("X-Content-Type-Options", "nosniff");
	}
}
