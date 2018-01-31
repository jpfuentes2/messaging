# SHOP Messaging

SHOP Messaging is an event-based messaging service for delivering commerce
lifecycle emails through MailChimp. The service currently supports the
following lifecycle messages:

- User creation
- Password reset
- Order confirmation
- Order cancellation
- Shipment confirmation

It also contains experimental support for delivering events to a Slack channel.

## Building SHOP Messaging

Like the all of the application services in the SHOP Protocol, this service is
designed to run in a Docker container and orchestrated through Kubernetes or
Marathon. While the service can be built and run locally, it is not officially
supported.

### Option 1: Prebuilt Container

The easiest way to get started is to use our prebuilt Docker image. To do that,
simply pull:

```bash
$ docker pull shoppersshop/messaging:<version>
```

Replace `<version>` with your desired release version. The version may either
match one of the tags corresponding to a release or `master` to get the latest
and greatest.

### Option 2: Build Container

To run private branches or to test changes, it may be necessary to build your
own container. This section will walk you through that process.

#### Install Prerequisites

SHOP Messaging is built in Clojure. The following depencencies are necessary to
build the system:

- JVM
- [lein](http://leiningen.org)

#### Build

Build all the dependencies and create the JAR:

```bash
$ make build
```

#### Create Container

Once the application is built, you can create a Docker image with:

```bash
$ make docker
```

## Executing

In order to run the SHOP Messaging image, set the following environment
variables:

- `ENVIRONMENT`: can be "staging" or "production";
- `PHOENIX_USER` / `PHOENIX_PASSWORD` / `PHOENIX_URL`: Messaging needs to be
  able to access [Phoenix](https://github.com/ShoppersShop/phoenix) to access
  critical user and order data, so these environments need to be configured to
  enable access;
- `API_HOST`: hostname or IP address for this service to bind to;
- `KAFKA_BROKER`: Address of Kafka broker;
- `SCHEMA_REGISTRY_URL`: Address of Confluent Schema Registy, which is needed
  to decode messages sent in Kafka.

# Contributing

Thanks for considering to help out with our source code! We operate on an open
contributor model where anyone across the Internet can help in the form of peer
review, testing, and patches.

For more details about how to get involved, see our
[Contribution Guide](https://github.com/ShoppersShop/messaging/blob/master/CONTRIBUTING.md)

## License

MIT
