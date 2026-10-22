"""Render the canonical C4 model as offline SVGs and a local HTML viewer.

Uses Python's standard library only. Reads architecture metadata and checks source
paths; never reads environment files, databases, or application data.
"""

from __future__ import annotations

import argparse
import heapq
import html
from html.parser import HTMLParser
import json
import math
from pathlib import Path
import re
import textwrap
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
STEP = 10
STYLES = {
    "person": ("#fff7cf", "#123458", "#123458"),
    "system": ("#123458", "#123458", "#ffffff"),
    "container": ("#22396f", "#123458", "#ffffff"),
    "component": ("#edf3fc", "#22396f", "#17324d"),
    "external": ("#f2f4f7", "#7a828e", "#17324d"),
    "database": ("#123458", "#123458", "#ffffff"),
    "artifact": ("#fff7cf", "#8a6500", "#17324d"),
}


def validate(model):
    diagram_ids = set()
    for diagram in model["diagrams"]:
        identity = diagram["id"]
        if not re.fullmatch(r"[a-z0-9-]+", identity) or identity in diagram_ids:
            raise ValueError(f"Invalid/duplicate diagram id: {identity}")
        diagram_ids.add(identity)
        nodes = {node["id"]: node for node in diagram["nodes"]}
        if len(nodes) != len(diagram["nodes"]):
            raise ValueError(f"Duplicate node id in {identity}")
        for node in nodes.values():
            if node["kind"] not in STYLES:
                raise ValueError(f"Invalid kind in {identity}: {node['kind']}")
            if not (0 <= node["x"] and 70 <= node["y"]
                    and node["x"] + node["width"] <= diagram["width"]
                    and node["y"] + node["height"] <= diagram["height"]):
                raise ValueError(f"Node outside canvas: {identity}/{node['id']}")
            for source in node.get("sources", []):
                path = (ROOT / source).resolve()
                if not path.is_relative_to(ROOT) or not path.exists():
                    raise ValueError(f"Missing/outside repository source: {source}")
        boxes = list(nodes.values())
        for index, first in enumerate(boxes):
            for second in boxes[index + 1:]:
                if overlaps(first, second):
                    raise ValueError(f"Overlapping nodes: {identity}/{first['id']}/{second['id']}")
        for relation in diagram["edges"]:
            if relation["from"] not in nodes or relation["to"] not in nodes or not relation["label"]:
                raise ValueError(f"Invalid relation in {identity}")
        for node in nodes.values():
            if node["kind"] == "component" and not any(
                boundary["x"] <= node["x"] and boundary["y"] <= node["y"]
                and node["x"] + node["width"] <= boundary["x"] + boundary["width"]
                and node["y"] + node["height"] <= boundary["y"] + boundary["height"]
                for boundary in diagram.get("boundaries", [])
            ):
                raise ValueError(f"Component outside its boundary: {identity}/{node['id']}")


def overlaps(a, b):
    return (a["x"] < b["x"] + b["width"] and b["x"] < a["x"] + a["width"]
            and a["y"] < b["y"] + b["height"] and b["y"] < a["y"] + a["height"])


def anchor(node, other):
    x, y, w, h = (node[key] for key in ("x", "y", "width", "height"))
    dx = other["x"] + other["width"] / 2 - (x + w / 2)
    dy = other["y"] + other["height"] / 2 - (y + h / 2)
    if abs(dx) >= abs(dy):
        return ((x + w if dx >= 0 else x, y + h / 2), (1 if dx >= 0 else -1, 0))
    return ((x + w / 2, y + h if dy >= 0 else y), (0, 1 if dy >= 0 else -1))


def blocked_cells(nodes):
    cells = set()
    for node in nodes:
        for x in range(math.ceil((node["x"] - 14) / STEP), math.floor((node["x"] + node["width"] + 14) / STEP) + 1):
            for y in range(math.ceil((node["y"] - 14) / STEP), math.floor((node["y"] + node["height"] + 14) / STEP) + 1):
                cells.add((x, y))
    return cells


def route(source, target, blocked, reserved, width, height):
    start_anchor, start_direction = anchor(source, target)
    end_anchor, end_direction = anchor(target, source)
    start_lead = tuple(start_anchor[i] + start_direction[i] * 28 for i in range(2))
    end_lead = tuple(end_anchor[i] + end_direction[i] * 28 for i in range(2))
    start, end = (tuple(round(value / STEP) for value in point) for point in (start_lead, end_lead))
    queue = [(0, start)]
    costs, parents = {start: 0}, {}
    limits = (width // STEP, height // STEP)
    while queue:
        _, current = heapq.heappop(queue)
        if current == end:
            break
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            candidate = (current[0] + dx, current[1] + dy)
            if candidate in blocked or not (2 <= candidate[0] <= limits[0] - 2 and 8 <= candidate[1] <= limits[1] - 2):
                continue
            cost = costs[current] + 1 + (0.6 if candidate in reserved else 0)
            previous = parents.get(current)
            if previous and (current[0] - previous[0], current[1] - previous[1]) != (dx, dy):
                cost += 0.25
            if cost < costs.get(candidate, math.inf):
                costs[candidate], parents[candidate] = cost, current
                heuristic = abs(candidate[0] - end[0]) + abs(candidate[1] - end[1])
                heapq.heappush(queue, (cost + heuristic, candidate))
    if end not in costs:
        raise ValueError(f"No unobstructed route: {source['id']} -> {target['id']}")
    cells = [end]
    while cells[-1] != start:
        cells.append(parents[cells[-1]])
    cells.reverse()
    reserved.update(cells)
    raw = [start_anchor, start_lead, *[(x * STEP, y * STEP) for x, y in cells], end_lead, end_anchor]
    points = []
    for point in raw:
        if points and point == points[-1]:
            continue
        if len(points) >= 2:
            a, b = points[-2:]
            if (b[0] - a[0]) * (point[1] - b[1]) == (b[1] - a[1]) * (point[0] - b[0]):
                points.pop()
        points.append(point)
    return points


def lines(text, width):
    return textwrap.wrap(text, width, break_long_words=False, break_on_hyphens=False) or [""]


def text_at(x, y, text, size=14, fill="#17324d", bold=False, anchor="start"):
    return (f'<text x="{x}" y="{y}" font-size="{size}" fill="{fill}"'
            f' font-weight="{600 if bold else 400}" text-anchor="{anchor}">{html.escape(text)}</text>')


def render_svg(diagram):
    identity = diagram["id"]
    nodes = {node["id"]: node for node in diagram["nodes"]}
    rows = math.ceil(len(diagram["edges"]) / 2)
    notes = [line for note in diagram.get("notes", []) for line in lines(note, int((diagram["width"] - 100) / 7))]
    total_height = diagram["height"] + 100 + rows * 65 + len(notes) * 22
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {diagram["width"]} {total_height}" width="{diagram["width"]}" height="{total_height}" role="img" aria-labelledby="{identity}-title {identity}-desc">',
             f'<title id="{identity}-title">{html.escape(diagram["title"])}</title>',
             f'<desc id="{identity}-desc">{html.escape(diagram["scope"])} Les relations numérotées sont détaillées sous le diagramme.</desc>',
             f'<defs><marker id="{identity}-arrow" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto"><path d="M0 0L10 4L0 8Z" fill="#59616d"/></marker></defs>',
             '<g font-family="Arial, sans-serif">',
             f'<rect width="{diagram["width"]}" height="{total_height}" fill="#ffffff"/>',
             text_at(30, 38, diagram["title"], 26, "#123458", True),
             text_at(30, 63, "Types explicites · flèches = sens de l'appel / du flux · pointillés = option ou étape hors ligne", 13)]
    for boundary in diagram.get("boundaries", []):
        parts.append(f'<rect x="{boundary["x"]}" y="{boundary["y"]}" width="{boundary["width"]}" height="{boundary["height"]}" rx="12" fill="none" stroke="#9aa7b8" stroke-width="2" stroke-dasharray="8 5"/>')
        parts.append(text_at(boundary["x"] + 15, boundary["y"] + 24, boundary["title"], 15, "#59616d", True))
    blocked, reserved = blocked_cells(nodes.values()), set()
    badges = []
    for index, relation in enumerate(diagram["edges"], 1):
        points = route(nodes[relation["from"]], nodes[relation["to"]], blocked, reserved, diagram["width"], diagram["height"])
        path = "M" + " L".join(f"{x:g} {y:g}" for x, y in points)
        dashed = ' stroke-dasharray="7 5"' if relation.get("dashed") else ""
        parts.append(f'<g data-edge="{index}"><title>{html.escape(relation["label"])}</title><path d="{path}" fill="none" stroke="#59616d" stroke-width="2" stroke-linejoin="round" marker-end="url(#{identity}-arrow)"{dashed}/></g>')
        # Numbered labels avoid covering component text in the more detailed views.
        a, b = max(zip(points[:-1], points[1:]), key=lambda pair: abs(pair[0][0] - pair[1][0]) + abs(pair[0][1] - pair[1][1]))
        badges.append(((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, index))
    for node in nodes.values():
        fill, stroke, foreground = STYLES[node["kind"]]
        x, y, w, h = (node[key] for key in ("x", "y", "width", "height"))
        parts.append(f'<g data-node="{html.escape(node["id"], quote=True)}"><title>{html.escape(node["title"] + ": " + node["description"] + " | " + "; ".join(node.get("sources", [])))}</title>')
        parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{24 if node["kind"] == "person" else 10}" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')
        if node["kind"] == "database":
            parts.append(f'<path d="M{x} {y + 16} Q{x + w / 2} {y + 40} {x + w} {y + 16}" fill="none" stroke="#dbe4f5"/>')
        if node["kind"] == "person":
            parts.append(f'<circle cx="{x + w - 24}" cy="{y + 23}" r="8" fill="#123458"/>')
        cursor = y + 29
        for line in lines(node["title"], 28):
            parts.append(text_at(x + 16, cursor, line, 18, foreground, True)); cursor += 22
        parts.append(text_at(x + 16, cursor + 1, f'[{node["type"]}]', 12, foreground)); cursor += 20
        if node["technology"]:
            for line in lines(node["technology"], 39):
                parts.append(text_at(x + 16, cursor, line, 13, foreground, True)); cursor += 17
        cursor += 7
        for line in lines(node["description"], 39):
            parts.append(text_at(x + 16, cursor, line, 13, foreground)); cursor += 17
        if cursor - 17 > y + h - 8:
            raise ValueError(f"Text overflow in {diagram['id']}/{node['id']}")
        parts.append('</g>')
    for x, y, index in badges:
        parts.append(f'<circle cx="{x:g}" cy="{y:g}" r="12" fill="#ffffff" stroke="#59616d"/>')
        parts.append(text_at(x, y + 4, str(index), 12, "#123458", True, "middle"))
    footer = diagram["height"] + 32
    parts.append(text_at(30, footer, "Relations et protocoles (numéros sur les flèches)", 18, "#123458", True))
    columns = (30, diagram["width"] / 2 + 15)
    for index, relation in enumerate(diagram["edges"]):
        column, row = divmod(index, rows)
        label = f'{index + 1}. {nodes[relation["from"]]["title"]} → {nodes[relation["to"]]["title"]} : {relation["label"]}'
        if relation.get("dashed"):
            label += " (pointillés)"
        for offset, line in enumerate(lines(label, int((diagram["width"] / 2 - 55) / 7.1))):
            if offset >= 3:
                raise ValueError(f"Relation legend overflow: {diagram['id']}/{index + 1}")
            parts.append(text_at(columns[column], footer + 30 + row * 65 + offset * 17, line, 13))
    y = footer + 40 + rows * 65
    for line in notes:
        parts.append(text_at(30, y, line, 13, "#59616d")); y += 22
    parts.extend(['</g>', '</svg>'])
    result = "\n".join(parts) + "\n"
    ET.fromstring(result)
    return result


def render_html(model, svgs):
    options, sections = [], []
    for index, diagram in enumerate(model["diagrams"]):
        identity, title = diagram["id"], html.escape(diagram["title"])
        options.append(f'<option value="{identity}">{title}</option>')
        sources = sorted({source for node in diagram["nodes"] for source in node.get("sources", [])})
        source_list = "".join(f'<li><code>{html.escape(source)}</code></li>' for source in sources)
        notes = "".join(f'<li>{html.escape(note)}</li>' for note in diagram.get("notes", []))
        sections.append(f'<section id="{identity}" class="diagram" {"hidden" if index else ""}><h2>{title}</h2><p>{html.escape(diagram["scope"])}</p><p><a href="{identity}.svg" download>Télécharger le SVG</a> · <a href="{identity}.svg" target="_blank" rel="noopener">Ouvrir le SVG seul</a></p><div class="viewport">{svgs[identity]}</div><ul>{notes}</ul><details><summary>Évidence dans le code</summary><ul>{source_list}</ul></details></section>')
    return '''<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Smart Financial Wellness Advisor — C4</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#fff;color:#17324d;font:16px/1.6 system-ui,sans-serif}main{max-width:1500px;padding:24px;margin:auto}h1{font-size:26px;color:#123458;margin:0 0 8px}h2{font-size:21px}p{max-width:100ch}a{color:#123458}header{border-bottom:3px solid #ffd400;padding-bottom:16px}.toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:12px;margin:20px 0}select,button{min-height:44px;border:1px solid #7a828e;border-radius:8px;padding:8px 12px;background:white;color:#123458;font:inherit}select{max-width:100%}button{cursor:pointer}:focus-visible{outline:3px solid #123458;outline-offset:3px}.viewport{overflow:auto;max-height:76vh;border:1px solid #dde1e7;border-radius:12px;background:white}.viewport>svg{display:block;width:100%;height:auto;max-width:none}.diagram[hidden]{display:none!important}summary{cursor:pointer;min-height:44px;padding:8px 0;font-weight:600}code{overflow-wrap:anywhere}footer{margin-top:24px;color:#59616d;font-size:14px}.legend{display:flex;flex-wrap:wrap;gap:8px 20px;font-size:14px}.legend span{border-left:5px solid #22396f;padding-left:8px}.legend .external{border-color:#7a828e}.legend .data{border-color:#8a6500}@media print{.toolbar,.diagram>p>a,header,footer,details{display:none}.viewport{max-height:none;overflow:visible;border:0}.viewport>svg{width:100%!important}main{padding:0}.diagram h2{margin-top:0}}@page{size:A3 landscape;margin:12mm}
</style></head><body><main><header><h1>Smart Financial Wellness Advisor — modèle C4</h1>
<p>Architecture inspectée le ''' + html.escape(model["date"]) + ''' : ''' + html.escape(model["scope"]) + ''' Vue de démonstration ; les fonctions IA optionnelles dépendent de la configuration et des modèles installés.</p>
<div class="legend"><span>Système / application : navy</span><span class="external">Système externe : gris</span><span class="data">Fichiers / artefacts : jaune pâle</span><span>Pointillés : option / étape hors ligne</span></div></header>
<nav class="toolbar" aria-label="Explorer les diagrammes"><label for="diagram-select">Diagramme</label><select id="diagram-select">''' + "".join(options) + '''</select><button id="zoom-in" type="button" aria-label="Agrandir le diagramme">Zoom +</button><button id="zoom-out" type="button" aria-label="Réduire le diagramme">Zoom −</button><button id="fit" type="button">Ajuster</button><output id="zoom-level" aria-live="polite">100 %</output><button type="button" id="print">Imprimer / PDF</button></nav>''' + "".join(sections) + '''<footer>Vue hors ligne : aucun CDN, requête réseau ou dépendance à Docker. Source canonique : model.json ; génération reproductible : render.py. Le niveau Code C4 n'est pas ajouté : ces vues de contexte, conteneurs, composants et déploiement décrivent les frontières utiles sans recopier chaque classe Java/Python.</footer></main>
<script>
const selector=document.getElementById('diagram-select');let zoom=100;
function scale(){document.querySelectorAll('.viewport>svg').forEach(svg=>svg.style.width=zoom+'%');document.getElementById('zoom-level').value=zoom+' %'}
function show(){const id=location.hash.slice(1);if([...selector.options].some(option=>option.value===id))selector.value=id;document.querySelectorAll('.diagram').forEach(section=>section.hidden=section.id!==selector.value);zoom=100;scale()}
selector.addEventListener('change',()=>{location.hash=selector.value;show()});window.addEventListener('hashchange',show);document.getElementById('zoom-in').onclick=()=>{zoom=Math.min(250,zoom+25);scale()};document.getElementById('zoom-out').onclick=()=>{zoom=Math.max(50,zoom-25);scale()};document.getElementById('fit').onclick=()=>{zoom=100;scale()};document.getElementById('print').onclick=()=>window.print();show();
</script></body></html>
'''


class ViewerCheck(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.svg_count = 0
        self.references = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if "id" in values:
            if values["id"] in self.ids:
                raise ValueError(f"Duplicate HTML/SVG id: {values['id']}")
            self.ids.add(values["id"])
        if tag == "svg":
            self.svg_count += 1
        if tag in {"script", "link", "img", "iframe"} and ("src" in values or "href" in values):
            raise ValueError("Viewer must not load external scripts, styles, or images")
        if "aria-labelledby" in values:
            self.references.extend(values["aria-labelledby"].split())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate and compare outputs without writing files")
    args = parser.parse_args()
    model = json.loads((HERE / "model.json").read_text(encoding="utf-8"))
    validate(model)
    svgs = {diagram["id"]: render_svg(diagram) for diagram in model["diagrams"]}
    outputs = {f"{identity}.svg": svg for identity, svg in svgs.items()}
    outputs["index.html"] = render_html(model, svgs)
    viewer = ViewerCheck()
    viewer.feed(outputs["index.html"])
    if viewer.svg_count != len(model["diagrams"]) or not set(viewer.references).issubset(viewer.ids):
        raise ValueError("Viewer has missing diagrams or accessible labels")
    for name, content in outputs.items():
        path = HERE / name
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                raise SystemExit(f"OUT OF DATE: {name}; run python docs/architecture/c4/render.py")
        else:
            path.write_text(content, encoding="utf-8", newline="\n")
    print(f"PASS: {len(model['diagrams'])} diagrams, {sum(len(d['nodes']) for d in model['diagrams'])} nodes, {sum(len(d['edges']) for d in model['diagrams'])} relations; sources/layout/XML/output consistency checked.")


if __name__ == "__main__":
    main()
