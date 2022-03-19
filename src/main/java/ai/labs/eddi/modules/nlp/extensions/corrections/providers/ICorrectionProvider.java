package ai.labs.eddi.modules.nlp.extensions.corrections.providers;


import ai.labs.eddi.modules.nlp.extensions.IParserExtensionProvider;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;

/**
 * @author ginccc
 */
public interface ICorrectionProvider extends IParserExtensionProvider<ICorrection> {
    String ID = "ai.labs.parser.corrections";
}
