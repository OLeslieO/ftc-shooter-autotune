import hashlib
import io
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile


repository = Path(sys.argv[1])
version = sys.argv[2]
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
group = "io.github.oleslieo"
artifacts = {"shooter-autotune": "aar", "shooter-autotune-core": "jar"}
for artifact, extension in artifacts.items():
    directory = repository / "io/github/oleslieo" / artifact / version
    stem = f"{artifact}-{version}"
    for suffix in (f".{extension}", "-sources.jar", ".pom", ".module"):
        path = directory / (stem + suffix)
        if not path.is_file() or path.stat().st_size == 0:
            raise SystemExit(f"Missing or empty publication: {path}")
        for algorithm in ("sha1", "md5"):
            expected = path.with_name(path.name + "." + algorithm).read_text().strip()
            actual = hashlib.new(algorithm, path.read_bytes()).hexdigest()
            if actual != expected:
                raise SystemExit(f"Checksum mismatch: {path}")
    pom = ET.parse(directory / (stem + ".pom")).getroot()
    for field, expected in (("groupId", group), ("artifactId", artifact), ("version", version)):
        if pom.findtext("m:" + field, namespaces=namespace) != expected:
            raise SystemExit(f"Incorrect {field} in {artifact} POM")
    module = json.loads((directory / (stem + ".module")).read_text())
    if any(module["component"].get(key) != value for key, value in
           (("group", group), ("module", artifact), ("version", version))):
        raise SystemExit(f"Incorrect Gradle module coordinates: {artifact}")
    dependencies = pom.findall("m:dependencies/m:dependency", namespace)
    if artifact == "shooter-autotune":
        expected_dependency = (group, "shooter-autotune-core", version)
        actual_dependencies = [tuple(dependency.findtext("m:" + field, namespaces=namespace)
                                     for field in ("groupId", "artifactId", "version"))
                               for dependency in dependencies]
        if actual_dependencies != [expected_dependency]:
            raise SystemExit(f"Incorrect AAR transitive dependency: {actual_dependencies}")
        for variant in module["variants"]:
            if variant.get("attributes", {}).get("org.gradle.category") == "library":
                published_dependencies = variant.get("dependencies", [])
                if len(published_dependencies) != 1:
                    raise SystemExit("AAR module must depend only on the core library")
                dependency = published_dependencies[0]
                if (dependency["group"], dependency["module"], dependency["version"]["requires"]) != expected_dependency:
                    raise SystemExit("Gradle module references incorrect core coordinates")
        with zipfile.ZipFile(directory / (stem + ".aar")) as archive:
            for asset in ("index.html", "app.js", "style.css"):
                archive.getinfo("assets/shooter-autotune/" + asset)
            with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
                classes.getinfo("org/ftc/shooter/tuner/ftc/ShooterAutoTuneOpMode.class")
    elif dependencies:
        raise SystemExit("Core must remain hardware-independent")
    metadata = ET.parse(directory.parent / "maven-metadata.xml").getroot()
    if version not in [entry.text for entry in metadata.findall("versioning/versions/version")]:
        raise SystemExit(f"Version missing from Maven metadata: {artifact}")
print(f"Verified both Maven publications at {version}")
