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

import com.soklet.HttpMethod;
import com.soklet.MarshaledResponse;
import com.soklet.McpMetricsEvent;
import com.soklet.McpRequestOutcome;
import com.soklet.McpStreamTerminationReason;
import com.soklet.MetricsCollector;
import com.soklet.ShutdownComponentDisposition;
import com.soklet.Request;
import com.soklet.RequestReadFailureReason;
import com.soklet.ResourceMethod;
import com.soklet.ResourcePathDeclaration;
import com.soklet.ServerType;
import com.soklet.SseComment;
import com.soklet.SseConnection;
import com.soklet.SseEvent;
import com.soklet.StreamTermination;
import com.soklet.StreamTerminationReason;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class OpenTelemetryMetricsCollectorTests {
	private static final AttributeKey<String> HTTP_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("http.request.method");
	private static final AttributeKey<String> SERVER_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.server.type");
	private static final AttributeKey<String> FAILURE_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.failure.reason");
	private static final AttributeKey<String> ERROR_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("error.type");
	private static final AttributeKey<String> SSE_DROP_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.sse.drop.reason");
	private static final AttributeKey<String> ROUTE_ATTRIBUTE_KEY = AttributeKey.stringKey("http.route");
	private static final AttributeKey<Long> STATUS_CODE_ATTRIBUTE_KEY = AttributeKey.longKey("http.response.status_code");
	private static final List<Double> LONG_LIVED_DURATION_BUCKET_BOUNDARIES = List.of(1D, 10D, 60D, 300D, 1_800D, 3_600D, 14_400D, 86_400D);
	private static final AttributeKey<String> MCP_ENDPOINT_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.endpoint");
	private static final AttributeKey<String> RPC_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("rpc.method");
	private static final AttributeKey<String> MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.request.outcome");
	private static final AttributeKey<String> MCP_STREAM_TERMINATION_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.stream.termination.reason");
	private static final AttributeKey<String> MCP_SUBSCRIPTION_TERMINATION_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.subscription.termination.reason");
	private static final AttributeKey<Long> MCP_PROTOCOL_ERROR_CODE_ATTRIBUTE_KEY = AttributeKey.longKey("rpc.jsonrpc.error_code");
	private static final AttributeKey<String> MCP_SHUTDOWN_OUTCOME_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.shutdown.outcome");
	private static final Set<String> MCP_SHUTDOWN_OUTCOMES = Set.of(
			"not_started", "graceful_termination", "forced_termination",
			"unexpected_termination", "residual_activity",
			"termination_unknown");
	private static final List<Double> MCP_REQUEST_DURATION_BUCKET_BOUNDARIES = List.of(
			0.001D, 0.002D, 0.005D, 0.010D, 0.025D, 0.050D,
			0.100D, 0.200D, 0.400D, 0.800D, 1.5D, 3D, 7D, 15D);
	private static final List<Double> MCP_STREAM_DURATION_BUCKET_BOUNDARIES = List.of(
			1D, 5D, 10D, 30D, 60D, 120D, 300D, 600D, 1_800D,
			3_600D, 7_200D, 14_400D);
	private static final String MCP_ENDPOINT = "/mcp";
	private static final String MCP_METHOD = "tools/call";

	@Test
	public void recordsHttpRequestAndResponseMetrics() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-http"))
				.build();

		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.POST, "/widgets/{id}", "widget");
		Request request = Request.withPath(HttpMethod.POST, "/widgets/123")
				.body("abcd".getBytes(StandardCharsets.UTF_8))
				.build();
		MarshaledResponse response = MarshaledResponse.withStatusCode(201)
				.body("created".getBytes(StandardCharsets.UTF_8))
				.build();

		collector.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod, response, Duration.ofMillis(25), List.of());
		collector.didWriteResponse(ServerType.STANDARD_HTTP, request, resourceMethod, response, Duration.ofMillis(4));

		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();

		Assertions.assertEquals(
				0L,
				longSumValue(metrics, "http.server.active_requests",
						attributes -> "POST".equals(attributes.get(HTTP_METHOD_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				1L,
				histogramCount(metrics, "http.server.request.duration",
						attributes -> "POST".equals(attributes.get(HTTP_METHOD_ATTRIBUTE_KEY))
								&& "/widgets/{id}".equals(attributes.get(ROUTE_ATTRIBUTE_KEY))
								&& 201L == requireNonNull(attributes.get(STATUS_CODE_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				1L,
				histogramCount(metrics, "http.server.request.body.size",
						attributes -> "/widgets/{id}".equals(attributes.get(ROUTE_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				1L,
				histogramCount(metrics, "soklet.server.response.write.duration",
						attributes -> "/widgets/{id}".equals(attributes.get(ROUTE_ATTRIBUTE_KEY)))
		);
	}

	@Test
	public void requestBodySizeUsesEncodedBytesForSemconvAndHandlerVisibleBytesForSoklet() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector semconvCollector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-semconv-request-body-size"))
				.build();
		OpenTelemetryMetricsCollector sokletCollector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-soklet-request-body-size"))
				.metricNamingStrategy(OpenTelemetryMetricsCollector.MetricNamingStrategy.SOKLET)
				.build();

		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.POST, "/widgets/{id}", "widget");
		Request encodedRequest = Request.withPath(HttpMethod.POST, "/widgets/123")
				.body(new byte[24])
				.build();
		Request handlerVisibleRequest = encodedRequest.copy()
				.body(new byte[128])
				.finish();
		MarshaledResponse response = MarshaledResponse.fromStatusCode(204);

		semconvCollector.didStartRequestHandling(ServerType.STANDARD_HTTP, handlerVisibleRequest, resourceMethod);
		semconvCollector.didFinishRequestHandling(ServerType.STANDARD_HTTP, handlerVisibleRequest, resourceMethod,
				response, Duration.ofMillis(1), List.of());
		sokletCollector.didStartRequestHandling(ServerType.STANDARD_HTTP, handlerVisibleRequest, resourceMethod);
		sokletCollector.didFinishRequestHandling(ServerType.STANDARD_HTTP, handlerVisibleRequest, resourceMethod,
				response, Duration.ofMillis(1), List.of());

		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();

		Assertions.assertEquals(24D, histogramSum(metrics, "http.server.request.body.size",
				attributes -> "/widgets/{id}".equals(attributes.get(ROUTE_ATTRIBUTE_KEY))));
		Assertions.assertEquals(128D, histogramSum(metrics, "soklet.server.request.body.size",
				attributes -> "/widgets/{id}".equals(attributes.get(ROUTE_ATTRIBUTE_KEY))));
	}

	@Test
	public void semconvOmitsUnknownEncodedBodySizeForIncompleteRequest() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-unknown-request-body-size"))
				.build();
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.POST, "/widgets/{id}", "widget");
		Request request = Request.withPath(HttpMethod.POST, "/widgets/123")
				.contentTooLarge(true)
				.build();
		MarshaledResponse response = MarshaledResponse.fromStatusCode(413);

		collector.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod,
				response, Duration.ofMillis(1), List.of());

		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();
		Assertions.assertTrue(metrics.stream()
				.noneMatch(metric -> "http.server.request.body.size".equals(metric.getName())));
	}

	@Test
	public void recordsRequestBodyDecompressionFailureReason() {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-request-decompression-failure"))
				.build();

		collector.didFailToReadRequest(
				ServerType.STANDARD_HTTP,
				null,
				"/widgets",
				RequestReadFailureReason.REQUEST_BODY_DECOMPRESSION_FAILED,
				null);

		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();

		Assertions.assertEquals(1L, longSumValue(metrics, "soklet.server.request.read.failures",
				attributes -> "standard_http".equals(attributes.get(SERVER_TYPE_ATTRIBUTE_KEY))
						&& "request_body_decompression_failed".equals(attributes.get(FAILURE_REASON_ATTRIBUTE_KEY))));
	}

	@Test
	public void recordsTransportFailureMetrics() {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-transport"))
				.build();

		collector.didRecordTransportFailure(
				ServerType.STANDARD_HTTP,
				MetricsCollector.TransportFailureReason.RESPONSE_WRITE_IDLE_TIMEOUT,
				new IOException("stalled"));
		collector.didRecordTransportFailure(
				ServerType.STANDARD_HTTP,
				MetricsCollector.TransportFailureReason.TASK_ERROR,
				null);
		collector.didRecordTransportFailure(
				ServerType.SSE,
				MetricsCollector.TransportFailureReason.WRITE_TIMEOUT,
				null);

		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();

		Assertions.assertEquals(
				1L,
				longSumValue(metrics, "soklet.server.transport.failures",
						attributes -> "standard_http".equals(attributes.get(SERVER_TYPE_ATTRIBUTE_KEY))
								&& "response_write_idle_timeout".equals(attributes.get(FAILURE_REASON_ATTRIBUTE_KEY))
								&& IOException.class.getName().equals(attributes.get(ERROR_TYPE_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				1L,
				longSumValue(metrics, "soklet.server.transport.failures",
						attributes -> "standard_http".equals(attributes.get(SERVER_TYPE_ATTRIBUTE_KEY))
								&& "task_error".equals(attributes.get(FAILURE_REASON_ATTRIBUTE_KEY))
								&& attributes.get(ERROR_TYPE_ATTRIBUTE_KEY) == null)
		);
		Assertions.assertEquals(
				1L,
				longSumValue(metrics, "soklet.server.transport.failures",
						attributes -> "sse".equals(attributes.get(SERVER_TYPE_ATTRIBUTE_KEY))
								&& "write_timeout".equals(attributes.get(FAILURE_REASON_ATTRIBUTE_KEY))
								&& attributes.get(ERROR_TYPE_ATTRIBUTE_KEY) == null)
		);
	}

	@Test
	public void recordsServerSentEventMetrics() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-sse"))
				.build();

		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/chat", "chat");
		Request request = Request.fromPath(HttpMethod.GET, "/chat");
		TestSseConnection connection = new TestSseConnection(request, resourceMethod, Instant.now());

		collector.didEstablishSseConnection(connection);
		collector.didWriteSseEvent(
				connection,
				SseEvent.withEvent("message").data("hello").build(),
				Duration.ofMillis(5),
				Duration.ofMillis(2),
				12,
				0
		);
		collector.didDropSseEvent(
				connection,
				SseEvent.withEvent("message").data("dropped").build(),
				MetricsCollector.SseEventDropReason.QUEUE_FULL,
				7,
				4
		);
		collector.didWriteSseComment(
				connection,
				SseComment.heartbeatInstance(),
				Duration.ofMillis(1),
				Duration.ofMillis(1),
				0,
				0
		);
		collector.didBroadcastSseEvent(ResourcePathDeclaration.fromPath("/chat"), 3, 2, 1);
		collector.didTerminateSseConnection(connection, StreamTermination
				.with(StreamTerminationReason.CLIENT_DISCONNECTED, Duration.ofSeconds(3))
				.build());

		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();

		Assertions.assertEquals(
				0L,
				longSumValue(metrics, "soklet.sse.streams.active",
						attributes -> "/chat".equals(attributes.get(ROUTE_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				1L,
				longSumValue(metrics, "soklet.sse.streams.established",
						attributes -> "/chat".equals(attributes.get(ROUTE_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				1L,
				longSumValue(metrics, "soklet.sse.streams.terminated",
						attributes -> "/chat".equals(attributes.get(ROUTE_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				1L,
				histogramCount(metrics, "soklet.sse.stream.duration",
						attributes -> "/chat".equals(attributes.get(ROUTE_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				1L,
				longSumValue(metrics, "soklet.sse.events.written",
						attributes -> "/chat".equals(attributes.get(ROUTE_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				1L,
				longSumValue(metrics, "soklet.sse.events.dropped",
						attributes -> "/chat".equals(attributes.get(ROUTE_ATTRIBUTE_KEY))
								&& "queue_full".equals(attributes.get(SSE_DROP_REASON_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				2L,
				longSumValue(metrics, "soklet.sse.broadcast.enqueued",
						attributes -> "/chat".equals(attributes.get(ROUTE_ATTRIBUTE_KEY)))
		);
		// Stream durations use long-lived bucket advice (not OTel's request-oriented defaults) and still record
		Assertions.assertEquals(
				LONG_LIVED_DURATION_BUCKET_BOUNDARIES,
				histogramBoundaries(metrics, "soklet.sse.stream.duration")
		);
	}

	@Test
	public void allTwentyThreeMcpEventsMapToExactTwentyTwoInstrumentsAndTransitions() {
		List<McpEventExpectation> expectations = mcpEventExpectations();
		Assertions.assertEquals(23, expectations.size());
		Assertions.assertEquals(
				Set.copyOf(Arrays.asList(McpMetricsEvent.class.getPermittedSubclasses())),
				expectations.stream().map(expectation -> expectation.event().getClass())
						.collect(Collectors.toSet()));

		for (McpEventExpectation expectation : expectations) {
			TestHarness harness = TestHarness.create();
			OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
					.withMeter(harness.openTelemetrySdk().getMeter(
							"test-mcp-event-" + expectation.event().getClass().getSimpleName()))
					.build();

			collector.didRecordMcpMetricsEvent(expectation.event());
			Set<String> actualNames = harness.metricReader().collectAllMetrics().stream()
					.map(MetricData::getName)
					.collect(Collectors.toSet());
			Assertions.assertEquals(expectation.metricNames(), actualNames,
					expectation.event().getClass().getSimpleName());
		}
	}

	@Test
	public void mcpInstrumentContractUsesExactKindsUnitsAttributesAndBuckets() {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-mcp-contract"))
				.build();
		recordAllMcpEvents(collector);

		Map<String, MetricData> metrics = harness.metricReader().collectAllMetrics().stream()
				.collect(Collectors.toMap(MetricData::getName, metric -> metric));
		Assertions.assertEquals(expectedMcpInstrumentNames(), metrics.keySet());

		Set<String> upDownCounters = Set.of(
				"soklet.mcp.requests.active",
				"soklet.mcp.request.streams.active",
				"soklet.mcp.subscriptions.active",
				"soklet.mcp.handler.executions.active",
				"soklet.mcp.handler.queue.depth");
		Set<String> histograms = Set.of(
				"soklet.mcp.request.duration",
				"soklet.mcp.request.stream.duration",
				"soklet.mcp.subscription.duration");
		Map<String, String> units = expectedMcpUnits();
		Map<String, Set<String>> attributeKeys = expectedMcpAttributeKeys();

		for (MetricData metric : metrics.values()) {
			Assertions.assertEquals(units.get(metric.getName()), metric.getUnit(),
					metric.getName());
			Assertions.assertEquals(attributeKeys.get(metric.getName()),
					metricAttributeNames(metric), metric.getName());

			if (histograms.contains(metric.getName())) {
				Assertions.assertEquals(MetricDataType.HISTOGRAM, metric.getType(),
						metric.getName());
				Assertions.assertEquals(1L, totalHistogramCount(metric),
						metric.getName());
			} else {
				Assertions.assertEquals(MetricDataType.LONG_SUM, metric.getType(),
						metric.getName());
				Assertions.assertEquals(!upDownCounters.contains(metric.getName()),
						metric.getLongSumData().isMonotonic(), metric.getName());
				Assertions.assertEquals(upDownCounters.contains(metric.getName()) ? 0L : 1L,
						totalLongSum(metric), metric.getName());
			}
		}

		Assertions.assertEquals(MCP_REQUEST_DURATION_BUCKET_BOUNDARIES,
				histogramBoundaries(metrics.values(), "soklet.mcp.request.duration"));
		Assertions.assertEquals(MCP_STREAM_DURATION_BUCKET_BOUNDARIES,
				histogramBoundaries(metrics.values(), "soklet.mcp.request.stream.duration"));
		Assertions.assertEquals(MCP_STREAM_DURATION_BUCKET_BOUNDARIES,
				histogramBoundaries(metrics.values(), "soklet.mcp.subscription.duration"));

		Assertions.assertEquals(0L, longSumValue(metrics.values(),
				"soklet.mcp.requests.active", attributes -> attributes.isEmpty()));
		Assertions.assertEquals(1L, longSumValue(metrics.values(),
				"soklet.mcp.requests.completed", attributes ->
						"complete".equals(attributes.get(MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY))));
		Assertions.assertEquals(0.8D, histogramSum(metrics.values(),
				"soklet.mcp.request.duration", attributes -> true), 0D);
		Assertions.assertEquals(1L, longSumValue(metrics.values(),
				"soklet.server.transport.failures", attributes ->
						"mcp".equals(attributes.get(SERVER_TYPE_ATTRIBUTE_KEY))
								&& "write_timeout".equals(attributes.get(FAILURE_REASON_ATTRIBUTE_KEY))
								&& attributes.get(ERROR_TYPE_ATTRIBUTE_KEY) == null));

		TestHarness overflowHarness = TestHarness.create();
		OpenTelemetryMetricsCollector overflowCollector = OpenTelemetryMetricsCollector
				.withMeter(overflowHarness.openTelemetrySdk().getMeter(
						"test-mcp-duration-overflow"))
				.build();
		overflowCollector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStarted(
				MCP_ENDPOINT, MCP_METHOD));
		Assertions.assertDoesNotThrow(() -> overflowCollector.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestFinished(MCP_ENDPOINT, MCP_METHOD,
						McpRequestOutcome.COMPLETE,
						Duration.ofSeconds(Long.MAX_VALUE, 999_999_999))));
		Collection<MetricData> overflowMetrics = overflowHarness.metricReader()
				.collectAllMetrics();
		Assertions.assertEquals(0L, longSumValue(overflowMetrics,
				"soklet.mcp.requests.active", Attributes::isEmpty));
		Assertions.assertEquals(1L, histogramCount(overflowMetrics,
				"soklet.mcp.request.duration", attributes -> true));
		Assertions.assertTrue(Double.isFinite(histogramSum(overflowMetrics,
				"soklet.mcp.request.duration", attributes -> true)));
	}

	@Test
	public void mcpEnumAndManualDimensionsUseExactTypedVocabularyWithoutSensitiveAttributes() {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-mcp-vocabulary"))
				.build();

		for (McpRequestOutcome outcome : McpRequestOutcome.values()) {
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStarted(
					MCP_ENDPOINT, MCP_METHOD));
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestFinished(
					MCP_ENDPOINT, MCP_METHOD, outcome, Duration.ofSeconds(1)));
		}
		for (McpStreamTerminationReason reason : McpStreamTerminationReason.values()) {
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStreamOpened(
					MCP_ENDPOINT, MCP_METHOD));
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStreamClosed(
					MCP_ENDPOINT, MCP_METHOD, reason, Duration.ofSeconds(1)));
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionOpened(
					MCP_ENDPOINT));
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionClosed(
					MCP_ENDPOINT, reason, Duration.ofSeconds(1)));
		}
		for (MetricsCollector.TransportFailureReason reason :
				MetricsCollector.TransportFailureReason.values())
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.transportFailure(reason));
		for (ShutdownComponentDisposition outcome :
				ShutdownComponentDisposition.values())
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.serverStopped(outcome));

		List<Integer> liveCodes = List.of(-32700, -32600, -32601, -32602,
				-32603, -32020, -32021, -32022, -31999, -31998);
		for (Integer code : liveCodes)
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.protocolError(code));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.protocolError(123456));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.unknownMirroredHeader(
				"/manual-endpoint", "manual/method"));

		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();
		Assertions.assertEquals(lowerSnakeValues(McpRequestOutcome.values()),
				stringAttributeValues(metrics, "soklet.mcp.requests.completed",
						MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY));
		Assertions.assertEquals(lowerSnakeValues(McpStreamTerminationReason.values()),
				stringAttributeValues(metrics, "soklet.mcp.request.stream.duration",
						MCP_STREAM_TERMINATION_REASON_ATTRIBUTE_KEY));
		Assertions.assertEquals(lowerSnakeValues(McpStreamTerminationReason.values()),
				stringAttributeValues(metrics, "soklet.mcp.subscription.duration",
						MCP_SUBSCRIPTION_TERMINATION_REASON_ATTRIBUTE_KEY));
		Assertions.assertEquals(lowerSnakeValues(
				MetricsCollector.TransportFailureReason.values()),
				stringAttributeValues(metrics, "soklet.server.transport.failures",
						FAILURE_REASON_ATTRIBUTE_KEY));
		Assertions.assertEquals(MCP_SHUTDOWN_OUTCOMES,
				stringAttributeValues(metrics, "soklet.mcp.shutdowns",
						MCP_SHUTDOWN_OUTCOME_ATTRIBUTE_KEY));

		Set<Long> expectedCodes = liveCodes.stream().map(Integer::longValue)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		expectedCodes.add(123456L);
		Assertions.assertEquals(expectedCodes,
				longAttributeValues(metrics, "soklet.mcp.protocol.errors",
						MCP_PROTOCOL_ERROR_CODE_ATTRIBUTE_KEY));
		Assertions.assertEquals(Set.of("/manual-endpoint"),
				stringAttributeValues(metrics, "soklet.mcp.unknown.mirrored.headers",
						MCP_ENDPOINT_ATTRIBUTE_KEY));
		Assertions.assertEquals(Set.of("manual/method"),
				stringAttributeValues(metrics, "soklet.mcp.unknown.mirrored.headers",
						RPC_METHOD_ATTRIBUTE_KEY));

		Set<String> allAttributeNames = metrics.stream()
				.flatMap(metric -> metricAttributeNames(metric).stream())
				.collect(Collectors.toSet());
		Assertions.assertEquals(expectedMcpAttributeKeys().values().stream()
				.flatMap(Set::stream).collect(Collectors.toSet()), allAttributeNames);
		Assertions.assertTrue(allAttributeNames.stream().noneMatch(name -> {
			String normalized = name.toLowerCase(Locale.ROOT);
			return normalized.contains("trace") || normalized.contains("token")
					|| normalized.contains("session") || normalized.contains("header")
					|| normalized.contains("request.id") || normalized.contains("throwable")
					|| normalized.contains("baggage");
		}));
	}

	@Test
	public void mcpSchemaIgnoresHttpNamingStrategyRemovesLegacySessionsAndPreservesFailureBoundary()
			throws Exception {
		Set<String> semconvNames = new LinkedHashSet<>();
		Set<String> sokletNames = new LinkedHashSet<>();
		TestHarness semconvHarness = TestHarness.create();
		TestHarness sokletHarness = TestHarness.create();
		OpenTelemetryMetricsCollector.withMeter(recordingMeter(
				semconvHarness.openTelemetrySdk().getMeter("test-mcp-semconv-names"),
				semconvNames)).build();
		OpenTelemetryMetricsCollector.withMeter(recordingMeter(
				sokletHarness.openTelemetrySdk().getMeter("test-mcp-soklet-names"),
				sokletNames)).metricNamingStrategy(
				OpenTelemetryMetricsCollector.MetricNamingStrategy.SOKLET).build();

		Set<String> expectedMcpSpecificNames = expectedMcpInstrumentNames().stream()
				.filter(name -> name.startsWith("soklet.mcp."))
				.collect(Collectors.toSet());
		Assertions.assertEquals(expectedMcpSpecificNames, semconvNames.stream()
				.filter(name -> name.startsWith("soklet.mcp."))
				.collect(Collectors.toSet()));
		Assertions.assertEquals(expectedMcpSpecificNames, sokletNames.stream()
				.filter(name -> name.startsWith("soklet.mcp."))
				.collect(Collectors.toSet()));
		Assertions.assertTrue(semconvNames.contains("soklet.server.transport.failures"));

		Set<String> publicMcpMethods = Arrays.stream(
				OpenTelemetryMetricsCollector.class.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.map(Method::getName)
				.filter(name -> name.toLowerCase(Locale.ROOT).contains("mcp"))
				.collect(Collectors.toSet());
		Assertions.assertEquals(Set.of("didRecordMcpMetricsEvent"), publicMcpMethods);
		Method callback = OpenTelemetryMetricsCollector.class.getDeclaredMethod(
				"didRecordMcpMetricsEvent", McpMetricsEvent.class);
		Assertions.assertEquals(void.class, callback.getReturnType());
		Assertions.assertTrue(Modifier.isPublic(callback.getModifiers()));
		Assertions.assertTrue(Arrays.stream(callback.getAnnotatedParameterTypes()[0]
				.getAnnotations()).anyMatch(annotation -> annotation.annotationType()
				.getName().equals("org.jspecify.annotations.NonNull")));

		TestHarness failureHarness = TestHarness.create();
		RuntimeException injectedFailure = new RuntimeException(
				"injected MCP metric recorder failure");
		OpenTelemetryMetricsCollector failingCollector = OpenTelemetryMetricsCollector
				.withMeter(failFirstCounterAddMeter(
						failureHarness.openTelemetrySdk().getMeter("test-mcp-failure-boundary"),
						"soklet.mcp.requests.accepted", injectedFailure))
				.build();
		RuntimeException observedFailure = Assertions.assertThrows(
				RuntimeException.class,
				() -> failingCollector.didRecordMcpMetricsEvent(
						McpMetricsEvent.requestAccepted()));
		Assertions.assertSame(injectedFailure, observedFailure,
				"The core dispatcher owns failure logging and containment.");
		Assertions.assertDoesNotThrow(() -> failingCollector.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestRejected()));
		Assertions.assertEquals(1L, longSumValue(
				failureHarness.metricReader().collectAllMetrics(),
				"soklet.mcp.requests.rejected", Attributes::isEmpty));
	}

	@Test
	public void handlesConcurrentMcpMetricEventsWithoutLoss() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-mcp-concurrency"))
				.build();
		int workers = 4;
		int iterationsPerWorker = 100;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(workers);
		ExecutorService executorService = Executors.newFixedThreadPool(workers);

		for (int worker = 0; worker < workers; ++worker) {
			executorService.execute(() -> {
				try {
					start.await();
					for (int iteration = 0; iteration < iterationsPerWorker; ++iteration) {
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStarted(
								MCP_ENDPOINT, MCP_METHOD));
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestFinished(
								MCP_ENDPOINT, MCP_METHOD, McpRequestOutcome.COMPLETE,
								Duration.ofMillis(1)));
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStreamOpened(
								MCP_ENDPOINT, MCP_METHOD));
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStreamClosed(
								MCP_ENDPOINT, MCP_METHOD, McpStreamTerminationReason.COMPLETED,
								Duration.ofSeconds(1)));
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionOpened(
								MCP_ENDPOINT));
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionClosed(
								MCP_ENDPOINT, McpStreamTerminationReason.COMPLETED,
								Duration.ofSeconds(1)));
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.handlerExecutionStarted());
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.handlerExecutionFinished());
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.handlerQueued());
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.handlerDequeued());
						collector.didRecordMcpMetricsEvent(McpMetricsEvent.progressEmitted(
								MCP_ENDPOINT, MCP_METHOD));
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					finished.countDown();
				}
			});
		}

		start.countDown();
		Assertions.assertTrue(finished.await(15, TimeUnit.SECONDS));
		executorService.shutdown();
		Assertions.assertTrue(executorService.awaitTermination(15, TimeUnit.SECONDS));

		long expected = (long) workers * iterationsPerWorker;
		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();
		Assertions.assertEquals(0L, longSumValue(metrics,
				"soklet.mcp.requests.active", Attributes::isEmpty));
		Assertions.assertEquals(expected, longSumValue(metrics,
				"soklet.mcp.requests.completed", attributes -> true));
		Assertions.assertEquals(expected, histogramCount(metrics,
				"soklet.mcp.request.duration", attributes -> true));
		Assertions.assertEquals(0L, longSumValue(metrics,
				"soklet.mcp.request.streams.active", Attributes::isEmpty));
		Assertions.assertEquals(expected, histogramCount(metrics,
				"soklet.mcp.request.stream.duration", attributes -> true));
		Assertions.assertEquals(0L, longSumValue(metrics,
				"soklet.mcp.subscriptions.active", Attributes::isEmpty));
		Assertions.assertEquals(expected, histogramCount(metrics,
				"soklet.mcp.subscription.duration", attributes -> true));
		Assertions.assertEquals(0L, longSumValue(metrics,
				"soklet.mcp.handler.executions.active", Attributes::isEmpty));
		Assertions.assertEquals(0L, longSumValue(metrics,
				"soklet.mcp.handler.queue.depth", Attributes::isEmpty));
		Assertions.assertEquals(expected, longSumValue(metrics,
				"soklet.mcp.progress.emitted", attributes -> true));
	}

	@Test
	public void supportsSokletNamingStrategy() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-soklet-naming"))
				.metricNamingStrategy(OpenTelemetryMetricsCollector.MetricNamingStrategy.SOKLET)
				.build();

		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/accounts/{id}", "product");
		Request request = Request.fromPath(HttpMethod.GET, "/accounts/123");
		MarshaledResponse response = MarshaledResponse.fromStatusCode(200);

		collector.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod, response,
				Duration.ofMillis(2), List.of());

		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();

		Assertions.assertEquals(
				1L,
				histogramCount(metrics, "soklet.server.request.duration",
						attributes -> "/accounts/{id}".equals(attributes.get(ROUTE_ATTRIBUTE_KEY)))
		);
	}

	@Test
	public void handlesConcurrentRequestCallbacks() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector
				.withMeter(harness.openTelemetrySdk().getMeter("test-concurrency"))
				.build();

		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/products/{id}", "product");
		Request request = Request.fromPath(HttpMethod.GET, "/products/123");
		MarshaledResponse response = MarshaledResponse.fromStatusCode(200);

		int workers = 8;
		int iterationsPerWorker = 200;
		CountDownLatch latch = new CountDownLatch(workers);
		ExecutorService executorService = Executors.newFixedThreadPool(workers);

		for (int i = 0; i < workers; i++) {
			executorService.submit(() -> {
				try {
					for (int j = 0; j < iterationsPerWorker; j++) {
						collector.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
						collector.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod, response,
								Duration.ofMillis(1), List.of());
					}
				} finally {
					latch.countDown();
				}
			});
		}

		Assertions.assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for workers");
		executorService.shutdown();
		Assertions.assertTrue(executorService.awaitTermination(15, TimeUnit.SECONDS), "Executor did not terminate");

		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();

		Assertions.assertEquals(
				0L,
				longSumValue(metrics, "http.server.active_requests",
						attributes -> "GET".equals(attributes.get(HTTP_METHOD_ATTRIBUTE_KEY)))
		);
		Assertions.assertEquals(
				workers * iterationsPerWorker,
				histogramCount(metrics, "http.server.request.duration",
						attributes -> "/products/{id}".equals(attributes.get(ROUTE_ATTRIBUTE_KEY)))
		);
	}

	private static List<McpEventExpectation> mcpEventExpectations() {
		return List.of(
				new McpEventExpectation(McpMetricsEvent.serverStarted(),
						Set.of("soklet.mcp.server.starts")),
				new McpEventExpectation(McpMetricsEvent.connectionAccepted(),
						Set.of("soklet.mcp.connections.accepted")),
				new McpEventExpectation(McpMetricsEvent.connectionRejected(),
						Set.of("soklet.mcp.connections.rejected")),
				new McpEventExpectation(McpMetricsEvent.requestAccepted(),
						Set.of("soklet.mcp.requests.accepted")),
				new McpEventExpectation(McpMetricsEvent.requestRejected(),
						Set.of("soklet.mcp.requests.rejected")),
				new McpEventExpectation(McpMetricsEvent.requestStarted(
						MCP_ENDPOINT, MCP_METHOD), Set.of("soklet.mcp.requests.active")),
				new McpEventExpectation(McpMetricsEvent.requestFinished(
						MCP_ENDPOINT, MCP_METHOD, McpRequestOutcome.COMPLETE,
						Duration.ofMillis(800)), Set.of(
						"soklet.mcp.requests.active",
						"soklet.mcp.requests.completed",
						"soklet.mcp.request.duration")),
				new McpEventExpectation(McpMetricsEvent.requestStreamOpened(
						MCP_ENDPOINT, MCP_METHOD),
						Set.of("soklet.mcp.request.streams.active")),
				new McpEventExpectation(McpMetricsEvent.requestStreamClosed(
						MCP_ENDPOINT, MCP_METHOD, McpStreamTerminationReason.COMPLETED,
						Duration.ofSeconds(30)), Set.of(
						"soklet.mcp.request.streams.active",
						"soklet.mcp.request.stream.duration")),
				new McpEventExpectation(McpMetricsEvent.subscriptionOpened(MCP_ENDPOINT),
						Set.of("soklet.mcp.subscriptions.active")),
				new McpEventExpectation(McpMetricsEvent.subscriptionClosed(
						MCP_ENDPOINT, McpStreamTerminationReason.COMPLETED,
						Duration.ofSeconds(30)), Set.of(
						"soklet.mcp.subscriptions.active",
						"soklet.mcp.subscription.duration")),
				new McpEventExpectation(McpMetricsEvent.cancelationSignaled(
						MCP_ENDPOINT, MCP_METHOD),
						Set.of("soklet.mcp.cancelations.signaled")),
				new McpEventExpectation(McpMetricsEvent.progressEmitted(
						MCP_ENDPOINT, MCP_METHOD),
						Set.of("soklet.mcp.progress.emitted")),
				new McpEventExpectation(McpMetricsEvent.keepAliveEmitted(),
						Set.of("soklet.mcp.keepalives.emitted")),
				new McpEventExpectation(McpMetricsEvent.protocolError(-32600),
						Set.of("soklet.mcp.protocol.errors")),
				new McpEventExpectation(McpMetricsEvent.unknownMirroredHeader(
						MCP_ENDPOINT, MCP_METHOD),
						Set.of("soklet.mcp.unknown.mirrored.headers")),
				new McpEventExpectation(McpMetricsEvent.handlerExecutionStarted(),
						Set.of("soklet.mcp.handler.executions.active")),
				new McpEventExpectation(McpMetricsEvent.handlerExecutionFinished(),
						Set.of("soklet.mcp.handler.executions.active")),
				new McpEventExpectation(McpMetricsEvent.handlerQueued(),
						Set.of("soklet.mcp.handler.queue.depth")),
				new McpEventExpectation(McpMetricsEvent.handlerDequeued(),
						Set.of("soklet.mcp.handler.queue.depth")),
				new McpEventExpectation(McpMetricsEvent.handlerCapacityRejected(),
						Set.of("soklet.mcp.handler.capacity.rejections")),
				new McpEventExpectation(McpMetricsEvent.transportFailure(
						MetricsCollector.TransportFailureReason.WRITE_TIMEOUT),
						Set.of("soklet.server.transport.failures")),
				new McpEventExpectation(McpMetricsEvent.serverStopped(
						ShutdownComponentDisposition.GRACEFUL_TERMINATION),
						Set.of("soklet.mcp.shutdowns"))
		);
	}

	private static void recordAllMcpEvents(
			@NonNull OpenTelemetryMetricsCollector collector) {
		requireNonNull(collector);
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.serverStarted());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.connectionAccepted());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.connectionRejected());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestAccepted());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestRejected());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStarted(
				MCP_ENDPOINT, MCP_METHOD));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestFinished(
				MCP_ENDPOINT, MCP_METHOD, McpRequestOutcome.COMPLETE,
				Duration.ofMillis(800)));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStreamOpened(
				MCP_ENDPOINT, MCP_METHOD));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStreamClosed(
				MCP_ENDPOINT, MCP_METHOD, McpStreamTerminationReason.COMPLETED,
				Duration.ofSeconds(30)));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionOpened(
				MCP_ENDPOINT));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionClosed(
				MCP_ENDPOINT, McpStreamTerminationReason.COMPLETED,
				Duration.ofSeconds(30)));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.cancelationSignaled(
				MCP_ENDPOINT, MCP_METHOD));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.progressEmitted(
				MCP_ENDPOINT, MCP_METHOD));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.keepAliveEmitted());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.protocolError(-32600));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.unknownMirroredHeader(
				MCP_ENDPOINT, MCP_METHOD));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.handlerExecutionStarted());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.handlerExecutionFinished());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.handlerQueued());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.handlerDequeued());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.handlerCapacityRejected());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.transportFailure(
				MetricsCollector.TransportFailureReason.WRITE_TIMEOUT));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.serverStopped(
				ShutdownComponentDisposition.GRACEFUL_TERMINATION));
	}

	private static Set<String> expectedMcpInstrumentNames() {
		return Set.of(
				"soklet.mcp.server.starts",
				"soklet.mcp.shutdowns",
				"soklet.mcp.connections.accepted",
				"soklet.mcp.connections.rejected",
				"soklet.mcp.requests.accepted",
				"soklet.mcp.requests.rejected",
				"soklet.mcp.requests.active",
				"soklet.mcp.requests.completed",
				"soklet.mcp.request.duration",
				"soklet.mcp.request.streams.active",
				"soklet.mcp.request.stream.duration",
				"soklet.mcp.subscriptions.active",
				"soklet.mcp.subscription.duration",
				"soklet.mcp.cancelations.signaled",
				"soklet.mcp.progress.emitted",
				"soklet.mcp.keepalives.emitted",
				"soklet.mcp.protocol.errors",
				"soklet.mcp.unknown.mirrored.headers",
				"soklet.mcp.handler.executions.active",
				"soklet.mcp.handler.queue.depth",
				"soklet.mcp.handler.capacity.rejections",
				"soklet.server.transport.failures");
	}

	private static Map<String, String> expectedMcpUnits() {
		Map<String, String> units = new LinkedHashMap<>();
		units.put("soklet.mcp.server.starts", "{start}");
		units.put("soklet.mcp.shutdowns", "{shutdown}");
		units.put("soklet.mcp.connections.accepted", "{connection}");
		units.put("soklet.mcp.connections.rejected", "{connection}");
		units.put("soklet.mcp.requests.accepted", "{request}");
		units.put("soklet.mcp.requests.rejected", "{request}");
		units.put("soklet.mcp.requests.active", "{request}");
		units.put("soklet.mcp.requests.completed", "{request}");
		units.put("soklet.mcp.request.duration", "s");
		units.put("soklet.mcp.request.streams.active", "{stream}");
		units.put("soklet.mcp.request.stream.duration", "s");
		units.put("soklet.mcp.subscriptions.active", "{subscription}");
		units.put("soklet.mcp.subscription.duration", "s");
		units.put("soklet.mcp.cancelations.signaled", "{cancelation}");
		units.put("soklet.mcp.progress.emitted", "{notification}");
		units.put("soklet.mcp.keepalives.emitted", "{comment}");
		units.put("soklet.mcp.protocol.errors", "{error}");
		units.put("soklet.mcp.unknown.mirrored.headers", "{header}");
		units.put("soklet.mcp.handler.executions.active", "{handler}");
		units.put("soklet.mcp.handler.queue.depth", "{request}");
		units.put("soklet.mcp.handler.capacity.rejections", "{request}");
		units.put("soklet.server.transport.failures", "{failure}");
		return Map.copyOf(units);
	}

	private static Map<String, Set<String>> expectedMcpAttributeKeys() {
		Set<String> none = Set.of();
		Set<String> endpointMethod = Set.of("soklet.mcp.endpoint", "rpc.method");
		Map<String, Set<String>> attributes = new LinkedHashMap<>();
		attributes.put("soklet.mcp.server.starts", none);
		attributes.put("soklet.mcp.shutdowns", Set.of("soklet.mcp.shutdown.outcome"));
		attributes.put("soklet.mcp.connections.accepted", none);
		attributes.put("soklet.mcp.connections.rejected", none);
		attributes.put("soklet.mcp.requests.accepted", none);
		attributes.put("soklet.mcp.requests.rejected", none);
		attributes.put("soklet.mcp.requests.active", none);
		attributes.put("soklet.mcp.requests.completed", Set.of(
				"soklet.mcp.endpoint", "rpc.method", "soklet.mcp.request.outcome"));
		attributes.put("soklet.mcp.request.duration", Set.of(
				"soklet.mcp.endpoint", "rpc.method", "soklet.mcp.request.outcome"));
		attributes.put("soklet.mcp.request.streams.active", none);
		attributes.put("soklet.mcp.request.stream.duration", Set.of(
				"soklet.mcp.endpoint", "rpc.method",
				"soklet.mcp.stream.termination.reason"));
		attributes.put("soklet.mcp.subscriptions.active", none);
		attributes.put("soklet.mcp.subscription.duration", Set.of(
				"soklet.mcp.endpoint", "soklet.mcp.subscription.termination.reason"));
		attributes.put("soklet.mcp.cancelations.signaled", endpointMethod);
		attributes.put("soklet.mcp.progress.emitted", endpointMethod);
		attributes.put("soklet.mcp.keepalives.emitted", none);
		attributes.put("soklet.mcp.protocol.errors", Set.of("rpc.jsonrpc.error_code"));
		attributes.put("soklet.mcp.unknown.mirrored.headers", endpointMethod);
		attributes.put("soklet.mcp.handler.executions.active", none);
		attributes.put("soklet.mcp.handler.queue.depth", none);
		attributes.put("soklet.mcp.handler.capacity.rejections", none);
		attributes.put("soklet.server.transport.failures", Set.of(
				"soklet.server.type", "soklet.failure.reason"));
		return Map.copyOf(attributes);
	}

	private static Set<String> metricAttributeNames(@NonNull MetricData metric) {
		return metricAttributes(metric).stream()
				.flatMap(attributes -> attributes.asMap().keySet().stream())
				.map(AttributeKey::getKey)
				.collect(Collectors.toSet());
	}

	private static List<Attributes> metricAttributes(@NonNull MetricData metric) {
		requireNonNull(metric);
		if (metric.getType() == MetricDataType.LONG_SUM)
			return metric.getLongSumData().getPoints().stream()
					.map(LongPointData::getAttributes).toList();
		if (metric.getType() == MetricDataType.HISTOGRAM)
			return metric.getHistogramData().getPoints().stream()
					.map(HistogramPointData::getAttributes).toList();
		throw new AssertionError("Unexpected MCP metric type: " + metric.getType());
	}

	private static long totalLongSum(@NonNull MetricData metric) {
		return requireNonNull(metric).getLongSumData().getPoints().stream()
				.mapToLong(LongPointData::getValue).sum();
	}

	private static long totalHistogramCount(@NonNull MetricData metric) {
		return requireNonNull(metric).getHistogramData().getPoints().stream()
				.mapToLong(HistogramPointData::getCount).sum();
	}

	private static Set<String> stringAttributeValues(
			@NonNull Collection<MetricData> metrics,
			@NonNull String metricName,
			@NonNull AttributeKey<String> attributeKey) {
		return metricAttributes(metricByName(metrics, metricName)).stream()
				.map(attributes -> requireNonNull(attributes.get(attributeKey)))
				.collect(Collectors.toSet());
	}

	private static Set<Long> longAttributeValues(
			@NonNull Collection<MetricData> metrics,
			@NonNull String metricName,
			@NonNull AttributeKey<Long> attributeKey) {
		return metricAttributes(metricByName(metrics, metricName)).stream()
				.map(attributes -> requireNonNull(attributes.get(attributeKey)))
				.collect(Collectors.toSet());
	}

	private static Set<String> lowerSnakeValues(@NonNull Enum<?>[] values) {
		return Arrays.stream(requireNonNull(values))
				.map(value -> value.name().toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());
	}

	private static Meter recordingMeter(@NonNull Meter delegate,
			@NonNull Set<String> instrumentNames) {
		requireNonNull(delegate);
		requireNonNull(instrumentNames);
		return (Meter) Proxy.newProxyInstance(Meter.class.getClassLoader(),
				new Class<?>[]{Meter.class}, (proxy, method, arguments) -> {
					if (arguments != null && arguments.length > 0
							&& arguments[0] instanceof String instrumentName
							&& method.getName().endsWith("Builder"))
						instrumentNames.add(instrumentName);
					try {
						return method.invoke(delegate, arguments);
					} catch (InvocationTargetException exception) {
						throw exception.getCause();
					}
				});
	}

	private static Meter failFirstCounterAddMeter(@NonNull Meter delegate,
			@NonNull String failingInstrumentName,
			@NonNull RuntimeException failure) {
		requireNonNull(delegate);
		requireNonNull(failingInstrumentName);
		requireNonNull(failure);
		AtomicBoolean shouldFail = new AtomicBoolean(true);
		return (Meter) Proxy.newProxyInstance(Meter.class.getClassLoader(),
				new Class<?>[]{Meter.class}, (proxy, method, arguments) -> {
					Object result;
					try {
						result = method.invoke(delegate, arguments);
					} catch (InvocationTargetException exception) {
						throw exception.getCause();
					}
					if (method.getName().equals("counterBuilder")
							&& arguments != null && arguments.length == 1
							&& failingInstrumentName.equals(arguments[0]))
						return failFirstCounterAddBuilder((LongCounterBuilder) result,
								shouldFail, failure);
					return result;
				});
	}

	private static LongCounterBuilder failFirstCounterAddBuilder(
			@NonNull LongCounterBuilder delegate,
			@NonNull AtomicBoolean shouldFail,
			@NonNull RuntimeException failure) {
		LongCounterBuilder[] proxyReference = new LongCounterBuilder[1];
		proxyReference[0] = (LongCounterBuilder) Proxy.newProxyInstance(
				LongCounterBuilder.class.getClassLoader(),
				new Class<?>[]{LongCounterBuilder.class},
				(proxy, method, arguments) -> {
					Object result;
					try {
						result = method.invoke(delegate, arguments);
					} catch (InvocationTargetException exception) {
						throw exception.getCause();
					}
					if (result == delegate)
						return proxyReference[0];
					if (method.getName().equals("build"))
						return failFirstCounterAdd((LongCounter) result, shouldFail, failure);
					return result;
				});
		return proxyReference[0];
	}

	private static LongCounter failFirstCounterAdd(@NonNull LongCounter delegate,
			@NonNull AtomicBoolean shouldFail,
			@NonNull RuntimeException failure) {
		return (LongCounter) Proxy.newProxyInstance(LongCounter.class.getClassLoader(),
				new Class<?>[]{LongCounter.class}, (proxy, method, arguments) -> {
					if (method.getName().equals("add")
							&& shouldFail.compareAndSet(true, false))
						throw failure;
					try {
						return method.invoke(delegate, arguments);
					} catch (InvocationTargetException exception) {
						throw exception.getCause();
					}
				});
	}

	private static ResourceMethod createResourceMethod(HttpMethod httpMethod,
																										 String route,
																										 String methodName) throws Exception {
		Method method = TestResources.class.getDeclaredMethod(methodName);
		return ResourceMethod.fromComponents(
				httpMethod,
				ResourcePathDeclaration.fromPath(route),
				method,
				httpMethod == HttpMethod.GET && route.equals("/chat")
		);
	}

	private static long longSumValue(Collection<MetricData> metrics,
																	 String metricName,
																	 java.util.function.Predicate<Attributes> attributesMatcher) {
		return metricByName(metrics, metricName).getLongSumData().getPoints().stream()
				.filter(point -> attributesMatcher.test(point.getAttributes()))
				.mapToLong(LongPointData::getValue)
				.findFirst()
				.orElseThrow();
	}

	private static long histogramCount(Collection<MetricData> metrics,
																	 String metricName,
																	 java.util.function.Predicate<Attributes> attributesMatcher) {
		return metricByName(metrics, metricName).getHistogramData().getPoints().stream()
				.filter(point -> attributesMatcher.test(point.getAttributes()))
				.mapToLong(HistogramPointData::getCount)
				.findFirst()
				.orElseThrow();
	}

	private static double histogramSum(Collection<MetricData> metrics,
																		 String metricName,
																		 java.util.function.Predicate<Attributes> attributesMatcher) {
		return metricByName(metrics, metricName).getHistogramData().getPoints().stream()
				.filter(point -> attributesMatcher.test(point.getAttributes()))
				.mapToDouble(HistogramPointData::getSum)
				.findFirst()
				.orElseThrow();
	}

	private static List<Double> histogramBoundaries(Collection<MetricData> metrics,
																									String metricName) {
		return metricByName(metrics, metricName).getHistogramData().getPoints().stream()
				.findFirst()
				.orElseThrow()
				.getBoundaries();
	}

	private static MetricData metricByName(Collection<MetricData> metrics,
																				 String metricName) {
		return metrics.stream()
				.filter(metric -> metricName.equals(metric.getName()))
				.findFirst()
				.orElseThrow();
	}

	private record TestHarness(@NonNull InMemoryMetricReader metricReader,
														 @NonNull OpenTelemetrySdk openTelemetrySdk,
														 @NonNull SdkMeterProvider sdkMeterProvider) {
		@NonNull
		static TestHarness create() {
			InMemoryMetricReader metricReader = InMemoryMetricReader.create();
			SdkMeterProvider sdkMeterProvider = SdkMeterProvider.builder()
					.registerMetricReader(metricReader)
					.build();
			OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
					.setMeterProvider(sdkMeterProvider)
					.build();

			return new TestHarness(metricReader, openTelemetrySdk, sdkMeterProvider);
		}
	}

	private record McpEventExpectation(@NonNull McpMetricsEvent event,
			@NonNull Set<String> metricNames) {
		private McpEventExpectation {
			requireNonNull(event);
			metricNames = Set.copyOf(requireNonNull(metricNames));
		}
	}

	private static final class TestResources {
		public static void widget() {
			// No-op
		}

		public static void product() {
			// No-op
		}

		public static void chat() {
			// No-op
		}
	}

	private static final class TestSseConnection implements SseConnection {
		@NonNull
		private final Request request;
		@NonNull
		private final ResourceMethod resourceMethod;
		@NonNull
		private final Instant establishedAt;

		private TestSseConnection(@NonNull Request request,
															@NonNull ResourceMethod resourceMethod,
															@NonNull Instant establishedAt) {
			this.request = requireNonNull(request);
			this.resourceMethod = requireNonNull(resourceMethod);
			this.establishedAt = requireNonNull(establishedAt);
		}

		@NonNull
		@Override
		public Request getRequest() {
			return this.request;
		}

		@NonNull
		@Override
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@NonNull
		@Override
		public Instant getEstablishedAt() {
			return this.establishedAt;
		}

		@NonNull
		@Override
		public Optional<Object> getClientContext() {
			return Optional.empty();
		}
	}
}
