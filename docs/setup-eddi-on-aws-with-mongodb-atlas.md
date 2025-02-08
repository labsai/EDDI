# Setting Up EDDI on AWS with MongoDB Atlas

This guide provides step-by-step instructions to set up EDDI on Amazon ECS and connect it to a MongoDB Atlas cluster.

## Prerequisites

1. **AWS Account**: Ensure you have an AWS account with the necessary permissions to create ECS clusters, task
   definitions, and IAM roles
2. **MongoDB Atlas Account**: Create an account on [MongoDB Atlas](https://www.mongodb.com/cloud/atlas) if you don't
   have one

## Step 1: Set Up MongoDB Atlas

### 1. Create a MongoDB Atlas Cluster

1. **Sign Up / Log In**:
    - Go to [MongoDB Atlas](https://www.mongodb.com/cloud/atlas) and log in

2. **Create a New Cluster**:
    - Click "Build a Cluster"
    - Choose AWS as the cloud provider and select a region
    - Choose the free tier (for development) or an appropriate plan for production purposes
    - Click "Create Cluster"

3. **Configure Cluster**:
    - After the cluster is created, click on "Connect"
    - Select "Connect Your Application"
    - Copy the connection string (e.g., `mongodb+srv://<user>:<password>@<host>/eddi?retryWrites=true&w=majority -Dmongodb.database=eddi`)

### 2. Create a Database User

1. **Add Database User**:
    - Navigate to "Database Access" under the "Security" tab
    - Click "Add New Database User"
    - Create a user with the required roles and note the username and password

### 3. Whitelist IP Addresses

1. **Network Access**:
    - Navigate to "Network Access" under the "Security" tab
    - Click "Add IP Address"
    - Add the IP addresses that need access, including your local machine and ECS IP range

## Step 2: Set Up Amazon ECS

### 1. Create a Task Definition

1. **Navigate to ECS**:
    - Go to the Amazon ECS console
    - Click "Task Definitions" and then "Create new Task Definition"
    - Select "FARGATE" as the launch type

2. **Configure Task Definition**:
    - Use the following JSON configuration:
```json
{
   "containerDefinitions": [
   {
   "name": "eddi",
   "image": "<image-id>.dkr.ecr.<region>.amazonaws.com/eddi:latest",
   "cpu": 1024,
   "memoryReservation": 2048,
   "portMappings": [
   {
   "containerPort": 7070,
   "hostPort": 7070,
   "protocol": "tcp"
   }
   ],
   "essential": true,
   "command": [
   "/bin/bash"
   ],
   "environment": [
   {
   "name": "JAVA_OPTS_APPEND",
   "value": "-Dmongodb.connectionString=mongodb+srv://<user>:<password>@<host>/eddi?retryWrites=true&w=majority -Dmongodb.database=eddi"
   }
   ],
   "mountPoints": [],
   "volumesFrom": [],
   "logConfiguration": {
   "logDriver": "awslogs",
   "options": {
   "awslogs-group": "eddi",
   "awslogs-region": "<region>",
   "awslogs-stream-prefix": "eddi"
   }
   },
   "healthCheck": {
   "command": [
   "CMD-SHELL",
   "curl -f http://localhost:7070/q/health || exit 1"
   ],
   "interval": 30,
   "timeout": 5,
   "retries": 3
   }
   }
   ],
   "networkMode": "awsvpc",
   "revision": 1,
   "volumes": [],
   "status": "ACTIVE",
   "requiresAttributes": [
   {
   "name": "com.amazonaws.ecs.capability.logging-driver.awslogs"
   },
   {
   "name": "com.amazonaws.ecs.capability.docker-remote-api.1.24"
   },
   {
   "name": "ecs.capability.execution-role-awslogs"
   },
   {
   "name": "com.amazonaws.ecs.capability.ecr-auth"
   },
   {
   "name": "com.amazonaws.ecs.capability.docker-remote-api.1.19"
   },
   {
   "name": "com.amazonaws.ecs.capability.docker-remote-api.1.21"
   },
   {
   "name": "com.amazonaws.ecs.capability.task-iam-role"
   },
   {
   "name": "ecs.capability.container-health-check"
   },
   {
   "name": "ecs.capability.execution-role-ecr-pull"
   },
   {
   "name": "com.amazonaws.ecs.capability.docker-remote-api.1.18"
   },
   {
   "name": "ecs.capability.task-eni"
   }
   ],
   "placementConstraints": [],
   "compatibilities": [
   "EC2",
   "FARGATE"
   ],
   "requiresCompatibilities": [
   "EC2",
   "FARGATE"
   ],
   "cpu": "1024",
   "memory": "2048"
}
```

### 2. Create an ECS Cluster

1. **Create Cluster**:
    - Navigate to "Clusters" and click "Create Cluster"
    - Choose "Networking only" (Fargate) and follow the prompts

### 3. Create a Service

1. **Create Service**:
    - Go to "Services" and click "Create"
    - Select your cluster and task definition
    - Configure the service with the desired number of tasks and networking settings

## Step 3: Connect EDDI to MongoDB Atlas

1. **Modify Application Configuration**:
    - Ensure that your EDDI application uses the MongoDB connection string from the environment variables
    - Update any necessary configuration files

2. **Deploy the Application**:
    - Deploy your EDDI application to ECS using the service created

3. **Test the Connection**:
    - Verify that the application connects to MongoDB Atlas by checking application logs and MongoDB Atlas metrics

## Security Considerations

1. **Encryption**:
    - Use TLS/SSL for encrypted connections (`ssl=true` in the connection string)

2. **IAM Roles**:
    - Assign IAM roles to ECS tasks to limit permissions

3. **Network Configuration**:
    - Place ECS tasks in private subnets and use a NAT gateway for internet access
    - Configure security groups for ECS tasks and MongoDB Atlas

---

By following these steps, you can set up EDDI on Amazon ECS and connect it to MongoDB Atlas securely and efficiently.
If you encounter any issues or have further questions, please refer to the AWS and MongoDB Atlas documentation or
contact support.