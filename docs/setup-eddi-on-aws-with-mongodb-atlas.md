# Setting Up EDDI on AWS with MongoDB Atlas

This guide provides step-by-step instructions to set up EDDI on Amazon ECS (Fargate) and connect it to a MongoDB Atlas cluster.

## Prerequisites

1. **AWS Account**: Ensure you have an AWS account with the necessary permissions to create ECS clusters, task
   definitions, IAM roles and AWS Secrets Manager secrets
2. **MongoDB Atlas Account**: Create an account on [MongoDB Atlas](https://www.mongodb.com/cloud/atlas) if you don't
   have one
3. **An OIDC provider** (for example Keycloak) if the service will be reachable from outside a private network — see
   [Authentication](#authentication) below

## Step 1: Set Up MongoDB Atlas

### 1. Create a MongoDB Atlas Cluster

1. **Sign Up / Log In**:
    - Go to [MongoDB Atlas](https://www.mongodb.com/cloud/atlas) and log in

2. **Create a New Cluster**:
    - Click "Build a Cluster"
    - Choose AWS as the cloud provider and select the same region you will run ECS in
    - Choose the free tier (for development) or an appropriate plan for production purposes
    - Click "Create Cluster"

3. **Get the Connection String**:
    - After the cluster is created, click on "Connect"
    - Select "Connect Your Application"
    - Copy the connection string and add the database name `eddi` to its path. It has this shape:
      `mongodb+srv://<user>:<password>@<cluster-host>/eddi?retryWrites=true&w=majority`
    - EDDI reads the database name from its own setting (`mongodb.database`, default `eddi`), so the path only matters
      for authentication. Set `MONGODB_DATABASE` if you want a different database name.

### 2. Create a Database User

1. **Add Database User**:
    - Navigate to "Database Access" under the "Security" tab
    - Click "Add New Database User"
    - Create a user with read/write access to the `eddi` database and note the username and password. The password
      goes into the connection string, which is stored as a secret in Step 2 — never into the task definition.

### 3. Allow Network Access

1. **Network Access**:
    - Navigate to "Network Access" under the "Security" tab
    - Click "Add IP Address"
    - Add the egress addresses of your ECS tasks (the NAT gateway's Elastic IP when the tasks run in private subnets).
      For production, prefer an Atlas private endpoint (AWS PrivateLink) or VPC peering over an IP allow-list.

## Step 2: Store the Secrets

The MongoDB connection string contains a password, and EDDI's secrets vault needs a master key. Both belong in AWS
Secrets Manager, from where ECS injects them into the container as environment variables.

```bash
aws secretsmanager create-secret --name eddi/mongodb-connectionstring \
  --secret-string 'mongodb+srv://<user>:<password>@<cluster-host>/eddi?retryWrites=true&w=majority'

aws secretsmanager create-secret --name eddi/vault-master-key \
  --secret-string "$(openssl rand -base64 32)"
```

- **`MONGODB_CONNECTIONSTRING`** overrides EDDI's `mongodb.connectionString`, whose default points at a host named
  `mongodb` that does not exist on ECS.
- **`EDDI_VAULT_MASTER_KEY`** enables the [secrets vault](secrets-vault.md). Without it EDDI still starts, but the vault
  is disabled: secret-scoped properties and `${vault:...}` references cannot be stored or resolved, and the
  [audit ledger](audit-ledger.md) is written without an HMAC signature unless the independent audit key
  `EDDI_AUDIT_HMAC_KEY` (`eddi.audit.hmac-key`) is set. In production EDDI refuses to start on a weak
  key (shorter than 16 characters or a known default). Keep the key: the vault's data keys are wrapped with it, so
  replacing it later requires a key rotation, not just a new value.

`valueFrom` takes the secret's name when the secret is in the task's region; otherwise use the secret's **complete** ARN as
`create-secret` printed it, including the six-character random suffix Secrets Manager appends. The ECS
**task execution role** must be allowed to read both secrets (`secretsmanager:GetSecretValue` on their ARNs).

## Step 3: Set Up Amazon ECS

### 1. Create a Task Definition

The official image is published on Docker Hub as `labsai/eddi`. Pin a release tag rather than `latest` (see
[Release & Versioning](release-versioning.md) for the tag scheme). Save the following as `eddi-task.json`, replacing
the `<...>` placeholders:

```json
{
  "family": "eddi",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "executionRoleArn": "arn:aws:iam::<account-id>:role/<ecs-task-execution-role>",
  "containerDefinitions": [
    {
      "name": "eddi",
      "image": "docker.io/labsai/eddi:6.4.0",
      "essential": true,
      "portMappings": [
        { "containerPort": 7070, "hostPort": 7070, "protocol": "tcp" }
      ],
      "secrets": [
        {
          "name": "MONGODB_CONNECTIONSTRING",
          "valueFrom": "eddi/mongodb-connectionstring"
        },
        {
          "name": "EDDI_VAULT_MASTER_KEY",
          "valueFrom": "eddi/vault-master-key"
        }
      ],
      "environment": [
        { "name": "QUARKUS_OIDC_TENANT_ENABLED", "value": "true" },
        { "name": "QUARKUS_OIDC_AUTH_SERVER_URL", "value": "https://<keycloak-host>/realms/eddi" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "eddi",
          "awslogs-region": "<region>",
          "awslogs-stream-prefix": "eddi"
        }
      },
      "healthCheck": {
        "command": ["CMD-SHELL", "curl -f http://localhost:7070/q/health/ready || exit 1"],
        "interval": 30,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 60
      }
    }
  ]
}
```

Create the log group and register the task definition:

```bash
aws logs create-log-group --log-group-name eddi
aws ecs register-task-definition --cli-input-json file://eddi-task.json
```

Do **not** set `JAVA_OPTS_APPEND` to pass configuration. The image sets `JAVA_OPTS_APPEND` itself (it binds the HTTP
listener to `0.0.0.0`, selects the JBoss log manager, sets the file encoding and the bundled docs path), and a value
supplied at runtime replaces the image's value instead of adding to it. Every EDDI and Quarkus property can be set as an
environment variable instead: upper-case the name and replace `.` and `-` with `_`
(`mongodb.connectionString` becomes `MONGODB_CONNECTIONSTRING`).

### Authentication

A Fargate task runs EDDI in production launch mode, where EDDI refuses to start without authentication:

- `AuthStartupGuard` fails startup unless OIDC is enabled (`QUARKUS_OIDC_TENANT_ENABLED=true`) or the operator opts out
  with `EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true`.
- `HighValueSurfaceGuard` additionally fails startup while authorization is off unless `/mcp` and `/secretstore` are
  each opted out on their own, with `EDDI_MCP_ALLOW_UNAUTHENTICATED=true` and
  `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED=true`.

The task definition above enables OIDC. Point `QUARKUS_OIDC_AUTH_SERVER_URL` at your realm; the backend validates bearer
tokens for the client `eddi-backend` (`quarkus.oidc.client-id`, and the audience tokens must carry). See
[Security](security.md#authentication--keycloak-oidc) for the realm, roles and the other settings.

The three opt-outs are only acceptable for a task in a private subnet with no inbound route from outside your network.
Never combine them with a public load balancer: every API endpoint, the MCP tools and the secrets vault would then be
callable by anyone who can reach the port.

### 2. Create an ECS Cluster

1. **Create Cluster**:
    - Navigate to "Clusters" and click "Create Cluster"
    - Choose AWS Fargate as the infrastructure and follow the prompts

### 3. Create a Service

1. **Create Service**:
    - Go to "Services" and click "Create"
    - Select your cluster and the `eddi` task definition
    - Place the tasks in private subnets and, if users need to reach EDDI, put an Application Load Balancer with an
      HTTPS listener in front of port 7070

## Step 4: Verify

1. **Check the logs** in the `eddi` CloudWatch log group. A successful start shows no `[SECURITY]` errors and no
   MongoDB connection timeouts.
2. **Check readiness** through the load balancer: `GET /q/health/ready` reports `UP` once the database is connected and
   the startup migrations have run.
3. **Check MongoDB Atlas metrics** for connections from the task.

## Security Considerations

1. **Encryption**:
    - `mongodb+srv://` connection strings use TLS by default; keep it on. Terminate client HTTPS at the load balancer.

2. **Secrets**:
    - Keep the connection string and the vault master key in Secrets Manager (or SSM Parameter Store SecureString), never
      in the task definition's `environment` block, where they are readable by anyone who can describe the task.

3. **IAM Roles**:
    - Give the task execution role only `secretsmanager:GetSecretValue` on EDDI's secrets plus the ECR/CloudWatch
      permissions it needs, and keep the task role minimal.

4. **Network Configuration**:
    - Place ECS tasks in private subnets and use a NAT gateway for internet access (LLM provider APIs)
    - Configure security groups so only the load balancer can reach port 7070

---

If you encounter any issues, see [Docker](docker.md) for the container's environment variables and the AWS and MongoDB
Atlas documentation for the platform side.
