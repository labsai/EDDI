package io.sls.botmarklet;

import org.junit.Assert;
import org.junit.Test;

public class RemoveLineBreakTest {

	@Test
	public void testSimple() {
		// test
		String result = BotmarkletCreatorUtility.removeLineBreaks("\n");
		// assert
		Assert.assertEquals("", result);
	}
	
	@Test
	public void testALittleBitMoreComplex() {
		// test
		String result = BotmarkletCreatorUtility.removeLineBreaks("a\n");
		// assert
		Assert.assertEquals("a", result);
	}

    @Test
    	public void testALittleBitMoreComplex2() {
    		// test
    		String result = BotmarkletCreatorUtility.removeLineBreaks("a\r");
    		// assert
    		Assert.assertEquals("a", result);
    	}

    @Test
    	public void testALittleBitMoreComplex3() {
    		// test
    		String result = BotmarkletCreatorUtility.removeLineBreaks("a\n\r");
    		// assert
    		Assert.assertEquals("a", result);
    	}
	
	@Test
	public void testDoubleLineBreak() {
		// test
		String result = BotmarkletCreatorUtility.removeLineBreaks("a\n\na");
		// assert
		Assert.assertEquals("aa", result);
	}
	
	@Test
	public void testRemoveBlanksAfterLineBreak() {
		// test
		String result = BotmarkletCreatorUtility.removeLineBreaks("a\n\n   a");
		// assert
		Assert.assertEquals("aa", result);
	}
}
