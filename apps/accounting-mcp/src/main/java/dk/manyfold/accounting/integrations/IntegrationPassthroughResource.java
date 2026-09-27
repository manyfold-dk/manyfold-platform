package dk.manyfold.accounting.integrations;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Generic REST front door for the integration passthrough (ADR-0043). One credential-injecting
 * reverse-proxy route per vendor, parameterised by {@code {vendor}}, delegating to the shared
 * {@link IntegrationGateway}, so the role gate, vendor allowlist, read-only enforcement and audit
 * are defined once. Kept internal (not publicly routed) for now -- {@code /mcp} is the agent front
 * door.
 */
@Path("/api/integrations/{vendor}")
public class IntegrationPassthroughResource {

  @Inject IntegrationGateway gateway;

  @GET
  @Path("/{path:.*}")
  @Blocking
  public Response get(
      @PathParam("vendor") String vendor,
      @PathParam("path") String path,
      @Context UriInfo uriInfo) {
    return handle(vendor, "GET", path, uriInfo);
  }

  @HEAD
  @Path("/{path:.*}")
  @Blocking
  public Response head(
      @PathParam("vendor") String vendor,
      @PathParam("path") String path,
      @Context UriInfo uriInfo) {
    return handle(vendor, "HEAD", path, uriInfo);
  }

  private Response handle(String vendor, String method, String path, UriInfo uriInfo) {
    ProxyResult res = gateway.read(vendor, method, path, uriInfo.getRequestUri().getRawQuery());
    Response.ResponseBuilder rb = Response.status(res.status());
    if (res.contentType() != null) {
      rb.type(res.contentType());
    }
    if (!"HEAD".equals(method) && res.body() != null) {
      rb.entity(res.body());
    }
    return rb.build();
  }
}
