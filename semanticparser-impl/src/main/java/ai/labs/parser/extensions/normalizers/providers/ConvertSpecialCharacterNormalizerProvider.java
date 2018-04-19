package ai.labs.parser.extensions.normalizers.providers;

import ai.labs.parser.extensions.normalizers.ConvertSpecialCharacterNormalizer;
import ai.labs.parser.extensions.normalizers.INormalizer;

public class ConvertSpecialCharacterNormalizerProvider implements INormalizerProvider {
    public static final String ID = "ai.labs.parser.normalizers.specialCharacter";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Convert Special Character Normalizer";
    }

    @Override
    public INormalizer provide() {
        return new ConvertSpecialCharacterNormalizer();
    }
}
