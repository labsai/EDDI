package ai.labs.eddi.modules.nlp.extensions.corrections.similarities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DamerauLevenshteinDistance}. Pure algorithm — zero
 * dependencies.
 */
class DamerauLevenshteinDistanceTest {

    private DamerauLevenshteinDistance calculator;

    @BeforeEach
    void setUp() {
        calculator = new DamerauLevenshteinDistance();
    }

    @Nested
    @DisplayName("calculate")
    class Calculate {

        @Test
        @DisplayName("identical strings should have distance 0")
        void identical() {
            assertEquals(0, calculator.calculate("hello", "hello"));
        }

        @Test
        @DisplayName("single substitution gives distance 1")
        void singleSubstitution() {
            assertEquals(1, calculator.calculate("cat", "car"));
        }

        @Test
        @DisplayName("single insertion gives distance 1")
        void singleInsertion() {
            assertEquals(1, calculator.calculate("cat", "cats"));
        }

        @Test
        @DisplayName("single deletion gives distance 1")
        void singleDeletion() {
            assertEquals(1, calculator.calculate("cats", "cat"));
        }

        @Test
        @DisplayName("single transposition gives distance 1")
        void singleTransposition() {
            assertEquals(1, calculator.calculate("test", "tset"));
        }

        @Test
        @DisplayName("empty source returns target length")
        void emptySource() {
            assertEquals(5, calculator.calculate("", "hello"));
        }

        @Test
        @DisplayName("empty target returns source length")
        void emptyTarget() {
            assertEquals(5, calculator.calculate("hello", ""));
        }

        @Test
        @DisplayName("completely different strings")
        void completelyDifferent() {
            assertEquals(3, calculator.calculate("abc", "xyz"));
        }

        @Test
        @DisplayName("multiple edits")
        void multipleEdits() {
            // kitten → sitting: s/k, i/e, insert g = 3
            assertEquals(3, calculator.calculate("kitten", "sitting"));
        }

        @Test
        @DisplayName("single character strings")
        void singleChar() {
            assertEquals(0, calculator.calculate("a", "a"));
            assertEquals(1, calculator.calculate("a", "b"));
        }
    }

    @Nested
    @DisplayName("calculateAndReturnFullResult")
    class FullResult {

        @Test
        @DisplayName("should return correct distance from result object")
        void distanceFromResult() {
            var result = calculator.calculateAndReturnFullResult("hello", "hallo");
            assertEquals(1, result.getDistance());
        }

        @Test
        @DisplayName("should return distance matrix of correct dimensions")
        void matrixDimensions() {
            var result = calculator.calculateAndReturnFullResult("abc", "xyz");
            int[][] matrix = result.getDistanceMatrix();
            // source.length()+1 rows, target.length()+1 cols
            assertEquals(4, matrix.length);
            assertEquals(4, matrix[0].length);
        }

        @Test
        @DisplayName("first row should be 0,1,2,...target.length")
        void firstRow() {
            var result = calculator.calculateAndReturnFullResult("abc", "xyz");
            int[][] matrix = result.getDistanceMatrix();
            for (int j = 0; j <= 3; j++) {
                assertEquals(j, matrix[0][j]);
            }
        }

        @Test
        @DisplayName("first column should be 0,1,2,...source.length")
        void firstColumn() {
            var result = calculator.calculateAndReturnFullResult("abc", "xyz");
            int[][] matrix = result.getDistanceMatrix();
            for (int i = 0; i <= 3; i++) {
                assertEquals(i, matrix[i][0]);
            }
        }

        @Test
        @DisplayName("toString should contain distance and matrix")
        void toStringFormat() {
            var result = calculator.calculateAndReturnFullResult("ab", "ba");
            String str = result.toString();
            assertTrue(str.contains("Distance:"));
            assertTrue(str.contains("Matrix:"));
        }
    }
}
