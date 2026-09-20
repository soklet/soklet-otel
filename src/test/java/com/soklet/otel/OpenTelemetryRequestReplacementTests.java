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
import com.soklet.HttpServer;
import com.soklet.LifecycleObserver;
import com.soklet.LogEvent;
import com.soklet.MarshaledResponse;
import com.soklet.MarshaledResponseBody;
import com.soklet.MetricsCollector;
import com.soklet.Request;
import com.soklet.RequestInterceptor;
import com.soklet.ResourceMethod;
import com.soklet.ResourceMethodResolver;
import com.soklet.ServerType;
import com.soklet.SimulatorConfig;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.SokletSimulator;
import com.soklet.StreamTermination;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamingResponseHandle;
import com.soklet.annotation.POST;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

@Timeout(30)
public class OpenTelemetryRequestReplacementTests {
	private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

	@Test
	public void copiesAndChangedIdsPreserveOriginalLifecycleIdentityAndEffectiveRouting() {
		for (boolean changeId : List.of(false, true)) {
			InMemorySpanExporter exporter = InMemorySpanExporter.create();
			try (OpenTelemetrySdk sdk = sdk(exporter);
				 OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver.fromOpenTelemetry(sdk)) {
				Request original = original();
				AtomicReference<Request> metricStart = new AtomicReference<>();
				AtomicReference<Request> metricFinish = new AtomicReference<>();
				MetricsCollector metrics = new MetricsCollector() {
					@Override
					public void didStartRequestHandling(@NonNull ServerType type, @NonNull Request request,
							@Nullable ResourceMethod method) { metricStart.set(request); }
					@Override
					public void didFinishRequestHandling(@NonNull ServerType type, @NonNull Request request,
							@Nullable ResourceMethod method, @NonNull MarshaledResponse response,
							@NonNull Duration duration, @NonNull List<@NonNull Throwable> throwables) {
						metricFinish.set(request);
					}
				};
				SokletSimulator.run(SimulatorConfig.builder().httpServer()
						.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
						.requestInterceptor(replacingInterceptor(changeId, "/rewritten", null, null))
						.lifecycleObservers(List.of(observer, quiet())).metricsCollector(metrics).build(), simulator -> {
					MarshaledResponse response = simulator.performHttpRequest(original).getMarshaledResponse();
					Assertions.assertEquals(200, response.getStatusCode());
					Assertions.assertEquals(changeId ? "effective" : "shared-id",
							new String(((MarshaledResponseBody.Bytes) response.getBody().orElseThrow()).getBytes(), StandardCharsets.UTF_8));
				});
				Assertions.assertNotSame(original, metricStart.get());
				Assertions.assertEquals(original.getId(), metricStart.get().getId());
				Assertions.assertSame(metricStart.get(), metricFinish.get());
				Assertions.assertEquals(0, observer.getActiveSpanCount());
				Assertions.assertEquals(1, exporter.getFinishedSpanItems().size());
				SpanData span = exporter.getFinishedSpanItems().get(0);
				Assertions.assertEquals("GET /rewritten", span.getName());
				Assertions.assertEquals("0af7651916cd43dd8448eb211c80319c", span.getTraceId());
				Assertions.assertEquals("b7ad6b7169203331", span.getParentSpanId());
			}
		}
	}

	@Test
	public void concurrentReplacementsWithReusedIdsDoNotMergeSpans() throws Exception {
		assertConcurrentReplacements(false, false);
	}

	@Test
	public void concurrentReuseOfOneRequestKeepsIndependentPlainAndStreamingSpans() throws Exception {
		assertConcurrentReplacements(true, false);
		assertConcurrentReplacements(true, true);
	}

	private void assertConcurrentReplacements(boolean reuseRequest, boolean streaming) throws Exception {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		Request sharedRequest = original();
		CountDownLatch entered = new CountDownLatch(4);
		CountDownLatch release = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(4);
		try (OpenTelemetrySdk sdk = sdk(exporter);
			 OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver.fromOpenTelemetry(sdk)) {
			List<Future<?>> requests = new ArrayList<>();
			for (int index = 0; index < 4; index++) {
				requests.add(executor.submit(() -> SokletSimulator.run(SimulatorConfig.builder().httpServer()
						.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
						.requestInterceptor(replacingInterceptor(true, streaming ? "/stream" : "/rewritten", entered, release))
						.lifecycleObservers(List.of(observer, quiet())).build(), simulator -> {
					Assertions.assertEquals(200, simulator.performHttpRequest(reuseRequest ? sharedRequest : original())
							.getMarshaledResponse().getStatusCode());
				})));
			}
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(4, observer.getActiveSpanCount());
			release.countDown();
			for (Future<?> request : requests)
				request.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(0, observer.getActiveSpanCount());
			Assertions.assertEquals(4, exporter.getFinishedSpanItems().size());
			Assertions.assertEquals(4, exporter.getFinishedSpanItems().stream().map(SpanData::getSpanId).distinct().count());
		} finally {
			release.countDown();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void socketStreamTerminationBeforeHandlingFinishKeepsOneCompleteSpan() throws Exception {
		int port;
		try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			port = reservation.getLocalPort();
		}
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		CountDownLatch terminated = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(1);
		AtomicBoolean terminalBeforeFinish = new AtomicBoolean(false);
		AtomicReference<Request> startedRequest = new AtomicReference<>();
		AtomicReference<Request> finishedRequest = new AtomicReference<>();
		AtomicReference<Request> streamRequest = new AtomicReference<>();
		AtomicReference<Throwable> observationFailure = new AtomicReference<>();
		LifecycleObserver order = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent event) {}
			@Override
			public void didStartRequestHandling(@NonNull ServerType type, @NonNull Request request,
					@Nullable ResourceMethod method) { startedRequest.set(request); }
			@Override
			public void didWriteResponse(@NonNull ServerType type, @NonNull Request request,
					@Nullable ResourceMethod method, @NonNull MarshaledResponse response, @NonNull Duration duration) {
				try {
					if (!terminated.await(5, TimeUnit.SECONDS))
						observationFailure.set(new AssertionError("Stream did not terminate before handling finish"));
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					observationFailure.set(exception);
				}
			}
			@Override
			public void didFinishRequestHandling(@NonNull ServerType type, @NonNull Request request,
					@Nullable ResourceMethod method, @NonNull MarshaledResponse response,
					@NonNull Duration duration, @NonNull List<@NonNull Throwable> throwables) {
				finishedRequest.set(request);
				finished.countDown();
			}
			@Override
			public void didTerminateResponseStream(@NonNull StreamingResponseHandle streamingResponseHandle,
					@NonNull StreamTermination streamTermination) {
				streamRequest.set(streamingResponseHandle.getRequest());
				terminalBeforeFinish.set(finished.getCount() == 1);
				terminated.countDown();
			}
		};
		try (OpenTelemetrySdk sdk = sdk(exporter);
			 OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver.fromOpenTelemetry(sdk);
			 Soklet soklet = Soklet.fromConfig(SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1").build())
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.requestInterceptor(replacingInterceptor(true, "/stream", null, null))
					.lifecycleObservers(List.of(observer, order)).build())) {
			soklet.start();
			HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/original").openConnection();
			connection.setConnectTimeout(2_000);
			connection.setReadTimeout(5_000);
			connection.setRequestProperty("traceparent", TRACEPARENT);
			try {
				Assertions.assertEquals(200, connection.getResponseCode());
				try (var body = connection.getInputStream()) {
					Assertions.assertEquals("effective", new String(body.readAllBytes(), StandardCharsets.UTF_8));
				}
				Assertions.assertTrue(finished.await(5, TimeUnit.SECONDS));
				Assertions.assertNull(observationFailure.get());
				Assertions.assertTrue(terminalBeforeFinish.get());
				Assertions.assertSame(startedRequest.get(), finishedRequest.get());
				Assertions.assertSame(startedRequest.get(), streamRequest.get());
				Assertions.assertEquals(0, observer.getActiveSpanCount());
				Assertions.assertEquals(1, exporter.getFinishedSpanItems().size());
				SpanData span = exporter.getFinishedSpanItems().get(0);
				Assertions.assertEquals("GET /stream", span.getName());
				Assertions.assertEquals(200L, span.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
				Assertions.assertEquals("completed", span.getAttributes().get(AttributeKey.stringKey("soklet.stream.termination.reason")));
				Assertions.assertEquals("b7ad6b7169203331", span.getParentSpanId());
			} finally {
				connection.disconnect();
			}
		}
	}

	private static Request original() {
		return Request.withPath(HttpMethod.GET, "/original").id("shared-id")
				.headers(Map.of("traceparent", Set.of(TRACEPARENT))).build();
	}

	private static RequestInterceptor replacingInterceptor(boolean changeId, String route,
			@Nullable CountDownLatch entered, @Nullable CountDownLatch release) {
		return new RequestInterceptor() {
			@Override
			public void wrapRequest(@NonNull ServerType type, @NonNull Request request,
					@NonNull Consumer<@NonNull Request> processor) {
				processor.accept(request.copy().path(route).httpMethod(HttpMethod.POST)
						.id(changeId ? "wrapped" : request.getId()).traceContext(null).finish());
			}
			@Override
			public void interceptRequest(@NonNull ServerType type, @NonNull Request request,
					@Nullable ResourceMethod method, @NonNull Function<@NonNull Request, @NonNull MarshaledResponse> generator,
					@NonNull Consumer<@NonNull MarshaledResponse> writer) {
				if (entered != null && release != null) {
					entered.countDown();
					try {
						if (!release.await(5, TimeUnit.SECONDS))
							throw new IllegalStateException("Timed out waiting for concurrent request barrier");
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(exception);
					}
				}
				writer.accept(generator.apply(request.copy().id(changeId ? "effective" : request.getId()).finish()));
			}
		};
	}

	private static LifecycleObserver quiet() {
		return new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent event) {}
		};
	}

	private static OpenTelemetrySdk sdk(InMemorySpanExporter exporter) {
		return OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
				.addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build();
	}

	public static class Resource {
		@POST("/rewritten")
		public MarshaledResponse rewritten(Request request) {
			return MarshaledResponse.withStatusCode(200)
					.body(String.valueOf(request.getId()).getBytes(StandardCharsets.UTF_8)).build();
		}
		@POST("/stream")
		public MarshaledResponse stream(Request request) {
			return MarshaledResponse.withStatusCode(200).streamingResponseBody(StreamingResponseBody.fromWriter((output, context) ->
					output.write(String.valueOf(request.getId()).getBytes(StandardCharsets.UTF_8)))).build();
		}
	}
}
