package ai.labs.resources.rest.http.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class HttpCall {
    private String name;
    private List<String> actions;
    private Request request;
}
