package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.SalaryRuleRequest;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.repository.SalaryRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one configuration row every calculation reads. Seeded with sane defaults
 * the first time it is requested.
 */
@Service
@RequiredArgsConstructor
public class SalaryRuleService {

    private final SalaryRuleRepository salaryRuleRepository;

    @Transactional
    public SalaryRule getActiveRule() {
        return salaryRuleRepository.findById(SalaryRule.CONFIG_ID)
                .orElseGet(() -> salaryRuleRepository.save(SalaryRule.defaultRule()));
    }

    @Transactional
    public SalaryRule updateRule(SalaryRuleRequest request) {
        SalaryRule rule = getActiveRule();
        rule.setBasicDaPercent(request.getBasicDaPercent());
        rule.setHraPercent(request.getHraPercent());
        rule.setConveyancePercent(request.getConveyancePercent());
        rule.setEducationPercent(request.getEducationPercent());
        rule.setPfPercent(request.getPfPercent());
        rule.setEsicPercent(request.getEsicPercent());
        rule.setEsicWageCeiling(request.getEsicWageCeiling());
        rule.setPtUpperThreshold(request.getPtUpperThreshold());
        rule.setPtUpperAmount(request.getPtUpperAmount());
        rule.setPtLowerThreshold(request.getPtLowerThreshold());
        rule.setPtLowerAmount(request.getPtLowerAmount());
        rule.setDayWiseDaysInMonth(request.getDayWiseDaysInMonth());
        rule.setStandardHoursPerDay(request.getStandardHoursPerDay());
        rule.setOvertimeRateMultiplier(request.getOvertimeRateMultiplier());
        return salaryRuleRepository.save(rule);
    }
}
