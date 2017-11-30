function HTTPCodeManager() {
    this.successfulRequest = function (httpCode) {
        /** 2xx - Success */
        return httpCode.toString().indexOf('2') === 0;
    }
}