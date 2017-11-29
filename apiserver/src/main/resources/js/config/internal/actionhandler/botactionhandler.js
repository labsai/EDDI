function BotActionHandler(contentBuilder, dataProvider) {
    var instance = this;
    var synchronisationHelper = new DialogSynchronisationHelper(dataProvider);
    var versionHelper = new VersionHelper();
    var versionsChanged = false;

    var deployToEnvironment = function (event, providerInstance, deploy, getDeploymentStatus, environment) {
        var intervalNumber;

        var completion = function (httpCode, xmlHttpRequest, value) {
            if (httpCode == 202) {
                var timeoutFunc = function () {
                    var returnValue = getDeploymentStatus.apply(providerInstance, [environment,
                        providerInstance.dataProviderState.getActiveId(),
                        providerInstance.dataProviderState.getActiveVersion()]);

                    application.contentModelProvider.contextButtonChangeState(event.sender.id, environment, returnValue);

                    if (returnValue != 'IN_PROGRESS') {
                        clearInterval(intervalNumber);
                    }
                };

                intervalNumber = setInterval(timeoutFunc, 1000);
            }
            else {
                application.contentModelProvider.contextButtonChangeState(event.sender.id, environment, 'ERROR');
            }
        };

        application.contentModelProvider.contextButtonChangeState(event.sender.id, environment, 'IN_PROGRESS');

        deploy.apply(providerInstance,
            [environment,
                providerInstance.dataProviderState.getActiveId(),
                providerInstance.dataProviderState.getActiveVersion(),
                completion]);
    }

    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'Save':
                var bot = dataProvider.readActiveBot();
                var oldPackages = bot.packages;
                bot.packages = [];

                var package;
                application.contentModelProvider.getBotDocumentDescriptionsSelector().each(function () {
                    package = application.contentModelProvider.getBotPackage($(this).attr("id"));
                    bot.packages.push(package.resource);
                });

                var changeDetected = oldPackages.length != bot.packages.length;
                if (!changeDetected) {
                    for (var i = 0; i < oldPackages.length; i++) {
                        if (oldPackages[i] !== bot.packages[i]) {
                            changeDetected = true;
                            break;
                        }
                    }
                }

                if (changeDetected || versionsChanged) {
                    dataProvider.updateActiveBot(bot,
                        function (httpCode, xmlHttpRequest, value) {
                            if (application.httpCodeManager.successfulRequest(httpCode)) {
                                //synchronisationHelper.updateActiveVersion(value);
                                application.reloadManager.performWithoutConfirmation(
                                    synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                );
                            } else {
                                synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                                    if (success) {
                                        //in case server request fails, and user decides not to reload... just stay on the page.
                                    } else {
                                        application.reloadManager.performWithoutConfirmation(
                                            synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                        );
                                    }
                                });
                            }
                        });
                }
                break;
            case 'Cancel':
                if (application.reloadManager.hasChanges()) {
                    window.location.reload();
                }
                break;
            case 'RightButtonClicked':
                instance.handleAddChild(event, application.contentModelProvider);
                break;
            case 'DeleteDocumentDescription':
                instance.handleRemoveChild(event, application.contentModelProvider);
                break;
            case "Deploy":
                deployToEnvironment(event, dataProvider, dataProvider.deployBot, dataProvider.getDeploymentStatus, 'restricted');
                break;
            case "TestDeploy":
                deployToEnvironment(event, dataProvider, dataProvider.deployBot, dataProvider.getDeploymentStatus, 'test');
                break;
            case 'LinkBot':
                window.open(
                    REST.apiURL + '/ui/restricted/' + application.dataProvider.dataProviderState.getActiveId(),
                    '_blank' // <- This is what makes it open in a new window.
                );
                break;
            case 'LinkBotTest':
                window.open(
                    REST.apiURL + '/ui/test/' + application.dataProvider.dataProviderState.getActiveId(),
                    '_blank' // <- This is what makes it open in a new window.
                );
                break;
            case 'VersionChanged':
                var uri = event.sender.getModel().resourceUri;

                if (SLSUriParser(uri).host == "ai.labs.bot") {
                    break;
                }

                var packages = application.contentModelProvider.getBotPackages();

                for (var i = 0; i < packages.length; ++i) {
                    if (packages[i].resource == event.sender.getModel().resourceUri) {
                        packages[i].resource = application.url.updateVersion(packages[i].resource, event.value);
                        break;
                    }
                }

                var htmlId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                $(htmlId).parent().parent().parent().removeClass(application.configuration.newStateClassName);
                event.sender.getModel().parentPackageControl.getModel().removeClass(application.configuration.newStateClassName);
                $(htmlId).parent().parent().parent().addClass(application.configuration.editedStateClassName);
                event.sender.getModel().parentPackageControl.getModel().addClass(application.configuration.editedStateClassName);

                versionsChanged = true;

                application.reloadManager.changesHappened();
                break;
            case 'GotoVersion':
                var targetUri = event.sender.getModel().resourceUri;

                if (event.sender.getModel().anchors) {
                    targetUri += event.sender.getModel().anchors;
                }

                versionHelper.gotoResourceUri(targetUri);
                break;
            case 'Monitor':
                var query = $.url.parse(application.url.getUriForPage('monitor'));

                if (typeof query.params === 'undefined') {
                    query.params = {};
                }

                query.params.botId = application.dataProvider.dataProviderState.getActiveId();
                //query.params.botVersion = application.dataProvider.dataProviderState.getActiveVersion();

                delete query.query;
                delete query.relative;
                delete query.source;

                /*Reload the page with the new version active.*/
                window.location.assign($.url.build(query));
                break;
            case 'Test':
                var query = $.url.parse(application.url.getUriForPage('testcases'));

                if (typeof query.params === 'undefined') {
                    query.params = {};
                }

                query.params.botId = application.dataProvider.dataProviderState.getActiveId();
                //query.params.botVersion = application.dataProvider.dataProviderState.getActiveVersion();

                delete query.query;
                delete query.relative;
                delete query.source;

                /*Reload the page with the new version active.*/
                window.location.assign($.url.build(query));
                break;
        }
    });
}

BotActionHandler.prototype = new GenericActionHandler();