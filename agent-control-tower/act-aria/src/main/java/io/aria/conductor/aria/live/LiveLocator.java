package io.aria.conductor.aria.live;

import java.util.Optional;

/**
 * The backup locator seam (spec D6): one stateless question about one frame.
 *
 * <p>It is the fallback for a live turn that carried an image and a look-intent
 * transcript but produced no {@code highlight_target} render: the socket asks for a box
 * directly instead of leaving the phone with nothing to draw. Implementations must never
 * throw, must never block the bridge's reader thread beyond their own budget, and must
 * never log the question, the image or the answer — a failure is silent by contract.
 */
public interface LiveLocator {

    /** No locator at all (tests, a deployment without the direct path). */
    LiveLocator NONE = (transcript, jpeg) -> Optional.empty();

    /**
     * Locates the object the wearer asked about.
     *
     * @param transcript the turn's final input transcript (the look-intent question)
     * @param jpeg       the turn's frame, exactly as the phone sent it
     * @return the render to draw, or empty when the locator is off, has no key, or failed
     */
    Optional<Render> locate(String transcript, byte[] jpeg);

    /**
     * A placeable answer on the phone's render contract: {@code bbox} is normalized
     * {@code [x,y,w,h]} in {@code [0,1]}, and {@code label}/{@code text} are the wearer's
     * own words — the direct answer carries no object name of its own.
     */
    record Render(String label, String text, double[] bbox) {

        public Render {
            bbox = bbox == null ? new double[0] : bbox.clone();
        }

        @Override
        public double[] bbox() {
            return bbox.clone();
        }
    }
}
