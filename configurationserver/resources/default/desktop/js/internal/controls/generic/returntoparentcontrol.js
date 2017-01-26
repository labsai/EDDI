function ReturnToParentControl(model) {
    var id = application.dataProvider.getNextIdGlobal();
    var imgPostfix = '_imgarrowleft';
    var textPostfix = '_text';
    var checkboxPostfix = '_checkbox';
    var containerPostfix = '_container';
    var toBotPostfix = '_tobot';
    var enclosingPostfix = '_enclosing';

    var bothPresent = function() {
        var anchorParams = application.url.serializeAnchors();

        if(anchorParams.hasOwnProperty(application.configuration.botParentIdHashKey) &&
            anchorParams.hasOwnProperty(application.configuration.botParentVersionHashKey) &&
            anchorParams.hasOwnProperty(application.configuration.packageParentIdHashKey) &&
            anchorParams.hasOwnProperty(application.configuration.packageParentVersionHashKey)) {
            return true;
        } else {
            return false;
        }
    }

    var makeHrefToBot = function() {
        var host = 'ai.labs.bot/botstore/bots/';

        var anchorParams = application.url.serializeAnchors();
        var resource = 'eddi://' + host + anchorParams[application.configuration.botParentIdHashKey] +
            '?version=' + anchorParams[application.configuration.botParentVersionHashKey];
        var href = application.url.getEditorUriForResourceUri(resource);

        return href;
    }

    var makeHref = function() {
        var host;
        switch(model.pageIdentifier) {
            case 'bots':
                host = 'ai.labs.bot/botstore/bots/';
                break;
            case 'packages':
                host = 'ai.labs.package/packagestore/packages/'
                break;
            default:
                throw 'Cant make href attribute for pageIdentifier: ' + model.pageIdentifier + '.';
        }

        var resource = 'eddi://' + host + model.resourceId + '?version=' + model.resourceVersion;
        var href = application.url.getEditorUriForResourceUri(resource);

        /** Preserve bot anchors if both anchors are present. */
        var anchors = "";
        var anchorParams = application.url.serializeAnchors();

        if(bothPresent()) {
            anchors = '#' + application.configuration.botParentIdHashKey + '=' + anchorParams[application.configuration.botParentIdHashKey] +
                      '&' + application.configuration.botParentVersionHashKey + '=' + anchorParams[application.configuration.botParentVersionHashKey];
        }

        return href + anchors;
    }

    this.createRepresentation = function() {
        var representation = '<div class="' + model.cssClassBase + enclosingPostfix + '">';

        if(bothPresent()) {
            representation += '<div class="' + model.cssClassBase + containerPostfix + toBotPostfix + '"><a href="' + makeHrefToBot() + '" class="' + model.cssClassBase + '" style="display:block">' +
                '<div class="' + model.cssClassBase + imgPostfix + '"></div>' +
                '<div class="' + model.cssClassBase + textPostfix + '">' + window.lang.convert('RETURN_TO_BOT') + '</div><div class="clear"></div></a>';

            representation += '<input id="' + model.cssClassBase + checkboxPostfix + toBotPostfix + '" checked="true" type="checkbox"/>' +
                '<span class="' + model.cssClassBase + checkboxPostfix + textPostfix + '">' +
                window.lang.convert('UPDATE_REFERENCES') +'</span>';
            representation += '</div>';
        }

        representation += '<div class="' + model.cssClassBase + containerPostfix + '"><a href="' + makeHref() + '" class="' + model.cssClassBase + '" style="display:block">' +
            '<div class="' + model.cssClassBase + imgPostfix + '"></div>' +
            '<div class="' + model.cssClassBase + textPostfix + '">' + window.lang.convert('RETURN_TO_PARENT') + '</div><div class="clear"></div></a>';

        representation += '<input id="' + model.cssClassBase + checkboxPostfix + '" checked="true" type="checkbox"/>' +
            '<span class="' + model.cssClassBase + checkboxPostfix + textPostfix + '">' +
             window.lang.convert('UPDATE_REFERENCES') +'</span>';
        representation += '</div></div>';

        return representation;
    }

    this.registerButtonEvents = function() {
        $('#'  + model.cssClassBase + checkboxPostfix).click(function(){
            application.referenceUpdateManager.toggleUpdateReferences();

            $('#'  + model.cssClassBase + checkboxPostfix + toBotPostfix).trigger('click');
        });

        $('#'  + model.cssClassBase + checkboxPostfix + toBotPostfix).click(function(){
            application.referenceUpdateManager.toggleUpdateReferencesBot();
        });
    }

    this.getModel = function() {
        return model;
    }
}

function ReturnToParentControlModel(cssClassBase, pageIdentifier, resourceId, resourceVersion) {
    this.cssClassBase = cssClassBase;
    this.pageIdentifier = pageIdentifier;
    this.resourceId = resourceId;
    this.resourceVersion = resourceVersion;
}