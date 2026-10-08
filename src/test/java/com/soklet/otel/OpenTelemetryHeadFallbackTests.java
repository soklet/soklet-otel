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
import com.soklet.MarshaledResponse;
import com.soklet.Request;
import com.soklet.ResourceMethodResolver;
import com.soklet.SimulatorConfig;
import com.soklet.SokletConfig;
import com.soklet.SokletSimulator;
import com.soklet.annotation.GET;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class OpenTelemetryHeadFallbackTests {
	@Test
	void headFallbackHasGetRouteAndHeadVerbWithoutOpeningAStreamOrLeakingASpan() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
		try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
			 OpenTelemetryLifecycleObserver observer = OpenTelemetryLifecycleObserver.withOpenTelemetry(sdk).build()) {
			Resource.producers.set(0);
			SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0).build())
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.lifecycleObserver(observer).build();
			SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator -> {
				Assertions.assertEquals(200, simulator.performHttpRequest(Request.withPath(HttpMethod.HEAD, "/export/42").build())
						.getMarshaledResponse().getStatusCode());
				Assertions.assertEquals(0, Resource.producers.get());
				Assertions.assertEquals(0, observer.getActiveSpanCount());
				Assertions.assertEquals(1, exporter.getFinishedSpanItems().size());
				var span = exporter.getFinishedSpanItems().get(0);
				Assertions.assertEquals("HEAD", span.getAttributes().get(AttributeKey.stringKey("http.request.method")));
				Assertions.assertEquals("/export/{id}", span.getAttributes().get(AttributeKey.stringKey("http.route")));
				Assertions.assertEquals(200L, span.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
			});
			Assertions.assertEquals(1, exporter.getFinishedSpanItems().size(), "Scoped shutdown must not backfill a span");
		}
	}
	public static final class Resource {
		private static final AtomicInteger producers = new AtomicInteger();
		@GET("/export/{id}") public MarshaledResponse export() {
			return MarshaledResponse.withStatusCode(200).stream(responseStream -> producers.incrementAndGet()).build();
		}
	}
}
