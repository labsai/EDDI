package ai.labs.eddi.modules.nlp.extensions.normalizers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public interface INormalizer {
    String BLANK_CHAR = " ";

    String normalize(String input, String userLanguage);

    default List<String> toStringList(String input) {
        return new ArrayList<>(Arrays.asList(input.split(BLANK_CHAR)));
    }

    default String concatWords(List<String> inputParts) {
        StringBuilder newInput = new StringBuilder();
        inputParts.forEach(part -> newInput.append(part.trim()).append(BLANK_CHAR));
        return newInput.toString().trim();
    }
}
