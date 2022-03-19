package ai.labs.eddi.modules.nlp.extensions.normalizers.providers;


import ai.labs.eddi.modules.nlp.extensions.normalizers.ContractedWordNormalizer;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;

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
    public INormalizer provide() {
        return new ContractedWordNormalizer();
    }
}
