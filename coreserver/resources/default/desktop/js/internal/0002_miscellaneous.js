function getQueryParts(href) {
    var query = $.url.parse(href);
    var path = query.path;

    var parts = path.split("/");

    var page;
    var environment;
    var id;

    page = typeof parts[1] !== 'undefined' ? decodeURIComponent(parts[1]) : page;
    environment = typeof parts[2] !== 'undefined' ? decodeURIComponent(parts[2]) : environment;
    id = typeof parts[3] !== 'undefined' ? decodeURIComponent(parts[3]) : id;

    return { page:page, environment:environment, id:id };
}

function getUriForResource(environment, id, conversationId) {
    var uriObject = $.url.parse(window.location.href);

    delete uriObject.relative;
    delete uriObject.source;
    delete uriObject.directory;

    var path = uriObject.path;

    var parts = path.split("/");
    parts[3] = id;

    var newPath = parts.join("/");
    uriObject.path = newPath;

    delete uriObject.params;
    delete uriObject.query;
    uriObject.params = {conversationId:conversationId};

    return $.url.build(uriObject);
}

function mouseMovement(element, imgUrl) {
    if (document.images)
        element.src = imgUrl;
}

function initKeyEvent() {
    var userInputBox = document.getElementById('input_user');
    userInputBox.focus();
    if (userInputBox) {
        if (window.event)
            userInputBox.onkeydown = keyPressed;
        else
            userInputBox.onkeypress = keyPressed;
    }
}

function keyPressed(event) {
    var e = event || window.event;

    if (e.keyCode == 13) {
        submitInput();
        refreshConversationLog();
        return false;
    }

    return true;
}
