package ai.labs.parser.extensions.normalizers;

import ai.labs.utilities.CharacterUtilities;
import org.apache.commons.lang3.StringUtils;

public class ConvertSpecialCharacterNormalizer implements INormalizer {
    @Override
    public String normalize(String input, String userLanguage) {
        return StringUtils.stripAccents(CharacterUtilities.convertSpecialCharacter(input));
    }
}
