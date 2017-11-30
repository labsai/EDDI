function ReferenceUpdateManager() {
    let doUpdates = true;
    let doUpdatesBot = true;
    let synchronisationHelper = new DialogSynchronisationHelper(application.dataProvider);

    this.updateReferences = function (resourceURI, completion) {
        if (doUpdates === false) {
            completion(true);
            return;
        }

        let anchorParams = application.url.serializeAnchors();

        if (application.url.getCurrentPage() === 'packages') {
            if (anchorParams.hasOwnProperty(application.configuration.botParentIdHashKey) &&
                anchorParams.hasOwnProperty(application.configuration.botParentVersionHashKey)) {
                application.dataProvider.updateResourceInBot(anchorParams[application.configuration.botParentIdHashKey],
                    anchorParams[application.configuration.botParentVersionHashKey],
                    resourceURI,
                    function (httpCode, xmlHttpRequest, value) {
                        if (application.httpCodeManager.successfulRequest(httpCode)) {
                            let newVersion = $.url.parse(xmlHttpRequest.responseText).params.version;
                            let obj = {};

                            obj[application.configuration.botParentVersionHashKey] = newVersion;

                            $.bbq.pushState(obj);
                            completion(true);
                        } else {
                            synchronisationHelper.showErrorDialogWithCallback(httpCode,
                                function (success) {
                                    if (!success) {
                                        application.reloadManager.performWithoutConfirmation(
                                            synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                        );
                                    }

                                    completion(false);

                                    $('#content').hideLoadingIndicator();
                                });
                        }
                    }
                );
            } else {
                completion(true);
            }

            return;
        }

        if (anchorParams.hasOwnProperty(application.configuration.packageParentIdHashKey) &&
            anchorParams.hasOwnProperty(application.configuration.packageParentVersionHashKey)) {
            console.log('calling update Res in pckg with resourceURI ' + resourceURI);

            application.dataProvider.updateResourceInPackage(anchorParams[application.configuration.packageParentIdHashKey],
                anchorParams[application.configuration.packageParentVersionHashKey],
                resourceURI,
                function (httpCode, xmlHttpRequest, value) {
                    console.log(xmlHttpRequest.responseText);
                    if (application.httpCodeManager.successfulRequest(httpCode)) {
                        let newVersion = $.url.parse(xmlHttpRequest.responseText).params.version;
                        let obj = {};

                        obj[application.configuration.packageParentVersionHashKey] = newVersion;

                        $.bbq.pushState(obj);

                        if (doUpdatesBot &&
                            anchorParams.hasOwnProperty(application.configuration.botParentIdHashKey) &&
                            anchorParams.hasOwnProperty(application.configuration.botParentVersionHashKey)) {
                            console.log('calling update Res in bot with resourceURI ' + xmlHttpRequest.responseText);
                            application.dataProvider.updateResourceInBot(anchorParams[application.configuration.botParentIdHashKey],
                                anchorParams[application.configuration.botParentVersionHashKey],
                                xmlHttpRequest.responseText,
                                function (httpCode, xmlHttpRequest, value) {
                                    console.log('Updated bot ' + anchorParams[application.configuration.botParentIdHashKey]);
                                    console.log('With return value: ' + httpCode);
                                    if (application.httpCodeManager.successfulRequest(httpCode)) {
                                        let newVersion = $.url.parse(xmlHttpRequest.responseText).params.version;
                                        let obj = {};

                                        obj[application.configuration.botParentVersionHashKey] = newVersion;

                                        $.bbq.pushState(obj);

                                        completion(true);
                                    } else {
                                        synchronisationHelper.showErrorDialogWithCallback(httpCode,
                                            function (success) {
                                                if (!success) {
                                                    application.reloadManager.performWithoutConfirmation(
                                                        synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                                    );
                                                }
                                                completion(false);

                                                $('#content').hideLoadingIndicator();
                                            });
                                    }
                                }
                            );
                        } else {
                            completion(true)
                        }
                    } else {
                        synchronisationHelper.showErrorDialogWithCallback(httpCode,
                            function (success) {
                                if (!success) {
                                    application.reloadManager.performWithoutConfirmation(
                                        synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                    );
                                }
                                completion(false);

                                $('#content').hideLoadingIndicator();
                            });
                    }
                }
            );
        } else {
            completion(true);
        }
    };

    this.toggleUpdateReferences = function () {
        doUpdates = !doUpdates;
    };

    this.toggleUpdateReferencesBot = function () {
        doUpdatesBot = !doUpdatesBot;
    }
}