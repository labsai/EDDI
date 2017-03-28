var eddi = {};

var Message;
Message = function (arg) {
    this.text = arg.text, this.message_side = arg.message_side;
    this.draw = function (_this) {
        return function () {
            var $message;
            $message = $($('.message_template').clone().html());
            $message.addClass(_this.message_side).find('.text').html(_this.text);
            $('.messages').append($message);
            return setTimeout(function () {
                return $message.addClass('appeared');
            }, 0);
        };
    }(this);
    return this;
};
$(function () {
    var getMessageText, message_side, displayMessage;
    message_side = 'right';
    getMessageText = function () {
        var $message_input;
        $message_input = $('.message_input');
        return $message_input.val();
    };
    displayMessage = function (text, side) {
        var $messages, message;
        if (text.trim() === '') {
            return;
        }
        $('.message_input').val('');
        $messages = $('.messages');
        message_side = side ? 'right' : 'left';
        message = new Message({
            text: text,
            message_side: side
        });
        message.draw();
        return $messages.animate({scrollTop: $messages.prop('scrollHeight')}, 300);
    };
    $('.send_message').click(function (e) {
        var userMessage = getMessageText();
        submitUserMessage(userMessage);
        return displayMessage(userMessage, 'right');
    });
    $('.message_input').keyup(function (e) {
        if (e.which === 13) {
            var userMessage = getMessageText();
            submitUserMessage(userMessage);
            return displayMessage(userMessage, 'right');
        }
    });

    var submitUserMessage = function (userMessage) {
        $.ajax({
            type: "POST",
            url: "/bots/" + eddi.environment + "/" + eddi.botId + "/" + eddi.conversationId,
            data: userMessage,
            contentType: "text/plain",
            success: function () {
                setTimeout(loadConversationLog, 2000);
            }
        });
    };

    var loadConversationLog = function () {
        $.get("/bots/" + eddi.environment + "/" + eddi.botId + "/" + eddi.conversationId).done(function (data) {
            refreshConversationLog(data);
        });
    };

    var refreshConversationLog = function (conversationMemory) {
        var conversationState = conversationMemory.conversationState;

        if (conversationState == 'ERROR') {
            log('ERROR', "An Error has occurred. Please contact the administrator!");
            return;
        }

        if (conversationState == 'ENDED') {
            $("#ended_dialog").dialog({
                modal: true,
                buttons: [
                    {
                        text: "New Conversation!",
                        click: function () {
                            $(this).dialog("close");
                            startNewConversation();
                        }
                    }
                ],
                position: {my: "top"},
                width: 300
            });
            $("#ended_dialog").dialog("option", "closeOnEscape", false);
            $('#status_indicator').css('visibility', 'hidden');
            $('#user_input_button_submit').addClass('ui-disabled');
            $('#redo').hide();
            $('#undo').hide();
        }

        if (conversationState == 'IN_PROGRESS') {
            setTimeout(loadConversationLog, 1000);
            return;
        }

        var ioList = [];
        for (var i = 0; i < conversationMemory.conversationSteps.length; i++) {
            var step = conversationMemory.conversationSteps[i];

            var input = null;
            var output = null;
            var media = null;
            for (var x = 0; x < step.data.length; x++) {
                var obj = step.data[x];
                if (obj.key.indexOf('input') == 0) {
                    input = obj.value;
                } else if (obj.key.indexOf('output') == 0) {
                    output = obj.value;
                } else if (obj.key.indexOf('media') == 0) {
                    media = obj.value;
                } else if (obj.key.indexOf('actions') == 0) {
                }
            }

            ioList.push({input: input, output: output, media: media});
        }

        if (ioList.length > 0) {
            var latestInteraction = ioList[ioList.length - 1];
            if (latestInteraction === 'undefined') {
                latestInteraction = {};
            }

            //apply media/images
            //var mediaURI = latestInteraction.media;
            /*if (typeof mediaURI !== 'undefined' && mediaURI.indexOf('image') == 0) {
             var mediaURL = baseURL + '/binary/default/mobile/images' + mediaURI.substring(mediaURI.lastIndexOf('/'));
             var img = $('<img id="' + mediaURI.substring(mediaURI.lastIndexOf('/' + 1, mediaURI.lastIndexOf('.'))) + '">');
             img.attr('src', mediaURL);
             img.attr('class', 'media_img');
             $('#desktop').html(img);
             } else {
             $('#desktop').html('<div style="text-align: center;width: 100%; height: 100%;">Nothing to show at the moment..</div>');
             }*/

            if (latestInteraction.output == null) {
                latestInteraction.output = '';
            }

            /*var mediaURIsString = latestInteraction.media;
             if (mediaURIsString != null) {
             var mediaURIs = mediaURIsString.split(',');
             for (var n = 0; n < mediaURIs.length; n++) {
             var mediaURI = mediaURIs[n];
             if (mediaURI.indexOf('pdf') == 0) {
             var fileName = mediaURI.substring(mediaURI.lastIndexOf('/') + 1);
             var mediaURL = baseURL + '/binary/default/desktop/binary/' + fileName;
             latestInteraction.output += '<br /><a target="_blank" href="' + mediaURL + '">' + fileName + '</a>'
             }
             }
             }*/

            displayMessage(latestInteraction.output, 'left');

            $('.message_input').focus();
        }
    };

    var deployBot = function (environment, botId, botVersion) {
        $.post("/administration/" + environment + "/deploy/" + botId + "?version=" + botVersion).done(function () {
            checkBotDeploymentStatus();
        });
    };

    var checkBotDeploymentStatus = function () {
        $.get("/administration/" + eddi.environment + "/deploymentstatus/" + eddi.botId + "?version=" + eddi.botVersion).done(function (data) {
            if (data === 'IN_PROGRESS') {
                setTimeout(checkBotDeploymentStatus, 1000);
            } else if (data === 'ERROR') {
                alert('An error occurred while bot deployment');
            } else if (data === 'READY') {
                proceedConversation();
            }
        });
    };

    var createConversation = function (environment, botId) {
        $.post("/bots/" + environment + "/" + botId).done(function (data, status, xhr) {
            var conversationUriArray = xhr.getResponseHeader('Location').split("/");
            eddi.conversationId = conversationUriArray[conversationUriArray.length - 1];
            proceedConversation();
        });
    };

    var checkConversationStatus = function (environment, botId, conversationId) {
        $.get("/bots/" + environment + "/" + botId + "/" + conversationId).always(function (data, status, xhr) {
            if (status === "error") {
                alert("Checking conversation has yield into an error.. ");
            } else if (status === "success") {
                eddi.conversationState = data.conversationState;
                if (eddi.conversationState !== 'READY') {
                    alert("Conversation is not Ready... (state=" + eddi.conversationState + ")");
                }

                loadConversationLog();
            }
        });
    };

    var getQueryParts = function (href) {
        var query = $.url.parse(href);
        var path = query.path;

        var parts = path.split("/");

        var environment = null;
        var botId = null;
        var conversationId = null;
        var botVersion = null;

        environment = typeof parts[2] !== 'undefined' ? decodeURIComponent(parts[2]) : environment;
        botId = typeof parts[3] !== 'undefined' ? decodeURIComponent(parts[3]) : botId;
        conversationId = typeof parts[4] !== 'undefined' ? decodeURIComponent(parts[4]) : conversationId;
        if (query.params && query.params.version) {
            botVersion = query.params.version;
        }

        return {conversationId: conversationId, environment: environment, botId: botId, botVersion: botVersion};
    };

    var proceedConversation = function () {
        if (!eddi.conversationId) {
            createConversation(eddi.environment, eddi.botId);
        } else {
            checkConversationStatus(eddi.environment, eddi.botId, eddi.conversationId);
        }
    };

    var checkBotDeployment = function () {
        //check if bot is deployed
        $.get("/administration/" + eddi.environment + "/deploymentstatus/" + eddi.botId + "?version=" + eddi.botVersion)
            .done(function (data) {
                if (data === 'NOT_FOUND') {
                    alert('Bot is not deployed at the moment.. Deploy NOW?');
                    deployBot(eddi.environment, eddi.botId, eddi.botVersion);
                }

                if (data === 'ERROR') {
                    alert('Bot encountered an server error :-(');
                }

                if (data === 'IN_PROGRESS') {
                    alert('Bot is still warming up...');
                }

                if (data === 'READY') {
                    proceedConversation();
                }
            });
    };

    $(document).ready(function () {
        var extractedParams = getQueryParts(window.location.href);
        //extract environment from URL
        if (extractedParams.environment !== null) {
            eddi.environment = extractedParams.environment;
        }

        //extract botId from URL
        if (extractedParams.botId !== null) {
            eddi.botId = extractedParams.botId;
        }

        //extract conversationId
        if (extractedParams.conversationId !== null) {
            eddi.conversationId = extractedParams.conversationId;
        }

        //extract conversationId
        if (extractedParams.botVersion !== null) {
            eddi.botVersion = extractedParams.botVersion;
            checkBotDeployment();
        } else {
            $.get("/botstore/bots/" + eddi.botId + "/currentversion", function (data) {
                eddi.botVersion = data;
                checkBotDeployment();
            });
        }
    });

    /*displayMessage('Hello Philip! :)', 'left');
     setTimeout(function () {
     return displayMessage('Hi Sandy! How are you?', 'right');
     }, 1000);
     return setTimeout(function () {
     return displayMessage('I\'m fine, thank you!', 'left');
     }, 2000);*/
});