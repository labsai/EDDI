package ai.labs.eddi.modules.nlp.extensions.normalizers.providers;

import ai.labs.eddi.modules.nlp.extensions.IParserExtensionProvider;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;

public interface INormalizerProvider extends IParserExtensionProvider<INormalizer> {
    String ID = "ai.labs.parser.normalizers";
}
