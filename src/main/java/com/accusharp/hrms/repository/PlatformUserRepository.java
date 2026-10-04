package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.PlatformUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, Long> {

    Optional<PlatformUser> findByUsername(String username);

    boolean existsByUsername(String username);

    /**
     * Whether an employee named this would answer a platform login. Compared the way
     * the database compares logins - without regard to case, and on MySQL's default
     * collation without regard to accents either - since login looks the name up there.
     */
    boolean existsByUsernameIgnoreCase(String username);
}
