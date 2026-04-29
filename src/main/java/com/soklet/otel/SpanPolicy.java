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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Objects;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Controls which Soklet lifecycle events are converted into OpenTelemetry spans or span events.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class SpanPolicy {
	@NonNull
	private static final SpanPolicy DEFAULT_INSTANCE;

	static {
		DEFAULT_INSTANCE = builder().build();
	}

	@NonNull
	private final Boolean recordHttpRequestSpans;
	@NonNull
	private final Boolean recordStreamingResponseSpans;
	@NonNull
	private final Boolean recordSseConnectionSpans;
	@NonNull
	private final Boolean recordSseWriteEvents;
	@NonNull
	private final Boolean recordMcpRequestSpans;
	@NonNull
	private final Boolean recordMcpSessionEvents;
	@NonNull
	private final Boolean recordMcpSseStreamSpans;
	@NonNull
	private final Boolean recordClientAddress;
	@NonNull
	private final Boolean recordRequestId;

	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	@NonNull
	public static SpanPolicy defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	private SpanPolicy(@NonNull Builder builder) {
		requireNonNull(builder);

		this.recordHttpRequestSpans = builder.recordHttpRequestSpans;
		this.recordStreamingResponseSpans = builder.recordStreamingResponseSpans;
		this.recordSseConnectionSpans = builder.recordSseConnectionSpans;
		this.recordSseWriteEvents = builder.recordSseWriteEvents;
		this.recordMcpRequestSpans = builder.recordMcpRequestSpans;
		this.recordMcpSessionEvents = builder.recordMcpSessionEvents;
		this.recordMcpSseStreamSpans = builder.recordMcpSseStreamSpans;
		this.recordClientAddress = builder.recordClientAddress;
		this.recordRequestId = builder.recordRequestId;
	}

	@NonNull
	public Boolean recordHttpRequestSpans() {
		return this.recordHttpRequestSpans;
	}

	@NonNull
	public Boolean recordStreamingResponseSpans() {
		return this.recordStreamingResponseSpans;
	}

	@NonNull
	public Boolean recordSseConnectionSpans() {
		return this.recordSseConnectionSpans;
	}

	@NonNull
	public Boolean recordSseWriteEvents() {
		return this.recordSseWriteEvents;
	}

	@NonNull
	public Boolean recordMcpRequestSpans() {
		return this.recordMcpRequestSpans;
	}

	@NonNull
	public Boolean recordMcpSessionEvents() {
		return this.recordMcpSessionEvents;
	}

	@NonNull
	public Boolean recordMcpSseStreamSpans() {
		return this.recordMcpSseStreamSpans;
	}

	@NonNull
	public Boolean recordClientAddress() {
		return this.recordClientAddress;
	}

	@NonNull
	public Boolean recordRequestId() {
		return this.recordRequestId;
	}

	@Override
	@NonNull
	public String toString() {
		return format("%s{recordHttpRequestSpans=%s, recordStreamingResponseSpans=%s, recordSseConnectionSpans=%s, recordSseWriteEvents=%s, recordMcpRequestSpans=%s, recordMcpSessionEvents=%s, recordMcpSseStreamSpans=%s, recordClientAddress=%s, recordRequestId=%s}",
				getClass().getSimpleName(), recordHttpRequestSpans(), recordStreamingResponseSpans(), recordSseConnectionSpans(),
				recordSseWriteEvents(), recordMcpRequestSpans(), recordMcpSessionEvents(),
				recordMcpSseStreamSpans(), recordClientAddress(), recordRequestId());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof SpanPolicy spanPolicy))
			return false;

		return Objects.equals(recordHttpRequestSpans(), spanPolicy.recordHttpRequestSpans())
				&& Objects.equals(recordStreamingResponseSpans(), spanPolicy.recordStreamingResponseSpans())
				&& Objects.equals(recordSseConnectionSpans(), spanPolicy.recordSseConnectionSpans())
				&& Objects.equals(recordSseWriteEvents(), spanPolicy.recordSseWriteEvents())
				&& Objects.equals(recordMcpRequestSpans(), spanPolicy.recordMcpRequestSpans())
				&& Objects.equals(recordMcpSessionEvents(), spanPolicy.recordMcpSessionEvents())
				&& Objects.equals(recordMcpSseStreamSpans(), spanPolicy.recordMcpSseStreamSpans())
				&& Objects.equals(recordClientAddress(), spanPolicy.recordClientAddress())
				&& Objects.equals(recordRequestId(), spanPolicy.recordRequestId());
	}

	@Override
	public int hashCode() {
		return Objects.hash(recordHttpRequestSpans(), recordStreamingResponseSpans(), recordSseConnectionSpans(),
				recordSseWriteEvents(), recordMcpRequestSpans(), recordMcpSessionEvents(),
				recordMcpSseStreamSpans(), recordClientAddress(), recordRequestId());
	}

	/**
	 * Mutable builder for {@link SpanPolicy} instances.
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private Boolean recordHttpRequestSpans;
		@NonNull
		private Boolean recordStreamingResponseSpans;
		@NonNull
		private Boolean recordSseConnectionSpans;
		@NonNull
		private Boolean recordSseWriteEvents;
		@NonNull
		private Boolean recordMcpRequestSpans;
		@NonNull
		private Boolean recordMcpSessionEvents;
		@NonNull
		private Boolean recordMcpSseStreamSpans;
		@NonNull
		private Boolean recordClientAddress;
		@NonNull
		private Boolean recordRequestId;

		private Builder() {
			this.recordHttpRequestSpans = true;
			this.recordStreamingResponseSpans = true;
			this.recordSseConnectionSpans = true;
			this.recordSseWriteEvents = false;
			this.recordMcpRequestSpans = true;
			this.recordMcpSessionEvents = true;
			this.recordMcpSseStreamSpans = true;
			this.recordClientAddress = false;
			this.recordRequestId = false;
		}

		@NonNull
		public Builder recordHttpRequestSpans(@NonNull Boolean recordHttpRequestSpans) {
			this.recordHttpRequestSpans = requireNonNull(recordHttpRequestSpans);
			return this;
		}

		@NonNull
		public Builder recordStreamingResponseSpans(@NonNull Boolean recordStreamingResponseSpans) {
			this.recordStreamingResponseSpans = requireNonNull(recordStreamingResponseSpans);
			return this;
		}

		@NonNull
		public Builder recordSseConnectionSpans(@NonNull Boolean recordSseConnectionSpans) {
			this.recordSseConnectionSpans = requireNonNull(recordSseConnectionSpans);
			return this;
		}

		@NonNull
		public Builder recordSseWriteEvents(@NonNull Boolean recordSseWriteEvents) {
			this.recordSseWriteEvents = requireNonNull(recordSseWriteEvents);
			return this;
		}

		@NonNull
		public Builder recordMcpRequestSpans(@NonNull Boolean recordMcpRequestSpans) {
			this.recordMcpRequestSpans = requireNonNull(recordMcpRequestSpans);
			return this;
		}

		@NonNull
		public Builder recordMcpSessionEvents(@NonNull Boolean recordMcpSessionEvents) {
			this.recordMcpSessionEvents = requireNonNull(recordMcpSessionEvents);
			return this;
		}

		@NonNull
		public Builder recordMcpSseStreamSpans(@NonNull Boolean recordMcpSseStreamSpans) {
			this.recordMcpSseStreamSpans = requireNonNull(recordMcpSseStreamSpans);
			return this;
		}

		@NonNull
		public Builder recordClientAddress(@NonNull Boolean recordClientAddress) {
			this.recordClientAddress = requireNonNull(recordClientAddress);
			return this;
		}

		@NonNull
		public Builder recordRequestId(@NonNull Boolean recordRequestId) {
			this.recordRequestId = requireNonNull(recordRequestId);
			return this;
		}

		@NonNull
		public SpanPolicy build() {
			return new SpanPolicy(this);
		}
	}
}
