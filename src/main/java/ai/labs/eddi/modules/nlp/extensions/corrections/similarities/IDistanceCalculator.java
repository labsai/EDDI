package ai.labs.eddi.modules.nlp.extensions.corrections.similarities;

public interface IDistanceCalculator {
    int calculate(String stringOne, String stringTwo);
}
