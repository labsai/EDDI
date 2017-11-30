function PropertiesActionHandler(contentBuilder, dataProvider) {
    let instance = this;
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'AddSelected':
                break;
            case 'DeleteSelected':
                break;
        }
    });
}