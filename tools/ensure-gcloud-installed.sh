#!/bin/bash
set -e
set -x

# Install and configure gcloud
sudo /opt/google-cloud-sdk/bin/gcloud --quiet components update --version 120.0.0
sudo /opt/google-cloud-sdk/bin/gcloud --quiet components update --version 120.0.0 kubectl
echo $ACCT_AUTH | base64 --decode -i > ${HOME}/account-auth.json
sudo /opt/google-cloud-sdk/bin/gcloud auth activate-service-account --key-file ${HOME}/account-auth.json
sudo /opt/google-cloud-sdk/bin/gcloud config set project $PROJECT_NAME
sudo /opt/google-cloud-sdk/bin/gcloud --quiet config set container/cluster $CLUSTER_NAME

# Reading the zone from the env var is not working so we set it here
sudo /opt/google-cloud-sdk/bin/gcloud config set compute/zone ${CLOUDSDK_COMPUTE_ZONE}
sudo /opt/google-cloud-sdk/bin/gcloud --quiet container clusters get-credentials $CLUSTER_NAME
