package io.sls.core.surveyrunner;

import io.sls.core.survey.ISurveyResultCollector;
import io.sls.core.survey.model.SurveyElement;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */
public class SurveyRunnerTest {

    /*@Test
    public void testSimple() {
        // setup
        List<SurveyElement> survey = Collections.singletonList(createSurvey().get(0));
        TestSurveyCollector resultCollector = new TestSurveyCollector();

        // test
        new SurveyRunner(survey, resultCollector).step();

        // assert
        Assert.assertEquals(1, resultCollector.getQuestionIds().size());
        Assert.assertEquals("usecasesOutput", resultCollector.getQuestionIds().get(0));
    }
*/

    private List<SurveyElement> createSurvey() {
        return new SurveyBuilder().//
                add("usecases", "usecasesOutput").build();
    }

    private class TestSurveyCollector implements ISurveyResultCollector {
        public List<String> getQuestionIds() {
            return questionIds;
        }

        private List<String> questionIds = new ArrayList<String>();


        @Override
        public void ask(String questionId) {
            questionIds.add(questionId);
        }
    }

    private class SurveyBuilder {
        List<SurveyElement> elements = new ArrayList<SurveyElement>();

        public List<SurveyElement> build() {
            return elements;
        }

        public SurveyBuilder add(String name, String questionId) {
            SurveyElement child = new SurveyElement();
            child.setName(name);
            child.setOutputId(questionId);
            elements.add(child);
            return this;
        }
    }
}
