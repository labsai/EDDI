package ai.labs.parser.extensions.normalizers.providers;

import ai.labs.parser.extensions.IParserExtensionProvider;
import ai.labs.parser.extensions.normalizers.INormalizer;

public interface INormalizerProvider extends IParserExtensionProvider<INormalizer> {
    String ID = "ai.labs.parser.normalizers";
}
