function SLSConfiguratorApplication() {
    this.url = new URLManipulator();
    this.configuration = new Configuration(this.url);

    this.initializedNavigation = false;

    var instance = this;
    var continueStartup = function (httpCode, xmlHttpRequest, value) {
        if (instance.httpCodeManager.successfulRequest(httpCode)) {
            var language = application.configuration.languageKey;
            var location = application.configuration.locationKey;
            var languageIdentifier = language + '_' + location;

            //jquery_lang_js.prototype.lang[languageIdentifier] = jQuery.parseJSON(value);

            /** Setup jquery-lang. */
            window.lang = new jquery_lang_js();
            window.lang.run();

            /** Set active language. */
            window.lang.change(languageIdentifier);

            /** Setup navigation. */
            if (!instance.initializedNavigation) {
                instance.navigationManager.initialize();
                instance.initializedNavigation = true;
            }

            /** Setup login information */
            instance.authenticationInformation.printLoginHeader();
            instance.authenticationInformation.printLoginInformation();

            $.editable.addInputType('autocomplete', {
                element: $.editable.types.text.element,
                plugin: function (settings, original) {
                    $('input', this).autocomplete(settings.autocomplete.options);
                }
            });

            /** Setup the context menu. */
            instance.headerModelProvider.observable.addObserver(instance.actionHandler.observer);
            var headerModel = instance.headerModelProvider.getHeaderModel();

            instance.headerBuilder.buildHeader(headerModel);

            /** Setup the content. */
            var contentModel = instance.contentModelProvider.makeContentModel(instance.contentBuilder.observer,
                instance.contentBuilder.observer);

            instance.contentBuilder.observable.addObserver(instance.actionHandler.observer);
            instance.contentBuilder.buildContent(contentModel);

            instance.contentBuilder.registerEvents();
            instance.headerBuilder.registerEvents();

            /** Display the page. */
            $('#right').hideLoadingIndicator(); //fadeIn();
        } else {
            /** TODO: Present an error. */
        }
    }

    /**
     * The application entry point.
     */
    this.applicationStarted = function (page) {
//        console.log('Started application with page identifier: ' + page + '.');

        /** Hide the page during the initialization phase. */
        $('#right').showLoadingIndicator();// hide();

        /** Initialize screen-independent components. */
        this.pluginManager = new PluginManager();

        /** Load plugins from config file. */
        this.pluginManager.plugins.behaviorruleextensionhandlers = this.configuration.behaviorruleextensionhandlers;
        this.pluginManager.plugins.lifecycletaskhandlers = this.configuration.lifecycletaskhandlers;

        this.httpCodeManager = new HTTPCodeManager();
        this.bindingManager = new BindingManager();
        this.networkCacheManager = new NetworkCacheManager();
        this.workspaceLockManager = new WorkspaceLockManager();
        this.jsonBuilderHelper = new JSONBuilderHelper();
        this.contentModelHelper = new ContentModelHelper();
        this.reloadManager = new ReloadManager();
        this.expressionHelper = new ExpressionHelper();

        this.errorHelper = new ErrorHelper();

        this.dataProvider = new DataProvider();
        this.navigationManager = new NavigationManager();
        this.authenticationInformation = new AuthenticationInformation();
        this.headerBuilder = new HeaderBuilder();

        this.referenceUpdateManager = new ReferenceUpdateManager();
        this.jsonBlueprintFactory = new JSONBlueprintFactory();

        /** Initialize models for the requested screen. */
        if (typeof this.dataProvider.dataProviderState.getActiveId() === 'undefined' || this.dataProvider.dataProviderState.getActiveId().length <= 0) {
            switch (page) {
                case 'bots':
                case 'packages':
                case 'outputs':
                case 'dialogs':
                case 'dictionaries':
                    this.headerModelProvider = new DocumentDescriptionsHeaderModel(page);
                    this.viewController = new DocumentDescriptionsController();
                    this.contentBuilder = new ContentBuilder(this.viewController);
                    this.actionHandler = new DocumentDescriptionsActionHandler(this.contentBuilder, this.dataProvider, page);
                    this.contentModelProvider = new DocumentDescriptionsContentModel(this.dataProvider, this.actionHandler, page);
                    break;
                case 'monitor':
                    this.headerModelProvider = new MonitorDescriptionsHeaderModel();
                    this.contentBuilder = new ContentBuilder();
                    this.actionHandler = new MonitorDescriptionsActionHandler(this.contentBuilder, this.dataProvider);
                    this.contentModelProvider = new MonitorDescriptionsContentModel(this.dataProvider, this.actionHandler);
                    break;
                case 'testcases':
                    this.headerModelProvider = new TestCaseDescriptionsHeaderModel();
                    this.contentBuilder = new ContentBuilder();
                    this.actionHandler = new TestCaseDescriptionsActionHandler(this.contentBuilder, this.dataProvider);
                    this.contentModelProvider = new TestCaseDescriptionsContentModel(this.dataProvider, this.actionHandler);
                    break;
                default:
                    throw new MalformedURLException('Navigation error: No such page: ' + page);
            }
        } else {
            switch (page) {
                case 'bots':
                    this.headerModelProvider = new BotHeaderModel();
                    this.contentBuilder = new ContentBuilder();
                    this.actionHandler = new BotActionHandler(this.contentBuilder, this.dataProvider);
                    this.contentModelProvider = new BotContentModel(this.dataProvider, this.actionHandler);
                    break;
                case 'packages':
                    this.viewController = new PackageController();
                    this.headerModelProvider = new PackageHeaderModel();
                    this.contentBuilder = new ContentBuilder(this.viewController);
                    this.actionHandler = new PackageActionHandler(this.contentBuilder, this.dataProvider);
                    this.contentModelProvider = new PackageContentModel(this.dataProvider, this.actionHandler);
                    this.viewController.observable.addObserver(this.actionHandler.observer);
                    break;
                case 'outputs':
                    this.headerModelProvider = new OutputHeaderModel();
                    this.contentBuilder = new ContentBuilder();
                    this.actionHandler = new OutputActionHandler(this.contentBuilder, this.dataProvider);
                    this.contentModelProvider = new OutputContentModel(this.dataProvider, this.actionHandler);
                    break;
                case 'dialogs':
                    this.jsonRepresentationManager = new JSONRepresentationManager();
                    this.headerModelProvider = new DialogHeaderModel();
                    this.viewController = new DialogController();
                    this.contentBuilder = new ContentBuilder(this.viewController);
                    this.actionHandler = new DialogActionHandler(this.contentBuilder, this.dataProvider);
                    this.contentModelProvider = new DialogContentModel(this.dataProvider, this.pluginManager, this.contentBuilder, this.actionHandler);
                    this.viewController.observable.addObserver(this.actionHandler.observer);
                    break;
                case 'dictionaries':
                    this.headerModelProvider = new DictionaryHeaderModel();
                    this.contentBuilder = new ContentBuilder();
                    this.actionHandler = new DictionaryActionHandler(this.contentBuilder, this.dataProvider);
                    this.contentModelProvider = new DictionaryContentModel(this.dataProvider, this.actionHandler);
                    break;
                case 'monitor':
                    this.headerModelProvider = new MonitorHeaderModel();
                    this.contentBuilder = new ContentBuilder();
                    this.actionHandler = new MonitorActionHandler(this.contentBuilder, this.dataProvider);
                    this.contentModelProvider = new MonitorContentModel(this.dataProvider, this.actionHandler);
                    break;
                case 'testcases':
                    this.headerModelProvider = new TestCaseHeaderModel();
                    this.contentBuilder = new ContentBuilder();
                    this.actionHandler = new TestCaseActionHandler(this.contentBuilder, this.dataProvider);
                    this.contentModelProvider = new TestCaseContentModel(this.dataProvider, this.actionHandler);
                    break;
//                case 'properties':
//                    this.headerModelProvider = new PropertiesHeaderModel();
//                    this.contentModelProvider = new PropertiesContentModel(this.dataProvider);
//                    this.contentBuilder = new ContentBuilder();
//                    this.actionHandler = new PropertiesActionHandler();
//                    break;
                default:
                    throw new MalformedURLException('Navigation error: No such page: ' + page);
            }
        }

        continueStartup(200);
    }
}

/**
 *  Manipulation of the application URL / GET-Parameters.
 *
 * From: http://stackoverflow.com/questions/1403888/get-url-parameter-with-jquery
 *  and  http://stackoverflow.com/questions/1090948/change-url-parameters-with-jquery
 * */
function URLManipulator() {
    this.updatedURL = function (currUrl, param, paramVal) {
        var url = currUrl
        var newAdditionalURL = "";
        var tempArray = url.split("?");
        var baseURL = tempArray[0];
        var additionalURL = tempArray[1];
        var temp = "";

        if (additionalURL) {
            var tempArray = additionalURL.split("&");
            for (var i = 0; i < tempArray.length; i++) {
                if (tempArray[i].split('=')[0] != param) {
                    newAdditionalURL += temp + tempArray[i];
                    temp = "&";
                }
            }
        }
        var rows_txt = temp + "" + param + "=" + paramVal;
        var finalURL = baseURL + "?" + newAdditionalURL + rows_txt;

        return finalURL;
    }

    this.getParam = function (variable) {
        var query = window.location.search.substring(1);
        var vars = query.split("&");

        for (var i = 0; i < vars.length; i++) {
            var pair = vars[i].split("=");

            if (pair[0] == variable) {
                return pair[1];
            }
        }

        return false;
    }

    this.serializeAnchors = function () {
        var anchors = $.param.fragment();

        var retVal = {};

        if (anchors == "") {
            return retVal;
        }

        var KVList = anchors.split('&');

        for (var i = 0; i < KVList.length; ++i) {
            var elem = KVList[i];
            var kv = elem.split('=');
            var key = kv[0];
            var value = kv[1];

            retVal[key] = decodeURIComponent(value);
        }

        return retVal;
    }

    var getQueryParts = function (href) {
        var query = $.url.parse(href);
        var path = query.path;

        var parts = path.split("/");

        var page;
        var id;
        var version;

        page = typeof parts[2] !== 'undefined' ? decodeURIComponent(parts[2]) : page;
        id = typeof parts[3] !== 'undefined' ? decodeURIComponent(parts[3]) : id;
        version = typeof query.params !== 'undefined' ? query.params['version'] : version;

        return {page: page, id: id, version: version};
    }

    var getCurrentQueryParts = function () {
        return getQueryParts(window.location.href);
    }

    this.getCurrentPage = function () {
        return getCurrentQueryParts().page;
    }

    this.getCurrentId = function () {
        return getCurrentQueryParts().id;
    }

    this.getCurrentVersion = function () {
        return getCurrentQueryParts().version;
    }

    this.deleteParamsExceptLanguage = function (parameters) {
        //remember location parameters if exists
        var languageParam;
        var locationParam;
        if (typeof parameters !== 'undefined') {
            languageParam = parameters["lang"];
            locationParam = parameters["loc"];
        }

        parameters = {};

        if (typeof languageParam !== 'undefined') {
            parameters["lang"] = languageParam;
        }

        if (typeof locationParam !== 'undefined') {
            parameters["loc"] = locationParam;
        }

        return parameters;
    }

    this.getUriForPage = function (pagename) {
        var uriObject = $.url.parse(window.location.href);

        delete uriObject.relative;
        delete uriObject.source;
        delete uriObject.directory;
        delete uriObject.query;
        delete uriObject.anchor;

        uriObject.params = this.deleteParamsExceptLanguage(uriObject.params);

        var path = uriObject.path;

        var parts = path.split("/");
        parts[2] = pagename;

        var newPath = '';
        for (var i = 0; i <= 2; i++) {
            if (i != 0) {
                newPath += '/';
            }
            newPath += parts[i];
        }
        uriObject.path = newPath;

        return $.url.build(uriObject);
    }

    this.getResourceParams = function (href) {
        var queryParts = getQueryParts(href);

        return {id: queryParts.id, version: queryParts.version};
    }

    this.getUriForResource = function (id, version) {
        var uriObject = $.url.parse(window.location.href);

        delete uriObject.relative;
        delete uriObject.source;
        delete uriObject.directory;

        var path = uriObject.path;

        var parts = path.split("/");
        parts[3] = id;

        var newPath = parts.join("/");
        uriObject.path = newPath;

        delete uriObject.query;

        uriObject.params = this.deleteParamsExceptLanguage(uriObject.params);

        if (typeof version !== 'undefined') {
            uriObject.params['version'] = version;
        }

        return $.url.build(uriObject);
    }

    this.updateVersion = function (uri, value) {
        var uriObject = $.url.parse(uri);

        delete uriObject.relative;
        delete uriObject.source;
        delete uriObject.directory;
        delete uriObject.query;

        uriObject.params = this.deleteParamsExceptLanguage(uriObject.params);

        uriObject.params.version = value;

        return $.url.build(uriObject);
    }

    this.getEditorUriForResourceUri = function (resourceUri) {
        var uriObject = $.url.parse(window.location.href);
        var resourceObject = $.url.parse(resourceUri);
        var resource = SLSUriParser(resourceUri);

        delete uriObject.relative;
        delete uriObject.source;
        delete uriObject.directory;

        var path = uriObject.path;

        var parts = path.split("/");
        parts[3] = resource.id;

        var editorScreen;
        switch (resourceObject.host) {
            case 'io.sls.package':
                editorScreen = 'packages';
                break;
            case 'io.sls.bot':
                editorScreen = 'bots';
                break;
            case 'io.sls.behavior':
                editorScreen = 'dialogs';
                break;
            case 'io.sls.output':
                editorScreen = 'outputs';
                break;
            case 'io.sls.regulardictionary':
                editorScreen = 'dictionaries';
                break;
            default:
                throw 'Cant find editor URI for host ' + resourceObject.host + '.';
        }
        parts[2] = editorScreen;

        var newPath = parts.join("/");
        uriObject.path = newPath;

        delete uriObject.query;
        uriObject.params = this.deleteParamsExceptLanguage(uriObject.params);

        uriObject.params.version = resource.version;
        uriObject.anchor = resourceObject.anchor;

        return $.url.build(uriObject);
    }

    this.getVersionAndIdForResource = function (resource) {
        var query = $.url.parse(resource);
        var id = query.path.split('/')[query.path.split('/').length - 1];
        var version = query.params.version;

        return {id: id, version: version};
    }
}

function MalformedURLException(msg) {
    this.message = msg;
}

/** Bypass logging errors with browsers that don't have a console object. */
if (typeof console === 'undefined') {
    var console = {
        log: function () {
        }
    };
}

function initKeycloakAuthentication(onSuccess, onFailure) {
    keycloak = Keycloak(keycloakAdapterConfigUrl);
    /**
     * The default Keycloak JS adapter only provides asynchronous update operation. This is a custom, synchronous version of
     * the update operation. It is required for synchronous restdataprovider calls (there are a lot of them).
     * TODO: we should transform the existing synchronous calls to asynchronous calls
     */
    keycloak.updateTokenSync = function keycloakUpdateSync(minValidity) {
        if (!keycloak.tokenParsed || !keycloak.refreshToken) {
            return false;
        }

        minValidity = minValidity || 5;

        var exec = function () {
            var refreshToken = false;
            if (keycloak.timeSkew == -1) {
                console.info('Skew ' + keycloak.timeSkew);
                refreshToken = true;
                console.info('[KEYCLOAK] Refreshing token: time skew not set');
            } else if (minValidity == -1) {
                refreshToken = true;
                console.info('[KEYCLOAK] Refreshing token: forced refresh');
            } else if (keycloak.isTokenExpired(minValidity)) {
                refreshToken = true;
                console.info('[KEYCLOAK] Refreshing token: token expired');
            }

            if (refreshToken) {
                var params = 'grant_type=refresh_token&' + 'refresh_token=' + keycloak.refreshToken;
                var url = keycloak.getRealmUrl() + '/protocol/openid-connect/token';

                var req = new XMLHttpRequest();
                req.open('POST', url, false);
                req.setRequestHeader('Content-type', 'application/x-www-form-urlencoded');
                req.withCredentials = true;

                if (keycloak.clientId && keycloak.clientSecret) {
                    req.setRequestHeader('Authorization', 'Basic ' + btoa(keycloak.clientId + ':' + keycloak.clientSecret));
                } else {
                    params += '&client_id=' + encodeURIComponent(keycloak.clientId);
                }

                var timeLocal = new Date().getTime();

                var result;
                req.onreadystatechange = function () {
                    if (req.readyState == 4) {
                        if (req.status == 200) {
                            console.info('[KEYCLOAK] Token refreshed');

                            timeLocal = (timeLocal + new Date().getTime()) / 2;

                            var tokenResponse = JSON.parse(req.responseText);

                            keycloak.setToken(tokenResponse['access_token'], tokenResponse['refresh_token'], tokenResponse['id_token'], timeLocal);

                            keycloak.onAuthRefreshSuccess && keycloak.onAuthRefreshSuccess();
                            result = true;
                        } else {
                            console.warn('[KEYCLOAK] Failed to refresh token');

                            keycloak.onAuthRefreshError && keycloak.onAuthRefreshError();
                            result = false;
                        }
                    }
                };

                req.send(params);
                return result;
            } else {
                return true;
            }
        }

        return exec();
    };
    keycloak.init({onLoad: 'login-required'}).success(function (authenticated) {
        if (authenticated) {
            onSuccess();
        } else {
            onFailure();
        }
    }).error(function () {
        onFailure();
    });
}

var application;

function initApplication() {
    application = new SLSConfiguratorApplication();

    try {
        var page = application.url.getCurrentPage();
    } catch (ex) {
        if (ex instanceof MalformedURLException) {
            console.log(ex.message);
            throw ex;
        }
    }

    try {
        application.applicationStarted(page);
    } catch (ex) {
        if (ex instanceof MalformedURLException) {
            /** Handle malformed url by going to the specified index page. */
            application.applicationStarted(application.configuration.defaultPage)
        }
        else {
            /** Propagate other exceptions. */
            console.log(ex);
            throw ex;
        }
    }
}

$(document).ready(function () {
    initKeycloakAuthentication(function() {
        console.log('successfully authenticated!');
        initApplication();
    }, function() {
        console.log('error in authentication');
        initApplication();
    })
});

/* We are using hash's (fragments) for navigation purpose on some sites, therefore the following code is needed. */

// Bind an event to window.onhashchange that, when the history state changes,
// gets the url from the hash and displays either our cached content or fetches
// new content to be displayed.
$(window).bind('hashchange', function (e) {
    // observable pattern not used here, because not one-to-many but one-to-one (only one contentModelProvider)
    if (application.contentModelProvider.urlHashHasChanged) {
        application.contentModelProvider.urlHashHasChanged(application.contentBuilder);
    }
});