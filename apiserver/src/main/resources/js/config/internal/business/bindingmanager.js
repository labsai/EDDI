function BindingManager() {
    /**
     * See: http://shebang.brandonmintern.com/foolproof-html-escaping-in-javascript
     *
     * @param input
     * @return {String}
     */
    let escapeHtml = function (input) {
        let div = document.createElement('div');
        div.appendChild(document.createTextNode(input));
        return div.innerHTML;
    };

    let unescapeHtml = function (input) {
        let div = document.createElement('div');
        div.innerHTML = input;
        let child = div.childNodes[0];
        return child ? child.nodeValue : '';
    };

    this.bindFromString = function (input) {
        return escapeHtml(input);
    };

    this.bindToString = function (input) {
        return unescapeHtml(input);
    }
}