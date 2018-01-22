package ai.labs.parser.extensions.normalizers.providers;

import ai.labs.parser.extensions.normalizers.INormalizer;
import ai.labs.parser.extensions.normalizers.RemoveUndefinedCharacterNormalizer;

public class RemoveUndefinedCharacterNormalizerProvider implements INormalizerProvider {
    public static final String ID = "ai.labs.parser.normalizers.allowedCharacter";

    @Override
    public INormalizer provide() {
        return new RemoveUndefinedCharacterNormalizer();
    }
}
