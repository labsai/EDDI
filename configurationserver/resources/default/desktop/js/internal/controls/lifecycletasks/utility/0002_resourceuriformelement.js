function ResourceURIFormElement(cssClassBase, inPlaceEdited, key, type, displayKey, currentValue, modelOrigin) {
    var id = application.dataProvider.getNextIdGlobal();
    var displayNamePostfix = '_displayname';
    var dropdownResourcePostfix = '_dropdown_resource';
    var dropdownVersionPostfix = '_dropdown_version';
    var editorPostfix = '_editoricon';
    var addButtonPostfix = '_addbutton';
    var containerPostfix = '_container';
    var placeholderPostfix = '_placeholder';
    var addContainerPostfix = '_addcontainer';

    var instance = this;

    var typeHasEditor = function (type) {
        return true;
    }

    this.observable = new Observable();

    var filter = type.split('//')[1];

    var fetchDescriptions = function(pfilter) {
        return application.dataProvider.readDocumentDescriptions(pfilter, 0, 0, '', 'asc');
    }

    var behaviorDescriptions = fetchDescriptions(filter);

    if (currentValue == "") {
        if (behaviorDescriptions.length > 0) {
            currentValue = behaviorDescriptions[0].resource;
        }
    }

    var getNameForResource = function (resource) {
        for (var i = 0; i < behaviorDescriptions.length; ++i) {
            if (behaviorDescriptions[i].resource.split('?')[0] == resource) {
                return behaviorDescriptions[i].name;
            }
        }
    }

    var getResourceForName = function (name) {
        for (var i = 0; i < behaviorDescriptions.length; ++i) {
            if (behaviorDescriptions[i].name == name) {
                return behaviorDescriptions[i].resource;
            }
        }
    }

    var getPossibleResources = function () {
        var retVal = [];

        for (var i = 0; i < behaviorDescriptions.length; ++i) {
            retVal.push(behaviorDescriptions[i].name);
        }

        return retVal;
    }

    var getPossibleVersions = function (resource) {
        var retVal = [];

        var maxVersion;
        for (var i = 0; i < behaviorDescriptions.length; ++i) {
            if (behaviorDescriptions[i].resource.split('?')[0] == resource) {
                maxVersion = $.url.parse(behaviorDescriptions[i].resource).params.version;
            }
        }

        for (var i = 0; i < maxVersion; ++i) {
            retVal.push(i + 1);
        }

        return retVal;
    }

    var iconCSSClassPostfix = '_icon';
    var textCSSClassPostfix = '_text';

    var makeAnchorTags = function() {
        var page = application.url.getCurrentPage();

        var isParentPage = function() {
            if(page == 'bots' || page == 'packages') {
                return true;
            }

            return false;
        };

        if(isParentPage()) {
            var parentIdHashKey, parentVersionHashKey;

            if(page == 'bots') {
                parentIdHashKey = application.configuration.botParentIdHashKey;
                parentVersionHashKey = application.configuration.botParentVersionHashKey;
            } else if(page == 'packages') {
                parentIdHashKey = application.configuration.packageParentIdHashKey;
                parentVersionHashKey = application.configuration.packageParentVersionHashKey;
            }

            var anchors = '#'
                + parentIdHashKey + '=' + application.url.getCurrentId()
                + '&' + parentVersionHashKey + '=' + application.url.getCurrentVersion()
                + '&' + $.param.fragment();

            return anchors;
        } else {
            return "";
        }
    }

    var makeAnchorUrl = function(uri) {
        return application.url.getEditorUriForResourceUri(uri) + makeAnchorTags();
    }

    var getResourceUrl = function() {
        var uri = instance.getModel().resourceUri;
        try {
            return makeAnchorUrl(uri);
        } catch(ex) {
            /** Handle empty or malformed resource URI. */
            if(ex instanceof MalformedURLException) {
                var possibleValues = getPossibleResources();

                if(possibleValues.length > 0) {
                    /** Case malformed. */
                    var resource = getResourceForName(possibleValues[0]);
                    var possibleVersions = getPossibleVersions(resource.split('?')[0]);

                    currentValue = resource.split('?')[0] + '?version=' + possibleVersions[0];

                    return makeAnchorUrl(currentValue);
                } else {
                    /** Case non-existant. */
                    throw new MalformedURLException("No values to display for resourceURI control.");
                }
            } else {
                /** Propagate other exception types. */
                throw ex;
            }
        }
    }

    var makeSelectionControlRepresentation = function() {
        var representation = '<div id="' + cssClassBase + dropdownResourcePostfix + id + '" class="' + cssClassBase + dropdownResourcePostfix + '"></div>';

        representation += '<div id="' + cssClassBase + dropdownVersionPostfix + id + '" class="' + cssClassBase + dropdownVersionPostfix + '"></div>';

        if (typeHasEditor(type)) {
            representation += '<a href="' + getResourceUrl() + '"><span style="display:block" id="' + cssClassBase + editorPostfix + id + '" class="' + cssClassBase + editorPostfix + '">';
            representation += '<div class="' + cssClassBase + iconCSSClassPostfix + '"></div>\
                              <span class="' + cssClassBase + textCSSClassPostfix + '">' + window.lang.convert('RESOURCE_LINK') + '</span>\
                              <div class="clear"></div></span></a>';
        }

        return representation;
    }

    this.createRepresentation = function () {
        var representation = '<div id="' + cssClassBase + containerPostfix + id + '" class="' + cssClassBase + containerPostfix + '">';
        representation += '<div class="' + cssClassBase + displayNamePostfix + '">' + window.lang.convert(displayKey) + '</div>';

        var possibleValues = getPossibleResources();

        /** Only display the selection control if there is actually resources available. */
        if(possibleValues.length > 0) {
            representation += makeSelectionControlRepresentation();
        } else {
            representation += '<div id="' + cssClassBase + placeholderPostfix + id + '" class="' + cssClassBase + placeholderPostfix + '">'
               + '</div>';
        }

        representation += '<div id="' + cssClassBase + addButtonPostfix + id + '" class="' + cssClassBase + addButtonPostfix + '"></div>';

        representation += '<div class="clear"></div></div>';

        return representation;
    }

    var currentResource = currentValue.split('?')[0];
    var currentVersion = $.url.parse(currentValue).params.version;

    var resourceCreationControl = null;
    var showAddMenu = function() {
        var resourceCreationModel = new ResourceCreationModel(id,
            'resourcecreation_',
            'resourcecreation',
            type,
            function(success, autoUpdate){
                if(success) {
                    if(resourceCreationControl.getModel().currentValue != "") {
                        var last = type.split('.').last();

                        var newUri;
                        /** Create a new resource. */
                        switch (last) {
                            case 'bot':
                                newUri = application.dataProvider.createBot(application.jsonBlueprintFactory.makeBlueprintForObjectType('Bot'));
                                break;
                            case 'package':
                                newUri = application.dataProvider.createPackage(application.jsonBlueprintFactory.makeBlueprintForObjectType('Package'));
                                break;
                            case 'output':
                                newUri = application.dataProvider.createOutputSet(application.jsonBlueprintFactory.makeBlueprintForObjectType('OutputConfigurationSet'));
                                break;
                            case 'behavior':
                                newUri = application.dataProvider.createBehaviorRuleSet(application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorRuleConfigurationSet'));
                                break;
                            case 'regulardictionary':
                                newUri = application.dataProvider.createRegularDictionary(application.jsonBlueprintFactory.makeBlueprintForObjectType('RegularDictionaryConfiguration'));
                                break;
                            default:
                                break;
                        }

                        var patch = application.jsonBlueprintFactory.makeBlueprintForObjectType('PatchInstruction');

                        var params = SLSUriParser(newUri);

                        patch.document = application.dataProvider.readDocumentDescription(params.id, params.version);
                        patch.document.name = resourceCreationControl.getModel().currentValue;

                        $('#' + cssClassBase + containerPostfix + id).showLoadingIndicator();
                        application.dataProvider.patchDocumentDescription(params.id,
                            params.version,
                            patch,
                            function(httpCode) {
                                if(!application.httpCodeManager.successfulRequest(httpCode)) {
                                    var dcm = new DialogControlModel(window.lang.convert('ERROR_CREATE_RESOURCE'), function() {},
                                        window.lang.convert("OK_BUTTON"));
                                    var dc = new DialogControl(dcm);
                                    dc.showDialog();
                                } else {
                                    behaviorDescriptions = fetchDescriptions(filter);

                                    if(autoUpdate) {
                                        currentValue = getResourceForName(resourceCreationControl.getModel().currentValue);
                                        currentResource = currentValue.split('?')[0];
                                        currentVersion = $.url.parse(currentValue).params.version;
                                    }

                                    updateUriInPlace();

                                    $('#' + cssClassBase + containerPostfix + id).replaceWith(instance.createRepresentation());
                                    instance.registerButtonEvents();
                                }

                                $('#' + cssClassBase + containerPostfix + id).hideLoadingIndicator();
                            });
                    } else {
                        var dcm = new DialogControlModel(window.lang.convert('ERROR_NO_RESOURCE_NAME'), function() {},
                            window.lang.convert("OK_BUTTON"));
                        var dc = new DialogControl(dcm);
                        dc.showDialog();
                    }
                }

                hideAddMenu();

                $('#' + cssClassBase + addButtonPostfix + id).click(function() {
                    $('#' + cssClassBase + addButtonPostfix + id).unbind('click');
                    showAddMenu();
                });
        }, true);
        resourceCreationControl = new ResourceCreationControl(resourceCreationModel);

        $('#' + cssClassBase + containerPostfix + id).after(resourceCreationControl.createRepresentation());
        resourceCreationControl.registerButtonEvents();

        $('#' + cssClassBase + addButtonPostfix + id).fadeOut();

        $('#' + resourceCreationControl.getModel().idPrefix + resourceCreationControl.getModel().id).hide().animate({
            height:'toggle'
        }, {
            duration:500
        });
    }

    var hideAddMenu = function() {
        $('#' + cssClassBase + addButtonPostfix + id).fadeIn();

        $('#' + resourceCreationControl.getModel().idPrefix + resourceCreationControl.getModel().id).animate({
            height:'toggle'
        }, {
            duration:500
        });
    }

    var updateUriInPlace = function() {
        modelOrigin[key] = instance.getModel().resourceUri;

        instance.observable.notify(new Event(this, 'UpdatedModel'));
    }

    this.getModel = function() {
        return {
            resourceUri: currentResource + '?version=' + currentVersion,
            anchors: makeAnchorTags()
        };
    }

    updateUriInPlace();

    this.registerButtonEvents = function () {
        currentResource = currentValue.split('?')[0];
        currentVersion = $.url.parse(currentValue).params.version;

        $('#' + cssClassBase + addButtonPostfix + id).click(function() {
            $('#' + cssClassBase + addButtonPostfix + id).unbind('click');
            showAddMenu();
        });

        $('#' + cssClassBase + dropdownResourcePostfix + id).dropdown({
            value:getNameForResource(currentResource),
            possibleValues:getPossibleResources(),
            valueChanged:function (value, oldValue) {
                /** Keep model up-to-date. */
                currentResource = getResourceForName(value).split('?')[0];

                var versions = getPossibleVersions(currentResource);
                currentVersion = versions[versions.length - 1];

                if(inPlaceEdited) {
                    updateUriInPlace();
                }

                $('#' + cssClassBase + dropdownVersionPostfix + id).dropdown({
                    value:currentVersion,
                    possibleValues:versions.reverse(),
                    valueChanged:function (value, oldValue) {
                        /** Keep model up-to-date. */
                        currentVersion = value;

                        if(inPlaceEdited) {
                            updateUriInPlace();
                        }
                    }
                });
            }
        });

        $('#' + cssClassBase + dropdownVersionPostfix + id).dropdown({
            value:currentVersion,
            possibleValues:getPossibleVersions(currentResource).reverse(),
            valueChanged:function (value, oldValue) {
                /** Keep model up-to-date. */
                currentVersion = value;

                if(inPlaceEdited) {
                    updateUriInPlace();
                }
            }
        });

        if (typeHasEditor(type)) {
            $('#' + cssClassBase + editorPostfix + id).click(function () {
                instance.observable.notify(new Event(instance, 'GotoVersion'));
                return false;
            });
        }
    }

    this.getKey = function () {
        return key;
    }

    this.getValue = function () {
        return currentResource + '?version=' + currentVersion;
    }
}