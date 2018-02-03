package ai.labs.parser.extensions.normalizers.providers;

import ai.labs.parser.extensions.normalizers.ConvertSpecialCharacterNormalizer;
import ai.labs.parser.extensions.normalizers.INormalizer;

public class ConvertSpecialCharacterNormalizerProvider implements INormalizerProvider {
    public static final String ID = "ai.labs.parser.normalizers.specialCharacter";

    @Override
    public INormalizer provide() {
        return new ConvertSpecialCharacterNormalizer();
    }
}
