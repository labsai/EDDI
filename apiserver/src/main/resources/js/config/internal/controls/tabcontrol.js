function TabControl(model) {
    let tabControlId = 'tabcontrol';
    let tabIdPrefix = 'tabcontrol_tab_';
    let tabListCSSClassPostfix = '_tablist';
    let tabListItemCSSClassPostfix = '_tablist_item';
    let tabSelectedCSSClassPostfix = '_tab_selected';
    let tabAnchorIdPrefix = 'anchor_';

    this.createRepresentation = function () {
        let representation = '<div id="' + tabControlId + '"><ul class="' + model.CSSClassBase + tabListCSSClassPostfix + '">';

        for (let i = 0; i < model.tabControlTabs.length; ++i) {
            if (i === 0) {
                representation += '<li class="' + model.CSSClassBase + tabListItemCSSClassPostfix + '">' +
                    '<a href="#' + tabIdPrefix + i + '" class="' + model.CSSClassBase + tabSelectedCSSClassPostfix + '" id="'
                    + tabAnchorIdPrefix + tabIdPrefix + i + '">'
                    + model.tabControlTabs[i].title + '</a></li>'
            } else {
                representation += '<li class="' + model.CSSClassBase + tabListItemCSSClassPostfix + '">' +
                    '<a href="#' + tabIdPrefix + i + '" id="' + tabAnchorIdPrefix + tabIdPrefix + i + '">' + model.tabControlTabs[i].title + '</a></li>'
            }
        }
        representation += '<div class="clear"></div></ul>';

        for (let i = 0; i < model.tabControlTabs.length; ++i) {
            representation += '<div id="' + tabIdPrefix + i + '">';

            for (let j = 0; j < model.tabControlTabs[i].children.length; ++j) {
                representation += model.tabControlTabs[i].children[j].createRepresentation();
            }

            representation += '</div>';
        }

        representation += '</div>';

        return representation;
    };

    this.registerButtonEvents = function () {
        let tabs = $('#' + tabControlId).tabs({
            selected: 0,
            select: function (event, ui) {
                let index = tabs.tabs('option', 'selected');
                console.log('#' + tabAnchorIdPrefix + tabIdPrefix + index)
                $('#' + tabAnchorIdPrefix + tabIdPrefix + index).toggleClass(model.CSSClassBase + tabSelectedCSSClassPostfix);
                $(ui.tab).toggleClass(model.CSSClassBase + tabSelectedCSSClassPostfix);
            }
        });

        for (let i = 0; i < model.tabControlTabs.length; ++i) {
            for (let j = 0; j < model.tabControlTabs[i].children.length; ++j) {
                model.tabControlTabs[i].children[j].registerButtonEvents();
            }
        }
    }
}

function TabControlModel(CSSClassBase, tabControlTabs) {
    this.CSSClassBase = CSSClassBase;
    this.tabControlTabs = tabControlTabs;
}

function TabControlTabModel(title, children) {
    this.title = title;
    this.children = children;
}