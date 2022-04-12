package ai.labs.eddi.engine.httpclient;

public interface ICompleteListener {
    void onComplete(IResponse response) throws IResponse.HttpResponseException;
}
