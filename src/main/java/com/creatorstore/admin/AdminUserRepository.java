package com.creatorstore.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByEmail(String email);

    /** Own transaction: called from a non-transactional login flow and must commit even when login then fails. */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE admin_users
               SET failed_login_count = CASE WHEN failed_login_count + 1 >= :maxAttempts THEN 0
                                             ELSE failed_login_count + 1 END,
                   locked_until       = CASE WHEN failed_login_count + 1 >= :maxAttempts THEN :lockUntil
                                             ELSE locked_until END,
                   updated_at         = now()
             WHERE id = :id
            """, nativeQuery = true)
    int recordFailedLogin(@Param("id") Long id,
                          @Param("maxAttempts") int maxAttempts,
                          @Param("lockUntil") Instant lockUntil);

    @Transactional
    @Modifying
    @Query(value = """
            UPDATE admin_users
               SET failed_login_count = 0,
                   locked_until       = NULL,
                   last_login_at      = :now,
                   updated_at         = now()
             WHERE id = :id
            """, nativeQuery = true)
    int recordSuccessfulLogin(@Param("id") Long id, @Param("now") Instant now);
}
