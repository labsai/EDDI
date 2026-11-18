/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.normalizers;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ContractedWordNormalizerTest {

    @Test
    public void normalize() {
        // setup
        final String testString = "don't doesn't didn't can't couldn't won't shouldn't it's";
        final String expected = "do not does not did not cannot could not will not should not it is";
        ContractedWordNormalizer normalizer = new ContractedWordNormalizer();

        // test
        String actual = normalizer.normalize(testString, "en");

        // assert
        Assertions.assertEquals(expected, actual);
    }

    @Test
    public void typographicApostropheAndFurtherContractions() {
        ContractedWordNormalizer normalizer = new ContractedWordNormalizer();

        // U+2019, as phone keyboards type the apostrophe
        String typographic = String.valueOf((char) 0x2019);

        Assertions.assertEquals("do not is not", normalizer.normalize("don" + typographic + "t isn't", "en"));
        Assertions.assertEquals("i will let us go", normalizer.normalize("I'll let" + typographic + "s go", "en"));
        Assertions.assertEquals("l" + typographic + "homme", normalizer.normalize("l" + typographic + "homme", "fr"),
                "tokens that are no contraction stay as typed");
    }
}
