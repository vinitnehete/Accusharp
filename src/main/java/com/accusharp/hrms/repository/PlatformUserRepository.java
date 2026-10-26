package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.PlatformUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, Long> {

    @Query("select u from PlatformUser u where lower(u.username) = lower(:username)")
    Optional<PlatformUser> findByUsername(@Param("username") String username);

    boolean existsByUsername(String username);

    /**
     * Whether an employee named this would answer a platform login. This is explicit
     * because PostgreSQL text equality is case-sensitive by default.
     */
    boolean existsByUsernameIgnoreCase(String username);
}
