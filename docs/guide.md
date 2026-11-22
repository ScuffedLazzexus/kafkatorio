# Running Kafkatorio

This guide sets up Kafkatorio for a Factorio server, from building it to a public website.

- [What you need](#what-you-need)
- [1. Build the Docker images and the mod](#1-build-the-docker-images-and-the-mod)
- [2. Configure Kafkatorio](#2-configure-kafkatorio)
- [3. Start Kafkatorio](#3-start-kafkatorio)
- [4. Connect the Factorio server](#4-connect-the-factorio-server)
- [5. Send the existing map](#5-send-the-existing-map)
- [6. Icons and names](#6-icons-and-names)
- [7. Put it on the internet](#7-put-it-on-the-internet)
- [Unraid](#unraid)
- [Updating](#updating)
- [Troubleshooting](#troubleshooting)
- [Developing](#developing)

## What you need

* **A Factorio 2.0 server running in Docker**, for example
  [factoriotools/factorio](https://hub.docker.com/r/factoriotools/factorio). Kafkatorio reads
  the server's log through Docker's syslog log driver. The example setup can run the Factorio
  server for you.
* **A Docker host for Kafkatorio**, ideally the same one as the Factorio server. It needs about
  5 GB of memory while the existing map is sent (step 5), and much less after that. (The memory
  limits are the `-Xmx` options in `docker-compose.yml`.)
* **A machine to build on**, with Docker, Git and a JDK (11 or newer). There are no published
  images, so you build them yourself. Gradle downloads everything else (including Node.js, for the
  mod).

| Port         | Service                                     | Expose?                                    |
|--------------|---------------------------------------------|--------------------------------------------|
| `12081`      | the website (`web-map`)                     | yes, through a reverse proxy (step 7)      |
| `12035`      | receives the Factorio server's log (syslog) | only to the Factorio server's Docker host  |
| `18090`      | Kafka UI, for debugging                     | no - Kafka has no authentication           |
| `34197/udp`  | the Factorio game (if the example runs it)  | yes, for players                           |

## 1. Build the Docker images and the mod

```shell
git clone <this repository> kafkatorio
cd kafkatorio

./gradlew \
  :modules:infra-kafka-cluster:dockerComposeBuild \
  :modules:events-processors:dockerComposeBuild \
  :modules:events-server-syslog:dockerComposeBuild \
  :modules:events-server-web:dockerComposeBuild \
  :modules:web-map:dockerComposeBuild \
  :modules:events-mod:distZip
```

This creates five images, tagged `localhost/dev.adamko.kafkatorio/<name>:<version>`
(`kafka-kraft`, `events-processors`, `events-server-syslog`, `events-server-web`, `web-map`), and
the mod, `modules/events-mod/build/distributions/kafkatorio-events_<version>.zip`. The version is
the `version` in [`build.gradle.kts`](../build.gradle.kts).

If you build on a different machine from the Docker host, copy the images over (no registry needed):

```shell
docker save $(docker images --format '{{.Repository}}:{{.Tag}}' | grep dev.adamko.kafkatorio) \
  | gzip | ssh <docker host> 'gunzip | docker load'
```

## 2. Configure Kafkatorio

Copy the [`kafkatorio-platform`](./kafkatorio-platform) directory to the Docker host. All
Kafkatorio's data is kept in it. In that directory:

1. Copy [`example.env`](./kafkatorio-platform/example.env) to `.env`, and check
   `KAFKATORIO_VERSION` matches the images you built.

2. Copy [`example.secret.kafkatorio-config.yml`](./kafkatorio-platform/example.secret.kafkatorio-config.yml)
   to `.secret.kafkatorio-config.yml`, and set

   * `jwt-secret`: a long random password, used to sign the Factorio server's token. You won't
     need to type it anywhere. For example, `openssl rand -hex 32`.
   * `kafkatorio-servers`: your Factorio server. The key is the server's *token name* (put in
     the token, below), and the value is its *ID*, which is used in the website's URLs
     (`/servers/<ID>/`). They can be the same, e.g. `my-server: "my-server"`.

3. Create a Kafka cluster ID, so Kafka's data survives image rebuilds:

   ```shell
   docker run --rm --entrypoint ./bin/kafka-storage.sh \
     localhost/dev.adamko.kafkatorio/kafka-kraft:<version> random-uuid > config/cluster_id
   ```

### Create a token for the Factorio server

The Factorio server's log is tagged with a token (a JWT), so Kafkatorio knows which server it's
from, and ignores logs from anyone else. Create it with the `jwt-secret` and the server's token
name (`sub`):

```shell
python3 -c 'import base64,hashlib,hmac,json,sys,time
b=lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
j=lambda o: b(json.dumps(o,separators=(",",":")).encode())
m=j({"alg":"HS256","typ":"JWT"})+"."+j({"sub":sys.argv[2],"iat":int(time.time())})
print(m+"."+b(hmac.new(sys.argv[1].encode(),m.encode(),hashlib.sha256).digest()))' \
  '<jwt-secret>' '<token name>'
```

(Or use [jwt.io](https://jwt.io/), with algorithm HS256, the payload
`{"sub": "<token name>", "iat": <current unix time>}`, and the `jwt-secret` as the secret. But
then you're typing the secret into a website.)

The token starts with `ey`. Put it in `.env` as `KAFKATORIO_TOKEN`.

## 3. Start Kafkatorio

```shell
docker compose up -d
docker compose logs -f
```

The website is on `http://<docker host>:12081`. It's empty until the Factorio server sends
something.

## 4. Connect the Factorio server

### Install the mod

Put `kafkatorio-events_<version>.zip` in the Factorio server's `mods` directory (for the example
server, `factorio-server/mods/`), and restart it.

Players need the same mod, with the same version, to join, so give them the zip too (it goes in
their Factorio `mods` directory). Don't let them download it from the mod portal: the portal's
`kafkatorio-events` is the original project, for Factorio 1.1, and its version numbers overlap
with this one.

### Forward the server's log to Kafkatorio

**If the example runs your Factorio server**, this is already set up. Start it with:

```shell
docker compose --profile factorio up -d
```

**If you already have a Factorio container**, add these options to it, then recreate it:

```shell
--log-driver=syslog \
--log-opt syslog-address=tcp://127.0.0.1:12035 \
--log-opt syslog-format=rfc5424 \
--log-opt mode=non-blocking \
--log-opt tag=<KAFKATORIO_TOKEN>
```

or in a Compose file:

```yaml
    logging:
      driver: syslog
      options:
        syslog-address: "tcp://127.0.0.1:12035"
        syslog-format: "rfc5424"
        mode: "non-blocking"
        tag: "<KAFKATORIO_TOKEN>"
```

The log driver runs in the Docker daemon, not in the container, so `127.0.0.1` is the Docker
host. If the Factorio server runs on another host, use this host's address, and in
`docker-compose.yml` change `127.0.0.1:12035:9002` to `12035:9002` (and firewall it so only the
Factorio host can connect).

Docker won't start a container if its syslog server can't be reached, so Kafkatorio must be
running before the Factorio server starts. After a reboot, delay the Factorio server's start by
about 30 seconds.

To check it's working, `docker compose logs -f events-server-syslog` while someone plays. If the
token wasn't signed with the `jwt-secret`, you'll see `Could not decode JWT` there.

## 5. Send the existing map

New chunks, buildings and research are sent as they happen. To send what's already there, join the
game and run, in the chat console:

```
/kafkatorio prototypes
/kafkatorio chunks 10
```

* `prototypes` sends the tile and entity types. Run it first, and again after changing mods.
* `chunks <radius>` sends the terrain, resources and buildings of every chunk within `<radius>`
  chunks of your position (on every surface). Each chunk is 32 tiles. Start small, and increase it
  until the whole map is on the website. Large radiuses (e.g. `chunks 30`) take minutes to process,
  and need the most memory.

Research is sent every minute. Production statistics are sent every minute, but only while the
game is running: if the server pauses when nobody's online (`auto_pause`), there are no statistics
then.

## 6. Icons and names

The building icons on the map, the production page and the research page need icons, building
sizes, in-game names and the technology tree. They're exported from a Factorio client with the
same mods as the server (they're not in this repository, since they belong to Wube and the mod
authors). Without them, the map has no building icons, and those pages show internal names, or
nothing.

On a computer with Factorio, with the game closed:

```shell
factorio --dump-data
factorio --dump-prototype-locale
factorio --dump-icon-sprites
python3 docs/kafkatorio-platform/building-icons/export_building_icons.py ~/.factorio/script-output ./icons
scp -r ./icons <docker host>:<kafkatorio-platform directory>/
```

* With Steam, run `SteamAppId=427520 factorio --dump-icon-sprites`, or Steam restarts the game
  normally instead.
* `factorio` is the game's executable, e.g. `~/.steam/steam/steamapps/common/Factorio/bin/x64/factorio`.
  `~/.factorio/script-output` is the Linux location; on Windows it's `%APPDATA%\Factorio\script-output`.
* If [Pillow](https://pypi.org/project/pillow/) is installed, the (large) technology icons are
  shrunk.

The website uses them straight away, but browsers keep icons for up to a day, so reload with
Ctrl+Shift+R. Export them again when the mods change.

## 7. Put it on the internet

Point a reverse proxy (Nginx Proxy Manager, Caddy, Traefik, ...) at port `12081`, and **enable
WebSocket support** - otherwise the website loads, but players and other live updates don't move.
The website, the API (`/kafkatorio/`) and the WebSocket (`/kafkatorio/ws`) are all on that one
port.

Don't expose the other ports. Kafka and the Kafka UI have no authentication.

## Unraid

[`kafkatorio-platform/unraid`](./kafkatorio-platform/unraid) has a setup for running Kafkatorio
on the same Unraid server as the Factorio server. It's the same as the example above, but keeps
the data in `appdata`, and doesn't run a Factorio server.

1. Install the *Compose Manager* plugin (or *Compose Manager Plus*) from the Apps tab.
2. Build the images, and copy them to Unraid ([step 1](#1-build-the-docker-images-and-the-mod)).
3. Create `/mnt/user/appdata/kafkatorio-platform/` containing
   * `.secret.kafkatorio-config.yml` ([step 2](#2-configure-kafkatorio))
   * `config/kafka-server.properties` (copy from [`kafkatorio-platform/config`](./kafkatorio-platform/config))
   * `config/cluster_id` ([step 2](#2-configure-kafkatorio))
4. Create a Compose Manager stack, with the Unraid
   [`docker-compose.yml`](./kafkatorio-platform/unraid/docker-compose.yml), and
   [`example.env`](./kafkatorio-platform/unraid/example.env) as its `.env`.
   Set `KAFKA_DATA_DIR` to a direct disk or pool path (e.g. `/mnt/cache/...` or `/mnt/disk1/...`),
   not `/mnt/user/...`: Unraid's FUSE layer is slow for Kafka. Start the stack.
5. Edit the Factorio container, enable *Advanced View*, and set *Extra Parameters* to

   ```
   --log-driver=syslog --log-opt syslog-address=tcp://127.0.0.1:12035 --log-opt syslog-format=rfc5424 --log-opt mode=non-blocking --log-opt tag=<KAFKATORIO_TOKEN>
   ```

   On the Docker tab, give the Factorio container an autostart *wait* of about 30 seconds, so
   Kafkatorio starts first.
6. Continue from [step 4](#4-connect-the-factorio-server) (installing the mod). Put the icons in
   `/mnt/user/appdata/kafkatorio-platform/icons`.

## Updating

1. Pull the changes, and rebuild the images and the mod ([step 1](#1-build-the-docker-images-and-the-mod)).
   If the version in `build.gradle.kts` changed, update `KAFKATORIO_VERSION` in `.env`.
2. `docker compose up -d` recreates the containers with the new images. The map and other data are
   kept.
3. If the mod changed, replace it on the server and give players the new zip. Factorio clients and
   the server must have exactly the same mod. Close the game before replacing the zip in your own
   `mods` directory.

Restart all the Kafkatorio services together when updating, not only some of them:
`events-server-syslog` reads and re-writes every packet, so it drops anything it doesn't know
about yet.

## Troubleshooting

**The website shows "Loading servers…" forever, or a new page shows the landing page.** Reload
without the cache (Ctrl+Shift+R). Older versions of the website could be cached by browsers after
an update.

**The website loads, but nothing moves.** Check WebSocket support is enabled in the reverse proxy
([step 7](#7-put-it-on-the-internet)).

**Nothing appears on the map.**
* Check the server's log reaches Kafkatorio: `docker compose logs events-server-syslog`.
  `Could not decode JWT` means the token wasn't signed with the `jwt-secret`.
* Log lines that Kafkatorio rejects go to the `kafkatorio.src.server-log.dlq` topic, with the
  reason (look at it in the Kafka UI). `no server ID for token` means the token name (`sub`) isn't
  in `kafkatorio-servers`.
* Run `/kafkatorio prototypes` and `/kafkatorio chunks 5` in game ([step 5](#5-send-the-existing-map)).
* After a large `chunks` command, processing takes minutes. The Kafka UI (`http://localhost:18090`
  on the Docker host) shows the topics, and how far behind the processors are.

**The Factorio container won't start: `failed to initialize logging driver`.** Kafkatorio's
syslog server isn't running yet. Start Kafkatorio first, wait a few seconds, then start the
Factorio container.

**Building icons, names, or the technology tree are missing.** Export the icons and names
([step 6](#6-icons-and-names)) with the same mods as the server.

## Developing

* `./gradlew build` builds and tests everything.
* [`modules/modules.md`](../modules/modules.md) shows how the modules depend on each other.
* The packets are defined in [`events-library`](../modules/events-library). Changing them
  regenerates the mod's TypeScript types
  (`modules/events-mod/src/main/typescript/generated/kafkatorio-schema.ts`).
* For the website, `./gradlew :modules:web-map:browserDevelopmentRun --continuous` serves it on
  `http://localhost:3000`. It forwards API requests to `events-server-web` on `localhost:12080`, so
  add `ports: [ "12080:80" ]` to `events-server-web` in your local `docker-compose.yml`. (Icons
  aren't forwarded.)
