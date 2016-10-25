package io.sls.core.parser.internal.matches;


import org.junit.Assert;
import org.junit.Test;

import java.util.Iterator;

/**
 * @author ginccc
 */
public class PermutationTest {

    @Test
    public void testPermutation() {
        //setup
        Permutation permutation = new Permutation(new Integer[]{1, 2, 3});
        Iterator<Integer[]> permutationIterator = permutation.iterator();

        //assert
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{1, 2, 3}, permutationIterator.next());
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{1, 3, 2}, permutationIterator.next());
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{2, 1, 3}, permutationIterator.next());
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{2, 3, 1}, permutationIterator.next());
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{3, 1, 2}, permutationIterator.next());
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{3, 2, 1}, permutationIterator.next());
        Assert.assertFalse(permutationIterator.hasNext());
    }

    @Test
    public void testPermutation1() {
        //setup
        Permutation permutation = new Permutation(new Integer[]{1, 0, 0});
        Iterator<Integer[]> permutationIterator = permutation.iterator();

        //assert
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{0, 0, 1}, permutationIterator.next());
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{0, 1, 0}, permutationIterator.next());
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{1, 0, 0}, permutationIterator.next());
        Assert.assertFalse(permutationIterator.hasNext());
    }

    @Test
    public void testPermutation2() {
        //setup
        Permutation permutation = new Permutation(new Integer[]{0, 0, 1});
        Iterator<Integer[]> permutationIterator = permutation.iterator();

        //assert
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{0, 0, 1}, permutationIterator.next());
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{0, 1, 0}, permutationIterator.next());
        Assert.assertTrue(permutationIterator.hasNext());
        Assert.assertArrayEquals(new Integer[]{1, 0, 0}, permutationIterator.next());
        Assert.assertFalse(permutationIterator.hasNext());
    }
}
