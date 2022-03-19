package ai.labs.eddi.modules.nlp.extensions.normalizers;


import ai.labs.eddi.utils.CharacterUtilities;

public class RemoveUndefinedCharacterNormalizer implements INormalizer {
    private static final String DEFAULT_ALLOWED_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz 1234567890!?:;.,";

    @Override
    public String normalize(String input, String userLanguage) {
        return CharacterUtilities.deleteUndefinedChars(input, DEFAULT_ALLOWED_CHARS);
    }
}
