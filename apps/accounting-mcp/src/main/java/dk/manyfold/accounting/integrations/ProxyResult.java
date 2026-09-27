package dk.manyfold.accounting.integrations;

import jakarta.ws.rs.core.MediaType;

/**
 * Result of a forwarded upstream call: the upstream HTTP status, its content type, and the
 * (size-capped) body bytes. Shared across the integration layer (ADR-0043) so both the REST
 * passthrough and the MCP tool surface the same shape from the single {@link VendorProxy}.
 */
public record ProxyResult(int status, MediaType contentType, byte[] body) {}
