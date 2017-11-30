function ExpressionHelper() {
    let removeIllegalChars = function (string) {
        let retString = '';
        let allowedChars = 'abcdefghijklmnopqrstuvwxyz0123456789_';
        for (let n = 0; n < string.length; n++) {
            for (let i = 0; i < allowedChars.length; i++) {
                if (string.charAt(n) === allowedChars.charAt(i)) {
                    retString += string.charAt(n);
                }
            }
        }

        return retString;
    };

    this.convertToExpression = function (input, semantic) {
        input = input.toLowerCase().trim();
        input = input.replace(/ /g, '_');
        input = removeIllegalChars(input);
        input = semantic + '(' + input + ')';
        return input;
    }
}