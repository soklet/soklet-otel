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
import com.soklet.McpEndpoint;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcRequestId;
import com.soklet.McpRequestOutcome;
import com.soklet.McpSseStream;
import com.soklet.Request;
import com.soklet.ResourceMethod;
import com.soklet.ResourcePathDeclaration;
import com.soklet.ServerType;
import com.soklet.SseConnection;
import com.soklet.SseEvent;
import com.soklet.StreamTermination;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamingResponseHandle;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class OpenTelemetryLifecycleObserverTests {
	private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
	private static final AttributeKey<String> SERVER_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.server.type");
	private static final AttributeKey<String> HTTP_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("http.request.method");
	private static final AttributeKey<String> ROUTE_ATTRIBUTE_KEY = AttributeKey.stringKey("http.route");
	private static final AttributeKey<Long> STATUS_CODE_ATTRIBUTE_KEY = AttributeKey.longKey("http.response.status_code");
	private static final AttributeKey<String> ERROR_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("error.type");
	private static final AttributeKey<String> CLIENT_ADDRESS_ATTRIBUTE_KEY = AttributeKey.stringKey("client.address");
	private static final AttributeKey<String> REQUEST_ID_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.request.id");
	private static final AttributeKey<String> STREAM_TERMINATION_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.stream.termination.reason");
	private static final AttributeKey<String> RPC_SYSTEM_ATTRIBUTE_KEY = AttributeKey.stringKey("rpc.system");
	private static final AttributeKey<String> RPC_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("rpc.method");
	private static final AttributeKey<String> MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.endpoint.class");
	private static final AttributeKey<Boolean> MCP_SESSION_ID_PRESENT_ATTRIBUTE_KEY = AttributeKey.booleanKey("soklet.mcp.session.id.present");
	private static final AttributeKey<Boolean> MCP_REQUEST_ID_PRESENT_ATTRIBUTE_KEY = AttributeKey.booleanKey("soklet.mcp.request.id.present");
	private static final AttributeKey<String> MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.request.outcome");
	private static final AttributeKey<Long> MCP_JSON_RPC_ERROR_CODE_ATTRIBUTE_KEY = AttributeKey.longKey("rpc.jsonrpc.error_code");

	@Test
	public void httpSpanUsesRemoteParentAndStandardAttributes() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = tracedRequest(HttpMethod.POST, "/widgets/123");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.POST, "/widgets/{id}", "widget");
		MarshaledResponse response = MarshaledResponse.withStatusCode(201)
				.body("created".getBytes(StandardCharsets.UTF_8))
				.build();

		observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		Assertions.assertEquals(Integer.valueOf(1), observer.getActiveSpanCount());
		observer.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod, response, Duration.ofMillis(8), List.of());

		SpanData span = onlySpan(harness);
		Assertions.assertEquals("POST /widgets/{id}", span.getName());
		Assertions.assertEquals(SpanKind.SERVER, span.getKind());
		Assertions.assertEquals("0af7651916cd43dd8448eb211c80319c", span.getTraceId());
		Assertions.assertEquals("b7ad6b7169203331", span.getParentSpanId());
		Assertions.assertEquals("http", span.getAttributes().get(SERVER_TYPE_ATTRIBUTE_KEY));
		Assertions.assertEquals("POST", span.getAttributes().get(HTTP_METHOD_ATTRIBUTE_KEY));
		Assertions.assertEquals("/widgets/{id}", span.getAttributes().get(ROUTE_ATTRIBUTE_KEY));
		Assertions.assertEquals(201L, requireNonNull(span.getAttributes().get(STATUS_CODE_ATTRIBUTE_KEY)));
		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
	}

	@Test
	public void httpServerErrorsMarkSpanError() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = Request.fromPath(HttpMethod.GET, "/widgets/123");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/widgets/{id}", "widget");
		MarshaledResponse response = MarshaledResponse.fromStatusCode(503);

		observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		observer.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod, response, Duration.ofMillis(3), List.of());

		SpanData span = onlySpan(harness);
		Assertions.assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
		Assertions.assertEquals("http.status_code", span.getAttributes().get(ERROR_TYPE_ATTRIBUTE_KEY));
	}

	@Test
	public void httpClientErrorsDoNotMarkSpanError() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = Request.fromPath(HttpMethod.GET, "/widgets/missing");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/widgets/{id}", "widget");

		observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		observer.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod,
				MarshaledResponse.fromStatusCode(404), Duration.ofMillis(3), List.of());

		SpanData span = onlySpan(harness);
		Assertions.assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
		Assertions.assertNull(span.getAttributes().get(ERROR_TYPE_ATTRIBUTE_KEY));
	}

	@Test
	public void missingTraceContextProducesRootSpan() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = Request.fromPath(HttpMethod.GET, "/widgets/123");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/widgets/{id}", "widget");

		observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		observer.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod,
				MarshaledResponse.fromStatusCode(200), Duration.ofMillis(3), List.of());

		SpanData span = onlySpan(harness);
		Assertions.assertEquals(SpanId.getInvalid(), span.getParentSpanId());
		Assertions.assertFalse(span.getParentSpanContext().isValid());
	}

	@Test
	public void traceStateIsTransferredToRemoteParent() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = Request.withPath(HttpMethod.GET, "/widgets/123")
				.headers(Map.of(
						"traceparent", Set.of(TRACEPARENT),
						"tracestate", Set.of("rojo=00f067aa0ba902b7,congo=t61rcWkgMzE")))
				.build();
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/widgets/{id}", "widget");

		observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		observer.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod,
				MarshaledResponse.fromStatusCode(200), Duration.ofMillis(3), List.of());

		SpanData span = onlySpan(harness);
		Assertions.assertEquals("00f067aa0ba902b7", span.getParentSpanContext().getTraceState().get("rojo"));
		Assertions.assertEquals("t61rcWkgMzE", span.getParentSpanContext().getTraceState().get("congo"));
	}

	@Test
	public void optionalRequestAttributesAreOptIn() throws Exception {
		Request request = Request.withPath(HttpMethod.GET, "/widgets/123")
				.id("request-123")
				.remoteAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 54321))
				.build();
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/widgets/{id}", "widget");
		TestHarness defaultHarness = TestHarness.create();
		OpenTelemetryLifecycleObserver defaultObserver = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(defaultHarness.openTelemetrySdk())
				.build();

		defaultObserver.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		defaultObserver.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod,
				MarshaledResponse.fromStatusCode(200), Duration.ofMillis(3), List.of());

		SpanData defaultSpan = onlySpan(defaultHarness);
		Assertions.assertNull(defaultSpan.getAttributes().get(CLIENT_ADDRESS_ATTRIBUTE_KEY));
		Assertions.assertNull(defaultSpan.getAttributes().get(REQUEST_ID_ATTRIBUTE_KEY));

		TestHarness optInHarness = TestHarness.create();
		OpenTelemetryLifecycleObserver optInObserver = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(optInHarness.openTelemetrySdk())
				.spanPolicy(SpanPolicy.builder()
						.recordClientAddress(true)
						.recordRequestId(true)
						.build())
				.build();

		optInObserver.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		optInObserver.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod,
				MarshaledResponse.fromStatusCode(200), Duration.ofMillis(3), List.of());

		SpanData optInSpan = onlySpan(optInHarness);
		Assertions.assertEquals(InetAddress.getLoopbackAddress().getHostAddress(),
				optInSpan.getAttributes().get(CLIENT_ADDRESS_ATTRIBUTE_KEY));
		Assertions.assertEquals("request-123", optInSpan.getAttributes().get(REQUEST_ID_ATTRIBUTE_KEY));
	}

	@Test
	public void streamingHttpSpanStaysOpenUntilStreamTermination() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = tracedRequest(HttpMethod.GET, "/download");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/download", "download");
		MarshaledResponse response = MarshaledResponse.fromStatusCode(200)
				.copy()
				.stream(StreamingResponseBody.fromWriter((output, context) -> output.write(new byte[]{1, 2, 3})))
				.finish();

		observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		Instant establishedAt = Instant.now();
		TestStreamingResponseHandle stream = new TestStreamingResponseHandle(request, resourceMethod, response, establishedAt);
		observer.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod, response, Duration.ofMillis(1), List.of());

		Assertions.assertEquals(List.of(), harness.spanExporter().getFinishedSpanItems());
		Assertions.assertEquals(Integer.valueOf(1), observer.getActiveSpanCount());

		observer.didTerminateResponseStream(stream, StreamTermination.with(StreamTerminationReason.COMPLETED, Duration.ZERO).build());

		SpanData span = onlySpan(harness);
		Assertions.assertEquals("completed", span.getAttributes().get(STREAM_TERMINATION_REASON_ATTRIBUTE_KEY));
		Assertions.assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
	}

	@Test
	public void responseStreamTerminationAfterCloseDoesNotBackfillSpan() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = tracedRequest(HttpMethod.GET, "/download");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/download", "download");
		MarshaledResponse response = MarshaledResponse.fromStatusCode(200)
				.copy()
				.stream(StreamingResponseBody.fromWriter((output, context) -> output.write(new byte[]{1, 2, 3})))
				.finish();
		TestStreamingResponseHandle stream = new TestStreamingResponseHandle(request, resourceMethod, response, Instant.now());

		observer.close();
		observer.didTerminateResponseStream(stream, StreamTermination.with(StreamTerminationReason.COMPLETED, Duration.ZERO).build());

		Assertions.assertEquals(List.of(), harness.spanExporter().getFinishedSpanItems());
		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
	}

	@Test
	public void duplicateHttpStartEndsOlderSpanAndKeepsOneActive() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = Request.fromPath(HttpMethod.GET, "/widgets/123");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/widgets/{id}", "widget");

		observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);

		Assertions.assertEquals(Integer.valueOf(1), observer.getActiveSpanCount());
		Assertions.assertEquals(1, harness.spanExporter().getFinishedSpanItems().size());
		Assertions.assertEquals("server_stopping", harness.spanExporter().getFinishedSpanItems().get(0)
				.getAttributes().get(STREAM_TERMINATION_REASON_ATTRIBUTE_KEY));

		observer.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod,
				MarshaledResponse.fromStatusCode(200), Duration.ofMillis(3), List.of());

		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
		Assertions.assertEquals(2, harness.spanExporter().getFinishedSpanItems().size());
	}

	@Test
	public void sseConnectionSpanEndsWithTerminationReason() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = tracedRequest(HttpMethod.GET, "/events");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/events", "events");
		TestSseConnection sseConnection = new TestSseConnection(request, resourceMethod, Instant.now());

		observer.didEstablishSseConnection(sseConnection);
		Assertions.assertEquals(Integer.valueOf(1), observer.getActiveSpanCount());
		observer.didTerminateSseConnection(sseConnection, StreamTermination.with(StreamTerminationReason.CLIENT_DISCONNECTED, Duration.ZERO).build());

		SpanData span = onlySpan(harness);
		Assertions.assertEquals("GET /events sse", span.getName());
		Assertions.assertEquals(SpanKind.SERVER, span.getKind());
		Assertions.assertEquals("server_sent_event", span.getAttributes().get(SERVER_TYPE_ATTRIBUTE_KEY));
		Assertions.assertEquals("client_disconnected", span.getAttributes().get(STREAM_TERMINATION_REASON_ATTRIBUTE_KEY));
		Assertions.assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
	}

	@Test
	public void sseWriteFailureRecordsExceptionWithoutPayloadData() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = tracedRequest(HttpMethod.GET, "/events");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/events", "events");
		TestSseConnection sseConnection = new TestSseConnection(request, resourceMethod, Instant.now());
		RuntimeException writeFailure = new RuntimeException("write failed");

		observer.didEstablishSseConnection(sseConnection);
		observer.didFailToWriteSseEvent(sseConnection, SseEvent.withData("sensitive-payload").event("inventory").build(),
				Duration.ofMillis(2), writeFailure);
		observer.didTerminateSseConnection(sseConnection, StreamTermination.with(StreamTerminationReason.WRITE_FAILED, Duration.ZERO)
				.cause(writeFailure)
				.build());

		SpanData span = onlySpan(harness);
		Assertions.assertTrue(span.getEvents().stream().map(EventData::getName).anyMatch("exception"::equals));
		Assertions.assertFalse(span.toString().contains("sensitive-payload"));
		Assertions.assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
	}

	@Test
	public void mcpJsonRpcSpanRecordsOutcomeAndErrors() {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = tracedRequest(HttpMethod.POST, "/mcp");

		observer.didStartMcpRequestHandling(request, TestMcpEndpoint.class, "session-1", "tools/call",
				McpJsonRpcRequestId.fromString("1"));
		observer.didFinishMcpRequestHandling(request, TestMcpEndpoint.class, "session-1", "tools/call",
				McpJsonRpcRequestId.fromString("1"), McpRequestOutcome.JSON_RPC_ERROR,
				McpJsonRpcError.fromCodeAndMessage(-32603, "Internal error"), Duration.ofMillis(10), List.of());

		SpanData span = onlySpan(harness);
		Assertions.assertEquals("MCP tools/call", span.getName());
		Assertions.assertEquals(SpanKind.SERVER, span.getKind());
		Assertions.assertEquals("mcp", span.getAttributes().get(SERVER_TYPE_ATTRIBUTE_KEY));
		Assertions.assertEquals("jsonrpc", span.getAttributes().get(RPC_SYSTEM_ATTRIBUTE_KEY));
		Assertions.assertEquals("tools/call", span.getAttributes().get(RPC_METHOD_ATTRIBUTE_KEY));
		Assertions.assertEquals(TestMcpEndpoint.class.getName(), span.getAttributes().get(MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY));
		Assertions.assertEquals(Boolean.TRUE, span.getAttributes().get(MCP_SESSION_ID_PRESENT_ATTRIBUTE_KEY));
		Assertions.assertEquals(Boolean.TRUE, span.getAttributes().get(MCP_REQUEST_ID_PRESENT_ATTRIBUTE_KEY));
		Assertions.assertEquals("json_rpc_error", span.getAttributes().get(MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY));
		Assertions.assertEquals(-32603L, requireNonNull(span.getAttributes().get(MCP_JSON_RPC_ERROR_CODE_ATTRIBUTE_KEY)));
		Assertions.assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
	}

	@Test
	public void mcpSseStreamSpanEndsWithTerminationReason() {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = tracedRequest(HttpMethod.GET, "/mcp");
		TestMcpSseStream stream = new TestMcpSseStream(request, TestMcpEndpoint.class, "session-1", Instant.now());

		observer.didEstablishMcpSseStream(stream);
		Assertions.assertEquals(Integer.valueOf(1), observer.getActiveSpanCount());
		observer.didTerminateMcpSseStream(stream, StreamTermination.with(StreamTerminationReason.SESSION_TERMINATED, Duration.ZERO).build());

		SpanData span = onlySpan(harness);
		Assertions.assertEquals("MCP SSE TestMcpEndpoint", span.getName());
		Assertions.assertEquals(SpanKind.SERVER, span.getKind());
		Assertions.assertEquals("mcp", span.getAttributes().get(SERVER_TYPE_ATTRIBUTE_KEY));
		Assertions.assertEquals(TestMcpEndpoint.class.getName(), span.getAttributes().get(MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY));
		Assertions.assertEquals("session_terminated", span.getAttributes().get(STREAM_TERMINATION_REASON_ATTRIBUTE_KEY));
		Assertions.assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
	}

	@Test
	public void concurrentHttpSpansDoNotCrossContaminateTraceIds() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/widgets/{id}", "widget");
		Integer requestCount = 16;
		CountDownLatch startLatch = new CountDownLatch(1);
		ExecutorService executorService = Executors.newFixedThreadPool(4);
		Collection<Runnable> tasks = new ArrayList<>();

		for (int index = 0; index < requestCount; ++index) {
			int requestIndex = index;
			tasks.add(() -> {
				try {
					startLatch.await();
					Request request = tracedRequest(HttpMethod.GET, "/widgets/" + requestIndex,
							"00-0af7651916cd43dd8448eb211c8031" + Character.forDigit(requestIndex, 16) + "-b7ad6b7169203331-01");

					observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
					observer.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod,
							MarshaledResponse.fromStatusCode(200), Duration.ofMillis(1), List.of());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException(e);
				}
			});
		}

		try {
			for (Runnable task : tasks)
				executorService.submit(task);

			startLatch.countDown();
			executorService.shutdown();
			Assertions.assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
		} finally {
			executorService.shutdownNow();
		}

		List<SpanData> spans = harness.spanExporter().getFinishedSpanItems()
				.stream()
				.sorted(Comparator.comparing(SpanData::getName))
				.toList();
		Assertions.assertEquals(requestCount.intValue(), spans.size());
		Assertions.assertEquals(requestCount.intValue(), spans.stream().map(SpanData::getTraceId).distinct().count());
		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
	}

	@Test
	public void telemetryApiFailuresAreContained() throws Exception {
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withTracer(spanName -> {
					throw new RuntimeException("tracer unavailable");
				})
				.build();
		Request request = Request.fromPath(HttpMethod.GET, "/widgets/123");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/widgets/{id}", "widget");

		Assertions.assertDoesNotThrow(() -> observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod));
		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
	}

	@Test
	public void closeDrainsActiveSpans() throws Exception {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(harness.openTelemetrySdk())
				.build();
		Request request = Request.fromPath(HttpMethod.GET, "/widgets/123");
		ResourceMethod resourceMethod = createResourceMethod(HttpMethod.GET, "/widgets/{id}", "widget");

		observer.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		Assertions.assertEquals(Integer.valueOf(1), observer.getActiveSpanCount());

		observer.close();
		observer.close();

		SpanData span = onlySpan(harness);
		Assertions.assertEquals("server_stopping", span.getAttributes().get(STREAM_TERMINATION_REASON_ATTRIBUTE_KEY));
		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
	}

	private static Request tracedRequest(@NonNull HttpMethod httpMethod,
																			 @NonNull String path) {
		return tracedRequest(httpMethod, path, TRACEPARENT);
	}

	private static Request tracedRequest(@NonNull HttpMethod httpMethod,
																			 @NonNull String path,
																			 @NonNull String traceparent) {
		return Request.withPath(httpMethod, path)
				.headers(Map.of("traceparent", Set.of(traceparent)))
				.build();
	}

	private static ResourceMethod createResourceMethod(HttpMethod httpMethod,
																										 String route,
																										 String methodName) throws Exception {
		Method method = TestResources.class.getDeclaredMethod(methodName);
		return ResourceMethod.fromComponents(
				httpMethod,
				ResourcePathDeclaration.fromPath(route),
				method,
				false
		);
	}

	@NonNull
	private static SpanData onlySpan(@NonNull TestHarness harness) {
		requireNonNull(harness);

		List<SpanData> spans = harness.spanExporter().getFinishedSpanItems();
		Assertions.assertEquals(1, spans.size());
		return spans.get(0);
	}

	private record TestHarness(@NonNull InMemorySpanExporter spanExporter,
														 @NonNull OpenTelemetrySdk openTelemetrySdk,
														 @NonNull SdkTracerProvider sdkTracerProvider) {
		@NonNull
		static TestHarness create() {
			InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
			SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
					.addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
					.build();
			OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
					.setTracerProvider(sdkTracerProvider)
					.build();

			return new TestHarness(spanExporter, openTelemetrySdk, sdkTracerProvider);
		}
	}

	private static final class TestResources {
		public static void widget() {
			// No-op
		}

		public static void download() {
			// No-op
		}

		public static void events() {
			// No-op
		}
	}

	private static final class TestMcpEndpoint implements McpEndpoint {
		// Marker type
	}

	private record TestStreamingResponseHandle(
			@NonNull Request request,
			@NonNull ResourceMethod resourceMethod,
			@NonNull MarshaledResponse marshaledResponse,
			@NonNull Instant establishedAt
	) implements StreamingResponseHandle {
		private TestStreamingResponseHandle {
			requireNonNull(request);
			requireNonNull(resourceMethod);
			requireNonNull(marshaledResponse);
			requireNonNull(establishedAt);
		}

		@Override
		@NonNull
		public ServerType getServerType() {
			return ServerType.STANDARD_HTTP;
		}

		@Override
		@NonNull
		public Request getRequest() {
			return this.request;
		}

		@Override
		@NonNull
		public Optional<ResourceMethod> getResourceMethod() {
			return Optional.of(this.resourceMethod);
		}

		@Override
		@NonNull
		public MarshaledResponse getMarshaledResponse() {
			return this.marshaledResponse;
		}

		@Override
		@NonNull
		public Instant getEstablishedAt() {
			return this.establishedAt;
		}
	}

	private record TestSseConnection(
			@NonNull Request request,
			@NonNull ResourceMethod resourceMethod,
			@NonNull Instant establishedAt
	) implements SseConnection {
		private TestSseConnection {
			requireNonNull(request);
			requireNonNull(resourceMethod);
			requireNonNull(establishedAt);
		}

		@Override
		@NonNull
		public Request getRequest() {
			return this.request;
		}

		@Override
		@NonNull
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@Override
		@NonNull
		public Instant getEstablishedAt() {
			return this.establishedAt;
		}

		@Override
		@NonNull
		public Optional<Object> getClientContext() {
			return Optional.empty();
		}
	}

	private record TestMcpSseStream(
			@NonNull Request request,
			@NonNull Class<? extends McpEndpoint> endpointClass,
			@NonNull String sessionId,
			@NonNull Instant establishedAt
	) implements McpSseStream {
		private TestMcpSseStream {
			requireNonNull(request);
			requireNonNull(endpointClass);
			requireNonNull(sessionId);
			requireNonNull(establishedAt);
		}

		@Override
		@NonNull
		public Request getRequest() {
			return this.request;
		}

		@Override
		@NonNull
		public Class<? extends McpEndpoint> getEndpointClass() {
			return this.endpointClass;
		}

		@Override
		@NonNull
		public String getSessionId() {
			return this.sessionId;
		}

		@Override
		@NonNull
		public Instant getEstablishedAt() {
			return this.establishedAt;
		}
	}
}
