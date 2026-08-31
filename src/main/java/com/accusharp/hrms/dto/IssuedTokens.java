package com.accusharp.hrms.dto;

/**
 * What a successful authentication produces internally: the body the client
 * receives, plus the raw refresh token, which the controller puts into an
 * httpOnly cookie.
 *
 * <p>The split exists so the refresh token cannot reach a JSON response by
 * accident. {@link TokenResponse} is the only thing serialized to the client
 * and it has no field to hold a refresh token, so there is no code path -
 * present or future - that can leak one into a response body without a
 * deliberate change to this type. That is a stronger guarantee than
 * remembering not to populate a field.
 */
public record IssuedTokens(TokenResponse response, String refreshToken) {
}
