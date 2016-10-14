#!/bin/sh
java -classpath '.:lib/*' -DEDDI_ENV=production io.sls.core.CoreServer
