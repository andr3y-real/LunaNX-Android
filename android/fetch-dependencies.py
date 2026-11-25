import hashlib
import os
from pathlib import Path
import shutil
import tarfile
import urllib.request

root = Path(__file__).resolve().parent / "build"
deps = root / "deps"

downloads = (
    (root / "android.jar", "https://raw.githubusercontent.com/Sable/android-platforms/master/android-35/android.jar",
     "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b", None),
    (deps / "curl.tar.gz", "https://curl.se/download/curl-8.17.0.tar.gz",
     "e8e74cdeefe5fb78b3ae6e90cd542babf788fa9480029cfcee6fd9ced42b7910", "curl-8.17.0"),
    (deps / "jansson.tar.gz", "https://codeload.github.com/akheron/jansson/tar.gz/refs/tags/v2.14.1",
     "979210eaffdffbcf54cfc34d047fccde13f21b529a381df26db871d886f729a4", "jansson-2.14.1"),
    (deps / "opus.tar.gz", "https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz",
     "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1", "opus-1.5.2"),
)

deps.mkdir(parents=True, exist_ok=True)
for path, url, sha256, source in downloads:
    if source is None and os.environ.get("ANDROID_JAR"):
        continue
    if not path.exists():
        temporary = path.with_suffix(".tmp")
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        temporary.replace(path)
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != sha256:
        raise SystemExit(f"Unexpected SHA-256 for {path}")
    if source and not (deps / source / "CMakeLists.txt").exists():
        with tarfile.open(path) as archive:
            archive.extractall(deps, filter="data")
