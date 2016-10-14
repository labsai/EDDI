// GET /{id}/currentversion
IRestBotStore.getCurrentVersion = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/botstore/bots/';
    uri += REST.Encoding.encodePathSegment(params.id);
    uri += '/currentversion';
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('text/plain');
    if(params.$callback){
        request.execute(params.$callback);
    }else{
        var returnValue;
        request.setAsync(false);
        var callback = function(httpCode, xmlHttpRequest, value){ returnValue = value;};
        request.execute(callback);
        return returnValue;
    }
}

IRestPackageStore.getCurrentVersion = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/packagestore/packages/';
    uri += REST.Encoding.encodePathSegment(params.id);
    uri += '/currentversion';
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('text/plain');
    if(params.$callback){
        request.execute(params.$callback);
    }else{
        var returnValue;
        request.setAsync(false);
        var callback = function(httpCode, xmlHttpRequest, value){ returnValue = value;};
        request.execute(callback);
        return returnValue;
    }
}

IRestBehaviorStore.getCurrentVersion = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/behaviorstore/behaviorsets/';
    uri += REST.Encoding.encodePathSegment(params.id);
    uri += '/currentversion';
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('text/plain');
    if(params.$callback){
        request.execute(params.$callback);
    }else{
        var returnValue;
        request.setAsync(false);
        var callback = function(httpCode, xmlHttpRequest, value){ returnValue = value;};
        request.execute(callback);
        return returnValue;
    }
}

IRestOutputStore.getCurrentVersion = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/outputstore/outputsets/';
    uri += REST.Encoding.encodePathSegment(params.id);
    uri += '/currentversion';
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('text/plain');
    if(params.$callback){
        request.execute(params.$callback);
    }else{
        var returnValue;
        request.setAsync(false);
        var callback = function(httpCode, xmlHttpRequest, value){ returnValue = value;};
        request.execute(callback);
        return returnValue;
    }
}

IRestRegularDictionaryStore.getCurrentVersion = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/regulardictionarystore/regulardictionaries/';
    uri += REST.Encoding.encodePathSegment(params.id);
    uri += '/currentversion';
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('text/plain');
    if(params.$callback){
        request.execute(params.$callback);
    }else{
        var returnValue;
        request.setAsync(false);
        var callback = function(httpCode, xmlHttpRequest, value){ returnValue = value;};
        request.execute(callback);
        return returnValue;
    }
};


var IRestBotAdministration = {};
// POST /administration/{environment}/deploy/{botId}
IRestBotAdministration.deployBot = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/administration/';
    uri += REST.Encoding.encodePathSegment(params.environment);
    uri += '/deploy/';
    uri += REST.Encoding.encodePathSegment(params.botId);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
    if(params.$authorization)
        request.addHeader('Authorization', 'Bearer ' + params.$authorization);
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('text/plain');
    if(params.$callback){
        request.execute(params.$callback);
    }else{
        var returnValue;
        request.setAsync(false);
        var callback = function(httpCode, xmlHttpRequest, value){ returnValue = value;};
        request.execute(callback);
        return returnValue;
    }
};

// GET /administration/{environment}/deploymentstatus/{botId}
IRestBotAdministration.getDeploymentStatus = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/administration/';
    uri += REST.Encoding.encodePathSegment(params.environment);
    uri += '/deploymentstatus/';
    uri += REST.Encoding.encodePathSegment(params.botId);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('text/plain');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
    if(params.$authorization)
        request.addHeader('Authorization', 'Bearer ' + params.$authorization);
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('text/plain');
    if(params.$callback){
        request.execute(params.$callback);
    }else{
        var returnValue;
        request.setAsync(false);
        var callback = function(httpCode, xmlHttpRequest, value){ returnValue = value;};
        request.execute(callback);
        return returnValue;
    }
};
