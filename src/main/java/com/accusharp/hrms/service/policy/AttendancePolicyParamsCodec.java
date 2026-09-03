package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.dto.policy.AttendancePolicyParams;
import com.accusharp.hrms.dto.policy.AttendancePolicyParams.Params;
import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.exception.BusinessRuleException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Binds a rule's {@code params} JSON to its {@link RuleType}'s record and
 * bean-validates it. The only way params ever become an object.
 *
 * <p>Two things this class refuses to do, both deliberate:
 *
 * <p><b>It never returns a map.</b> Reading params loosely at evaluation time -
 * {@code params.get("graceMinutes")} with a default when absent - is how a
 * typo'd key becomes a silently different salary. Binding to a record means a
 * missing or misspelt field is an error at the moment somebody saves the rule,
 * not a quiet zero four weeks later on a payslip.
 *
 * <p><b>It never swallows a bad blob.</b> A blob that will not bind throws,
 * including on the evaluation path. Skipping the rule instead would change pay
 * by omission - the day would silently revert to unpolicied behaviour with
 * nothing in the trace to say a rule had been dropped, which is the one failure
 * mode that must never be quiet. Write-time validation makes this unreachable
 * through the API; it exists for hand-edited rows and rolled-back deploys.
 *
 * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} is left on for the same reason. A
 * client that sends {@code graceMins} instead of {@code graceMinutes} gets a
 * 400 naming the field rather than a rule that quietly runs on a zero grace and
 * marks the whole company late.
 */
@Component
@RequiredArgsConstructor
public class AttendancePolicyParamsCodec {

    /**
     * A private mapper, not the application's shared one. The shared mapper's
     * configuration is tuned for the API surface and may be relaxed later;
     * this one must stay strict, because what it parses decides pay.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Validator validator;

    /** Binds and validates - the write path, where the message reaches a human. */
    public Params parse(RuleType ruleType, String json) {
        Params params = bind(ruleType, json);
        validate(ruleType, params);
        return params;
    }

    /**
     * Binds and validates a stored rule, typed to the record the caller expects.
     * Throws rather than returning empty when the row is unusable - see the
     * class Javadoc for why a dropped rule must never be silent.
     */
    public <T extends Params> T parse(AttendancePolicyRule rule, Class<T> expected) {
        Params params = bind(rule.getRuleType(), rule.getParams());
        if (!expected.isInstance(params)) {
            throw new BusinessRuleException("attendance policy rule " + rule.getId() + " ("
                    + rule.ruleLabel() + ") holds " + params.getClass().getSimpleName()
                    + " params but " + expected.getSimpleName() + " was expected");
        }
        return expected.cast(params);
    }

    /** Serialises a validated record back to the column. */
    public String write(Params params) {
        return MAPPER.writeValueAsString(params);
    }

    private Params bind(RuleType ruleType, String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessRuleException("params are required for a " + ruleType + " rule");
        }
        try {
            return MAPPER.readValue(json, AttendancePolicyParams.typeOf(ruleType));
        } catch (RuntimeException ex) {
            throw new BusinessRuleException("params do not match " + ruleType + ": " + rootCause(ex));
        }
    }

    private void validate(RuleType ruleType, Params params) {
        Set<ConstraintViolation<Params>> violations = validator.validate(params);
        if (violations.isEmpty()) {
            return;
        }
        String detail = violations.stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .sorted()
                .collect(Collectors.joining(", "));
        throw new BusinessRuleException("invalid " + ruleType + " params: " + detail);
    }

    /**
     * Jackson nests the useful part several causes deep; the outer message is
     * the class it was trying to build, which tells a client nothing about
     * which field they got wrong.
     */
    private String rootCause(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null) {
            return cause.getClass().getSimpleName();
        }
        int detail = message.indexOf(" (through reference chain");
        return detail > 0 ? message.substring(0, detail) : message;
    }
}
