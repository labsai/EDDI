function ContentModelHelper() {
    this.createResourceVersionSelectorControl = function () {
        let uri = application.dataProvider.readActiveDocumentDescription().resource;

        let params = SLSUriParser(uri);

        let maxVersion = application.dataProvider.getCurrentVersionForResource(uri);

        let headerIdPrefix = params.id + 'header_';
        let headerControlModel = new VersionSelectorControlModel(params.id, headerIdPrefix, 'headercontrol',
            ([]).arrayWithRange(1, maxVersion), params.version, uri, true, false);
        let headerControl = new VersionSelectorControl(headerControlModel);

        headerControl.observable.addObserver(application.actionHandler.observer);

        let resourceVersionControl = new ResourceVersionControl(headerControl);
        $('.contextbutton:last').after(resourceVersionControl.createRepresentation());

        $('.resourceversion').hide().fadeIn('slow');

        resourceVersionControl.registerButtonEvents();
    };

    this.createDocumentDescriptorDisplayControl = function () {
        let docDisplayControl = new DocumentDescriptorDisplayControl(application.dataProvider.readActiveDocumentDescription());
        $('.loginbox').before(docDisplayControl.createRepresentation());
        $('.documentdescriptorui').hide().fadeIn('slow');

        docDisplayControl.registerButtonEvents();
    };

    this.createReturnToParentButton = function () {
        let anchorParams = application.url.serializeAnchors();

        let returnToParentCM = null;

        /**
         * Note: The order here matters.
         *
         * Package anchor parameters must be evaluated before bot anchor parameters,
         * because if both exist, package parameters must be handled first.
         */
        if (anchorParams.hasOwnProperty(application.configuration.packageParentIdHashKey) &&
            anchorParams.hasOwnProperty(application.configuration.packageParentVersionHashKey)) {
            returnToParentCM = new ReturnToParentControlModel('returntoparent',
                'packages',
                anchorParams[application.configuration.packageParentIdHashKey],
                anchorParams[application.configuration.packageParentVersionHashKey]
            );
        } else if (anchorParams.hasOwnProperty(application.configuration.botParentIdHashKey) &&
            anchorParams.hasOwnProperty(application.configuration.botParentVersionHashKey)) {
            returnToParentCM = new ReturnToParentControlModel('returntoparent',
                'bots',
                anchorParams[application.configuration.botParentIdHashKey],
                anchorParams[application.configuration.botParentVersionHashKey]
            );
        }

        if (returnToParentCM !== null) {
            let returnToParentControl = new ReturnToParentControl(returnToParentCM);

            $('.loginbox').before(returnToParentControl.createRepresentation());
            $('.' + returnToParentControl.getModel().cssClassBase).hide().show("slide", {direction: "left"}, 1000);

            returnToParentControl.registerButtonEvents();
        }
    };

    this.createLanguageSelector = function () {
        let languageSelector = new LanguageSelectorControl();

        if ($('.resourceversion').exists()) {
            $('.resourceversion').after(languageSelector.createRepresentation());
        } else {
            $('.contextbutton:last').after(languageSelector.createRepresentation());
        }
        languageSelector.registerButtonEvents();
    }
}