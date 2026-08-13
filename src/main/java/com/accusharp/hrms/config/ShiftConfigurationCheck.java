package com.accusharp.hrms.config;

import com.accusharp.hrms.dto.ShiftResponse;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Shouts about shift configuration that will quietly produce wrong attendance.
 *
 * <p>Every attendance figure is an interpretation of the shift master, so a bad
 * row there is worth more than a bad row anywhere else - and it is invisible:
 * the attendance still computes, it is just wrong. Two real incidents motivated
 * this, both found only by reading a month's output long after the fact.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class ShiftConfigurationCheck {

    private final ShiftRepository shiftRepository;

    @Bean
    ApplicationRunner warnAboutShiftConfiguration() {
        return args -> {
            List<Shift> shifts = shiftRepository.findAll();
            for (Shift shift : shifts) {
                for (String warning : ShiftResponse.warningsFor(shift)) {
                    log.warn("shift.config code={} - {}", shift.getShiftCode(), warning);
                }
            }
            shifts.stream()
                    .filter(Shift::crossesMidnight)
                    .forEach(shift -> log.info("shift.config code={} crosses midnight ({} -> {})",
                            shift.getShiftCode(), shift.getStartTime(), shift.getEndTime()));

            if (shifts.stream().noneMatch(Shift::crossesMidnight)) {
                log.info("shift.config no shift crosses midnight - if you roster night shifts, "
                        + "check their end time is not after their start time");
            }
        };
    }
}
