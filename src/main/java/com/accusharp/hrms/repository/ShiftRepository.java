package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Shift;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findByShiftCode(String shiftCode);

    boolean existsByShiftCode(String shiftCode);

    List<Shift> findByCompanyIdOrCompanyIsNull(Long companyId);

    Optional<Shift> findByShiftCodeAndCompanyId(String shiftCode, Long companyId);

    Optional<Shift> findByShiftCodeAndCompanyIsNull(String shiftCode);

    boolean existsByShiftCodeAndCompanyId(String shiftCode, Long companyId);

    boolean existsByShiftCodeAndCompanyIsNull(String shiftCode);
}
