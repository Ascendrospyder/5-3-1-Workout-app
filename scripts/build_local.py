"""Offline APK build using cached Kotlin and the installed Android SDK.

Does not install on a device or modify the Gradle setup. Reuses the existing
debug keystore so the APK can update earlier debug builds from this machine.
"""
from pathlib import Path
import datetime
import os
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
tools = sorted((SDK / "build-tools").iterdir(), key=lambda p: tuple(int(n) for n in p.name.split(".")))[-1]
gradle = next((Path(os.environ["USERPROFILE"]) / ".gradle/wrapper/dists/gradle-8.9-bin").glob("*/gradle-8.9/lib"))
keystore = Path(os.environ["USERPROFILE"]) / ".android/debug.keystore"
if not keystore.is_file():
    raise SystemExit("Existing debug.keystore is required; refusing to generate a different signing key.")
work = ROOT / "app/build/local" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
work.mkdir(parents=True)
android = SDK / "platforms/android-35/android.jar"
stdlib = gradle / "kotlin-stdlib-1.9.23.jar"

def run(*args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)

sources = list((ROOT / "app/src/main/java").rglob("*.kt"))
classes = work / "classes"
run("java", "-cp", str(gradle / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath",
    os.pathsep.join(map(str, [stdlib, android])), "-d", classes, *sources)
tests = list((ROOT / "app/src/test/java").rglob("*.kt"))
test_classes = work / "test-classes"
junit = gradle / "junit-4.13.2.jar"
hamcrest = gradle / "hamcrest-core-1.3.jar"
run("java", "-cp", str(gradle / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath",
    os.pathsep.join(map(str, [classes, stdlib, android, junit, hamcrest])), "-d", test_classes, *tests)
run("java", "-cp", os.pathsep.join(map(str, [classes, test_classes, stdlib, junit, hamcrest])),
    "org.junit.runner.JUnitCore", "com.example.fivethreeonelifter.ProgramMathTest",
    "com.example.fivethreeonelifter.WorkoutDefaultsTest",
    "com.example.fivethreeonelifter.AppPaletteTest")
ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
manifest.getroot().set("package", "com.example.fivethreeonelifter")
manifest_path = work / "AndroidManifest.xml"
manifest.write(manifest_path, encoding="utf-8", xml_declaration=True)
gradle_config = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
version = re.search(r'versionCode\s*=\s*(\d+)', gradle_config).group(1)
name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_config).group(1)
resources = work / "resources.zip"
unsigned = work / "unsigned.apk"
run(tools / "aapt2.exe", "compile", "--dir", ROOT / "app/src/main/res", "-o", resources)
run(tools / "aapt2.exe", "link", "-I", android, "--manifest", manifest_path,
    "--min-sdk-version", "26", "--target-sdk-version", "35", "--version-code", version,
    "--version-name", name, "--debug-mode", "-o", unsigned, resources)
jar = work / "classes.jar"
with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in classes.rglob("*"):
        if path.is_file():
            archive.write(path, path.relative_to(classes).as_posix())
dex = work / "dex"
dex.mkdir()
run("java", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8",
    "--min-api", "26", "--lib", android, "--output", dex, jar, stdlib)
with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
    for path in dex.glob("*.dex"):
        archive.write(path, path.name)
aligned = work / "aligned.apk"
run(tools / "zipalign.exe", "-f", "4", unsigned, aligned)
apk = work / f"LiftLog-{name}-debug.apk"
run("java", "-jar", tools / "lib/apksigner.jar", "sign", "--ks", keystore,
    "--ks-pass", "pass:android", "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey",
    "--out", apk, aligned)
run("java", "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", apk)
print(f"APK: {apk}")
