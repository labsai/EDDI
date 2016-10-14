/**
 * Builds the header context menu from HeaderElements.
 * @constructor
 */
function HeaderBuilder() {
    var headerElements = [];

    this.buildHeader = function (model) {
        for (var i = 0; i < model.length; ++i) {
            var buttonModel = this.createContextButtonModel(model[i]);

            var button = new ContextButton(buttonModel);

            headerElements.push(button);
        }

        var fullHtml = '<div id="header">';

        for (var i = 0; i < headerElements.length; ++i) {
            fullHtml += headerElements[i].createRepresentation();
        }

        fullHtml += '<div class="clear"></div></div>';

        $('#header').replaceWith(fullHtml);
    }

    this.getHeaderElements = function () {
        return headerElements;
    }

    this.registerEvents = function () {
        for (var i = 0; i < headerElements.length; ++i) {
            headerElements[i].registerButtonEvents();
        }
    }

    this.createContextButtonModel = function (headerElement) {
        return new ContextButtonModel(headerElement.id, headerElement.text, headerElement.action, headerElement.actionInstance, headerElement.imageClass);
    }
}