package com.accusharp.hrms;

import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for the double-deduction race the audit flagged in {@code
 * LeaveBalanceService#consume}: two concurrent leave approvals reading the
 * same balance and both writing back a deduction. {@link
 * LeaveBalance#version} (a JPA {@code @Version}) is what closes that window -
 * this loads two independent copies of the same row (as two truly concurrent
 * requests would each hold their own), saves the first, then proves the
 * second - now stale - is rejected rather than silently overwriting it.
 */
@SpringBootTest
class LeaveBalanceConcurrencyTest {

    @Autowired private LeaveBalanceRepository leaveBalanceRepository;

    private Long balanceId;

    @BeforeEach
    void setUp() {
        leaveBalanceRepository.deleteAll();
        LeaveBalance saved = leaveBalanceRepository.save(LeaveBalance.builder()
                .userId("CONC001").leaveYear(2026).leaveType(LeaveType.CASUAL_LEAVE)
                .quota(new BigDecimal("12.0")).used(BigDecimal.ZERO.setScale(1))
                .build());
        balanceId = saved.getId();
    }

    @Test
    @DisplayName("a stale concurrent write to the same balance is rejected, not silently applied on top")
    void secondConcurrentWriterIsRejectedNotSilentlyOverwritten() {
        // Two independent reads, exactly as two concurrent HTTP requests would
        // each load their own copy before either commits.
        LeaveBalance firstReader = leaveBalanceRepository.findById(balanceId).orElseThrow();
        LeaveBalance secondReader = leaveBalanceRepository.findById(balanceId).orElseThrow();

        firstReader.setUsed(firstReader.getUsed().add(new BigDecimal("2.0")));
        leaveBalanceRepository.save(firstReader);
        leaveBalanceRepository.flush();

        secondReader.setUsed(secondReader.getUsed().add(new BigDecimal("2.0")));
        assertThatThrownBy(() -> {
            leaveBalanceRepository.save(secondReader);
            leaveBalanceRepository.flush();
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Exactly one deduction landed, not two - the bug this guards against
        // would have left this at 4.0.
        LeaveBalance stored = leaveBalanceRepository.findById(balanceId).orElseThrow();
        assertThat(stored.getUsed()).isEqualByComparingTo("2.0");
    }
}
