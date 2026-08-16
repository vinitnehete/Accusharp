package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.EmployeeCreationResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Renders one bulk-import's newly-created employees as a downloadable
 * credentials sheet (userId, employeeCode, employeeName, temporaryPassword)
 * - the practical way for HR to hand ~50-500 temporary passwords to the
 * right people when there is no email/SMS delivery infrastructure to send
 * them automatically (see SECURITY.md).
 *
 * <p>Built only from the in-memory {@link EmployeeCreationResponse} list a
 * single bulk-import call already produced - never a second lookup, never
 * persisted - so it keeps the same one-time-return contract as the JSON
 * response ({@code EmployeeService#create}'s Javadoc: "returned exactly
 * once, never logged").
 */
public final class EmployeeCredentialsCsvWriter {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader("userId", "employeeCode", "employeeName", "temporaryPassword")
            .build();

    private EmployeeCredentialsCsvWriter() {
    }

    public static byte[] write(List<EmployeeCreationResponse> created) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, FORMAT)) {
            for (EmployeeCreationResponse response : created) {
                printer.printRecord(response.employee().userId(), response.employee().employeeCode(),
                        response.employee().employeeName(), response.temporaryPassword());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
