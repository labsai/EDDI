package ai.labs.eddi.models;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

@Getter
@Setter
public class BotTriggerConfiguration {
    private String intent;
    private List<BotDeployment> botDeployments = new LinkedList<>();
}


