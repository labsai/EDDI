function ResourceVersionControl(versionSelectorControl) {
    var CSSClassBase = 'resourceversion'

    this.createRepresentation = function () {
        var representation = '<div class="' + CSSClassBase + '">' + versionSelectorControl.createRepresentation() + '<div class="clear"></div></div>';

        return representation;
    }

    this.registerButtonEvents = function () {
        if (versionSelectorControl.getModel().currentVersion == versionSelectorControl.getModel().versions.last()) {
            $('.' + CSSClassBase).css('background-color', '#90ee90');
        }

        versionSelectorControl.registerButtonEvents();
    }
}