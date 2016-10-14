var botId;
var conversationId;
var environment;
var baseURL = window.location.protocol + '//' + window.location.host;
var basePath;
var audioPlayerInit = false;

$(document).ready(function () {
    try {
        initKeyEvent();
        botId = $("#botId").val();
        environment = $("#environment").val();
        startNewConversation();

        $('#user_input_button_submit').click(function () {
            try {
                submitInput();
                refreshConversationLog();
            } catch (e) {
                log('ERROR', e.message);
            }
        });

        $('#change_password_btn').click(function () {
            var currentUserId = $('#currentUserId').val();
            if (typeof currentUserId !== 'undefined' && currentUserId !== '') {
                $("#change_password_dialog").dialog({
                    modal:true,
                    buttons:[
                        { text:"Change Password Now!",
                            click:function () {
                                $(this).dialog("close");
                                var currentUserId = $('#currentUserId').val();
                                var newPassword = $('#newPassword').val();
                                var confirmPassword = $('#confirmPassword').val();
                                if (newPassword !== confirmPassword) {
                                    alert('Passwords are not the same! Please reenter!');
                                    $('#newPassword').val('');
                                    $('#confirmPassword').val('');
                                } else {
                                    IRestUserStore.changePassword({userId:currentUserId, newPassword:newPassword, $callback:function (httpCode, xmlHttpRequest, value) {
                                        if (httpCode.toString().indexOf('2') == 0) {
                                            alert('Password has been successfully changed. You will be required to login with your new password at the next input.');
                                        } else {
                                            log('ERROR', 'Could not change password!');
                                        }
                                    }});

                                }
                            }
                        }
                    ],
                    position:{ my:"top"},
                    width:300
                });
            }
        });

        // validate signup form on keyup and submit
        $("#change_password_frm").validate({
            rules:{
                newPassword:{
                    required:true,
                    minlength:6
                },
                confirm_password:{
                    required:true,
                    minlength:6,
                    equalTo:"#newPassword"
                }
            },
            messages:{
                newPassword:{
                    required:"Please provide a password",
                    minlength:"Your password must be at least 6 characters long"
                },
                confirm_password:{
                    required:"Please provide a password",
                    minlength:"Your password must be at least 6 characters long",
                    equalTo:"Please enter the same password as above"
                }
            }
        });

        $('#undo').click(function () {
            try {
                undo();
            } catch (e) {
                log('ERROR', e.message);
            }
        });

        $('#redo').click(function () {
            try {
                redo();
            } catch (e) {
                log('ERROR', e.message);
            }
        });

        basePath = window.location.pathname;
        basePath = basePath.substring(0, basePath.lastIndexOf('/') + 1);
    } catch (e) {
        log('ERROR', e.message);
    }
});

function undo() {
    IRestBotEngine.undo({environment:environment, botId:botId, conversationId:conversationId});
    refreshConversationLog();
}

function redo() {
    IRestBotEngine.redo({environment:environment, botId:botId, conversationId:conversationId});
    refreshConversationLog();
}

function submitInput() {
    var message = $('#input_user').val();
    IRestBotEngine.say({environment:environment, botId:botId, conversationId:conversationId, $entity:message});
    $('#input_user').val('');
}

function initJPlayer(audioURL) {
    $('#jquery_jplayer_1').jPlayer({
        ready:function () {
            $(this).jPlayer('setMedia', {
                mp3:audioURL
            }).jPlayer('play');
        },
        swfPath:'/binary/default/desktop/images',
        supplied:'mp3',
        wmode:'window'
    });

    audioPlayerInit = true;
}

var refreshConversationLog = function () {
    $('#status_indicator').css('visibility', 'visible');
    var conversationMemory = IRestBotEngine.readConversationLog({environment:environment, botId:botId, conversationId:conversationId});

    if (conversationMemory === 'undefined' || conversationMemory === '') {
        startNewConversation();
    }

    var conversationState = conversationMemory.conversationState;

    if (conversationState == 'ERROR') {
        log('ERROR', "An Error has occurred. Please contact the administrator!");
        return;
    }

    if (conversationState == 'ENDED') {
        $("#ended_dialog").dialog({
            modal:true,
            buttons:[
                { text:"New Conversation!",
                    click:function () {
                        $(this).dialog("close");
                        startNewConversation();
                    }
                }
            ],
            position:{ my:"top"},
            width:300
        });
        $("#ended_dialog").dialog("option", "closeOnEscape", false);
        $('#status_indicator').css('visibility', 'hidden');
        $('#user_input_button_submit').addClass('ui-disabled');
        $('#redo').hide();
        $('#undo').hide();
    }

    if (conversationState == 'IN_PROGRESS') {
        setTimeout(refreshConversationLog, 1000);
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

        ioList.push({input:input, output:output, media:media});
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

        var mediaURIsString = latestInteraction.media;
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
        }

        $('#output_bot').html(latestInteraction.output);
    }

    var numBoxes = 4;
    /*for (var n = ioList.length - 1, historyNumber = 1; n >= 0; n--, historyNumber++) {
     var interaction = ioList[n];
     if (interaction.input == null) {
     continue;
     }
     $('.user_history_container_' + historyNumber + ' > .user_history_' + historyNumber).html(interaction.input);
     $('.user_history_container_' + historyNumber).css('visibility', 'visible');
     if (numBoxes == historyNumber) {
     break;
     }
     }*/

    if (conversationState != 'ENDED' && ioList.length > 1) {
        $('#undo').show();
    } else {
        $('#undo').hide();
    }

    if (conversationState != 'ENDED' && conversationMemory.redoCacheSize > 0) {
        $('#redo').show();
    } else {
        $('#redo').hide();
    }

    $('#status_indicator').css('visibility', 'hidden');
}

function startNewConversation() {
    var uri = IRestBotEngine.startConversation({environment:environment, botId:botId});
    if (typeof uri === 'undefined') {
        log('ERROR', 'Cannot start new conversation with bot! No bot deployed? [botId=' + botId + ']');
    } else {
        var conversationUriArray = uri.split("/");
        conversationId = conversationUriArray[conversationUriArray.length - 1];
        refreshConversationLog();
    }
}
