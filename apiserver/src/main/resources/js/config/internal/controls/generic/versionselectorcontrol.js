function VersionSelectorControl(model) {
    let gotoCSSClassPostfix = '_goto';
    let resourceUriPostfix = '_resourceuri';
    let iconCSSClassPostfix = '_icon';
    let textCSSClassPostfix = '_text';
    let dropdownCSSClassPostfix = '_dropdown';

    let instance = this;

    this.observable = new Observable();

    let makeAnchorTags = function () {
        let page = application.url.getCurrentPage();

        let isParentPage = function () {
            return page === 'bots' || page === 'packages';
        };

        if (isParentPage() && model.appendAnchorTags) {
            let parentIdHashKey, parentVersionHashKey;

            if (page === 'bots') {
                parentIdHashKey = application.configuration.botParentIdHashKey;
                parentVersionHashKey = application.configuration.botParentVersionHashKey;
            } else if (page === 'packages') {
                parentIdHashKey = application.configuration.packageParentIdHashKey;
                parentVersionHashKey = application.configuration.packageParentVersionHashKey;
            }

            let anchors = '#'
                + parentIdHashKey + '=' + application.url.getCurrentId()
                + '&' + parentVersionHashKey + '=' + application.url.getCurrentVersion();

            if ($.param.fragment() !== "") {
                anchors += '&' + $.param.fragment();
            }

            return anchors;
        } else {
            /** Preserve old anchor tags. */
            if ($.param.fragment() !== "") {
                return '#' + $.param.fragment();
            } else {
                return "";
            }
        }
    };

    this.createRepresentation = function () {
        let representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">';

        if (model.showLink === true) {
            representation += '<a id="' + model.idPrefix + model.id + resourceUriPostfix + '" href="' + application.url.getEditorUriForResourceUri(model.resourceUri) + makeAnchorTags() + '">\
                              <span style="display:block" id="' + model.idPrefix + model.id + gotoCSSClassPostfix + '" class="' + model.CSSClassBase + gotoCSSClassPostfix + '">\
                              <div class="' + model.CSSClassBase + iconCSSClassPostfix + '"></div>\
                              <span class="' + model.CSSClassBase + textCSSClassPostfix + '">' + window.lang.convert('RESOURCE_LINK') + '</span>\
                              <div class="clear"></div></span></a>';
        }

        representation += '<div id="' + model.idPrefix + model.id + dropdownCSSClassPostfix + '" class="' + model.CSSClassBase + dropdownCSSClassPostfix + '"></div>';
        representation += '<div class="clear"></div></div>';
        return representation;
    };

    this.getModel = function () {
        return model;
    };

    let onUpdateVersion = function () {
        $('#' + model.idPrefix + model.id + resourceUriPostfix).attr('href', application.url.getEditorUriForResourceUri(model.resourceUri));
    };

    this.registerButtonEvents = function () {
        $('#' + model.idPrefix + model.id + gotoCSSClassPostfix).disableSelection();

        $('#' + model.idPrefix + model.id + gotoCSSClassPostfix).click(function () {
            model.anchors = makeAnchorTags();
            instance.observable.notify(new Event(instance, 'GotoVersion'));
            return false;
        });

        $('#' + model.idPrefix + model.id + dropdownCSSClassPostfix).dropdown({
            value: model.currentVersion,
            possibleValues: model.versions.reverse(),
            valueChanged: function (value, oldValue) {
                let editableEvent = new Event(instance, 'VersionChanged');

                editableEvent.value = value;
                editableEvent.oldValue = oldValue;
                editableEvent.isUserInput = false;

                model.currentVersion = value;

                instance.observable.notify(editableEvent);

                model.resourceUri = application.url.updateVersion(model.resourceUri, value);
                onUpdateVersion();
            }
        });
    }
}

function VersionSelectorControlModel(id, idPrefix, CSSClassBase, versions, currentVersion, resourceUri, showLink, appendAnchorTags) {
    this.id = id;
    this.idPrefix = idPrefix;
    this.CSSClassBase = CSSClassBase;
    this.versions = versions;
    this.currentVersion = currentVersion;
    this.resourceUri = resourceUri;
    this.showLink = showLink;
    this.appendAnchorTags = appendAnchorTags;
}