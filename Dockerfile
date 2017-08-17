FROM openjdk:8-jre-alpine

ARG EDDI_VERSION
ARG EDDI_ENV
ARG EDDI_MEMORY_MIN
ARG EDDI_MEMORY_MAX

EXPOSE 7070

#install bash
RUN apk update
RUN apk add bash

#create workdir
RUN mkdir -p /apiserver/
WORKDIR /apiserver/
#create log dir
RUN mkdir /apiserver/logs
#create distribution/target dir (artifact used for deployment)
RUN mkdir /apiserver/install
#COPY not ADD the artifact (the current eddi structure is assumed)
COPY /apiserver/target/apiserver-$EDDI_VERSION-package.zip /apiserver/install
COPY /start_eddi.sh /apiserver
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
ENTRYPOINT /apiserver/start_eddi.sh

#for using a script as entrypoint the file NlpServerStartup.sh is not found.
#specify the sh
#ENTRYPOINT ["/bin/sh", "-c", "/apiserver/NlpServerStartup.sh" "$EDDI_ENV" "$EDDI_AUTH" "$EDDI_DB"]
#sh is added by default /bin/sh -c
#ENTRYPOINT exec /apiserver/NlpServerStartup.sh $EDDI_ENV $EDDI_AUTH $EDDI_DB
