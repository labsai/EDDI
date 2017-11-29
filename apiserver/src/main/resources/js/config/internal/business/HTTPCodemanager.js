function HTTPCodeManager() {
    this.successfulRequest = function (httpCode) {
        /** 2xx - Success */
        if (httpCode.toString().indexOf('2') == 0) {
            return true;
        } else {
            return false;
        }
    }
}