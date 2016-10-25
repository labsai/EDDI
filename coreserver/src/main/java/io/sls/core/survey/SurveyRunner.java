package io.sls.core.survey;

import io.sls.core.survey.model.SurveyElement;

import java.util.List;

/**
 * @author ginccc
 */
public class SurveyRunner {
    private List<SurveyElement> survey;
    private ISurveyResultCollector resultCollector;


    public SurveyRunner(List<SurveyElement> survey, ISurveyResultCollector resultCollector) {
        this.survey = survey;
        this.resultCollector = resultCollector;
    }

    public void step() {
        resultCollector.ask(survey.get(0).getChildren().get(0).getOutputId());
    }
}
