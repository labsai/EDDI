REST.apiURL = REST.apiURL.substr(0, REST.apiURL.length - 1);
let eddi = {};
eddi.environments = ['unrestricted', 'restricted', 'test'];
$(function () {
    let $card = $('#card-1');
    $card.empty();

    for (let n = 0; n < eddi.environments.length; n++) {
        eddi.environment = eddi.environments[n];
        let deploymentStatuses = IRestBotAdministration.getDeploymentStatuses({environment: eddi.environment});
        let $bot = '';

        for (let i = 0; i < deploymentStatuses.length; i++) {
            let deploymentStatus = deploymentStatuses[i];
            let link = REST.apiURL + '/chat' + '/' + eddi.environment + '/' + deploymentStatus.botId;

            let description = deploymentStatus.descriptor.description;
            let name = deploymentStatus.descriptor.name;
            $bot +=
                '<div class="botContainer">' +
                '<div class="botCard"><div class="font-weight-bold">Name</div><div>' + (name !== '' ? name : '-') + '</div></div>\n<br>' +
                '<div class="botCard"><div class="font-weight-bold">Description</div><div>' + (description !== '' ? description : '-') + '</div></div>\n<br>' +
                '<div class="botCard"><div class="font-weight-bold">BotId</div><div>' + deploymentStatus.botId + '</div></div>\n' +
                '<div class="botCard"><div class="font-weight-bold">Version</div><div>' + deploymentStatus.botVersion + '</div></div>\n' +
                '<div class="botCard"><div class="font-weight-bold">Environment</div><div>' + deploymentStatus.environment + '</div></div>\n' +
                '<div class="botCard"><div class="font-weight-bold">Created</div><div>' + moment(deploymentStatus.descriptor.createdOn).fromNow() + '</div></div>\n' +
                '<div class="botCard"><div class="font-weight-bold">Modified</div><div>' + moment(deploymentStatus.descriptor.lastModifiedOn).fromNow() + '</div></div>\n' +
                '<div class="botCard"><div class="font-weight-bold">Status</div><div>' + deploymentStatus.status + '</div></div>\n' +
                '<div class="botCard"><div class="font-weight-bold">Link</div><div><a href="' + link + '" target="_blank">Open</a></div></div>' +
                '</div>\n';
        }
        $card.append($bot);
    }
});
