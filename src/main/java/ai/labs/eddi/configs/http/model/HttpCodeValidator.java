package ai.labs.eddi.configs.http.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class HttpCodeValidator {
    public static final HttpCodeValidator DEFAULT =
            new HttpCodeValidator(
                    List.of(200, 201),
                    Arrays.asList(0, 400, 401, 402, 403, 404, 409, 410, 500, 501, 502));
    private List<Integer> runOnHttpCode;
    private List<Integer> skipOnHttpCode;
}
