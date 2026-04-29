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

import com.soklet.McpEndpoint;
import com.soklet.McpSseStream;
import com.soklet.Request;
import com.soklet.ResourceMethod;
import com.soklet.SseConnection;
import com.soklet.StreamingResponseHandle;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Customizes OpenTelemetry span names emitted by {@link OpenTelemetryLifecycleObserver}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface SpanNamingStrategy {
	@NonNull
	String httpRequestSpanName(@NonNull Request request,
														 @Nullable ResourceMethod resourceMethod);

	@NonNull
	String streamingResponseSpanName(@NonNull StreamingResponseHandle stream);

	@NonNull
	String sseConnectionSpanName(@NonNull SseConnection connection);

	@NonNull
	String mcpRequestSpanName(@NonNull Request request,
														@NonNull Class<? extends McpEndpoint> endpointClass,
														@NonNull String jsonRpcMethod);

	@NonNull
	String mcpSseStreamSpanName(@NonNull McpSseStream stream);

	@NonNull
	static SpanNamingStrategy defaultInstance() {
		return DefaultSpanNamingStrategy.defaultInstance();
	}
}
