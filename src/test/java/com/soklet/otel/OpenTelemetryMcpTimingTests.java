package com.soklet.otel;

import com.soklet.CorsAuthorizer;
import com.soklet.HttpMethod;
import com.soklet.LifecycleObserver;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpJsonRpcError;
import com.soklet.McpProtocolVersion;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpServer;
import com.soklet.McpSimulation;
import com.soklet.McpToolRegistration;
import com.soklet.Request;
import com.soklet.ResourceMethodResolver;
import com.soklet.SokletConfig;
import com.soklet.SokletSimulator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class OpenTelemetryMcpTimingTests {
	@Test void slowComposedStartObserverDoesNotShiftTheSemanticInterval() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
		try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
			 OpenTelemetryLifecycleObserver otel = OpenTelemetryLifecycleObserver.withOpenTelemetry(sdk).build()) {
			CountDownLatch finished = new CountDownLatch(1);
			AtomicReference<Instant> origin = new AtomicReference<>();
			AtomicReference<Duration> duration = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			LifecycleObserver observer = new LifecycleObserver() {
				@Override public void didStartMcpRequestHandling(McpRequestContext context) {
					try {
						origin.set(context.getStartedAt().orElseThrow());
						Thread.sleep(100);
						otel.didStartMcpRequestHandling(context);
					} catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
				}
				@Override public void didFinishMcpRequestHandling(McpRequestContext context, McpRequestOutcome outcome,
						McpJsonRpcError error, Duration elapsed, List<Throwable> throwables) {
					try {
						assertEquals(McpRequestOutcome.COMPLETE, outcome);
						duration.set(elapsed);
						otel.didFinishMcpRequestHandling(context, outcome, error, elapsed, throwables);
					} catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
					finally { finished.countDown(); }
				}
			};
			SokletSimulator.run(config(observer), simulator -> {
				try (McpSimulation request = simulator.startMcpRequest(request("slow-start"))) {
					assertResponse(request);
					await(finished);
				}
			});
			assertNull(failure.get());
			assertEquals(1, exporter.getFinishedSpanItems().size());
			SpanData span = exporter.getFinishedSpanItems().get(0);
			assertTrue(duration.get().compareTo(Duration.ofMillis(100)) >= 0,
					"The runtime duration must include the composed start observer's work");
			assertEquals(epochNanos(origin.get()), span.getStartEpochNanos());
			assertEquals(epochNanos(origin.get().plus(duration.get())), span.getEndEpochNanos());
		}
	}

	@Test void asynchronousFinishQueueDelayDoesNotExtendTheSemanticInterval() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
		try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
			 OpenTelemetryLifecycleObserver otel = OpenTelemetryLifecycleObserver.withOpenTelemetry(sdk).build()) {
			CountDownLatch blockerEntered = new CountDownLatch(1);
			CountDownLatch releaseBlocker = new CountDownLatch(1);
			CountDownLatch queuedFinished = new CountDownLatch(1);
			AtomicReference<Instant> queuedOrigin = new AtomicReference<>();
			AtomicReference<Instant> queuedFinishCallback = new AtomicReference<>();
			AtomicReference<Duration> queuedDuration = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			LifecycleObserver observer = new LifecycleObserver() {
				@Override public void didStartMcpRequestHandling(McpRequestContext context) {
					try {
						if (id(context).equals("queued")) queuedOrigin.set(context.getStartedAt().orElseThrow());
						otel.didStartMcpRequestHandling(context);
					} catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
				}
				@Override public void didFinishMcpRequestHandling(McpRequestContext context, McpRequestOutcome outcome,
						McpJsonRpcError error, Duration elapsed, List<Throwable> throwables) {
					boolean queued = id(context).equals("queued");
					try {
						assertEquals(McpRequestOutcome.COMPLETE, outcome);
						if (!queued) {
							blockerEntered.countDown();
							await(releaseBlocker);
						} else {
							queuedFinishCallback.set(Instant.now());
							queuedDuration.set(elapsed);
						}
						otel.didFinishMcpRequestHandling(context, outcome, error, elapsed, throwables);
					} catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
					finally { if (queued) queuedFinished.countDown(); }
				}
			};
			SokletSimulator.run(config(observer), simulator -> {
				try (McpSimulation blocker = simulator.startMcpRequest(request("blocker"))) {
					assertResponse(blocker);
					await(blockerEntered);
					try (McpSimulation queued = simulator.startMcpRequest(request("queued"))) {
						assertResponse(queued);
						try {
							assertFalse(queuedFinished.await(100, TimeUnit.MILLISECONDS),
									"The single observation worker must leave this finish queued");
						} catch (InterruptedException exception) { throw new AssertionError(exception); }
						finally { releaseBlocker.countDown(); }
						await(queuedFinished);
					}
				} finally { releaseBlocker.countDown(); }
			});
			assertNull(failure.get());
			assertEquals(2, exporter.getFinishedSpanItems().size());
			SpanData queued = exporter.getFinishedSpanItems().get(1);
			assertEquals(epochNanos(queuedOrigin.get()), queued.getStartEpochNanos());
			assertEquals(epochNanos(queuedOrigin.get().plus(queuedDuration.get())), queued.getEndEpochNanos());
			assertTrue(epochNanos(queuedFinishCallback.get()) - queued.getEndEpochNanos() >= Duration.ofMillis(75).toNanos(),
					"The time waiting for the observation worker must be excluded from the span");
		}
	}

	private static SokletConfig config(LifecycleObserver observer) {
		var versions = Set.of(McpProtocolVersion.V2026_07_28);
		var tool = McpToolRegistration.withName("echo", versions).jsonObjectArguments()
				.handler((request, values, features) -> McpCompleteResult.fromToolText("ok")).build();
		var endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("otel-timing", "1").build(), versions)
				.toolRegistrations(List.of(tool)).build();
		var server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> McpAdmissionDecision.accepted())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.requestHandlerConcurrency(1).requestHandlerQueueCapacity(4)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed()).build();
		return SokletConfig.withMcpServer(server).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer).build();
	}

	private static Request request(String id) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{},"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		return Request.withPath(HttpMethod.POST, "/mcp").headers(Map.of("Host", List.of("127.0.0.1"),
				"Content-Type", List.of("application/json"), "Accept", List.of("application/json, text/event-stream"),
				"MCP-Protocol-Version", List.of("2026-07-28"), "Mcp-Method", List.of("tools/call"), "Mcp-Name", List.of("echo")))
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static String id(McpRequestContext context) {
		return context.getRequestId().orElseThrow().asString().orElseThrow();
	}

	private static void assertResponse(McpSimulation simulation) {
		try {
			var response = simulation.awaitResponse(Duration.ofSeconds(5)).orElseThrow();
			assertEquals(200, response.getStatusCode());
			assertTrue(new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8).contains("\"text\":\"ok\""));
		} catch (InterruptedException exception) { throw new AssertionError(exception); }
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS), "The expected callback did not arrive"); }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
	}

	private static long epochNanos(Instant instant) {
		return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
	}
}
