package ai.labs.eddi.modules.nlp.matches;

import ai.labs.eddi.modules.nlp.internal.matches.Permutation;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Iterator;

/**
 * @author ginccc
 */
@Slf4j
public class PermutationTest {

    @Test
    public void testPermutation() {
        //setup
        Permutation permutation = new Permutation(new Integer[]{1, 2, 3});
        Iterator<Integer[]> permutationIterator = permutation.iterator();

        //assert
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 2, 3}, permutationIterator.next());
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 3, 2}, permutationIterator.next());
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 1, 3}, permutationIterator.next());
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 3, 1}, permutationIterator.next());
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{3, 1, 2}, permutationIterator.next());
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{3, 2, 1}, permutationIterator.next());
        Assertions.assertFalse(permutationIterator.hasNext());
    }

    @Test
    public void testPermutation1() {
        //setup
        Permutation permutation = new Permutation(new Integer[]{1, 0, 0});
        Iterator<Integer[]> permutationIterator = permutation.iterator();

        //assert
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 0, 1}, permutationIterator.next());
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 1, 0}, permutationIterator.next());
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 0, 0}, permutationIterator.next());
        Assertions.assertFalse(permutationIterator.hasNext());
    }

    @Test
    public void testPermutation2() {
        //setup
        Permutation permutation = new Permutation(new Integer[]{0, 0, 1});
        Iterator<Integer[]> permutationIterator = permutation.iterator();

        //assert
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 0, 1}, permutationIterator.next());
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 1, 0}, permutationIterator.next());
        Assertions.assertTrue(permutationIterator.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 0, 0}, permutationIterator.next());
        Assertions.assertFalse(permutationIterator.hasNext());
    }
}
