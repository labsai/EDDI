package io.sls.core.sendmail;

import ai.labs.lifecycle.*;
import io.sls.memory.IConversationMemory;
import io.sls.memory.IData;
import io.sls.permission.IAuthorization;
import io.sls.permission.IPermissionStore;
import io.sls.permission.model.AuthorizedSubjects;
import io.sls.permission.model.AuthorizedUser;
import io.sls.permission.model.Permissions;
import io.sls.persistence.IResourceStore;
import io.sls.runtime.ThreadContext;
import io.sls.user.IUserStore;
import io.sls.user.model.User;
import io.sls.utilities.RuntimeUtilities;
import org.apache.commons.mail.EmailException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.*;

/**
 * @author ginccc
 */
@Singleton
public class SendMailTask implements ILifecycleTask {
    private static final String ACTION_KEY = "action";
    private static final String OUTPUT_KEY = "output:final";
    private final IUserStore userStore;
    private final IPermissionStore permissionStore;
    private final String host;
    private final String port;
    private final String auth;
    private final String from;
    private final String password;
    private final String configurationServerURI;
    private String[] addresses;
    private SendMail sendMail;
    private String subject;

    @Inject
    public SendMailTask(IUserStore userStore, IPermissionStore permissionStore,
                        @Named("mail.host") String host,
                        @Named("mail.port") String port,
                        @Named("mail.auth") String auth,
                        @Named("mail.from") String from,
                        @Named("mail.password") String password,
                        @Named("system.configurationServerURI") String configurationServerURI) {
        
        this.userStore = userStore;
        this.permissionStore = permissionStore;
        this.host = host;
        this.port = port;
        this.auth = auth;
        this.from = from;
        this.password = password;
        this.configurationServerURI = configurationServerURI;
    }

    @Override
    public String getId() {
        return "io.sls.sendmail";
    }

    @Override
    public Object getComponent() {
        return null;
    }

    @Override
    public List<String> getComponentDependencies() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getOutputDependencies() {
        return Collections.emptyList();
    }

    @Override
    public void init() {
        sendMail = new SendMail(new SendMail.Options(host, port, auth, from, password));
    }
    
    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        IData latestActions = memory.getCurrentStep().getLatestData(ACTION_KEY);
        if (latestActions == null) {
            return;
        }
        List<String> actions = (List<String>) latestActions.getResult();


        /*if (actions.contains("sendmail_to_admin")) {
            try {
                IData latestOutput = memory.getCurrentStep().getLatestData(OUTPUT_KEY);
                if (latestOutput == null) {
                    return;
                }
                String output = (String) latestOutput.getResult();
                sendMailToAdmins(memory, subject, output);
            } catch (EmailException e) {
                throw new LifecycleException(e.getLocalizedMessage(), e);
            } catch (IResourceStore.ResourceStoreException e) {
                throw new LifecycleException(e.getLocalizedMessage(), e);
            } catch (IResourceStore.ResourceNotFoundException e) {
                throw new LifecycleException(e.getLocalizedMessage(), e);
            }
        }*/

        if (actions.contains("sendmail_to_addresses")) {
            try {
                String userId = ThreadContext.get("currentuser:userid").toString();
                User user = userStore.readUser(userId);
                String message = user.getDisplayName() + " has asked a question which E.D.D.I. cannot answer.\n\n";
                String userInput = memory.getCurrentStep().getData("input:initial").getResult().toString();
                message += userInput;

                String conversationURL = configurationServerURI + "/editor/monitor/" + memory.getId();
                message += "\n\nTo view the full conversation, visit: <a href=\"" + conversationURL + "\">Conversation Log</a>";
                message += "\n\nKind Regards, \nE.D.D.I.";

                sendMail.send(addresses, user.getEmail(), subject, message);
            } catch (EmailException | IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
                throw new LifecycleException(e.getLocalizedMessage(), e);
            }
        }

    }

    private void sendMailToAdmins(IConversationMemory memory, String subject, String message) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, EmailException {
        Permissions permissions = permissionStore.readPermissions(memory.getBotId());
        AuthorizedSubjects admins = permissions.getPermissions().get(IAuthorization.Type.ADMINISTRATION);
        List<String> adminEmailAddresses = new LinkedList<String>();
        List<AuthorizedUser> adminsUsers = admins.getUsers();
        for (AuthorizedUser adminUser : adminsUsers) {
            String path = adminUser.getUser().getPath();
            User user = userStore.readUser(path.substring(path.lastIndexOf("/") + 1));
            adminEmailAddresses.add(user.getEmail());
        }
        sendMail.send(adminEmailAddresses.toArray(new String[adminEmailAddresses.size()]), null, subject, message);
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        String addressesString = (String) configuration.get("addresses");
        subject = (String) configuration.get("subject");
        StringTokenizer tokenizer = new StringTokenizer(addressesString, ";");
        List<String> addresses = new LinkedList<String>();
        while (tokenizer.hasMoreElements()) {
            addresses.add(tokenizer.nextToken().trim());
        }

        this.addresses = addresses.toArray(new String[addresses.size()]);
    }

    @Override
    public void setExtensions(Map<String, Object> extensions) throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
        if (!RuntimeUtilities.isNullOrEmpty(extensions)) {
            throw new UnrecognizedExtensionException("No extensions expected in SendMailTask!");
        }
    }
}
