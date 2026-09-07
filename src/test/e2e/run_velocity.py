"""Real Velocity callback ingress, MySQL durability and process restart acceptance."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[3]
FIXTURES = Path(__file__).parent / "fixtures"
VELOCITY_SHA = "fe53021f3168322cb6cb68f78699866fd098df3c306e4359847a10b0d02689ef"
VELOCITY_URL = (
    f"https://fill-data.papermc.io/v1/objects/{VELOCITY_SHA}/"
    "velocity-3.4.0-SNAPSHOT-563.jar"
)
BASE_URL = "http://127.0.0.1:25876"
# Credentials belong only to the disposable CI database and callback listener.
SECRET = "proxyarc-disposable-e2e-callback"
PASSWORD = "proxyarc-disposable-e2e-mysql"


def request(path, body=None, content_type="application/x-www-form-urlencoded"):
    req = Request(BASE_URL + path, data=body, headers={"Content-Type": content_type})
    try:
        response = urlopen(req, timeout=15)
    except HTTPError as failure:
        response = failure
    with response:
        return response.status, response.read().decode()


def sql(statement):
    return subprocess.check_output([
        "docker", "exec", "-e", f"MYSQL_PWD={PASSWORD}",
        os.environ["E2E_MYSQL_CONTAINER"], "mysql", "-uproxyarc_e2e",
        "--batch", "--skip-column-names", "proxyarc_e2e", "-e", statement,
    ], text=True, timeout=15).strip()


def assert_pending_events(count):
    actual = sql("SELECT COUNT(*), SUM(reward_state = 'PENDING') FROM arc_votes_events")
    assert actual == f"{count}\t{count}", actual
    components = sql(
        "SELECT component_key, provider, COALESCE(currency_id, ''), "
        "SUM(amount), COUNT(*) FROM arc_votes_reward_components "
        "GROUP BY component_key, provider, currency_id ORDER BY component_key"
    )
    expected = (
        f"premium\tredis_economy\ttokens\t{3 * count}.00\t{count}\n"
        f"standard\tvault\t\t{1000 * count}.00\t{count}"
    )
    assert components == expected, (components, expected)


@contextmanager
def running_velocity(directory, environment, phase):
    log_path = directory / f"velocity-{phase}.log"
    with log_path.open("w") as log:
        process = subprocess.Popen([
            "java", "-Xms128M", "-Xmx512M", "-Dterminal.jline=false",
            "-jar", "velocity.jar",
        ], cwd=directory, env=environment, stdin=subprocess.PIPE, stdout=log,
            stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                assert process.poll() is None, f"Velocity exited: {log_path}\n{log_path.read_text()[-12000:]}"
                try:
                    if request("/callbacks/minecraft-rating") == (405, "method_not_allowed"):
                        break
                except (URLError, TimeoutError):
                    pass
                time.sleep(0.2)
            else:
                raise AssertionError(f"ProxyVotes did not become ready: {log_path}\n{log_path.read_text()[-12000:]}")
            yield
        finally:
            if process.poll() is None:
                try:
                    process.stdin.write("shutdown\n")
                    process.stdin.flush()
                    process.wait(timeout=20)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)


def main():
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="velocity-e2e-", dir=build))
    print(f"Velocity E2E artifacts: {directory}", flush=True)
    jar = ROOT / "build/libs/ProxyARC.jar"
    assert jar.is_file(), "Run ./gradlew shadowJar before this acceptance test"
    download = Request(VELOCITY_URL, headers={
        "User-Agent": "RusCrafting-E2E/1.0 (https://github.com/alexey-va/ProxyARC)",
    })
    with urlopen(download, timeout=60) as response:
        content = response.read()
    assert hashlib.sha256(content).hexdigest() == VELOCITY_SHA, "Velocity checksum mismatch"
    (directory / "velocity.jar").write_bytes(content)
    modules = directory / "plugins/proxyarc/modules"
    modules.mkdir(parents=True)
    shutil.copy(jar, directory / "plugins/ProxyARC.jar")
    shutil.copy(FIXTURES / "velocity.toml", directory / "velocity.toml")
    shutil.copy(FIXTURES / "votes.yml", modules / "votes.yml")
    for module in ("redis", "logging", "discord", "telegram", "portal-bridge"):
        (modules / f"{module}.yml").write_text("enabled: false\n")
    environment = os.environ | {
        "PROXYARC_E2E_MYSQL_PASSWORD": PASSWORD,
        "PROXYARC_E2E_CALLBACK_SECRET": SECRET,
    }
    timestamp = str(int(time.time()))
    nickname = "VelocityVoteE2E"
    signature = hashlib.sha1((nickname + timestamp + SECRET).encode()).hexdigest()
    form = urlencode({"username": nickname, "timestamp": timestamp, "signature": signature}).encode()
    with running_velocity(directory, environment, "first"):
        assert request("/callbacks/minecraft-rating", form) == (200, "ok")
        assert_pending_events(1)
        print("PASS real Velocity records signed callback and both pending currency components", flush=True)
        with ThreadPoolExecutor(max_workers=8) as workers:
            results = list(workers.map(lambda _: request("/callbacks/minecraft-rating", form), range(8)))
        assert results == [(200, "ok")] * 8, results
        assert_pending_events(1)
        bad_form = urlencode({"username": nickname, "timestamp": timestamp, "signature": "0" * 40}).encode()
        assert request("/callbacks/minecraft-rating", bad_form) == (403, "invalid_signature")
        assert_pending_events(1)
        print("PASS concurrent replay and invalid signature preserve one durable event", flush=True)

    with running_velocity(directory, environment, "restarted"):
        assert request("/callbacks/minecraft-rating", form) == (200, "ok")
        assert_pending_events(1)
        boundary = "ProxyArcE2EBoundary"
        fields = {"nick": nickname, "time": timestamp, "sign": signature}
        multipart = ("".join(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'
            for key, value in fields.items()
        ) + f"--{boundary}--\r\n").encode()
        assert request("/callbacks/hotmc", multipart, f"multipart/form-data; boundary={boundary}") == (200, "ok")
        assert_pending_events(2)
        print("PASS process restart preserves deduplication and HotMC records a separate source", flush=True)


if __name__ == "__main__":
    main()
