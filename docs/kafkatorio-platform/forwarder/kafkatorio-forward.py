#!/usr/bin/env python3
"""
Forward a Factorio server's output to Kafkatorio, for servers that don't run in Docker.

The kafkatorio-events mod prints its packets to the server's standard output. In Docker, the
syslog log driver sends them to Kafkatorio. This does the same for a server that runs directly on
a machine: it reads the server's output, prints it as usual, and sends the mod's packets to
Kafkatorio's syslog port, tagged with the server's token.

Run the server through it (recommended):

    KAFKATORIO_TOKEN=<token> kafkatorio-forward.py --host <kafkatorio host> -- \\
        ./bin/x64/factorio --start-server my-save.zip

or pipe the server's output into it:

    ./bin/x64/factorio --start-server my-save.zip | kafkatorio-forward.py --token-file token.txt

The server keeps running if Kafkatorio is down or restarts: packets are kept in memory (up to
--buffer lines) and sent when Kafkatorio is back. Ctrl+C or SIGTERM is passed to the server, so it
saves and stops as usual.

Only needs Python 3.8+, no extra packages.
"""

import argparse
import collections
import datetime
import os
import select
import signal
import socket
import subprocess
import sys
import threading
import time

PACKET_PREFIX = "KafkatorioPacket:::"


def log(message: str) -> None:
    print(f"[kafkatorio-forward] {message}", file=sys.stderr, flush=True)


class Sender(threading.Thread):
    """Sends queued lines to the syslog server, reconnecting when needed."""

    def __init__(self, host: str, port: int, token: str, buffer_size: int):
        super().__init__(name="kafkatorio-sender", daemon=True)
        self.address = (host, port)
        self.token = token
        self.hostname = socket.gethostname().split(".")[0] or "-"
        self.queue = collections.deque()
        self.buffer_size = buffer_size
        self.dropped = 0
        self.lock = threading.Condition()
        self.closing = False
        self.sock = None

    def add(self, line: str) -> None:
        with self.lock:
            if len(self.queue) >= self.buffer_size:
                self.queue.popleft()
                self.dropped += 1
                if self.dropped == 1 or self.dropped % 1000 == 0:
                    log(f"buffer full, dropped {self.dropped} oldest packets so far")
            self.queue.append(line)
            self.lock.notify()

    def close(self, timeout: float) -> None:
        """Send what's left, for up to `timeout` seconds."""
        with self.lock:
            self.closing = True
            self.lock.notify()
        self.join(timeout)
        with self.lock:
            if self.queue:
                log(f"stopped with {len(self.queue)} packets not sent")

    def format(self, line: str) -> bytes:
        # RFC 5424, like Docker's syslog log driver: the token goes in the APP-NAME field
        timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds")
        timestamp = timestamp.replace("+00:00", "Z")
        return f"<14>1 {timestamp} {self.hostname} {self.token} - - - {line}\n".encode("utf-8")

    def peer_closed(self) -> bool:
        """
        Kafkatorio never sends anything, so if the socket is readable, it has been closed.
        Without this check, lines written after Kafkatorio closes the connection (e.g. when it
        restarts) seem to be sent, but are lost.
        """
        try:
            readable, _, _ = select.select([self.sock], [], [], 0)
            return bool(readable) and self.sock.recv(1, socket.MSG_PEEK) == b""
        except OSError:
            return True

    def connect(self) -> None:
        delay = 1.0
        while self.sock is None:
            try:
                self.sock = socket.create_connection(self.address, timeout=10)
                self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                log(f"connected to {self.address[0]}:{self.address[1]}")
            except OSError as e:
                with self.lock:
                    if self.closing:
                        raise
                log(f"can't connect to {self.address[0]}:{self.address[1]} ({e}), retrying in {delay:.0f}s")
                time.sleep(delay)
                delay = min(delay * 2, 60)

    def run(self) -> None:
        while True:
            with self.lock:
                while not self.queue and not self.closing:
                    self.lock.wait()
                if not self.queue:
                    break  # closing, and everything is sent
                line = self.queue[0]
            try:
                if self.sock is not None and self.peer_closed():
                    raise ConnectionResetError("closed by Kafkatorio")
                self.connect()
                self.sock.sendall(self.format(line))
                with self.lock:
                    self.queue.popleft()
            except OSError as e:
                if self.sock is not None:
                    log(f"connection lost ({e}), reconnecting")
                    self.sock.close()
                    self.sock = None
                with self.lock:
                    if self.closing:
                        break
        if self.sock is not None:
            self.sock.close()


def read_token(args) -> str:
    if args.token_file:
        with open(args.token_file) as f:
            token = f.read().strip()
    else:
        token = os.environ.get("KAFKATORIO_TOKEN", "").strip()
    if not token:
        sys.exit("kafkatorio-forward: no token - set KAFKATORIO_TOKEN, or use --token-file")
    if any(c.isspace() for c in token):
        sys.exit("kafkatorio-forward: the token can't contain spaces")
    return token


def forward(stream, sender: Sender, forward_all: bool) -> None:
    """Print each line of the server's output, and queue the packets."""
    for raw in stream:
        line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
        try:
            print(line, flush=True)
        except BrokenPipeError:
            pass  # nobody's reading our output - keep forwarding anyway
        if forward_all or line.startswith(PACKET_PREFIX):
            sender.add(line)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Forward a Factorio server's output to Kafkatorio.",
        epilog="Put the server's command after `--`, or pipe its output in.",
    )
    parser.add_argument("--host", default="127.0.0.1", help="Kafkatorio's host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=12035, help="Kafkatorio's syslog port (default: 12035)")
    parser.add_argument("--token-file", help="a file containing the server's token (default: $KAFKATORIO_TOKEN)")
    parser.add_argument("--buffer", type=int, default=100_000,
                        help="packets to keep while Kafkatorio can't be reached (default: 100000)")
    parser.add_argument("--all", action="store_true", help="send every line, not only the mod's packets")
    parser.add_argument("command", nargs=argparse.REMAINDER, help="the Factorio server command")
    args = parser.parse_args()

    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    sender = Sender(args.host, args.port, read_token(args), args.buffer)
    sender.start()

    if not command:
        # Piped: Ctrl+C reaches the server too, which then stops and closes the pipe. Keep going
        # until then, so the last packets are sent.
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        forward(sys.stdin.buffer, sender, args.all)
        sender.close(timeout=10)
        return 0

    # In its own process group, so Ctrl+C only reaches the server once (passed on below), not
    # twice (from the terminal, and from us).
    server = subprocess.Popen(
        command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True,
    )

    def pass_on(signum, _frame):
        # Factorio saves and stops on SIGINT/SIGTERM
        server.send_signal(signum)

    signal.signal(signal.SIGINT, pass_on)
    signal.signal(signal.SIGTERM, pass_on)
    if hasattr(signal, "SIGHUP"):
        signal.signal(signal.SIGHUP, pass_on)

    forward(server.stdout, sender, args.all)
    code = server.wait()
    sender.close(timeout=10)
    return code


if __name__ == "__main__":
    sys.exit(main())
