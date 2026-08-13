package com.accusharp.hrms.security;

import com.accusharp.hrms.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

/**
 * Turns "authenticated but not permitted" into the same {@link ApiError}
 * shape every other failure uses - for an {@code AccessDeniedException}
 * thrown at the security filter-chain level itself. {@code @PreAuthorize}
 * denials on controller methods do <b>not</b> reach this class: they are
 * thrown during the controller invocation, inside {@code DispatcherServlet},
 * so {@code GlobalExceptionHandler}'s {@code @ExceptionHandler} always
 * catches them first (verified in {@code SecurityConfig}'s Javadoc). This
 * handler exists for the other kind of denial - a rule declared directly in
 * {@code authorizeHttpRequests(...)} (e.g. {@code .hasRole(...)} on a
 * request matcher) - which this app does not currently use, but which would
 * bypass the controller entirely and land here instead.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ApiError body = new ApiError(Instant.now(), HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(), "You do not have permission to perform this action",
                request.getRequestURI());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
