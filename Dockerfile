FROM labsai/base-image

ARG EDDI_VERSION
ARG EDDI_ENV
ARG EDDI_MEMORY_MIN
ARG EDDI_MEMORY_MAX

EXPOSE 7070

RUN mkdir -p /apiserver/ && \
    mkdir /apiserver/logs && \
    mkdir /apiserver/install

COPY /licenses/ /apiserver/licences
COPY /apiserver/target/apiserver-$EDDI_VERSION-package.zip /apiserver/install
COPY /start_eddi.sh /apiserver

RUN unzip /apiserver/install/apiserver-$EDDI_VERSION-package.zip -d /apiserver  && \
    chmod -R 777 /apiserver/ && \
    rm /apiserver/install/apiserver-$EDDI_VERSION-package.zip

WORKDIR /apiserver/
ENTRYPOINT /apiserver/start_eddi.sh
