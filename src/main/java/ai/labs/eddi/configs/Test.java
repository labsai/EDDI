package ai.labs.eddi.configs;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Date;

@Path("test")
public class Test {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TestResponse getTest() {
        return new TestResponse();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class TestResponse {
        private Date date = new Date();
    }
}

