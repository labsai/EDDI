function IntegerControl(model) {
    model.CSSClassBase = 'blockcontrol';

    var cacheId = application.jsonBuilderHelper.getDefinitionCacheId(model.type);
    var filterString = model.type.split('//')[1].split('?')[0];
    var displayName = application.networkCacheManager.cachedNetworkCall(cacheId, application.dataProvider,
        application.dataProvider.readExtensionDefinitions, [filterString])[0].name;

    model.text = displayName;
    model.editable = false;
    model.deleteable = true;

    var textCSSClassPostfix = '_text';
    var editableIdPrefix = 'editable_';
    var deleteableIdPrefix = 'deleteable_';
    var editableCSSClassPostfix = '_editable';
    var deleteableCSSClassPostfix = '_deleteable';
    var rightSideCSSClassPostfix = '_right';

    this.observable = new Observable();

    var instance = this;

    this.createRepresentation = function () {
        var representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">' +
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
    }

    this.registerButtonEvents = function () {
        if (model.editable) {
            $('#' + model.idPrefix + editableIdPrefix + model.id).click(function () {
                instance.observable.notify(new Event(instance, 'EditElement'));
                return false;
            });
        }

        if (model.deleteable) {
            $('#' + model.idPrefix + deleteableIdPrefix + model.id).click(function () {
                instance.observable.notify(new Event(instance, 'DeleteElement'));
                return false;
            });
        }
    }

    this.getModel = function () {
        return model;
    }
}