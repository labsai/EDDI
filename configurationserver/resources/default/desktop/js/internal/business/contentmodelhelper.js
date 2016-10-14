function ContentModelHelper() {
    this.createResourceVersionSelectorControl = function() {
        var uri = application.dataProvider.readActiveDocumentDescription().resource;

        var params = SLSUriParser(uri);

        var maxVersion = application.dataProvider.getCurrentVersionForResource(uri);

        var headerIdPrefix = params.id + 'header_';
        var headerControlModel = new VersionSelectorControlModel(params.id, headerIdPrefix, 'headercontrol',
            (new Array).arrayWithRange(1, maxVersion), params.version, uri, true, false);
        var headerControl = new VersionSelectorControl(headerControlModel);

        headerControl.observable.addObserver(application.actionHandler.observer);

        var resourceVersionControl = new ResourceVersionControl(headerControl);
        $('.contextbutton:last').after(resourceVersionControl.createRepresentation());

        $('.resourceversion').hide().fadeIn('slow');

        resourceVersionControl.registerButtonEvents();
    }

    this.createDocumentDescriptorDisplayControl = function() {
        var docDisplayControl = new DocumentDescriptorDisplayControl(application.dataProvider.readActiveDocumentDescription());
        $('.loginbox').before(docDisplayControl.createRepresentation());
        $('.documentdescriptorui').hide().fadeIn('slow');

        docDisplayControl.registerButtonEvents();
    }

    this.createReturnToParentButton = function() {
        var anchorParams = application.url.serializeAnchors();

        var returnToParentCM = null;

        /**
         * Note: The order here matters.
         *
         * Package anchor parameters must be evaluated before bot anchor parameters,
         * because if both exist, package parameters must be handled first.
         */
        if(anchorParams.hasOwnProperty(application.configuration.packageParentIdHashKey) &&
           anchorParams.hasOwnProperty(application.configuration.packageParentVersionHashKey)) {
            returnToParentCM = new ReturnToParentControlModel('returntoparent',
                'packages',
                anchorParams[application.configuration.packageParentIdHashKey],
                anchorParams[application.configuration.packageParentVersionHashKey]
            );
        } else if(anchorParams.hasOwnProperty(application.configuration.botParentIdHashKey) &&
                  anchorParams.hasOwnProperty(application.configuration.botParentVersionHashKey)) {
            returnToParentCM = new ReturnToParentControlModel('returntoparent',
                'bots',
                anchorParams[application.configuration.botParentIdHashKey],
                anchorParams[application.configuration.botParentVersionHashKey]
            );
        }

        if(returnToParentCM != null) {
            var returnToParentControl = new ReturnToParentControl(returnToParentCM);

            $('.loginbox').before(returnToParentControl.createRepresentation());
            $('.' + returnToParentControl.getModel().cssClassBase).hide().show("slide", {direction:"left"}, 1000);

            returnToParentControl.registerButtonEvents();
        }
    }

    this.createLanguageSelector = function() {
        var languageSelector = new LanguageSelectorControl();

        if($('.resourceversion').exists()) {
            $('.resourceversion').after(languageSelector.createRepresentation());
        } else {
            $('.contextbutton:last').after(languageSelector.createRepresentation());
        }
        languageSelector.registerButtonEvents();
    }
}