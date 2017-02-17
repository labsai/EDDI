FROM java:8-jre

#TODO move the env to docker-compose
EXPOSE 7070
ENV EDDI_VERSION 3.0-SNAPSHOT
ENV EDDI_ENV: development
ENV EDDI_DB: mongodb
ENV EDDI_AUTH: unknown
#create workdir
RUN mkdir -p /nlpserver/
WORKDIR /nlpserver/
#create log dir
RUN mkdir /nlpserver/logs
#create distribution/target dir (artifact used for deployment)
RUN mkdir /nlpserver/install
#COPY not ADD the artifact (the current eddi structure is assumed)
COPY /nlpserver/target/nlpserver-$EDDI_VERSION-package.zip /nlpserver/install
#COPY not ADD the artifact (artifact is in the root folder)
#COPY nlpserver-$EDDI_VERSION-package.zip /
#un
RUN unzip /nlpserver/install/nlpserver-$EDDI_VERSION-package.zip -d /nlpserver
#make sure the right access is granted
#see the username
#CMD exec echo "$(id -u -n)"
#see the structure
#CMD exec ls -la
#make sure all file (after unzip) have the right rights(executable) for one file use RUN chmod +x <file>
RUN chmod -R 777 /nlpserver/
ENTRYPOINT java -classpath '.:lib/*' -DEDDI_ENV=$EDDI_ENV -DEDDI_AUTH=$EDDI_AUTH -DEDDI_DB=$EDDI_DB ai.labs.nlp.NlpServer

#for using a script as entrypoint the file NlpServerStartup.sh is not found.
#specify the sh
#ENTRYPOINT ["/bin/sh", "-c", "/nlpserver/NlpServerStartup.sh" "$EDDI_ENV" "$EDDI_AUTH" "$EDDI_DB"]
#sh is added by default /bin/sh -c
#ENTRYPOINT exec /nlpserver/NlpServerStartup.sh $EDDI_ENV $EDDI_AUTH $EDDI_DB
