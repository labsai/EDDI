FROM java:8-jre

#TODO move the env to docker-compose
EXPOSE 7070
ENV EDDI_VERSION 4.0-SNAPSHOT
ENV EDDI_ENV production
ENV EDDI_DB mongodb
ENV EDDI_AUTH unknown
#create workdir
RUN mkdir -p /apiserver/
WORKDIR /apiserver/
#create log dir
RUN mkdir /apiserver/logs
#create distribution/target dir (artifact used for deployment)
RUN mkdir /apiserver/install
#COPY not ADD the artifact (the current eddi structure is assumed)
COPY /apiserver/target/apiserver-$EDDI_VERSION-package.zip /apiserver/install
#COPY not ADD the artifact (artifact is in the root folder)
#COPY apiserver-$EDDI_VERSION-package.zip /
#un
RUN unzip /apiserver/install/apiserver-$EDDI_VERSION-package.zip -d /apiserver
#make sure the right access is granted
#see the username
#CMD exec echo "$(id -u -n)"
#see the structure
#CMD exec ls -la
#make sure all file (after unzip) have the right rights(executable) for one file use RUN chmod +x <file>
RUN chmod -R 777 /apiserver/
ENTRYPOINT java -classpath '.:lib/*' -DEDDI_ENV=$EDDI_ENV -DEDDI_AUTH=$EDDI_AUTH -DEDDI_DB=$EDDI_DB ai.labs.api.ApiServer

#for using a script as entrypoint the file NlpServerStartup.sh is not found.
#specify the sh
#ENTRYPOINT ["/bin/sh", "-c", "/apiserver/NlpServerStartup.sh" "$EDDI_ENV" "$EDDI_AUTH" "$EDDI_DB"]
#sh is added by default /bin/sh -c
#ENTRYPOINT exec /apiserver/NlpServerStartup.sh $EDDI_ENV $EDDI_AUTH $EDDI_DB
