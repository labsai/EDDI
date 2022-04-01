package ai.labs.eddi.httpclient;

public interface ICompleteListener {
    void onComplete(IResponse response) throws IResponse.HttpResponseException;
}
