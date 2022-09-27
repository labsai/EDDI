let eddi = {};
eddi.environments = ['unrestricted', 'restricted', 'test'];

eddi.importBotExamples = function () {
    $.ajax({
        method: 'POST',
        url: '/backup/import/examples', statusCode: {
            200: function (response) {
                if (typeof response !== 'undefined' && response.length !== 0) {
                    eddi.addBotDeployments(eddi.environments[0], response);
                }
            }
        }
    });
};

eddi.deployExampleBots = function () {
    $('#btnDeployExampleBots').hide();
    $('#no-bots-deployed').html('Deploying Bots...');
    eddi.importBotExamples();

};

eddi.addBotDeployments = function (environment, deploymentStatuses) {
    let $card = $('#card-1');
    let $bot = '';
    for (let i = 0; i < deploymentStatuses.length; i++) {
        $('#no-bots-deployed').remove();
        let deploymentStatus = deploymentStatuses[i];
        let link = '/chat' + '/' + environment + '/' + deploymentStatus.botId;

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
    if ($bot !== '') {
        $card.append($bot);
    }
};

eddi.fetchDeployedBots = function (environment) {
    $.ajax({
        url: '/administration/' + environment + '/deploymentstatus', statusCode: {
            200: function (response) {
                if (typeof response !== 'undefined' && response.length !== 0) {
                    eddi.addBotDeployments(environment, response);
                }
            }
        }
    });
};

$(function () {
    eddi.baseUri = window.location.protocol + "//" + window.location.host;
    $('#botBuilderUrl').prop('href', '/manage?apiUrl=' + encodeURIComponent(eddi.baseUri));

    for (let n = 0; n < eddi.environments.length; n++) {
        let environment = eddi.environments[n];
        eddi.fetchDeployedBots(environment);
    }
});
