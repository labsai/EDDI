package ai.labs.eddi.modules.nlp.extensions.corrections.providers;

import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.MergedTermsCorrection;
import io.quarkus.runtime.Startup;

import javax.enterprise.context.ApplicationScoped;

/**
 * @author ginccc
 */
@Startup
@ApplicationScoped
public class MergedTermsCorrectionProvider implements ICorrectionProvider {
    public static final String ID = "ai.labs.parser.corrections.mergedTerms";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Merged Terms Correction";
    }

    @Override
    public ICorrection provide() {
        return new MergedTermsCorrection();
    }
}
