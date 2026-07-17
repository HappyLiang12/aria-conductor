package io.aria.conductor.test;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base class for Spring Boot integration tests.
 * <p>
 * Subclasses inherit:
 * <ul>
 *   <li>{@code @SpringBootTest} (full application context)</li>
 *   <li>{@code @ActiveProfiles("test")} for the isolated H2 test profile</li>
 *   <li>{@code @Transactional} so each test rolls back automatically</li>
 *   <li>An {@code EntityManager.clear()} hook executed before each test</li>
 * </ul>
 * Tests requiring a different profile may override the annotation.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public abstract class IntegrationTestBase {

    @PersistenceContext
    protected EntityManager entityManager;

    /**
     * Reset transactional state before each test. The {@code @Transactional}
     * rollback handles row-level cleanup; this hook ensures the persistence
     * context is also cleared so identity-map carryover never leaks.
     */
    @BeforeEach
    void resetState() {
        if (entityManager != null) {
            entityManager.clear();
        }
    }
}
