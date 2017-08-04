#!/bin/bash
set -e
set -x

curl https://raw.githubusercontent.com/kubernetes/helm/master/scripts/get > get_helm.sh
chmod 700 get_helm.sh
./get_helm.sh
helm init
helm repo remove local
helm repo add differ-charts https://differ-charts.storage.googleapis.com
helm repo update