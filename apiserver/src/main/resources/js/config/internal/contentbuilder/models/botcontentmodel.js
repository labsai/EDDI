function BotContentModel(dataProvider, actionHandler) {
    let instance = this;

    let firstLevelGroupControlIdPrefix = 'bot_';
    let secondLevelGroupControlIdPrefix = 'package_';

    this.getBotDocumentDescriptionsSelector = function () {
        return DocumentDescriptionControl.getRootElementSelector();
    };

    let botPackages = [];
    this.getBotPackages = function () {
        return botPackages;
    };

    this.getBotPackage = function (htmlId) {
        let parts = htmlId.split(secondLevelGroupControlIdPrefix);
        let botPackageIndex = parts[parts.length - 1];
        return this.getBotPackages()[botPackageIndex];
    };

    let getDeploymentStatus = function (providerInstance, environment, getDeploymentStatusFunc, completion) {
        getDeploymentStatusFunc.apply(providerInstance,
            [environment,
                dataProvider.dataProviderState.getActiveId(),
                dataProvider.dataProviderState.getActiveVersion(),
                completion
            ]);
    };

    let goToBotButton = null;
    let testGoToBotButton = null;
    let testDeployTestButton = null;

    this.contextButtonChangeState = function (id, environment, state) {
        let removeGotoBotIfExists = function () {
            if (goToBotButton !== null) {
                $('#contextbuttonid_' + goToBotButton.getModel().id).fadeOut('slow').remove();
                goToBotButton = null;
            }
        };

        let removeTestGotoBotIfExists = function () {
            if (testGoToBotButton !== null) {
                $('#contextbuttonid_' + testGoToBotButton.getModel().id).fadeOut('slow').remove();
                testGoToBotButton = null;
            }
        };

        let removeTestDeployTestButtonIfExists = function () {
            if (testDeployTestButton !== null) {
                $('#contextbuttonid_' + testDeployTestButton.getModel().id).fadeOut('slow').remove();
                testDeployTestButton = null;
            }
        };

        switch (state) {
            case 'READY':
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-color', 'green');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-image', '');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').removeClass('iconimage_help');

                if (environment === 'unrestricted') {
                    let headerElement = new HeaderElement(window.lang.convert('CONTEXT_LINK_BOT'), function () {
                        application.headerModelProvider.observable.notify(new Event(this, 'LinkBot'));
                    }, this);

                    let contextButtonModel = application.headerBuilder.createContextButtonModel(headerElement);
                    let contextButton = new ContextButton(contextButtonModel);

                    if (goToBotButton === null) {
                        $('#contextbuttonid_' + id).after(contextButton.createRepresentation());
                    } else {
                        $('#contextbuttonid_' + goToBotButton.getModel().id).replaceWith(contextButton.createRepresentation());
                    }

                    contextButton.registerButtonEvents();

                    goToBotButton = contextButton;
                }

                if (environment === 'test') {
                    let headerElement = new HeaderElement(window.lang.convert('CONTEXT_LINK_BOT_TEST'), function () {
                        application.headerModelProvider.observable.notify(new Event(this, 'LinkBotTest'));
                    }, this);

                    let contextButtonModel = application.headerBuilder.createContextButtonModel(headerElement);
                    let contextButton = new ContextButton(contextButtonModel);

                    if (testGoToBotButton === null) {
                        $('#contextbuttonid_' + id).after(contextButton.createRepresentation());
                    } else {
                        $('#contextbuttonid_' + testGoToBotButton.getModel().id).replaceWith(contextButton.createRepresentation());
                    }

                    contextButton.registerButtonEvents();

                    testGoToBotButton = contextButton;
                }

//                if (environment == 'test') {
//                    let headerElement = new HeaderElement(window.lang.convert('CONTEXT_TEST'), function () {
//                        application.headerModelProvider.observable.notify(new Event(this, 'Test'));
//                    }, this);
//
//                    let contextButtonModel = application.headerBuilder.createContextButtonModel(headerElement);
//                    let contextButton = new ContextButton(contextButtonModel);
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
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-image', 'url("/binary/img/config/deployment_indicator.gif")');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-size', '32px 32px');
                $('#contextbuttonid_' + id + ' > .contextbutton_icon').css('background-repeat', 'no-repeat');
                if (environment === 'unrestricted') {
                    removeGotoBotIfExists();
                }
                if (environment === 'test') {
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
                if (environment === 'unrestricted') {
                    removeGotoBotIfExists();
                }
                if (environment === 'test') {
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
                if (environment === 'unrestricted') {
                    removeGotoBotIfExists();
                }
                if (environment === 'test') {
                    removeTestGotoBotIfExists();
                }
//                if (environment == 'test') {
//                    removeTestDeployTestButtonIfExists();
//                }
                break;
        }
    };

    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        let models = [];

        let headerElements = application.headerBuilder.getHeaderElements();

        let deployId, testDeployId;
        $(headerElements).each(function () {
            if (this.getModel().text === window.lang.convert('CONTEXT_DEPLOY')) {
                deployId = this.getModel().id;
            } else if (this.getModel().text === window.lang.convert('CONTEXT_TEST_DEPLOY')) {
                testDeployId = this.getModel().id
            }
        });
        instance.contextButtonChangeState(deployId, 'unrestricted', 'NOT_DEPLOYED');
        instance.contextButtonChangeState(testDeployId, 'test', 'NOT_DEPLOYED');

        getDeploymentStatus(dataProvider, 'unrestricted', dataProvider.getDeploymentStatus, function (httpCode, xmlHttpRequest, value) {
            if (httpCode.toString().indexOf('4') === 0) {
                instance.contextButtonChangeState(deployId, 'unrestricted', 'NOT_DEPLOYED');
            } else {
                instance.contextButtonChangeState(deployId, 'unrestricted', value);
            }
        });

        getDeploymentStatus(dataProvider, 'test', dataProvider.getDeploymentStatus, function (httpCode, xmlHttpRequest, value) {
            if (httpCode.toString().indexOf('4') === 0) {
                instance.contextButtonChangeState(testDeployId, 'test', 'NOT_DEPLOYED');
            } else {
                instance.contextButtonChangeState(testDeployId, 'test', value);
            }
        });

        let botDesc = dataProvider.readActiveDocumentDescription();


        application.contentModelHelper.createResourceVersionSelectorControl();
        application.contentModelHelper.createDocumentDescriptorDisplayControl();
        application.contentModelHelper.createLanguageSelector();

        let bot = dataProvider.readActiveBot();
        let groupControl;
        if (typeof bot !== 'undefined' && typeof botDesc !== 'undefined') {
            bot.id = 0;

            let groupControlModel = this.createBotGroupControlModel(botDesc, bot);
            groupControl = this.createGroupControl(groupControlModel, 'groupcontrol', sizeCallbackInstance);

            for (let j = 0; j < bot.packages.length; ++j) {
                let packageUriObject = SLSUriParser(bot.packages[j]);
                let packageId = decodeURIComponent(packageUriObject.id);
                let packageVersion = decodeURIComponent(packageUriObject.version);

                let packageDesc = dataProvider.readDocumentDescription(packageId, packageVersion);
                if (typeof packageDesc !== 'undefined') {
                    packageDesc.id = botPackages.length;
                    botPackages.push(packageDesc);

                    let packageModel = this.createPackageGroupControlModel(packageDesc, groupControlModel);
                    let packageControl = this.createDocumentDescriptionControl(packageModel, 'documentdescriptioncontrol', sizeCallbackInstance);

                    groupControlModel.addChild(packageControl);
                }
            }
        } else {
            application.errorHelper.showError();
        }

        models.push(groupControl);

        return models;
    };

    this.createBotGroupControlModel = function (botDesc, bot) {
        let footerControls = [];

        let footerModel = new FooterControlModel(bot.id, firstLevelGroupControlIdPrefix + 'footer_', true);
        let footerControl = new FooterControl(footerModel, 'footercontrol');

        footerControls.push(footerControl);

        let headerControls = [];

        let gcm = new GroupControlModel(bot.id, firstLevelGroupControlIdPrefix, botDesc.name,
            footerControls, false, true, null, false, false);

        gcm.headerControls = headerControls;

        return gcm;
    };

    this.createPackageGroupControlModel = function (packageDesc, parentControlModel) {
        let footerControls = [];

        let footerModel = new FooterControlModel(packageDesc.id, parentControlModel.idPrefix + parentControlModel.id + '_' + secondLevelGroupControlIdPrefix + 'footer_', false);
        let footerControl = new FooterControl(footerModel, 'footercontrol');

        footerControls.push(footerControl);

        let headerControls = [];

        let uri = packageDesc.resource;

        let params = SLSUriParser(uri);

        let maxVersion = dataProvider.getCurrentVersionForResource(uri);

        let headerIdPrefix = parentControlModel.idPrefix + parentControlModel.id + '_' + secondLevelGroupControlIdPrefix + 'header_';
        let headerControlModel = new VersionSelectorControlModel(packageDesc.id, headerIdPrefix, 'headercontrol',
            ([]).arrayWithRange(1, maxVersion), params.version, uri, true, true);
        let headerControl = new VersionSelectorControl(headerControlModel);

        headerControl.observable.addObserver(actionHandler.observer);
        headerControls.push(headerControl);

        return new DocumentDescriptionControlModel(packageDesc.id, parentControlModel.idPrefix + parentControlModel.id + '_' + secondLevelGroupControlIdPrefix,
            packageDesc.name, packageDesc.description, headerControls, footerControls, false, false, false);
    };

    this.createGroupControl = function (model, CSSClassBase, sizeCallbackInstance) {
        let gc = new GroupControl(model, CSSClassBase);

        gc.observable.addObserver(sizeCallbackInstance);
        gc.observable.addObserver(actionHandler.observer);

        return gc;
    };

    this.createDocumentDescriptionControl = function (model, CSSClassBase, sizeCallbackInstance) {
        let ddc = new DocumentDescriptionControl(model, CSSClassBase);

        /** Get the versionselectorcontrol. */
        for (let i = 0; i < model.headerControls.length; ++i) {
            model.headerControls[i].getModel().parentPackageControl = ddc;
        }

        ddc.observable.addObserver(sizeCallbackInstance);
        ddc.observable.addObserver(actionHandler.observer);

        return ddc;
    };

    this.addChildControl = function (parentControl) {
        let text = window.lang.convert('ASK_PACKAGE_SELECTION');

        let formElements = dataProvider.readDocumentDescriptions('ai.labs.package', 0, 0, '', 'asc');

        let callback = function (success, callbackEvent) {
            if (success) {
                if (callbackEvent.documentDescription !== 'undefined') {
                    let package = callbackEvent.documentDescription;
                    package.id = botPackages.length;
                    botPackages.push(package);

                    let packageModel = instance.createPackageGroupControlModel(package, parentControl.getModel());
                    let packageControl = instance.createDocumentDescriptionControl(packageModel, 'documentdescriptioncontrol', application.contentBuilder.observer);

                    let parentId = '#' + application.configuration.referencePrefix + parentControl.getModel().idPrefix + parentControl.getModel().id;
                    $(packageControl.createRepresentation())
                        .hide()
                        .appendTo(parentId)
                        .fadeIn();

                    parentControl.getModel().addChild(packageControl);
                    packageControl.registerButtonEvents();

                    let packageId = '#' + packageControl.getModel().idPrefix + packageControl.getModel().id
                    $(packageId).addClass(application.configuration.newStateClassName);
                    packageControl.getModel().addClass(application.configuration.newStateClassName);
                    application.reloadManager.changesHappened();
                }
            }
        };

        let model = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'), window.lang.convert('CANCEL_BUTTON'),
            formElements, {dialogType: 'documentDescription', inlineResourceCreation: true});

        let dialog = new DialogControl(model);

        dialog.showDialog();
    };

    this.removeChildControl = function (childControl) {
        let packageModel = childControl.getModel();
        let text = window.lang.convert('ASK_DELETE_PACKAGE') + '<b>' + packageModel.title + '</b>?';

        let callback = function (success) {
            if (success) {
                $('#' + packageModel.idPrefix + packageModel.id).stop().fadeOut('slow', function () {
                    $(this).remove();
                    application.reloadManager.changesHappened();
                });
            }
        };

        let model = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'), window.lang.convert('CANCEL_BUTTON'), false);

        let dialog = new DialogControl(model);
        dialog.showDialog();
    }
}