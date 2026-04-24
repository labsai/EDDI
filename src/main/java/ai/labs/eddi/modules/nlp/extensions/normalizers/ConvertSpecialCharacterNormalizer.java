/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.normalizers;

import ai.labs.eddi.utils.CharacterUtilities;
import org.apache.commons.lang3.StringUtils;

public class ConvertSpecialCharacterNormalizer implements INormalizer {
    @Override
    public String normalize(String input, String userLanguage) {
        return StringUtils.stripAccents(CharacterUtilities.convertSpecialCharacter(input));
    }
}
