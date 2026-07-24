package io.aria.conductor.test;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * Base class for repository slice tests ({@code @DataJpaTest} + embedded H2).
 * <p>
 * Bootstraps via the shared {@link JpaSliceConfig} so modules without their own
 * {@code @SpringBootApplication} can still run JPA slices. Each test runs in a
 * transaction that rolls back automatically (standard {@code @DataJpaTest}
 * semantics).
 * <p>
 * Use {@link TestDataBuilder} for fixtures and {@link #flushAndClear()} before
 * asserting query results to force real SQL round-trips.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceConfig.class)
public abstract class DataJpaTestBase {

    @PersistenceContext
    protected EntityManager entityManager;

    /**
     * Flush pending writes and clear the persistence context so subsequent
     * reads hit the database instead of the identity map.
     */
    protected void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
