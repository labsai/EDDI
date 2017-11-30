function AuthenticationInformation() {
    this.printLoginHeader = function () {
        $('.loginbox_header').append('<span>' + window.lang.convert('HEADER_USER') + '</span>');
    };

    this.printLoginInformation = function () {
        $('#user_display_name').before(window.lang.convert('LOGGED_IN_MESSAGE'));
        $('#logout_url').append(window.lang.convert('LOGOUT_KEYWORD'));
    }
}
