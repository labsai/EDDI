FROM labsai/base-image

ARG EDDI_VERSION
ARG EDDI_ENV
ARG EDDI_MEMORY_MIN
ARG EDDI_MEMORY_MAX

EXPOSE 7070

RUN mkdir -p /apiserver/
WORKDIR /apiserver/

COPY /licenses/ /apiserver/licences

RUN mkdir /apiserver/logs

RUN mkdir /apiserver/install

COPY /apiserver/target/apiserver-$EDDI_VERSION-package.zip /apiserver/install
COPY /start_eddi.sh /apiserver

RUN unzip /apiserver/install/apiserver-$EDDI_VERSION-package.zip -d /apiserver

RUN chmod -R 777 /apiserver/
ENTRYPOINT /apiserver/start_eddi.sh
