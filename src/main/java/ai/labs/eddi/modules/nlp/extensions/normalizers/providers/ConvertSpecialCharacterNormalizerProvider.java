package ai.labs.eddi.modules.nlp.extensions.normalizers.providers;


import ai.labs.eddi.modules.nlp.extensions.normalizers.ConvertSpecialCharacterNormalizer;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
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
    public INormalizer provide(Map<String, Object> config) {
        return new ConvertSpecialCharacterNormalizer();
    }
}
