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
import com.soklet.InstanceProvider;
import com.soklet.LifecycleObserver;
import com.soklet.MarshaledResponse;
import com.soklet.Request;
import com.soklet.RequestInterceptor;
import com.soklet.ResourceMethod;
import com.soklet.ResourceMethodResolver;
import com.soklet.ServerType;
import com.soklet.SimulatorConfig;
import com.soklet.SokletConfig;
import com.soklet.SokletSimulator;
import com.soklet.StreamTermination;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseHandle;
import com.soklet.annotation.GET;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class OpenTelemetrySimulatorStreamingAdmissionTests {
	@Test
	void admissionRejectionExportsOne503SpanWithEitherTerminationOrdering() throws Exception {
		for (boolean terminationFirst : List.of(true, false)) {
			InMemorySpanExporter exporter = InMemorySpanExporter.create();
			SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
			try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
				 OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver.withOpenTelemetry(sdk).build()) {
				Resource resource = new Resource();
				CountDownLatch handlingFinished = new CountDownLatch(1);
				CountDownLatch terminationFinished = new CountDownLatch(1);
				AtomicReference<Throwable> failure = new AtomicReference<>();
				LifecycleObserver controlled = new LifecycleObserver() {
					@Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) {
						observer.didStartRequestHandling(type, request, method);
					}
					@Override public void willWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response) {
						observer.willWriteResponse(type, request, method, response);
					}
					@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
							MarshaledResponse response, Duration duration, List<Throwable> throwables) {
						observer.didFinishRequestHandling(type, request, method, response, duration, throwables);
						if (request.getPath().equals("/rejected")) handlingFinished.countDown();
					}
					@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
						boolean rejected = handle.getRequest().getPath().equals("/rejected");
						try {
							if (rejected) {
								Assertions.assertEquals(StreamTerminationReason.BACKPRESSURE, termination.getReason());
								if (!terminationFirst) await(handlingFinished);
							}
							observer.didTerminateResponseStream(handle, termination);
						} catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
						finally { if (rejected) terminationFinished.countDown(); }
					}
				};
				RequestInterceptor interceptor = new RequestInterceptor() {
					@Override public void wrapRequest(ServerType type, Request request, Consumer<Request> requestConsumer) {
						requestConsumer.accept(request);
						if (terminationFirst && request.getPath().equals("/rejected")) await(terminationFinished);
					}
				};
				SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0)
						.streamingLifecycleCapacity(1).streamingCallbackConcurrency(1).build())
						.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
						.instanceProvider(new InstanceProvider() {
							@Override public <T> T provide(Class<T> type) {
								return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
							}
						}).requestInterceptor(interceptor).lifecycleObserver(controlled).build();
				SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator -> {
					Thread occupied = new Thread(() -> {
						try { simulator.performHttpRequest(request("/occupied")); }
						catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
					}, "otel-admission-occupied");
					occupied.start();
					try {
						await(resource.entered);
						Assertions.assertEquals(503, simulator.performHttpRequest(request("/rejected")).getMarshaledResponse().getStatusCode());
						await(handlingFinished); await(terminationFinished);
					} finally {
						resource.release.countDown();
						try { occupied.join(3000); Assertions.assertFalse(occupied.isAlive()); }
						catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
					}
					Assertions.assertNull(failure.get());
					Assertions.assertEquals(0, observer.getActiveSpanCount());
					Assertions.assertEquals(2, exporter.getFinishedSpanItems().size());
					Assertions.assertEquals(1, exporter.getFinishedSpanItems().stream().filter(span -> Long.valueOf(503).equals(
							span.getAttributes().get(AttributeKey.longKey("http.response.status_code")))).count());
				});
				Assertions.assertEquals(2, exporter.getFinishedSpanItems().size(), "Scoped shutdown must not backfill a span");
			}
		}
	}

	private static Request request(String path) { return Request.withPath(HttpMethod.GET, path).build(); }
	private static void await(CountDownLatch latch) {
		try { Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "Controlled lifecycle event did not arrive"); }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
	}
	public static final class Resource {
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		@GET("/occupied") public MarshaledResponse occupied() {
			return MarshaledResponse.withStatusCode(200).stream(stream -> { this.entered.countDown(); this.release.await(); }).build();
		}
		@GET("/rejected") public MarshaledResponse rejected() {
			return MarshaledResponse.withStatusCode(200).stream(stream -> Assertions.fail("Rejected producer entered")).build();
		}
	}
}
