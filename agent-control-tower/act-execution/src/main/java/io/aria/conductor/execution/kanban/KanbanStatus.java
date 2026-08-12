package io.aria.conductor.execution.kanban;

/**
 * Lifecycle status for a kanban item.
 *
 * <p>Allowed transitions (enforced by {@link KanbanService}):
 * <ul>
 *   <li>TODO        → IN_PROGRESS, BLOCKED, CANCELLED</li>
 *   <li>IN_PROGRESS → DONE, BLOCKED, CANCELLED</li>
 *   <li>BLOCKED     → TODO, IN_PROGRESS, CANCELLED</li>
 *   <li>DONE        → (terminal)</li>
 *   <li>CANCELLED   → (terminal)</li>
 * </ul>
 */
public enum KanbanStatus {
    TODO,
    IN_PROGRESS,
    DONE,
    BLOCKED,
    CANCELLED,
    REVIEW
}
