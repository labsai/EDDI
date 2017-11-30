function SLSConfiguratorApplication() {
    this.url = new URLManipulator();
    this.configuration = new Configuration(this.url);

    this.initializedNavigation = false;

    let instance = this;
    let continueStartup = function (httpCode, xmlHttpRequest, value) {
        if (instance.httpCodeManager.successfulRequest(httpCode)) {
            let language = application.configuration.languageKey;
            let location = application.configuration.locationKey;
            let languageIdentifier = language + '_' + location;

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
            let headerModel = instance.headerModelProvider.getHeaderModel();

            instance.headerBuilder.buildHeader(headerModel);

            /** Setup the content. */
            let contentModel = instance.contentModelProvider.makeContentModel(instance.contentBuilder.observer,
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
    };

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
        let url = currUrl;
        let newAdditionalURL = "";
        let tempArray = url.split("?");
        let baseURL = tempArray[0];
        let additionalURL = tempArray[1];
        let temp = "";

        if (additionalURL) {
            let tempArray = additionalURL.split("&");
            for (let i = 0; i < tempArray.length; i++) {
                if (tempArray[i].split('=')[0] !== param) {
                    newAdditionalURL += temp + tempArray[i];
                    temp = "&";
                }
            }
        }
        let rows_txt = temp + "" + param + "=" + paramVal;
        return baseURL + "?" + newAdditionalURL + rows_txt;
    };

    this.getParam = function (variable) {
        let query = window.location.search.substring(1);
        let vars = query.split("&");

        for (let i = 0; i < vars.length; i++) {
            let pair = vars[i].split("=");

            if (pair[0] === variable) {
                return pair[1];
            }
        }

        return false;
    };

    this.serializeAnchors = function () {
        let anchors = $.param.fragment();

        let retVal = {};

        if (anchors === "") {
            return retVal;
        }

        let KVList = anchors.split('&');

        for (let i = 0; i < KVList.length; ++i) {
            let elem = KVList[i];
            let kv = elem.split('=');
            let key = kv[0];
            let value = kv[1];

            retVal[key] = decodeURIComponent(value);
        }

        return retVal;
    };

    let getQueryParts = function (href) {
        let query = $.url.parse(href);
        let path = query.path;

        let parts = path.split("/");

        let page;
        let id;
        let version;

        page = typeof parts[2] !== 'undefined' ? decodeURIComponent(parts[2]) : page;
        id = typeof parts[3] !== 'undefined' ? decodeURIComponent(parts[3]) : id;
        version = typeof query.params !== 'undefined' ? query.params['version'] : version;

        return {page: page, id: id, version: version};
    };

    let getCurrentQueryParts = function () {
        return getQueryParts(window.location.href);
    };

    this.getCurrentPage = function () {
        return getCurrentQueryParts().page;
    };

    this.getCurrentId = function () {
        return getCurrentQueryParts().id;
    };

    this.getCurrentVersion = function () {
        return getCurrentQueryParts().version;
    };

    this.deleteParamsExceptLanguage = function (parameters) {
        //remember location parameters if exists
        let languageParam;
        let locationParam;
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
    };

    this.getUriForPage = function (pagename) {
        let uriObject = $.url.parse(window.location.href);

        delete uriObject.relative;
        delete uriObject.source;
        delete uriObject.directory;
        delete uriObject.query;
        delete uriObject.anchor;

        uriObject.params = this.deleteParamsExceptLanguage(uriObject.params);

        let path = uriObject.path;

        let parts = path.split("/");
        parts[2] = pagename;

        let newPath = '';
        for (let i = 0; i <= 2; i++) {
            if (i !== 0) {
                newPath += '/';
            }
            newPath += parts[i];
        }
        uriObject.path = newPath;

        return $.url.build(uriObject);
    };

    this.getResourceParams = function (href) {
        let queryParts = getQueryParts(href);

        return {id: queryParts.id, version: queryParts.version};
    };

    this.getUriForResource = function (id, version) {
        let uriObject = $.url.parse(window.location.href);

        delete uriObject.relative;
        delete uriObject.source;
        delete uriObject.directory;

        let path = uriObject.path;

        let parts = path.split("/");
        parts[3] = id;

        let newPath = parts.join("/");
        uriObject.path = newPath;

        delete uriObject.query;

        uriObject.params = this.deleteParamsExceptLanguage(uriObject.params);

        if (typeof version !== 'undefined') {
            uriObject.params['version'] = version;
        }

        return $.url.build(uriObject);
    };

    this.updateVersion = function (uri, value) {
        let uriObject = $.url.parse(uri);

        delete uriObject.relative;
        delete uriObject.source;
        delete uriObject.directory;
        delete uriObject.query;

        uriObject.params = this.deleteParamsExceptLanguage(uriObject.params);

        uriObject.params.version = value;

        return $.url.build(uriObject);
    };

    this.getEditorUriForResourceUri = function (resourceUri) {
        let uriObject = $.url.parse(window.location.href);
        let resourceObject = $.url.parse(resourceUri);
        let resource = SLSUriParser(resourceUri);

        delete uriObject.relative;
        delete uriObject.source;
        delete uriObject.directory;

        let path = uriObject.path;

        let parts = path.split("/");
        parts[3] = resource.id;

        let editorScreen;
        switch (resourceObject.host) {
            case 'ai.labs.package':
                editorScreen = 'packages';
                break;
            case 'ai.labs.bot':
                editorScreen = 'bots';
                break;
            case 'ai.labs.behavior':
                editorScreen = 'dialogs';
                break;
            case 'ai.labs.output':
                editorScreen = 'outputs';
                break;
            case 'ai.labs.regulardictionary':
                editorScreen = 'dictionaries';
                break;
            default:
                throw 'Cant find editor URI for host ' + resourceObject.host + '.';
        }
        parts[2] = editorScreen;

        let newPath = parts.join("/");
        uriObject.path = newPath;

        delete uriObject.query;
        uriObject.params = this.deleteParamsExceptLanguage(uriObject.params);

        uriObject.params.version = resource.version;
        uriObject.anchor = resourceObject.anchor;

        return $.url.build(uriObject);
    };

    this.getVersionAndIdForResource = function (resource) {
        let query = $.url.parse(resource);
        let id = query.path.split('/')[query.path.split('/').length - 1];
        let version = query.params.version;

        return {id: id, version: version};
    }
}

function MalformedURLException(msg) {
    this.message = msg;
}

/** Bypass logging errors with browsers that don't have a console object. */
if (typeof console === 'undefined') {
    let console = {
        log: function () {
        }
    };
}

let application;

function initApplication() {
    application = new SLSConfiguratorApplication();

    let page;
    try {
        page = application.url.getCurrentPage();
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
    initApplication();
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