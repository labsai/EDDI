function ExpressionHelper() {
    var removeIllegalChars = function (string) {
        var retString = '';
        var allowedChars = 'abcdefghijklmnopqrstuvwxyz0123456789_';
        for (var n = 0; n < string.length; n++) {
            for (var i = 0; i < allowedChars.length; i++) {
                if (string.charAt(n) == allowedChars.charAt(i)) {
                    retString += string.charAt(n);
                }
            }
        }

        return retString;
    }

    this.convertToExpression = function (input, semantic) {
        input = input.toLowerCase().trim();
        input = input.replace(/ /g, '_');
        input = removeIllegalChars(input);
        input = semantic + '(' + input + ')';
        return input;
    }
}