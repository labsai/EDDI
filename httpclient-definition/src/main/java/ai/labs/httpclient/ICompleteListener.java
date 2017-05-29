package ai.labs.httpclient;

public interface ICompleteListener {
    void onComplete(IResponse response) throws IResponse.HttpResponseException;
}
