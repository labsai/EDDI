function PropertiesActionHandler(contentBuilder, dataProvider) {
    var instance = this;
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'AddSelected':
                break;
            case 'DeleteSelected':
                break;
        }
    });
}