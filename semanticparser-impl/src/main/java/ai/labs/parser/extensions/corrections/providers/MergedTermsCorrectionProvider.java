package ai.labs.parser.extensions.corrections.providers;

import ai.labs.parser.extensions.corrections.ICorrection;
import ai.labs.parser.extensions.corrections.MergedTermsCorrection;

/**
 * @author ginccc
 */
public class MergedTermsCorrectionProvider implements ICorrectionProvider {
    public static final String ID = "ai.labs.parser.corrections.mergedTerms";

    @Override
    public ICorrection provide() {
        return new MergedTermsCorrection();
    }
}
