package ai.labs.eddi.configs.apicalls.model;


import java.util.Arrays;
import java.util.List;

public class HttpCodeValidator {
    public static final HttpCodeValidator DEFAULT =
            new HttpCodeValidator(
                    List.of(200, 201),
                    Arrays.asList(0, 400, 401, 402, 403, 404, 409, 410, 500, 501, 502));
    private List<Integer> runOnHttpCode;
    private List<Integer> skipOnHttpCode;

    public HttpCodeValidator() {
    }

    public HttpCodeValidator(List<Integer> runOnHttpCode, List<Integer> skipOnHttpCode) {
        this.runOnHttpCode = runOnHttpCode;
        this.skipOnHttpCode = skipOnHttpCode;
    }

    public List<Integer> getRunOnHttpCode() {
        return runOnHttpCode;
    }

    public void setRunOnHttpCode(List<Integer> runOnHttpCode) {
        this.runOnHttpCode = runOnHttpCode;
    }

    public List<Integer> getSkipOnHttpCode() {
        return skipOnHttpCode;
    }

    public void setSkipOnHttpCode(List<Integer> skipOnHttpCode) {
        this.skipOnHttpCode = skipOnHttpCode;
    }
}
