---
description: >-
  Prompt & Conversation Management Middleware for Conversational AI APIs such as
  ChatGPT. Developed in Java, powered by Quarkus, provided with Docker, and
  orchestrated with Kubernetes or Openshift.
---

# E.D.D.I Documentation

v5.2.0 - STABLE

License: Apache License 2.0

Visit [here](https://eddi.labs.ai/) for further references about the project.



[![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://www.codacy.com/gh/labsai/EDDI/dashboard?utm\_source=github.com\&amp;utm\_medium=referral\&amp;utm\_content=labsai/EDDI\&amp;utm\_campaign=Badge\_Grade) [![CircleCI](https://circleci.com/gh/labsai/EDDI/tree/main.svg?style=svg)](https://circleci.com/gh/labsai/EDDI/tree/main)



## Intro

The Conversational AI Middleware System - E.D.D.I (Enhanced Dialog Driven Interface), has been developed with the focus on running it in cloud environments such as plain docker, kubernetes or openshift.

The most outstanding features are:

* Seamless integration with conversational or traditional REST APIs
* Configurable NLP and Behavior rules to facilitate conversations and monitor sensitive topics
* Support for multiple chatbots, including multiple versions of the same bot, running concurrently

Technical specifications:

* Resource-/REST-oriented architecture
* Java Quarkus framework
* JAX-RS
* Dependency Injection
* Prometheus integration (Metrics endpoint)
* Kubernetes integration (Liveness/Readiness endpoint)
* MongoDB for storing bot configurations and conversation logs
* OAuth 2.0 (Keycloak) for authentication and user management
* HTML, CSS, Javascript (Dashboard & Basic Chat UI)
