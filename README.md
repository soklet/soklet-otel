<a href="https://www.soklet.com">
    <picture>
        <source media="(prefers-color-scheme: dark)" srcset="https://cdn.soklet.com/soklet-gh-logo-dark-v2.png">
        <img alt="Soklet" src="https://cdn.soklet.com/soklet-gh-logo-light-v2.png" width="300" height="101">
    </picture>
</a>

# Soklet OpenTelemetry Integration (otel)

[OpenTelemetry](https://opentelemetry.io) integration for [Soklet](https://www.soklet.com), implemented via [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html) for metrics and [`LifecycleObserver`](https://javadoc.soklet.com/com/soklet/LifecycleObserver.html) for traces.

## What Is It?

This Soklet add-on library provides
[`OpenTelemetryMetricsCollector`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryMetricsCollector.html),
a production-oriented implementation of Soklet's
[`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html) interface.
It also provides
[`OpenTelemetryLifecycleObserver`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryLifecycleObserver.html),
a production-oriented implementation of Soklet's
[`LifecycleObserver`](https://javadoc.soklet.com/com/soklet/LifecycleObserver.html) interface.

The metrics collector records HTTP, SSE, and MCP session lifecycle telemetry into OpenTelemetry
[`Meter`](https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/1.59.0/io/opentelemetry/api/metrics/Meter.html)
instruments (counters, up-down counters,
and histograms), so your existing OTel pipeline/exporter stack can collect and ship metrics.
The lifecycle observer creates OpenTelemetry server spans for HTTP requests, streaming responses, SSE streams, and MCP JSON-RPC requests using Soklet's parsed W3C trace context as the remote parent when available.

Its only dependency other than [Soklet](https://www.soklet.com) is [opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java) (the Java implementation of the OpenTelemetry API).

Like [Soklet](https://www.soklet.com), Java 17+ is required.

## Installation

```xml
<dependency>
  <groupId>com.soklet</groupId>
  <artifactId>soklet-otel</artifactId>
  <version>1.3.0-SNAPSHOT</version>
</dependency>
```

## Usage

Create a collector and observer and wire them into
[`SokletConfig`](https://javadoc.soklet.com/com/soklet/SokletConfig.html):

```java
import com.soklet.SokletConfig;
import com.soklet.HttpServer;
import com.soklet.otel.OpenTelemetryLifecycleObserver;
import com.soklet.otel.OpenTelemetryMetricsCollector;
import io.opentelemetry.api.OpenTelemetry;
import java.util.List;

// Acquire an OpenTelemetry instance from wherever you'd like...
OpenTelemetry openTelemetry = myOpenTelemetry();

// ...and use it to drive Soklet's OpenTelemetry integrations.
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).metricsCollector(
  OpenTelemetryMetricsCollector.withOpenTelemetry(openTelemetry)
    // Optional: SOKLET for fully-custom soklet.* HTTP metric names
    // .metricNamingStrategy(OpenTelemetryMetricsCollector.MetricNamingStrategy.SOKLET)
    .instrumentationName("com.mycompany.myapp.soklet")
    .instrumentationVersion("1.0.0")
    .build()
).lifecycleObservers(List.of(
  OpenTelemetryLifecycleObserver.withOpenTelemetry(openTelemetry)
    .instrumentationName("com.mycompany.myapp.soklet")
    .instrumentationVersion("1.0.0")
    .build()
  )
).build();
```

Related API references:

- [`OpenTelemetryMetricsCollector`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryMetricsCollector.html)
- [`OpenTelemetryMetricsCollector.MetricNamingStrategy`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryMetricsCollector.MetricNamingStrategy.html)
- [`OpenTelemetryLifecycleObserver`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryLifecycleObserver.html)
- [`SpanPolicy`](https://otel.javadoc.soklet.com/com/soklet/otel/SpanPolicy.html)
- [`SpanNamingStrategy`](https://otel.javadoc.soklet.com/com/soklet/otel/SpanNamingStrategy.html)
- [`OpenTelemetry`](https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/1.59.0/io/opentelemetry/api/OpenTelemetry.html)
- [`Meter`](https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/1.59.0/io/opentelemetry/api/metrics/Meter.html)
- [`SokletConfig`](https://javadoc.soklet.com/com/soklet/SokletConfig.html)
- [`HttpServer`](https://javadoc.soklet.com/com/soklet/HttpServer.html)
- [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html)
- [`LifecycleObserver`](https://javadoc.soklet.com/com/soklet/LifecycleObserver.html)

If you already have a
[`Meter`](https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/1.59.0/io/opentelemetry/api/metrics/Meter.html),
wire directly:

```java
OpenTelemetryMetricsCollector collector = 
  OpenTelemetryMetricsCollector.withMeter(myMeter).build();
```

## Emitted Metrics

HTTP metrics (default strategy: `SEMCONV`):

- `http.server.active_requests`
- `http.server.request.duration`
- `http.server.request.body.size`
- `http.server.response.body.size`

Soklet-specific metrics (all strategies):

- `soklet.server.connections.accepted`
- `soklet.server.connections.rejected`
- `soklet.server.requests.accepted`
- `soklet.server.requests.rejected`
- `soklet.server.request.read.failures`
- `soklet.server.transport.failures` (`soklet.server.type`, `soklet.failure.reason`, optional `error.type`)
- `soklet.server.response.write.duration`
- `soklet.server.response.write.failures`
- `soklet.sse.streams.active`
- `soklet.sse.streams.established`
- `soklet.sse.handshakes.rejected`
- `soklet.sse.streams.terminated`
- `soklet.sse.stream.duration`
- `soklet.sse.events.written`
- `soklet.sse.events.write.failures`
- `soklet.sse.events.write.duration`
- `soklet.sse.events.delivery.lag`
- `soklet.sse.events.payload.size`
- `soklet.sse.events.queue.depth`
- `soklet.sse.events.dropped`
- `soklet.sse.comments.written`
- `soklet.sse.comments.write.failures`
- `soklet.sse.comments.write.duration`
- `soklet.sse.comments.delivery.lag`
- `soklet.sse.comments.payload.size`
- `soklet.sse.comments.queue.depth`
- `soklet.sse.comments.dropped`
- `soklet.sse.broadcast.attempted`
- `soklet.sse.broadcast.enqueued`
- `soklet.sse.broadcast.dropped`
- `soklet.mcp.sessions.active` (`soklet.mcp.endpoint.class`)
- `soklet.mcp.sessions.created` (`soklet.mcp.endpoint.class`)
- `soklet.mcp.sessions.terminated` (`soklet.mcp.endpoint.class`, `soklet.mcp.session.termination.reason`)
- `soklet.mcp.session.duration` (`soklet.mcp.endpoint.class`, `soklet.mcp.session.termination.reason`)

Common attributes:

- `soklet.server.type` (`standard_http`, `sse`, `mcp`)
- `soklet.failure.reason`
- `error.type`
- `http.request.method`
- `url.scheme`
- `http.route`
- `http.response.status_code`
- `soklet.sse.termination.reason`
- `soklet.sse.drop.reason`
- `soklet.sse.comment.type`
- `soklet.sse.broadcast.payload.type`
- `soklet.mcp.endpoint.class`
- `soklet.mcp.session.termination.reason`

## Cardinality Guidance

- `http.route` uses Soklet route declarations when available (for example `/widgets/{id}`).
- With `SEMCONV`, unmatched requests omit `http.route` (per OTel guidance).
- With `SOKLET`, unmatched requests are grouped under `_unmatched`.
- Request paths, remote addresses, and raw query values are intentionally not emitted as attributes by default.
- `error.type` is emitted only when Soklet supplies a throwable for the measurement. It uses the throwable class name, so deployments with many custom exception types should account for that cardinality in their OpenTelemetry backend.
- `soklet.mcp.endpoint.class` uses the endpoint's fully-qualified class name, so its cardinality is bounded by the number of MCP endpoint classes. MCP session IDs are intentionally never emitted as attributes.
- W3C trace context from `traceparent` / `tracestate` is available through Soklet's `Request` callbacks, but this metrics collector does not emit trace IDs, parent IDs, or `tracestate` values as metric attributes. Those values are high-cardinality and are better handled by logs, spans, or exemplar-aware tracing integrations.

## Emitted Spans

[`OpenTelemetryLifecycleObserver`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryLifecycleObserver.html) creates `SERVER` spans for:

- Standard HTTP requests.
- Streaming HTTP responses, with the request span kept open until stream termination.
- Server-Sent Event connections.
- MCP JSON-RPC requests.
- MCP SSE streams.

Inbound W3C `traceparent` / `tracestate` headers parsed by [`Request::getTraceContext`](<https://javadoc.soklet.com/com/soklet/Request.html#getTraceContext()>) are used as the remote parent. Malformed or absent trace context produces a root span. Long-lived SSE and MCP SSE spans may not appear in some tracing backends until the stream ends.

Trace IDs belong in spans and logs, not metric labels. If you need metrics-to-trace drill-down, use OpenTelemetry exemplars in your metrics pipeline rather than adding trace IDs as attributes.

## Notes

- The collector is thread-safe and designed for callback hot paths (no I/O or blocking operations in callback methods).
- [`MetricNamingStrategy.SEMCONV`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryMetricsCollector.MetricNamingStrategy.html)
  is the default for HTTP metric names.
- `snapshot()` / `snapshotText()` from
  [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html)
  are not implemented here; use your OpenTelemetry backend/exporter to query metrics.
- `soklet.sse.stream.duration` and `soklet.mcp.session.duration` advise long-lived bucket boundaries
  (1s, 10s, 60s, 5m, 30m, 1h, 4h, 24h) suited to stream/session lifetimes instead of OpenTelemetry's
  request-oriented defaults. The SSE histogram's bucket layout changed in 1.3.0 - recheck any
  dashboards or alerts that referenced its previous default buckets.

For Soklet documentation and lifecycle semantics, see [https://www.soklet.com](https://www.soklet.com).
