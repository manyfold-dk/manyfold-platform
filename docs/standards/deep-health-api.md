# Deep Health API Standard

## Table of Contents

- [Overview](#overview)
- [Endpoint Specification](#endpoint-specification)
- [Response Schema](#response-schema)
- [Status Definitions](#status-definitions)
- [Implementation Guidelines](#implementation-guidelines)
- [Examples](#examples)
- [Integration with Platform](#integration-with-platform)
- [Testing](#testing)
- [References](#references)

## Overview

The Deep Health API is a standardized health check endpoint that all platform applications must implement. Unlike simple liveness probes that return basic up/down status, deep health endpoints provide comprehensive health information including:

- Self-health metrics (memory, CPU, threads)
- Dependency health (databases, storage, external services)
- Resource utilization
- Application-specific metadata

The response is meant to let operators and tools:
1. Identify root causes of failures
2. Tell a failing dependency from a failing service
3. Display meaningful health information in dashboards

What the platform consumes today is narrower; see [Integration with Platform](#integration-with-platform).

## Endpoint Specification

| Attribute | Value |
|-----------|-------|
| Path | `/api/v1/health/deep` |
| Method | `GET` |
| Content-Type | `application/json` |
| Response Codes | `200 OK` (always, even when unhealthy) |

The endpoint should always return HTTP 200 with a JSON body. The health status is communicated via the `status` field in the response, not the HTTP status code. This ensures monitoring systems can always parse the response. Access control in front of the application (a login at the gateway, for example) can still answer 401 or 403 before the request reaches the endpoint.

## Response Schema

```json
{
  "status": "healthy | degraded | unhealthy",
  "service": "service-name",
  "version": "1.0.0",
  "timestamp": "2024-01-26T12:00:00Z",
  "self": {
    "status": "healthy | degraded | unhealthy",
    "uptimeSeconds": 3600,
    "resources": {
      "cpuPercent": 25.5,
      "memoryUsedBytes": 268435456,
      "memoryMaxBytes": 536870912,
      "activeThreads": 42
    },
    "metrics": {
      "customMetric": "value"
    }
  },
  "dependencies": [
    {
      "name": "dependency-name",
      "type": "database | cache | storage | api | queue",
      "status": "healthy | degraded | unhealthy",
      "latencyMs": 15,
      "message": "Optional status message"
    }
  ],
  "metadata": {
    "javaVersion": "21",
    "customKey": "customValue"
  }
}
```

### Required Fields

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | Overall health status |
| `service` | string | Service name |
| `version` | string | Service version |
| `timestamp` | ISO 8601 | Response timestamp |
| `self` | object | Self-health information |
| `dependencies` | array | List of dependency health checks |

### Optional Fields

| Field | Type | Description |
|-------|------|-------------|
| `metadata` | object | Additional key-value pairs |
| `self.metrics` | object | Custom application metrics |

## Status Definitions

### Overall Status

| Status | Meaning | Action |
|--------|---------|--------|
| `healthy` | Service fully operational | None |
| `degraded` | Partial functionality, non-critical issues | Monitor, may auto-heal |
| `unhealthy` | Critical failures, service unable to function | Requires remediation |

### Dependency Status

| Status | Meaning | Example |
|--------|---------|---------|
| `healthy` | Dependency accessible and responsive | Database query succeeds < 100ms |
| `degraded` | Dependency slow or partially available | Database query > 1s but succeeds |
| `unhealthy` | Dependency unreachable or failing | Connection refused |

### Dependency Types

| Type | Description | Example Checks |
|------|-------------|----------------|
| `database` | SQL/NoSQL databases | Connection test, simple query |
| `cache` | Redis, Memcached | Ping, get/set test |
| `storage` | S3, file systems | HeadBucket, list files |
| `api` | External HTTP services | Health endpoint call |
| `queue` | Message queues | Connection test, queue stats |

## Implementation Guidelines

### 1. Always Return HTTP 200

```java
// Good - status in body, always 200
@GET
public Response deepHealth() {
    DeepHealthResponse health = collectHealth();
    return Response.ok(health).build();  // Always 200
}
```

### 2. Include All Critical Dependencies

Check every external dependency that could impact service operation:

```java
List<DependencyHealth> dependencies = new ArrayList<>();
dependencies.add(checkDatabase());
dependencies.add(checkRedis());
dependencies.add(checkS3Storage());
dependencies.add(checkExternalApi());
```

### 3. Set Reasonable Timeouts

Dependency checks should timeout quickly (< 5 seconds) to prevent health endpoint hanging:

```java
private DependencyHealth checkDatabase() {
    long start = System.currentTimeMillis();
    // try-with-resources returns the pooled connection, even on failure
    try (Connection connection = dataSource.getConnection()) {
        if (!connection.isValid(3)) {  // 3-second validation timeout
            return unhealthy("database", "Connection validation failed");
        }
        return healthy("database", System.currentTimeMillis() - start);
    } catch (Exception e) {
        return unhealthy("database", e.getMessage());
    }
}
```

`isValid(3)` bounds only the validation. Waiting for a free pooled connection is bounded by the pool's acquisition timeout, so keep that short as well.

### 4. Calculate Status Correctly

Overall status should reflect the worst status among self and dependencies:

```java
private String calculateStatus(SelfHealth self, List<DependencyHealth> deps) {
    if ("unhealthy".equals(self.status())) return "unhealthy";

    boolean degraded = "degraded".equals(self.status());
    for (DependencyHealth dep : deps) {
        if ("unhealthy".equals(dep.status())) return "unhealthy";
        if ("degraded".equals(dep.status())) degraded = true;
    }

    return degraded ? "degraded" : "healthy";
}
```

### 5. Include Meaningful Messages

Dependency messages should help diagnose issues:

```java
// Good - includes specific error
return new DependencyHealth("s3", "storage", "unhealthy", latency,
    "Connection refused: https://s3.example.com");

// Bad - no useful information
return new DependencyHealth("s3", "storage", "unhealthy", latency, "Failed");
```

## Examples

### Java/Quarkus Example

See implementation in:
- `apps/website/backend/src/main/java/dk/manyfold/website/api/v1/DeepHealthResource.java`
- A tenant application in its own repository implements the same resource with an S3 storage check; the response examples below follow its shape

### Response Example - Healthy

```json
{
  "status": "healthy",
  "service": "example-service",
  "version": "1.0.0",
  "timestamp": "2024-01-26T12:00:00Z",
  "self": {
    "status": "healthy",
    "uptimeSeconds": 86400,
    "resources": {
      "cpuPercent": 15.2,
      "memoryUsedBytes": 134217728,
      "memoryMaxBytes": 536870912,
      "activeThreads": 28
    },
    "metrics": {
      "heapUsedPercent": 25
    }
  },
  "dependencies": [
    {
      "name": "s3-storage",
      "type": "storage",
      "status": "healthy",
      "latencyMs": 45,
      "message": "Bucket example-service-data accessible"
    }
  ],
  "metadata": {
    "javaVersion": "21",
    "s3Bucket": "example-service-data"
  }
}
```

### Response Example - Unhealthy

```json
{
  "status": "unhealthy",
  "service": "example-service",
  "version": "1.0.0",
  "timestamp": "2024-01-26T12:05:00Z",
  "self": {
    "status": "healthy",
    "uptimeSeconds": 86700,
    "resources": {
      "cpuPercent": 12.0,
      "memoryUsedBytes": 134217728,
      "memoryMaxBytes": 536870912,
      "activeThreads": 28
    },
    "metrics": {}
  },
  "dependencies": [
    {
      "name": "s3-storage",
      "type": "storage",
      "status": "unhealthy",
      "latencyMs": 5000,
      "message": "Failed: Connection timed out"
    }
  ],
  "metadata": {}
}
```

## Integration with Platform

### Health Aggregation

The website backend's `HealthAggregationService` builds the layered platform health that the backend serves at `GET /api/v1/health` and `GET /api/v1/health/summary`. In its applications layer it calls exactly one deep health endpoint: the website backend's own, over loopback. From that response it takes the overall `status` and the name of the first `unhealthy` dependency. Every other application is checked by pod phase through the Kubernetes API, from a list each installation configures, because those applications' network policies keep the backend out. Implementing this endpoint does not, by itself, add an application to the aggregate.

### Dashboard Display

The website's Deep Health Explorer view (behind a login) renders the website backend's own deep health response, showing:
- Overall and self status
- Individual dependency status
- Latency metrics
- Dependency messages

The Grafana "Mother of Dashboards" does not read deep health. Its Applications tile shows the Prometheus `up` metric of the website backend's scrape job.

### Automated Remediation

Remediation is driven by alerts, not by deep health. A deep health `unhealthy` status triggers no action by itself. The website backend's `AutoRemediationService` evaluates the active Alertmanager alerts every 60 seconds and maps them to actions:

| Alert | Remediation Action |
|-------|-------------------|
| `KubePodCrashLooping`, `KubePodNotReady` | Pod restart (the pod is deleted; its controller recreates it) |
| `KubeDeploymentReplicasMismatch` | Deployment rollout restart |
| Any other alert, including a failing dependency | None; the alert goes to humans through its normal route |

`RemediationService` guards every resource: at most one attempt per 5 minutes and at most 3 attempts per hour. After that it stops acting, and the alert stays with humans.

## Testing

### Unit Tests

Test the deep health endpoint returns correct structure:

```java
@Test
void testDeepHealthEndpoint() {
    given()
        .when()
        .get("/api/v1/health/deep")
        .then()
        .statusCode(200)
        .body("status", notNullValue())
        .body("service", notNullValue())
        .body("self", notNullValue())
        .body("dependencies", notNullValue());
}
```

### Integration Tests

Test with actual dependencies (in dev environment):

```java
@Test
void testDeepHealthWithS3() {
    // Requires S3 to be available
    given()
        .when()
        .get("/api/v1/health/deep")
        .then()
        .body("dependencies.find { it.name == 's3-storage' }.status",
            equalTo("healthy"));
}
```

## References

- [ADR 0023: Self-Healing Platform](../adr/0023-self-healing-platform.md)
- [ADR 0013: Observability Stack](../adr/0013-observability-stack.md)
- [Kubernetes Health Checks](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
