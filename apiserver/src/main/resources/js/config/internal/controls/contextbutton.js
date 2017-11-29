function ContextButton(contextButtonModelCtr) {
    var idPrefix = 'contextbuttonid_';
    var CSSClassBase = 'contextbutton';
    var textPostfix = '_text';
    var iconPostfix = '_icon';
    var contextButtonModel = contextButtonModelCtr;

    this.createRepresentation = function () {
        var representation;

        representation = '<div id="' + idPrefix + contextButtonModel.id + '" class="' + CSSClassBase + '"> \
            <div class="' + CSSClassBase + iconPostfix + ' ' + contextButtonModel.imageClass + '"></div> \
            <span class="' + CSSClassBase + textPostfix + '">' + contextButtonModel.text + '</span> \
            </div>';

        return representation;
    }

    this.registerButtonEvents = function () {
        $('#' + idPrefix + contextButtonModel.id).click(function (e) {
            contextButtonModel.action.call(contextButtonModel.actionInstance);
        });
    }

    this.getModel = function () {
        return contextButtonModel;
    }
}

function ContextButtonModel(id, text, action, actionInstance, imageClass) {
    this.id = id;
    this.text = text;
    this.action = action;
    this.actionInstance = actionInstance;
    this.imageClass = imageClass;

    if (typeof this.imageClass === 'undefined') {
        this.imageClass = 'iconimage_help'
    }
}