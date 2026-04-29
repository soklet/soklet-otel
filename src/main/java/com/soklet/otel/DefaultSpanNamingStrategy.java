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

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultSpanNamingStrategy implements SpanNamingStrategy {
	@NonNull
	private static final DefaultSpanNamingStrategy DEFAULT_INSTANCE = new DefaultSpanNamingStrategy();

	@NonNull
	public static DefaultSpanNamingStrategy defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	private DefaultSpanNamingStrategy() {
		// Singleton
	}

	@Override
	@NonNull
	public String httpRequestSpanName(@NonNull Request request,
																		@Nullable ResourceMethod resourceMethod) {
		requireNonNull(request);

		if (resourceMethod == null)
			return request.getHttpMethod().name();

		return "%s %s".formatted(request.getHttpMethod().name(), resourceMethod.getResourcePathDeclaration().getPath());
	}

	@Override
	@NonNull
	public String streamingResponseSpanName(@NonNull StreamingResponseHandle stream) {
		requireNonNull(stream);
		return "%s stream".formatted(httpRequestSpanName(stream.getRequest(), stream.getResourceMethod().orElse(null)));
	}

	@Override
	@NonNull
	public String sseConnectionSpanName(@NonNull SseConnection connection) {
		requireNonNull(connection);
		return "%s %s sse".formatted(connection.getRequest().getHttpMethod().name(),
				connection.getResourceMethod().getResourcePathDeclaration().getPath());
	}

	@Override
	@NonNull
	public String mcpRequestSpanName(@NonNull Request request,
																 @NonNull Class<? extends McpEndpoint> endpointClass,
																 @NonNull String jsonRpcMethod) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(jsonRpcMethod);

		return "MCP %s".formatted(jsonRpcMethod);
	}

	@Override
	@NonNull
	public String mcpSseStreamSpanName(@NonNull McpSseStream stream) {
		requireNonNull(stream);
		return "MCP SSE %s".formatted(stream.getEndpointClass().getSimpleName());
	}
}
