function ReturnToParentControl(model) {
    let id = application.dataProvider.getNextIdGlobal();
    let imgPostfix = '_imgarrowleft';
    let textPostfix = '_text';
    let checkboxPostfix = '_checkbox';
    let containerPostfix = '_container';
    let toBotPostfix = '_tobot';
    let enclosingPostfix = '_enclosing';

    let bothPresent = function () {
        let anchorParams = application.url.serializeAnchors();

        return anchorParams.hasOwnProperty(application.configuration.botParentIdHashKey) &&
            anchorParams.hasOwnProperty(application.configuration.botParentVersionHashKey) &&
            anchorParams.hasOwnProperty(application.configuration.packageParentIdHashKey) &&
            anchorParams.hasOwnProperty(application.configuration.packageParentVersionHashKey);
    };

    let makeHrefToBot = function () {
        let host = 'ai.labs.bot/botstore/bots/';

        let anchorParams = application.url.serializeAnchors();
        let resource = 'eddi://' + host + anchorParams[application.configuration.botParentIdHashKey] +
            '?version=' + anchorParams[application.configuration.botParentVersionHashKey];
        return application.url.getEditorUriForResourceUri(resource);
    };

    let makeHref = function () {
        let host;
        switch (model.pageIdentifier) {
            case 'bots':
                host = 'ai.labs.bot/botstore/bots/';
                break;
            case 'packages':
                host = 'ai.labs.package/packagestore/packages/'
                break;
            default:
                throw 'Cant make href attribute for pageIdentifier: ' + model.pageIdentifier + '.';
        }

        let resource = 'eddi://' + host + model.resourceId + '?version=' + model.resourceVersion;
        let href = application.url.getEditorUriForResourceUri(resource);

        /** Preserve bot anchors if both anchors are present. */
        let anchors = "";
        let anchorParams = application.url.serializeAnchors();

        if (bothPresent()) {
            anchors = '#' + application.configuration.botParentIdHashKey + '=' + anchorParams[application.configuration.botParentIdHashKey] +
                '&' + application.configuration.botParentVersionHashKey + '=' + anchorParams[application.configuration.botParentVersionHashKey];
        }

        return href + anchors;
    };

    this.createRepresentation = function () {
        let representation = '<div class="' + model.cssClassBase + enclosingPostfix + '">';

        if (bothPresent()) {
            representation += '<div class="' + model.cssClassBase + containerPostfix + toBotPostfix + '"><a href="' + makeHrefToBot() + '" class="' + model.cssClassBase + '" style="display:block">' +
                '<div class="' + model.cssClassBase + imgPostfix + '"></div>' +
                '<div class="' + model.cssClassBase + textPostfix + '">' + window.lang.convert('RETURN_TO_BOT') + '</div><div class="clear"></div></a>';

            representation += '<input id="' + model.cssClassBase + checkboxPostfix + toBotPostfix + '" checked="true" type="checkbox"/>' +
                '<span class="' + model.cssClassBase + checkboxPostfix + textPostfix + '">' +
                window.lang.convert('UPDATE_REFERENCES') + '</span>';
            representation += '</div>';
        }

        representation += '<div class="' + model.cssClassBase + containerPostfix + '"><a href="' + makeHref() + '" class="' + model.cssClassBase + '" style="display:block">' +
            '<div class="' + model.cssClassBase + imgPostfix + '"></div>' +
            '<div class="' + model.cssClassBase + textPostfix + '">' + window.lang.convert('RETURN_TO_PARENT') + '</div><div class="clear"></div></a>';

        representation += '<input id="' + model.cssClassBase + checkboxPostfix + '" checked="true" type="checkbox"/>' +
            '<span class="' + model.cssClassBase + checkboxPostfix + textPostfix + '">' +
            window.lang.convert('UPDATE_REFERENCES') + '</span>';
        representation += '</div></div>';

        return representation;
    };

    this.registerButtonEvents = function () {
        $('#' + model.cssClassBase + checkboxPostfix).click(function () {
            application.referenceUpdateManager.toggleUpdateReferences();

            $('#' + model.cssClassBase + checkboxPostfix + toBotPostfix).trigger('click');
        });

        $('#' + model.cssClassBase + checkboxPostfix + toBotPostfix).click(function () {
            application.referenceUpdateManager.toggleUpdateReferencesBot();
        });
    };

    this.getModel = function () {
        return model;
    }
}

function ReturnToParentControlModel(cssClassBase, pageIdentifier, resourceId, resourceVersion) {
    this.cssClassBase = cssClassBase;
    this.pageIdentifier = pageIdentifier;
    this.resourceId = resourceId;
    this.resourceVersion = resourceVersion;
}