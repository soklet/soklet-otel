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
import com.soklet.McpEndpoint;
import com.soklet.McpSessionTerminationReason;
import com.soklet.MetricsCollector;
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
	private static final AttributeKey<String> MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> MCP_SESSION_TERMINATION_REASON_ATTRIBUTE_KEY;
	@NonNull
	private static final List<Double> LONG_LIVED_DURATION_BUCKET_BOUNDARIES;

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
		MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.endpoint.class");
		MCP_SESSION_TERMINATION_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.session.termination.reason");
		// SSE streams and MCP sessions live for minutes-to-hours (MCP's default idle timeout is 24 hours),
		// so OpenTelemetry's request-oriented default buckets (which top out at 10 seconds) would collapse
		// nearly all measurements into the +Inf bucket. Advise boundaries suited to those lifetimes instead.
		LONG_LIVED_DURATION_BUCKET_BOUNDARIES = List.of(1D, 10D, 60D, 300D, 1_800D, 3_600D, 14_400D, 86_400D);
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
	private final LongUpDownCounter activeMcpSessionsCounter;
	@NonNull
	private final LongCounter mcpSessionsCreatedCounter;
	@NonNull
	private final LongCounter mcpSessionsTerminatedCounter;
	@NonNull
	private final DoubleHistogram mcpSessionDurationHistogram;

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

		this.activeMcpSessionsCounter = meter.upDownCounterBuilder("soklet.mcp.sessions.active")
				.setDescription("Number of active MCP sessions.")
				.setUnit("{session}")
				.build();
		this.mcpSessionsCreatedCounter = meter.counterBuilder("soklet.mcp.sessions.created")
				.setDescription("Total number of MCP sessions created.")
				.setUnit("{session}")
				.build();
		this.mcpSessionsTerminatedCounter = meter.counterBuilder("soklet.mcp.sessions.terminated")
				.setDescription("Total number of MCP sessions terminated.")
				.setUnit("{session}")
				.build();
		this.mcpSessionDurationHistogram = meter.histogramBuilder("soklet.mcp.session.duration")
				.setDescription("MCP session lifetime from creation to termination.")
				.setUnit("s")
				.setExplicitBucketBoundariesAdvice(LONG_LIVED_DURATION_BUCKET_BOUNDARIES)
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

	@Override
	public void didCreateMcpSession(@NonNull Request request,
																	@NonNull Class<? extends McpEndpoint> endpointClass,
																	@NonNull String sessionId) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(sessionId);

		// The session ID is intentionally not emitted as an attribute: it is unbounded-cardinality.
		Attributes attributes = mcpSessionAttributes(endpointClass);
		this.activeMcpSessionsCounter.add(1, attributes);
		this.mcpSessionsCreatedCounter.add(1, attributes);
	}

	@Override
	public void didTerminateMcpSession(@NonNull Class<? extends McpEndpoint> endpointClass,
																		 @NonNull String sessionId,
																		 @NonNull Duration sessionDuration,
																		 @NonNull McpSessionTerminationReason terminationReason,
																		 @Nullable Throwable throwable) {
		requireNonNull(endpointClass);
		requireNonNull(sessionId);
		requireNonNull(sessionDuration);
		requireNonNull(terminationReason);

		// The active counter's decrement must use the SAME attribute set as didCreateMcpSession's
		// increment (endpoint class only - creation cannot know the eventual termination reason),
		// otherwise per-series values would never net back to zero.
		this.activeMcpSessionsCounter.add(-1, mcpSessionAttributes(endpointClass));

		Attributes terminationAttributes = Attributes.builder()
				.putAll(mcpSessionAttributes(endpointClass))
				.put(MCP_SESSION_TERMINATION_REASON_ATTRIBUTE_KEY, enumValue(terminationReason))
				.build();

		this.mcpSessionsTerminatedCounter.add(1, terminationAttributes);
		this.mcpSessionDurationHistogram.record(seconds(sessionDuration), terminationAttributes);
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
	private static Attributes mcpSessionAttributes(@NonNull Class<? extends McpEndpoint> endpointClass) {
		requireNonNull(endpointClass);
		return Attributes.of(MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY, endpointClass.getName());
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
