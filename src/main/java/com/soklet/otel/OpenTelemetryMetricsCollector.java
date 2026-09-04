/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.otel;

import com.soklet.ConnectionRejectionReason;
import com.soklet.MarshaledResponse;
import com.soklet.McpMetricsEvent;
import com.soklet.MetricsCollector;
import com.soklet.ShutdownComponentDisposition;
import com.soklet.Request;
import com.soklet.RequestReadFailureReason;
import com.soklet.RequestRejectionReason;
import com.soklet.ResourceMethod;
import com.soklet.ResourcePathDeclaration;
import com.soklet.ServerType;
import com.soklet.SseComment;
import com.soklet.SseConnection;
import com.soklet.SseEvent;
import com.soklet.StreamTermination;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * OpenTelemetry-backed {@link MetricsCollector} for Soklet HTTP, SSE, and MCP telemetry.
 * <p>
 * This implementation records counters/histograms via OpenTelemetry's metrics API and is designed to be
 * lightweight, thread-safe, and non-blocking in request hot paths.
 * <p>
 * By default, standard HTTP metrics use OpenTelemetry Semantic Convention names. Soklet-specific concepts
 * (for example SSE queue/drop/broadcast details) are emitted with {@code soklet.*} names.
 * For request-body size, the default semantic-convention strategy records the encoded payload size as
 * transferred, while the {@link MetricNamingStrategy#SOKLET} strategy records the handler-visible body size.
 * If an oversized request is rejected before its complete encoded payload size is known, the
 * semantic-convention body-size sample is omitted instead of recording an inaccurate zero.
 * Response-body size is based on the finalized {@link MarshaledResponse}; if the HTTP transport applies
 * dynamic gzip afterward, that metric remains the pre-compression size.
 * <p>
 * If inbound requests include W3C trace context, Soklet exposes it via {@link Request#getTraceContext()} to
 * custom metrics collectors and application code. This metrics-only implementation intentionally does not emit
 * trace IDs, parent IDs, or {@code tracestate} values as metric attributes because those values are high-cardinality
 * and belong in logs, spans, or exemplar-aware tracing integrations instead.
 * MCP listener shutdown dispositions are projected to the exact lower-snake values
 * {@code not_started}, {@code graceful_termination}, {@code forced_termination},
 * {@code unexpected_termination}, {@code residual_activity}, and
 * {@code termination_unknown}.
 * <p>
 * See <a href="https://soklet.com/docs/metrics-collection">https://soklet.com/docs/metrics-collection</a> for Soklet's metrics/telemetry documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class OpenTelemetryMetricsCollector implements MetricsCollector {
	@NonNull
	private static final String UNMATCHED_ROUTE;
	@NonNull
	private static final String UNKNOWN_COMMENT_TYPE;
	@NonNull
	private static final String BROADCAST_PAYLOAD_EVENT;
	@NonNull
	private static final String BROADCAST_PAYLOAD_COMMENT;
	@NonNull
	private static final String DEFAULT_INSTRUMENTATION_NAME;
	@NonNull
	private static final String URL_SCHEME_HTTP;

	@NonNull
	private static final AttributeKey<String> SERVER_TYPE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> FAILURE_REASON_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> ERROR_TYPE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> HTTP_METHOD_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> HTTP_ROUTE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> URL_SCHEME_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<Long> HTTP_STATUS_CODE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> SSE_TERMINATION_REASON_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> SSE_DROP_REASON_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> SSE_COMMENT_TYPE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> SSE_BROADCAST_PAYLOAD_TYPE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> MCP_ENDPOINT_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> RPC_METHOD_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> MCP_STREAM_TERMINATION_REASON_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> MCP_SUBSCRIPTION_TERMINATION_REASON_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<Long> RPC_JSON_RPC_ERROR_CODE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> MCP_SHUTDOWN_OUTCOME_ATTRIBUTE_KEY;
	@NonNull
	private static final List<Double> LONG_LIVED_DURATION_BUCKET_BOUNDARIES;
	@NonNull
	private static final List<Double> MCP_REQUEST_DURATION_BUCKET_BOUNDARIES;
	@NonNull
	private static final List<Double> MCP_LONG_LIVED_DURATION_BUCKET_BOUNDARIES;

	static {
		UNMATCHED_ROUTE = "_unmatched";
		UNKNOWN_COMMENT_TYPE = "unknown";
		BROADCAST_PAYLOAD_EVENT = "event";
		BROADCAST_PAYLOAD_COMMENT = "comment";
		DEFAULT_INSTRUMENTATION_NAME = "com.soklet.otel";
		URL_SCHEME_HTTP = "http";

		SERVER_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.server.type");
		FAILURE_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.failure.reason");
		ERROR_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("error.type");
		HTTP_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("http.request.method");
		HTTP_ROUTE_ATTRIBUTE_KEY = AttributeKey.stringKey("http.route");
		URL_SCHEME_ATTRIBUTE_KEY = AttributeKey.stringKey("url.scheme");
		HTTP_STATUS_CODE_ATTRIBUTE_KEY = AttributeKey.longKey("http.response.status_code");
		SSE_TERMINATION_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.sse.termination.reason");
		SSE_DROP_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.sse.drop.reason");
		SSE_COMMENT_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.sse.comment.type");
		SSE_BROADCAST_PAYLOAD_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.sse.broadcast.payload.type");
		MCP_ENDPOINT_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.endpoint");
		RPC_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("rpc.method");
		MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.request.outcome");
		MCP_STREAM_TERMINATION_REASON_ATTRIBUTE_KEY =
				AttributeKey.stringKey("soklet.mcp.stream.termination.reason");
		MCP_SUBSCRIPTION_TERMINATION_REASON_ATTRIBUTE_KEY =
				AttributeKey.stringKey("soklet.mcp.subscription.termination.reason");
		RPC_JSON_RPC_ERROR_CODE_ATTRIBUTE_KEY = AttributeKey.longKey("rpc.jsonrpc.error_code");
		MCP_SHUTDOWN_OUTCOME_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.shutdown.outcome");
		// SSE streams live for minutes-to-hours, so OpenTelemetry's request-oriented
		// defaults would collapse nearly all measurements into the +Inf bucket.
		LONG_LIVED_DURATION_BUCKET_BOUNDARIES = List.of(1D, 10D, 60D, 300D, 1_800D, 3_600D, 14_400D, 86_400D);
		MCP_REQUEST_DURATION_BUCKET_BOUNDARIES = List.of(
				0.001D, 0.002D, 0.005D, 0.010D, 0.025D, 0.050D, 0.100D,
				0.200D, 0.400D, 0.800D, 1.500D, 3D, 7D, 15D);
		MCP_LONG_LIVED_DURATION_BUCKET_BOUNDARIES = List.of(
				1D, 5D, 10D, 30D, 60D, 120D, 300D, 600D, 1_800D,
				3_600D, 7_200D, 14_400D);
	}

	@NonNull
	private final LongCounter connectionsAcceptedCounter;
	@NonNull
	private final LongCounter connectionsRejectedCounter;
	@NonNull
	private final LongCounter requestsAcceptedCounter;
	@NonNull
	private final LongCounter requestsRejectedCounter;
	@NonNull
	private final LongCounter requestReadFailureCounter;
	@NonNull
	private final LongCounter transportFailureCounter;
	@NonNull
	private final LongUpDownCounter activeRequestsCounter;
	@NonNull
	private final DoubleHistogram requestDurationHistogram;
	@NonNull
	private final DoubleHistogram responseWriteDurationHistogram;
	@NonNull
	private final LongCounter responseWriteFailureCounter;
	@NonNull
	private final LongCounter requestThrowableCounter;
	@NonNull
	private final LongHistogram requestBodySizeHistogram;
	@NonNull
	private final LongHistogram responseBodySizeHistogram;

	@NonNull
	private final LongUpDownCounter activeServerSentEventStreamsCounter;
	@NonNull
	private final LongCounter serverSentEventStreamsEstablishedCounter;
	@NonNull
	private final LongCounter serverSentEventHandshakeFailureCounter;
	@NonNull
	private final LongCounter serverSentEventStreamsTerminatedCounter;
	@NonNull
	private final DoubleHistogram serverSentEventStreamDurationHistogram;
	@NonNull
	private final LongCounter serverSentEventWrittenCounter;
	@NonNull
	private final LongCounter serverSentEventWriteFailureCounter;
	@NonNull
	private final DoubleHistogram serverSentEventWriteDurationHistogram;
	@NonNull
	private final DoubleHistogram serverSentEventDeliveryLagHistogram;
	@NonNull
	private final LongHistogram serverSentEventPayloadSizeHistogram;
	@NonNull
	private final LongHistogram serverSentEventQueueDepthHistogram;
	@NonNull
	private final LongCounter serverSentEventDropCounter;

	@NonNull
	private final LongCounter serverSentEventCommentWrittenCounter;
	@NonNull
	private final LongCounter serverSentEventCommentWriteFailureCounter;
	@NonNull
	private final DoubleHistogram serverSentEventCommentWriteDurationHistogram;
	@NonNull
	private final DoubleHistogram serverSentEventCommentDeliveryLagHistogram;
	@NonNull
	private final LongHistogram serverSentEventCommentPayloadSizeHistogram;
	@NonNull
	private final LongHistogram serverSentEventCommentQueueDepthHistogram;
	@NonNull
	private final LongCounter serverSentEventCommentDropCounter;

	@NonNull
	private final LongCounter serverSentEventBroadcastAttemptCounter;
	@NonNull
	private final LongCounter serverSentEventBroadcastEnqueuedCounter;
	@NonNull
	private final LongCounter serverSentEventBroadcastDroppedCounter;

	@NonNull
	private final LongCounter mcpServerStartsCounter;
	@NonNull
	private final LongCounter mcpShutdownsCounter;
	@NonNull
	private final LongCounter mcpConnectionsAcceptedCounter;
	@NonNull
	private final LongCounter mcpConnectionsRejectedCounter;
	@NonNull
	private final LongCounter mcpRequestsAcceptedCounter;
	@NonNull
	private final LongCounter mcpRequestsRejectedCounter;
	@NonNull
	private final LongUpDownCounter mcpActiveRequestsCounter;
	@NonNull
	private final LongCounter mcpRequestsCompletedCounter;
	@NonNull
	private final DoubleHistogram mcpRequestDurationHistogram;
	@NonNull
	private final LongUpDownCounter mcpActiveRequestStreamsCounter;
	@NonNull
	private final DoubleHistogram mcpRequestStreamDurationHistogram;
	@NonNull
	private final LongUpDownCounter mcpActiveSubscriptionsCounter;
	@NonNull
	private final DoubleHistogram mcpSubscriptionDurationHistogram;
	@NonNull
	private final LongCounter mcpCancelationsSignaledCounter;
	@NonNull
	private final LongCounter mcpProgressEmittedCounter;
	@NonNull
	private final LongCounter mcpKeepAlivesEmittedCounter;
	@NonNull
	private final LongCounter mcpProtocolErrorsCounter;
	@NonNull
	private final LongCounter mcpUnknownMirroredHeadersCounter;
	@NonNull
	private final LongUpDownCounter mcpActiveHandlerExecutionsCounter;
	@NonNull
	private final LongUpDownCounter mcpHandlerQueueDepthCounter;
	@NonNull
	private final LongCounter mcpHandlerCapacityRejectionsCounter;

	@NonNull
	private final MetricNamingStrategy metricNamingStrategy;

	/**
	 * Acquires a builder for {@link OpenTelemetryMetricsCollector} instances, using {@link GlobalOpenTelemetry}
	 * by default.
	 *
	 * @return the builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Acquires a builder seeded with a required {@link Meter}.
	 *
	 * @param meter the meter used to build instruments
	 * @return the builder
	 */
	@NonNull
	public static Builder withMeter(@NonNull Meter meter) {
		requireNonNull(meter);
		return builder().meter(meter);
	}

	/**
	 * Acquires a builder seeded with a required {@link OpenTelemetry} instance.
	 *
	 * @param openTelemetry the OpenTelemetry instance used to build a meter
	 * @return the builder
	 */
	@NonNull
	public static Builder withOpenTelemetry(@NonNull OpenTelemetry openTelemetry) {
		requireNonNull(openTelemetry);
		return builder().openTelemetry(openTelemetry);
	}

	/**
	 * Creates an instance from a required {@link Meter} without additional customization.
	 *
	 * @param meter the meter used to build instruments
	 * @return an {@link OpenTelemetryMetricsCollector} instance
	 */
	@NonNull
	public static OpenTelemetryMetricsCollector fromMeter(@NonNull Meter meter) {
		return withMeter(meter).build();
	}

	/**
	 * Creates an instance from a required {@link OpenTelemetry} without additional customization.
	 *
	 * @param openTelemetry the OpenTelemetry instance used to build a meter
	 * @return an {@link OpenTelemetryMetricsCollector} instance
	 */
	@NonNull
	public static OpenTelemetryMetricsCollector fromOpenTelemetry(@NonNull OpenTelemetry openTelemetry) {
		return withOpenTelemetry(openTelemetry).build();
	}

	private OpenTelemetryMetricsCollector(@NonNull Builder builder) {
		requireNonNull(builder);
		Meter meter = requireNonNull(builder.resolveMeter());
		this.metricNamingStrategy = requireNonNull(builder.metricNamingStrategy);

		String activeRequestsMetricName = activeRequestsMetricNameFor(this.metricNamingStrategy);
		String requestDurationMetricName = requestDurationMetricNameFor(this.metricNamingStrategy);
		String requestBodySizeMetricName = requestBodySizeMetricNameFor(this.metricNamingStrategy);
		String requestBodySizeDescription = requestBodySizeDescriptionFor(this.metricNamingStrategy);
		String responseBodySizeMetricName = responseBodySizeMetricNameFor(this.metricNamingStrategy);

		this.connectionsAcceptedCounter = meter.counterBuilder("soklet.server.connections.accepted")
				.setDescription("Total number of accepted inbound TCP connections.")
				.setUnit("{connection}")
				.build();
		this.connectionsRejectedCounter = meter.counterBuilder("soklet.server.connections.rejected")
				.setDescription("Total number of rejected inbound TCP connections.")
				.setUnit("{connection}")
				.build();
		this.requestsAcceptedCounter = meter.counterBuilder("soklet.server.requests.accepted")
				.setDescription("Total number of accepted requests before app-level handling.")
				.setUnit("{request}")
				.build();
		this.requestsRejectedCounter = meter.counterBuilder("soklet.server.requests.rejected")
				.setDescription("Total number of rejected requests before app-level handling.")
				.setUnit("{request}")
				.build();
		this.requestReadFailureCounter = meter.counterBuilder("soklet.server.request.read.failures")
				.setDescription("Total number of request read/parse failures.")
				.setUnit("{request}")
				.build();
		this.transportFailureCounter = meter.counterBuilder("soklet.server.transport.failures")
				.setDescription("Total number of low-level transport failures.")
				.setUnit("{failure}")
				.build();
		this.activeRequestsCounter = meter.upDownCounterBuilder(activeRequestsMetricName)
				.setDescription("Number of in-flight requests currently being handled.")
				.setUnit("{request}")
				.build();
		this.requestDurationHistogram = meter.histogramBuilder(requestDurationMetricName)
				.setDescription("Total request handling duration.")
				.setUnit("s")
				.build();
		this.responseWriteDurationHistogram = meter.histogramBuilder("soklet.server.response.write.duration")
				.setDescription("Duration spent writing response bytes.")
				.setUnit("s")
				.build();
		this.responseWriteFailureCounter = meter.counterBuilder("soklet.server.response.write.failures")
				.setDescription("Total number of response write failures.")
				.setUnit("{response}")
				.build();
		this.requestThrowableCounter = meter.counterBuilder("soklet.server.request.throwables")
				.setDescription("Total number of throwables observed during request handling.")
				.setUnit("{throwable}")
				.build();
		this.requestBodySizeHistogram = meter.histogramBuilder(requestBodySizeMetricName)
				.ofLongs()
				.setDescription(requestBodySizeDescription)
				.setUnit("By")
				.build();
		this.responseBodySizeHistogram = meter.histogramBuilder(responseBodySizeMetricName)
				.ofLongs()
				.setDescription("Response body size in bytes.")
				.setUnit("By")
				.build();

		this.activeServerSentEventStreamsCounter = meter.upDownCounterBuilder("soklet.sse.streams.active")
				.setDescription("Number of active SSE streams.")
				.setUnit("{stream}")
				.build();
		this.serverSentEventStreamsEstablishedCounter = meter.counterBuilder("soklet.sse.streams.established")
				.setDescription("Total number of SSE streams established.")
				.setUnit("{stream}")
				.build();
		this.serverSentEventHandshakeFailureCounter = meter.counterBuilder("soklet.sse.handshakes.rejected")
				.setDescription("Total number of rejected SSE handshakes.")
				.setUnit("{handshake}")
				.build();
		this.serverSentEventStreamsTerminatedCounter = meter.counterBuilder("soklet.sse.streams.terminated")
				.setDescription("Total number of terminated SSE streams.")
				.setUnit("{stream}")
				.build();
		this.serverSentEventStreamDurationHistogram = meter.histogramBuilder("soklet.sse.stream.duration")
				.setDescription("SSE stream duration.")
				.setUnit("s")
				.setExplicitBucketBoundariesAdvice(LONG_LIVED_DURATION_BUCKET_BOUNDARIES)
				.build();
		this.serverSentEventWrittenCounter = meter.counterBuilder("soklet.sse.events.written")
				.setDescription("Total number of SSE events successfully written.")
				.setUnit("{event}")
				.build();
		this.serverSentEventWriteFailureCounter = meter.counterBuilder("soklet.sse.events.write.failures")
				.setDescription("Total number of SSE events that failed to write.")
				.setUnit("{event}")
				.build();
		this.serverSentEventWriteDurationHistogram = meter.histogramBuilder("soklet.sse.events.write.duration")
				.setDescription("SSE event write duration.")
				.setUnit("s")
				.build();
		this.serverSentEventDeliveryLagHistogram = meter.histogramBuilder("soklet.sse.events.delivery.lag")
				.setDescription("Time spent waiting in the SSE queue before write.")
				.setUnit("s")
				.build();
		this.serverSentEventPayloadSizeHistogram = meter.histogramBuilder("soklet.sse.events.payload.size")
				.ofLongs()
				.setDescription("Serialized SSE event payload size in bytes.")
				.setUnit("By")
				.build();
		this.serverSentEventQueueDepthHistogram = meter.histogramBuilder("soklet.sse.events.queue.depth")
				.ofLongs()
				.setDescription("Queued element depth when SSE event write/drop outcome is observed.")
				.setUnit("{item}")
				.build();
		this.serverSentEventDropCounter = meter.counterBuilder("soklet.sse.events.dropped")
				.setDescription("Total number of SSE events dropped before enqueue.")
				.setUnit("{event}")
				.build();

		this.serverSentEventCommentWrittenCounter = meter.counterBuilder("soklet.sse.comments.written")
				.setDescription("Total number of SSE comments successfully written.")
				.setUnit("{comment}")
				.build();
		this.serverSentEventCommentWriteFailureCounter = meter.counterBuilder("soklet.sse.comments.write.failures")
				.setDescription("Total number of SSE comments that failed to write.")
				.setUnit("{comment}")
				.build();
		this.serverSentEventCommentWriteDurationHistogram = meter.histogramBuilder("soklet.sse.comments.write.duration")
				.setDescription("SSE comment write duration.")
				.setUnit("s")
				.build();
		this.serverSentEventCommentDeliveryLagHistogram = meter.histogramBuilder("soklet.sse.comments.delivery.lag")
				.setDescription("Time spent waiting in the SSE queue before comment write.")
				.setUnit("s")
				.build();
		this.serverSentEventCommentPayloadSizeHistogram = meter.histogramBuilder("soklet.sse.comments.payload.size")
				.ofLongs()
				.setDescription("Serialized SSE comment payload size in bytes.")
				.setUnit("By")
				.build();
		this.serverSentEventCommentQueueDepthHistogram = meter.histogramBuilder("soklet.sse.comments.queue.depth")
				.ofLongs()
				.setDescription("Queued element depth when SSE comment write/drop outcome is observed.")
				.setUnit("{item}")
				.build();
		this.serverSentEventCommentDropCounter = meter.counterBuilder("soklet.sse.comments.dropped")
				.setDescription("Total number of SSE comments dropped before enqueue.")
				.setUnit("{comment}")
				.build();

		this.serverSentEventBroadcastAttemptCounter = meter.counterBuilder("soklet.sse.broadcast.attempted")
				.setDescription("Total number of attempted SSE broadcast deliveries.")
				.setUnit("{delivery}")
				.build();
		this.serverSentEventBroadcastEnqueuedCounter = meter.counterBuilder("soklet.sse.broadcast.enqueued")
				.setDescription("Total number of SSE broadcast deliveries successfully enqueued.")
				.setUnit("{delivery}")
				.build();
		this.serverSentEventBroadcastDroppedCounter = meter.counterBuilder("soklet.sse.broadcast.dropped")
				.setDescription("Total number of SSE broadcast deliveries dropped before enqueue.")
				.setUnit("{delivery}")
				.build();

		this.mcpServerStartsCounter = meter.counterBuilder("soklet.mcp.server.starts")
				.setDescription("Total successful MCP server starts.")
				.setUnit("{start}")
				.build();
		this.mcpShutdownsCounter = meter.counterBuilder("soklet.mcp.shutdowns")
				.setDescription("Total MCP server shutdowns by outcome.")
				.setUnit("{shutdown}")
				.build();
		this.mcpConnectionsAcceptedCounter = meter.counterBuilder("soklet.mcp.connections.accepted")
				.setDescription("Total accepted MCP connections admitted within the connection-capacity bound.")
				.setUnit("{connection}")
				.build();
		this.mcpConnectionsRejectedCounter = meter.counterBuilder("soklet.mcp.connections.rejected")
				.setDescription("Total MCP connections rejected because the connection-capacity bound was full.")
				.setUnit("{connection}")
				.build();
		this.mcpRequestsAcceptedCounter = meter.counterBuilder("soklet.mcp.requests.accepted")
				.setDescription("Total MCP requests accepted by the bounded protocol processor.")
				.setUnit("{request}")
				.build();
		this.mcpRequestsRejectedCounter = meter.counterBuilder("soklet.mcp.requests.rejected")
				.setDescription("Total MCP requests rejected before admitted semantic handling.")
				.setUnit("{request}")
				.build();
		this.mcpActiveRequestsCounter = meter.upDownCounterBuilder("soklet.mcp.requests.active")
				.setDescription("Currently active admitted MCP requests.")
				.setUnit("{request}")
				.build();
		this.mcpRequestsCompletedCounter = meter.counterBuilder("soklet.mcp.requests.completed")
				.setDescription("Total completed MCP requests.")
				.setUnit("{request}")
				.build();
		this.mcpRequestDurationHistogram = meter.histogramBuilder("soklet.mcp.request.duration")
				.setDescription("MCP request duration.")
				.setUnit("s")
				.setExplicitBucketBoundariesAdvice(MCP_REQUEST_DURATION_BUCKET_BOUNDARIES)
				.build();
		this.mcpActiveRequestStreamsCounter = meter.upDownCounterBuilder("soklet.mcp.request.streams.active")
				.setDescription("Currently active MCP request streams.")
				.setUnit("{stream}")
				.build();
		this.mcpRequestStreamDurationHistogram = meter.histogramBuilder("soklet.mcp.request.stream.duration")
				.setDescription("MCP request-stream duration.")
				.setUnit("s")
				.setExplicitBucketBoundariesAdvice(MCP_LONG_LIVED_DURATION_BUCKET_BOUNDARIES)
				.build();
		this.mcpActiveSubscriptionsCounter = meter.upDownCounterBuilder("soklet.mcp.subscriptions.active")
				.setDescription("Currently active MCP subscriptions.")
				.setUnit("{subscription}")
				.build();
		this.mcpSubscriptionDurationHistogram = meter.histogramBuilder("soklet.mcp.subscription.duration")
				.setDescription("MCP subscription duration.")
				.setUnit("s")
				.setExplicitBucketBoundariesAdvice(MCP_LONG_LIVED_DURATION_BUCKET_BOUNDARIES)
				.build();
		this.mcpCancelationsSignaledCounter = meter.counterBuilder("soklet.mcp.cancelations.signaled")
				.setDescription("Total cooperative MCP request cancelations signaled by endpoint and method.")
				.setUnit("{cancelation}")
				.build();
		this.mcpProgressEmittedCounter = meter.counterBuilder("soklet.mcp.progress.emitted")
				.setDescription("Total MCP progress notifications accepted for delivery by endpoint and method.")
				.setUnit("{notification}")
				.build();
		this.mcpKeepAlivesEmittedCounter = meter.counterBuilder("soklet.mcp.keepalives.emitted")
				.setDescription("Total MCP keep-alive comments accepted for delivery.")
				.setUnit("{comment}")
				.build();
		this.mcpProtocolErrorsCounter = meter.counterBuilder("soklet.mcp.protocol.errors")
				.setDescription("Total client-visible MCP protocol errors by fixed code.")
				.setUnit("{error}")
				.build();
		this.mcpUnknownMirroredHeadersCounter = meter.counterBuilder("soklet.mcp.unknown.mirrored.headers")
				.setDescription("Total unknown MCP mirrored-header occurrences by endpoint and method.")
				.setUnit("{header}")
				.build();
		this.mcpActiveHandlerExecutionsCounter = meter.upDownCounterBuilder("soklet.mcp.handler.executions.active")
				.setDescription("Currently occupied MCP handler-execution slots.")
				.setUnit("{handler}")
				.build();
		this.mcpHandlerQueueDepthCounter = meter.upDownCounterBuilder("soklet.mcp.handler.queue.depth")
				.setDescription("MCP application requests waiting for a handler slot.")
				.setUnit("{request}")
				.build();
		this.mcpHandlerCapacityRejectionsCounter = meter.counterBuilder("soklet.mcp.handler.capacity.rejections")
				.setDescription("Total MCP requests rejected because the handler queue was full.")
				.setUnit("{request}")
				.build();
	}

	@Override
	public void didAcceptConnection(@NonNull ServerType serverType,
																	@Nullable InetSocketAddress remoteAddress) {
		requireNonNull(serverType);
		this.connectionsAcceptedCounter.add(1, serverTypeAttributes(serverType));
	}

	@Override
	public void didFailToAcceptConnection(@NonNull ServerType serverType,
																				@Nullable InetSocketAddress remoteAddress,
																				@NonNull ConnectionRejectionReason reason,
																				@Nullable Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(reason);
		this.connectionsRejectedCounter.add(1, serverTypeAndReasonAttributes(serverType, reason));
	}

	@Override
	public void didAcceptRequest(@NonNull ServerType serverType,
															 @Nullable InetSocketAddress remoteAddress,
															 @Nullable String requestTarget) {
		requireNonNull(serverType);
		this.requestsAcceptedCounter.add(1, serverTypeAttributes(serverType));
	}

	@Override
	public void didFailToAcceptRequest(@NonNull ServerType serverType,
																		 @Nullable InetSocketAddress remoteAddress,
																		 @Nullable String requestTarget,
																		 @NonNull RequestRejectionReason reason,
																		 @Nullable Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(reason);
		this.requestsRejectedCounter.add(1, serverTypeAndReasonAttributes(serverType, reason));
	}

	@Override
	public void didFailToReadRequest(@NonNull ServerType serverType,
																	 @Nullable InetSocketAddress remoteAddress,
																	 @Nullable String requestTarget,
																	 @NonNull RequestReadFailureReason reason,
																	 @Nullable Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(reason);
		this.requestReadFailureCounter.add(1, serverTypeAndReasonAttributes(serverType, reason));
	}

	@Override
	public void didRecordTransportFailure(@NonNull ServerType serverType,
																@NonNull TransportFailureReason reason,
																@Nullable Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(reason);
		this.transportFailureCounter.add(1, transportFailureAttributes(serverType, reason, throwable));
	}

	@Override
	public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
		requireNonNull(event);

		if (event instanceof McpMetricsEvent.ServerStarted) {
			this.mcpServerStartsCounter.add(1);
		} else if (event instanceof McpMetricsEvent.ServerStopped serverStopped) {
			this.mcpShutdownsCounter.add(1, Attributes.of(
					MCP_SHUTDOWN_OUTCOME_ATTRIBUTE_KEY,
					mcpShutdownOutcome(
							serverStopped.getShutdownComponentDisposition())));
		} else if (event instanceof McpMetricsEvent.ConnectionAccepted) {
			this.mcpConnectionsAcceptedCounter.add(1);
		} else if (event instanceof McpMetricsEvent.ConnectionRejected) {
			this.mcpConnectionsRejectedCounter.add(1);
		} else if (event instanceof McpMetricsEvent.RequestAccepted) {
			this.mcpRequestsAcceptedCounter.add(1);
		} else if (event instanceof McpMetricsEvent.RequestRejected) {
			this.mcpRequestsRejectedCounter.add(1);
		} else if (event instanceof McpMetricsEvent.RequestStarted) {
			this.mcpActiveRequestsCounter.add(1);
		} else if (event instanceof McpMetricsEvent.RequestFinished requestFinished) {
			Attributes attributes = mcpRequestAttributes(
					requestFinished.getEndpointPath(),
					requestFinished.getJsonRpcMethod(), requestFinished.getOutcome());
			double durationSeconds = safeSeconds(requestFinished.getDuration());
			this.mcpActiveRequestsCounter.add(-1);
			this.mcpRequestsCompletedCounter.add(1, attributes);
			this.mcpRequestDurationHistogram.record(durationSeconds, attributes);
		} else if (event instanceof McpMetricsEvent.RequestStreamOpened) {
			this.mcpActiveRequestStreamsCounter.add(1);
		} else if (event instanceof McpMetricsEvent.RequestStreamClosed requestStreamClosed) {
			Attributes attributes = mcpRequestStreamAttributes(
					requestStreamClosed.getEndpointPath(),
					requestStreamClosed.getJsonRpcMethod(),
					requestStreamClosed.getReason());
			double durationSeconds = safeSeconds(requestStreamClosed.getDuration());
			this.mcpActiveRequestStreamsCounter.add(-1);
			this.mcpRequestStreamDurationHistogram.record(durationSeconds, attributes);
		} else if (event instanceof McpMetricsEvent.SubscriptionOpened) {
			this.mcpActiveSubscriptionsCounter.add(1);
		} else if (event instanceof McpMetricsEvent.SubscriptionClosed subscriptionClosed) {
			Attributes attributes = mcpSubscriptionAttributes(
					subscriptionClosed.getEndpointPath(),
					subscriptionClosed.getReason());
			double durationSeconds = safeSeconds(subscriptionClosed.getDuration());
			this.mcpActiveSubscriptionsCounter.add(-1);
			this.mcpSubscriptionDurationHistogram.record(durationSeconds, attributes);
		} else if (event instanceof McpMetricsEvent.CancelationSignaled cancelationSignaled) {
			this.mcpCancelationsSignaledCounter.add(1, mcpEndpointMethodAttributes(
					cancelationSignaled.getEndpointPath(),
					cancelationSignaled.getJsonRpcMethod()));
		} else if (event instanceof McpMetricsEvent.ProgressEmitted progressEmitted) {
			this.mcpProgressEmittedCounter.add(1, mcpEndpointMethodAttributes(
					progressEmitted.getEndpointPath(),
					progressEmitted.getJsonRpcMethod()));
		} else if (event instanceof McpMetricsEvent.KeepAliveEmitted) {
			this.mcpKeepAlivesEmittedCounter.add(1);
		} else if (event instanceof McpMetricsEvent.ProtocolError protocolError) {
			this.mcpProtocolErrorsCounter.add(1, Attributes.of(
					RPC_JSON_RPC_ERROR_CODE_ATTRIBUTE_KEY,
					protocolError.getCode().longValue()));
		} else if (event instanceof McpMetricsEvent.UnknownMirroredHeader unknownMirroredHeader) {
			this.mcpUnknownMirroredHeadersCounter.add(1, mcpEndpointMethodAttributes(
					unknownMirroredHeader.getEndpointPath(),
					unknownMirroredHeader.getJsonRpcMethod()));
		} else if (event instanceof McpMetricsEvent.HandlerExecutionStarted) {
			this.mcpActiveHandlerExecutionsCounter.add(1);
		} else if (event instanceof McpMetricsEvent.HandlerExecutionFinished) {
			this.mcpActiveHandlerExecutionsCounter.add(-1);
		} else if (event instanceof McpMetricsEvent.HandlerQueued) {
			this.mcpHandlerQueueDepthCounter.add(1);
		} else if (event instanceof McpMetricsEvent.HandlerDequeued) {
			this.mcpHandlerQueueDepthCounter.add(-1);
		} else if (event instanceof McpMetricsEvent.HandlerCapacityRejected) {
			this.mcpHandlerCapacityRejectionsCounter.add(1);
		} else if (event instanceof McpMetricsEvent.TransportFailure transportFailure) {
			this.transportFailureCounter.add(1, Attributes.of(
					SERVER_TYPE_ATTRIBUTE_KEY, "mcp",
					FAILURE_REASON_ATTRIBUTE_KEY,
					enumValue(transportFailure.getReason())));
		}
	}

	@NonNull
	private static String mcpShutdownOutcome(
			@NonNull ShutdownComponentDisposition outcome) {
		requireNonNull(outcome);
		return switch (outcome) {
			case NOT_STARTED -> "not_started";
			case GRACEFUL_TERMINATION -> "graceful_termination";
			case FORCED_TERMINATION -> "forced_termination";
			case UNEXPECTED_TERMINATION -> "unexpected_termination";
			case RESIDUAL_ACTIVITY -> "residual_activity";
			case TERMINATION_UNKNOWN -> "termination_unknown";
		};
	}

	@Override
	public void didStartRequestHandling(@NonNull ServerType serverType,
																			@NonNull Request request,
																			@Nullable ResourceMethod resourceMethod) {
		requireNonNull(serverType);
		requireNonNull(request);

		this.activeRequestsCounter.add(1, activeRequestAttributes(serverType, request));
	}

	@Override
	public void didFinishRequestHandling(@NonNull ServerType serverType,
																			 @NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod,
																			 @NonNull MarshaledResponse marshaledResponse,
																			 @NonNull Duration duration,
																			 @NonNull List<@NonNull Throwable> throwables) {
		requireNonNull(serverType);
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(duration);
		requireNonNull(throwables);

		Throwable throwable = throwables.isEmpty() ? null : throwables.get(0);
		Attributes attributes = requestAttributes(serverType, request, resourceMethod, marshaledResponse.getStatusCode(), throwable);

		this.activeRequestsCounter.add(-1, activeRequestAttributes(serverType, request));
		this.requestDurationHistogram.record(seconds(duration), attributes);
		long requestBodySizeInBytes = this.metricNamingStrategy == MetricNamingStrategy.SEMCONV
				? request.getEncodedBodySizeInBytes().longValue()
				: request.getBody().map(body -> (long) body.length).orElse(0L);

		if (this.metricNamingStrategy != MetricNamingStrategy.SEMCONV
				|| !request.isContentTooLarge()
				|| requestBodySizeInBytes > 0)
			this.requestBodySizeHistogram.record(requestBodySizeInBytes, attributes);
		this.responseBodySizeHistogram.record(marshaledResponse.getBodyLength(), attributes);

		if (!throwables.isEmpty())
			this.requestThrowableCounter.add(throwables.size(), attributes);
	}

	@Override
	public void didWriteResponse(@NonNull ServerType serverType,
															 @NonNull Request request,
															 @Nullable ResourceMethod resourceMethod,
															 @NonNull MarshaledResponse marshaledResponse,
															 @NonNull Duration responseWriteDuration) {
		requireNonNull(serverType);
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(responseWriteDuration);

		this.responseWriteDurationHistogram.record(
				seconds(responseWriteDuration),
				requestAttributes(serverType, request, resourceMethod, marshaledResponse.getStatusCode(), null)
		);
	}

	@Override
	public void didFailToWriteResponse(@NonNull ServerType serverType,
																		 @NonNull Request request,
																		 @Nullable ResourceMethod resourceMethod,
																		 @NonNull MarshaledResponse marshaledResponse,
																		 @NonNull Duration responseWriteDuration,
																		 @NonNull Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(responseWriteDuration);
		requireNonNull(throwable);

		Attributes attributes = requestAttributes(serverType, request, resourceMethod, marshaledResponse.getStatusCode(), throwable);
		this.responseWriteFailureCounter.add(1, attributes);
		this.responseWriteDurationHistogram.record(seconds(responseWriteDuration), attributes);
	}

	@Override
	public void didEstablishSseConnection(@NonNull SseConnection sseConnection) {
		requireNonNull(sseConnection);

		Attributes attributes = serverSentEventAttributes(sseConnection);
		this.activeServerSentEventStreamsCounter.add(1, attributes);
		this.serverSentEventStreamsEstablishedCounter.add(1, attributes);
	}

	@Override
	public void didFailToEstablishSseConnection(@NonNull Request request,
																							@Nullable ResourceMethod resourceMethod,
																							SseConnection.@NonNull HandshakeFailureReason reason,
																							@Nullable Throwable throwable) {
		requireNonNull(request);
		requireNonNull(reason);

		this.serverSentEventHandshakeFailureCounter.add(1,
				Attributes.builder()
						.put(HTTP_METHOD_ATTRIBUTE_KEY, request.getHttpMethod().name())
						.put(HTTP_ROUTE_ATTRIBUTE_KEY, routeFor(resourceMethod))
						.put(FAILURE_REASON_ATTRIBUTE_KEY, enumValue(reason))
						.build()
		);
	}

	@Override
	public void didTerminateSseConnection(@NonNull SseConnection sseConnection,
																				@NonNull StreamTermination termination) {
		requireNonNull(sseConnection);
		requireNonNull(termination);

		Attributes routeAttributes = serverSentEventAttributes(sseConnection);
		Attributes durationAttributes = Attributes.builder()
				.putAll(routeAttributes)
				.put(SSE_TERMINATION_REASON_ATTRIBUTE_KEY, enumValue(termination.getReason()))
				.build();

		this.activeServerSentEventStreamsCounter.add(-1, routeAttributes);
		this.serverSentEventStreamsTerminatedCounter.add(1, durationAttributes);
		this.serverSentEventStreamDurationHistogram.record(seconds(termination.getDuration()), durationAttributes);
	}

	@Override
	public void didWriteSseEvent(@NonNull SseConnection sseConnection,
															 @NonNull SseEvent sseEvent,
															 @NonNull Duration writeDuration,
															 @Nullable Duration deliveryLag,
															 @Nullable Integer payloadBytes,
															 @Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseEvent);
		requireNonNull(writeDuration);

		Attributes attributes = serverSentEventAttributes(sseConnection);
		this.serverSentEventWrittenCounter.add(1, attributes);
		this.serverSentEventWriteDurationHistogram.record(seconds(writeDuration), attributes);

		if (deliveryLag != null)
			this.serverSentEventDeliveryLagHistogram.record(seconds(deliveryLag), attributes);
		if (payloadBytes != null)
			this.serverSentEventPayloadSizeHistogram.record(payloadBytes, attributes);
		if (queueDepth != null)
			this.serverSentEventQueueDepthHistogram.record(queueDepth, attributes);
	}

	@Override
	public void didFailToWriteSseEvent(@NonNull SseConnection sseConnection,
																		 @NonNull SseEvent sseEvent,
																		 @NonNull Duration writeDuration,
																		 @NonNull Throwable throwable,
																		 @Nullable Duration deliveryLag,
																		 @Nullable Integer payloadBytes,
																		 @Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseEvent);
		requireNonNull(writeDuration);
		requireNonNull(throwable);

		Attributes attributes = serverSentEventAttributes(sseConnection);
		this.serverSentEventWriteFailureCounter.add(1, attributes);
		this.serverSentEventWriteDurationHistogram.record(seconds(writeDuration), attributes);

		if (deliveryLag != null)
			this.serverSentEventDeliveryLagHistogram.record(seconds(deliveryLag), attributes);
		if (payloadBytes != null)
			this.serverSentEventPayloadSizeHistogram.record(payloadBytes, attributes);
		if (queueDepth != null)
			this.serverSentEventQueueDepthHistogram.record(queueDepth, attributes);
	}

	@Override
	public void didDropSseEvent(@NonNull SseConnection sseConnection,
															@NonNull SseEvent sseEvent,
															@NonNull SseEventDropReason reason,
															@Nullable Integer payloadBytes,
															@Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseEvent);
		requireNonNull(reason);

		Attributes attributes = Attributes.builder()
				.putAll(serverSentEventAttributes(sseConnection))
				.put(SSE_DROP_REASON_ATTRIBUTE_KEY, enumValue(reason))
				.build();
		this.serverSentEventDropCounter.add(1, attributes);

		if (payloadBytes != null)
			this.serverSentEventPayloadSizeHistogram.record(payloadBytes, attributes);
		if (queueDepth != null)
			this.serverSentEventQueueDepthHistogram.record(queueDepth, attributes);
	}

	@Override
	public void didWriteSseComment(@NonNull SseConnection sseConnection,
																 @NonNull SseComment sseComment,
																 @NonNull Duration writeDuration,
																 @Nullable Duration deliveryLag,
																 @Nullable Integer payloadBytes,
																 @Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseComment);
		requireNonNull(writeDuration);

		Attributes attributes = serverSentEventCommentAttributes(sseConnection, sseComment.getCommentType());
		this.serverSentEventCommentWrittenCounter.add(1, attributes);
		this.serverSentEventCommentWriteDurationHistogram.record(seconds(writeDuration), attributes);

		if (deliveryLag != null)
			this.serverSentEventCommentDeliveryLagHistogram.record(seconds(deliveryLag), attributes);
		if (payloadBytes != null)
			this.serverSentEventCommentPayloadSizeHistogram.record(payloadBytes, attributes);
		if (queueDepth != null)
			this.serverSentEventCommentQueueDepthHistogram.record(queueDepth, attributes);
	}

	@Override
	public void didFailToWriteSseComment(@NonNull SseConnection sseConnection,
																			 @NonNull SseComment sseComment,
																			 @NonNull Duration writeDuration,
																			 @NonNull Throwable throwable,
																			 @Nullable Duration deliveryLag,
																			 @Nullable Integer payloadBytes,
																			 @Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseComment);
		requireNonNull(writeDuration);
		requireNonNull(throwable);

		Attributes attributes = serverSentEventCommentAttributes(sseConnection, sseComment.getCommentType());
		this.serverSentEventCommentWriteFailureCounter.add(1, attributes);
		this.serverSentEventCommentWriteDurationHistogram.record(seconds(writeDuration), attributes);

		if (deliveryLag != null)
			this.serverSentEventCommentDeliveryLagHistogram.record(seconds(deliveryLag), attributes);
		if (payloadBytes != null)
			this.serverSentEventCommentPayloadSizeHistogram.record(payloadBytes, attributes);
		if (queueDepth != null)
			this.serverSentEventCommentQueueDepthHistogram.record(queueDepth, attributes);
	}

	@Override
	public void didDropSseComment(@NonNull SseConnection sseConnection,
																@NonNull SseComment sseComment,
																@NonNull SseEventDropReason reason,
																@Nullable Integer payloadBytes,
																@Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseComment);
		requireNonNull(reason);

		Attributes attributes = Attributes.builder()
				.putAll(serverSentEventCommentAttributes(sseConnection, sseComment.getCommentType()))
				.put(SSE_DROP_REASON_ATTRIBUTE_KEY, enumValue(reason))
				.build();
		this.serverSentEventCommentDropCounter.add(1, attributes);

		if (payloadBytes != null)
			this.serverSentEventCommentPayloadSizeHistogram.record(payloadBytes, attributes);
		if (queueDepth != null)
			this.serverSentEventCommentQueueDepthHistogram.record(queueDepth, attributes);
	}

	@Override
	public void didBroadcastSseEvent(@NonNull ResourcePathDeclaration route,
																	 int attempted,
																	 int enqueued,
																	 int dropped) {
		requireNonNull(route);
		recordBroadcastTotals(route, BROADCAST_PAYLOAD_EVENT, UNKNOWN_COMMENT_TYPE, attempted, enqueued, dropped);
	}

	@Override
	public void didBroadcastSseComment(@NonNull ResourcePathDeclaration route,
																		 SseComment.@NonNull CommentType commentType,
																		 int attempted,
																		 int enqueued,
																		 int dropped) {
		requireNonNull(route);
		requireNonNull(commentType);
		recordBroadcastTotals(route, BROADCAST_PAYLOAD_COMMENT, enumValue(commentType), attempted, enqueued, dropped);
	}

	@NonNull
	private Attributes serverTypeAttributes(@NonNull ServerType serverType) {
		requireNonNull(serverType);
		return Attributes.of(SERVER_TYPE_ATTRIBUTE_KEY, enumValue(serverType));
	}

	@NonNull
	private Attributes serverTypeAndReasonAttributes(@NonNull ServerType serverType,
																									 @NonNull Enum<?> reason) {
		requireNonNull(serverType);
		requireNonNull(reason);
		return Attributes.builder()
				.put(SERVER_TYPE_ATTRIBUTE_KEY, enumValue(serverType))
				.put(FAILURE_REASON_ATTRIBUTE_KEY, enumValue(reason))
				.build();
	}

	@NonNull
	private Attributes transportFailureAttributes(@NonNull ServerType serverType,
																								@NonNull TransportFailureReason reason,
																								@Nullable Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(reason);

		var builder = Attributes.builder()
				.put(SERVER_TYPE_ATTRIBUTE_KEY, enumValue(serverType))
				.put(FAILURE_REASON_ATTRIBUTE_KEY, enumValue(reason));

		if (throwable != null)
			builder.put(ERROR_TYPE_ATTRIBUTE_KEY, throwable.getClass().getName());

		return builder.build();
	}

	@NonNull
	private Attributes activeRequestAttributes(@NonNull ServerType serverType,
																						 @NonNull Request request) {
		requireNonNull(serverType);
		requireNonNull(request);

		var builder = Attributes.builder()
				.put(HTTP_METHOD_ATTRIBUTE_KEY, request.getHttpMethod().name());

		if (this.metricNamingStrategy == MetricNamingStrategy.SEMCONV) {
			builder.put(URL_SCHEME_ATTRIBUTE_KEY, URL_SCHEME_HTTP);
		} else {
			builder.put(SERVER_TYPE_ATTRIBUTE_KEY, enumValue(serverType));
		}

		return builder.build();
	}

	@NonNull
	private Attributes requestAttributes(@NonNull ServerType serverType,
																			 @NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod,
																			 @Nullable Integer statusCode,
																			 @Nullable Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(request);

		var builder = Attributes.builder()
				.put(HTTP_METHOD_ATTRIBUTE_KEY, request.getHttpMethod().name());

		if (this.metricNamingStrategy == MetricNamingStrategy.SEMCONV) {
			builder.put(URL_SCHEME_ATTRIBUTE_KEY, URL_SCHEME_HTTP);

			if (resourceMethod != null)
				builder.put(HTTP_ROUTE_ATTRIBUTE_KEY, routeFor(resourceMethod));
		} else {
			builder.put(SERVER_TYPE_ATTRIBUTE_KEY, enumValue(serverType))
					.put(HTTP_ROUTE_ATTRIBUTE_KEY, routeFor(resourceMethod));
		}

		if (statusCode != null)
			builder.put(HTTP_STATUS_CODE_ATTRIBUTE_KEY, statusCode.longValue());

		String errorType = errorTypeFor(statusCode, throwable);

		if (errorType != null)
			builder.put(ERROR_TYPE_ATTRIBUTE_KEY, errorType);

		return builder.build();
	}

	@NonNull
	private static Attributes mcpEndpointMethodAttributes(@NonNull String endpointPath,
																							@NonNull String jsonRpcMethod) {
		requireNonNull(endpointPath);
		requireNonNull(jsonRpcMethod);
		return Attributes.of(
				MCP_ENDPOINT_ATTRIBUTE_KEY, endpointPath,
				RPC_METHOD_ATTRIBUTE_KEY, jsonRpcMethod);
	}

	@NonNull
	private static Attributes mcpRequestAttributes(@NonNull String endpointPath,
																			@NonNull String jsonRpcMethod,
																			@NonNull Enum<?> outcome) {
		requireNonNull(outcome);
		return Attributes.builder()
				.putAll(mcpEndpointMethodAttributes(endpointPath, jsonRpcMethod))
				.put(MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY, enumValue(outcome))
				.build();
	}

	@NonNull
	private static Attributes mcpRequestStreamAttributes(@NonNull String endpointPath,
																						@NonNull String jsonRpcMethod,
																						@NonNull Enum<?> reason) {
		requireNonNull(reason);
		return Attributes.builder()
				.putAll(mcpEndpointMethodAttributes(endpointPath, jsonRpcMethod))
				.put(MCP_STREAM_TERMINATION_REASON_ATTRIBUTE_KEY, enumValue(reason))
				.build();
	}

	@NonNull
	private static Attributes mcpSubscriptionAttributes(@NonNull String endpointPath,
																					@NonNull Enum<?> reason) {
		requireNonNull(endpointPath);
		requireNonNull(reason);
		return Attributes.of(
				MCP_ENDPOINT_ATTRIBUTE_KEY, endpointPath,
				MCP_SUBSCRIPTION_TERMINATION_REASON_ATTRIBUTE_KEY, enumValue(reason));
	}

	@NonNull
	private Attributes serverSentEventAttributes(@NonNull SseConnection sseConnection) {
		requireNonNull(sseConnection);
		return Attributes.of(
				HTTP_ROUTE_ATTRIBUTE_KEY,
				routeFor(sseConnection.getResourceMethod())
		);
	}

	@NonNull
	private Attributes serverSentEventCommentAttributes(@NonNull SseConnection sseConnection,
																											SseComment.@NonNull CommentType commentType) {
		requireNonNull(sseConnection);
		requireNonNull(commentType);
		return Attributes.builder()
				.putAll(serverSentEventAttributes(sseConnection))
				.put(SSE_COMMENT_TYPE_ATTRIBUTE_KEY, enumValue(commentType))
				.build();
	}

	private void recordBroadcastTotals(@NonNull ResourcePathDeclaration route,
																		 @NonNull String payloadType,
																		 @NonNull String commentType,
																		 int attempted,
																		 int enqueued,
																		 int dropped) {
		requireNonNull(route);
		requireNonNull(payloadType);
		requireNonNull(commentType);

		Attributes attributes = Attributes.builder()
				.put(HTTP_ROUTE_ATTRIBUTE_KEY, route.getPath())
				.put(SSE_BROADCAST_PAYLOAD_TYPE_ATTRIBUTE_KEY, payloadType)
				.put(SSE_COMMENT_TYPE_ATTRIBUTE_KEY, commentType)
				.build();

		if (attempted > 0)
			this.serverSentEventBroadcastAttemptCounter.add(attempted, attributes);
		if (enqueued > 0)
			this.serverSentEventBroadcastEnqueuedCounter.add(enqueued, attributes);
		if (dropped > 0)
			this.serverSentEventBroadcastDroppedCounter.add(dropped, attributes);
	}

	@NonNull
	private static String activeRequestsMetricNameFor(@NonNull MetricNamingStrategy metricNamingStrategy) {
		requireNonNull(metricNamingStrategy);
		if (metricNamingStrategy == MetricNamingStrategy.SEMCONV)
			return "http.server.active_requests";
		return "soklet.server.requests.active";
	}

	@NonNull
	private static String requestDurationMetricNameFor(@NonNull MetricNamingStrategy metricNamingStrategy) {
		requireNonNull(metricNamingStrategy);
		if (metricNamingStrategy == MetricNamingStrategy.SEMCONV)
			return "http.server.request.duration";
		return "soklet.server.request.duration";
	}

	@NonNull
	private static String requestBodySizeMetricNameFor(@NonNull MetricNamingStrategy metricNamingStrategy) {
		requireNonNull(metricNamingStrategy);
		if (metricNamingStrategy == MetricNamingStrategy.SEMCONV)
			return "http.server.request.body.size";
		return "soklet.server.request.body.size";
	}

	@NonNull
	private static String requestBodySizeDescriptionFor(@NonNull MetricNamingStrategy metricNamingStrategy) {
		requireNonNull(metricNamingStrategy);
		if (metricNamingStrategy == MetricNamingStrategy.SEMCONV)
			return "Encoded request payload body size in bytes as transferred, excluding headers and transfer framing.";
		return "Request body size in bytes as observed by handlers.";
	}

	@NonNull
	private static String responseBodySizeMetricNameFor(@NonNull MetricNamingStrategy metricNamingStrategy) {
		requireNonNull(metricNamingStrategy);
		if (metricNamingStrategy == MetricNamingStrategy.SEMCONV)
			return "http.server.response.body.size";
		return "soklet.server.response.body.size";
	}

	@NonNull
	private static String routeFor(@Nullable ResourceMethod resourceMethod) {
		if (resourceMethod == null)
			return UNMATCHED_ROUTE;
		return resourceMethod.getResourcePathDeclaration().getPath();
	}

	@NonNull
	private static String enumValue(@NonNull Enum<?> value) {
		requireNonNull(value);
		return value.name().toLowerCase(Locale.ROOT);
	}

	@Nullable
	private String errorTypeFor(@Nullable Integer statusCode,
															@Nullable Throwable throwable) {
		if (throwable != null)
			return throwable.getClass().getName();

		if (this.metricNamingStrategy == MetricNamingStrategy.SEMCONV
				&& statusCode != null
				&& statusCode >= 500)
			return String.valueOf(statusCode);

		return null;
	}

	private static double seconds(@NonNull Duration duration) {
		requireNonNull(duration);
		return duration.toNanos() / 1_000_000_000D;
	}

	private static double safeSeconds(@NonNull Duration duration) {
		requireNonNull(duration);
		return duration.getSeconds() + duration.getNano() / 1_000_000_000D;
	}

	/**
	 * Naming strategy for HTTP metric instrument names.
	 * <p>
	 * SSE- and Soklet-specific concepts remain under the {@code soklet.*} namespace in all strategies.
	 */
	public enum MetricNamingStrategy {
		/**
		 * Use OpenTelemetry Semantic Convention names for standard HTTP server metrics.
		 * Request-body size records the encoded payload size as transferred.
		 */
		SEMCONV,
		/**
		 * Use {@code soklet.*} names for all metrics.
		 * Request-body size records the body size visible to handlers.
		 */
		SOKLET
	}

	/**
	 * Builder used to construct instances of {@link OpenTelemetryMetricsCollector}.
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nullable
		private Meter meter;
		@Nullable
		private OpenTelemetry openTelemetry;
		@NonNull
		private MetricNamingStrategy metricNamingStrategy;
		@NonNull
		private String instrumentationName;
		@Nullable
		private String instrumentationVersion;

		private Builder() {
			this.openTelemetry = GlobalOpenTelemetry.get();
			this.metricNamingStrategy = MetricNamingStrategy.SEMCONV;
			this.instrumentationName = DEFAULT_INSTRUMENTATION_NAME;
			this.instrumentationVersion = null;
		}

		/**
		 * Sets a specific meter to use for metric instruments.
		 *
		 * @param meter the meter to use
		 * @return this builder
		 */
		@NonNull
		public Builder meter(@NonNull Meter meter) {
			this.meter = requireNonNull(meter);
			return this;
		}

		/**
		 * Sets the OpenTelemetry API object used to construct a meter if {@link #meter(Meter)} is not set.
		 *
		 * @param openTelemetry the OpenTelemetry instance
		 * @return this builder
		 */
		@NonNull
		public Builder openTelemetry(@NonNull OpenTelemetry openTelemetry) {
			this.openTelemetry = requireNonNull(openTelemetry);
			return this;
		}

		/**
		 * Sets the naming strategy for HTTP metrics.
		 *
		 * @param metricNamingStrategy the naming strategy
		 * @return this builder
		 */
		@NonNull
		public Builder metricNamingStrategy(@NonNull MetricNamingStrategy metricNamingStrategy) {
			this.metricNamingStrategy = requireNonNull(metricNamingStrategy);
			return this;
		}

		/**
		 * Sets the instrumentation scope name to use when constructing a meter.
		 *
		 * @param instrumentationName the instrumentation scope name
		 * @return this builder
		 */
		@NonNull
		public Builder instrumentationName(@NonNull String instrumentationName) {
			this.instrumentationName = requireNonNull(instrumentationName);
			return this;
		}

		/**
		 * Sets an optional instrumentation scope version to use when constructing a meter.
		 *
		 * @param instrumentationVersion the instrumentation scope version, or {@code null}
		 * @return this builder
		 */
		@NonNull
		public Builder instrumentationVersion(@Nullable String instrumentationVersion) {
			this.instrumentationVersion = instrumentationVersion;
			return this;
		}

		@NonNull
		private Meter resolveMeter() {
			if (this.meter != null)
				return this.meter;

			MeterBuilder meterBuilder = requireNonNull(this.openTelemetry).meterBuilder(this.instrumentationName);

			if (this.instrumentationVersion != null)
				meterBuilder = meterBuilder.setInstrumentationVersion(this.instrumentationVersion);

			return meterBuilder.build();
		}

		/**
		 * Builds the collector.
		 *
		 * @return the collector instance
		 */
		@NonNull
		public OpenTelemetryMetricsCollector build() {
			return new OpenTelemetryMetricsCollector(this);
		}
	}
}
