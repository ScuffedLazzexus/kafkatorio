#!/usr/bin/env python3
"""
Export building icons and metadata for the web map's "Building icons" layer.

The icons belong to Wube and the mod authors, so they're not in this repo. Instead, export them
from a Factorio client that has the same mods as the server:

    factorio --dump-data              # script-output/data-raw-dump.json
    factorio --dump-prototype-locale  # script-output/entity-locale.json
    factorio --dump-icon-sprites      # script-output/entity/*.png

(For a Steam install, set `SteamAppId=427520` so that Steam doesn't relaunch the game.)

Then run

    export_building_icons.py ~/.factorio/script-output <output-dir>

and mount <output-dir> in the web-map container at /usr/share/nginx/icons.

The output contains
  - buildings.json: {"<entity name>": {"w": <tiles>, "h": <tiles>, "name": "<display name>"}}
  - entity/<entity name>.png
"""

import json
import math
import shutil
import sys
from pathlib import Path


def footprint(selection_box) -> tuple[int, int]:
    """The size in tiles, from a selection box like [[-1.5, -1.5], [1.5, 1.5]]"""
    (x0, y0), (x1, y1) = selection_box
    return max(1, math.ceil(x1 - x0 - 0.1)), max(1, math.ceil(y1 - y0 - 0.1))


def main(script_output: Path, output_dir: Path):
    data_raw = json.loads((script_output / "data-raw-dump.json").read_text())
    names = json.loads((script_output / "entity-locale.json").read_text()).get("names", {})
    icons_dir = script_output / "entity"

    (output_dir / "entity").mkdir(parents=True, exist_ok=True)

    buildings = {}
    for prototypes in data_raw.values():
        if not isinstance(prototypes, dict):
            continue
        for name, proto in prototypes.items():
            if not isinstance(proto, dict):
                continue
            # only things that players can build
            if "player-creation" not in proto.get("flags", []):
                continue
            selection_box = proto.get("selection_box")
            icon = icons_dir / f"{name}.png"
            if not selection_box or not icon.exists():
                continue

            w, h = footprint(selection_box)
            buildings[name] = {"w": w, "h": h, "name": names.get(name, name)}
            shutil.copyfile(icon, output_dir / "entity" / f"{name}.png")

    (output_dir / "buildings.json").write_text(json.dumps(buildings, indent=1, sort_keys=True))
    print(f"exported {len(buildings)} buildings to {output_dir}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(f"usage: {sys.argv[0]} <factorio script-output dir> <output dir>")
    main(Path(sys.argv[1]).expanduser(), Path(sys.argv[2]).expanduser())
