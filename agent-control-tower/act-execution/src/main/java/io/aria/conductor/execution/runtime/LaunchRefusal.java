package io.aria.conductor.execution.runtime;

/**
 * The one question a launch-path wait may ask the placement that owns a launch:
 * <em>has the launcher proved that it refused this launch before it spawned
 * anything?</em> Returning the launcher's own reason lets a wait that is about to
 * fail on its own timeout end on the reason instead, with the sentence the image
 * printed, which is the only version an operator can act on.
 *
 * <p>{@code null} means <em>nothing is proven</em>, and that is the answer for the
 * overwhelming majority of launches: a healthy core, a launcher that stayed silent,
 * a launcher whose output this plane could not read, a launcher that reported a
 * spawn and only then faulted, and a placement with no image launcher at all (HOST).
 * Every one of those keeps the behaviour the wait had before this seam existed,
 * because an unproven verdict licenses neither an early failure nor a destroy — the
 * fail-closed rule {@code LaunchObservation} owns and this interface only carries.
 *
 * <p>The source is consulted on a poll the wait already performs, never on a poll
 * loop of its own, and it must be cheap: the successful path may not pay anything
 * for the refusal the refused path needs.
 */
@FunctionalInterface
public interface LaunchRefusal {

    /** Nothing is ever proven refused: every wait behaves exactly as it did before. */
    LaunchRefusal NONE = () -> null;

    /**
     * The launcher's refusal reason, in the image's own words, once the launch is
     * proven to have been refused before spawning; {@code null} while nothing is
     * proven.
     */
    String reason();
}
