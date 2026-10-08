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
import com.soklet.LogEventType;
import com.soklet.MarshaledResponse;
import com.soklet.Request;
import com.soklet.RequestInterceptor;
import com.soklet.ResourceMethod;
import com.soklet.ResourceMethodResolver;
import com.soklet.ServerType;
import com.soklet.SokletConfig;
import com.soklet.SokletSimulator;
import com.soklet.annotation.GET;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/** Verifies that tracing does not add a second application log channel. */
@ResourceLock("java.lang.System.err")
class OpenTelemetryLoggingTests {
	@Test
	void tracingObserverDoesNotPrintLogEvents() {
		try (OpenTelemetryLifecycleObserver observer =
				OpenTelemetryLifecycleObserver.fromOpenTelemetry(OpenTelemetry.noop())) {
			String output = captureStandardError(() -> observer.didReceiveLogEvent(
					LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "event-secret")
							.throwable(new IllegalStateException("throwable-secret")).build()));

			Assertions.assertEquals("", output);
		}
	}

	@Test
	void runtimeFailureUsesOneApplicationLogWhileTracingStillExportsItsSpan() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
				.setTracerProvider(SdkTracerProvider.builder()
						.addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build();
				OpenTelemetryLifecycleObserver tracingObserver =
						OpenTelemetryLifecycleObserver.fromOpenTelemetry(sdk)) {
			Throwable failure = new IllegalStateException("application-only-failure");
			List<LogEvent> received = new CopyOnWriteArrayList<>();
			LifecycleObserver loggingObserver = new LifecycleObserver() {
				@Override
				public void didReceiveLogEvent(LogEvent logEvent) {
					received.add(logEvent);
				}
			};
			SokletConfig config = SokletConfig.withHttpServer(HttpServer.fromPort(0))
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(LoggingResource.class)))
					.requestInterceptor(new RequestInterceptor() {
						@Override
						public void interceptRequest(ServerType serverType, Request request,
								ResourceMethod resourceMethod,
								Function<Request, MarshaledResponse> responseGenerator,
								Consumer<MarshaledResponse> responseWriter) {
							throw (IllegalStateException) failure;
						}
					}).lifecycleObservers(List.of(loggingObserver, tracingObserver)).build();

			String output = captureStandardError(() -> SokletSimulator.run(config,
					simulator -> Assertions.assertEquals(500, simulator.performHttpRequest(
							Request.fromPath(HttpMethod.GET, "/log-test"))
							.getMarshaledResponse().getStatusCode())));

			Assertions.assertEquals("", output);
			Assertions.assertEquals(1, received.size());
			Assertions.assertEquals(LogEventType.REQUEST_INTERCEPTOR_INTERCEPT_REQUEST_FAILED,
					received.get(0).getLogEventType());
			Assertions.assertSame(failure, received.get(0).getThrowable().orElseThrow());
			Assertions.assertEquals(1, exporter.getFinishedSpanItems().size());
			Assertions.assertEquals(500L, exporter.getFinishedSpanItems().get(0)
					.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
			Assertions.assertEquals(0, tracingObserver.getActiveSpanCount());
		}
	}

	public static final class LoggingResource {
		@GET("/log-test")
		public String get() {
			throw new AssertionError("Failed interception must not invoke the resource");
		}
	}

	private static String captureStandardError(Runnable action) {
		PrintStream originalError = System.err;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream capturedError = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
			System.setErr(capturedError);
			try {
				action.run();
			} finally {
				System.setErr(originalError);
			}
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}
}
