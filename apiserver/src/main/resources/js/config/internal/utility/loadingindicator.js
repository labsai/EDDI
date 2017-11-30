function LoadingIndicator(model) {
    this.createRepresentation = function () {
        return '<div id="' + application.configuration.loadingIndicatorPrefix + model.id + '" class="loading_indicator"></div>';
    }
}

function LoadingIndicatorModel(id) {
    this.id = id;
}

$.fn.showLoadingIndicator = function () {
    let model = new LoadingIndicatorModel($(this).attr('id'));
    let loadingIndicator = new LoadingIndicator(model);

    let loadingId = '#' + application.configuration.loadingIndicatorPrefix + model.id;

    $(this).before(loadingIndicator.createRepresentation());
    $(loadingId).outerHeight($(this).outerHeight());
    $(loadingId).addClass($(this).attr('class'));

    $(this).stop().fadeOut('fast', function () {
        $(loadingId).fadeIn();
    });
};

$.fn.hideLoadingIndicator = function () {
    let loadingIndicatorRefId = '#' + application.configuration.loadingIndicatorPrefix + this.attr('id');

    let instance = this;
    $(loadingIndicatorRefId).stop().fadeOut('fast', function () {
        $(loadingIndicatorRefId).remove();
        $(instance).fadeIn('slow');
    });
};