<a href="https://www.soklet.com">
    <picture>
        <source media="(prefers-color-scheme: dark)" srcset="https://cdn.soklet.com/soklet-gh-logo-dark-v2.png">
        <img alt="Soklet" src="https://cdn.soklet.com/soklet-gh-logo-light-v2.png" width="300" height="101">
    </picture>
</a>

# Soklet OpenTelemetry Integration (otel)

[OpenTelemetry](https://opentelemetry.io) metrics integration for [Soklet](https://www.soklet.com), implemented via [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html).

## What Is It?

This Soklet add-on library provides
[`OpenTelemetryMetricsCollector`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryMetricsCollector.html),
a production-oriented implementation of Soklet's
[`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html) interface.

It records HTTP + SSE lifecycle telemetry into OpenTelemetry
[`Meter`](https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/1.59.0/io/opentelemetry/api/metrics/Meter.html)
instruments (counters, up-down counters,
and histograms), so your existing OTel pipeline/exporter stack can collect and ship metrics.

Its only dependency other than [Soklet](https://www.soklet.com) is [opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java) (the Java implementation of the OpenTelemetry API).

Like [Soklet](https://www.soklet.com), Java 17+ is required.

## Installation

```xml
<dependency>
  <groupId>com.soklet</groupId>
  <artifactId>soklet-otel</artifactId>
  <version>1.1.0</version>
</dependency>
```

## Usage

Create a collector and wire it into
[`SokletConfig`](https://javadoc.soklet.com/com/soklet/SokletConfig.html):

```java
import com.soklet.SokletConfig;
import com.soklet.Server;
import com.soklet.otel.OpenTelemetryMetricsCollector;
import io.opentelemetry.api.OpenTelemetry;

// Acquire an OpenTelemetry instance from wherever you'd like...
OpenTelemetry openTelemetry = myOpenTelemetry();

// ...and use it to drive Soklet's OpenTelemetryMetricsCollector.
SokletConfig config = SokletConfig.withServer(
  Server.fromPort(8080)
).metricsCollector(
  OpenTelemetryMetricsCollector.withOpenTelemetry(openTelemetry)
    // Optional: SOKLET for fully-custom soklet.* HTTP metric names
    // .metricNamingStrategy(OpenTelemetryMetricsCollector.MetricNamingStrategy.SOKLET)
    .instrumentationName("com.mycompany.myapp.soklet")
    .instrumentationVersion("1.0.0")
    .build()
).build();
```

Related API references:

- [`OpenTelemetryMetricsCollector`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryMetricsCollector.html)
- [`OpenTelemetryMetricsCollector.MetricNamingStrategy`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryMetricsCollector.MetricNamingStrategy.html)
- [`OpenTelemetry`](https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/1.59.0/io/opentelemetry/api/OpenTelemetry.html)
- [`Meter`](https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/1.59.0/io/opentelemetry/api/metrics/Meter.html)
- [`SokletConfig`](https://javadoc.soklet.com/com/soklet/SokletConfig.html)
- [`Server`](https://javadoc.soklet.com/com/soklet/Server.html)
- [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html)

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
- `soklet.server.response.write.duration`
- `soklet.server.response.write.failures`
- `soklet.sse.connections.active`
- `soklet.sse.connections.established`
- `soklet.sse.connections.handshake.failures`
- `soklet.sse.connections.terminated`
- `soklet.sse.connection.duration`
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

Common attributes:

- `soklet.server.type` (`standard_http`, `server_sent_event`)
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

## Cardinality Guidance

- `http.route` uses Soklet route declarations when available (for example `/widgets/{id}`).
- With `SEMCONV`, unmatched requests omit `http.route` (per OTel guidance).
- With `SOKLET`, unmatched requests are grouped under `_unmatched`.
- Request paths, remote addresses, and raw query values are intentionally not emitted as attributes by default.

## Notes

- The collector is thread-safe and designed for callback hot paths (no I/O or blocking operations in callback methods).
- [`MetricNamingStrategy.SEMCONV`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryMetricsCollector.MetricNamingStrategy.html)
  is the default for HTTP metric names.
- `snapshot()` / `snapshotText()` from
  [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html)
  are not implemented here; use your OpenTelemetry backend/exporter to query metrics.

For Soklet documentation and lifecycle semantics, see [https://www.soklet.com](https://www.soklet.com).
