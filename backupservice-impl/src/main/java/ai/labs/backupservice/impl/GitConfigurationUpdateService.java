package ai.labs.backupservice.impl;

import ai.labs.backupservice.IGitConfigurationUpdateService;
import ai.labs.backupservice.IZipArchive;
import ai.labs.resources.rest.config.bots.IBotStore;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import javax.inject.Inject;
import javax.inject.Provider;
import java.io.InputStream;


@Slf4j
public class GitConfigurationUpdateService implements IGitConfigurationUpdateService {

    private Provider<IBotStore> botStore;
    private Provider<IZipArchive> zipArchive;


    @Inject
    public GitConfigurationUpdateService(Provider<IBotStore> providerIBotStore, Provider<IZipArchive> providerIZipArchive)  {
        this.botStore = providerIBotStore;
        this.zipArchive = providerIZipArchive;
    }


    @Override
    public void updateBot(InputStream zippedBotConfigFiles, String botId) {
        IBotStore botStore = this.botStore.get();
        IZipArchive zipArchive = this.zipArchive.get();

        log.info("Called updateBot");


    }

}
