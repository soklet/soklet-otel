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
import com.soklet.MetricsCollector;
import com.soklet.Request;
import com.soklet.ResourceMethod;
import com.soklet.ResourcePathDeclaration;
import com.soklet.ServerType;
import com.soklet.SseComment;
import com.soklet.SseConnection;
import com.soklet.SseEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class OpenTelemetryMetricsCollectorTests {
	private static final AttributeKey<String> HTTP_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("http.request.method");
	private static final AttributeKey<String> SSE_DROP_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.sse.drop.reason");
	private static final AttributeKey<String> ROUTE_ATTRIBUTE_KEY = AttributeKey.stringKey("http.route");
	private static final AttributeKey<Long> STATUS_CODE_ATTRIBUTE_KEY = AttributeKey.longKey("http.response.status_code");

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
		collector.didTerminateSseConnection(connection, Duration.ofSeconds(3),
				SseConnection.TerminationReason.REMOTE_CLOSE, null);

		Collection<MetricData> metrics = harness.metricReader().collectAllMetrics();

		Assertions.assertEquals(
				0L,
				longSumValue(metrics, "soklet.sse.connections.active",
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
