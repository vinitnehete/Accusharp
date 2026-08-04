package com.accusharp.hrms.exception;

import java.time.Instant;

/** The single error shape every failing endpoint returns. */
public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
