package ai.labs.models;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

@Getter
@Setter
public class HttpCodeValidator {
    private List<Integer> runOnHttpCode = Arrays.asList(200);
    private List<Integer> skipOnHttpCode = Arrays.asList(0, 400, 401, 402, 403, 404, 409, 410, 500, 501, 502);
}
