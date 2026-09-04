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
import com.soklet.McpJsonRpcError;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
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
	private static final String RPC_SYSTEM_JSON_RPC;
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
	private static final AttributeKey<String> RPC_SYSTEM_NAME_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> RPC_METHOD_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> MCP_ENDPOINT_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY;
	@NonNull
	private static final AttributeKey<String> RPC_RESPONSE_STATUS_CODE_ATTRIBUTE_KEY;
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
		RPC_SYSTEM_JSON_RPC = "jsonrpc";

		SERVER_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.server.type");
		HTTP_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("http.request.method");
		HTTP_ROUTE_ATTRIBUTE_KEY = AttributeKey.stringKey("http.route");
		URL_SCHEME_ATTRIBUTE_KEY = AttributeKey.stringKey("url.scheme");
		HTTP_STATUS_CODE_ATTRIBUTE_KEY = AttributeKey.longKey("http.response.status_code");
		ERROR_TYPE_ATTRIBUTE_KEY = AttributeKey.stringKey("error.type");
		CLIENT_ADDRESS_ATTRIBUTE_KEY = AttributeKey.stringKey("client.address");
		REQUEST_ID_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.request.id");
		STREAM_TERMINATION_REASON_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.stream.termination.reason");
		RPC_SYSTEM_NAME_ATTRIBUTE_KEY = AttributeKey.stringKey("rpc.system.name");
		RPC_METHOD_ATTRIBUTE_KEY = AttributeKey.stringKey("rpc.method");
		MCP_ENDPOINT_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.endpoint");
		MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY = AttributeKey.stringKey("soklet.mcp.request.outcome");
		RPC_RESPONSE_STATUS_CODE_ATTRIBUTE_KEY = AttributeKey.stringKey("rpc.response.status_code");
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
	private final ConcurrentMap<IdentityKey<McpRequestContext>, McpSpanState> mcpRequestSpans;
	@NonNull
	private final ConcurrentMap<IdentityKey<SseConnection>, SpanState> sseConnectionSpans;
	@Nullable
	private final Runnable beforeMcpSpanPublication;
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
		this.beforeMcpSpanPublication = builder.beforeMcpSpanPublication;
		this.closed = new AtomicBoolean(false);
	}

	@NonNull
	public Integer getActiveSpanCount() {
		return this.httpRequestSpans.size()
				+ this.mcpRequestSpans.size()
				+ this.sseConnectionSpans.size();
	}

	@Override
	public void close() {
		if (!this.closed.compareAndSet(false, true))
			return;

		drain(this.httpRequestSpans);
		drainMcpRequestSpans();
		drain(this.sseConnectionSpans);
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
	public void didStartMcpRequestHandling(@NonNull McpRequestContext context) {
		requireNonNull(context);

		if (this.closed.get() || !this.spanPolicy.recordMcpRequestSpans())
			return;

		safelyRun(() -> {
			Instant startedAt = Instant.now();
			Span span = this.tracer.spanBuilder(this.spanNamingStrategy.mcpRequestSpanName(context))
					.setSpanKind(SpanKind.SERVER)
					.setParent(parentContextFor(context.getTraceContext().orElse(null)))
					.setStartTimestamp(startedAt)
					.setAttribute(SERVER_TYPE_ATTRIBUTE_KEY, SERVER_TYPE_MCP)
					.setAttribute(RPC_SYSTEM_NAME_ATTRIBUTE_KEY, RPC_SYSTEM_JSON_RPC)
					.setAttribute(RPC_METHOD_ATTRIBUTE_KEY,
							DefaultSpanNamingStrategy.boundedMcpJsonRpcMethod(
									context.getJsonRpcMethod()))
					.setAttribute(MCP_ENDPOINT_ATTRIBUTE_KEY, context.getEndpoint().getPath())
					.startSpan();

			try {
				if (this.spanPolicy.recordClientAddress() || this.spanPolicy.recordRequestId())
					setOptionalRequestAttributes(span, context.getRequest());

				if (this.closed.get()) {
					endSpanSafely(span);
					return;
				}

				storeReplacingMcpRequestSpan(new IdentityKey<>(context),
						new McpSpanState(span, startedAt));
			} catch (RuntimeException e) {
				endSpanSafely(span);
				throw e;
			}
		});
	}

	@Override
	public void didFinishMcpRequestHandling(@NonNull McpRequestContext context,
			@NonNull McpRequestOutcome outcome,
			@Nullable McpJsonRpcError error,
			@NonNull Duration duration,
			@NonNull List<@NonNull Throwable> throwables) {
		requireNonNull(context);
		requireNonNull(outcome);
		requireNonNull(duration);
		requireNonNull(throwables);

		if (this.closed.get() || !this.spanPolicy.recordMcpRequestSpans())
			return;

		safelyRun(() -> {
			McpSpanState spanState = this.mcpRequestSpans.remove(
					new IdentityKey<>(context));

			if (spanState == null)
				return;

			try {
				applyMcpFinish(spanState.span(), outcome, error);
			} finally {
				endSpanSafely(spanState.span(), spanState.startedAt(), duration);
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

	private void applyMcpFinish(@NonNull Span span,
			@NonNull McpRequestOutcome outcome,
			@Nullable McpJsonRpcError error) {
		requireNonNull(span);
		requireNonNull(outcome);

		String outcomeValue = enumValue(outcome);
		span.setAttribute(MCP_REQUEST_OUTCOME_ATTRIBUTE_KEY, outcomeValue);

		if (error != null) {
			String statusCode = String.valueOf(error.getCode());
			span.setAttribute(RPC_RESPONSE_STATUS_CODE_ATTRIBUTE_KEY, statusCode);
			span.setAttribute(ERROR_TYPE_ATTRIBUTE_KEY, statusCode);
			span.setStatus(StatusCode.ERROR);
		} else if (isMcpErrorOutcome(outcome)) {
			span.setAttribute(ERROR_TYPE_ATTRIBUTE_KEY, outcomeValue);
			span.setStatus(StatusCode.ERROR);
		}
	}

	private boolean isMcpErrorOutcome(@NonNull McpRequestOutcome outcome) {
		requireNonNull(outcome);

		return switch (outcome) {
			case COMPLETE, INPUT_REQUIRED, CANCELED, CLIENT_DISCONNECTED -> false;
			case REJECTED, APPLICATION_ERROR, PROTOCOL_ERROR, INTERNAL_ERROR,
					 DEADLINE_EXCEEDED, WRITE_FAILED -> true;
		};
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
			case COMPLETED, CLIENT_DISCONNECTED, SERVER_STOPPING, APPLICATION_CANCELED -> false;
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

	private void endSpanSafely(@NonNull Span span,
			@NonNull Instant startedAt,
			@NonNull Duration duration) {
		requireNonNull(span);
		requireNonNull(startedAt);
		requireNonNull(duration);

		try {
			endSpanSafely(span, startedAt.plus(duration));
		} catch (RuntimeException e) {
			// Manually supplied durations can exceed Instant's range.
			endSpanSafely(span);
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
		return parentContextFor(request.getTraceContext().orElse(null));
	}

	@NonNull
	private Context parentContextFor(@Nullable TraceContext traceContext) {
		if (traceContext == null)
			return Context.root();

		try {
			TraceStateBuilder traceStateBuilder = TraceState.builder();
			List<TraceStateEntry> traceStateEntries =
					traceContext.getTraceStateEntries();

			// OpenTelemetry prepends each value inserted by put(), so traverse
			// Soklet's W3C-ordered entries in reverse to preserve their order.
			for (int index = traceStateEntries.size() - 1; index >= 0; --index) {
				TraceStateEntry traceStateEntry = traceStateEntries.get(index);
				traceStateBuilder.put(traceStateEntry.getKey(), traceStateEntry.getValue());
			}

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

	private void storeReplacingMcpRequestSpan(
			@NonNull IdentityKey<McpRequestContext> identityKey,
			@NonNull McpSpanState spanState) {
		requireNonNull(identityKey);
		requireNonNull(spanState);

		this.mcpRequestSpans.compute(identityKey, (key, existingSpanState) -> {
			if (existingSpanState != null)
				endSpanSafely(existingSpanState.span());

			boolean closedBeforePublication = this.closed.get();

			if (!closedBeforePublication && this.beforeMcpSpanPublication != null)
				this.beforeMcpSpanPublication.run();

			if (closedBeforePublication) {
				endSpanSafely(spanState.span());
				return null;
			}

			return spanState;
		});

		if (this.closed.get()
				&& this.mcpRequestSpans.remove(identityKey, spanState))
			endSpanSafely(spanState.span());
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

	private void drainMcpRequestSpans() {
		for (Map.Entry<IdentityKey<McpRequestContext>, McpSpanState> entry
				: this.mcpRequestSpans.entrySet()) {
			try {
				McpSpanState spanState = entry.getValue();

				if (this.mcpRequestSpans.remove(entry.getKey(), spanState))
					endSpanSafely(spanState.span());
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
		@Nullable
		private Runnable beforeMcpSpanPublication;

		private Builder() {
			this.openTelemetry = GlobalOpenTelemetry.get();
			this.instrumentationName = DEFAULT_INSTRUMENTATION_NAME;
			this.instrumentationVersion = defaultInstrumentationVersion();
			this.spanNamingStrategy = SpanNamingStrategy.defaultInstance();
			this.spanPolicy = SpanPolicy.defaultInstance();
			this.beforeMcpSpanPublication = null;
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
		Builder beforeMcpSpanPublicationForTesting(@NonNull Runnable hook) {
			this.beforeMcpSpanPublication = requireNonNull(hook);
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

	private record McpSpanState(
			@NonNull Span span,
			@NonNull Instant startedAt
	) {
		private McpSpanState {
			requireNonNull(span);
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
