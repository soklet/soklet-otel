package com.soklet.otel;

import com.soklet.HttpMethod;
import com.soklet.MarshaledResponse;
import com.soklet.McpRequestOutcome;
import com.soklet.Request;
import com.soklet.ResourceMethod;
import com.soklet.ServerType;
import com.soklet.ShutdownComponentDisposition;
import com.soklet.StreamTermination;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseHandle;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Runs existing integration bytecode in a process with synthetic newer core enums. */
public final class EvolutionaryEnumCompatibilityProbe {
	public static void main(String[] arguments) throws Exception {
		assertEquals("unknown", ServerTypeAttribute.valueFor(ServerType.valueOf("FUTURE")));
		try (var observer = OpenTelemetryLifecycleObserver.fromOpenTelemetry(OpenTelemetry.noop())) {
			var classification = OpenTelemetryLifecycleObserver.class.getDeclaredMethod("isMcpErrorOutcome", McpRequestOutcome.class);
			classification.setAccessible(true);
			assertEquals(true, classification.invoke(observer, McpRequestOutcome.valueOf("FUTURE")));
		}
		var shutdown = OpenTelemetryMetricsCollector.class.getDeclaredMethod("mcpShutdownOutcome", ShutdownComponentDisposition.class);
		shutdown.setAccessible(true);
		assertEquals("termination_unknown", shutdown.invoke(null, ShutdownComponentDisposition.valueOf("FUTURE")));

		for (var naming : OpenTelemetryMetricsCollector.MetricNamingStrategy.values()) {
			for (boolean terminationFirst : List.of(false, true)) {
				var reader = InMemoryMetricReader.create();
				var exporter = InMemorySpanExporter.create();
				try (var sdk = OpenTelemetrySdk.builder()
						.setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
						.setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build();
					 var observer = OpenTelemetryLifecycleObserver.fromOpenTelemetry(sdk)) {
					var collector = OpenTelemetryMetricsCollector.withOpenTelemetry(sdk).metricNamingStrategy(naming).build();
					Request request = Request.fromPath(HttpMethod.GET, "/future-stream");
					MarshaledResponse response = MarshaledResponse.withStatusCode(200).stream(stream -> {}).build();
					var handle = new StreamingResponseHandle() {
						@Override public ServerType getServerType() { return ServerType.HTTP; }
						@Override public Request getRequest() { return request; }
						@Override public Optional<ResourceMethod> getResourceMethod() { return Optional.empty(); }
						@Override public MarshaledResponse getMarshaledResponse() { return response; }
						@Override public Instant getEstablishedAt() { return Instant.now(); }
					};
					var termination = StreamTermination.with(StreamTerminationReason.valueOf("FUTURE"), Duration.ofMillis(1)).build();
					collector.didStartRequestHandling(ServerType.HTTP, request, null);
					observer.didStartRequestHandling(ServerType.HTTP, request, null);
					collector.willWriteResponseStream(handle);
					observer.willWriteResponseStream(handle);
					if (terminationFirst) {
						collector.didTerminateResponseStream(handle, termination, Duration.ofMillis(2), 7L);
						observer.didTerminateResponseStream(handle, termination);
					}
					collector.didFinishRequestHandling(ServerType.HTTP, request, null, response, Duration.ofMillis(1), List.of());
					observer.didFinishRequestHandling(ServerType.HTTP, request, null, response, Duration.ofMillis(1), List.of());
					if (!terminationFirst) {
						collector.didTerminateResponseStream(handle, termination, Duration.ofMillis(2), 7L);
						observer.didTerminateResponseStream(handle, termination);
					}
					String activeName = naming == OpenTelemetryMetricsCollector.MetricNamingStrategy.SEMCONV
							? "http.server.active_requests" : "soklet.server.requests.active";
					var metrics = reader.collectAllMetrics();
					long active = metrics.stream().filter(metric -> metric.getName().equals(activeName))
							.flatMap(metric -> metric.getLongSumData().getPoints().stream()).mapToLong(point -> point.getValue()).sum();
					assertEquals(0L, active);
					assertTrue(metrics.stream().filter(metric -> metric.getName().equals("soklet.http.response.stream.terminations"))
							.flatMap(metric -> metric.getLongSumData().getPoints().stream()).anyMatch(point -> point.getValue() == 1));
					assertEquals(0, observer.getActiveSpanCount());
					assertEquals(1, exporter.getFinishedSpanItems().size());
					var span = exporter.getFinishedSpanItems().getFirst();
					assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, span.getStatus().getStatusCode());
					assertEquals("future", span.getAttributes().get(AttributeKey.stringKey("soklet.stream.termination.reason")));
				}
			}
		}
	}
}
