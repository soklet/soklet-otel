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

import com.soklet.HttpServer;
import com.soklet.LifecycleObserver;
import com.soklet.LifecyclePolicy;
import com.soklet.LogEvent;
import com.soklet.MarshaledResponse;
import com.soklet.Request;
import com.soklet.RequestInterceptor;
import com.soklet.ResourceMethod;
import com.soklet.ResourceMethodResolver;
import com.soklet.ServerType;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.StreamTermination;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamingResponseHandle;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class OpenTelemetryHttpProtocolRejectionTests {
	@Test
	void protocolRejectionExportsOne505SpanWhetherTerminationPrecedesOrFollowsHandlingFinish() throws Exception {
		for (boolean terminationFirst : List.of(true, false)) {
			InMemorySpanExporter exporter = InMemorySpanExporter.create();
			SdkTracerProvider provider = SdkTracerProvider.builder()
					.addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
			try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
				 OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver.withOpenTelemetry(sdk).build()) {
				CountDownLatch handlingFinished = new CountDownLatch(1);
				CountDownLatch terminationFinished = new CountDownLatch(1);
				AtomicInteger terminationCalls = new AtomicInteger();
				AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
				int port;
				try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
				LifecycleObserver controlledObserver = new LifecycleObserver() {
					@Override
					public void didStartRequestHandling(ServerType serverType, Request request, ResourceMethod resourceMethod) {
						observer.didStartRequestHandling(serverType, request, resourceMethod);
					}
					@Override
					public void willWriteResponse(ServerType serverType, Request request, ResourceMethod resourceMethod, MarshaledResponse response) {
						observer.willWriteResponse(serverType, request, resourceMethod, response);
					}
					@Override
					public void didFinishRequestHandling(ServerType serverType, Request request, ResourceMethod resourceMethod,
							MarshaledResponse response, Duration duration, List<Throwable> throwables) {
						observer.didFinishRequestHandling(serverType, request, resourceMethod, response, duration, throwables);
						handlingFinished.countDown();
					}
					@Override
					public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
						try {
							terminationCalls.incrementAndGet();
							Assertions.assertEquals(StreamTerminationReason.PROTOCOL_UNSUPPORTED, termination.getReason());
							if (!terminationFirst) Assertions.assertTrue(handlingFinished.await(3, TimeUnit.SECONDS));
							observer.didTerminateResponseStream(handle, termination);
						} catch (Throwable throwable) { callbackFailure.set(throwable); }
						finally { terminationFinished.countDown(); }
					}
					@Override
					public void didReceiveLogEvent(LogEvent logEvent) { }
				};
				RequestInterceptor interceptor = new RequestInterceptor() {
					@Override
					public void wrapRequest(ServerType serverType, Request request, Consumer<Request> requestConsumer) {
						requestConsumer.accept(request);
						if (terminationFirst) {
							try { Assertions.assertTrue(terminationFinished.await(3, TimeUnit.SECONDS)); }
							catch (Throwable throwable) {
								if (throwable instanceof InterruptedException) Thread.currentThread().interrupt();
								callbackFailure.compareAndSet(null, throwable);
							}
						}
					}
					@Override
					public void interceptRequest(ServerType serverType, Request request, ResourceMethod resourceMethod,
							Function<Request, MarshaledResponse> responseGenerator, Consumer<MarshaledResponse> responseWriter) {
						responseWriter.accept(MarshaledResponse.withStatusCode(200)
								.streamingResponseBody(StreamingResponseBody.fromWriter(stream -> Assertions.fail("Rejected writer entered"))).build());
					}
				};
				try (Soklet soklet = Soklet.fromConfig(SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1")
						.concurrency(1).streamingResponseTimeout(Duration.ofSeconds(5)).build())
						.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HealthResource.class)))
						.requestInterceptor(interceptor).lifecycleObserver(controlledObserver)
						.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofSeconds(1))
								.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build())) {
					soklet.start();
					try (Socket socket = new Socket("127.0.0.1", port)) {
						socket.setSoTimeout(3_000);
						socket.getOutputStream().write("GET /stream HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
						String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
						Assertions.assertTrue(wire.startsWith("HTTP/1.0 505 HTTP Version Not Supported"), wire);
					}
					Assertions.assertTrue(handlingFinished.await(3, TimeUnit.SECONDS));
					Assertions.assertTrue(terminationFinished.await(3, TimeUnit.SECONDS));
					Assertions.assertNull(callbackFailure.get());
					Assertions.assertEquals(1, terminationCalls.get());
					Assertions.assertEquals(0, observer.getActiveSpanCount());
					Assertions.assertEquals(1, exporter.getFinishedSpanItems().size(), "Duplicate or missing span");
					Assertions.assertEquals(505L, exporter.getFinishedSpanItems().get(0).getAttributes()
							.get(AttributeKey.longKey("http.response.status_code")));
				}
				Assertions.assertEquals(1, exporter.getFinishedSpanItems().size(), "Shutdown backfilled a span");
			}
		}
	}

	public static final class HealthResource {
		@com.soklet.annotation.GET("/health")
		public String health() { return "ok"; }
	}
}
