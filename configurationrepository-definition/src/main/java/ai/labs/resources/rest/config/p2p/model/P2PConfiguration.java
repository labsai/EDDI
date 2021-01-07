package ai.labs.resources.rest.config.p2p.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @author rpi
 */

@Getter
@Setter
public class P2PConfiguration {

    private boolean exposeAnswers;
    private List<ExposedAnswer> exposedAnswers;
    private List<String> askOtherBotActions;

}
