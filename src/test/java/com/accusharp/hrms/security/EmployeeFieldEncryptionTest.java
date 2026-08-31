package com.accusharp.hrms.security;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the sensitive employee identifiers are ciphertext in the database,
 * not merely that the converter runs. Asserting through the repository alone
 * would pass even if the converter were a no-op, because it would encrypt and
 * decrypt symmetrically - so every assertion here that matters reads the raw
 * column with JDBC, underneath JPA.
 */
@SpringBootTest
class EmployeeFieldEncryptionTest {

    private static final String USER_ID = "ENC001";
    private static final String ACCOUNT_NO = "50100123456789";
    private static final String UAN = "101234567890";
    private static final String ESIC = "3100123456";
    private static final String IFSC = "HDFC0001234";

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EncryptedStringConverter converter;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();
        employeeRepository.save(Employee.builder()
                .userId(USER_ID).employeeCode("EMP-ENC-001").employeeName("Encryption Test User")
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(Role.EMPLOYEE)
                .joiningDate(LocalDate.of(2023, 1, 1))
                .grossSalary(new BigDecimal("30000")).pfBasic(new BigDecimal("15000"))
                .medicalAllowance(BigDecimal.ZERO).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .bankAccountNo(ACCOUNT_NO).bankIfscNo(IFSC).uanNo(UAN).esicIpNo(ESIC)
                .build());
    }

    @Test
    @DisplayName("sensitive identifiers are unreadable in the database")
    void sensitiveColumnsAreCiphertextAtRest() {
        String storedAccount = rawColumn("bank_account_no");
        String storedUan = rawColumn("uan_no");
        String storedEsic = rawColumn("esic_ip_no");
        String storedIfsc = rawColumn("bank_ifsc_no");

        // The plaintext must not appear anywhere in the stored value - this is
        // the assertion that would fail if the converter were removed.
        assertThat(storedAccount).doesNotContain(ACCOUNT_NO).startsWith("enc1:");
        assertThat(storedUan).doesNotContain(UAN).startsWith("enc1:");
        assertThat(storedEsic).doesNotContain(ESIC).startsWith("enc1:");
        assertThat(storedIfsc).doesNotContain(IFSC).startsWith("enc1:");
    }

    @Test
    @DisplayName("the application still reads them as plaintext")
    void valuesRoundTripThroughJpa() {
        Employee loaded = employeeRepository.findByUserId(USER_ID).orElseThrow();
        assertThat(loaded.getBankAccountNo()).isEqualTo(ACCOUNT_NO);
        assertThat(loaded.getUanNo()).isEqualTo(UAN);
        assertThat(loaded.getEsicIpNo()).isEqualTo(ESIC);
        assertThat(loaded.getBankIfscNo()).isEqualTo(IFSC);
    }

    @Test
    @DisplayName("the same value encrypts differently every time, so ciphertext cannot be compared")
    void encryptionIsNonDeterministic() {
        String first = converter.convertToDatabaseColumn(ACCOUNT_NO);
        String second = converter.convertToDatabaseColumn(ACCOUNT_NO);

        // A deterministic scheme would let anyone with read access spot which
        // employees share a bank account, or confirm a guessed number.
        assertThat(first).isNotEqualTo(second);
        assertThat(converter.convertToEntityAttribute(first)).isEqualTo(ACCOUNT_NO);
        assertThat(converter.convertToEntityAttribute(second)).isEqualTo(ACCOUNT_NO);
    }

    @Test
    @DisplayName("rows written before encryption existed are still readable")
    void legacyPlaintextStillReads() {
        // Simulates an existing production database: write plaintext straight
        // past JPA, exactly as the previous version of the app did.
        jdbcTemplate.update("UPDATE employee SET bank_account_no = ? WHERE user_id = ?",
                "LEGACY-PLAINTEXT-123", USER_ID);

        Employee loaded = employeeRepository.findByUserId(USER_ID).orElseThrow();
        assertThat(loaded.getBankAccountNo()).isEqualTo("LEGACY-PLAINTEXT-123");
    }

    @Test
    @DisplayName("a tampered ciphertext is rejected rather than silently returning wrong data")
    void tamperedCiphertextIsRejected() {
        String valid = converter.convertToDatabaseColumn(ACCOUNT_NO);
        // Flip a character in the base64 payload.
        char[] chars = valid.toCharArray();
        int last = chars.length - 1;
        chars[last] = chars[last] == 'A' ? 'B' : 'A';

        // GCM authenticates; a wrong bank account number reaching a payslip
        // would be far worse than an error.
        assertThatThrownBy(() -> converter.convertToEntityAttribute(new String(chars)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt");
    }

    private String rawColumn(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM employee WHERE user_id = ?", String.class, USER_ID);
    }
}
