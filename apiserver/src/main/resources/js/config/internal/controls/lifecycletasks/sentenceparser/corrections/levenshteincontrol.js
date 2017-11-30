function LevenshteinControl(model) {
    model.CSSClassBase = 'blockcontrol';

    let cacheId = application.jsonBuilderHelper.getDefinitionCacheId(model.type);
    let filterString = model.type.split('//')[1].split('?')[0];
    let displayName = application.networkCacheManager.cachedNetworkCall(cacheId, application.dataProvider,
        application.dataProvider.readExtensionDefinitions, [filterString])[0].name;

    model.text = displayName;
    model.editable = true;
    model.deleteable = true;

    let textCSSClassPostfix = '_text';
    let editableIdPrefix = 'editable_';
    let deleteableIdPrefix = 'deleteable_';
    let editableCSSClassPostfix = '_editable';
    let deleteableCSSClassPostfix = '_deleteable';
    let rightSideCSSClassPostfix = '_right';

    this.observable = new Observable();

    let instance = this;

    this.createRepresentation = function () {
        let representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">' +
            '<div class="' + model.CSSClassBase + textCSSClassPostfix + '">' + model.text + '</div>';

        representation += '<div class="' + model.CSSClassBase + rightSideCSSClassPostfix + '">';
        if (model.editable) {
            representation += '<a href=="#"><div id="' + model.idPrefix + editableIdPrefix + model.id + '"' +
                ' class="' + model.CSSClassBase + editableCSSClassPostfix + '"></div></a>';
        }

        if (model.deleteable) {
            representation += '<a href=="#"><div id="' + model.idPrefix + deleteableIdPrefix + model.id + '"' +
                ' class="' + model.CSSClassBase + deleteableCSSClassPostfix + '"></div></a>';
        }
        representation += '<div class="clear"></div></div>';

        representation += '<div class="clear"></div></div>';

        return representation;
    };

    this.registerButtonEvents = function () {
        if (model.editable) {
            $('#' + model.idPrefix + editableIdPrefix + model.id).click(function () {
                let event = new Event(instance, 'EditElement');

                event.configDefinition = model.configDefinition;

                instance.observable.notify(event);
                return false;
            });
        }

        if (model.deleteable) {
            $('#' + model.idPrefix + deleteableIdPrefix + model.id).click(function () {
                instance.observable.notify(new Event(instance, 'DeleteElement'));
                return false;
            });
        }
    };

    this.getModel = function () {
        return model;
    }
}