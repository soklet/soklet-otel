/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.otel;

import com.soklet.ServerType;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Shared wire vocabulary for {@code soklet.server.type}, independent of Java enum names.
 */
@ThreadSafe
final class ServerTypeAttribute {
	@NonNull
	static final String HTTP = "http";
	@NonNull
	static final String SSE = "sse";
	@NonNull
	static final String MCP = "mcp";

	private ServerTypeAttribute() {}

	@NonNull
	static String valueFor(@NonNull ServerType serverType) {
		requireNonNull(serverType);
		return switch (serverType) {
			case HTTP -> HTTP;
			case SSE -> SSE;
		};
	}
}
