package io.aria.conductor.memory.service;

import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Serialises the "one current ACTIVE value per (entity, fact key)" decision (spec §6.3) on
 * the entity's optimistic {@code @Version}.
 * <p>
 * All three paths that open a current value — an operator/run {@code addFact}, a
 * {@code supersedeFact} and the approval of a PENDING fact — first bump the entity row and
 * flush. Two racing writers read the same version; the loser's
 * {@code UPDATE ... WHERE version=?} matches no row, which surfaces here as a governed
 * conflict instead of a second current row. Without the shared claim an approval or a
 * supersede could interleave with an addFact on the same {@code (entity, fact key)} and
 * commit two rows with {@code valid_to=null}. A database partial unique index is not usable
 * because H2 and MariaDB differ on that construct, so the version check is the guard.
 * <p>
 * The claim is coarser than the fact key: it serialises every current-value write on the
 * entity, which is the price two concurrent addFacts already paid and what makes the
 * cross-path races safe.
 */
@Component
class MemoryCurrentValueSlot {

    private final MemoryEntityRepository entities;

    MemoryCurrentValueSlot(MemoryEntityRepository entities) {
        this.entities = entities;
    }

    /**
     * Claim the entity's current-value slot for one fact key in the caller's transaction.
     * Must run before the fact rows are written, so a loser writes nothing.
     */
    void claim(UUID entityId, String factKey, Instant now) {
        MemoryEntity entity = entities.findById(entityId)
                .orElseThrow(() -> new MemoryNotFoundException("memory entity " + entityId + " not found"));
        entity.setUpdatedAt(now);
        try {
            entities.save(entity);
            entities.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new MemoryConflictException("entity " + entityId
                    + " changed while a current value for '" + factKey
                    + "' was being claimed; another writer won the race — re-read before writing");
        }
    }
}
