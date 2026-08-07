package com.accusharp.hrms.security;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.PlatformUser;
import com.accusharp.hrms.enums.PrincipalType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated identity for both principal types this application
 * supports: a company {@link Employee} or a platform-level
 * {@link PlatformUser}. {@code username} is {@code Employee.userId} or
 * {@code PlatformUser.username} depending on {@code type} - the two are
 * different login realms and are never confused because {@code type} always
 * travels with the identity, both in this object and in the JWT claims.
 */
public class UserPrincipal implements UserDetails {

    private final PrincipalType type;
    private final String username;
    private final String passwordHash;
    private final Long companyId;
    private final String role;
    private final boolean enabled;
    private final boolean accountNonLocked;

    private UserPrincipal(PrincipalType type, String username, String passwordHash, Long companyId,
                          String role, boolean enabled, boolean accountNonLocked) {
        this.type = type;
        this.username = username;
        this.passwordHash = passwordHash;
        this.companyId = companyId;
        this.role = role;
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
    }

    public static UserPrincipal fromEmployee(Employee employee) {
        return new UserPrincipal(
                PrincipalType.EMPLOYEE,
                employee.getUserId(),
                employee.getPasswordHash(),
                employee.getCompany() == null ? null : employee.getCompany().getId(),
                employee.getRole().name(),
                employee.isAccountEnabled(),
                !employee.isAccountLocked());
    }

    public static UserPrincipal fromPlatformUser(PlatformUser platformUser) {
        return new UserPrincipal(
                PrincipalType.PLATFORM,
                platformUser.getUsername(),
                platformUser.getPasswordHash(),
                null,
                platformUser.getRole().name(),
                platformUser.isEnabled(),
                !platformUser.isAccountLocked());
    }

    /** Rebuilds a principal from JWT claims alone - no database hit per request. */
    public static UserPrincipal fromClaims(PrincipalType type, String username, Long companyId, String role) {
        return new UserPrincipal(type, username, null, companyId, role, true, true);
    }

    public PrincipalType getType() {
        return type;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public String getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
