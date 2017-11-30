/**
 * Builds the header context menu from HeaderElements.
 * @constructor
 */
function HeaderBuilder() {
    let headerElements = [];

    this.buildHeader = function (model) {
        for (let i = 0; i < model.length; ++i) {
            let buttonModel = this.createContextButtonModel(model[i]);

            let button = new ContextButton(buttonModel);

            headerElements.push(button);
        }

        let fullHtml = '<div id="header">';

        for (let i = 0; i < headerElements.length; ++i) {
            fullHtml += headerElements[i].createRepresentation();
        }

        fullHtml += '<div class="clear"></div></div>';

        $('#header').replaceWith(fullHtml);
    };

    this.getHeaderElements = function () {
        return headerElements;
    };

    this.registerEvents = function () {
        for (let i = 0; i < headerElements.length; ++i) {
            headerElements[i].registerButtonEvents();
        }
    };

    this.createContextButtonModel = function (headerElement) {
        return new ContextButtonModel(headerElement.id, headerElement.text, headerElement.action, headerElement.actionInstance, headerElement.imageClass);
    }
}