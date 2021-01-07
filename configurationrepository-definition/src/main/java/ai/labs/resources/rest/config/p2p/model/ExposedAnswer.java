package ai.labs.resources.rest.config.p2p.model;

import ai.labs.resources.rest.config.regulardictionary.model.RegularDictionaryConfiguration;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @author rpi
 */

@Getter
@Setter
public class ExposedAnswer {

    private String name;
    private List<RegularDictionaryConfiguration.PhraseConfiguration> phraseConfigurations;
    private List<RegularDictionaryConfiguration.RegExConfiguration> regExConfigurations;
    private List<RegularDictionaryConfiguration.WordConfiguration> wordConfigurations;
    private boolean needsUserConfirmation;
}
