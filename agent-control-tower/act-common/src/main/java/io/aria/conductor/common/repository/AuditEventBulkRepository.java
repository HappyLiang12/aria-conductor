package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;

/**
 * Set-based audit deletion for the bounded retirement path (spec 7.2).
 *
 * <p>Retirement may delete audit history only for records it hard-deletes, and
 * only by the exact rows frozen in the retirement manifest: the caller supplies
 * one of the frozen types ({@code Agent}, {@code Run}, {@code Action}), the
 * frozen resource IDs of that type and the frozen audit row IDs, all three
 * digest-verified before the call. There is deliberately no
 * delete-by-conversation or delete-by-free-text method here -- an audit row that
 * merely mentions a deleted record in its details is not the deleted record's
 * row and must survive.
 *
 * <p>Requiring the frozen row ids keeps the statement strictly narrower than the
 * {@code (resourceType, resourceId)} pair: a row written for a target resource
 * after the manifest was frozen (for example in the window between the
 * execute-time re-derivation and the delete) is not in the frozen set and
 * survives.
 *
 * <p>Single-statement bulk delete, no entity load; the caller owns the
 * transaction and must re-verify the frozen ID sets before calling.
 */
@Repository
public interface AuditEventBulkRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * Deletes the frozen audit rows of one verified resource type: the row id
     * must be in the frozen row set <em>and</em> its resource id in the frozen
     * target set.
     *
     * @return the number of deleted rows
     */
    @Modifying
    @Query("DELETE FROM AuditEvent a WHERE a.resourceType = :resourceType"
            + " AND a.resourceId IN :resourceIds AND a.id IN :auditEventIds")
    int deleteByResourceTypeAndResourceIdIn(@Param("resourceType") String resourceType,
                                            @Param("resourceIds") Collection<String> resourceIds,
                                            @Param("auditEventIds") Collection<Long> auditEventIds);
}
