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

import com.soklet.LifecycleObserver;
import com.soklet.MarshaledResponse;
import com.soklet.McpEndpoint;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcRequestId;
import com.soklet.McpRequestOutcome;
import com.soklet.McpSseStream;
import com.soklet.Request;
import com.soklet.ResourceMethod;
import com.soklet.ServerType;
import com.soklet.SseComment;
import com.soklet.SseConnection;
import com.soklet.SseEvent;
import com.soklet.StreamTermination;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseHandle;
import com.soklet.TraceContext;
import com.soklet.TraceStateEntry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.TraceStateBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.context.Context;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * OpenTelemetry-backed {@link LifecycleObserver} that emits server spans for Soklet lifecycle events.
 * <p>
 * This type complements {@link OpenTelemetryMetricsCollector}: metrics remain low-cardinality aggregate telemetry,
 * while this observer emits per-request and per-stream spans.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class OpenTelemetryLifecycleObserver implements LifecycleObserver, AutoCloseable {
	@NonNull
	private static final String DEFAULT_INSTRUMENTATION_NAME;
	@NonNull
	private static final String URL_SCHEME_HTTP;
	@NonNull
	private static final String SERVER_TYPE_HTTP;
	@NonNull
	private static final String SERVER_TYPE_SSE;
	@NonNull
	private static final String SERVER_TYPE_MCP;

	@NonNull
	private static final AttributeKey<String> SERVER_TYPE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> HTTP_METHOD_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> HTTP_ROUTE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> URL_SCHEME_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<Long> HTTP_STATUS_CODE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> ERROR_TYPE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> CLIENT_ADDRESS_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> REQUEST_ID_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> STREAM_TERMINATION_REASON_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> RPC_SYSTEM_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> RPC_METHOD_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<Boolean> MCP_SESSION_ID_PRESENT_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<Boolean> MCP_REQUEST_ID_PRESENT_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<Long> MCP_JSON_RPC_ERROR_CODE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<Boolean> SSE_CLIENT_CONTEXT_PRESENT_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> SSE_PAYLOAD_TYPE_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> SSE_EVENT_TYPE_ATTRIBUTE_KEY;

	static {
		DEFAULT_INSTRUMENTATION_NAME = "com.soklet.otel";
		URL_SCHEME_HTTP = "http";
		SERVER_TYPE_HTTP = "http";
		SERVER_TYPE_SSE = "server_sent_event";
		SERVER_TYPE_MCP = "mcp";

		SERVER_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.server.type");
		HTTP_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("http.request.method");
		HTTP_ROUTE_ATTRIBUTE_KEY = AttributeKey.stringKey("http.route");
		URL_SCHEME_ATTRIBUTE_KEY = AttributeKey.stringKey("url.scheme");
		HTTP_STATUS_CODE_ATTRIBUTE_KEY = AttributeKey.longKey("http.response.status_code");
		ERROR_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("error.type");
		CLIENT_ADDRESS_ATTRIBUTE_KEY = AttributeKey.stringKey("client.address");
		REQUEST_ID_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.request.id");
		STREAM_TERMINATION_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.stream.termination.reason");
		RPC_SYSTEM_ATTRIBUTE_KEY = AttributeKey.stringKey("rpc.system");
		RPC_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("rpc.method");
		MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.endpoint.class");
		MCP_SESSION_ID_PRESENT_ATTRIBUTE_KEY = AttributeKey.booleanKey("soklet.mcp.session.id.present");
		MCP_REQUEST_ID_PRESENT_ATTRIBUTE_KEY = AttributeKey.booleanKey("soklet.mcp.request.id.present");
		MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.request.outcome");
		MCP_JSON_RPC_ERROR_CODE_ATTRIBUTE_KEY = AttributeKey.longKey("rpc.jsonrpc.error_code");
		SSE_CLIENT_CONTEXT_PRESENT_ATTRIBUTE_KEY = AttributeKey.booleanKey("soklet.sse.client_context.present");
		SSE_PAYLOAD_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.sse.payload.type");
		SSE_EVENT_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.sse.event.type");
	}

	@NonNull
	private final Tracer tracer;
	@NonNull
	private final SpanNamingStrategy spanNamingStrategy;
	@NonNull
	private final SpanPolicy spanPolicy;
	@NonNull
	private final ConcurrentMap<IdentityKey<Request>, SpanState> httpRequestSpans;
	@NonNull
	private final ConcurrentMap<IdentityKey<Request>, SpanState> mcpRequestSpans;
	@NonNull
	private final ConcurrentMap<IdentityKey<SseConnection>, SpanState> sseConnectionSpans;
	@NonNull
	private final ConcurrentMap<IdentityKey<McpSseStream>, SpanState> mcpSseStreamSpans;
	@NonNull
	private final AtomicBoolean closed;

	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	@NonNull
	public static Builder withOpenTelemetry(@NonNull OpenTelemetry openTelemetry) {
		requireNonNull(openTelemetry);
		return builder().openTelemetry(openTelemetry);
	}

	@NonNull
	public static Builder withTracer(@NonNull Tracer tracer) {
		requireNonNull(tracer);
		return builder().tracer(tracer);
	}

	@NonNull
	public static OpenTelemetryLifecycleObserver fromOpenTelemetry(@NonNull OpenTelemetry openTelemetry) {
		return withOpenTelemetry(openTelemetry).build();
	}

	@NonNull
	public static OpenTelemetryLifecycleObserver fromTracer(@NonNull Tracer tracer) {
		return withTracer(tracer).build();
	}

	private OpenTelemetryLifecycleObserver(@NonNull Builder builder) {
		requireNonNull(builder);

		this.tracer = requireNonNull(builder.resolveTracer());
		this.spanNamingStrategy = requireNonNull(builder.spanNamingStrategy);
		this.spanPolicy = requireNonNull(builder.spanPolicy);
		this.httpRequestSpans = new ConcurrentHashMap<>();
		this.mcpRequestSpans = new ConcurrentHashMap<>();
		this.sseConnectionSpans = new ConcurrentHashMap<>();
		this.mcpSseStreamSpans = new ConcurrentHashMap<>();
		this.closed = new AtomicBoolean(false);
	}

	@NonNull
	public Integer getActiveSpanCount() {
		return this.httpRequestSpans.size()
				+ this.mcpRequestSpans.size()
				+ this.sseConnectionSpans.size()
				+ this.mcpSseStreamSpans.size();
	}

	@Override
	public void close() {
		if (!this.closed.compareAndSet(false, true))
			return;

		drain(this.httpRequestSpans);
		drain(this.mcpRequestSpans);
		drain(this.sseConnectionSpans);
		drain(this.mcpSseStreamSpans);
	}

	@Override
	public void didStartRequestHandling(@NonNull ServerType serverType,
																			@NonNull Request request,
																			@Nullable ResourceMethod resourceMethod) {
		requireNonNull(serverType);
		requireNonNull(request);

		if (this.closed.get() || !this.spanPolicy.recordHttpRequestSpans())
			return;

		safelyRun(() -> {
			Instant startedAt = Instant.now();
			Span span = this.tracer.spanBuilder(this.spanNamingStrategy.httpRequestSpanName(request, resourceMethod))
					.setSpanKind(SpanKind.SERVER)
					.setParent(parentContextFor(request))
					.setStartTimestamp(startedAt)
					.setAttribute(SERVER_TYPE_ATTRIBUTE_KEY, serverTypeValue(serverType))
					.setAttribute(HTTP_METHOD_ATTRIBUTE_KEY, request.getHttpMethod().name())
					.setAttribute(URL_SCHEME_ATTRIBUTE_KEY, URL_SCHEME_HTTP)
					.startSpan();

			try {
				setRouteAttribute(span, resourceMethod);
				setOptionalRequestAttributes(span, request);

				if (this.closed.get()) {
					endSpanSafely(span);
					return;
				}

				SpanState spanState = new SpanState(span, request, resourceMethod, startedAt);
				storeReplacing(this.httpRequestSpans, new IdentityKey<>(request), spanState);
			} catch (RuntimeException e) {
				endSpanSafely(span);
				throw e;
			}
		});
	}

	@Override
	public void didFinishRequestHandling(@NonNull ServerType serverType,
																			 @NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod,
																			 @NonNull MarshaledResponse marshaledResponse,
																			 @NonNull Duration duration,
																			 @NonNull List<@NonNull Throwable> throwables) {
		requireNonNull(serverType);
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(duration);
		requireNonNull(throwables);

		if (this.closed.get() || !this.spanPolicy.recordHttpRequestSpans())
			return;

		IdentityKey<Request> key = new IdentityKey<>(request);
		SpanState spanState = this.httpRequestSpans.get(key);

		if (spanState == null)
			return;

		safelyRun(() -> {
			boolean keepOpen = marshaledResponse.isStreaming() && this.spanPolicy.recordStreamingResponseSpans();

			try {
				applyHttpFinish(spanState.span(), resourceMethod, marshaledResponse, throwables);
			} finally {
				if (!keepOpen && this.httpRequestSpans.remove(key, spanState))
					endSpanSafely(spanState.span(), spanState.startedAt().plus(duration));
			}
		});
	}

	@Override
	public void didTerminateResponseStream(@NonNull StreamingResponseHandle streamingResponse,
																				 @NonNull StreamTermination termination) {
		requireNonNull(streamingResponse);
		requireNonNull(termination);

		if (this.closed.get() || !this.spanPolicy.recordHttpRequestSpans() || !this.spanPolicy.recordStreamingResponseSpans())
			return;

		safelyRun(() -> {
			IdentityKey<Request> key = new IdentityKey<>(streamingResponse.getRequest());
			SpanState spanState = this.httpRequestSpans.remove(key);

			if (spanState == null)
				spanState = backfilledStreamingSpan(streamingResponse);

			try {
				applyStreamTermination(spanState.span(), termination);
			} finally {
				endSpanSafely(spanState.span(), streamingResponse.getEstablishedAt().plus(termination.getDuration()));
			}
		});
	}

	@Override
	public void didEstablishSseConnection(@NonNull SseConnection sseConnection) {
		requireNonNull(sseConnection);

		if (this.closed.get() || !this.spanPolicy.recordSseConnectionSpans())
			return;

		safelyRun(() -> {
			Span span = this.tracer.spanBuilder(this.spanNamingStrategy.sseConnectionSpanName(sseConnection))
					.setSpanKind(SpanKind.SERVER)
					.setParent(parentContextFor(sseConnection.getRequest()))
					.setStartTimestamp(sseConnection.getEstablishedAt())
					.setAttribute(SERVER_TYPE_ATTRIBUTE_KEY, SERVER_TYPE_SSE)
					.setAttribute(HTTP_METHOD_ATTRIBUTE_KEY, sseConnection.getRequest().getHttpMethod().name())
					.setAttribute(URL_SCHEME_ATTRIBUTE_KEY, URL_SCHEME_HTTP)
					.setAttribute(HTTP_ROUTE_ATTRIBUTE_KEY, sseConnection.getResourceMethod().getResourcePathDeclaration().getPath())
					.setAttribute(SSE_CLIENT_CONTEXT_PRESENT_ATTRIBUTE_KEY, sseConnection.getClientContext().isPresent())
					.startSpan();

			try {
				setOptionalRequestAttributes(span, sseConnection.getRequest());

				if (this.closed.get()) {
					endSpanSafely(span);
					return;
				}

				storeReplacing(this.sseConnectionSpans, new IdentityKey<>(sseConnection),
						new SpanState(span, sseConnection.getRequest(), sseConnection.getResourceMethod(), sseConnection.getEstablishedAt()));
			} catch (RuntimeException e) {
				endSpanSafely(span);
				throw e;
			}
		});
	}

	@Override
	public void didWriteSseEvent(@NonNull SseConnection sseConnection,
															 @NonNull SseEvent sseEvent,
															 @NonNull Duration writeDuration) {
		requireNonNull(sseConnection);
		requireNonNull(sseEvent);
		requireNonNull(writeDuration);

		if (this.closed.get() || !this.spanPolicy.recordSseWriteEvents())
			return;

		safelyRun(() -> {
			SpanState spanState = this.sseConnectionSpans.get(new IdentityKey<>(sseConnection));

			if (spanState != null)
				spanState.span().addEvent("sse.event.written", Attributes.of(
						SSE_PAYLOAD_TYPE_ATTRIBUTE_KEY, "event",
						SSE_EVENT_TYPE_ATTRIBUTE_KEY, sseEvent.getEvent().orElse("message")));
		});
	}

	@Override
	public void didFailToWriteSseEvent(@NonNull SseConnection sseConnection,
																		 @NonNull SseEvent sseEvent,
																		 @NonNull Duration writeDuration,
																		 @NonNull Throwable throwable) {
		requireNonNull(sseConnection);
		requireNonNull(sseEvent);
		requireNonNull(writeDuration);
		requireNonNull(throwable);

		if (this.closed.get())
			return;

		safelyRun(() -> {
			SpanState spanState = this.sseConnectionSpans.get(new IdentityKey<>(sseConnection));

			if (spanState != null)
				recordException(spanState.span(), throwable);
		});
	}

	@Override
	public void didWriteSseComment(@NonNull SseConnection sseConnection,
																 @NonNull SseComment sseComment,
																 @NonNull Duration writeDuration) {
		requireNonNull(sseConnection);
		requireNonNull(sseComment);
		requireNonNull(writeDuration);

		if (this.closed.get() || !this.spanPolicy.recordSseWriteEvents())
			return;

		safelyRun(() -> {
			SpanState spanState = this.sseConnectionSpans.get(new IdentityKey<>(sseConnection));

			if (spanState != null)
				spanState.span().addEvent("sse.comment.written", Attributes.of(
						SSE_PAYLOAD_TYPE_ATTRIBUTE_KEY, "comment",
						SSE_EVENT_TYPE_ATTRIBUTE_KEY, enumValue(sseComment.getCommentType())));
		});
	}

	@Override
	public void didFailToWriteSseComment(@NonNull SseConnection sseConnection,
																			 @NonNull SseComment sseComment,
																			 @NonNull Duration writeDuration,
																			 @NonNull Throwable throwable) {
		requireNonNull(sseConnection);
		requireNonNull(sseComment);
		requireNonNull(writeDuration);
		requireNonNull(throwable);

		if (this.closed.get())
			return;

		safelyRun(() -> {
			SpanState spanState = this.sseConnectionSpans.get(new IdentityKey<>(sseConnection));

			if (spanState != null)
				recordException(spanState.span(), throwable);
		});
	}

	@Override
	public void didTerminateSseConnection(@NonNull SseConnection sseConnection,
																				@NonNull StreamTermination termination) {
		requireNonNull(sseConnection);
		requireNonNull(termination);

		if (this.closed.get() || !this.spanPolicy.recordSseConnectionSpans())
			return;

		safelyRun(() -> {
			SpanState spanState = this.sseConnectionSpans.remove(new IdentityKey<>(sseConnection));

			if (spanState == null)
				return;

			try {
				applyStreamTermination(spanState.span(), termination);
			} finally {
				endSpanSafely(spanState.span(), sseConnection.getEstablishedAt().plus(termination.getDuration()));
			}
		});
	}

	@Override
	public void didStartMcpRequestHandling(@NonNull Request request,
																				 @NonNull Class<? extends McpEndpoint> endpointClass,
																				 @Nullable String sessionId,
																				 @NonNull String jsonRpcMethod,
																				 @Nullable McpJsonRpcRequestId jsonRpcRequestId) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(jsonRpcMethod);

		if (this.closed.get() || !this.spanPolicy.recordMcpRequestSpans())
			return;

		safelyRun(() -> {
			Instant startedAt = Instant.now();
			Span span = this.tracer.spanBuilder(this.spanNamingStrategy.mcpRequestSpanName(request, endpointClass, jsonRpcMethod))
					.setSpanKind(SpanKind.SERVER)
					.setParent(parentContextFor(request))
					.setStartTimestamp(startedAt)
					.setAttribute(SERVER_TYPE_ATTRIBUTE_KEY, SERVER_TYPE_MCP)
					.setAttribute(RPC_SYSTEM_ATTRIBUTE_KEY, "jsonrpc")
					.setAttribute(RPC_METHOD_ATTRIBUTE_KEY, jsonRpcMethod)
					.setAttribute(MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY, endpointClass.getName())
					.setAttribute(MCP_SESSION_ID_PRESENT_ATTRIBUTE_KEY, sessionId != null)
					.setAttribute(MCP_REQUEST_ID_PRESENT_ATTRIBUTE_KEY, jsonRpcRequestId != null)
					.startSpan();

			try {
				setOptionalRequestAttributes(span, request);

				if (this.closed.get()) {
					endSpanSafely(span);
					return;
				}

				storeReplacing(this.mcpRequestSpans, new IdentityKey<>(request), new SpanState(span, request, null, startedAt));
			} catch (RuntimeException e) {
				endSpanSafely(span);
				throw e;
			}
		});
	}

	@Override
	public void didFinishMcpRequestHandling(@NonNull Request request,
																					@NonNull Class<? extends McpEndpoint> endpointClass,
																					@Nullable String sessionId,
																					@NonNull String jsonRpcMethod,
																					@Nullable McpJsonRpcRequestId jsonRpcRequestId,
																					@NonNull McpRequestOutcome requestOutcome,
																					@Nullable McpJsonRpcError jsonRpcError,
																					@NonNull Duration duration,
																					@NonNull List<@NonNull Throwable> throwables) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(jsonRpcMethod);
		requireNonNull(requestOutcome);
		requireNonNull(duration);
		requireNonNull(throwables);

		if (this.closed.get() || !this.spanPolicy.recordMcpRequestSpans())
			return;

		safelyRun(() -> {
			SpanState spanState = this.mcpRequestSpans.remove(new IdentityKey<>(request));

			if (spanState == null)
				return;

			try {
				spanState.span().setAttribute(MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY, enumValue(requestOutcome));

				if (jsonRpcError != null) {
					spanState.span().setAttribute(MCP_JSON_RPC_ERROR_CODE_ATTRIBUTE_KEY, jsonRpcError.code().longValue());
					spanState.span().setAttribute(ERROR_TYPE_ATTRIBUTE_KEY, "json_rpc_error");
				}

				for (Throwable throwable : throwables)
					recordException(spanState.span(), throwable);

				if (jsonRpcError != null || !throwables.isEmpty() || requestOutcome == McpRequestOutcome.JSON_RPC_ERROR)
					spanState.span().setStatus(StatusCode.ERROR);
			} finally {
				endSpanSafely(spanState.span(), spanState.startedAt().plus(duration));
			}
		});
	}

	@Override
	public void didCreateMcpSession(@NonNull Request request,
																	@NonNull Class<? extends McpEndpoint> endpointClass,
																	@NonNull String sessionId) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(sessionId);

		if (this.closed.get() || !this.spanPolicy.recordMcpSessionEvents())
			return;

		safelyRun(() -> {
			SpanState spanState = this.mcpRequestSpans.get(new IdentityKey<>(request));

			if (spanState != null)
				spanState.span().addEvent("mcp.session.created", Attributes.of(
						MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY, endpointClass.getName(),
						MCP_SESSION_ID_PRESENT_ATTRIBUTE_KEY, true));
		});
	}

	@Override
	public void didEstablishMcpSseStream(@NonNull McpSseStream stream) {
		requireNonNull(stream);

		if (this.closed.get() || !this.spanPolicy.recordMcpSseStreamSpans())
			return;

		safelyRun(() -> {
			Span span = this.tracer.spanBuilder(this.spanNamingStrategy.mcpSseStreamSpanName(stream))
					.setSpanKind(SpanKind.SERVER)
					.setParent(parentContextFor(stream.getRequest()))
					.setStartTimestamp(stream.getEstablishedAt())
					.setAttribute(SERVER_TYPE_ATTRIBUTE_KEY, SERVER_TYPE_MCP)
					.setAttribute(MCP_ENDPOINT_CLASS_ATTRIBUTE_KEY, stream.getEndpointClass().getName())
					.setAttribute(MCP_SESSION_ID_PRESENT_ATTRIBUTE_KEY, true)
					.startSpan();

			try {
				setOptionalRequestAttributes(span, stream.getRequest());

				if (this.closed.get()) {
					endSpanSafely(span);
					return;
				}

				storeReplacing(this.mcpSseStreamSpans, new IdentityKey<>(stream), new SpanState(span, stream.getRequest(), null, stream.getEstablishedAt()));
			} catch (RuntimeException e) {
				endSpanSafely(span);
				throw e;
			}
		});
	}

	@Override
	public void didTerminateMcpSseStream(@NonNull McpSseStream stream,
																			 @NonNull StreamTermination termination) {
		requireNonNull(stream);
		requireNonNull(termination);

		if (this.closed.get() || !this.spanPolicy.recordMcpSseStreamSpans())
			return;

		safelyRun(() -> {
			SpanState spanState = this.mcpSseStreamSpans.remove(new IdentityKey<>(stream));

			if (spanState == null)
				return;

			try {
				applyStreamTermination(spanState.span(), termination);
			} finally {
				endSpanSafely(spanState.span(), stream.getEstablishedAt().plus(termination.getDuration()));
			}
		});
	}

	private void applyHttpFinish(@NonNull Span span,
															 @Nullable ResourceMethod resourceMethod,
															 @NonNull MarshaledResponse marshaledResponse,
															 @NonNull List<@NonNull Throwable> throwables) {
		requireNonNull(span);
		requireNonNull(marshaledResponse);
		requireNonNull(throwables);

		setRouteAttribute(span, resourceMethod);
		span.setAttribute(HTTP_STATUS_CODE_ATTRIBUTE_KEY, marshaledResponse.getStatusCode().longValue());

		for (Throwable throwable : throwables)
			recordException(span, throwable);

		if (!throwables.isEmpty()) {
			span.setStatus(StatusCode.ERROR);
		} else if (marshaledResponse.getStatusCode() >= 500) {
			span.setAttribute(ERROR_TYPE_ATTRIBUTE_KEY, "http.status_code");
			span.setStatus(StatusCode.ERROR);
		}
	}

	@NonNull
	private SpanState backfilledStreamingSpan(@NonNull StreamingResponseHandle stream) {
		requireNonNull(stream);

		Span span = this.tracer.spanBuilder(this.spanNamingStrategy.streamingResponseSpanName(stream))
				.setSpanKind(SpanKind.SERVER)
				.setParent(parentContextFor(stream.getRequest()))
				.setStartTimestamp(stream.getEstablishedAt())
				.setAttribute(SERVER_TYPE_ATTRIBUTE_KEY, serverTypeValue(stream.getServerType()))
				.setAttribute(HTTP_METHOD_ATTRIBUTE_KEY, stream.getRequest().getHttpMethod().name())
				.setAttribute(URL_SCHEME_ATTRIBUTE_KEY, URL_SCHEME_HTTP)
				.startSpan();

		try {
			setRouteAttribute(span, stream.getResourceMethod().orElse(null));
			setOptionalRequestAttributes(span, stream.getRequest());
			return new SpanState(span, stream.getRequest(), stream.getResourceMethod().orElse(null), stream.getEstablishedAt());
		} catch (RuntimeException e) {
			endSpanSafely(span);
			throw e;
		}
	}

	private void applyStreamTermination(@NonNull Span span,
																			@NonNull StreamTermination termination) {
		requireNonNull(span);
		requireNonNull(termination);

		span.setAttribute(STREAM_TERMINATION_REASON_ATTRIBUTE_KEY, enumValue(termination.getReason()));
		termination.getCause().ifPresent(throwable -> recordException(span, throwable));

		if (isError(termination))
			span.setStatus(StatusCode.ERROR);
	}

	private boolean isError(@NonNull StreamTermination termination) {
		requireNonNull(termination);

		if (termination.getCause().isPresent())
			return true;

		return switch (termination.getReason()) {
			case COMPLETED, CLIENT_DISCONNECTED, SERVER_STOPPING, APPLICATION_CANCELED, SESSION_TERMINATED -> false;
			case PROTOCOL_UNSUPPORTED, RESPONSE_TIMEOUT, RESPONSE_IDLE_TIMEOUT, BACKPRESSURE, WRITE_FAILED,
					 PRODUCER_FAILED, INTERNAL_ERROR, SIMULATOR_LIMIT_EXCEEDED, UNKNOWN -> true;
		};
	}

	private void recordException(@NonNull Span span,
															 @NonNull Throwable throwable) {
		requireNonNull(span);
		requireNonNull(throwable);

		span.recordException(throwable);
		span.setAttribute(ERROR_TYPE_ATTRIBUTE_KEY, throwable.getClass().getName());
		span.setStatus(StatusCode.ERROR);
	}

	private void safelyRun(@NonNull Runnable runnable) {
		requireNonNull(runnable);

		try {
			runnable.run();
		} catch (RuntimeException e) {
			// Telemetry failures must never affect application request handling.
		}
	}

	private void endSpanSafely(@NonNull Span span) {
		requireNonNull(span);

		try {
			span.end();
		} catch (RuntimeException e) {
			// Telemetry failures must never affect application request handling.
		}
	}

	private void endSpanSafely(@NonNull Span span,
														 @NonNull Instant timestamp) {
		requireNonNull(span);
		requireNonNull(timestamp);

		try {
			span.end(timestamp);
		} catch (RuntimeException e) {
			// Telemetry failures must never affect application request handling.
		}
	}

	private void setRouteAttribute(@NonNull Span span,
																 @Nullable ResourceMethod resourceMethod) {
		requireNonNull(span);

		if (resourceMethod != null)
			span.setAttribute(HTTP_ROUTE_ATTRIBUTE_KEY, resourceMethod.getResourcePathDeclaration().getPath());
	}

	private void setOptionalRequestAttributes(@NonNull Span span,
																						@NonNull Request request) {
		requireNonNull(span);
		requireNonNull(request);

		if (this.spanPolicy.recordClientAddress()) {
			InetSocketAddress remoteAddress = request.getRemoteAddress().orElse(null);

			if (remoteAddress != null && remoteAddress.getAddress() != null)
				span.setAttribute(CLIENT_ADDRESS_ATTRIBUTE_KEY, remoteAddress.getAddress().getHostAddress());
		}

		if (this.spanPolicy.recordRequestId())
			span.setAttribute(REQUEST_ID_ATTRIBUTE_KEY, String.valueOf(request.getId()));
	}

	@NonNull
	private Context parentContextFor(@NonNull Request request) {
		requireNonNull(request);

		TraceContext traceContext = request.getTraceContext().orElse(null);

		if (traceContext == null)
			return Context.root();

		try {
			TraceStateBuilder traceStateBuilder = TraceState.builder();

			for (TraceStateEntry traceStateEntry : traceContext.getTraceStateEntries())
				traceStateBuilder.put(traceStateEntry.getKey(), traceStateEntry.getValue());

			SpanContext spanContext = SpanContext.createFromRemoteParent(
					traceContext.getTraceId(),
					traceContext.getParentId(),
					TraceFlags.fromByte((byte) (traceContext.getTraceFlags() & 0xFF)),
					traceStateBuilder.build());

			if (!spanContext.isValid())
				return Context.root();

			return Span.wrap(spanContext).storeInContext(Context.root());
		} catch (RuntimeException e) {
			return Context.root();
		}
	}

	private <T> void storeReplacing(@NonNull ConcurrentMap<IdentityKey<T>, SpanState> spanStates,
																	@NonNull IdentityKey<T> identityKey,
																	@NonNull SpanState spanState) {
		requireNonNull(spanStates);
		requireNonNull(identityKey);
		requireNonNull(spanState);

		spanStates.compute(identityKey, (key, existingSpanState) -> {
			if (existingSpanState != null)
				endServerStopping(existingSpanState);

			if (this.closed.get()) {
				endSpanSafely(spanState.span());
				return null;
			}

			return spanState;
		});
	}

	private void drain(@NonNull ConcurrentMap<?, SpanState> spanStates) {
		requireNonNull(spanStates);

		for (Map.Entry<?, SpanState> entry : spanStates.entrySet()) {
			try {
				SpanState spanState = entry.getValue();

				if (spanStates.remove(entry.getKey(), spanState))
					endServerStopping(spanState);
			} catch (RuntimeException e) {
				// Drain must best-effort every active span even if one entry fails.
			}
		}
	}

	private void endServerStopping(@NonNull SpanState spanState) {
		requireNonNull(spanState);

		safelyRun(() -> {
			// SERVER_STOPPING is operational shutdown, not a span error.
			spanState.span().setAttribute(STREAM_TERMINATION_REASON_ATTRIBUTE_KEY, enumValue(StreamTerminationReason.SERVER_STOPPING));
			spanState.span().end();
		});
	}

	@NonNull
	private static String serverTypeValue(@NonNull ServerType serverType) {
		requireNonNull(serverType);

		return switch (serverType) {
			case STANDARD_HTTP -> SERVER_TYPE_HTTP;
			case SSE -> SERVER_TYPE_SSE;
			case MCP -> SERVER_TYPE_MCP;
		};
	}

	@NonNull
	private static String enumValue(@NonNull Enum<?> value) {
		requireNonNull(value);
		return value.name().toLowerCase(Locale.ROOT);
	}

	@NotThreadSafe
	public static final class Builder {
		@Nullable
		private OpenTelemetry openTelemetry;
		@Nullable
		private Tracer tracer;
		@NonNull
		private String instrumentationName;
		@Nullable
		private String instrumentationVersion;
		@NonNull
		private SpanNamingStrategy spanNamingStrategy;
		@NonNull
		private SpanPolicy spanPolicy;

		private Builder() {
			this.openTelemetry = GlobalOpenTelemetry.get();
			this.instrumentationName = DEFAULT_INSTRUMENTATION_NAME;
			this.instrumentationVersion = defaultInstrumentationVersion();
			this.spanNamingStrategy = SpanNamingStrategy.defaultInstance();
			this.spanPolicy = SpanPolicy.defaultInstance();
		}

		@NonNull
		public Builder openTelemetry(@NonNull OpenTelemetry openTelemetry) {
			this.openTelemetry = requireNonNull(openTelemetry);
			this.tracer = null;
			return this;
		}

		@NonNull
		public Builder tracer(@NonNull Tracer tracer) {
			this.tracer = requireNonNull(tracer);
			return this;
		}

		@NonNull
		public Builder instrumentationName(@NonNull String instrumentationName) {
			this.instrumentationName = requireNonNull(instrumentationName);
			this.tracer = null;
			return this;
		}

		@NonNull
		public Builder instrumentationVersion(@Nullable String instrumentationVersion) {
			this.instrumentationVersion = instrumentationVersion;
			this.tracer = null;
			return this;
		}

		@NonNull
		public Builder spanNamingStrategy(@NonNull SpanNamingStrategy spanNamingStrategy) {
			this.spanNamingStrategy = requireNonNull(spanNamingStrategy);
			return this;
		}

		@NonNull
		public Builder spanPolicy(@NonNull SpanPolicy spanPolicy) {
			this.spanPolicy = requireNonNull(spanPolicy);
			return this;
		}

		@NonNull
		public OpenTelemetryLifecycleObserver build() {
			return new OpenTelemetryLifecycleObserver(this);
		}

		@NonNull
		private Tracer resolveTracer() {
			if (this.tracer != null)
				return this.tracer;

			TracerBuilder tracerBuilder = requireNonNull(this.openTelemetry).tracerBuilder(this.instrumentationName);

			if (this.instrumentationVersion != null)
				tracerBuilder.setInstrumentationVersion(this.instrumentationVersion);

			return tracerBuilder.build();
		}
	}

	@Nullable
	private static String defaultInstrumentationVersion() {
		return OpenTelemetryLifecycleObserver.class.getPackage().getImplementationVersion();
	}

	private record SpanState(
			@NonNull Span span,
			@NonNull Request request,
			@Nullable ResourceMethod resourceMethod,
			@NonNull Instant startedAt
	) {
		private SpanState {
			requireNonNull(span);
			requireNonNull(request);
			requireNonNull(startedAt);
		}
	}

	@ThreadSafe
	private static final class IdentityKey<T> {
		@NonNull
		private final T value;
		private final Integer hashCode;

		private IdentityKey(@NonNull T value) {
			this.value = requireNonNull(value);
			this.hashCode = System.identityHashCode(value);
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof IdentityKey<?> identityKey))
				return false;

			return this.value == identityKey.value;
		}

		@Override
		public int hashCode() {
			return this.hashCode;
		}

		@Override
		@NonNull
		public String toString() {
			return "%s{value=%s}".formatted(getClass().getSimpleName(), this.value);
		}
	}
}
