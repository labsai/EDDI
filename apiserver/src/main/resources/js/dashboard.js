REST.apiURL = '';
let eddi = {};
eddi.environments = ['unrestricted', 'restricted', 'test'];

eddi.deployExampleBots = function () {
    $('#btnDeployExampleBots').hide();
    $('#no-bots-deployed').html('Deploying Bots...');
    let deploymentStatuses = IRestImportService.importBotExamples();
    eddi.addBotDeployments(eddi.environments[0], deploymentStatuses);
};

eddi.addBotDeployments = function (environment, deploymentStatuses) {
    let $card = $('#card-1');
    let $bot = '';
    for (let i = 0; i < deploymentStatuses.length; i++) {
        $('#no-bots-deployed').remove();
        let deploymentStatus = deploymentStatuses[i];
        let link = REST.apiURL + '/chat' + '/' + environment + '/' + deploymentStatus.botId;

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

eddi.logout = function () {
    eddi.keycloak.logout();
};

$(function () {
    $.ajax({
        url: '/user/securityType', statusCode: {
            200: function (response) {
                if (response === 'keycloak') {
                    console.log('securityType is ' + response);

                    eddi.keycloak = Keycloak(
                        {
                            "realm": "EDDI",
                            "auth-server-url": "https://auth.labs.ai/auth",
                            "ssl-required": "external",
                            "resource": "eddi-engine",
                            "clientId": "eddi-engine",
                            "public-client": true,
                            "confidential-port": 0
                        });

                    eddi.keycloak.init({onLoad: 'login-required'});

                    $('#logoutBtn').prop('href', eddi.keycloak.createLogoutUrl());
                }
            }
        }
    });

    eddi.baseUri = window.location.protocol + "//" + window.location.host;
    $('#botBuilderUrl').prop('href', '//manager.labs.ai?apiUrl=' + encodeURIComponent(eddi.baseUri));

    $.ajax({
        url: '/user/isAuthenticated', statusCode: {
            204: function () {
                $('#logoutBtn').prop('disabled', false);
                $('#logoutBtn').prop('onclick', 'eddi.logout()');
                $('#logoutBtn').removeClass('disabled');
            }
        }
    });

    for (let n = 0; n < eddi.environments.length; n++) {
        let environment = eddi.environments[n];
        let deploymentStatuses = IRestBotAdministration.getDeploymentStatuses({environment: environment});
        eddi.addBotDeployments(environment, deploymentStatuses);
    }
});
