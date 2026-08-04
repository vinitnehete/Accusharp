package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Shift;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findByShiftCode(String shiftCode);

    boolean existsByShiftCode(String shiftCode);
}
