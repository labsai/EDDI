package ai.labs.botmarklet;

import junit.framework.Assert;
import org.junit.Test;

import java.io.UnsupportedEncodingException;

public class UrlEncodeTest {

	@Test
	public void testUrlEncode1() throws UnsupportedEncodingException {
		String result = BotmarkletCreatorUtility.replaceBlanks(" ");
		
		Assert.assertEquals("%20", result);
	}
}
