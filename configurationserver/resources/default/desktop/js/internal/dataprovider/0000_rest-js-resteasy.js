// namespace
var REST = {
    apiURL : null,
    debug: false,
    loglevel : 0,
    antiBrowserCache : false,
    cacheHeaders : []
};

// helper function
REST.getKeys = function (o) {
    if (o !== Object(o))
        throw new TypeError('REST.getKeys called on non-object');
    var ret = [], p;
    for (p in o) if (Object.prototype.hasOwnProperty.call(o, p)) ret.push(p);
    return ret;
};

// constructor
REST.Request = function (){
    REST.log("Creating new Request");
    this.uri = null;
    this.method = "GET";
    this.username = null;
    this.password = null;
    this.acceptHeader = "*/*";
    this.contentTypeHeader = null;
    this.async = true;
    this.queryParameters = [];
    this.matrixParameters = [];
    this.formParameters = [];
    this.forms = [];
    this.cookies = [];
    this.headers = [];
    this.entity = null;
}

REST.Request.prototype = {
    execute : function(callback) {
        var _execute = function(self, callback) {
            var request = new XMLHttpRequest();
            var url = self.uri;

            if (REST.antiBrowserCache == true) {
                request.url = url;
            }

            var restRequest = self;
            for (var i = 0; i < self.matrixParameters.length; i++) {
                url += ";" + REST.Encoding.encodePathParamName(self.matrixParameters[i][0]);
                url += "=" + REST.Encoding.encodePathParamValue(self.matrixParameters[i][1]);
            }
            for (var i = 0; i < self.queryParameters.length; i++) {
                if (i == 0)
                    url += "?";
                else
                    url += "&";
                url += REST.Encoding.encodeQueryParamNameOrValue(self.queryParameters[i][0]);
                url += "=" + REST.Encoding.encodeQueryParamNameOrValue(self.queryParameters[i][1]);
            }
            for (var i = 0; i < self.cookies.length; i++) {
                document.cookie = encodeURI(self.cookies[i][0])
                    + "=" + encodeURI(self.cookies[i][1]);
            }
            request.open(self.method, url, self.async, self.username, self.password);
            var acceptSet = self;
            var contentTypeSet = self;
            var containsAuthorizationHeader = self;
            for (var i = 0; i < self.headers.length; i++) {
                if (self.headers[i][0].toLowerCase() == 'accept')
                    acceptSet = self.headers[i][1];
                if (self.headers[i][0].toLowerCase() == 'content-type')
                    contentTypeSet = self.headers[i][1];
                if (self.headers[i][0].toLowerCase() == 'authorization')
                    containsAuthorizationHeader = true;

                request.setRequestHeader(REST.Encoding.encodeHeaderName(self.headers[i][0]),
                    REST.Encoding.encodeHeaderValue(self.headers[i][1]));
            }
            if (!containsAuthorizationHeader) {
                request.setRequestHeader('Authorization', 'Bearer: ' + keycloak.token);
            }

            if(!acceptSet)
                request.setRequestHeader('Accept', self.acceptHeader);
            REST.log("Got form params: "+self.formParameters.length);
            // see if we're sending an entity or a form
            if(self.entity && (self.formParameters.length > 0 || self.forms.length > 0))
                throw "Cannot have both an entity and form parameters";
            // form
            if(self.formParameters.length > 0 || self.forms.length > 0){
                if(contentTypeSet && contentTypeSet != "application/x-www-form-urlencoded")
                    throw "The ContentType that was set by header value ("+contentTypeSet+") is incompatible with form parameters";
                if(self.contentTypeHeader && self.contentTypeHeader != "application/x-www-form-urlencoded")
                    throw "The ContentType that was set with setContentType ("+self.contentTypeHeader+") is incompatible with form parameters";
                contentTypeSet = "application/x-www-form-urlencoded";
                request.setRequestHeader('Content-Type', contentTypeSet);
            }else if(self.entity && !contentTypeSet && self.contentTypeHeader){
                // entity
                contentTypeSet = self.contentTypeHeader;
                request.setRequestHeader('Content-Type', self.contentTypeHeader);
            }
            // we use this flag to work around buggy browsers
            var gotReadyStateChangeEvent = false;
            if(callback){
                request.onreadystatechange = function() {
                    gotReadyStateChangeEvent = true;
                    REST.log("Got readystatechange");
                    REST._complete(this, callback);
                };
            }
            var data = self.entity;
            if(self.entity){
                if(self.entity instanceof Element){
                    if(!contentTypeSet || REST._isXMLMIME(contentTypeSet))
                        data = REST.serialiseXML(self.entity);
                }else if(self.entity instanceof Document){
                    if(!contentTypeSet || REST._isXMLMIME(contentTypeSet))
                        data = self.entity;
                }else if(self.entity instanceof Object){
                    if(!contentTypeSet || REST._isJSONMIME(contentTypeSet))
                        data = JSON.stringify(self.entity);
                }
            }else if(self.formParameters.length > 0){
                data = '';
                for(var i=0;i<self.formParameters.length;i++){
                    if(i > 0)
                        data += "&";
                    data += REST.Encoding.encodeFormNameOrValue(self.formParameters[i][0]);
                    data += "=" + REST.Encoding.encodeFormNameOrValue(self.formParameters[i][1]);
                }
            } else if (self.forms.length > 0) {
                data = '';
                for (var i = 0; i < self.forms.length; i++) {
                    if (i > 0)
                        data += "&";
                    var obj = self.forms[i][1];
                    var key = REST.getKeys(obj)[0];
                    data += REST.Encoding.encodeFormNameOrValue(key);
                    data += "=" + REST.Encoding.encodeFormNameOrValue(obj[key]);
                }
            }
            REST.log("Content-Type set to "+contentTypeSet);
            REST.log("Entity set to "+data);

            request.send(data);
            // now if the browser did not follow the specs and did not fire the events while synchronous,
            // handle it manually
            if(!self.async && !gotReadyStateChangeEvent && callback){
                REST.log("Working around browser readystatechange bug");
                REST._complete(request, callback);
            }

            if (REST.debug == true) { REST.lastRequest = request; }

            if (REST.antiBrowserCache == true && request.status != 304) {
                var _cachedHeaders = {
                    "Etag":request.getResponseHeader('Etag'),
                    "Last-Modified":request.getResponseHeader('Last-Modified'),
                    "entity":request.responseText
                };

                var signature = REST._generate_cache_signature(url);
                REST._remove_deprecated_cache_signature(signature);
                REST._addToArray(REST.cacheHeaders, signature, _cachedHeaders);
            }
        };
        var minValidity = 10;
        if (this.async) {
            var self = this;
            keycloak.updateToken(minValidity).success(function() {
                _execute(self, callback);
            }).error(function() {
                keycloak.login();
            });
        } else {
            if (keycloak.updateTokenSync(minValidity)) {
                _execute(this, callback);
            } else {
                keycloak.login();
            }

        }
    },
    setAccepts : function(acceptHeader){
        REST.log("setAccepts("+acceptHeader+")");
        this.acceptHeader = acceptHeader;
    },
    setCredentials : function(username, password){
        this.password = password;
        this.username = username;
    },
    setEntity : function(entity){
        REST.log("setEntity("+entity+")");
        this.entity = entity;
    },
    setContentType : function(contentType){
        REST.log("setContentType("+contentType+")");
        this.contentTypeHeader = contentType;
    },
    setURI : function(uri){
        REST.log("setURI("+uri+")");
        this.uri = uri;
    },
    setMethod : function(method){
        REST.log("setMethod("+method+")");
        this.method = method;
    },
    setAsync : function(async){
        REST.log("setAsync("+async+")");
        this.async = async;
    },
    addCookie : function(name, value){
        REST.log("addCookie("+name+"="+value+")");
        REST._addToArray(this.cookies, name, value);
    },
    addQueryParameter : function(name, value){
        REST.log("addQueryParameter("+name+"="+value+")");
        REST._addToArray(this.queryParameters, name, value);
    },
    addMatrixParameter : function(name, value){
        REST.log("addMatrixParameter("+name+"="+value+")");
        REST._addToArray(this.matrixParameters, name, value);
    },
    addFormParameter : function(name, value){
        REST.log("addFormParameter("+name+"="+value+")");
        REST._addToArray(this.formParameters, name, value);
    },
    addForm : function(name, value){
        REST.log("addForm("+name+"="+value+")");
        REST._addToArray(this.forms, name, value);
    },
    addHeader : function(name, value){
        REST.log("addHeader("+name+"="+value+")");
        REST._addToArray(this.headers, name, value);
    }
};

REST.log = function (string) {
    if (REST.loglevel > 0)
        print(string);
};

REST._addToArray = function (array, name, value) {
    if (value instanceof Array) {
        for (var i = 0; i < value.length; i++) {
            array.push([name, value[i]]);
        }
    } else {
        array.push([name, value]);
    }
};

REST._generate_cache_signature = function (url) {
    return url.replace(/\?resteasy_jsapi_anti_cache=\d+/, '');
};

REST._remove_deprecated_cache_signature = function (signature) {
    for (idx in REST.cacheHeaders) {
        var _signature = REST.cacheHeaders[idx][0];
        if (signature == _signature) {
            REST.cacheHeaders.splice(idx, 1);
        }
    }

};

REST._get_cache_signature = function (signature) {
    for (idx in REST.cacheHeaders) {
        var _signature = REST.cacheHeaders[idx][0];
        if (signature == _signature) {
            return REST.cacheHeaders[idx];
        }
    }
    return null;
};

REST._complete = function(request, callback){
    REST.log("Request ready state: "+request.readyState);
    if(request.readyState == 4) {
        var entity;
        REST.log("Request status: "+request.status);
        REST.log("Request response: "+request.responseText);
        if(request.status >= 200 && request.status < 300){
            request.onreadystatechange = null;
            var contentType = request.getResponseHeader("Content-Type");
            if(contentType != null){
                if(REST._isXMLMIME(contentType))
                    entity = request.responseXML;
                else if(REST._isJSONMIME(contentType))
                    entity = JSON.parse(request.responseText);
                else
                    entity = request.responseText;
            }else
                entity = request.responseText;
        }

        if (request.status == 304) {
            entity = REST._get_cache_signature(REST._generate_cache_signature(request.url))[1]['entity'];
        }
        REST.log("Calling callback with: "+entity);
        callback(request.status, request, entity);
    }
}

REST._isXMLMIME = function(contentType){
    return contentType == "text/xml"
        || contentType == "application/xml"
        || (contentType.indexOf("application/") == 0
        && contentType.lastIndexOf("+xml") == (contentType.length - 4));
}

REST._isJSONMIME = function(contentType){
    return contentType == "application/json"
        || (contentType.indexOf("application/") == 0
        && contentType.lastIndexOf("+json") == (contentType.length - 5));
}

/* Encoding */

REST.Encoding = {};

REST.Encoding.hash = function(a){
    var ret = {};
    for(var i=0;i<a.length;i++)
        ret[a[i]] = 1;
    return ret;
}

//
// rules

REST.Encoding.Alpha = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
    'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z'];

REST.Encoding.Numeric = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];

REST.Encoding.AlphaNum = [].concat(REST.Encoding.Alpha, REST.Encoding.Numeric);

REST.Encoding.AlphaNumHash = REST.Encoding.hash(REST.Encoding.AlphaNum);

/**
 * unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
 */
REST.Encoding.Unreserved = [].concat(REST.Encoding.AlphaNum, ['-', '.', '_', '~']);

/**
 * gen-delims = ":" / "/" / "?" / "#" / "[" / "]" / "@"
 */
REST.Encoding.GenDelims = [':', '/', '?', '#', '[', ']', '@'];

/**
 * sub-delims = "!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="
 */
REST.Encoding.SubDelims = ['!','$','&','\'','(', ')', '*','+',',',';','='];

/**
 * reserved = gen-delims | sub-delims
 */
REST.Encoding.Reserved = [].concat(REST.Encoding.GenDelims, REST.Encoding.SubDelims);

/**
 * pchar = unreserved | escaped | sub-delims | ":" | "@"
 *
 * Note: we don't allow escaped here since we will escape it ourselves, so we don't want to allow them in the
 * unescaped sequences
 */
REST.Encoding.PChar = [].concat(REST.Encoding.Unreserved, REST.Encoding.SubDelims, [':', '@']);

/**
 * path_segment = pchar <without> ";"
 */
REST.Encoding.PathSegmentHash = REST.Encoding.hash(REST.Encoding.PChar);
delete REST.Encoding.PathSegmentHash[";"];

/**
 * path_param_name = pchar <without> ";" | "="
 */
REST.Encoding.PathParamHash = REST.Encoding.hash(REST.Encoding.PChar);
delete REST.Encoding.PathParamHash[";"];
delete REST.Encoding.PathParamHash["="];

/**
 * path_param_value = pchar <without> ";"
 */
REST.Encoding.PathParamValueHash = REST.Encoding.hash(REST.Encoding.PChar);
delete REST.Encoding.PathParamValueHash[";"];

/**
 * query = pchar / "/" / "?"
 */
REST.Encoding.QueryHash = REST.Encoding.hash([].concat(REST.Encoding.PChar, ['/', '?']));
// deviate from the RFC to disallow separators such as "=", "@" and the famous "+" which is treated as a space
// when decoding
delete REST.Encoding.QueryHash["="];
delete REST.Encoding.QueryHash["&"];
delete REST.Encoding.QueryHash["+"];

/**
 * fragment = pchar / "/" / "?"
 */
REST.Encoding.FragmentHash = REST.Encoding.hash([].concat(REST.Encoding.PChar, ['/', '?']));

// HTTP

REST.Encoding.HTTPSeparators = ["(" , ")" , "<" , ">" , "@"
    , "," , ";" , ":" , "\\" , "\""
    , "/" , "[" , "]" , "?" , "="
    , "{" , "}" , ' ' , '\t'];

// This should also hold the CTLs but we never need them
REST.Encoding.HTTPChar = [];
(function(){
    for(var i=32;i<127;i++)
        REST.Encoding.HTTPChar.push(String.fromCharCode(i));
})()

// CHAR - separators
REST.Encoding.HTTPToken = REST.Encoding.hash(REST.Encoding.HTTPChar);
(function(){
    for(var i=0;i<REST.Encoding.HTTPSeparators.length;i++)
        delete REST.Encoding.HTTPToken[REST.Encoding.HTTPSeparators[i]];
})()

//
// functions

//see http://www.w3.org/TR/html4/interact/forms.html#h-17.13.4.1
//and http://www.apps.ietf.org/rfc/rfc1738.html#page-4
REST.Encoding.encodeFormNameOrValue = function (val){
    return REST.Encoding.encodeValue(val, REST.Encoding.AlphaNumHash, true);
};


//see http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.2
REST.Encoding.encodeHeaderName = function (val){
    // token+ from http://www.w3.org/Protocols/rfc2616/rfc2616-sec2.html#sec2

    // There is no way to encode a header name. it is either a valid token or invalid and the
    // XMLHttpRequest will fail (http://www.w3.org/TR/XMLHttpRequest/#the-setrequestheader-method)
    // What we could do here is throw if the value is invalid
    return val;
}

//see http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.2
REST.Encoding.encodeHeaderValue = function (val){
    // *TEXT or combinations of token, separators, and quoted-string from http://www.w3.org/Protocols/rfc2616/rfc2616-sec2.html#sec2
    // FIXME: implement me. Stef has given up, since it involves latin1, quoted strings, MIME encoding (http://www.ietf.org/rfc/rfc2047.txt)
    // which mentions a limit on encoded value of 75 chars, which should be split into several lines. This is mad.
    return val;
}

// see http://www.ietf.org/rfc/rfc3986.txt
REST.Encoding.encodeQueryParamNameOrValue = function (val){
    return REST.Encoding.encodeValue(val, REST.Encoding.QueryHash);
}

//see http://www.ietf.org/rfc/rfc3986.txt
REST.Encoding.encodePathSegment = function (val){
    return REST.Encoding.encodeValue(val, REST.Encoding.PathSegmentHash);
}

//see http://www.ietf.org/rfc/rfc3986.txt
REST.Encoding.encodePathParamName = function (val){
    return REST.Encoding.encodeValue(val, REST.Encoding.PathParamHash);
}

//see http://www.ietf.org/rfc/rfc3986.txt
REST.Encoding.encodePathParamValue = function (val){
    return REST.Encoding.encodeValue(val, REST.Encoding.PathParamValueHash);
}

REST.Encoding.encodeValue = function (val, allowed, form){
    if(typeof val != "string"){
        REST.log("val is not a string");
        return val;
    }
    if(val.length == 0){
        REST.log("empty string");
        return val;
    }
    var ret = '';
    for(var i=0;i<val.length;i++){
        var first = val[i];
        if(allowed[first] == 1){
            REST.log("char allowed: "+first);
            ret = ret.concat(first);
        }else if(form && (first == ' ' || first == '\n')){
            // special rules for application/x-www-form-urlencoded
            if(first == ' ')
                ret += '+';
            else
                ret += '%0D%0A';
        }else{
            // See http://www.faqs.org/rfcs/rfc2781.html 2.2

            // switch to codepoint
            first = val.charCodeAt(i);
            // utf-16 pair?
            if(first < 0xD800 || first > 0xDFFF){
                // just a single utf-16 char
                ret = ret.concat(REST.Encoding.percentUTF8(first));
            }else{
                if(first > 0xDBFF || i+1 >= val.length)
                    throw "Invalid UTF-16 value: " + val;
                var second = val.charCodeAt(++i);
                if(second < 0xDC00 || second > 0xDFFF)
                    throw "Invalid UTF-16 value: " + val;
                // char = 10 lower bits of first shifted left + 10 lower bits of second
                var c = ((first & 0x3FF) << 10) | (second & 0x3FF);
                // and add this
                c += 0x10000;
                // char is now 32 bit unicode
                ret = ret.concat(REST.Encoding.percentUTF8(c));
            }
        }
    }
    return ret;
}

// see http://tools.ietf.org/html/rfc3629
REST.Encoding.percentUTF8 = function(c){
    if(c < 0x80)
        return REST.Encoding.percentByte(c);
    if(c < 0x800){
        var first = 0xC0 | ((c & 0x7C0) >> 6);
        var second = 0x80 | (c & 0x3F);
        return REST.Encoding.percentByte(first, second);
    }
    if(c < 0x10000){
        var first = 0xE0 | ((c >> 12) & 0xF);
        var second = 0x80 | ((c >> 6) & 0x3F);
        var third = 0x80 | (c & 0x3F);
        return REST.Encoding.percentByte(first, second, third);
    }
    if(c < 0x110000){
        var first = 0xF0 | ((c >> 18) & 0x7);
        var second = 0x80 | ((c >> 12) & 0x3F);
        var third = 0x80 | ((c >> 6) & 0x3F);
        var fourth = 0x80 | (c & 0x3F);
        return REST.Encoding.percentByte(first, second, third, fourth);
    }
    throw "Invalid character for UTF-8: "+c;
}

REST.Encoding.percentByte = function(){
    var ret = '';
    for(var i=0;i<arguments.length;i++){
        var b = arguments[i];
        if (b >= 0 && b <= 15)
            ret += "%0" + b.toString(16);
        else
            ret += "%" + b.toString(16);
    }
    return ret;
}

REST.serialiseXML = function(node){
    if (typeof XMLSerializer != "undefined")
        return (new XMLSerializer()).serializeToString(node) ;
    else if (node.xml) return node.xml;
    else throw "XML.serialize is not supported or can't serialize " + node;
}
REST.apiURL = 'http://localhost:8181/';
var IRestUserStore = {};
// GET /userstore/users
IRestUserStore.searchUser = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/userstore/users';
    if(Object.prototype.hasOwnProperty.call(params, 'username'))
        request.addQueryParameter('username', params.username);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// POST /userstore/users
IRestUserStore.createUser = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/userstore/users';
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
var IRestDocumentDescriptorStore = {};
// GET /descriptorstore/descriptors/{id}/simple
IRestDocumentDescriptorStore.readSimpleDescriptor = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/descriptorstore/descriptors/';
    uri += REST.Encoding.encodePathSegment(params.id);
    uri += '/simple';
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
var IRestBinaryResource = {};
// GET /binary/{path:.*}
IRestBinaryResource.getBinary = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/binary/';
    uri += REST.Encoding.encodePathSegment(params.path);
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
var IRestBehaviorStore = {};
// POST /behaviorstore/behaviorsets
IRestBehaviorStore.createBehaviorRuleSet = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/behaviorstore/behaviorsets';
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// GET /behaviorstore/behaviorsets
IRestBehaviorStore.readBehaviorDescriptors = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/behaviorstore/behaviorsets';
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
var IRestOutputKeys = {};
// GET /outputKeys
IRestOutputKeys.readOutputKeys = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/outputKeys';
    if(Object.prototype.hasOwnProperty.call(params, 'packageId'))
        request.addQueryParameter('packageId', params.packageId);
    if(Object.prototype.hasOwnProperty.call(params, 'packageVersion'))
        request.addQueryParameter('packageVersion', params.packageVersion);
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
var IRestOutputStore = {};
// GET /outputstore/outputsets/{id}
IRestOutputStore.readOutputSet = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/outputstore/outputsets/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'order'))
        request.addQueryParameter('order', params.order);
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// DELETE /outputstore/outputsets/{id}
IRestOutputStore.deleteOutputSet = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('DELETE');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/outputstore/outputsets/';
    uri += REST.Encoding.encodePathSegment(params.id);
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
// PATCH /outputstore/outputsets/{id}
IRestOutputStore.patchOutputSet = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PATCH');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/outputstore/outputsets/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// PUT /outputstore/outputsets/{id}
IRestOutputStore.updateOutputSet = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/outputstore/outputsets/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
var IRestRegularDictionaryStore = {};
// GET /regulardictionarystore/regulardictionaries/{id}/expressions
IRestRegularDictionaryStore.readExpressions = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/regulardictionarystore/regulardictionaries/';
    uri += REST.Encoding.encodePathSegment(params.id);
    uri += '/expressions';
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'order'))
        request.addQueryParameter('order', params.order);
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// GET /userstore/users/{userId}
IRestUserStore.readUser = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/userstore/users/';
    uri += REST.Encoding.encodePathSegment(params.userId);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// DELETE /userstore/users/{userId}
IRestUserStore.deleteUser = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('DELETE');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/userstore/users/';
    uri += REST.Encoding.encodePathSegment(params.userId);
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
// PUT /userstore/users/{userId}
IRestUserStore.updateUser = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/userstore/users/';
    uri += REST.Encoding.encodePathSegment(params.userId);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
var IRestScriptImport = {};
// POST /scriptimport
IRestScriptImport.createBot = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/scriptimport';
    if(Object.prototype.hasOwnProperty.call(params, 'language'))
        request.addQueryParameter('language', params.language);
    if(Object.prototype.hasOwnProperty.call(params, 'botId'))
        request.addQueryParameter('botId', params.botId);
    if(Object.prototype.hasOwnProperty.call(params, 'botVersion'))
        request.addQueryParameter('botVersion', params.botVersion);
    if(params.$entity)
        request.setEntity(params.$entity);
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
var IRestExtensionStore = {};
// POST /extensionstore/extensions
IRestExtensionStore.createExtension = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/extensionstore/extensions';
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// GET /extensionstore/extensions
IRestExtensionStore.readExtensionDescriptors = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/extensionstore/extensions';
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
var IRestGroupStore = {};
// GET /groupstore/groups/{groupId}
IRestGroupStore.readGroup = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/groupstore/groups/';
    uri += REST.Encoding.encodePathSegment(params.groupId);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// PUT /groupstore/groups/{groupId}
IRestGroupStore.updateGroup = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/groupstore/groups/';
    uri += REST.Encoding.encodePathSegment(params.groupId);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// DELETE /groupstore/groups/{groupId}
IRestGroupStore.deleteGroup = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('DELETE');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/groupstore/groups/';
    uri += REST.Encoding.encodePathSegment(params.groupId);
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
// POST /outputstore/outputsets
IRestOutputStore.createOutputSet = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/outputstore/outputsets';
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// GET /outputstore/outputsets
IRestOutputStore.readOutputDescriptors = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/outputstore/outputsets';
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
var IRestBotStore = {};
// GET /botstore/bots/{id}/currentversion
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
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// GET /outputstore/outputsets/{id}/outputKeys
IRestOutputStore.readOutputKeys = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/outputstore/outputsets/';
    uri += REST.Encoding.encodePathSegment(params.id);
    uri += '/outputKeys';
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'order'))
        request.addQueryParameter('order', params.order);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
var IRestPermissionStore = {};
// GET /permissionstore/permissions/{resourceId}
IRestPermissionStore.readPermissions = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/permissionstore/permissions/';
    uri += REST.Encoding.encodePathSegment(params.resourceId);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// PUT /permissionstore/permissions/{resourceId}
IRestPermissionStore.updatePermissions = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/permissionstore/permissions/';
    uri += REST.Encoding.encodePathSegment(params.resourceId);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
var IRestMonitorStore = {};
// GET /conversationstore/conversations
IRestMonitorStore.readConversationDescriptors = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/conversationstore/conversations';
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    if(Object.prototype.hasOwnProperty.call(params, 'botId'))
        request.addQueryParameter('botId', params.botId);
    if(Object.prototype.hasOwnProperty.call(params, 'botVersion'))
        request.addQueryParameter('botVersion', params.botVersion);
    if(Object.prototype.hasOwnProperty.call(params, 'conversationState'))
        request.addQueryParameter('conversationState', params.conversationState);
    if(Object.prototype.hasOwnProperty.call(params, 'viewState'))
        request.addQueryParameter('viewState', params.viewState);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// GET /regulardictionarystore/regulardictionaries
IRestRegularDictionaryStore.readRegularDictionaryDescriptors = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/regulardictionarystore/regulardictionaries';
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// POST /regulardictionarystore/regulardictionaries
IRestRegularDictionaryStore.createRegularDictionary = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/regulardictionarystore/regulardictionaries';
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
var IRestPackageStore = {};
// GET /packagestore/packages
IRestPackageStore.readPackageDescriptors = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/packagestore/packages';
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// POST /packagestore/packages
IRestPackageStore.createPackage = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/packagestore/packages';
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// GET /botstore/bots/{id}
IRestBotStore.readBot = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/botstore/bots/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// PUT /botstore/bots/{id}
IRestBotStore.updateBot = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/botstore/bots/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// DELETE /botstore/bots/{id}
IRestBotStore.deleteBot = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('DELETE');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/botstore/bots/';
    uri += REST.Encoding.encodePathSegment(params.id);
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
// PUT /botstore/bots/{id}
IRestBotStore.updateResourceInBot = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/botstore/bots/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
// PUT /packagestore/packages/{id}
IRestPackageStore.updateResourceInPackage = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/packagestore/packages/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
// DELETE /packagestore/packages/{id}
IRestPackageStore.deletePackage = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('DELETE');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/packagestore/packages/';
    uri += REST.Encoding.encodePathSegment(params.id);
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
// PUT /packagestore/packages/{id}
IRestPackageStore.updatePackage = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/packagestore/packages/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// GET /packagestore/packages/{id}
IRestPackageStore.readPackage = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/packagestore/packages/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// PATCH /descriptorstore/descriptors/{id}
IRestDocumentDescriptorStore.patchDescriptor = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PATCH');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/descriptorstore/descriptors/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// GET /descriptorstore/descriptors/{id}
IRestDocumentDescriptorStore.readDescriptor = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/descriptorstore/descriptors/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// GET /extensionstore/extensions/{id}
IRestExtensionStore.readExtension = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/extensionstore/extensions/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// PUT /extensionstore/extensions/{id}
IRestExtensionStore.updateExtension = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/extensionstore/extensions/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// DELETE /extensionstore/extensions/{id}
IRestExtensionStore.deleteExtension = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('DELETE');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/extensionstore/extensions/';
    uri += REST.Encoding.encodePathSegment(params.id);
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
// GET /packagestore/packages/{id}/currentversion
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
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// POST /groupstore/groups
IRestGroupStore.createGroup = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/groupstore/groups';
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// GET /descriptorstore/descriptors
IRestDocumentDescriptorStore.readDescriptors = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/descriptorstore/descriptors';
    if(Object.prototype.hasOwnProperty.call(params, 'type'))
        request.addQueryParameter('type', params.type);
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
var IRestTextResource = {};
// GET /text/{path:.*}
IRestTextResource.getStaticResource = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/text/';
    uri += REST.Encoding.encodePathSegment(params.path);
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
// GET /behaviorstore/behaviorsets/{id}/currentversion
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
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// DELETE /behaviorstore/behaviorsets/{id}
IRestBehaviorStore.deleteBehaviorRuleSet = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('DELETE');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/behaviorstore/behaviorsets/';
    uri += REST.Encoding.encodePathSegment(params.id);
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
// GET /behaviorstore/behaviorsets/{id}
IRestBehaviorStore.readBehaviorRuleSet = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/behaviorstore/behaviorsets/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// PUT /behaviorstore/behaviorsets/{id}
IRestBehaviorStore.updateBehaviorRuleSet = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/behaviorstore/behaviorsets/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// GET /regulardictionarystore/regulardictionaries/{id}/currentversion
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
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// POST /botstore/bots
IRestBotStore.createBot = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/botstore/bots';
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// GET /botstore/bots
IRestBotStore.readBotDescriptors = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/botstore/bots';
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// POST /userstore/users/changepassword
IRestUserStore.changePassword = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('POST');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/userstore/users/changepassword';
    if(Object.prototype.hasOwnProperty.call(params, 'userId'))
        request.addQueryParameter('userId', params.userId);
    if(Object.prototype.hasOwnProperty.call(params, 'newPassword'))
        request.addQueryParameter('newPassword', params.newPassword);
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
// GET /regulardictionarystore/regulardictionaries/{id}
IRestRegularDictionaryStore.readRegularDictionary = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/regulardictionarystore/regulardictionaries/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(Object.prototype.hasOwnProperty.call(params, 'filter'))
        request.addQueryParameter('filter', params.filter);
    if(Object.prototype.hasOwnProperty.call(params, 'order'))
        request.addQueryParameter('order', params.order);
    if(Object.prototype.hasOwnProperty.call(params, 'index'))
        request.addQueryParameter('index', params.index);
    if(Object.prototype.hasOwnProperty.call(params, 'limit'))
        request.addQueryParameter('limit', params.limit);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// DELETE /regulardictionarystore/regulardictionaries/{id}
IRestRegularDictionaryStore.deleteRegularDictionary = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('DELETE');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/regulardictionarystore/regulardictionaries/';
    uri += REST.Encoding.encodePathSegment(params.id);
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
// PUT /regulardictionarystore/regulardictionaries/{id}
IRestRegularDictionaryStore.updateRegularDictionary = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PUT');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/regulardictionarystore/regulardictionaries/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
// PATCH /regulardictionarystore/regulardictionaries/{id}
IRestRegularDictionaryStore.patchRegularDictionary = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('PATCH');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/regulardictionarystore/regulardictionaries/';
    uri += REST.Encoding.encodePathSegment(params.id);
    if(Object.prototype.hasOwnProperty.call(params, 'version'))
        request.addQueryParameter('version', params.version);
    if(params.$entity)
        request.setEntity(params.$entity);
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
    if(params.$contentType)
        request.setContentType(params.$contentType);
    else
        request.setContentType('application/json');
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
var IRestEditor = {};
// GET /editor/{path:.*}
IRestEditor.getEditor = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/editor/';
    uri += REST.Encoding.encodePathSegment(params.path);
    if(Object.prototype.hasOwnProperty.call(params, 'lang'))
        request.addQueryParameter('lang', params.lang);
    if(Object.prototype.hasOwnProperty.call(params, 'loc'))
        request.addQueryParameter('loc', params.loc);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('text/html');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// DELETE /conversationstore/conversations/{conversationId}
IRestMonitorStore.deleteConversationLog = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('DELETE');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/conversationstore/conversations/';
    uri += REST.Encoding.encodePathSegment(params.conversationId);
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
// GET /conversationstore/conversations/{conversationId}
IRestMonitorStore.readConversationLog = function(_params){
    var params = _params ? _params : {};
    var request = new REST.Request();
    request.setMethod('GET');
    var uri = params.$apiURL ? params.$apiURL : REST.apiURL;
    uri += '/conversationstore/conversations/';
    uri += REST.Encoding.encodePathSegment(params.conversationId);
    request.setURI(uri);
    if(params.$username && params.$password)
        request.setCredentials(params.$username, params.$password);
    if(params.$accepts)
        request.setAccepts(params.$accepts);
    else
        request.setAccepts('application/json');
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
// GET /outputstore/outputsets/{id}/currentversion
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
    if (REST.antiBrowserCache == true) {
        request.addQueryParameter('resteasy_jsapi_anti_cache', (new Date().getTime()));
        var cached_obj = REST._get_cache_signature(REST._generate_cache_signature(uri));
        if (cached_obj != null) { request.addHeader('If-Modified-Since', cached_obj[1]['Last-Modified']); request.addHeader('If-None-Match', cached_obj[1]['Etag']);}
    }
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
