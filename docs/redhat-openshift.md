# RedHat Openshift

E.D.D.I is enterprise certified to run on RedHat Openshift and is therefore offered with support on the redhat marketplace: [https://marketplace.redhat.com/en-us/products/labsai](https://marketplace.redhat.com/en-us/products/labsai)

## EDDI-operator

[![Docker Repository on Quay](https://quay.io/repository/labsai/eddi-operator/status)](https://quay.io/repository/labsai/eddi-operator)

### Usage

#### Openshift Setup

**Prerequisites**

* Openshift 4.3+ Deployment
* Block Storage (Preferable with storage class)

**Installing the Operator from the RedHat Marketplace**

1. Head to the Operator section in the Admin Overview and go to the OperatorHub
2. Choose which version of the EDDI Operator to use (Marketplace or normal)
3. Click install and leave the defaults (All Namespaces, Update Channel alpha and Approval Strategy Automatic)
4. Click subscribe

**Using the operator**

After the installation of the operator, go to the installed Operators menu point and click on the first EDDI menu on top and create a new Instance. Below is a minimal CustomResource. The storageclass\_name has to be changed to the name of an existing StorageClass, the environment variable will be added as a label to the mongoDB deployment.

```yaml
apiVersion: labs.ai/v1alpha1
kind: Eddi
metadata:
  name: eddi
spec:
  size: 1
  mongodb:
    environment: prod
    storageclass_name: managed-nfs-storage
    storage_size: 20G
```

The operator will create a route automatically so you can access the EDDI admin panel. Per default the route will take the name of the CR. With the CR from above the route would look like this: `eddi-route-$NAMESPACE.apps.ocp.example.com` ($NAMESPACE will be the name of the project where the CR was created.)

