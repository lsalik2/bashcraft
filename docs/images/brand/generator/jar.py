"""Reads vanilla textures and font data straight out of the Minecraft client jar (nothing is copied into the repo)."""
import glob
import json
import os
import zipfile

from png import decode

JAR_GLOB = ".gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.3/minecraft-merged-*-26.3.jar"


def find_jar(project_dir):
    jars = sorted(glob.glob(os.path.join(project_dir, JAR_GLOB)))
    if not jars:
        raise FileNotFoundError("no Minecraft 26.3 jar in Loom's cache; run ./gradlew build once, or pass the jar path")
    return jars[0]


class Assets:
    def __init__(self, jar_path):
        self.zip = zipfile.ZipFile(jar_path)

    def texture(self, name):
        """A texture under assets/minecraft/textures, e.g. 'block/dirt'."""
        return decode(self.zip.read(f"assets/minecraft/textures/{name}.png"))

    def json(self, name):
        return json.loads(self.zip.read(f"assets/minecraft/{name}.json"))
