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
import com.soklet.Request;
import com.soklet.ServerType;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Duration;
import java.util.List;

/** Global SDK registration is JVM-wide; do not run these tests alongside other tests. */
@Isolated
public class OpenTelemetryBuilderTests {
	@BeforeEach
	@AfterEach
	public void resetGlobal() {
		GlobalOpenTelemetry.resetForTest();
	}

	@Test
	public void explicitFactoriesNeverReadOrInitializeTheGlobalSdk() {
		OpenTelemetry explicit = OpenTelemetry.noop();
		OpenTelemetryLifecycleObserver.fromOpenTelemetry(explicit).close();
		OpenTelemetryLifecycleObserver.fromTracer(explicit.getTracer("explicit")).close();
		OpenTelemetryMetricsCollector.fromOpenTelemetry(explicit);
		OpenTelemetryMetricsCollector.fromMeter(explicit.getMeter("explicit"));
		Assertions.assertDoesNotThrow(() -> GlobalOpenTelemetry.set(explicit));
	}

	@Test
	public void buildersResolveGlobalOnlyAtBuildTime() {
		OpenTelemetryLifecycleObserver.Builder tracing = OpenTelemetryLifecycleObserver.builder();
		OpenTelemetryMetricsCollector.Builder metrics = OpenTelemetryMetricsCollector.builder();
		InMemorySpanExporter spans = InMemorySpanExporter.create();
		InMemoryMetricReader metricReader = InMemoryMetricReader.create();
		try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
				.setTracerProvider(SdkTracerProvider.builder()
						.addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
				.setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
				.buildAndRegisterGlobal();
			 OpenTelemetryLifecycleObserver observer = tracing.build()) {
			OpenTelemetryMetricsCollector collector = metrics.build();
			Request request = Request.fromPath(HttpMethod.GET, "/late-sdk");
			MarshaledResponse response = MarshaledResponse.fromStatusCode(200);
			observer.didStartRequestHandling(ServerType.HTTP, request, null);
			observer.didFinishRequestHandling(ServerType.HTTP, request, null, response, Duration.ZERO, List.of());
			collector.didStartRequestHandling(ServerType.HTTP, request, null);
			collector.didFinishRequestHandling(ServerType.HTTP, request, null, response, Duration.ZERO, List.of());
			Assertions.assertEquals(1, spans.getFinishedSpanItems().size());
			Assertions.assertFalse(metricReader.collectAllMetrics().isEmpty());
			String expectedVersion = InstrumentationScope.defaultVersion();
			Assertions.assertEquals(expectedVersion,
					spans.getFinishedSpanItems().get(0).getInstrumentationScopeInfo().getVersion());
			Assertions.assertTrue(metricReader.collectAllMetrics().stream().allMatch(metric ->
					java.util.Objects.equals(metric.getInstrumentationScopeInfo().getVersion(), expectedVersion)));
		}
	}

	@Test
	public void explicitInstrumentationVersionOverridesSharedDefault() {
		InMemorySpanExporter spans = InMemorySpanExporter.create();
		InMemoryMetricReader metricReader = InMemoryMetricReader.create();
		try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
				.setTracerProvider(SdkTracerProvider.builder()
						.addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
				.setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build()).build();
			 OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver.withOpenTelemetry(sdk)
					.instrumentationVersion("custom-version").build()) {
			OpenTelemetryMetricsCollector collector = OpenTelemetryMetricsCollector.withOpenTelemetry(sdk)
					.instrumentationVersion("custom-version").build();
			Request request = Request.fromPath(HttpMethod.GET, "/explicit-version");
			MarshaledResponse response = MarshaledResponse.fromStatusCode(200);
			observer.didStartRequestHandling(ServerType.HTTP, request, null);
			observer.didFinishRequestHandling(ServerType.HTTP, request, null, response, Duration.ZERO, List.of());
			collector.didStartRequestHandling(ServerType.HTTP, request, null);
			collector.didFinishRequestHandling(ServerType.HTTP, request, null, response, Duration.ZERO, List.of());
			Assertions.assertEquals("custom-version", spans.getFinishedSpanItems().get(0)
					.getInstrumentationScopeInfo().getVersion());
			Assertions.assertTrue(metricReader.collectAllMetrics().stream().allMatch(metric ->
					metric.getInstrumentationScopeInfo().getVersion().equals("custom-version")));
		}
	}
}
