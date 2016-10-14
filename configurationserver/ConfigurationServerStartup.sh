#!/bin/sh
java -classpath '.:lib/*' -DEDDI_ENV=production io.sls.configuration.ConfigurationServer
