package io.sls.botmarklet;

import junit.framework.Assert;
import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

public class CommaSeparatedListTest {

	@Test
	public void testSimple() throws MalformedURLException {
		// setup
		List<URL> input = Arrays.asList(URI.create("http://bla").toURL(), URI.create("http://bla1").toURL());
		
		// test
		String string = BotmarkletCreatorUtility.createCommaSeparatedList(input);
		
		// assert
		Assert.assertEquals("\"http://bla\",\"http://bla1\"", string); 
	}
	
	@Test
	public void testSimple1() throws MalformedURLException {
		// setup
		List<URL> input = Arrays.asList(URI.create("http://bla1").toURL(), URI.create("http://bla2").toURL());
		
		// test
		String string = BotmarkletCreatorUtility.createCommaSeparatedList(input);
		
		// assert
		Assert.assertEquals("\"http://bla1\",\"http://bla2\"", string); 
	}
	
	@Test
	public void testOneItem() throws MalformedURLException {
		// setup
		List<URL> input = Arrays.asList(URI.create("http://bla1").toURL());
		
		// test
		String string = BotmarkletCreatorUtility.createCommaSeparatedList(input);
		
		// assert
		Assert.assertEquals("\"http://bla1\"", string); 
	}
}
