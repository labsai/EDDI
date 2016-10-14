package io.sls.core.survey;

public class SurveyDefinitionParserTest {

    /*@Test
    public void testDefaultValues() throws IOException {
        // test
        SurveyElement def = SurveyDefinitionParser.parse("{}");

        Assert.assertNotNull(def);
        Assert.assertEquals((Float) 0.0f, def.getRandomFactor());
        Assert.assertEquals(SortingType.alphabetic, def.getSortingtype());
        Assert.assertEquals((Integer) 5, def.getShowMax());
        Assert.assertEquals((Integer) 2, def.getShowMin());
        Assert.assertEquals((Integer) 1, def.getRounds());
        Assert.assertEquals(new Boolean(false), def.getSurveyChildZeroResults());
        Assert.assertEquals(new Boolean(false), def.getSurveyChildUnchangedResults());
    }

    @Test
    public void testSimpleDefinition() throws IOException {
        String content = loadJsonFromClasspathFile("simpleTest.json");

        // test
        SurveyElement def = SurveyDefinitionParser.parse(content);

        Assert.assertNotNull(def);
        Assert.assertEquals("root", def.getName());
        Assert.assertEquals((Float) 0.0f, def.getRandomFactor());
        Assert.assertEquals(SortingType.alphabetic, def.getSortingtype());
        Assert.assertEquals((Integer) 5, def.getShowMax());
        Assert.assertEquals((Integer) 2, def.getShowMin());
        Assert.assertEquals((Integer) 2, def.getRounds());
        Assert.assertEquals(new Boolean(false), def.getSurveyChildZeroResults());
        Assert.assertEquals(new Boolean(false), def.getSurveyChildUnchangedResults());
        Assert.assertEquals("category(usecases)", def.getCategoryExpressions()[0]);
        Assert.assertEquals("category(usecases2)", def.getCategoryExpressions()[1]);
        Assert.assertEquals(2, def.getCategoryExpressions().length);
    }

    @Test
    public void testDefinitionWithChildren() throws IOException {
        String content = loadJsonFromClasspathFile("surveyDefinitionWithChildren.json");

        // test
        SurveyElement def = SurveyDefinitionParser.parse(content);

        // assert
        Assert.assertNotNull(def.getChildren());
        Assert.assertEquals(1, def.getChildren().size());

        SurveyElement child = def.getChildren().get(0);

        Assert.assertNotNull(child);
        Assert.assertEquals("root", child.getName());
        Assert.assertEquals((Float) 0.0f, child.getRandomFactor());
        Assert.assertEquals(SortingType.alphabetic, child.getSortingtype());
        Assert.assertEquals((Integer) 5, child.getShowMax());
        Assert.assertEquals((Integer) 2, child.getShowMin());
        Assert.assertEquals((Integer) 2, child.getRounds());
        Assert.assertEquals(new Boolean(false), child.getSurveyChildZeroResults());
        Assert.assertEquals(new Boolean(false), child.getSurveyChildUnchangedResults());
        Assert.assertEquals("category(usecases)", child.getCategoryExpressions()[0]);
        Assert.assertEquals("category(usecases2)", child.getCategoryExpressions()[1]);
        Assert.assertEquals(2, child.getCategoryExpressions().length);
    }

    @Test
    public void testDefinitionWithChildrenWithChildren() throws IOException {
        String content = loadJsonFromClasspathFile("surveyDefinitionWithChildrenWithChildren.json");

        // test
        SurveyElement def = SurveyDefinitionParser.parse(content);

        // assert
        Assert.assertNotNull(def.getChildren());
        Assert.assertEquals(1, def.getChildren().size());

        SurveyElement child = def.getChildren().get(0).getChildren().get(0);

        Assert.assertNotNull(child);
        Assert.assertEquals("root", child.getName());
        Assert.assertEquals((Float) 0.0f, child.getRandomFactor());
        Assert.assertEquals(SortingType.alphabetic, child.getSortingtype());
        Assert.assertEquals((Integer) 5, child.getShowMax());
        Assert.assertEquals((Integer) 2, child.getShowMin());
        Assert.assertEquals((Integer) 2, child.getRounds());
        Assert.assertEquals(new Boolean(false), child.getSurveyChildZeroResults());
        Assert.assertEquals(new Boolean(false), child.getSurveyChildUnchangedResults());
        Assert.assertEquals("category(usecases)", child.getCategoryExpressions()[0]);
        Assert.assertEquals("category(usecases2)", child.getCategoryExpressions()[1]);
        Assert.assertEquals(2, child.getCategoryExpressions().length);
    }

    private String loadJsonFromClasspathFile(String fileName) throws IOException {
        InputStream stream = this.getClass().getResourceAsStream(fileName);

        return inputStreamToString(stream);
    }

    private String inputStreamToString(InputStream in) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(in));
        StringBuilder stringBuilder = new StringBuilder();

        String line;
        while ((line = bufferedReader.readLine()) != null) {
            stringBuilder.append(line).append("\n");
        }

        return stringBuilder.toString();
    }*/
}
