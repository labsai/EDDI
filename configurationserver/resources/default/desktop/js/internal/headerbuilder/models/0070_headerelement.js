function HeaderElement(text, action, actionInstance, imageClass) {
    this.id = application.dataProvider.getNextIdGlobal();
    this.text = text;
    this.action = action;
    this.actionInstance = actionInstance;
    this.imageClass = imageClass;
}