package ai.labs.parser.extensions.corrections.providers;

import ai.labs.parser.extensions.IParserExtensionProvider;
import ai.labs.parser.extensions.corrections.ICorrection;

/**
 * @author ginccc
 */
public interface ICorrectionProvider extends IParserExtensionProvider<ICorrection> {
    String ID = "ai.labs.parser.corrections";
}
