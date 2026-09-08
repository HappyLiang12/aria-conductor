package io.aria.conductor.execution.kanban;

/**
 * Lifecycle status for a kanban item.
 *
 * <p>Allowed transitions (enforced by {@link KanbanService}):
 * <ul>
 *   <li>BACKLOG     → TODO, CANCELLED</li>
 *   <li>TODO        → IN_PROGRESS, BACKLOG, CANCELLED</li>
 *   <li>IN_PROGRESS → TODO, BACKLOG, REVIEW, DONE, CANCELLED</li>
 *   <li>REVIEW      → IN_PROGRESS, TODO, DONE, CANCELLED</li>
 *   <li>DONE        → (terminal)</li>
 *   <li>CANCELLED   → (terminal)</li>
 * </ul>
 *
 * <p>BLOCKED is retired: it still exists so persisted rows deserialze, has no
 * outgoing transitions, and V52 migrated legacy rows to REVIEW.
 */
public enum KanbanStatus {
    BACKLOG,
    TODO,
    IN_PROGRESS,
    DONE,
    CANCELLED,
    REVIEW,
    BLOCKED
}
