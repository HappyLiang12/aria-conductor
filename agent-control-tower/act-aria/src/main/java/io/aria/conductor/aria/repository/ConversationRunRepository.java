package io.aria.conductor.aria.repository;

import io.aria.conductor.common.model.Run;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The conversation-clear write, kept separate from
 * {@link io.aria.conductor.agent.repository.RunRepository} so a conversation purge
 * owns exactly one statement over the run rows (the shape
 * {@code AuditEventBulkRepository} already established for bulk writes).
 *
 * <p>Deliberately a set-based {@code UPDATE}, never a load-and-save: the codebase's
 * pinned rule is that a whole-entity save flushes {@code status}/{@code completedAt}
 * too, so a run read before a concurrent verified cancel committed would be written
 * back stale. This statement touches the conversation link and nothing else.
 */
public interface ConversationRunRepository extends Repository<Run, UUID> {

    /**
     * Forgets which conversation the named runs belonged to. After this, the runs no
     * longer resolve through {@code findByConversationId...}, so the cleared
     * conversation is no longer the latest one and its timeline is empty, while the
     * run rows themselves survive for the runs surface, reports and usage totals.
     *
     * @return the number of run rows that carried one of the ids and a conversation
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Run r set r.conversationId = null where r.id in :ids")
    int detachFromConversation(@Param("ids") List<UUID> ids);
}
