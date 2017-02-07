function BotContentModel(dataProvider, actionHandler) {
    var instance = this;

    var firstLevelGroupControlIdPrefix = 'bot_';
    var secondLevelGroupControlIdPrefix = 'package_';

    this.getBotDocumentDescriptionsSelector = function () {
        return DocumentDescriptionControl.getRootElementSelector();
    }

    var botPackages = [];
    this.getBotPackages = function () {
        return botPackages;
    }

    this.getBotPackage = function (htmlId) {
        var parts = htmlId.split(secondLevelGroupControlIdPrefix);
        var botPackageIndex = parts[parts.length - 1];
        return this.getBotPackages()[botPackageIndex];
    }

    var getDeploymentStatus = function (providerInstance, environment, getDeploymentStatusFunc, completion) {
        getDeploymentStatusFunc.apply(providerInstance,
            [environment,
                dataProvider.dataProviderState.getActiveId(),
                dataProvider.dataProviderState.getActiveVersion(),
                completion
            ]);
    }

    var goToBotButton = null;
    var testGoToBotButton = null;
    var testDeployTestButton = null;

    this.contextButtonChangeState = function (id, environment, state) {
        var removeGotoBotIfExists = function () {
            if (goToBotButton != null) {
                $('#contextbuttonid_' + goToBotButton.getModel().id).fadeOut('slow').remove();
                goToBotButton = null;
            }
        };

        var removeTestGotoBotIfExists = function () {
            if (testGoToBotButton != null) {
                $('#contextbuttonid_' + testGoToBotButton.getModel().id).fadeOut('slow').remove();
                testGoToBotButton = null;
            }
        };

        var removeTestDeployTestButtonIfExists = function () {
            if (testDeployTestButton != null) {
                $('#contextbuttonid_' + testDeployTestButton.getModel().id).fadeOut('slow').remove();
                testDeployTestButton = null;
            }
        };

        switch (state) {
            case 'READY':
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-color', 'green');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-image', '');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').removeClass('iconimage_help');

                if (environment == 'restricted') {
                    var headerElement = new HeaderElement(window.lang.convert('CONTEXT_LINK_BOT'), function () {
                        application.headerModelProvider.observable.notify(new Event(this, 'LinkBot'));
                    }, this);

                    var contextButtonModel = application.headerBuilder.createContextButtonModel(headerElement);
                    var contextButton = new ContextButton(contextButtonModel);

                    if (goToBotButton === null) {
                        $('#contextbuttonid_' + id).after(contextButton.createRepresentation());
                    } else {
                        $('#contextbuttonid_' + goToBotButton.getModel().id).replaceWith(contextButton.createRepresentation());
                    }

                    contextButton.registerButtonEvents();

                    goToBotButton = contextButton;
                }

                if (environment == 'test') {
                    var headerElement = new HeaderElement(window.lang.convert('CONTEXT_LINK_BOT_TEST'), function () {
                        application.headerModelProvider.observable.notify(new Event(this, 'LinkBotTest'));
                    }, this);

                    var contextButtonModel = application.headerBuilder.createContextButtonModel(headerElement);
                    var contextButton = new ContextButton(contextButtonModel);

                    if (testGoToBotButton === null) {
                        $('#contextbuttonid_' + id).after(contextButton.createRepresentation());
                    } else {
                        $('#contextbuttonid_' + testGoToBotButton.getModel().id).replaceWith(contextButton.createRepresentation());
                    }

                    contextButton.registerButtonEvents();

                    testGoToBotButton = contextButton;
                }

//                if (environment == 'test') {
//                    var headerElement = new HeaderElement(window.lang.convert('CONTEXT_TEST'), function () {
//                        application.headerModelProvider.observable.notify(new Event(this, 'Test'));
//                    }, this);
//
//                    var contextButtonModel = application.headerBuilder.createContextButtonModel(headerElement);
//                    var contextButton = new ContextButton(contextButtonModel);
//
//                    if (testDeployTestButton === null) {
//                        $('#contextbuttonid_' + id).after(contextButton.createRepresentation());
//                    } else {
//                        $('#contextbuttonid_' + goToBotButton.getModel().id).replaceWith(contextButton.createRepresentation());
//                    }
//
//                    contextButton.registerButtonEvents();
//
//                    testDeployTestButton = contextButton;
//                }
                break;
            case 'IN_PROGRESS':
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-color', 'transparent');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-image', 'url("/binary/default/desktop/images/deployment_indicator.gif")');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-size', '32px 32px');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-repeat', 'no-repeat');
                if (environment == 'restricted') {
                    removeGotoBotIfExists();
                }
                if (environment == 'test') {
                    removeTestGotoBotIfExists();
                }
//                if (environment == 'test') {
//                    removeTestDeployTestButtonIfExists();
//                }
                break;
            case 'NOT_DEPLOYED':
            case 'NOT_FOUND':
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-color', 'gray');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').removeClass('iconimage_help');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-image', '');
                if (environment == 'restricted') {
                    removeGotoBotIfExists();
                }
                if (environment == 'test') {
                    removeTestGotoBotIfExists();
                }
//                if (environment == 'test') {
//                    removeTestDeployTestButtonIfExists();
//                }
                break;
            case 'ERROR':
            default:
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-color', 'red');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').removeClass('iconimage_help');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-image', '');
                if (environment == 'restricted') {
                    removeGotoBotIfExists();
                }
                if (environment == 'test') {
                    removeTestGotoBotIfExists();
                }
//                if (environment == 'test') {
//                    removeTestDeployTestButtonIfExists();
//                }
                break;
        }
    }

    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        var models = [];

        var headerElements = application.headerBuilder.getHeaderElements();

        var deployId, testDeployId;
        $(headerElements).each(function () {
            if (this.getModel().text == window.lang.convert('CONTEXT_DEPLOY')) {
                deployId = this.getModel().id;
            } else if (this.getModel().text == window.lang.convert('CONTEXT_TEST_DEPLOY')) {
                testDeployId = this.getModel().id
            }
        });
        instance.contextButtonChangeState(deployId, 'restricted', 'NOT_DEPLOYED');
        instance.contextButtonChangeState(testDeployId, 'test', 'NOT_DEPLOYED');

        getDeploymentStatus(dataProvider, 'restricted', dataProvider.getDeploymentStatus, function (httpCode, xmlHttpRequest, value) {
            if (httpCode.toString().indexOf('4') == 0) {
                instance.contextButtonChangeState(deployId, 'restricted', 'NOT_DEPLOYED');
            } else {
                instance.contextButtonChangeState(deployId, 'restricted', value);
            }
        });

        getDeploymentStatus(dataProvider, 'test', dataProvider.getDeploymentStatus, function (httpCode, xmlHttpRequest, value) {
            if (httpCode.toString().indexOf('4') == 0) {
                instance.contextButtonChangeState(testDeployId, 'test', 'NOT_DEPLOYED');
            } else {
                instance.contextButtonChangeState(testDeployId, 'test', value);
            }
        });

        var botDesc = dataProvider.readActiveDocumentDescription();


        application.contentModelHelper.createResourceVersionSelectorControl();
        application.contentModelHelper.createDocumentDescriptorDisplayControl();
        application.contentModelHelper.createLanguageSelector();

        var bot = dataProvider.readActiveBot();
        if (typeof bot !== 'undefined' && typeof botDesc !== 'undefined') {
            bot.id = 0;
            var groupControlModel = this.createBotGroupControlModel(botDesc, bot);

            var groupControl = this.createGroupControl(groupControlModel, 'groupcontrol', sizeCallbackInstance);

            for (var j = 0; j < bot.packages.length; ++j) {
                var packageUriObject = SLSUriParser(bot.packages[j]);
                var packageId = decodeURIComponent(packageUriObject.id);
                var packageVersion = decodeURIComponent(packageUriObject.version);

                var packageDesc = dataProvider.readDocumentDescription(packageId, packageVersion);
                if (typeof packageDesc !== 'undefined') {
                    packageDesc.id = botPackages.length;
                    botPackages.push(packageDesc);

                    var packageModel = this.createPackageGroupControlModel(packageDesc, groupControlModel);
                    var packageControl = this.createDocumentDescriptionControl(packageModel, 'documentdescriptioncontrol', sizeCallbackInstance);

                    groupControlModel.addChild(packageControl);
                }
            }
        } else {
            application.errorHelper.showError();
        }

        models.push(groupControl);

        return models;
    }

    this.createBotGroupControlModel = function (botDesc, bot) {
        var footerControls = [];

        var footerModel = new FooterControlModel(bot.id, firstLevelGroupControlIdPrefix + 'footer_', true);
        var footerControl = new FooterControl(footerModel, 'footercontrol');

        footerControls.push(footerControl);

        var headerControls = [];

        var gcm = new GroupControlModel(bot.id, firstLevelGroupControlIdPrefix, botDesc.name,
            footerControls, false, true, null, false, false);

        gcm.headerControls = headerControls;

        return gcm;
    }

    this.createPackageGroupControlModel = function (packageDesc, parentControlModel) {
        var footerControls = [];

        var footerModel = new FooterControlModel(packageDesc.id, parentControlModel.idPrefix + parentControlModel.id + '_' + secondLevelGroupControlIdPrefix + 'footer_', false);
        var footerControl = new FooterControl(footerModel, 'footercontrol');

        footerControls.push(footerControl);

        var headerControls = [];

        var uri = packageDesc.resource;

        var params = SLSUriParser(uri);

        var maxVersion = dataProvider.getCurrentVersionForResource(uri);

        var headerIdPrefix = parentControlModel.idPrefix + parentControlModel.id + '_' + secondLevelGroupControlIdPrefix + 'header_';
        var headerControlModel = new VersionSelectorControlModel(packageDesc.id, headerIdPrefix, 'headercontrol',
            (new Array).arrayWithRange(1, maxVersion), params.version, uri, true, true);
        var headerControl = new VersionSelectorControl(headerControlModel);

        headerControl.observable.addObserver(actionHandler.observer);
        headerControls.push(headerControl);

        return new DocumentDescriptionControlModel(packageDesc.id, parentControlModel.idPrefix + parentControlModel.id + '_' + secondLevelGroupControlIdPrefix,
            packageDesc.name, packageDesc.description, headerControls, footerControls, false, false, false);
    }

    this.createGroupControl = function (model, CSSClassBase, sizeCallbackInstance) {
        var gc = new GroupControl(model, CSSClassBase);

        gc.observable.addObserver(sizeCallbackInstance);
        gc.observable.addObserver(actionHandler.observer);

        return gc;
    }

    this.createDocumentDescriptionControl = function (model, CSSClassBase, sizeCallbackInstance) {
        var ddc = new DocumentDescriptionControl(model, CSSClassBase);

        /** Get the versionselectorcontrol. */
        for (var i = 0; i < model.headerControls.length; ++i) {
            model.headerControls[i].getModel().parentPackageControl = ddc;
        }

        ddc.observable.addObserver(sizeCallbackInstance);
        ddc.observable.addObserver(actionHandler.observer);

        return ddc;
    }

    this.addChildControl = function (parentControl) {
        var text = window.lang.convert('ASK_PACKAGE_SELECTION');

        var formElements = dataProvider.readDocumentDescriptions('ai.labs.package', 0, 0, '', 'asc');

        var callback = function (success, callbackEvent) {
            if (success) {
                if (callbackEvent.documentDescription !== 'undefined') {
                    var package = callbackEvent.documentDescription;
                    package.id = botPackages.length;
                    botPackages.push(package);

                    var packageModel = instance.createPackageGroupControlModel(package, parentControl.getModel());
                    var packageControl = instance.createDocumentDescriptionControl(packageModel, 'documentdescriptioncontrol', application.contentBuilder.observer);

                    var parentId = '#' + application.configuration.referencePrefix + parentControl.getModel().idPrefix + parentControl.getModel().id;
                    $(packageControl.createRepresentation())
                        .hide()
                        .appendTo(parentId)
                        .fadeIn();

                    parentControl.getModel().addChild(packageControl);
                    packageControl.registerButtonEvents();

                    var packageId = '#' + packageControl.getModel().idPrefix + packageControl.getModel().id
                    $(packageId).addClass(application.configuration.newStateClassName);
                    packageControl.getModel().addClass(application.configuration.newStateClassName);
                    application.reloadManager.changesHappened();
                }
            }
        };

        var model = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'), window.lang.convert('CANCEL_BUTTON'),
            formElements, {dialogType:'documentDescription', inlineResourceCreation:true});

        var dialog = new DialogControl(model);

        dialog.showDialog();
    }

    this.removeChildControl = function (childControl) {
        var packageModel = childControl.getModel();
        var text = window.lang.convert('ASK_DELETE_PACKAGE') + '<b>' + packageModel.title + '</b>?';

        var callback = function (success) {
            if (success) {
                $('#' + packageModel.idPrefix + packageModel.id).stop().fadeOut('slow', function () {
                    $(this).remove();
                    application.reloadManager.changesHappened();
                });
            }
        };

        var model = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'), window.lang.convert('CANCEL_BUTTON'), false);

        var dialog = new DialogControl(model);
        dialog.showDialog();
    }
}