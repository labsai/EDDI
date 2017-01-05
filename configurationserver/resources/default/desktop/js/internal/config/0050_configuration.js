function Configuration(url) {
    /** Defines the identifier of the default page to be loaded at application start. */
    this.defaultPage = 'bots';

    /** Define the default languagepack. */
    this.defaultLanguageKey = 'en';
    this.defaultLocationKey = 'US';

    /**
     *  The current language is the default language,
     *  unless the query parameters('lang' and 'loc') explicitly call for a different languagepack.
     */
    var lang = this.defaultLanguageKey;
    var loc = this.defaultLocationKey;
    var urlParams = $.url.parse(window.location.href).params;
    if (typeof urlParams !== 'undefined') {
        lang = urlParams['lang'];
        if (typeof lang !== 'undefined') {
            lang = decodeURIComponent(lang);
        } else {
            lang = this.defaultLanguageKey;
        }

        loc = urlParams['loc'];
        if (typeof loc !== 'undefined') {
            loc = decodeURIComponent(loc);
        } else {
            loc = this.defaultLocationKey;
        }
    }

    /** Defines the currently active languagepack. */
    this.languageKey = lang;
    this.locationKey = loc;

    /** Defines the available languages. => Should have a rest interface. */
    this.languageSet = ["en / US", "de / AT"];

    this.botParentIdHashKey = 'botId';
    this.botParentVersionHashKey = 'botVersion';
    this.packageParentIdHashKey = 'packageId';
    this.packageParentVersionHashKey = 'packageVersion';

    if(url.getCurrentPage() == "dictionaries" ||
       url.getCurrentPage() == "outputs") {
        this.newStateClassName = 'isNewTableRow';
        this.editedStateClassName = 'hasChangesTableRow';
    } else {
        this.newStateClassName = 'isNew';
        this.editedStateClassName = 'hasChanges';
    }

    /** Group name for Behaviour Rule Extensions that have no group associated to them. */
    this.lostAndFoundGroupName = 'lost_and_found';

    /** ResultSize extension slider limit */
    this.sliderMaxResultSize = 50;

    /** Time without action in miliseconds to delay the synchronisation with the server when using the slider control. */
    this.sliderSynchronisationDelayMilis = 1500;

    /**
     * HTML id attribute prefix for elements that are part of a control entity. (E.g. .sortable()-div's inside group controls).
     * This prefix in the id-attribute is used to find the corresponding entities for the html elements in jquery events.
     * Make sure you don't prefix any of your other html id's with this referencePrefix.
     * */
    this.referencePrefix = 'ref_';

    /**
     * HTML id attribute prefix for loading indicators.
     */
    this.loadingIndicatorPrefix = 'loading_';

    this.rootElementReceiver = 'root_element';

    /**
     * Command property 'sortableLevel' for movements on the package level.
     *
     * @type {String}
     */
    this.packageOuterSortableLevel = 'PackageOuter';

    /**
     * Command property 'sortableLevel' for movements on the sub-package level.
     *
     * @type {String}
     */
    this.packageInnerSortableLevel = 'PackageInner';

    /**
     * Stores the plugins that handle behavior rule extensions.
     * To add your own behavior rule extension handler extend the __behaviorruleextensionhandlers__
     * object in the following format:
     *
     * ${HANDLED_EXTENSION_TYPE} : {model: ${PLUGIN_MODEL_CLASS_NAME}, control: ${PLUGIN_CLASS_NAME}}
     *
     * , where * ${HANDLED_EXTENSION_TYPE} is the corresponding type identification from the REST-Service reply.
     *         * ${PLUGIN_MODEL_CLASS_NAME} is the name(a reference) to your controls' model.
     *           Make sure the objects' file is imported in the root-HTML file.
     *
     *   NOTE: The plugins' model must implement the following interface:
     *         PLUGIN_MODEL_CLASS_NAME(BehaviorRuleExtension): Constructor initializing the controls' model with the
     *                                                         corresponding BehaviorRuleExtension object of type
     *                                                         ${HANDLED_EXTENSION_TYPE}
     *                                                         NOTE: If no BehaviorRuleExtension is provided,
     *                                                               a valid, default-initialized model must be returned.
     *
     *         * ${PLUGIN_CLASS_NAME} is the name(a reference) to your controls' class.
     *           Make sure the objects' file is imported in the root-HTML file.
     *
     *   NOTE: The plugin class must implement the following interface:
     *         PLUGIN_CLASS_NAME(PLUGIN_MODEL_CLASS_NAME): Constructor initializing the control with your model.
     *         String createRepresentation(void): Method returning the controls' HTML representation.
     *         Model getModel(void): Method returning the controls' model.
     *         int getHeight(void): Method returning the controls' final height, including margin and padding.
     *         void registerButtonEvents(void): After DOM-changes DOM-events must be re-registered. Register any events
     *                                          you wish to handle here. DO NOT register them in __createRepresentation__,
     *                                          as it is called only once at initialization-time.
     *                                          This method will be called at the appropriate times.
     *
     *
     * @type {Object}
     */
    this.behaviorruleextensionhandlers =
    {
        "connector":{ model:ConnectorModel, control:ConnectorControl },
        "occurrence":{ model:OccurrenceModel, control:OccurrenceControl },
        "inputmatcher":{ model:InputmatcherModel, control:InputmatcherControl },
        "negation":{ model:NegationModel, control:NegationControl },
        "dependency":{ model:DependencyModel, control:DependencyControl },
        "resultSize":{ model:ResultSizeModel, control:ResultSizeControl },
        "outputReference":{ model:OutputReferenceModel, control:OutputReferenceControl }
    };

    /**
     * Stores the plugins that handle lifecycle tasks.
     * To add your own behavior rule extension handler extend the __lifecycletaskhandlers__
     * object in the following format:
     *
     * @see behaviorruleextensionhandlers
     *
     * @type {Object}
     */
    this.lifecycletaskhandlers =
    {
        /**
         * NOTE: Only add lifecycletaskhandlers if you want to implement custom UI for a lifecycle task. If there is no
         * plugin available for a received lifecycle task type, it will be dynamically created using its ExtensionDefinition
         * object obtained from the REST-Service.
         *
         * The controls below are deprecated and have been replaced by the generic lifecyclecontrol.
         */
        /**"core://io.sls.ai.labs.parser?version=1":{ model:SentenceparserLifecycleModel, control:SentenceparserLifecycleControl },
         "core://ai.labs.behavior?version=1"       : { model:BehaviorLifecycleModel, control:BehaviorLifecycleControl },
         "core://ai.labs.output?version=1"         : { model:OutputLifecycleModel, control:OutputLifecycleControl },
         "core://io.sls.ai.labs.parser.dictionaries.integer?version=1":{model:IntegerModel, control:IntegerControl},
         "core://io.sls.ai.labs.parser.dictionaries.regular?version=1":{model:RegularModel, control:RegularControl},
         "core://io.sls.ai.labs.parser.corrections.levenshtein?version=1":{model:LevenshteinModel, control:LevenshteinControl},
         "core://io.sls.ai.labs.parser.corrections.phonetic?version=1":{model:PhoneticModel, control:PhoneticControl}*/
    }

    /* Table Controls */
    this.tableControlDefaultLengthValues = ['10', '25', '50', '100'];
}