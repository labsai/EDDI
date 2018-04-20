package ai.labs.parser.extensions.normalizers.providers;

import ai.labs.parser.extensions.normalizers.INormalizer;
import ai.labs.parser.extensions.normalizers.RemoveUndefinedCharacterNormalizer;

public class RemoveUndefinedCharacterNormalizerProvider implements INormalizerProvider {
    public static final String ID = "ai.labs.parser.normalizers.allowedCharacter";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Remove Undefined Character Normalizer";
    }

    @Override
    public INormalizer provide() {
        return new RemoveUndefinedCharacterNormalizer();
    }
}
