package ai.labs.parser.extensions.normalizers.providers;

import ai.labs.parser.extensions.normalizers.ContractedWordNormalizer;
import ai.labs.parser.extensions.normalizers.INormalizer;

public class ContractedWordNormalizerProvider implements INormalizerProvider {
    public static final String ID = "ai.labs.parser.normalizers.contractedWords";

    @Override
    public INormalizer provide() {
        return new ContractedWordNormalizer();
    }
}
