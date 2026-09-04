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

The metrics collector records HTTP, SSE, and modern MCP event telemetry into OpenTelemetry
[`Meter`](https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/1.59.0/io/opentelemetry/api/metrics/Meter.html)
instruments (counters, up-down counters,
and histograms), so your existing OTel pipeline/exporter stack can collect and ship metrics.
The lifecycle observer creates OpenTelemetry server spans for HTTP requests, streaming responses, SSE
connections, and admitted MCP requests. HTTP/SSE spans use Soklet's parsed HTTP W3C trace context when
available; MCP spans use only the validated trace context carried in MCP metadata.

Its only dependency other than [Soklet](https://www.soklet.com) is [opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java) (the Java implementation of the OpenTelemetry API).

Like [Soklet](https://www.soklet.com), Java 17+ is required.

## Installation

```xml
<dependency>
  <groupId>com.soklet</groupId>
  <artifactId>soklet-otel</artifactId>
  <version>2.0.0</version>
</dependency>
```

Version 2.0.0 is built and tested against Soklet 4.0.0. The prior soklet-otel 1.3.1 release targets
Soklet 3.5.1. Soklet is a provided dependency of this integration, so applications should continue to
declare their Soklet dependency explicitly.

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
- `http.server.request.body.size` (encoded payload bytes as transferred, excluding headers and transfer framing)
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

MCP metrics in 2.0.0 map all 23 `McpMetricsEvent` variants to exactly
21 dedicated `soklet.mcp.*` instruments plus the existing shared transport-failure instrument:

| Instrument | Kind and unit | Exact attributes |
| --- | --- | --- |
| `soklet.mcp.server.starts` | Counter, `{start}` | None |
| `soklet.mcp.shutdowns` | Counter, `{shutdown}` | `soklet.mcp.shutdown.outcome` |
| `soklet.mcp.connections.accepted` | Counter, `{connection}` | None |
| `soklet.mcp.connections.rejected` | Counter, `{connection}` | None |
| `soklet.mcp.requests.accepted` | Counter, `{request}` | None |
| `soklet.mcp.requests.rejected` | Counter, `{request}` | None |
| `soklet.mcp.requests.active` | Up-down counter, `{request}` | None |
| `soklet.mcp.requests.completed` | Counter, `{request}` | `soklet.mcp.endpoint`, `rpc.method`, `soklet.mcp.request.outcome` |
| `soklet.mcp.request.duration` | Histogram, `s` | `soklet.mcp.endpoint`, `rpc.method`, `soklet.mcp.request.outcome` |
| `soklet.mcp.request.streams.active` | Up-down counter, `{stream}` | None |
| `soklet.mcp.request.stream.duration` | Histogram, `s` | `soklet.mcp.endpoint`, `rpc.method`, `soklet.mcp.stream.termination.reason` |
| `soklet.mcp.subscriptions.active` | Up-down counter, `{subscription}` | None |
| `soklet.mcp.subscription.duration` | Histogram, `s` | `soklet.mcp.endpoint`, `soklet.mcp.subscription.termination.reason` |
| `soklet.mcp.cancelations.signaled` | Counter, `{cancelation}` | `soklet.mcp.endpoint`, `rpc.method` |
| `soklet.mcp.progress.emitted` | Counter, `{notification}` | `soklet.mcp.endpoint`, `rpc.method` |
| `soklet.mcp.keepalives.emitted` | Counter, `{comment}` | None |
| `soklet.mcp.protocol.errors` | Counter, `{error}` | `rpc.jsonrpc.error_code` |
| `soklet.mcp.unknown.mirrored.headers` | Counter, `{header}` | `soklet.mcp.endpoint`, `rpc.method` |
| `soklet.mcp.handler.executions.active` | Up-down counter, `{handler}` | None |
| `soklet.mcp.handler.queue.depth` | Up-down counter, `{request}` | None |
| `soklet.mcp.handler.capacity.rejections` | Counter, `{request}` | None |
| `soklet.server.transport.failures` | Counter, `{failure}` | `soklet.server.type=mcp`, `soklet.failure.reason` |

The request-duration histogram advises these finite boundaries in seconds:
`0.001`, `0.002`, `0.005`, `0.01`, `0.025`, `0.05`, `0.1`, `0.2`, `0.4`, `0.8`,
`1.5`, `3`, `7`, and `15`. Request-stream and subscription duration histograms advise
`1`, `5`, `10`, `30`, `60`, `120`, `300`, `600`, `1800`, `3600`, `7200`, and `14400`
seconds. The OpenTelemetry SDK supplies the overflow bucket.

Enum-backed MCP values are emitted in lower-snake case. Framework-produced protocol-error codes are
exactly `-32700`, `-32600`, `-32601`, `-32602`, `-32603`, `-32020`, `-32021`, `-32022`,
`-31999`, and `-31998`. Framework-produced endpoint and method dimensions are the registered endpoint
path and recognized JSON-RPC method, with `<unrecognized>` as the bounded fallback. Directly constructed
`McpMetricsEvent` values are an application-controlled input and are not constrained to that live-runtime
vocabulary.

The fixed enum-backed vocabularies are:

- Shutdown outcome: `not_started`, `graceful_termination`, `forced_termination`,
  `unexpected_termination`, `residual_activity`, `termination_unknown`.
- Request outcome: `complete`, `input_required`, `rejected`, `application_error`, `protocol_error`,
  `internal_error`, `canceled`, `deadline_exceeded`, `client_disconnected`, `write_failed`.
- Request-stream and subscription termination reason: `completed`, `client_disconnected`, `request_canceled`,
  `deadline_exceeded`, `write_failed`, `backpressure`, `server_stopped`,
  `simulator_capture_item_limit_exceeded`, `simulator_capture_byte_limit_exceeded`, `internal_error`.
- MCP transport-failure reason: `request_read_timeout`, `request_too_large`, `malformed_request`, `read_error`,
  `write_error`, `response_write_idle_timeout`, `response_ready_error`, `request_read_timeout_error`,
  `response_write_idle_timeout_error`, `accept_loop_error`, `connection_setup_error`, `task_error`,
  `timeout_task_error`, `selection_key_error`, `register_error`, `write_timeout`, `event_loop_terminated`,
  `unknown`.

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
- `soklet.mcp.endpoint`
- `rpc.method`
- `soklet.mcp.request.outcome`
- `soklet.mcp.stream.termination.reason`
- `soklet.mcp.subscription.termination.reason`
- `rpc.jsonrpc.error_code`
- `soklet.mcp.shutdown.outcome`

Request decompression failures use `soklet.failure.reason=request_body_decompression_failed`.

## Cardinality Guidance

- `http.route` uses Soklet route declarations when available (for example `/widgets/{id}`).
- With `SEMCONV`, unmatched requests omit `http.route` (per OTel guidance).
- With `SOKLET`, unmatched requests are grouped under `_unmatched`.
- Request paths, remote addresses, and raw query values are intentionally not emitted as attributes by default.
- `error.type` is emitted only when Soklet supplies a throwable for the measurement. It uses the throwable class name, so deployments with many custom exception types should account for that cardinality in their OpenTelemetry backend.
- Framework-produced MCP metric dimensions are limited to registered endpoint paths, recognized methods or
  `<unrecognized>`, fixed lower-snake enum values, and the ten fixed protocol-error codes listed above.
- The unknown-mirrored-header counter records only endpoint and method. It never records a header name or value.
- MCP transport failures use only `soklet.server.type=mcp` and a fixed lower-snake failure reason. They do not
  add `error.type` or retain a throwable.
- For framework-produced MCP events, the integration adds no dedicated attributes for raw request data,
  payloads, request IDs, trace or parent IDs, trace-correlation tokens or key IDs, `tracestate`, baggage,
  header identity, or throwable material. Applications that submit manually constructed MCP events control the
  values placed in public dimensions such as endpoint and method; those values can contain sensitive text, so
  applications own both their confidentiality and their cardinality.

## Emitted Spans

[`OpenTelemetryLifecycleObserver`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryLifecycleObserver.html) creates `SERVER` spans for:

- Standard HTTP requests.
- Streaming HTTP responses, with the request span kept open until stream termination.
- Server-Sent Event connections.
- Admitted MCP requests and notifications, with one request span kept open through the exact client-visible
  terminal outcome, including request-stream and subscription lifetimes.

For HTTP and SSE, inbound W3C `traceparent` / `tracestate` headers parsed by
[`Request::getTraceContext`](<https://javadoc.soklet.com/com/soklet/Request.html#getTraceContext()>) are used as
the remote parent. Malformed or absent trace context produces a root span. Long-lived SSE spans may not appear
in some tracing backends until the stream ends.

MCP request spans use only the validated
[`McpRequestContext::getTraceContext`](<https://javadoc.soklet.com/com/soklet/McpRequestContext.html#getTraceContext()>)
value as their remote parent. The trace ID, parent ID, flags, and `tracestate` are preserved. Missing or invalid
MCP metadata produces a root span; the observer never falls back to the physical HTTP request's trace context,
the current OpenTelemetry `Context`, a link, or baggage.

### MCP request spans in 2.0.0

`SpanPolicy.recordMcpRequestSpans()` defaults to `true` when an application installs the observer. One span
starts at admitted semantic handling and ends at the supplied client-visible terminal duration. Preadmission
failures do not create a span, and no separate MCP session, request-stream, or subscription span is emitted.

The default span name is `MCP <method>`. The built-in name and `rpc.method` use exactly these live bounded
methods: `server/discover`, `tools/list`, `tools/call`, `prompts/list`, `prompts/get`, `resources/list`,
`resources/templates/list`, `resources/read`, `subscriptions/listen`, and `notifications/cancelled`. Every
other value, including an admitted unsupported notification, becomes `<unrecognized>`. The raw method is never
copied to `rpc.method_original`. A custom `SpanNamingStrategy` receives the full `McpRequestContext`, so the
application owns the custom name's confidentiality and cardinality.

Every MCP span begins with this exact attribute projection:

| Attribute | Value |
| --- | --- |
| `soklet.server.type` | `mcp` |
| `rpc.system.name` | `jsonrpc` |
| `rpc.method` | Bounded method or `<unrecognized>` |
| `soklet.mcp.endpoint` | Registered endpoint path |

Terminal projection always adds the lower-snake `soklet.mcp.request.outcome`. A non-null JSON-RPC error adds
its decimal code as both string-valued `rpc.response.status_code` and `error.type`, and sets status `ERROR`.
Without an error object, `rejected`, `application_error`, `protocol_error`, `internal_error`,
`deadline_exceeded`, and `write_failed` set `ERROR` with that outcome as `error.type`; `complete`,
`input_required`, `canceled`, and `client_disconnected` leave status `UNSET` and omit `error.type`. MCP spans
never set status `OK` or a status description.

MCP span events are empty. JSON-RPC error message/data and lifecycle throwables are not exported. The raw
validated trace identity exists only as OpenTelemetry parent/span identity, never as an attribute or event;
`tracestate` and baggage are not copied to attributes. The existing `recordClientAddress` and `recordRequestId`
policy opt-ins, both default `false`, may add only `client.address` and Soklet's server-generated
`soklet.request.id` from the underlying request. They never emit the MCP JSON-RPC request ID. Applications that
produce custom JSON-RPC error codes own the cardinality and confidentiality of the resulting
`rpc.response.status_code` and `error.type` values.

Trace IDs belong in spans and logs, not metric labels. If you need metrics-to-trace drill-down, use OpenTelemetry exemplars in your metrics pipeline rather than adding trace IDs as attributes.

## MCP Migration in 2.0.0

The modern core protocol has no legacy MCP session abstraction. Accordingly, 2.0.0 removes
the four `soklet.mcp.sessions.*` / `soklet.mcp.session.duration` instruments and consumes
`McpMetricsEvent` through `OpenTelemetryMetricsCollector.didRecordMcpMetricsEvent(...)` instead of the removed
session-era callbacks `didCreateMcpSession`, `didTerminateMcpSession`, `didEstablishMcpSseStream`, and
`didTerminateMcpSseStream` or the legacy request-start/request-finish callback shapes.

The release removes these public tracing controls rather than carrying their obsolete session-era semantics
forward:

- `SpanPolicy.recordMcpSessionEvents()` and `SpanPolicy.recordMcpSseStreamSpans()`, together with their builder
  setters.
- The old `SpanNamingStrategy.mcpRequestSpanName(Request, Class, String)` and
  `SpanNamingStrategy.mcpSseStreamSpanName(McpSseStream)` methods, including the corresponding default-strategy
  implementations.
- Legacy MCP branches and session events in `OpenTelemetryLifecycleObserver`.

The existing `SpanPolicy.recordMcpRequestSpans()` getter and builder setter remain, now with the admitted-request
semantics documented above. `SpanNamingStrategy.mcpRequestSpanName(McpRequestContext)` replaces the old
request-shaped method and has a default implementation, so custom strategies do not need to implement it. Old
MCP naming overrides are no longer invoked and should be removed when recompiling custom strategies.

Compared with released 1.3.1, the verified 2.0.0 public API delta is 13 removals and 4 additions. The
four additions are the modern MCP metric callback, modern MCP request start and finish callbacks, and the
context-shaped naming method.

### 2.0.0 verification

The modern span contract is frozen by these eight focused methods in
`OpenTelemetryMcpLifecycleObserverTests`:

- `mcpMetadataTraceContextIsTheOnlyRemoteParentAndPreservesTraceState`
- `mcpSpanUsesExactDefaultAndCustomNamesAttributesAndTerminalSemantics`
- `allMcpRequestOutcomesMapToExactStatusAndErrorVocabulary`
- `mcpRequestSpanStaysOpenUntilTerminalFinishAcrossStreamAndSubscriptionLifetimes`
- `mcpPolicyAndNamingAreModernAdditiveAndLegacySessionControlsRemainAbsent`
- `mcpTelemetryFailuresAreContainedAndReleaseStateExactlyOnce`
- `concurrentMcpSpansRemainContextIsolatedAndCloseDrainsEveryState`
- `mcpSpanProjectionExcludesSensitiveContextAndHttpFallbackCanaries`

`OpenTelemetryLifecycleObserverTests#legacyMcpSessionTracingSurfacesRemainAbsentAndModernRequestCallbacksAreImplemented`
freezes the public removal/addition boundary. The release verification contract runs the full 36-test module on
JDK 17, 21, and 25 and builds the package, sources, attached Javadocs, and standalone Javadocs against exact
Soklet 4.0.0.

## Notes

- The collector is thread-safe and designed for callback hot paths (no I/O or blocking operations in callback methods).
- [`MetricNamingStrategy.SEMCONV`](https://otel.javadoc.soklet.com/com/soklet/otel/OpenTelemetryMetricsCollector.MetricNamingStrategy.html)
  is the default for HTTP metric names.
- Dedicated `soklet.mcp.*` names and the MCP use of the shared transport-failure instrument are identical under
  `SEMCONV` and `SOKLET`; the naming strategy changes HTTP names only.
- With `SEMCONV`, `http.server.request.body.size` records the encoded payload size required by the OpenTelemetry HTTP semantic conventions, so a transparently decompressed gzip request reports its compressed size. With `SOKLET`, `soklet.server.request.body.size` records the handler-visible body size instead.
- If Soklet rejects an oversized request before its complete encoded payload size is known, the `SEMCONV` body-size sample is omitted instead of recording an inaccurate zero.
- `http.server.response.body.size` records the finalized `MarshaledResponse` size. If Soklet's HTTP transport applies dynamic gzip afterward, this remains the pre-compression size; already-encoded response bodies report their encoded size normally.
- `snapshot()` / `snapshotText()` from
  [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html)
  are not implemented here; use your OpenTelemetry backend/exporter to query metrics.
- The integration emits observations directly into the configured OpenTelemetry SDK. It does not promise core
  snapshot/reset/filter semantics, configured zero-series export, or atomic publication across the multiple
  instruments updated by one terminal MCP event.
- MCP span state is identity-keyed and supports cross-thread finish. Observer close drains active spans without
  fabricating an outcome or status, and telemetry failures are contained. Duplicate direct starts for the same
  context are outside the core lifecycle contract: the observer defensively ends the superseded span and retains
  the newest, but does not promise exact-once behavior for malformed callback sequences.
- MCP span export, sampling, backend retention, flushing, and cross-signal atomicity remain OpenTelemetry SDK and
  operator concerns. This integration does not yet emit a structured log carrier or raw trace-correlation token.
- Cardinality retention and eviction are controlled by OpenTelemetry SDK views, readers, and exporters. The
  core default collector's per-family retention caps do not apply here, particularly when applications submit
  manually constructed MCP events with their own endpoint, method, or protocol-code vocabulary.
- `soklet.sse.stream.duration` advises long-lived bucket boundaries
  (1s, 10s, 60s, 5m, 30m, 1h, 4h, 24h) instead of OpenTelemetry's request-oriented defaults.
  Its bucket layout changed in 1.3.0 - recheck any dashboards or alerts that referenced its previous defaults.
- The modern MCP request, request-stream, and subscription histogram boundaries are listed in the MCP table
  section above. There is no MCP session-duration instrument.

For Soklet documentation and lifecycle semantics, see [https://www.soklet.com](https://www.soklet.com).
