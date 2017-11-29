function BindingManager() {
    /**
     * See: http://shebang.brandonmintern.com/foolproof-html-escaping-in-javascript
     *
     * @param input
     * @return {String}
     */
    var escapeHtml = function (input) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(input));
        return div.innerHTML;
    };

    var unescapeHtml = function (input) {
        var div = document.createElement('div');
        div.innerHTML = input;
        var child = div.childNodes[0];
        return child ? child.nodeValue : '';
    };

    this.bindFromString = function (input) {
        return escapeHtml(input);
    }

    this.bindToString = function (input) {
        return unescapeHtml(input);
    }
}