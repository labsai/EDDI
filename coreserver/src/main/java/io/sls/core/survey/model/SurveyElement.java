package io.sls.core.survey.model;

import java.util.ArrayList;
import java.util.List;

public class SurveyElement {

    private String name;
    private String[] propertyExpressions;
    private String[] categoryExpressions;
    private String outputId;

    private Float randomFactor;
    private SortingType sortingType;
    private Integer showMax;
    private Integer showMin;
    private Integer rounds;
    private Boolean surveyChildZeroResults;
    private Boolean surveyChildUnchangedResults;
    private List<SurveyElement> children = new ArrayList<SurveyElement>();

    public SurveyElement() {

    }

    public SurveyElement(float randomFactor, SortingType sortingType, int showMax, int showMin, int rounds,
                         boolean surveyChildZeroResults, boolean surveyChildUnchangedResults) {
        this.randomFactor = randomFactor;
        this.sortingType = sortingType;
        this.showMax = showMax;
        this.showMin = showMin;
        this.rounds = rounds;
        this.surveyChildZeroResults = surveyChildZeroResults;
        this.surveyChildUnchangedResults = surveyChildUnchangedResults;
    }

    public String[] getPropertyExpressions() {
        return propertyExpressions;
    }

    public void setPropertyExpressions(String[] propertyExpressions) {
        this.propertyExpressions = propertyExpressions;
    }

    public String[] getCategoryExpressions() {
        return categoryExpressions;
    }

    public void setCategoryExpressions(String[] categoryExpressions) {
        this.categoryExpressions = categoryExpressions;
    }

    public String getOutputId() {
        return outputId;
    }

    public void setOutputId(String outputId) {
        this.outputId = outputId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Float getRandomFactor() {
        return randomFactor;
    }

    public void setRandomFactor(Float randomfactor) {
        this.randomFactor = randomfactor;
    }

    public SortingType getSortingtype() {
        return sortingType;
    }

    public void setSortingtype(SortingType sortingtype) {
        this.sortingType = sortingtype;
    }

    public Integer getShowMax() {
        return showMax;
    }

    public void setShowMax(Integer showMax) {
        this.showMax = showMax;
    }

    public Integer getShowMin() {
        return showMin;
    }

    public void setShowMin(Integer showMin) {
        this.showMin = showMin;
    }

    public Integer getRounds() {
        return rounds;
    }

    public void setRounds(Integer rounds) {
        this.rounds = rounds;
    }

    public Boolean getSurveyChildZeroResults() {
        return surveyChildZeroResults;
    }

    public void setSurveyChildZeroResults(Boolean surveyChildZeroResults) {
        this.surveyChildZeroResults = surveyChildZeroResults;
    }

    public Boolean getSurveyChildUnchangedResults() {
        return surveyChildUnchangedResults;
    }

    public void setSurveyChildUnchangedResults(
            Boolean surveyChildUnchangedResults) {
        this.surveyChildUnchangedResults = surveyChildUnchangedResults;
    }

    public enum SortingType {
        alphabetic, resultsize, orderId
    }

    public List<SurveyElement> getChildren() {
        return children;
    }

    public void setChildren(List<SurveyElement> elements) {
        this.children = elements;
    }
}
