package com.accusharp.hrms.service.shift;

import com.accusharp.hrms.dto.ShiftRequest;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Shift master. The four standard shifts are seeded at startup; admins add
 * custom ones through this service.
 */
@Service
@RequiredArgsConstructor
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final ShiftScheduleRepository shiftScheduleRepository;

    @Transactional
    public Shift create(ShiftRequest request) {
        if (shiftRepository.existsByShiftCode(request.getShiftCode())) {
            throw new ConflictException("Shift already exists with code " + request.getShiftCode());
        }
        return shiftRepository.save(apply(new Shift(), request));
    }

    @Transactional
    public Shift update(Long id, ShiftRequest request) {
        Shift shift = getById(id);
        shiftRepository.findByShiftCode(request.getShiftCode())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another shift already uses code " + request.getShiftCode());
                });
        return shiftRepository.save(apply(shift, request));
    }

    @Transactional(readOnly = true)
    public Shift getById(Long id) {
        return shiftRepository.findById(id).orElseThrow(() -> NotFoundException.of("Shift", id));
    }

    @Transactional(readOnly = true)
    public Shift getByCode(String shiftCode) {
        return shiftRepository.findByShiftCode(shiftCode)
                .orElseThrow(() -> NotFoundException.of("Shift", "code " + shiftCode));
    }

    @Transactional(readOnly = true)
    public List<Shift> getAll() {
        return shiftRepository.findAll();
    }

    /** Refused while any roster still points at the shift. */
    @Transactional
    public void delete(Long id) {
        Shift shift = getById(id);
        if (shiftScheduleRepository.countByShiftId(id) > 0) {
            throw new ConflictException("Shift is still used by existing schedules");
        }
        shiftRepository.delete(shift);
    }

    private Shift apply(Shift shift, ShiftRequest request) {
        shift.setShiftCode(request.getShiftCode());
        shift.setShiftName(request.getShiftName());
        shift.setStartTime(request.getStartTime());
        shift.setEndTime(request.getEndTime());
        shift.setWorkingHours(request.getWorkingHours());
        shift.setBreakMinutes(request.getBreakMinutes());
        shift.setGraceMinutes(request.getGraceMinutes());
        shift.setOvertimeWindowMinutes(request.getOvertimeWindowMinutes());
        return shift;
    }
}
