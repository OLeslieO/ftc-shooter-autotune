import argparse
from pathlib import Path
import shutil
import subprocess


parser = argparse.ArgumentParser(description="Export only the AutoTune library into a new local Git repository")
parser.add_argument("destination", type=Path)
arguments = parser.parse_args()
source = Path(__file__).resolve().parents[1]
destination = arguments.destination.resolve()
if destination.exists():
    raise SystemExit("Destination already exists; choose a new empty location")
if source == destination or source in destination.parents:
    raise SystemExit("Destination must be outside the source library directory")
entries = (
    ".gitignore", ".github", "README.md", "PUBLISHING.md", "build.gradle", "settings.gradle",
    "gradle.properties", "gradle", "gradlew", "gradlew.bat", "tuner-core", "ftc-library",
    "ftc-integration", "examples", "scripts",
)
for entry in entries:
    if not (source / entry).exists():
        raise SystemExit(f"Missing standalone file: {entry}")
destination.mkdir(parents=True)
for entry in entries:
    original = source / entry
    target = destination / entry
    if original.is_dir():
        shutil.copytree(original, target, ignore=shutil.ignore_patterns("build", ".gradle", "__pycache__", ".DS_Store"))
    else:
        shutil.copy2(original, target)
(destination / "gradlew").chmod(0o755)
subprocess.run(["git", "init", str(destination)], check=True)
print(f"Independent local repository: {destination}")
print("No commit, remote repository, or upload has been created. Follow PUBLISHING.md.")
