package edu.learnsphere.core.service;

import edu.learnsphere.core.spi.ModelChoice;
import java.util.Objects;

/**
 * A chat model the Settings screen can offer.
 *
 * @param available   its files are present and this computer can run it
 * @param recommended what automatic choice picks on this computer
 * @param selected    the current setting
 */
public record ModelOption(ModelChoice choice, boolean available, boolean recommended, boolean selected) {

    public ModelOption {
        Objects.requireNonNull(choice, "choice");
    }
}
