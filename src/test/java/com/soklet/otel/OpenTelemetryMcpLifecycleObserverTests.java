/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.otel;

import com.soklet.HttpMethod;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpEndpoint;
import com.soklet.McpImplementation;
import com.soklet.McpInputResponses;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonRpcError;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestId;
import com.soklet.McpRequestOutcome;
import com.soklet.Request;
import com.soklet.ResourceMethod;
import com.soklet.SseConnection;
import com.soklet.StreamingResponseHandle;
import com.soklet.TraceContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class OpenTelemetryMcpLifecycleObserverTests {
	private static final String MCP_TRACEPARENT =
			"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
	private static final String HTTP_TRACEPARENT =
			"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
	private static final String ENDPOINT_PATH = "/mcp/v20";
	private static final String RECOGNIZED_METHOD = "tools/call";
	private static final String UNRECOGNIZED_METHOD = "notifications/vendor.secret-canary";
	private static final String UNRECOGNIZED_METHOD_LABEL = "<unrecognized>";
	private static final Set<String> CORE_METHODS = Set.of(
			"server/discover", "tools/list", "tools/call", "prompts/list",
			"prompts/get", "resources/list", "resources/templates/list",
			"resources/read", "subscriptions/listen", "notifications/cancelled");
	private static final McpEndpoint ENDPOINT = McpEndpoint.withPath(
			ENDPOINT_PATH, McpImplementation.withNameAndVersion(
					"otel-mcp-tests", "2.0.0").build())
			.build();

	private static final AttributeKey<String> SERVER_TYPE_ATTRIBUTE_KEY =
			AttributeKey.stringKey("soklet.server.type");
	private static final AttributeKey<String> RPC_SYSTEM_NAME_ATTRIBUTE_KEY =
			AttributeKey.stringKey("rpc.system.name");
	private static final AttributeKey<String> RPC_METHOD_ATTRIBUTE_KEY =
			AttributeKey.stringKey("rpc.method");
	private static final AttributeKey<String> MCP_ENDPOINT_ATTRIBUTE_KEY =
			AttributeKey.stringKey("soklet.mcp.endpoint");
	private static final AttributeKey<String> MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY =
			AttributeKey.stringKey("soklet.mcp.request.outcome");
	private static final AttributeKey<String> RPC_RESPONSE_STATUS_CODE_ATTRIBUTE_KEY =
			AttributeKey.stringKey("rpc.response.status_code");
	private static final AttributeKey<String> ERROR_TYPE_ATTRIBUTE_KEY =
			AttributeKey.stringKey("error.type");
	private static final AttributeKey<String> CLIENT_ADDRESS_ATTRIBUTE_KEY =
			AttributeKey.stringKey("client.address");
	private static final AttributeKey<String> REQUEST_ID_ATTRIBUTE_KEY =
			AttributeKey.stringKey("soklet.request.id");

	@Test
	public void mcpMetadataTraceContextIsTheOnlyRemoteParentAndPreservesTraceState() {
		TestHarness parentHarness = TestHarness.create();
		OpenTelemetryLifecycleObserver parentObserver = observer(parentHarness);
		Request requestWithDifferentHttpParent = physicalRequest(
				"physical-parent-request", HTTP_TRACEPARENT);
		TraceContext mcpTraceContext = traceContext(MCP_TRACEPARENT,
				List.of("rojo=00f067aa0ba902b7,congo=t61rcWkgMzE"));
		McpRequestContext context = context(RECOGNIZED_METHOD,
				requestWithDifferentHttpParent, Optional.of(mcpTraceContext)).context();

		parentObserver.didStartMcpRequestHandling(context);
		parentObserver.didFinishMcpRequestHandling(context, McpRequestOutcome.COMPLETE,
				null, Duration.ofMillis(7), List.of());

		SpanData child = onlySpan(parentHarness);
		Assertions.assertEquals(Duration.ofMillis(7).toNanos(),
				child.getEndEpochNanos() - child.getStartEpochNanos());
		Assertions.assertEquals("0af7651916cd43dd8448eb211c80319c",
				child.getTraceId());
		Assertions.assertEquals("b7ad6b7169203331", child.getParentSpanId());
		Assertions.assertTrue(child.getParentSpanContext().isRemote());
		Assertions.assertEquals(TraceFlags.getSampled(),
				child.getParentSpanContext().getTraceFlags());
		Assertions.assertTrue(child.getParentSpanContext().isSampled());
		Assertions.assertEquals("00f067aa0ba902b7",
				child.getParentSpanContext().getTraceState().get("rojo"));
		Assertions.assertEquals("t61rcWkgMzE",
				child.getParentSpanContext().getTraceState().get("congo"));
		Assertions.assertEquals(List.of("rojo", "congo"), List.copyOf(
				child.getParentSpanContext().getTraceState().asMap().keySet()));

		TestHarness rootHarness = TestHarness.create();
		OpenTelemetryLifecycleObserver rootObserver = observer(rootHarness);
		McpRequestContext noMcpParent = context(RECOGNIZED_METHOD,
				requestWithDifferentHttpParent, Optional.empty()).context();
		Span ambient = Span.wrap(SpanContext.create(
				"11111111111111111111111111111111",
				"2222222222222222", TraceFlags.getSampled(), TraceState.getDefault()));
		try (Scope ignored = ambient.makeCurrent()) {
			rootObserver.didStartMcpRequestHandling(noMcpParent);
		}
		rootObserver.didFinishMcpRequestHandling(noMcpParent,
				McpRequestOutcome.COMPLETE, null, Duration.ofMillis(1), List.of());

		SpanData root = onlySpan(rootHarness);
		Assertions.assertEquals(SpanId.getInvalid(), root.getParentSpanId());
		Assertions.assertFalse(root.getParentSpanContext().isValid(),
				"Physical HTTP trace headers must not parent admitted MCP request spans.");
		Assertions.assertNotEquals("4bf92f3577b34da6a3ce929d0e0e4736",
				root.getTraceId());
	}

	@Test
	public void mcpSpanUsesExactDefaultAndCustomNamesAttributesAndTerminalSemantics() {
		for (String coreMethod : CORE_METHODS) {
			TestHarness harness = TestHarness.create();
			OpenTelemetryLifecycleObserver observer = observer(harness);
			McpRequestContext context = context(coreMethod,
					physicalRequest("physical-default-" + coreMethod, null),
					Optional.empty()).context();

			observer.didStartMcpRequestHandling(context);
			observer.didFinishMcpRequestHandling(context, McpRequestOutcome.COMPLETE,
					null, Duration.ofMillis(2), List.of());

			SpanData span = onlySpan(harness);
			Assertions.assertEquals("MCP " + coreMethod, span.getName());
			assertStartAttributes(span, coreMethod);
			Assertions.assertEquals("complete",
					span.getAttributes().get(MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY));
			Assertions.assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
			Assertions.assertNull(span.getAttributes().get(ERROR_TYPE_ATTRIBUTE_KEY));
			Assertions.assertNull(span.getAttributes().get(CLIENT_ADDRESS_ATTRIBUTE_KEY));
			Assertions.assertNull(span.getAttributes().get(REQUEST_ID_ATTRIBUTE_KEY));
		}

		Request optInRequest = Request.withPath(HttpMethod.POST, ENDPOINT_PATH)
				.id("physical-request-id")
				.remoteAddress(new InetSocketAddress(
						InetAddress.getLoopbackAddress(), 54_321))
				.build();
		ContextFixture unsupportedFixture = context(UNRECOGNIZED_METHOD,
				optInRequest, Optional.empty());
		TestHarness boundedHarness = TestHarness.create();
		OpenTelemetryLifecycleObserver boundedObserver = observer(boundedHarness);

		boundedObserver.didStartMcpRequestHandling(unsupportedFixture.context());
		boundedObserver.didFinishMcpRequestHandling(unsupportedFixture.context(),
				McpRequestOutcome.COMPLETE, null, Duration.ZERO, List.of());

		SpanData bounded = onlySpan(boundedHarness);
		Assertions.assertEquals("MCP " + UNRECOGNIZED_METHOD_LABEL,
				bounded.getName());
		assertStartAttributes(bounded, UNRECOGNIZED_METHOD_LABEL);
		Assertions.assertFalse(bounded.getAttributes().asMap().keySet().stream()
				.map(AttributeKey::getKey)
				.anyMatch("rpc.method_original"::equals));

		TestHarness customHarness = TestHarness.create();
		OpenTelemetryLifecycleObserver customObserver = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(customHarness.openTelemetrySdk())
				.spanNamingStrategy(customNamingStrategy())
				.spanPolicy(SpanPolicy.builder()
						.recordClientAddress(true)
						.recordRequestId(true)
						.build())
				.build();

		customObserver.didStartMcpRequestHandling(unsupportedFixture.context());
		customObserver.didFinishMcpRequestHandling(unsupportedFixture.context(),
				McpRequestOutcome.COMPLETE, null, Duration.ZERO, List.of());

		SpanData custom = onlySpan(customHarness);
		Assertions.assertEquals("custom " + UNRECOGNIZED_METHOD, custom.getName(),
				"A custom naming strategy remains application-owned and sees the raw context method.");
		Assertions.assertEquals(UNRECOGNIZED_METHOD_LABEL,
				custom.getAttributes().get(RPC_METHOD_ATTRIBUTE_KEY));
		Assertions.assertEquals(InetAddress.getLoopbackAddress().getHostAddress(),
				custom.getAttributes().get(CLIENT_ADDRESS_ATTRIBUTE_KEY));
		Assertions.assertEquals("physical-request-id",
				custom.getAttributes().get(REQUEST_ID_ATTRIBUTE_KEY));
		Assertions.assertNotEquals("jsonrpc-id-canary",
				custom.getAttributes().get(REQUEST_ID_ATTRIBUTE_KEY));
	}

	@Test
	public void allMcpRequestOutcomesMapToExactStatusAndErrorVocabulary() {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = observer(harness);
		Set<McpRequestOutcome> errorOutcomes = Set.of(
				McpRequestOutcome.REJECTED,
				McpRequestOutcome.APPLICATION_ERROR,
				McpRequestOutcome.PROTOCOL_ERROR,
				McpRequestOutcome.INTERNAL_ERROR,
				McpRequestOutcome.DEADLINE_EXCEEDED,
				McpRequestOutcome.WRITE_FAILED);

		for (McpRequestOutcome outcome : McpRequestOutcome.values()) {
			String method = "tools/call";
			McpRequestContext context = context(method,
					physicalRequest("outcome-" + outcome.name(), null),
					Optional.empty()).context();
			observer.didStartMcpRequestHandling(context);
			observer.didFinishMcpRequestHandling(context, outcome, null,
					Duration.ofNanos(123), List.of());

			SpanData span = harness.spanExporter().getFinishedSpanItems()
					.get(harness.spanExporter().getFinishedSpanItems().size() - 1);
			String outcomeValue = enumValue(outcome);
			Assertions.assertEquals(outcomeValue,
					span.getAttributes().get(MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY));
			if (errorOutcomes.contains(outcome)) {
				Assertions.assertEquals(StatusCode.ERROR,
						span.getStatus().getStatusCode(), outcome.name());
				Assertions.assertEquals(outcomeValue,
						span.getAttributes().get(ERROR_TYPE_ATTRIBUTE_KEY), outcome.name());
			} else {
				Assertions.assertEquals(StatusCode.UNSET,
						span.getStatus().getStatusCode(), outcome.name());
				Assertions.assertNull(span.getAttributes().get(ERROR_TYPE_ATTRIBUTE_KEY),
						outcome.name());
			}
			Assertions.assertEquals("", span.getStatus().getDescription(),
					outcome.name());
			Assertions.assertNull(span.getAttributes()
					.get(RPC_RESPONSE_STATUS_CODE_ATTRIBUTE_KEY), outcome.name());
			Assertions.assertEquals(List.of(), span.getEvents(), outcome.name());
		}

		McpJsonRpcError exactError = McpJsonRpcError.fromApplication(123_456,
				"client-visible-error-message-canary",
				McpJsonObject.builder()
						.put("secret-error-data", "error-data-canary")
						.build());
		RuntimeException contained = new RuntimeException("throwable-material-canary");
		McpRequestContext errorContext = context(RECOGNIZED_METHOD,
				physicalRequest("error-code", null), Optional.empty()).context();
		observer.didStartMcpRequestHandling(errorContext);
		observer.didFinishMcpRequestHandling(errorContext,
				McpRequestOutcome.COMPLETE, exactError, Duration.ofMillis(1),
				List.of(contained));

		SpanData errorSpan = harness.spanExporter().getFinishedSpanItems()
				.get(harness.spanExporter().getFinishedSpanItems().size() - 1);
		Assertions.assertEquals(StatusCode.ERROR,
				errorSpan.getStatus().getStatusCode());
		Assertions.assertEquals("", errorSpan.getStatus().getDescription());
		Assertions.assertEquals("complete",
				errorSpan.getAttributes().get(MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY));
		Assertions.assertEquals("123456",
				errorSpan.getAttributes().get(RPC_RESPONSE_STATUS_CODE_ATTRIBUTE_KEY));
		Assertions.assertEquals("123456",
				errorSpan.getAttributes().get(ERROR_TYPE_ATTRIBUTE_KEY));
		Assertions.assertEquals(List.of(), errorSpan.getEvents(),
				"MCP finish throwables are not projected as exception events.");
		assertDoesNotContain(errorSpan.toString(),
				"client-visible-error-message-canary", "error-data-canary",
				"throwable-material-canary");

		RuntimeException throwableOnly =
				new RuntimeException("throwable-only-material-canary");
		McpRequestContext throwableOnlyContext = context(RECOGNIZED_METHOD,
				physicalRequest("throwable-only", null), Optional.empty()).context();
		observer.didStartMcpRequestHandling(throwableOnlyContext);
		observer.didFinishMcpRequestHandling(throwableOnlyContext,
				McpRequestOutcome.COMPLETE, null, Duration.ofMillis(1),
				List.of(throwableOnly));

		SpanData throwableOnlySpan = harness.spanExporter().getFinishedSpanItems()
				.get(harness.spanExporter().getFinishedSpanItems().size() - 1);
		Assertions.assertEquals(StatusCode.UNSET,
				throwableOnlySpan.getStatus().getStatusCode());
		Assertions.assertEquals("",
				throwableOnlySpan.getStatus().getDescription());
		Assertions.assertEquals("complete", throwableOnlySpan.getAttributes()
				.get(MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY));
		Assertions.assertNull(throwableOnlySpan.getAttributes()
				.get(RPC_RESPONSE_STATUS_CODE_ATTRIBUTE_KEY));
		Assertions.assertNull(throwableOnlySpan.getAttributes()
				.get(ERROR_TYPE_ATTRIBUTE_KEY));
		Assertions.assertEquals(List.of(), throwableOnlySpan.getEvents());
		assertDoesNotContain(throwableOnlySpan.toString(),
				"throwable-only-material-canary");

		McpRequestContext overflowContext = context(RECOGNIZED_METHOD,
				physicalRequest("overflow-duration", null), Optional.empty()).context();
		observer.didStartMcpRequestHandling(overflowContext);
		Assertions.assertDoesNotThrow(() -> observer.didFinishMcpRequestHandling(
				overflowContext, McpRequestOutcome.COMPLETE, null,
				Duration.ofSeconds(Long.MAX_VALUE, 999_999_999), List.of()));
		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
		Assertions.assertEquals(McpRequestOutcome.values().length + 3,
				harness.spanExporter().getFinishedSpanItems().size());
	}

	@Test
	public void mcpRequestSpanStaysOpenUntilTerminalFinishAcrossStreamAndSubscriptionLifetimes() {
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = observer(harness);
		McpRequestContext streamContext = context("tools/call",
				physicalRequest("stream-request", null), Optional.empty()).context();
		McpRequestContext subscriptionContext = context("subscriptions/listen",
				physicalRequest("subscription-request", null), Optional.empty()).context();

		observer.didStartMcpRequestHandling(streamContext);
		observer.didStartMcpRequestHandling(subscriptionContext);
		Assertions.assertEquals(Integer.valueOf(2), observer.getActiveSpanCount());
		Assertions.assertEquals(List.of(), harness.spanExporter().getFinishedSpanItems(),
				"Stream and subscription establishment do not create or end MCP spans.");

		observer.didFinishMcpRequestHandling(streamContext,
				McpRequestOutcome.CLIENT_DISCONNECTED, null, Duration.ofSeconds(4),
				List.of());
		Assertions.assertEquals(Integer.valueOf(1), observer.getActiveSpanCount());
		Assertions.assertEquals(1, harness.spanExporter().getFinishedSpanItems().size());

		observer.didFinishMcpRequestHandling(subscriptionContext,
				McpRequestOutcome.WRITE_FAILED, null, Duration.ofMinutes(3), List.of());
		List<SpanData> spans = harness.spanExporter().getFinishedSpanItems();
		Assertions.assertEquals(2, spans.size());
		Assertions.assertEquals(Set.of("MCP tools/call", "MCP subscriptions/listen"),
				spans.stream().map(SpanData::getName)
						.collect(java.util.stream.Collectors.toSet()));
		Assertions.assertEquals(Integer.valueOf(0), observer.getActiveSpanCount());
		Map<String, Long> durationsByName = spans.stream().collect(
				java.util.stream.Collectors.toMap(SpanData::getName,
						span -> span.getEndEpochNanos() - span.getStartEpochNanos()));
		Assertions.assertEquals(Duration.ofSeconds(4).toNanos(),
				durationsByName.get("MCP tools/call"));
		Assertions.assertEquals(Duration.ofMinutes(3).toNanos(),
				durationsByName.get("MCP subscriptions/listen"));
	}

	@Test
	public void mcpPolicyAndNamingAreModernAdditiveAndLegacySessionControlsRemainAbsent()
			throws Exception {
		Assertions.assertEquals(Boolean.TRUE,
				SpanPolicy.defaultInstance().recordMcpRequestSpans());
		Assertions.assertEquals(Boolean.FALSE, SpanPolicy.builder()
				.recordMcpRequestSpans(false)
				.build()
				.recordMcpRequestSpans());
		Assertions.assertThrows(NullPointerException.class,
				() -> SpanPolicy.builder().recordMcpRequestSpans(null));
		Assertions.assertTrue(SpanPolicy.defaultInstance().toString()
				.contains("recordMcpRequestSpans=true"));

		Method defaultMcpName = SpanNamingStrategy.class.getMethod(
				"mcpRequestSpanName", McpRequestContext.class);
		Assertions.assertEquals(String.class, defaultMcpName.getReturnType());
		Assertions.assertTrue(defaultMcpName.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(defaultMcpName.getAnnotatedParameterTypes()[0]
				.isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(defaultMcpName.isDefault(),
				"Existing three-method naming strategies remain source-compatible.");
		Method policyGetter = SpanPolicy.class.getMethod("recordMcpRequestSpans");
		Assertions.assertEquals(Boolean.class, policyGetter.getReturnType());
		Assertions.assertTrue(policyGetter.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		Method policyBuilder = SpanPolicy.Builder.class.getMethod(
				"recordMcpRequestSpans", Boolean.class);
		Assertions.assertEquals(SpanPolicy.Builder.class, policyBuilder.getReturnType());
		Assertions.assertTrue(policyBuilder.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(policyBuilder.getAnnotatedParameterTypes()[0]
				.isAnnotationPresent(NonNull.class));
		SpanNamingStrategy legacyThreeMethodStrategy = legacyThreeMethodNamingStrategy();
		McpRequestContext recognized = context(RECOGNIZED_METHOD,
				physicalRequest("legacy-namer", null), Optional.empty()).context();
		Assertions.assertEquals("MCP tools/call",
				legacyThreeMethodStrategy.mcpRequestSpanName(recognized));

		Set<String> legacyNames = Set.of(
				"recordMcpSessionEvents", "recordMcpSseStreamSpans",
				"mcpSseStreamSpanName", "didCreateMcpSession",
				"didTerminateMcpSession", "didEstablishMcpSseStream",
				"didTerminateMcpSseStream");
		Set<String> modernSurface = new LinkedHashSet<>();
		modernSurface.addAll(Arrays.stream(SpanPolicy.class.getMethods())
				.map(Method::getName).toList());
		modernSurface.addAll(Arrays.stream(SpanPolicy.Builder.class.getMethods())
				.map(Method::getName).toList());
		modernSurface.addAll(Arrays.stream(SpanNamingStrategy.class.getMethods())
				.map(Method::getName).toList());
		modernSurface.addAll(Arrays.stream(OpenTelemetryLifecycleObserver.class
				.getMethods()).map(Method::getName).toList());
		Assertions.assertTrue(java.util.Collections.disjoint(
				legacyNames, modernSurface));

		TestHarness disabledHarness = TestHarness.create();
		OpenTelemetryLifecycleObserver disabled = OpenTelemetryLifecycleObserver
				.withOpenTelemetry(disabledHarness.openTelemetrySdk())
				.spanPolicy(SpanPolicy.builder()
						.recordMcpRequestSpans(false)
						.build())
				.build();
		disabled.didStartMcpRequestHandling(recognized);
		disabled.didFinishMcpRequestHandling(recognized,
				McpRequestOutcome.COMPLETE, null, Duration.ZERO, List.of());
		Assertions.assertEquals(List.of(), disabledHarness.spanExporter()
				.getFinishedSpanItems());
		Assertions.assertEquals(Integer.valueOf(0), disabled.getActiveSpanCount());
	}

	@Test
	public void mcpTelemetryFailuresAreContainedAndReleaseStateExactlyOnce() {
		OpenTelemetryLifecycleObserver startFailure = OpenTelemetryLifecycleObserver
				.withTracer(spanName -> {
					throw new RuntimeException("tracer-unavailable-canary");
				})
				.build();
		McpRequestContext startFailureContext = context(RECOGNIZED_METHOD,
				physicalRequest("start-failure", null), Optional.empty()).context();
		Assertions.assertDoesNotThrow(() -> startFailure
				.didStartMcpRequestHandling(startFailureContext));
		Assertions.assertEquals(Integer.valueOf(0), startFailure.getActiveSpanCount());

		AtomicInteger endCount = new AtomicInteger();
		OpenTelemetryLifecycleObserver finishFailure = OpenTelemetryLifecycleObserver
				.withTracer(failingFinishTracer(endCount))
				.build();
		McpRequestContext finishFailureContext = context(RECOGNIZED_METHOD,
				physicalRequest("finish-failure", null), Optional.empty()).context();
		finishFailure.didStartMcpRequestHandling(finishFailureContext);
		Assertions.assertEquals(Integer.valueOf(1), finishFailure.getActiveSpanCount());
		Assertions.assertDoesNotThrow(() -> finishFailure.didFinishMcpRequestHandling(
				finishFailureContext, McpRequestOutcome.INTERNAL_ERROR, null,
				Duration.ofMillis(1), List.of()));
		Assertions.assertEquals(Integer.valueOf(0), finishFailure.getActiveSpanCount());
		Assertions.assertEquals(1, endCount.get());
		finishFailure.didFinishMcpRequestHandling(finishFailureContext,
				McpRequestOutcome.COMPLETE, null, Duration.ZERO, List.of());
		Assertions.assertEquals(1, endCount.get());

		TestHarness cleanupHarness = TestHarness.create();
		OpenTelemetryLifecycleObserver cleanupObserver = observer(cleanupHarness);
		McpRequestContext duplicate = context(RECOGNIZED_METHOD,
				physicalRequest("duplicate", null), Optional.empty()).context();
		cleanupObserver.didStartMcpRequestHandling(duplicate);
		cleanupObserver.didStartMcpRequestHandling(duplicate);
		Assertions.assertEquals(1, cleanupHarness.spanExporter()
				.getFinishedSpanItems().size());
		Assertions.assertEquals(Integer.valueOf(1), cleanupObserver.getActiveSpanCount());
		assertPlainCleanup(cleanupHarness.spanExporter().getFinishedSpanItems().get(0));

		cleanupObserver.didFinishMcpRequestHandling(duplicate,
				McpRequestOutcome.COMPLETE, null, Duration.ZERO, List.of());
		McpRequestContext activeAtClose = context(RECOGNIZED_METHOD,
				physicalRequest("active-at-close", null), Optional.empty()).context();
		cleanupObserver.didStartMcpRequestHandling(activeAtClose);
		cleanupObserver.close();
		cleanupObserver.close();
		Assertions.assertEquals(Integer.valueOf(0), cleanupObserver.getActiveSpanCount());
		Assertions.assertEquals(3, cleanupHarness.spanExporter()
				.getFinishedSpanItems().size());
		assertPlainCleanup(cleanupHarness.spanExporter().getFinishedSpanItems().get(2));
		cleanupObserver.didFinishMcpRequestHandling(activeAtClose,
				McpRequestOutcome.COMPLETE, null, Duration.ZERO, List.of());
		cleanupObserver.didFinishMcpRequestHandling(context(RECOGNIZED_METHOD,
				physicalRequest("missing-state", null), Optional.empty()).context(),
				McpRequestOutcome.COMPLETE, null, Duration.ZERO, List.of());
		Assertions.assertEquals(3, cleanupHarness.spanExporter()
				.getFinishedSpanItems().size());
	}

	@Test
	public void concurrentMcpSpansRemainContextIsolatedAndCloseDrainsEveryState()
			throws Exception {
		int requestCount = 16;
		TestHarness finishedHarness = TestHarness.create();
		OpenTelemetryLifecycleObserver finishedObserver = observer(finishedHarness);
		List<McpRequestContext> finishedContexts = contexts(requestCount, "finished");
		runConcurrently(finishedContexts, context -> {
			finishedObserver.didStartMcpRequestHandling(context);
			finishedObserver.didFinishMcpRequestHandling(context,
					McpRequestOutcome.COMPLETE, null, Duration.ofMillis(1), List.of());
		});

		List<SpanData> finished = finishedHarness.spanExporter().getFinishedSpanItems();
		Assertions.assertEquals(requestCount, finished.size());
		Assertions.assertEquals(requestCount,
				finished.stream().map(SpanData::getTraceId).distinct().count());
		Assertions.assertEquals(requestCount,
				finished.stream().map(SpanData::getParentSpanId).distinct().count());
		Assertions.assertEquals(Integer.valueOf(0), finishedObserver.getActiveSpanCount());

		TestHarness closeHarness = TestHarness.create();
		OpenTelemetryLifecycleObserver closeObserver = observer(closeHarness);
		List<McpRequestContext> closeContexts = contexts(requestCount, "close");
		runConcurrently(closeContexts, closeObserver::didStartMcpRequestHandling);
		Assertions.assertEquals(Integer.valueOf(requestCount),
				closeObserver.getActiveSpanCount());
		closeObserver.close();
		Assertions.assertEquals(Integer.valueOf(0), closeObserver.getActiveSpanCount());
		Assertions.assertEquals(requestCount,
				closeHarness.spanExporter().getFinishedSpanItems().size());
		closeHarness.spanExporter().getFinishedSpanItems()
				.forEach(OpenTelemetryMcpLifecycleObserverTests::assertPlainCleanup);

		TestHarness publicationHarness = TestHarness.create();
		CountDownLatch publicationReached = new CountDownLatch(1);
		CountDownLatch publicationRelease = new CountDownLatch(1);
		OpenTelemetryLifecycleObserver publicationObserver =
				OpenTelemetryLifecycleObserver
						.withOpenTelemetry(publicationHarness.openTelemetrySdk())
						.beforeMcpSpanPublicationForTesting(() -> {
							publicationReached.countDown();
							await(publicationRelease);
						})
						.build();
		McpRequestContext publicationContext = context(RECOGNIZED_METHOD,
				physicalRequest("close-publication-race", null),
				Optional.empty()).context();
		ExecutorService publicationExecutor = Executors.newSingleThreadExecutor();
		try {
			Future<?> startFuture = publicationExecutor.submit(() ->
					publicationObserver.didStartMcpRequestHandling(publicationContext));
			await(publicationReached);
			publicationObserver.close();
			publicationRelease.countDown();
			startFuture.get(5, TimeUnit.SECONDS);
		} finally {
			publicationRelease.countDown();
			publicationExecutor.shutdownNow();
		}
		Assertions.assertEquals(Integer.valueOf(0),
				publicationObserver.getActiveSpanCount(),
				"A start published after close's drain must remove its exact state.");
		Assertions.assertEquals(1, publicationHarness.spanExporter()
				.getFinishedSpanItems().size());
		assertPlainCleanup(publicationHarness.spanExporter()
				.getFinishedSpanItems().get(0));
	}

	@Test
	public void mcpSpanProjectionExcludesSensitiveContextAndHttpFallbackCanaries() {
		String physicalRequestId = "physical-request-id-canary";
		Request physicalRequest = Request.withPath(HttpMethod.POST, ENDPOINT_PATH)
				.id(physicalRequestId)
				.headers(Map.of(
						"traceparent", Set.of(HTTP_TRACEPARENT),
						"tracestate", Set.of("httpvendor=http-tracestate-canary"),
						"authorization", Set.of("http-authorization-canary")))
				.build();
		ContextFixture fixture = context(RECOGNIZED_METHOD, physicalRequest,
				Optional.empty());
		McpJsonRpcError error = McpJsonRpcError.fromApplication(777_777,
				"error-message-canary",
				McpJsonObject.builder().put("private", "error-data-canary").build());
		TestHarness harness = TestHarness.create();
		OpenTelemetryLifecycleObserver observer = observer(harness);

		observer.didStartMcpRequestHandling(fixture.context());
		observer.didFinishMcpRequestHandling(fixture.context(),
				McpRequestOutcome.APPLICATION_ERROR, error, Duration.ofMillis(2),
				List.of(new RuntimeException("throwable-canary")));

		SpanData span = onlySpan(harness);
		Assertions.assertEquals(SpanId.getInvalid(), span.getParentSpanId());
		Assertions.assertEquals(Set.of(
				"soklet.server.type", "rpc.system.name", "rpc.method",
				"soklet.mcp.endpoint", "soklet.mcp.request.outcome",
				"rpc.response.status_code", "error.type"),
				span.getAttributes().asMap().keySet().stream()
						.map(AttributeKey::getKey)
						.collect(java.util.stream.Collectors.toSet()));
		Assertions.assertEquals(List.of(), span.getEvents());
		Assertions.assertTrue(fixture.accessedMethods().containsAll(Set.of(
				"getEndpoint", "getJsonRpcMethod", "getTraceContext")));
		Assertions.assertTrue(java.util.Collections.disjoint(
				fixture.accessedMethods(), Set.of(
						"getRequest", "getEndpointPathParameters", "getRequestId", "getProtocolVersion",
						"getOperationName", "getClientInfo", "getClientCapabilities",
						"getRequestMetadata", "getInputResponses",
						"getFrameworkRequestState", "getApplicationRequestState",
						"getDeprecatedLogLevel", "getBaggage", "getAdmissionIdentity")));
		assertDoesNotContain(span.toString(),
				physicalRequestId, "4bf92f3577b34da6a3ce929d0e0e4736",
				"http-tracestate-canary", "http-authorization-canary",
				"jsonrpc-id-canary", "endpoint-path-parameter-canary",
				"protocol-version-canary", "operation-name-canary",
				"client-info-canary", "request-metadata-canary",
				"baggage-key-canary", "baggage-value-canary",
				"admission-partition-canary", "error-message-canary",
				"error-data-canary", "throwable-canary");
	}

	private static void assertStartAttributes(@NonNull SpanData span,
			@NonNull String method) {
		requireNonNull(span);
		requireNonNull(method);
		Assertions.assertEquals(SpanKind.SERVER, span.getKind());
		Assertions.assertEquals(List.of(), span.getLinks());
		Assertions.assertEquals("mcp",
				span.getAttributes().get(SERVER_TYPE_ATTRIBUTE_KEY));
		Assertions.assertEquals("jsonrpc",
				span.getAttributes().get(RPC_SYSTEM_NAME_ATTRIBUTE_KEY));
		Assertions.assertEquals(method,
				span.getAttributes().get(RPC_METHOD_ATTRIBUTE_KEY));
		Assertions.assertEquals(ENDPOINT_PATH,
				span.getAttributes().get(MCP_ENDPOINT_ATTRIBUTE_KEY));
	}

	private static void assertPlainCleanup(@NonNull SpanData span) {
		requireNonNull(span);
		Assertions.assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
		Assertions.assertNull(span.getAttributes().get(MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY));
		Assertions.assertNull(span.getAttributes().get(RPC_RESPONSE_STATUS_CODE_ATTRIBUTE_KEY));
		Assertions.assertNull(span.getAttributes().get(ERROR_TYPE_ATTRIBUTE_KEY));
		Assertions.assertEquals(List.of(), span.getEvents());
	}

	private static void assertDoesNotContain(@NonNull String rendered,
			@NonNull String... canaries) {
		requireNonNull(rendered);
		requireNonNull(canaries);
		for (String canary : canaries)
			Assertions.assertFalse(rendered.contains(requireNonNull(canary)), canary);
	}

	@NonNull
	private static OpenTelemetryLifecycleObserver observer(
			@NonNull TestHarness harness) {
		return OpenTelemetryLifecycleObserver
				.withOpenTelemetry(requireNonNull(harness).openTelemetrySdk())
				.build();
	}

	@NonNull
	private static ContextFixture context(@NonNull String jsonRpcMethod,
			@NonNull Request request,
			@NonNull Optional<@NonNull TraceContext> traceContext) {
		requireNonNull(jsonRpcMethod);
		requireNonNull(request);
		requireNonNull(traceContext);
		Set<String> accessedMethods = java.util.Collections.synchronizedSet(
				new LinkedHashSet<>());
		InvocationHandler invocationHandler = (proxy, method, arguments) -> {
			String methodName = method.getName();
			if (method.getDeclaringClass() == Object.class) {
				return switch (methodName) {
					case "equals" -> proxy == requireNonNull(arguments)[0];
					case "hashCode" -> System.identityHashCode(proxy);
					case "toString" -> "TestMcpRequestContext";
					default -> throw new AssertionError(methodName);
				};
			}

			accessedMethods.add(methodName);
			return switch (methodName) {
				case "getRequest" -> request;
				case "getEndpoint" -> ENDPOINT;
				case "getEndpointPathParameters" ->
						Map.of("tenant", "endpoint-path-parameter-canary");
				case "getJsonRpcMethod" -> jsonRpcMethod;
				case "getRequestId" -> Optional.of(
						McpRequestId.fromString("jsonrpc-id-canary"));
				case "getProtocolVersion" -> "protocol-version-canary";
				case "getOperationName" -> Optional.of("operation-name-canary");
				case "getClientInfo" -> Optional.of(
						McpImplementation.withNameAndVersion(
								"client-info-canary", "1").build());
				case "getClientCapabilities" -> null;
				case "getRequestMetadata" -> McpJsonObject.builder()
						.put("private", "request-metadata-canary").build();
				case "getInputResponses" -> McpInputResponses.emptyInstance();
				case "getFrameworkRequestState", "getApplicationRequestState" ->
						Optional.empty();
				case "getDeprecatedLogLevel" -> Optional.empty();
				case "getTraceContext" -> traceContext;
				case "getBaggage" -> Map.of(
						"baggage-key-canary", "baggage-value-canary");
				case "getAdmissionIdentity" -> McpAdmissionIdentity
						.withRateLimitPartitionKey("admission-partition-canary")
						.build();
				default -> throw new AssertionError("Unexpected context method: " + method);
			};
		};
		McpRequestContext context = (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class}, invocationHandler);
		return new ContextFixture(context, accessedMethods);
	}

	@NonNull
	private static Request physicalRequest(@NonNull String requestId,
			@Nullable String traceparent) {
		requireNonNull(requestId);
		Request.PathBuilder builder = Request.withPath(HttpMethod.POST, ENDPOINT_PATH)
				.id(requestId);
		if (traceparent != null)
			builder.headers(Map.of("traceparent", Set.of(traceparent)));
		return builder.build();
	}

	@NonNull
	private static TraceContext traceContext(@NonNull String traceparent,
			@NonNull List<@NonNull String> tracestate) {
		return TraceContext.fromHeaderValues(List.of(requireNonNull(traceparent)),
				requireNonNull(tracestate)).orElseThrow();
	}

	@NonNull
	private static SpanNamingStrategy legacyThreeMethodNamingStrategy() {
		return new SpanNamingStrategy() {
			@Override
			@NonNull
			public String httpRequestSpanName(@NonNull Request request,
					@Nullable ResourceMethod resourceMethod) {
				return "legacy-http";
			}

			@Override
			@NonNull
			public String streamingResponseSpanName(
					@NonNull StreamingResponseHandle stream) {
				return "legacy-stream";
			}

			@Override
			@NonNull
			public String sseConnectionSpanName(@NonNull SseConnection connection) {
				return "legacy-sse";
			}
		};
	}

	@NonNull
	private static SpanNamingStrategy customNamingStrategy() {
		return new SpanNamingStrategy() {
			@Override
			@NonNull
			public String httpRequestSpanName(@NonNull Request request,
					@Nullable ResourceMethod resourceMethod) {
				return "custom-http";
			}

			@Override
			@NonNull
			public String streamingResponseSpanName(
					@NonNull StreamingResponseHandle stream) {
				return "custom-stream";
			}

			@Override
			@NonNull
			public String sseConnectionSpanName(@NonNull SseConnection connection) {
				return "custom-sse";
			}

			@Override
			@NonNull
			public String mcpRequestSpanName(@NonNull McpRequestContext context) {
				return "custom " + context.getJsonRpcMethod();
			}
		};
	}

	@NonNull
	private static Tracer failingFinishTracer(@NonNull AtomicInteger endCount) {
		requireNonNull(endCount);
		Span span = (Span) Proxy.newProxyInstance(Span.class.getClassLoader(),
				new Class<?>[]{Span.class}, (proxy, method, arguments) -> {
					if ("setAttribute".equals(method.getName())
							&& arguments != null && arguments.length > 0
							&& arguments[0] instanceof AttributeKey<?> attributeKey
							&& "soklet.mcp.request.outcome".equals(attributeKey.getKey()))
						throw new RuntimeException("finish-projection-failure-canary");
					if ("end".equals(method.getName())) {
						endCount.incrementAndGet();
						return null;
					}
					return defaultProxyValue(proxy, method);
				});
		SpanBuilder[] builderReference = new SpanBuilder[1];
		builderReference[0] = (SpanBuilder) Proxy.newProxyInstance(
				SpanBuilder.class.getClassLoader(), new Class<?>[]{SpanBuilder.class},
				(proxy, method, arguments) -> {
					if ("startSpan".equals(method.getName()))
						return span;
					if (SpanBuilder.class.isAssignableFrom(method.getReturnType()))
						return builderReference[0];
					return defaultProxyValue(proxy, method);
				});
		return spanName -> builderReference[0];
	}

	@Nullable
	private static Object defaultProxyValue(@NonNull Object proxy,
			@NonNull Method method) {
		Class<?> returnType = requireNonNull(method).getReturnType();
		if (returnType.isInstance(proxy))
			return proxy;
		if (!returnType.isPrimitive())
			return null;
		if (returnType == boolean.class)
			return false;
		if (returnType == byte.class)
			return (byte) 0;
		if (returnType == short.class)
			return (short) 0;
		if (returnType == int.class)
			return 0;
		if (returnType == long.class)
			return 0L;
		if (returnType == float.class)
			return 0.0F;
		if (returnType == double.class)
			return 0.0D;
		if (returnType == char.class)
			return '\0';
		return null;
	}

	@NonNull
	private static List<McpRequestContext> contexts(int count,
			@NonNull String prefix) {
		List<McpRequestContext> contexts = new ArrayList<>();
		for (int index = 0; index < count; ++index) {
			String traceparent = "00-%032x-%016x-01".formatted(index + 1,
					index + 1);
			contexts.add(context(RECOGNIZED_METHOD,
					physicalRequest(prefix + '-' + index, null),
					Optional.of(traceContext(traceparent, List.of()))).context());
		}
		return List.copyOf(contexts);
	}

	private static void runConcurrently(
			@NonNull Collection<@NonNull McpRequestContext> contexts,
			java.util.function.Consumer<McpRequestContext> action)
			throws Exception {
		requireNonNull(contexts);
		requireNonNull(action);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			for (McpRequestContext context : contexts) {
				executor.submit(() -> {
					try {
						start.await();
						action.accept(context);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new RuntimeException(exception);
					}
				});
			}
			start.countDown();
			executor.shutdown();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	private static void await(@NonNull CountDownLatch latch) {
		try {
			Assertions.assertTrue(requireNonNull(latch).await(5, TimeUnit.SECONDS),
					"Timed out waiting for deterministic MCP span publication barrier.");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(exception);
		}
	}

	@NonNull
	private static String enumValue(@NonNull Enum<?> value) {
		return requireNonNull(value).name().toLowerCase(Locale.ROOT);
	}

	@NonNull
	private static SpanData onlySpan(@NonNull TestHarness harness) {
		List<SpanData> spans = requireNonNull(harness).spanExporter()
				.getFinishedSpanItems();
		Assertions.assertEquals(1, spans.size());
		return spans.get(0);
	}

	private record ContextFixture(@NonNull McpRequestContext context,
			@NonNull Set<@NonNull String> accessedMethods) {
		private ContextFixture {
			requireNonNull(context);
			requireNonNull(accessedMethods);
		}
	}

	private record TestHarness(@NonNull InMemorySpanExporter spanExporter,
			@NonNull OpenTelemetrySdk openTelemetrySdk,
			@NonNull SdkTracerProvider sdkTracerProvider) {
		@NonNull
		private static TestHarness create() {
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

}
