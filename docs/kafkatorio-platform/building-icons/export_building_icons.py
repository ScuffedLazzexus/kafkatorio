#!/usr/bin/env python3
"""
Export building icons and metadata for the web map's "Building icons" layer.

The icons belong to Wube and the mod authors, so they're not in this repo. Instead, export them
from a Factorio client that has the same mods as the server:

    factorio --dump-data              # script-output/data-raw-dump.json
    factorio --dump-prototype-locale  # script-output/*-locale.json
    factorio --dump-icon-sprites      # script-output/entity/*.png, recipe/*.png, technology/*.png, ...

(For a Steam install, set `SteamAppId=427520` so that Steam doesn't relaunch the game.)

Then run

    export_building_icons.py ~/.factorio/script-output <output-dir>

and mount <output-dir> in the web-map container at /usr/share/nginx/icons.

The output contains
  - buildings.json: {"<entity name>": {"w": <tiles>, "h": <tiles>, "name": "<display name>"}}
  - entity/<entity name>.png
  - recipes.json: {"<recipe name>": "<display name>"}
  - resources.json: {"<resource name>": "<display name>"}
  - recipe/<recipe name>.png
  - technologies.json: the technology tree, for the research page
  - technology/<technology name>.png
  - items.json: {"<item name>": "<display name>"}, for the research and production pages
  - item/<item name>.png
  - fluids.json: {"<fluid name>": "<display name>"}, for the production page
  - fluid/<fluid name>.png
"""

import json
import math
import shutil
import sys
from pathlib import Path

try:
    from PIL import Image  # optional, to shrink the (large) technology icons
except ImportError:
    Image = None

# technology icons are 256px, but the research page shows them much smaller
TECHNOLOGY_ICON_SIZE = 128


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

    # recipes, shown on crafting machines
    recipe_names = json.loads((script_output / "recipe-locale.json").read_text()).get("names", {})
    (output_dir / "recipe").mkdir(exist_ok=True)
    recipes = {}
    for icon in (script_output / "recipe").glob("*.png"):
        recipes[icon.stem] = recipe_names.get(icon.stem, icon.stem)
        shutil.copyfile(icon, output_dir / "recipe" / icon.name)
    (output_dir / "recipes.json").write_text(json.dumps(recipes, indent=1, sort_keys=True))

    # resource names, for the resource patch labels
    resources = {name: names.get(name, name) for name in data_raw.get("resource", {})}
    (output_dir / "resources.json").write_text(json.dumps(resources, indent=1, sort_keys=True))

    technologies = export_technologies(script_output, data_raw, output_dir)
    items = export_icons_and_names(script_output, output_dir, "item")
    fluids = export_icons_and_names(script_output, output_dir, "fluid")

    print(f"exported {len(buildings)} buildings, {len(recipes)} recipes, {len(resources)} resources,"
          f" {len(technologies)} technologies, {len(items)} items and {len(fluids)} fluids to {output_dir}")


def as_list(value) -> list:
    """Factorio dumps empty lists as empty objects, `{}`"""
    return value if isinstance(value, list) else []


def copy_icon(source: Path, target: Path, max_size: int):
    """Copy an icon, shrinking it to max_size if Pillow is installed."""
    if Image is None:
        shutil.copyfile(source, target)
        return
    with Image.open(source) as image:
        if image.width > max_size:
            image = image.resize((max_size, max_size * image.height // image.width), Image.LANCZOS)
        image.save(target, optimize=True)


def export_technologies(script_output: Path, data_raw: dict, output_dir: Path) -> dict:
    """The technology tree, with the cost of each technology, and icons."""
    tech_names = json.loads((script_output / "technology-locale.json").read_text()).get("names", {})

    (output_dir / "technology").mkdir(exist_ok=True)

    technologies = {}

    for name, tech in data_raw.get("technology", {}).items():
        if tech.get("hidden"):
            continue
        unit = tech.get("unit")
        max_level = tech.get("max_level")
        technology = {
            "name": tech_names.get(name, name),
            "prerequisites": as_list(tech.get("prerequisites")),
            "order": tech.get("order", ""),
            "unlocks": [e["recipe"] for e in as_list(tech.get("effects")) if e.get("type") == "unlock-recipe"],
        }
        if unit:
            ingredients = [list(i.values()) if isinstance(i, dict) else i for i in as_list(unit.get("ingredients"))]
            technology["cost"] = {
                "count": unit.get("count"),
                # e.g. "2^(L-6)*1000", for technologies with levels
                "countFormula": unit.get("count_formula"),
                "time": unit.get("time"),
                "ingredients": [[pack, amount] for pack, amount in ingredients],
            }
            if tech.get("ignore_tech_cost_multiplier"):
                technology["cost"]["ignoreMultiplier"] = True
        trigger = tech.get("research_trigger")
        if trigger:
            technology["trigger"] = trigger
        if max_level is not None:
            technology["maxLevel"] = max_level  # a number, or "infinite"
        if tech.get("upgrade"):
            technology["upgrade"] = True
        # technologies with levels are split up (e.g. mining-productivity-1, -2, ...); the level of
        # the first one is in its name
        level = name.rsplit("-", 1)[-1]
        if level.isdigit():
            technology["level"] = int(level)
        if tech.get("enabled") is False:
            technology["disabled"] = True
        technologies[name] = technology

        icon = script_output / "technology" / f"{name}.png"
        if icon.exists():
            copy_icon(icon, output_dir / "technology" / icon.name, TECHNOLOGY_ICON_SIZE)

    (output_dir / "technologies.json").write_text(json.dumps(technologies, indent=1, sort_keys=True))
    return technologies


def export_icons_and_names(script_output: Path, output_dir: Path, kind: str) -> dict:
    """All icons of a kind (e.g. item, fluid) with their display names, as <kind>s.json"""
    names = json.loads((script_output / f"{kind}-locale.json").read_text()).get("names", {})
    (output_dir / kind).mkdir(exist_ok=True)
    exported = {}
    for icon in sorted((script_output / kind).glob("*.png")):
        exported[icon.stem] = names.get(icon.stem, icon.stem)
        shutil.copyfile(icon, output_dir / kind / icon.name)
    (output_dir / f"{kind}s.json").write_text(json.dumps(exported, indent=1, sort_keys=True))
    return exported


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(f"usage: {sys.argv[0]} <factorio script-output dir> <output dir>")
    main(Path(sys.argv[1]).expanduser(), Path(sys.argv[2]).expanduser())
