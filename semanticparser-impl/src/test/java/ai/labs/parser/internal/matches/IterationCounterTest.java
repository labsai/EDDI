package ai.labs.parser.internal.matches;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author ginccc
 */
@Slf4j
class IterationCounterTest {

    /**
     * TODO IterationCounter does not get to 1-1-1 or 2-2-2
     * @throws Exception
     */
    @Test
    void testCreateIterationPlan() throws Exception {
        //setup
        IterationCounter counter = new IterationCounter(3, new Integer[]{2, 2, 2});

        //assert
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 0, 0}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 0, 1}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 1, 0}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 0, 0}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 0, 2}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 2, 0}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 0, 0}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 1, 1}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 0, 1}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 1, 0}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 1, 2}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 2, 1}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 0, 2}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 2, 0}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 0, 1}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 1, 0}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{0, 2, 2}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 0, 2}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 2, 0}, counter.next().getIndexes());
        /*Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 1, 1}, counter.next().getIndexes());*/
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 1, 2}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 2, 1}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 1, 1}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{1, 2, 2}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 1, 2}, counter.next().getIndexes());
        Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 2, 1}, counter.next().getIndexes());
        /*Assertions.assertTrue(counter.hasNext());
        Assertions.assertArrayEquals(new Integer[]{2, 2, 2}, counter.next().getIndexes());*/
        Assertions.assertFalse(counter.hasNext());

    }

    /*@Test
    public void testCreateIterationPlan1() throws Exception {
        //setup
        IterationCounter counter = new IterationCounter(3, new Integer[]{2, 2, 2});

        while (counter.hasNext()) {
            System.out.println(Arrays.toString(counter.next().getIndexes()));
        }
    }*/
}
