function TabControl(model) {
    var tabControlId = 'tabcontrol';
    var tabIdPrefix = 'tabcontrol_tab_';
    var tabListCSSClassPostfix = '_tablist';
    var tabListItemCSSClassPostfix = '_tablist_item';
    var tabSelectedCSSClassPostfix = '_tab_selected';
    var tabAnchorIdPrefix = 'anchor_';

    this.createRepresentation = function () {
        var representation = '<div id="' + tabControlId + '"><ul class="' + model.CSSClassBase + tabListCSSClassPostfix + '">';

        for (var i = 0; i < model.tabControlTabs.length; ++i) {
            if (i == 0) {
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

        for (var i = 0; i < model.tabControlTabs.length; ++i) {
            representation += '<div id="' + tabIdPrefix + i + '">';

            for (var j = 0; j < model.tabControlTabs[i].children.length; ++j) {
                representation += model.tabControlTabs[i].children[j].createRepresentation();
            }

            representation += '</div>';
        }

        representation += '</div>';

        return representation;
    }

    this.registerButtonEvents = function () {
        var tabs = $('#' + tabControlId).tabs({
            selected: 0,
            select: function (event, ui) {
                var index = tabs.tabs('option', 'selected');
                console.log('#' + tabAnchorIdPrefix + tabIdPrefix + index)
                $('#' + tabAnchorIdPrefix + tabIdPrefix + index).toggleClass(model.CSSClassBase + tabSelectedCSSClassPostfix);
                $(ui.tab).toggleClass(model.CSSClassBase + tabSelectedCSSClassPostfix);
            }
        });

        for (var i = 0; i < model.tabControlTabs.length; ++i) {
            for (var j = 0; j < model.tabControlTabs[i].children.length; ++j) {
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