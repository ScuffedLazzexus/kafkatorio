# Running Kafkatorio

### Building the project

#### Requirements

* Docker
* Docker Compose
* Java 11

Kafkatorio uses [Gradle](https://docs.gradle.org/current/userguide/getting_started.html) to
build the project, and Docker Compose to create images.

* `./gradlew build` - build and test the project
* `./gradlew runKafkatorio` - run locally (if Docker is running, a Kafka instance will start)
* `./gradlew dockerComposeBuild` - build, test, and create Kafkatorio Docker images

### Server set up

> See [./kafkatorio-platform/docker-compose.yml](kafkatorio-platform/docker-compose.yml) for a
> complete example

1. Copy the [kafkatorio-platform](./kafkatorio-platform) directory to your computer
2. Rename
   [`example.secret.kafkatorio-config.yml`](./kafkatorio-platform/example.secret.kafkatorio-config.yml)
   to `.secret.kafkatorio-config.yml`

   In it, define both
    * a JWT secret password (a long alphanumeric string is best, you won't need to type this
      password manually)
    * a Factorio server, with a short-ID and a description.
3. Rename [`example.env`](./kafkatorio-platform/example.env) to `.env`
4. Create a JWT for your Factorio server, by going to https://jwt.io/

    * In 'PAYLOAD: DATA', set `sub` to your Factorio server's short ID.
      ```json
      {
        "sub": "<your server short ID>",
        "iat": ...
      }
      ```
      (`iat`, 'issued-at-time', should update automatically to be the current time.)
    * Replace `your-256-bit-secret` with your JWT secret password

   Copy the 'Encoded' token (it should start with `ey`).

   In the `.env` file, set `KAFKATORIO_TOKEN` to equal the token.
5. In `kafkatorio-platform`, start the docker containers

   ```terminal
   docker-compose up -d; docker-compose logs -f
   ```

### Unraid set up

> See [./kafkatorio-platform/unraid/docker-compose.yml](kafkatorio-platform/unraid/docker-compose.yml)

This runs the Kafkatorio services on the same Unraid server as the Factorio server. The Docker
syslog log driver runs in the Docker daemon on the host, so the syslog port only needs to listen
on `127.0.0.1`, and Kafka isn't exposed at all.

1. Install the *Compose Manager* plugin (or *Compose Manager Plus*) from the Apps tab.
2. Build the images locally, and copy them to Unraid (no registry needed):

   ```terminal
   ./gradlew dockerComposeBuild
   docker save $(docker images --format '{{.Repository}}:{{.Tag}}' | grep dev.adamko.kafkatorio) \
     | gzip | ssh root@<unraid> 'gunzip | docker load'
   ```
3. On Unraid, create `/mnt/user/appdata/kafkatorio-platform/` containing
    * `.secret.kafkatorio-config.yml` (see above)
    * `config/kafka-server.properties` (copy from [./kafkatorio-platform/config](kafkatorio-platform/config))
    * `config/cluster_id` - a fixed Kafka cluster ID, so the Kafka data survives image rebuilds.
      Generate one with `docker run --rm --entrypoint ./bin/kafka-storage.sh <kafka-kraft image> random-uuid`.
4. Create a Compose Manager stack, with the Unraid `docker-compose.yml`, and
   [`example.env`](kafkatorio-platform/unraid/example.env) as its `.env`. Start the stack.
5. Edit the Factorio container, enable 'Advanced View', and set 'Extra Parameters' to

   ```
   --log-driver=syslog --log-opt syslog-address=tcp://127.0.0.1:12035 --log-opt syslog-format=rfc5424 --log-opt mode=non-blocking --log-opt tag=<KAFKATORIO_TOKEN>
   ```

   Docker won't start a container if its TCP syslog server isn't reachable, so on the Docker tab
   give the Factorio container an autostart 'wait' of ~30 seconds, so the Kafkatorio services start
   first.
6. The web map is on port `12081`. It also proxies `/kafkatorio/` to the events server, so a
   reverse proxy (e.g. Nginx Proxy Manager) only needs to forward to that one port - make sure
   WebSocket support is enabled.
7. In Factorio, run `/kafkatorio prototypes` and then `/kafkatorio chunks 10` to send the existing
   map.
