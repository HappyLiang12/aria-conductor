package io.aria.conductor.execution.git;

/**
 * The one resolution point of the GitHub credential a git operation uses.
 *
 * <p>It is a source rather than a value on purpose (F8): the git pack credential
 * is stored at runtime through {@code POST /api/v1/packs/pack-git-0001/credentials},
 * so a value resolved once while the Spring context started stayed stale until a
 * restart. Every operation asks the source again, and a credential stored a
 * second ago is used by the next call.
 *
 * <p>{@link #token()} returns {@code null} (or blank) when no credential resolves
 * at all; an implementation may throw when a stored row cannot be read, and the
 * caller handles that as an unavailable credential rather than as a crash.
 */
@FunctionalInterface
public interface GitTokenSource {

    /** The current token, or {@code null}/blank when nothing resolves. */
    String token();
}
