function ResourceVersionControl(versionSelectorControl) {
    let CSSClassBase = 'resourceversion'

    this.createRepresentation = function () {
        return '<div class="' + CSSClassBase + '">' + versionSelectorControl.createRepresentation() + '<div class="clear"></div></div>';
    };

    this.registerButtonEvents = function () {
        if (versionSelectorControl.getModel().currentVersion === versionSelectorControl.getModel().versions.last()) {
            $('.' + CSSClassBase).css('background-color', '#90ee90');
        }

        versionSelectorControl.registerButtonEvents();
    }
}