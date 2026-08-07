package com.accusharp.hrms.security;

import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.PlatformUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a login username against both principal realms: a company
 * {@link com.accusharp.hrms.entity.Employee} first (the common case), falling
 * back to a platform-level {@link com.accusharp.hrms.entity.PlatformUser}.
 * The two are disjoint username spaces in practice - employee usernames are
 * device/business ids like {@code EMP001}, platform usernames are chosen at
 * platform-account creation time.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final EmployeeRepository employeeRepository;
    private final PlatformUserRepository platformUserRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return employeeRepository.findByUserId(username)
                .map(UserPrincipal::fromEmployee)
                .map(UserDetails.class::cast)
                .or(() -> platformUserRepository.findByUsername(username)
                        .map(UserPrincipal::fromPlatformUser))
                .orElseThrow(() -> new UsernameNotFoundException("No account for username " + username));
    }
}
