package ai.labs.eddi.modules.nlp.extensions.normalizers.providers;


import ai.labs.eddi.modules.nlp.extensions.normalizers.ContractedWordNormalizer;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class ContractedWordNormalizerProvider implements INormalizerProvider {
    public static final String ID = "ai.labs.parser.normalizers.contractedWords";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Contracted Word Normalizer";
    }

    @Override
    public INormalizer provide(Map<String, Object> config) {
        return new ContractedWordNormalizer();
    }
}
